# Testovací plán — ftb-quests-mysql-sync

Tento dokument je pro **herní testery** (hráče). Cílem je ověřit, že synchronizace FTB Quests mezi servery `agr1` a `agr2` funguje správně — postup, odměny, týmové chování a synchronizace napříč servery.

Chování modu (co se má dít) je popsané v [README.md](README.md), sekce „Chování odměn a kapitol". Tento plán z toho vychází.

> **Jste hráči, ne admini.** Restart serveru, nasazení nové verze ani vypnutí databáze **nedělejte** — tohle spouští **admin**. U scénářů, kde je to potřeba (F a H), je u kroků napsáno „**admin**" — domluvíte se s ním na čase a vy jen ověříte výsledek ve hře.

---

## Než začnete

- **Připojení:** připojte se na **proxy** (adresu a port dodá admin). Nepřipojujte se nikam jinam — proxy vás pustí na herní servery.
- **Přepínání mezi servery:** ve hře napište:
  - `/server agr1` — přepne vás na server **agr1**
  - `/server agr2` — přepne vás na server **agr2**
- **Jste vždy jen na jednom serveru naráz.** Proto scénáře „cross-server" (A souběh, D toast, E konzistence) potřebují **dva testery současně** — jeden na `agr1`, druhý na `agr2`. Sám to neuděláš (nemůžeš být na obou).
- **Tým:** většina scénářů je týmová. Potřebujete **tým aspoň o 2 hráčích** (FTB Teams). Někdo navíc ať je **sólo** (bez party) pro sólo scénáře.
- **Itemy na splnění questů:** pokud na quest nemáš suroviny, řekni **adminovi** — dá ti je / zapne creative. Cílem testu je synchronizace, ne grind.
- **Verze:** admin řekne, jakou **verzi modu** servery běží. Verzi piš do každého hlášení.

---

## Jak zapisovat výsledky

1. **Každý scénář** označ jako **PASS** / **FAIL** / **BLOCKED** (nešlo otestovat).
2. **Když něco nesedí (FAIL):** založ issue na GitHubu přes šablonu **„Bug report (CZ)"**:
   <https://github.com/sky-ux941/ftb-quests-mysql-sync/issues/new/choose>
