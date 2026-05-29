# Testovací plán — ftb-quests-mysql-sync

Tento dokument je pro **herní testery** (hráče). Cílem je ověřit, že synchronizace mezi servery `agr1` a `agr2` funguje správně — postup v questech, odměny, **týmy, barva týmu a claimnuté chunky**.

Chování modu (co se má dít) je popsané v [README.md](README.md). Tento plán z toho vychází.

> **Jste hráči, ne admini.** Nemáte přístup na server (žádné SSH, logy, restart, vypínání databáze). **Testuje se výhradně ve hře.** Vše, co potřebujete, jde udělat herními akcemi: hrát, přepínat servery (`/server …`), odpojit se a znovu připojit, zadávat herní příkazy.

---

## Než začnete

- **Připojení:** připojte se na **proxy** (adresu a port dodá admin). Nikam jinam se nepřipojujte — proxy vás pustí na herní servery.
- **Přepínání mezi servery** (povoleno, je to herní akce):
  - `/server agr1` — přepne vás na **agr1**
  - `/server agr2` — přepne vás na **agr2**
- **Odpojit/připojit se** (relog) je taky povolené — je to běžná herní akce. Jen **nerestartujete server** (to neumíte a není potřeba).
- **Jste vždy jen na jednom serveru naráz.** Proto „cross-server" scénáře potřebují **dva testery současně** — jeden na `agr1`, druhý na `agr2`. Sám to neuděláš (nemůžeš být na obou).
- **Tým:** většina scénářů je týmová. Potřebujete **tým aspoň o 2 hráčích** (FTB Teams). Někdo navíc ať je **sólo** (bez party) pro sólo scénáře.
- **Itemy / claimy:** pokud nemáš suroviny na quest nebo na claimnutí chunku, řekni **adminovi** předem (dá ti je / zapne creative). Cílem testu je synchronizace, ne grind.
- **Verze:** admin řekne, jakou **verzi modu** servery běží. Verzi piš do každého hlášení.

---

## Jak zapisovat výsledky

1. **Každý scénář** označ jako **PASS** / **FAIL** / **BLOCKED** (nešlo otestovat).
2. **Když něco nesedí (FAIL):** založ issue na GitHubu přes šablonu **„Bug report (CZ)"**:
   <https://github.com/panpeacemaker/ftb-quests-mysql-sync/issues/new/choose>
