# FTB Quests MySQL Sync — Testovací Plán

## Verze
**Branch:** `fix/per-player-rank-progress`  
**JAR:** `ftb-quests-mysql-sync-1.0.4-snapshot-all.jar`  
**Build:** `./gradlew build` → `BUILD SUCCESSFUL`

---

## 1. Build Validation (automatizované)

- [x] `./gradlew build` – `BUILD SUCCESSFUL`  
- [x] `build/libs/ftb-quests-mysql-sync-1.0.4-snapshot-all.jar` existuje  
- [x] Hash ověřen (`md5sum`) vs. předchozí verze  

---

## 2. Login Delay Fix (Fix #2)

**Cíl:** Předejít race conditionu při loginu, kdy klient ještě nemá inicializovaný `ClientQuestFile`

### Test Scenář: Opakovaný login (agr1 → agr2)

1. Hráč na agr1 dokončí quest, zaloguje se ven.
2. Hráč se připojí na agr2.
3. **Ověřit**, že klient nevypadá s NullPointerException `TeamData.getFile() == null`.
4. **Ověřit**, že loading screen (ReceivingLevelScreen) se zavře bez crashu.
5. **Ověřit**, že FTB Quests GUI otevře po připojení (ne crashne).

### Očekávaný výsledek
- Bez crashu.
- `ClientQuestFile` se inicializuje před `SyncTeamDataMessage`.

---

## 3. Cross-Server Quest Sync (Core)

### 3.1 Team Quest (shared)

1. Hráč A a B jsou ve stejném týmu.
2. A na agr1 dokončí quest Q1.
3. B se připojí na agr2.
4. **Ověřit**, že B vidí Q1 jako dokončený.

### 3.2 Rank Solo Quest (per-player)

1. Hráč A a B jsou ve stejném týmu.
2. A na agr1 dokončí rank quest R1.
3. B se připojí na agr2.
4. **Ověřit**, že B NEVIDÍ R1 jako dokončený.
5. B dokončí R1 na agr2.
6. **Ověřit**, že B může claimnout vlastní reward.

### 3.3 Shop Repeatable (solo + team)

1. A na agr1 koupí item v shopu (repeatable quest).
2. B na agr2 se připojí.
3. **Ověřit**, že B NEMÁ ten progress (solo).
4. **Ověřit**, že B MŮŽE claimnout stejný reward (team). **NE** – pokud je team-scope, pak jen jeden claim.
5. Zkontrolovat `ftbquests_reward_claim_scopes` – má jenom jeden záznam pro team.

---

## 4. Reward Deduplication

### 4.1 Team Reward

1. Hráč A claimne týmový reward na agr1.
2. Hráč B se pokusí claimnout stejný reward na agr2.
3. **Ověřit**, že B vidí „Reward already claimed on another server.
4. Zkontrolovat `ftbquests_reward_claim_scopes` – jeden záznam s `scope_type='TEAM'`.

### 4.2 Solo Reward (rank)

1. Hráč A claimne solo reward na agr1.
2. Hráč B claimne stejný reward na agr1 (stejný server).
3. **Ověřit**, že B může claimnout (per-player).
4. Zkontrolovat `ftbquests_reward_claim_scopes` – dva záznamy s `scope_type='PLAYER'`.

---

## 5. Team Sync & Materialization

1. Hráč A vytvoří tým na agr1, pozve B.
2. B se připojí na agr2.
3. **Ověřit**, že tím je automaticky materializován na agr2 (TeamMaterializer).
4. **Ověřit**, že B má správné FTB Teams UI s členy a ranky.

---

## 6. Crash Test: Freeze Fix Compat

### Scenář: Rychlý re-login

1. Hráč se rychle vypne a připojí na druhý server.
2. **Ověřit**, že se nestane NullPointerException v `Quest.isVisible`.
3. **Ověřit**, že loading screen se po zavření normálně rozjede bez freeze.

---

## 7. Redis / MySQL Komunikace

### 7.1 Redis Publish/Subscribe

1. Hráč A na agr1 změní quest progress.
2. **Ověřit** log agr2: `Received remote Redis team update: ...`
3. **Ověřit**, že agr2 provede `applyRemoteUpdate` a posílá paket členům.

### 7.2 MySQL Persistence

1. Zkontrolovat `ftbquests_teamdata` – existuje řádek s tímto `team_id`.
2. Zkontrolovat `ftbquests_rank_progress` – existují řádky pro každého hráče.
3. Zkontrolovat `ftbquests_reward_claim_scopes` – dedupulikace funguje.

---

## 8. Performance / Stability

- [ ] Za 24 hodin uptime nesmí být memory leak (>50 MB).
- [ ] Redis reconnect po network chybě musí fungovat.
- [ ] MySQL pool reconnect po restartu DB musí fungovat.
- [ ] `forcedReloadAndPushTo` se nesmí volat více než jednou za login.

---

## 9. Chyby & Troubleshooting

| Příznak | Možná Příčina | Rychlá Oprava |
|---|---|---|
| `TeamData.getFile() == null` crash | Freeze fix otevře UI moc brzo | Ujisti se, že ftbquestsfreezefix je opraven nebo klient čaká na inicializaci |
| `forceReloadAndPushTo` spam | Schedulováno vícekrát na login | Zkontroluj event listener registraci |
| Claim duplication dopředu | Missing scope check | Zkontroluj `getClaimType` a `ftbquests_reward_claim_scopes` |
| Team members invisible | TeamSync nedokončeno | Zkontroluj `handleRemote` a DB sync |
| Save fails | MySQL pool exhausted | Zvýšit `maxPoolSize` nebo snížit zátlas |

---

## Acceptance Criteria

- [ ] Všechny test scénáře prošly.  
- [ ] Žádný crash při loginu / switchi.  
- [ ] Quest sync funguje agr1 ↔ agr2.  
- [ ] Solo rank progress se nepropaguje mezi týmovými hráči.  
- [ ] Team rewards nejsou duplikovány.  
- [ ] Freeze fixu se nestane.  
