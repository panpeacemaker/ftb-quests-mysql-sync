import { createRequire } from 'node:module';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import sinon from 'sinon';

const require = createRequire(import.meta.url);
const axios = require('axios');
const { createApp } = require('./test-helper.js');

describe('web routes negative tests', () => {
  let app;
  let db;

  beforeEach(() => {
    app = createApp();
    // db.js is now in require cache after createApp() loaded routes.js
    db = require('./db.js');
    sinon.restore();
  });

  afterEach(() => {
    sinon.restore();
  });

  it('auth-missing: returns 401 when WOT reports not logged in', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: false } });
    const res = await request(app)
      .get('/api/agrarius/status')
      .set('Cookie', 'session=test');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('Login required');
  });

  it('auth-forbidden: returns 403 when role is not in AGRARIUS_ROLES', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: true, role: 'user', username: 'nobody' } });
    const res = await request(app)
      .get('/api/agrarius/status')
      .set('Cookie', 'session=test');
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('Agrarius admin access required');
  });

  it('auth-service-down: returns 503 when axios throws', async () => {
    sinon.stub(axios, 'get').rejects(new Error('network error'));
    const res = await request(app)
      .get('/api/agrarius/status')
      .set('Cookie', 'session=test');
    expect(res.status).toBe(503);
    expect(res.body.error).toBe('Auth check failed');
  });

  it('csrf-missing: returns 403 on POST /api/agrarius/reset without X-Requested-With', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: true, role: 'admin', username: 'admin' } });
    const res = await request(app)
      .post('/api/agrarius/reset')
      .set('Cookie', 'session=test')
      .send({ confirm: true });
    expect(res.status).toBe(403);
    expect(res.body.error).toBe('CSRF check failed');
  });

  it('confirm-missing: returns 400 when confirm is not true', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: true, role: 'admin', username: 'admin' } });
    const res = await request(app)
      .post('/api/agrarius/reset')
      .set('Cookie', 'session=test')
      .set('X-Requested-With', 'agrarius-admin')
      .send({ confirm: false });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('Confirm required');
  });

  it('rate-limit-login: returns 429 on the 6th login attempt', async () => {
    sinon.stub(axios, 'post').resolves({
      status: 200,
      data: { loggedIn: true, role: 'admin', username: 'admin' },
      headers: { 'set-cookie': ['session=test'] },
    });

    const agent = request.agent(app);
    for (let i = 0; i < 5; i++) {
      const res = await agent
        .post('/api/agrarius/login')
        .send({ username: 'admin', password: 'admin' });
      expect(res.status).toBe(200);
    }

    const res = await agent
      .post('/api/agrarius/login')
      .send({ username: 'admin', password: 'admin' });
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('Too many requests');
  });

  it('rate-limit-reset: returns 429 on the 31st reset attempt', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: true, role: 'admin', username: 'admin' } });

    const agent = request.agent(app);
    for (let i = 0; i < 30; i++) {
      const res = await agent
        .post('/api/agrarius/reset')
        .set('Cookie', 'session=test')
        .set('X-Requested-With', 'agrarius-admin')
        .send({ confirm: true, scope: 'INVALID', targetId: 'x', mode: 'FULL' });
      expect(res.status).toBe(400);
    }

    const res = await agent
      .post('/api/agrarius/reset')
      .set('Cookie', 'session=test')
      .set('X-Requested-With', 'agrarius-admin')
      .send({ confirm: true, scope: 'INVALID', targetId: 'x', mode: 'FULL' });
    expect(res.status).toBe(429);
    expect(res.body.error).toBe('Too many requests');
  });

  it('happy-status: returns 200 for authorized admin with mocked DB', async () => {
    sinon.stub(axios, 'get').resolves({ data: { loggedIn: true, role: 'admin', username: 'admin' } });
    sinon.stub(db, 'ping').resolves({ db: true, redis: true });
    sinon.stub(db, 'query').resolves([{ t: 'ftbquests_player_names' }]);

    const res = await request(app)
      .get('/api/agrarius/status')
      .set('Cookie', 'session=test');
    expect(res.status).toBe(200);
    expect(res.body.db).toBe(true);
    expect(res.body.redis).toBe(true);
    expect(res.body.tables).toContain('ftbquests_player_names');
  });
});
