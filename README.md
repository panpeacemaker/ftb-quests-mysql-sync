# ftb-quests-mysql-sync

Synchronizace týmových dat FTB Quests napříč více Minecraft servery přes sdílený MySQL + Redis backend.

---

## Úvod / Co mod dělá

`ftb-quests-mysql-sync` je mod pro **Forge 1.20.1**, který udržuje **týmová data FTB Quests konzistentní napříč více Minecraft servery**. Servery (např. `agr1` a `agr2`) sdílejí jeden MySQL a jeden Redis backend.

Synchronizují se tato týmová data:

- postup v questech (quest progress),
- dokončení questů (completions),
- nárokování odměn (reward claims).

Díky tomu zůstane postup a odměny týmu konzistentní bez ohledu na to, zda jeho členové hrají na `agr1`, nebo `agr2`.

---

## Jak to funguje (architektura)

### Datové úložiště

| Komponenta | Technologie | Role |
|---|---|---|
| Trvalé úložiště | MySQL (HikariCP, shaded v jaru) | Týmová data, evidence nárokovaných odměn, info o týmech, členství, postup ranků |
| Real-time propagace | Redis (Jedis, shaded) | Pub/sub kanál pro okamžité šíření změn (delta) mezi servery |
| Integrace s FTB | Mixins | Zásahy do FTB Quests (`TeamData`, `Reward`, `Quest`) a do FTBTeams |

### MySQL tabulky

| Tabulka | Obsah |
|---|---|
| `ftbquests_teamdata` | NBT bloby s týmovými daty |
| `ftbquests_reward_claim_scopes` | Evidence (ledger) nárokovaných odměn |
| `ftbquests_team_info` | Informace o týmech |
| `ftbquests_team_membership` | Členství v týmech |
| `ftbquests_rank_progress` | Postup ranků |

### Vláknový model

- Práce s DB a Redisem běží **mimo hlavní vlákno serveru**.
- Hlavní vlákno serveru pouze serializuje neměnné (immutable) NBT snapshoty.
- Lokální vanilla ukládání do souboru zůstává zachováno jako **záloha (fallback)**.

### serverId

Každý server má svůj `serverId` (např. `agr1`, `agr2`). Slouží k tomu, aby server **ignoroval vlastní odražené (echoed) aktualizace**, které se k němu vrátí přes Redis.

---

## Chování odměn a kapitol

Toto je provozně nejdůležitější část. Mod rozlišuje **tři třídy chování** podle kapitoly.

### Přehledová tabulka

| Třída chování | Kapitoly / ID | Pravidlo odměn |
|---|---|---|
| **SHOP (týmově sdílené, opakovatelné)** | SHOP — `3CEC7F7BAD54E4C6` | Týmově sdílené a opakovatelné. Jedno nárokování na odměnu na tým za cyklus; po dokončení celé sady se quest resetuje. |
| **Týmově sdílená progrese (jedna odměna na tým, jen jednou)** | 16 kapitol: skupina *Progression Tracking*, skupina *Through the Ages Regular* a *Extras* kapitoly `power` / `applied_energistics` / `mystical_agriculture_gregified` | Jedna odměna na **tým**, claim-once (neopakovatelné), synchronizováno mezi servery. |
| **SOLO (per-hráč)** | ranks — `3622ED01311E6763`; Quality of Life — `67F6F5055518AC4F` | Zůstává **per-hráč** — každý člen nárokuje vlastní odměnu. |

### SHOP kapitola (`3CEC7F7BAD54E4C6`)

- Odměny jsou **týmově sdílené a opakovatelné**.
- Tým si sadu koupí jednou — **jedno nárokování na odměnu na tým**, deduplikováno napříč servery.
- Když je celá sada nárokována, **quest se resetuje**, aby tým mohl zaplatit za novou sadu.
- Deduplikace je **per nákupní „cyklus"** — opakování tedy funguje, ale jeden konkrétní cyklus nelze nárokovat dvakrát napříč servery.

### Týmově sdílené progresní kapitoly (16 kapitol)

- **Jedna odměna na tým**, nárokování jen jednou (neopakovatelné), synchronizováno mezi servery.
- Když odměnu nárokuje jeden člen, **všichni spoluhráči ji vidí jako nárokovanou** a nemohou ji nárokovat znovu.
- Item dostane **pouze ten, kdo odměnu nárokoval**.

