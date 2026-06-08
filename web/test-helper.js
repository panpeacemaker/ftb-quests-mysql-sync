'use strict';

function createApp() {
  process.env.AGR_ALLOW_EMPTY_PASSWORDS = '1';
  process.env.WOT_ME_URL = 'http://test/wot/me';
  process.env.WOT_LOGIN_URL = 'http://test/wot/login';
  process.env.AGRARIUS_ROLES = 'admin';
  process.env.AGRARIUS_ADMINS = '';
  process.env.AGR_DB_PASS = 'dummy';
  process.env.AGR_REDIS_PASS = 'dummy';
  process.env.NODE_ENV = 'test';

  // Ensure fresh module instances (especially the rate-limiter Map)
  delete require.cache[require.resolve('./db')];
  delete require.cache[require.resolve('./routes')];

  const express = require('express');
  const routes = require('./routes');

  const app = express();
  app.use(express.json());
  app.use(routes);
  return app;
}

module.exports = { createApp };
