# AegisGraph: Graph-Powered AML Intelligence for Swiss Private Banks

*A beginner-friendly guide for explaining the project at the hackathon. Everything runs on open-source software. All demo data is synthetic.*

## 1. Executive Summary & The Problem

### Objective in one minute

A bank has to answer two questions about its clients: **who really owns this money?** and **is it moving in a suspicious way?** Most banks answer them with separate tools. Criminals live in the gap between the two.

**AegisGraph** answers both in one place by storing everything in a *graph*, a network of dots (people, companies, accounts) joined by lines (owns, controls, sends money to).

- **Module 1, ShadowTrace** reads legal documents such as trust deeds and draws the ownership map.
- **Module 2, GraphWeaver** watches payments as they happen and raises an alert when they form a known money-laundering shape.

**Project objective:** build a fully open-source prototype for Swiss private banks that turns messy PDFs into a structured ownership graph and detects laundering patterns in a live payment stream, with every alert explained and traceable.

### Our approach in four ideas

1. **Think in networks.** Laundering is about relationships, so we store relationships directly instead of rows in tables.
2. **Use a private AI.** A local open-source language model reads documents on the bank’s own machines, so client data never leaves the building.
3. **Watch a rolling window.** Payments are checked against the last 60 minutes of activity, not one at a time.
4. **Explain everything.** Each ownership link carries a quote from the document, and each alert carries its accounts, transfers and a risk score.

### The problem in plain words

Criminals hide in two different ways, and each needs a different defence.

**Problem A: Hidden owners (static).** Regulation requires banks to know the *ultimate beneficial owner* (UBO): the real person behind an account. Criminals bury that person under layers: a trust owns a holding company in the British Virgin Islands, which owns a shell company in Panama, which owns the account. A *nominee director* (a paid stand-in) signs the papers. The proof lives in long PDFs, often scanned and in several languages. Reading them by hand is slow and easy to get wrong.

**Problem B: Hidden movement (dynamic).** Large transfers attract attention, so criminals split them (*micro-structuring*, also called *smurfing*). Two classic shapes:

- **Fan-out / fan-in:** one account sends many small transfers to many middlemen, who all forward the money to one final account.
- **Circular velocity:** money hops through a chain of accounts and comes back to where it started, to look like legitimate business.

A rule that checks one payment at a time sees nothing odd, because each payment alone is small and ordinary.

|  | Static: hidden owners | Dynamic: micro-structuring |
| --- | --- | --- |
| What is hidden | Who owns and controls | Where the money really goes |
| Where the evidence is | Legal documents (PDF) | The payment stream |
| Typical trick | Layers of shells, trusts, nominees | Many small transfers and loops |
| Old way | Read manually | One-payment-at-a-time rules |
| AegisGraph answer | Module 1: ShadowTrace | Module 2: GraphWeaver |

## 2. Solution Overview & Architecture

### Two modules, one graph

Think of **ShadowTrace as the detective who reads the case file** and **GraphWeaver as the CCTV camera watching the street**. Both write into the same graph, so the evidence sits in one place.

&#91;embedded content: architecture · dashboard, one Spring Boot app with two modules, Tika + Ollama, TinkerGraph, Kafka\]

The dashboard talks to one Spring Boot app. Its two modules share a single graph, with Tika and Ollama reading documents and Kafka carrying payments.

### What each layer does

| Layer | Component | Job |
| --- | --- | --- |
| Frontend | Single HTML page (vanilla JavaScript) | Upload PDFs, run simulations, show alerts and interactive graphs |
| Backend | Spring Boot 4, Java 21 | REST endpoints, scheduling, wiring everything together |
| AI | Ollama + Spring AI | Turns document text into typed Java records |
| Documents | Apache Tika | Pulls text out of PDFs, with OCR for scans if Tesseract is installed |
| Graph | TinkerGraph + Gremlin | Stores nodes and edges and answers pattern queries |
| Messaging | Apache Kafka + Spring Cloud Stream | Carries payments in and alerts out |

### The graph model

| Node | Key properties |
| --- | --- |
| Person | name, nationality, passportHash, isPEP, sanctionStatus |
| Company | name, jurisdiction, registrationNumber, riskScore, sanctionStatus |
| Trust | name, type, establishmentDate, governingLaw |
| BankAccount | iban, bankBIC, currency, balance |

| Edge | Meaning | Key properties |
| --- | --- | --- |
| OWNS | Owns a share of | percentage, votingRights, evidence quote, source document |
| CONTROLS | Directs without owning (director, nominee, trustee, settlor, protector) | role, evidence quote, source document |
| TRANSFERRED\_TO | Sent money to | amount, currency, timestamp, channel, transaction id, permanent or temporary |

