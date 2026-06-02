# ftb-quests-mysql-sync

Synchronizace dat FTB Quests, FTB Teams a FTB Chunks napříč více Minecraft servery přes sdílený MySQL + Redis backend.

---

## Úvod / Co mod dělá

`ftb-quests-mysql-sync` je serverový mod pro **Forge 1.20.1**, který udržuje **týmová data konzistentní napříč více Minecraft servery** za proxy (např. `agr1` a `agr2`). Všechny servery sdílejí jeden MySQL a jeden Redis backend, takže hráč může přecházet mezi servery a jeho tým, postup i claimnuté chunky zůstanou stejné.

**Klienti běží na čistém (vanilla) FTB — žádný klientský mod není potřeba.**

Mod má tři nezávisle zapínatelné oblasti synchronizace:

| Oblast | Config přepínač | Výchozí stav | Co se synchronizuje |
|---|---|---|---|
| **Questy** (`syncQuests`) | `true` | zapnuto | Postup v questech, dokončení, nárokování odměn, shop cykly, completion toasty |
| **Týmy** (`syncTeams`) | `false` | vypnuto | Členství v party, vlastník týmu, **barva týmu** (živě, bez relogu), název týmu |
| **Chunky** (`syncChunks`) | `false` | vypnuto | Nárokované (claimed) a force-loadnuté chunky per tým |