3. Do issue **vždy** napiš: scénář (např. „G – barva týmu"), **jména testerů**, kdo byl na **kterém serveru**, **verzi modu**, kroky, co jsi čekal a co se reálně stalo. Ideálně **screenshot** a **přibližný čas** (kvůli dohledání v logu adminem).
4. Když je to **PASS**, stačí odškrtnout v seznamu níže — issue nezakládej.

### Checklist (odškrtávej)

**Questy a odměny**
- [ ] A — Shop (opakovatelný týmový)
- [ ] B — Progrese (1 odměna na tým, jen jednou)
- [ ] C — Sólo kapitoly (rank, Quality of Life)
- [ ] D — Completion toast (notifikace)
- [ ] E — Cross-server konzistence postupu

**Týmy a barva**
- [ ] F — Členství v týmu po přepnutí serveru
- [ ] G — Barva týmu živě napříč servery
- [ ] H — Název a vlastník týmu
- [ ] I — Barva/název po přepnutí serveru a relogu

**Chunky (FTB Chunks)**
- [ ] J — Synchronizace claimnutých chunků
- [ ] K — Synchronizace force-loadu
- [ ] L — Odclaimnutí chunku

**Příkazy `/ftbsync` (experimentální — očekává se, že selžou)**
- [ ] M — Pozvání hráče na druhém serveru
- [ ] N — Vyhození hráče online na druhém serveru
- [ ] O — Vyhození offline hráče + jeho návrat
- [ ] P — Převod vlastnictví týmu

**Zátěž a souběh**
- [ ] Q — Rychlé přepínání serverů
- [ ] R — Dva členové mění tým současně

---

## Scénáře

> Legenda: **Typ** = sólo / cross-server (kolik testerů) · **Cíl** = co ověřujeme · **Kroky** = postup · **Očekávání** = co se má stát · **Závažnost** = jak vážné, když to selže.
> Přepnutí serveru = `/server agr1` nebo `/server agr2`.

---

## Questy a odměny

### A — Shop (opakovatelný, týmově sdílený) · cross-server (2 testeři) · High

- **Cíl:** Shop odměny jsou týmové a opakovatelné; jedno nárokování na odměnu na tým za cyklus; po nárokování celé sady jde quest koupit znovu.
- **Kroky:**
  1. V týmu (2 hráči) na `agr1` kup v shopu sadu a **nárokuj všechny odměny** v questu.
  2. Sleduj, zda se po nárokování celé sady **quest resetuje** (jde koupit znovu).
  3. Druhý člen (`/server agr2`) se podívá na stejný shop quest.
  4. **Souběh:** oba členové (každý na jiném serveru) zkusí nárokovat **stejnou** odměnu skoro současně.
- **Očekávání:** Každou odměnu lze v cyklu nárokovat **jen jednou za tým**. Po celé sadě se quest **resetuje**. Při souběhu jeden uspěje, druhý dostane hlášku **„Reward already claimed on another server."** (to je správně, ne bug).

### B — Progrese (1 odměna na tým, jen jednou) · cross-server (2 testeři) · High

- **Cíl:** U progresních kapitol je **jedna odměna na tým**, jen jednou, synchronizovaná mezi servery.
- **Kroky:**
  1. Tým dokončí progresní quest.
  2. Jeden člen na `agr1` **nárokuje** odměnu.
  3. Ostatní (`agr1` i `/server agr2`) se na odměnu podívají.
- **Očekávání:** Item dostane **jen ten, kdo nárokoval**. Ostatní vidí odměnu jako **nárokovanou** a nemůžou ji vzít znovu — na obou serverech.

### C — Sólo kapitoly (rank, Quality of Life) · sólo (2 testeři pro porovnání) · Medium

- **Cíl:** Kapitoly **ranks** a **Quality of Life** zůstávají **per-hráč**.
- **Kroky:** Dva členové téhož týmu splní stejný sólo quest a každý si **nárokuje vlastní** odměnu.
- **Očekávání:** **Oba** dostanou odměnu nezávisle; nárokování jednoho **neblokuje** druhého (na rozdíl od B).

### D — Completion toast (notifikace) · cross-server (2 testeři) · Medium

- **Cíl:** Po dokončení týmového questu dostanou notifikaci „Quest Complete" (vpravo nahoře) **všichni online členové na obou serverech**.
- **Kroky:**
  1. Tým má 2 online členy, **každý na jiném serveru**.
  2. Jeden z nich **dokončí** týmový quest.
- **Očekávání:** Online spoluhráč na **druhém** serveru dostane toast. (Offline člen toast nedostane — to je správně.)

### E — Cross-server konzistence postupu · cross-server (2 testeři) · High

- **Cíl:** Postup týmu je stejný bez ohledu na server.
- **Kroky:**
  1. Na `agr1` udělej kus postupu (splň quest / část tasku).
  2. Přepni se na `agr2` (nebo to zkontroluje druhý tester, co tam už je) a otevři stejný quest.
  3. Zkus to i opačně (`agr2` → kontrola na `agr1`).
- **Očekávání:** Postup z jednoho serveru je **vidět i na druhém** (krátká prodleva 1–2 s je OK).

---

## Týmy a barva

### F — Členství v týmu po přepnutí serveru · cross-server (2 testeři) · Critical

- **Cíl:** Když si vytvoříš party a pozveš spoluhráče na stejném serveru, zůstane členem i po přechodu na druhý server. **(Toto je funkce, která má fungovat.)**
- **Kroky:**
  1. Tester A i Tester B jsou na `agr1`.
  2. Tester A vytvoří party a **nativní FTB pozvánkou** pozve Testera B.
  3. Tester B **přijme** pozvánku (ještě na `agr1`) a potvrdí, že je v týmu.
  4. Tester B přejde `/server agr2` a otevře týmovou obrazovku.
  5. Tester A zůstane na `agr1` a taky otevře týmovou obrazovku.
- **Očekávání:** Tester B je **pořád ve stejném týmu i na `agr2`**. Tester A vidí Testera B jako člena. Tester B **nezmizí do sóla** ani nedostane nový samostatný tým.

### G — Barva týmu živě napříč servery · cross-server (2 testeři) · High

- **Cíl:** Změna barvy týmu na `agr1` se projeví **živě na `agr2` bez relogu** — včetně barvy claimnutých chunků na mapě.
- **Kroky:**
  1. Tester A a Tester B jsou v jednom týmu (viz F).
  2. Tester A na `agr1` **claimne jeden chunk** pro tým.
  3. Tester B přejde `/server agr2`, otevře mapu FTB Chunks a podívá se na barvu claimu.
  4. Tester A **změní barvu týmu** na `agr1`.
  5. Tester B **zůstane na `agr2`, NEodpojuje se**, sleduje týmovou obrazovku a mapu chunků (klidně mapu zavři a znovu otevři, ale **bez disconnectu**).
- **Očekávání:** Tester B vidí **novou barvu na `agr2` bez relogu**, a změní se i barva claimnutého chunku na mapě. Tester B zůstává ve stejném týmu, nevznikne duplicitní tým.

### H — Název a vlastník týmu · cross-server (2 testeři) · Critical

- **Cíl:** Název týmu a vlastník jsou stejné na obou serverech.
- **Kroky:**
  1. Tester A (vlastník) na `agr1`, Tester B na `agr2`, v jednom týmu.
  2. Tester A **změní název týmu** na `agr1`.
  3. Tester B otevře/obnoví týmovou obrazovku na `agr2`.
  4. Oba zkontrolují, kdo je **vlastník** a jaký je **název**.
- **Očekávání:** Nový název se objeví na `agr2`. **Vlastník je stejný** na obou serverech (Tester A). Žádný nesoulad mezi `agr1` a `agr2`.

### I — Barva/název po přepnutí serveru a relogu · cross-server (2 testeři) · Medium

- **Cíl:** Barva i název zůstanou správné po přepínání serverů a po odpojení/připojení.
- **Kroky:**
  1. Navazuje na G/H (tým s nastavenou barvou a názvem).
  2. Tester A změní barvu na `agr1`, zatímco Tester B je na `agr2`.
  3. Tester B přepne `agr2 → agr1`, zkontroluje barvu a název; pak `agr1 → agr2`, zkontroluje znovu.
  4. Tester B se **odpojí a znovu připojí** a zkontroluje barvu/název ještě jednou.
- **Očekávání:** Barva i název jsou **stejné na obou serverech** a zůstanou správné po přepínání i po relogu. Claimnuté chunky mají správnou barvu.

---

## Chunky (FTB Chunks)

### J — Synchronizace claimnutých chunků · sólo · Critical

- **Cíl:** Claimnutý chunk se objeví i na druhém serveru.
- **Kroky:**
  1. Na `agr1` buď v týmu, postav se do **neclaimnutého** chunku, otevři mapu FTB Chunks a **claimni** ho.
  2. Potvrď, že je claimnutý na `agr1`.
  3. Přejdi `/server agr2`, dojdi na stejné místo, otevři mapu a zkontroluj ten chunk.
- **Očekávání:** Chunk je **už claimnutý i na `agr2`**, patří **stejnému týmu** a má **stejnou barvu**.

### K — Synchronizace force-loadu · sólo · High

- **Cíl:** Příznak force-load (chunk zůstává načtený) se synchronizuje.
- **Kroky:**
  1. Navazuje na J (claimnutý chunk).
  2. Na `agr1` zapni **force-load** pro ten chunk a potvrď.
  3. Přejdi na `agr2`, zkontroluj ten chunk.
  4. Na `agr2` force-load **vypni**, vrať se na `agr1` a zkontroluj.
- **Očekávání:** Zapnutí na `agr1` se projeví na `agr2`; vypnutí na `agr2` se projeví na `agr1`. Samotný claim zůstane.

### L — Odclaimnutí chunku · sólo · Critical

- **Cíl:** Odebraný (unclaim) chunk zmizí na obou serverech.
- **Kroky:**
  1. Začni s chunkem claimnutým na obou serverech (z J).
  2. Na `agr1` chunk **odclaimni** a potvrď, že už není claimnutý.
  3. Přejdi na `agr2` a zkontroluj ten chunk.
- **Očekávání:** Chunk **už není claimnutý ani na `agr2`**; starý claim je pryč. Jde claimnout znovu, pokud máš právo.

---

## Příkazy `/ftbsync` (experimentální)

> ⚠️ **Tahle oblast je rozpracovaná a očekává se, že některé scénáře SELŽOU.** Otestuj je a poctivě nahlas, co se reálně stalo — pomůže to s opravou. Příkaz smí použít **vlastník party** nebo **operátor**.

### M — Pozvání hráče na druhém serveru · cross-server (2 testeři) · Critical

- **Cíl:** Vlastník na jednom serveru přidá hráče, který je právě na druhém serveru.
- **Kroky:**
  1. Tester A na `agr1` (má tým), Tester B na `agr2`.
  2. Tester A zadá: `/ftbsync team invite TesterB`
  3. Tester B sleduje na `agr2`, jestli se přidal do týmu (otevři týmovou obrazovku).
- **Očekávání (cílový stav):** Tester B se **živě přidá do týmu** na `agr2`, oba servery ukazují stejné členství.
- **Pozn.:** Pokud se Tester B nepřidá / vyskočí chyba — to je zrovna **známá chyba**, kterou hledáme. Nahlas přesně, co se stalo (chybová hláška, nic se nestalo, …).

### N — Vyhození hráče online na druhém serveru · cross-server (2 testeři) · Critical

- **Cíl:** Vyhození člena, který je online na druhém serveru, ho z týmu hned odebere.
- **Kroky:**
  1. Tester A (vlastník) na `agr1`, Tester B (člen) na `agr2`, v jednom týmu.
  2. Tester A claimne chunk; Tester B na `agr2` potvrdí, že má v claimu týmová práva.
  3. Tester A zadá: `/ftbsync team kick TesterB`
  4. Tester B **zůstane na `agr2`** (bez relogu), otevře týmovou obrazovku a zkusí interakci v claimnutém chunku.
- **Očekávání (cílový stav):** Tester B je **živě odebrán** z týmu, přestane být členem a **ztratí práva** v týmovém claimu.
- **Pozn.:** Pokud Tester B **zůstane v týmu nebo si dál drží práva** dokud se nepřipojí znovu — to je **známá chyba**. Nahlas to (zůstal členem? měl práva? po relogu se to spravilo?).

### O — Vyhození offline hráče + jeho návrat · cross-server příprava, pak relog · Critical

- **Cíl:** Vyhození offline hráče vydrží i po jeho návratu do hry.
- **Kroky:**
  1. Tester A (vlastník) a Tester B v jednom týmu.
  2. Tester B se **úplně odpojí** ze hry.
  3. Tester A zadá `/ftbsync team kick TesterB` a potvrdí, že Tester B zmizel z týmu.
  4. Tester B se **znovu připojí** (na `agr1` nebo `agr2`), otevře týmovou obrazovku.
- **Očekávání:** Tester B **zůstane mimo tým** i po návratu, nevrátí se zpět a nemá týmová práva.

### P — Převod vlastnictví týmu · sólo (stejný server) i cross-server · Critical

- **Cíl:** `/ftbsync team transfer` převede vlastnictví; projeví se i na druhém serveru.
- **Kroky:**
  1. Tester A (vlastník) a Tester B v jednom týmu.
  2. **Varianta 1 (stejný server):** oba na `agr1`. Tester A zadá `/ftbsync team transfer TesterB`.
  3. **Varianta 2 (cross-server):** Tester B na `agr2`, Tester A na `agr1` zadá `/ftbsync team transfer TesterB`.
  4. Oba zkontrolují, kdo je vlastník. Tester B zkusí akci vlastníka (změna názvu/barvy).
- **Očekávání:** Tester B se stane **vlastníkem na obou serverech**, Tester A už není vlastník, a změny od nového vlastníka se synchronizují.

---

## Zátěž a souběh

### Q — Rychlé přepínání serverů · sólo · High

- **Cíl:** Tým a chunky zůstanou stabilní při rychlém přepínání.
- **Kroky:**
  1. Na `agr1` měj tým s jasným názvem a barvou, claimni chunk, zapni force-load.
  2. `/server agr2` → hned `/server agr1` → hned `/server agr2`.
  3. Otevři týmovou obrazovku a mapu chunků a zkontroluj název, barvu, claim, force-load.
- **Očekávání:** Vše zůstane správné; **nevypadneš z týmu** ani nedostaneš nový tým.

### R — Dva členové mění tým současně · cross-server (2 testeři) · Medium

- **Cíl:** Stav se sjednotí, když dva členové z různých serverů mění tým naráz.
- **Kroky:**
  1. Tester A na `agr1`, Tester B na `agr2`, v jednom týmu (oba musí mít právo měnit nastavení — případně si nejdřív předej vlastnictví, nebo scénář přeskoč s poznámkou).
  2. Tester A **změní barvu**, Tester B skoro současně **změní název** (nebo oba změní barvu na jinou).
  3. Počkejte ~30 s, pak oba zkontrolují tým a přepnou servery.
- **Očekávání:** Konečný název i barva jsou **stejné na obou serverech**. Servery **nesmí zůstat rozejité** (např. červená na `agr1`, modrá na `agr2`). Tým se neztratí, nevznikne duplikát.

---

## Možný problém s identitou (UUID)

Tohle nemůžeš zkontrolovat přímo, ale **nahlas jako Critical**, pokud uvidíš:

- tvůj tým / postup / chunky existují na `agr1`, ale na `agr2` vypadají **resetovaně** (nebo naopak),
- objevíš se jako **jiný nebo zdvojený** hráč,
- ztratíš členství v týmu **jen na jednom serveru**,
- jiný hráč tě vidí jako **dvě různé identity**.

---

## Co testeři NEdělají

Nemáte přístup na server, takže tohle **není** vaše věc (a není potřeba k žádnému scénáři):

- restart / nasazení (deploy) serveru,
- vypínání databáze, simulace výpadku,
- čtení serverových logů, SSH, příkazy mimo hru.

Všechno výše uvedené se testuje **čistě ve hře**.

---

## Priorita / závažnost (pro hlášení)

| Závažnost | Příklad |
|---|---|
| **Critical** | Zmizel postup / claimy / členství; duplikace odměn; rozejitá identita; vyhození/převod nefunguje. |
| **High** | Barva/název/force-load se nesynchronizuje (G, H, K); cross-server nesync postupu (E). |
| **Medium** | Toast nechodí (D); konflikt souběžných změn se nesjednotí (R). |
| **Low** | Kosmetika, prodlevy v rámci pár vteřin, drobnosti v GUI. |

---

## Tipy

- Cross-server scénáře testujte **ve dvou** (jeden hráč nemůže být na obou serverech naráz).
- Když si nejsi jistý, jestli je chování bug, mrkni do [README.md](README.md).
- Krátké prodlevy (1–2 s) na synchronizaci jsou normální. Bug je, když se to **nesrovná vůbec**, nebo až po relogu (u věcí, co mají jet živě — viz G, N).
- Do hlášení dej **přesnou verzi modu**, **kdo byl na kterém serveru**, a **čas** — usnadní to dohledání v logu adminem.