A *PEP* is a politically exposed person. Every node has a `ref` such as `person:…`, `company:…` or `account:` followed by the IBAN. This shared naming scheme lets both modules point at the same things.

### How the modules work together

- They share one graph, one id scheme and one dashboard.
- ShadowTrace answers **who is behind these entities**. GraphWeaver answers **what are these accounts doing right now**.
- An alert lists the accounts involved, and the ownership map shows the people and companies behind a structure.
- **Honest status:** in this prototype the two sub-graphs are designed to connect but are not yet linked. Adding `Company → OWNS → BankAccount` edges, so that an alert names the humans behind it, is the first item on the roadmap (Section 6).

### Code layout

| Package | Contents |
| --- | --- |
| `shadowtrace` | Records, Spring AI parsing, Gremlin persistence, upload endpoint |
| `graphweaver` | Event model, stream consumer, edge writer, pattern engine, alert producer, simulator, read-only graph API |
| `seeder` | Bulk test data generator and its endpoint (off by default) |
| `static/index.html` | The whole dashboard |

## 3. Tech Stack & Open-Source Innovations

| Technology | What it does here | Why we chose it |
| --- | --- | --- |
| Java 21 + virtual threads | Runtime, with cheap threads for blocking work | Modern, stable, enterprise standard |
| Spring Boot 4 | Application framework | Fast to build, widely trusted in banks |
| Spring AI 2 | Calls the LLM and maps the answer to Java records | Type-safe *structured output*, no fragile text parsing |
| Ollama + open LLM | Runs the language model locally | No client data leaves the bank. The model is one config line: we use Qwen 2.5, and Llama 3.2 works by changing the model name |
| Apache Tika | Extracts text from PDFs | Mature, handles many file types, OCR-capable |
| SimpleVectorStore (Spring AI) | Keeps document chunks searchable | Open-source, in-memory, swappable for pgvector or Qdrant |
| Apache TinkerGraph + Gremlin | Graph database and query language | Open-source reference implementation of Apache TinkerPop, and the same queries run on bigger graph systems |
| Apache Kafka + Spring Cloud Stream | Real-time payment and alert streams | Industry-standard streaming, and the binder keeps the code broker-neutral |
| HTML, CSS, vanilla JS | Dashboard and graph drawing | No framework, no CDN, runs fully offline |

### Innovations worth pointing out

- **Local-first AI.** Sensitive deeds are processed on the bank’s own hardware.
- **Typed AI output.** The model’s answer lands directly in Java records, so bad data fails early.
- **Temporary vs permanent edges.** Ordinary transfers expire after 48 hours to keep the graph light. Large transfers (CHF 100,000 and above) and anything that triggers an alert are kept permanently as evidence.
- **Safe re-runs.** Uploads and payments are idempotent: sending the same data twice never creates duplicates.
- **Privacy by design.** Passport numbers are stored only as a salted SHA-256 hash.
- **Everything is explainable.** Quotes, evidence edges, alert ids and risk scores make each finding easy to audit.
- **Built-in proof.** A bulk generator plants known patterns inside thousands of random payments and reports planted vs detected.

Versions seen in our run: Spring Boot 4.1.1, Spring Cloud Stream 5.0.2 (Spring Cloud 2025.1.2), Kafka clients 4.2.1, Apache TinkerPop 3.7.3.

## 4. End-to-End User & Data Flow

### 4.1 From PDF upload to ownership graph

&#91;embedded content: ShadowTrace flow · seven steps from PDF upload to ownership graph\]

Step 4 is the only place a language model is used. Everything around it is ordinary, testable code.

1. **Upload.** The compliance officer drops a trust deed on the dashboard. The server checks it really is a PDF.
2. **Read.** Tika extracts the text.
3. **Chunk.** Long documents are cut into pieces the model can handle. The pieces are also stored for later search.
4. **Ask the AI.** Each piece goes to the local model with strict rules: use only facts that are written, never guess, quote the source. The answer is mapped into Java records.
5. **Merge.** Pieces about the same person or company share the same `ref`, so they combine into one picture.
6. **Write.** Nodes and edges are upserted into TinkerGraph. Links to entities the AI never named are skipped and reported.
7. **Show.** The dashboard displays counts, tables, the raw JSON and an interactive graph.

### 4.2 From payment to alert

&#91;embedded content: GraphWeaver flow · six steps from payment to alert\]

Steps 1 to 3 run for every payment. Steps 4 to 6 run on a timer, so a pattern is caught on the next scan after its last payment arrives.