> **Pozn.:** `syncTeams` a `syncChunks` jsou ve výchozím stavu **vypnuté**. Zapínají se v configu (viz sekce Konfigurace) a vyžadují stabilní UUID hráčů z proxy (viz „Známá omezení").

Díky tomu zůstanou postup, tým i claimnuté chunky konzistentní bez ohledu na to, na kterém serveru člen týmu zrovna hraje.

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

### MySQL tabulky (týmy a chunky)

Kromě questových tabulek mod používá:

| Tabulka | Obsah |
|---|---|
| `ftbchunks_team_claims` | Nárokované/force-loadnuté chunky per tým (dim + souřadnice) |
| `ftbquests_player_names` | Mapování jméno hráče → UUID (pro příkazy; plní se při loginu) |

---

## Synchronizace týmů (`syncTeams`)

Když je `syncTeams = true`, mod synchronizuje **FTB Teams** napříč servery:

- **Členství v party** — když si na `agr1` vytvoříš party a pozveš spoluhráče, uvidí se navzájem i po přechodu na `agr2`.
- **Vlastník týmu** — vlastnictví party je konzistentní napříč servery.
- **Barva týmu** — změna barvy týmu se propisuje **živě na druhý server bez nutnosti relogu** (od verze 1.0.29). Barva se projeví i v zobrazení nárokovaných chunků na mapě.
- **Název týmu** — propisuje se stejnou cestou jako barva.

### Jak to konverguje

Authoritou je vždy **MySQL**. Lokální FTB událost → zápis do MySQL → Redis publish (invalidace) → druhý server načte aktuální stav z DB a aplikuje ho lokálně. Při loginu hráče se jeho tým „zhmotní" (materializuje) z DB, takže přechod mezi servery vždy skončí ve správném týmu.

> **Vyžaduje stabilní UUID hráčů** z proxy (Velocity „modern forwarding"). Při offline/cracked UUID se identita může rozejít — viz „Známá omezení".

---

## Synchronizace chunků (`syncChunks`)

Když je `syncChunks = true`, mod zrcadlí **FTB Chunks** claimy per tým napříč servery 1:1:

- Nárokované chunky (claims) i force-load příznak se synchronizují.
- Authoritou je MySQL; při konvergenci se lokální sada claimů srovná s DB.

### Bezpečnost při zavedení (seeding)

Tabulka `ftbchunks_team_claims` startuje **prázdná**. Aby první materializace nesmazala existující claimy, je nutné DB nejdřív naplnit z kanonického serveru:

| Klíč | Význam |
|---|---|
| `chunkSeedOnStart` | Při startu naplní DB z claimů na tomto serveru (jednorázově) |
| `chunkCanonicalServerId` | `serverId`, který je zdrojem pravdy pro seeding (např. `agr1`) |
| `chunkForceLoadSync` | Synchronizovat i force-load příznak |

> **Postup zavedení:** Nejdřív zapni `syncChunks` + `chunkSeedOnStart=true` na kanonickém serveru, nech naplnit DB, pak teprve zapni na druhém serveru. Jinak hrozí smazání claimů.

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

| Klíč | Význam | Výchozí |
|---|---|---|
| `syncQuests` | Zapnout synchronizaci questů | `true` |
| `syncTeams` | Zapnout synchronizaci týmů (party, vlastník, barva, název) | `false` |
| `syncChunks` | Zapnout synchronizaci FTB Chunks claimů | `false` |
| `chunkSeedOnStart` | Jednorázově naplnit DB z claimů tohoto serveru | `false` |
| `chunkCanonicalServerId` | `serverId` zdroje pravdy pro seeding chunků | `agr1` |
| `chunkForceLoadSync` | Synchronizovat i force-load příznak chunků | `true` |
| `sendFullTeamData` | Posílat plná týmová data | `true` |
| `sendDeltaPackets` | Posílat delta pakety (přírůstkové změny) | `false` |
| `conflictPolicy` | Politika řešení konfliktů | `reload_remote` |

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

## Příkazy pro správu týmů (experimentální)

> ⚠️ **Tato funkce je experimentální a aktuálně NESPOLEHLIVÁ napříč servery.** Funguje pro hráče na stejném serveru; cross-server scénáře (pozvat/vyhodit hráče, který je na druhém serveru) mají známé chyby — viz „Známá omezení". Nepoužívej v ostrém provozu, dokud nebude opraveno.

Při `syncTeams = true` registruje mod příkaz:

```
/ftbsync team invite <hráč>     # přidá hráče přímo do party (bez FTB pozvánky/přijetí)
/ftbsync team kick <hráč>       # vyhodí hráče z party
/ftbsync team transfer <hráč>   # převede vlastnictví party (cíl musí být už členem)
```

- Příkaz smí použít **vlastník party** nebo **operátor (op level ≥ 2)**.
- `invite` je **přímé přidání** (ne nativní FTB pozvánka s přijetím), aby šlo přidat i hráče na druhém serveru.
- Jméno hráče se překládá na UUID přes lokální cache → DB (`ftbquests_player_names`) → Mojang API. **Nikdy se nevytváří offline UUID.**

---

## Známá omezení

- **Cross-server pozvání / vyhození zatím spolehlivě nefunguje.** Pozvání hráče, který je na druhém serveru, a vyhození hráče, který je online na druhém serveru, mají známé chyby (hráč se nemusí přidat / zůstane v týmu s právy do relogu). Synchronizace samotného členství při loginu a barvy týmu funguje; živé příkazy ne. Oprava se řeší.
- **Stabilní UUID je nutnost.** Veškerá per-hráč synchronizace (týmy, chunky) stojí na tom, že proxy posílá konzistentní UUID hráče (Velocity „modern forwarding", online-mode). Při offline/cracked UUID se identita může rozejít a synchronizace selže.
- **Seeding chunků je destruktivní, pokud se přeskočí.** Viz sekce „Synchronizace chunků" — prázdná DB + materializace bez seedingu = smazané claimy. Vždy nejdřív naplň DB z kanonického serveru.

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
| `1.0.26` | Synchronizace členství v party (FTB Teams) napříč servery |
| `1.0.28` | Synchronizace FTB Chunks claimů + force-load per tým |
| `1.0.29` | Synchronizace barvy týmu (živě, bez relogu) |
| `1.0.30` | Příkazy `/ftbsync team invite/kick/transfer` (cross-server) |
| `1.0.31` | Oprava konvergence při vyhození offline hráče |
| `1.0.32` | Pokus o opravu cross-server pozvání a vyhození online hráče — **stále nespolehlivé**, viz Známá omezení |
| `1.0.33` | Oprava issues #1–#4: rank už neteče do sdílených dat (fail-closed claim), disband/kick migruje questy+reward scopes na sólo tým (konec ztráty postupu a farmení coinů), single-owner invariant (oprava dvou ownerů + editace názvu), barva claimů se propíše na existující chunky bez relogu, publish-after-commit pro team props/owner |
| `1.1.1` | Oprava logout force-save crashnutí: bridge interface je mimo mixin package a logout používá bezpečný cast. Redis reset eventy s `forceReplace=true` nahrazují lokální quest data místo merge se starým stavem. |