Patří sem:

- skupina **Progression Tracking**,
- skupina **Through the Ages Regular**,
- **Extras** kapitoly: `power`, `applied_energistics`, `mystical_agriculture_gregified`.

### SOLO kapitoly

- **ranks** (`3622ED01311E6763`) a **Quality of Life** (`67F6F5055518AC4F`) zůstávají **per-hráč**.
- Každý člen týmu nárokuje svou vlastní odměnu.

### Ostatní kapitoly

- Kapitoly **mechanics** a **game_tips** nejsou nijak speciálně ošetřeny — ponechány na vanilla chování FTB.

### Completion toast

- Když tým dokončí quest, **každý online člen týmu na libovolném serveru** dostane v pravém horním rohu toast „Quest Complete".
- Toast tedy nedostane jen ten, kdo quest dokončil, ale všichni online členové týmu.

### Poznámka pro adminy — staré per-hráč záznamy

Odměny, které si hráč **individuálně nárokoval ještě PŘED** přechodem na týmové sdílení, si ponechávají starý per-hráč záznam. Taková odměna může být týmem nárokovatelná **ještě jednou** po aktualizaci (jednorázový efekt). **Nová** nárokování už deduplikují správně.

---

## Konfigurace

Konfigurační soubor:

```
<server>/config/ftbquestssync-server.toml
```

### MySQL

| Klíč | Význam |
|---|---|
| `host` | Hostname / IP MySQL serveru |
| `port` | Port MySQL |
| `database` | Název databáze |
| `username` | Uživatelské jméno k DB |
| `password` | Heslo k DB (viz poznámka o tajemstvích níže) |
| `maxPoolSize` | Maximální velikost connection poolu (HikariCP) |
| `minIdle` | Minimální počet nečinných spojení v poolu |
| `useSsl` | Zda použít SSL pro spojení k MySQL |
| `allowPublicKeyRetrieval` | Povolit načtení veřejného klíče (MySQL auth) |

### Redis

| Klíč | Význam |
|---|---|
| `redisHost` | Hostname / IP Redis serveru |
| `redisPort` | Port Redisu |
| `redisPassword` | Heslo k Redisu |

### General

| Klíč | Význam |
|---|---|
| `syncQuests` | Zapnout synchronizaci questů |
| `syncTeams` | Zapnout synchronizaci týmů |
| `sendFullTeamData` | Posílat plná týmová data |
| `sendDeltaPackets` | Posílat delta pakety (přírůstkové změny) |
| `conflictPolicy` | Politika řešení konfliktů |

### Policy

| Klíč | Význam |
|---|---|
| `mode` | Režim seznamů: `blacklist` / `whitelist` |
| `soloChapterIds` | ID kapitol, které jsou solo (per-hráč) |
| `repeatableSoloChapterIds` | ID solo kapitol, které jsou opakovatelné |
| `soloQuestIds` | ID jednotlivých solo questů |
| `soloTaskIds` | ID jednotlivých solo tasků |
| `teamClaimChapterIds` | Sada shop/opakovatelných týmových kapitol |
| `teamSharedChapterIds` | Sada progresních kapitol s jednou odměnou na tým |
| `syncSoloProgressPerPlayer` | Synchronizovat solo postup per-hráč |
| `soloRewardsPerPlayer` | Solo odměny per-hráč |
| `teamRewardsDedupGlobal` | Globální (napříč servery) deduplikace týmových odměn |
| `rewardFailClosed` | Chování při nedostupné DB (viz níže) |

### Seznamy ID kapitol

Seznamy ID kapitol přijímají **16-místná hexadecimální ID** (16-hex).

**Jak přidat kapitolu do `teamSharedChapterIds`** (jedna odměna na tým):

```toml
teamSharedChapterIds = [
    "3CEC7F7BAD54E4C6",
    "0123456789ABCDEF"   # zde přidejte 16-hex ID nové kapitoly
]
```

**Jak přidat kapitolu do `teamClaimChapterIds`** (shop / opakovatelné):