1. **Arrive.** A payment event reaches the Kafka payments topic.
2. **Validate.** Invalid events are retried three times and then moved to a dead-letter queue.
3. **Record.** The service ignores duplicates, creates missing accounts and adds a `TRANSFERRED_TO` edge. It is *temporary* (expires after 48 hours) unless the amount is CHF 100,000 or more.
4. **Look back.** Every 30 seconds the engine scans the last 60 minutes of transfers.
5. **Score.** A match gets a risk score from 0 to 100, and its edges become permanent evidence.
6. **Alert.** An `AML_ALERT_EVENT` is published to Kafka and stored for the dashboard, which draws the suspicious subgraph.

### 4.3 The two patterns explained

&#91;embedded content: the two patterns · fan-out/fan-in and circular velocity\]

On the left, A splits money across many middlemen who regroup it at B. On the right, money travels a ring and lands back at A.

**Fan-out / fan-in is flagged when** all of these hold:

- every transfer is under CHF 10,000 (below the structuring ceiling);
- one origin reaches the same beneficiary through at least 20 different intermediaries;
- each second hop happens within 10 minutes of the first;
- each middleman forwards at least 90% of what it received.

**Circular velocity is flagged when:**

- money follows a chain of 3 to 6 transfers in time order and returns to the starting account;
- the whole loop completes within 60 minutes;
- no hop loses more than 10% of the value.

**Risk score** = half *frequency* (how many intermediaries or hops compared with the minimum) plus half *timing regularity* (evenly spaced transfers look automated). Severity bands: 80+ critical, 60+ high, 40+ medium, otherwise low. All thresholds are configuration values.

### 4.4 How to test it

**You need:** Java 21+, Maven, Ollama with a model pulled, a Kafka broker (for example `docker run -d -p 9092:9092 apache/kafka`), and optionally Tesseract for scanned PDFs.

**Start:** run `AEGIS_SEEDER_ENABLED=true ./mvnw spring-boot:run` and open `http://localhost:8080`.

| # | Test | What to do | What you should see |
| --- | --- | --- | --- |
| 1 | Health | Open the dashboard | Green “Live” chip and KPI cards |
| 2 | Module 1 | ShadowTrace page, upload a synthetic trust deed | Entity counts, tables, JSON, ownership graph |
| 3 | Fan-out / fan-in | GraphWeaver page, click *Fan-Out / Fan-In*, wait 2 seconds, click *Run detection now* | One alert, with 20 intermediaries in the subgraph |
| 4 | Circular | Click *Circular*, then *Run detection now* | One alert shaped as a six-account ring |
| 5 | No false alarm | Click *Noise* (200 payments), then run detection | No new alerts |
| 6 | Load test | Load test page, choose *Large*, run | About 600 entities and about 5,100 payments, with 6 planted patterns compared against those detected |
| 7 | No Kafka | Tick *Bypass Kafka* in the load test | Same results without a broker |

Command-line equivalent: `curl -X POST "localhost:8080/api/test/seed?scale=large"`.

**If something fails**

- *Kafka timeout at startup:* the broker is not reachable on `localhost:9092`. Start it, or check its advertised listener.
- *Extraction fails or is empty:* check that Ollama is running and the configured model is pulled.
- *Simulator or seeder disabled:* enable `aegis.graphweaver.simulator-enabled` or `aegis.seeder.enabled`.

Use synthetic documents only. Never upload real client data to a demo system.

## 5. Business Benefits for Swiss Private Banks

*This section explains how the prototype supports regulatory expectations. It is not a certified compliance product, and the compliance officer always makes the final decision. Confirm requirements with your legal team.*

### Supporting FINMA and AML-law expectations

| Swiss expectation (plain words) | How AegisGraph helps |
| --- | --- |
| Identify the beneficial owner behind a structure (under the Anti-Money Laundering Act and the banks’ due-diligence code) | ShadowTrace maps persons, trusts and companies and who owns or controls what, with a quote from the deed on each link |
| Monitor transactions using a risk-based approach (FINMA AML Ordinance) | GraphWeaver watches the live stream with tunable thresholds and risk scores |
| Clarify unusual transactions | Each alert shows the full subgraph: accounts, amounts and timing |
| Document findings and report suspicion to MROS | Alerts have ids, timestamps and permanently kept evidence edges, and the analyst decides whether to file |
| Protect client data | The AI runs locally, and passports are stored only as salted hashes |

### Why banks gain