3. Do issue **vždy** napiš: scénář (např. „B – progrese"), **server** (agr1 / agr2 / oba), **verzi modu**, kroky, co jsi čekal a co se reálně stalo. Ideálně screenshot a **přibližný čas** (kvůli dohledání v logu).
4. Když je to **PASS**, stačí odškrtnout v tomto seznamu (níže) — issue nezakládej.

### Checklist (odškrtávej)

- [ ] A — Shop (opakovatelný týmový)
- [ ] B — Progrese (1 odměna na tým, jen jednou)
- [ ] C — Sólo kapitoly (rank, Quality of Life)
- [ ] D — Completion toast (notifikace)
- [ ] E — Cross-server konzistence postupu
- [ ] F — Zachování postupu po nasazení/restartu (s adminem)
- [ ] G — Týmové operace (join / leave / přidání člena)
- [ ] H — Chování při výpadku databáze (s adminem)

---

## Scénáře

> Legenda: **Cíl** = co ověřujeme · **Kroky** = hlavní postup · **Očekávání** = co se má stát.
> Přepnutí na druhý server = `/server agr1` nebo `/server agr2`.

### A — Shop (opakovatelný, týmově sdílený)

- **Cíl:** Shop odměny jsou týmové a opakovatelné; jedno nárokování na odměnu na tým za cyklus; po nárokování celé sady se quest dá koupit znovu.
- **Kroky:**
  1. V týmu (2 hráči) na `agr1` kup v shopu sadu a **nárokuj všechny odměny** v questu.
  2. Sleduj, zda se po nárokování celé sady **quest resetuje** (jde koupit znovu).
  3. Druhý člen (`/server agr2`) se podívá na stejný shop quest.
  4. **Souběh:** oba členové (každý na jiném serveru) zkusí nárokovat **stejnou** odměnu skoro současně.
- **Očekávání:**
  - Každou odměnu lze v rámci jednoho cyklu nárokovat **jen jednou za tým** (ne každý člen zvlášť).
  - Po nárokování celé sady se quest **resetuje** a tým může zaplatit za novou sadu.
  - Při souběhu jeden uspěje, druhý dostane hlášku **„Reward already claimed on another server."** (to je správně, ne bug).

### B — Progrese (1 odměna na tým, jen jednou)

- **Cíl:** U progresních kapitol (Progression Tracking, Through the Ages Regular, a Extras: power / applied_energistics / mystical_agriculture_gregified) je **jedna odměna na tým**, nárokovatelná **jen jednou** (neopakovatelně), synchronizovaná mezi servery.
- **Kroky:**
  1. Tým dokončí progresní quest.
  2. Jeden člen na `agr1` **nárokuje** odměnu.
  3. Ostatní členové (`agr1` i `/server agr2`) se na tu odměnu podívají.
- **Očekávání:**
  - Item dostane **jen ten, kdo nárokoval**.
  - Ostatní vidí odměnu jako **nárokovanou** a **nemůžou** ji vzít znovu — na obou serverech.

### C — Sólo kapitoly (rank, Quality of Life)

- **Cíl:** Kapitoly **ranks** a **Quality of Life** zůstávají **per-hráč** (každý si bere své).
- **Kroky:**
  1. Dva členové téhož týmu splní stejný sólo quest.
  2. Každý si **nárokuje vlastní** odměnu.
- **Očekávání:**
  - **Oba** dostanou odměnu nezávisle. Nárokování jednoho **neblokuje** druhého (na rozdíl od scénáře B).

### D — Completion toast (notifikace)

- **Cíl:** Po dokončení týmového questu dostanou notifikaci „Quest Complete" (vpravo nahoře) **všichni online členové týmu na obou serverech** — ne jen ten, kdo quest splnil.
- **Kroky:**
  1. Tým má aspoň 2 online členy, **každý na jiném serveru** (jeden `agr1`, druhý `/server agr2`).
  2. Jeden z nich **dokončí** týmový quest.
  3. (Volitelně) třetí člen je **offline** — ověř po jeho přihlášení.
- **Očekávání:**
  - **Online** spoluhráč na **druhém** serveru dostane toast vpravo nahoře.
  - **Offline** člen toast **nedostane** (notifikace je jen pro online v okamžiku dokončení).

### E — Cross-server konzistence postupu

- **Cíl:** Postup týmu je stejný bez ohledu na server.
- **Kroky:**
  1. Na `agr1` udělej kus postupu (splň quest / part tasku).
  2. Přepni se na druhý server (`/server agr2`) a otevři stejný quest. (Nebo to zkontroluje druhý tester, který tam už je.)
  3. Zkus to i opačně (postup na `agr2` → kontrola na `agr1`).
- **Očekávání:**
  - Postup nasčítaný na jednom serveru je **vidět i na druhém** (krátká prodleva na sync je OK).

### F — Zachování postupu po nasazení / restartu ⚠️ (s adminem)

- **Cíl:** **Klíčový test.** Po nasazení nové verze / restartu serveru **nesmí** zmizet postup hráčů.
- **Kroky:**
  1. Udělej výrazný postup (splň několik questů, nárokuj odměny) na `agr1`.
  2. Domluv s **adminem** čas — **admin** server `agr1` **restartuje / nasadí novou verzi** (ty to nespouštíš).
  3. Po naběhnutí se **znovu přihlas** (`/server agr1`) a zkontroluj quest book.
  4. Zopakuj pro `agr2`.
- **Očekávání:**
  - Po přihlášení je **veškerý postup zachovaný** (splněné questy zůstávají splněné, nárokované odměny zůstávají nárokované).
  - **Nově přidané** questy z nového balíku se logicky ukážou jako nesplněné — to **není** bug.
- **Pozn.:** Pokud postup po restartu **zmizí**, je to **vážný bug** — pošli issue s časem restartu a co přesně zmizelo (před/po). Tahle oblast je teď pod aktivním zkoumáním, takže detailní hlášení (co, na kterém serveru, kdy) hodně pomůže.

### G — Týmové operace (join / leave / přidání člena)

- **Cíl:** Změny členství v týmu se synchronizují napříč servery.
- **Kroky:**
  1. Sólo hráč **vstoupí** do týmu — ověř, že vidí týmový postup (i po `/server agr2`).
  2. Člen **opustí** tým — ověř chování.
  3. **Přes servery:** člen na `agr1` přidá do party hráče, který je na `agr2`.
- **Očekávání:**
  - Po vstupu hráč **přebírá týmový postup**; členství je vidět na obou serverech.
  - Přidání člena napříč servery se **propíše** (nový člen vidí týmová data).

### H — Chování při výpadku databáze (s adminem)

- **Cíl:** Když je databáze nedostupná, nárokování odměn je **zamítnuto** (ochrana proti duplikátům). Tenhle scénář řídí **admin**.
- **Kroky:**
  1. **Admin** v domluvený čas dočasně **odpojí** přístup k databázi.
  2. Tester zkusí **nárokovat** odměnu.
  3. **Admin** databázi zase zapne.
- **Očekávání:**
  - Během výpadku je nárokování **zamítnuto**. Žádný duplikát, žádný „volný" claim.
  - Po obnovení DB nárokování zase funguje.

---

## Co testeři NEdělají

Tohle je věc **admina**, ne hráče-testera:

- restart / nasazení (deploy) serveru,
- vypínání databáze,
- čtení serverových logů, SSH, příkazy mimo hru.

U scénářů **F** a **H** se s adminem jen domluvíte na čase a ověříte výsledek **ve hře**.

---

## Priorita / závažnost (pro hlášení)

| Závažnost | Příklad |
|---|---|
| **Critical** | Zmizel postup / odměny (scénář F); duplikace odměn napříč servery. |
| **High** | Týmová odměna jde vzít víc členy (B selhává); cross-server nesync (E). |
| **Medium** | Toast nechodí spoluhráčům (D); membership se nepropíše (G). |
| **Low** | Kosmetika, prodlevy v rámci pár vteřin, drobnosti v GUI. |

---

## Tipy

- Cross-server scénáře testujte **ve dvou** (jeden hráč nemůže být na obou serverech naráz).
- Když si nejsi jistý, jestli je chování bug, mrkni do [README.md](README.md) sekce „Chování odměn a kapitol".
- Krátké prodlevy (1–2 s) na synchronizaci jsou normální. Bug je, když se to **nesrovná vůbec**.
- Do hlášení dej **přesnou verzi modu**, **server** (`agr1`/`agr2`) a **čas** — usnadní to dohledání v logu.