```toml
teamClaimChapterIds = [
    "3CEC7F7BAD54E4C6",
    "FEDCBA9876543210"   # zde přidejte 16-hex ID nové kapitoly
]
```

### Tajemství (hesla) přes JVM `-D` system properties

Hesla lze místo plaintextu v `.toml` dodat přes JVM `-D` system properties, např.:

```
-Dftbquestssync.mysql.password=<MYSQL_PASSWORD>
```

> **Doporučení:** Heslo by **nemělo** zůstávat v plaintextu tam, kde se tomu lze vyhnout.

### `rewardFailClosed`

| Hodnota | Chování při nedostupné DB |
|---|---|
| `true` (výchozí) | Nárokování odměn je **ZAMÍTNUTO** (bezpečné pro ekonomiku) |
| `false` | Nárokování je povoleno **bez cross-server ochrany** (riziko duplikátů) |

---

## Sestavení (build)

Pro vývojáře/admina, který jar znovu sestavuje.

Z kořene repozitáře spusťte s **JDK 17**:

```bash
./gradlew clean reobfShadowJar
```

> **Pozor — Java 17:** Build vyžaduje Java 17. Novější JDK rozbíjejí toolchain Gradle 8.4.

Výstup:

```
build/libs/ftb-quests-mysql-sync-<version>.jar
```

> **DŮLEŽITÉ:** Musí se použít task `reobfShadowJar`. Obyčejný `shadowJar` produkuje deobfuskovaný jar, který na serveru **NEBUDE fungovat**.

---

## Nasazení / aktualizace modu (jar)

Aktualizujte jeden server po druhém a použijte běžný způsob správy daného serveru.

1. **Čistě zastavte server** příkazem `stop` v konzoli/hře nebo běžným administrátorským postupem.
   > Vypnutí může trvat ~120 s. Pokud serverový proces nezmizí, vynuceně ho ukončete podle provozního postupu.
2. **Zazálohujte stávající jar** z `<server>/mods/` (např. do `mods.disabled/deploy-backups/` s časovým razítkem). Zálohy si ponechte.
3. **Nasaďte nový jar** `ftb-quests-mysql-sync-<version>.jar` do `<server>/mods/` a ujistěte se, že je přítomen **PRÁVĚ JEDEN** `ftb-quests-mysql-sync-*.jar` (starý odeberte / přesuňte). Dvě kopie se nikdy nesmí načíst současně.
4. **Spusťte server** běžným způsobem daného prostředí.
5. **Ověřte v logu** (`latest.log`) řádek:
   ```
   FTB Quests Sync <version> ready (mysqlAvailable=true, redisEnabled=true, teamsRedisEnabled=true, serverId=...)
   ```
   a dále `Done (...)! For help`. Potvrďte, že tam **NENÍ**:
   - `IllegalClassLoadError`
   - `Mixin apply failed`
   - `AbstractMethodError`
6. **Zopakujte pro druhý server.** Při aktualizaci všech serverů je dělejte **jeden po druhém** a každý před dalším ověřte.

---

## Nasazení / aktualizace questů (quest config)

Tohle je aktualizace **obsahu questů** (kapitoly, questy, odměny) — **ne** modu. Quest config leží zde:

```
<server>/config/ftbquests/quests/
├── data.snbt              # metadata booku (title, version, …)
├── chapter_groups.snbt    # skupiny kapitol
└── chapters/*.snbt        # jednotlivé kapitoly
```