- **Lower risk.** Relationship-aware detection catches patterns that single-payment rules miss.
- **Audit readiness.** Every ownership link has its source quote, and every alert has an id, a time window and evidence that is not deleted.
- **Faster analysts.** A graph drawing replaces hours of reading and cross-referencing. Measure the speed yourself with the Load test page.
- **Data sovereignty.** No cloud AI service is needed, and nothing leaves the bank’s infrastructure.
- **Lower cost, no lock-in.** Every component is open source, with no licence fees, and the code can be inspected.
- **Easy to extend.** Thresholds are configuration, and new patterns are new Gremlin queries.

## 6. Future Enhancements

### Known limits today

- The graph is in memory and is lost on restart.
- Payment accounts are not yet linked to ownership entities.
- Sanctions status is a placeholder (`false`).
- Alerts that fail to reach Kafka are logged, not retried.
- Thresholds compare nominal amounts with no currency conversion.
- There is no login, role model or audit log yet.
- Detection reads the clock, so replaying old data needs an event-time mode.

### Roadmap

| Priority | Enhancement | Why it matters |
| --- | --- | --- |
| 1 | Link accounts to owners and roll up UBO percentages through the layers | Alerts name the people behind the money |
| 2 | Persistent, scalable graph | Production volumes and restarts |
| 3 | Live sanctions and PEP screening | Required in real operations |
| 4 | Reliability: transactional outbox, replay with event time | No lost alerts, reproducible results |
| 5 | Security: login, roles, audit trail | Banking-grade access control |
| 6 | Analyst workflow: case management, draft suspicious-activity reports | From alert to filing |
| 7 | Smarter detection: more patterns, currency conversion, machine-learning scores | Fewer false alarms |
| 8 | Better extraction: German, French and Italian deeds, human review, accuracy tests | Real Swiss documents |

### Cloud graph scaling

- **Azure Cosmos DB for Apache Gremlin** keeps our Gremlin queries mostly unchanged, so migration is mainly connection configuration plus testing, because not every Gremlin step is supported. A Swiss region helps with data residency.
- **Neo4j** is a mature graph database with a strong ecosystem, but it uses the Cypher language, so queries would be rewritten.
- **JanusGraph** is an open-source option that speaks Gremlin natively.
- Our code already talks to the graph through one traversal source, so the swap is contained.

### Live sanctions sync

- Download the **SECO** (Swiss State Secretariat for Economic Affairs) sanctions list and the **OFAC** (US Treasury) list on a schedule.
- Match names with fuzzy matching and set `sanctionStatus` on people and companies.
- Re-screen the graph whenever a list changes, and flag every account within a few hops of a sanctioned party.

## Appendix A: Two-minute demo script

1. **Problem (20 s).** “Criminals hide who owns the money and how it moves. Banks check these separately.”
2. **ShadowTrace (40 s).** Upload a trust deed, show the extracted entities and the graph, and point at an evidence quote.
3. **GraphWeaver (40 s).** Click Fan-Out / Fan-In, run detection, open the alert and show the subgraph and risk score.
4. **Scale (20 s).** Run the Large load test and show planted vs detected.
5. **Close.** “Open source, runs locally, explainable, ready to scale.”

## Appendix B: Questions judges may ask

| Question | Short answer |
| --- | --- |
| Why a graph and not a database table? | Laundering is about relationships, and a graph follows them across many hops naturally |
| Why a local LLM? | Client data stays inside the bank, and there is no per-call fee |
| Can the AI make mistakes? | Yes. We force structured output, forbid guessing, keep source quotes, and a human reviews alerts |
| How do you know detection works? | The load test plants known patterns among thousands of random payments and reports planted vs detected |
| Will it scale? | The code uses standard Gremlin, so the graph can move to a distributed store. Today it is a prototype |
| Is it compliant? | It supports compliance work, but it is not a certified product |

## Appendix C: Glossary

| Term | Meaning |
| --- | --- |
| AML | Anti-money laundering |
| UBO | Ultimate beneficial owner, the real person behind an entity |
| Shell company | A company with no real business, often used to hide owners |
| Nominee director | A stand-in who is a company’s director on paper |
| Trust | A legal arrangement where a trustee holds assets for beneficiaries |
| PEP | Politically exposed person |
| Micro-structuring | Splitting money into small amounts to avoid attention |
| Graph | Dots (nodes) joined by lines (edges) |
| Gremlin | The query language for graphs in Apache TinkerPop |
| Kafka | A system that carries streams of events between applications |
| LLM | Large language model, the AI that reads text |
| MROS | Money Laundering Reporting Office Switzerland |
| DLQ | Dead-letter queue, a parking place for events that cannot be processed |