> **POZOR — ID kapitol musí sedět s konfigem modu.** Pokud nový balík questů změní **16-hex ID kapitol** SHOP / progresních / solo kapitol (viz sekce „Chování odměn a kapitol" a `teamClaimChapterIds` / `teamSharedChapterIds` / `soloChapterIds`), musíš odpovídajícím způsobem upravit `ftbquestssync-server.toml`, jinak týmová synchronizace u těch kapitol přestane fungovat. **Před nasazením ID porovnej.**

Postup (vždy **jeden server po druhém**):

1. **Zazálohuj stávající config** (uvnitř kontejneru / na serveru):
   ```bash
   cd <server>/config/ftbquests
   cp -a quests quests.bak-$(date +%Y%m%d-%H%M%S)
   ```
   Zálohu **nemaž** — je to tvůj rollback.
2. **Čistě zastav server** (viz výše; pozor na ~120 s + případné vynucené ukončení podle provozního postupu).
3. **Nahraď obsah** `quests/` novými soubory. Např. z tarballu:
   ```bash
   cd <server>/config/ftbquests
   rm -rf quests && tar xzf /tmp/quests-new.tgz
   ```
4. **Ověř, že to sedí** ještě před startem:
   ```bash
   ls <server>/config/ftbquests/quests/chapters/ | wc -l   # počet kapitol
   ls <server>/config/ftbquests/quests/data.snbt           # musí existovat
   ```
5. **Spusť server** a počkej na nájezd.
6. **Ověř v logu** (`latest.log`) řádek typu:
   ```
   Loaded N chapter groups, M chapters, ... quests
   ```
   Počet kapitol/questů musí odpovídat novému balíku. Potvrď `Done (...)! For help` a žádné `ERROR`/`FATAL`.
7. **Zopakuj pro druhý server.** Druhý server spouštěj až po ověření prvního — ať je vždy aspoň jeden online.

### Resetují se hráčům questy při nasazení nového configu?

**Ne — samotné nasazení configu postup nemaže.** Funguje to takto:

- Dokončení questů je klíčované podle **UUID questu**, ne podle obsahu kapitoly.
- Postup hráčů/týmů žije **mimo** `config/` — v `world/ftbquests/` (týmové `.snbt` soubory) a v MySQL tabulce `ftbquests_teamdata`. Výměna `config/ftbquests/quests/` tyto soubory **nesahá**.
- Pokud nový balík **zachová UUID** existujících questů, jejich dokončení **zůstane**. (Při reálné aktualizaci se typicky zachová drtivá většina UUID — měřeno ~99 %.)
- „Resetovaně" se ukážou **jen nově přidané nebo přepsané questy** (mají nová UUID). To **není** reset postupu — je to nový/změněný obsah, který logicky ještě není splněný. Odebrané questy zmizí i s jejich záznamem.

**K plnému resetu dojde jen tehdy, když někdo smaže `world/ftbquests/` nebo vyprázdní MySQL tabulky** (`ftbquests_teamdata`, `ftbquests_reward_claim_scopes`). To se na ostrém serveru při běžném nasazení **neděje** — pokud to ručně neuděláš. Reset, který může nastat na testovacím prostředí, je dán wipem testu, ne nasazením configu.

> **Shrnutí pro ostrý server:** nasazení nového quest configu (ani nové verze modu) postup hráčů nesmaže. Stačí dodržet postup výše a nemazat `world/ftbquests/` ani MySQL data.

---

## Databáze a migrace

- Tabulky se **vytvoří automaticky** při prvním startu.
- Schema migrace jsou **idempotentní** a chráněné MySQL **advisory lockem** — při startu více serverů migraci aplikuje pouze první, ostatní jsou no-op.
- Evidence nárokovaných odměn je klíčována podle **(`scope_type`, `scope_uuid`, `reward_id`, `cycle`)**.
- Admini by tyto tabulky **NEMĚLI ručně upravovat**.

Jednorázová migrace v logu vypadá takto:

```
Claim scope cycle migration: rebuilt PRIMARY KEY ...
```

---

## Řešení problémů

### Chatová hláška „Reward already claimed on another server."

Cross-server deduplikace zamítla duplicitní nárokování. Je to **očekávané**, když dva členové závodí o stejnou odměnu.

### MySQL/Redis ukazuje na localhost

Pokud mod v logu hlásí, že MySQL/Redis míří na **localhost**, jde o **varování o chybné konfiguraci** pro multi-server setup.

### Rollback

1. Zastavte server.
2. Obnovte zálohovaný jar s časovým razítkem zpět do `mods/` (jediný jar).
3. Restartujte.

### Kam se dívat

- Log: `<server>/logs/latest.log`
- Grepujte podle: `FTBQuestsSync`

```bash
grep "FTBQuestsSync" <server>/logs/latest.log
```

---

## Historie verzí

| Verze | Změna |
|---|---|
| `1.0.20` | Opakovatelný týmově sdílený shop |
| `1.0.21` | Jedna odměna na tým pro progresní kapitoly |
| `1.0.22` | Cross-server completion toast pro online spoluhráče |
