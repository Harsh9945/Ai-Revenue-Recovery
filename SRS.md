# Software Requirements Specification
## AI Revenue Recovery Agent — Payment Failure Detection, Diagnosis & Recovery

**Prepared for:** Razorpay AI Buildathon — Track 03 (AI Revenue Recovery)
**Prepared by:** Harsh
**Version:** 1.2 (synced to actual implementation: real webhook ingestion, WhatsApp/SMS nudge, corrected EV cost model, resilience behavior, explicit track-scope note)
**Date:** 22 August 2026

---

## 1. Introduction

### 1.1 Purpose
This document specifies the functional and non-functional requirements for an **AI Revenue Recovery Agent** — a system that detects failed, degraded, or at-risk payment transactions in near real time, diagnoses the root cause of failure, and executes a bounded, auditable recovery action (retry, re-route, or escalate) to recover revenue that would otherwise be permanently lost.

### 1.2 Scope
The system ingests **real Razorpay Checkout webhook events** (via a public tunnel to a local endpoint), supplemented with a synthetic dataset for volume/edge-case testing, and:
- Classifies each failed or degraded transaction by failure type (soft vs. hard failure)
- Decides and executes a recovery action within defined guardrails
- Recovers hard failures via a **dynamic payment link sent over WhatsApp/SMS**, not just a logged message
- Tracks and reports recovery outcomes with a full audit trail
- Exposes a dashboard showing recovery rate, ₹ recovered, and false-retry cost

Out of scope: real production payment settlement, real bank/UPI integration beyond Razorpay's sandbox API, real merchant onboarding, and any offense-capable fraud logic (this is a defense/recovery system only, not a fraud-generation or bypass tool).

### 1.2.1 Track Scope Note
Razorpay's Track 03 brief spans six example directions (payment degradation recovery, checkout drop-off recovery, failed-subscription recovery, B2B receivables chasing, mandate retry sequencing, Hinglish voice recovery, promise-to-pay tracking). This build deliberately goes deep on **one direction — payment degradation -> root cause -> recovery** — rather than shallow across several, on the reasoning that the brief's stated evaluation bar ("measured money recovered across a batch, with compliant escalation, stopping rules, and an audit trail") rewards depth and rigor on a single workflow over partial coverage of many.

### 1.3 Intended Audience
Buildathon evaluators, and as a working reference for solo development (backend-heavy build, single developer).

### 1.4 Definitions & Abbreviations
| Term | Meaning |
|---|---|
| PSR | Payment Success Rate |
| Soft failure | Transient failure likely to succeed on retry (network blip, bank timeout, OTP delay) |
| Hard failure | Deterministic failure that will not succeed on retry (insufficient funds, blocked card, invalid CVV) |
| Business Decline | NPCI classification: transaction declined for business/policy reasons (e.g. insufficient funds, limit exceeded) — largely controllable by better checkout UX, not retry |
| Technical Decline | NPCI classification: transaction failed due to infrastructure issues (bank server down, timeout, network) — the category retries actually help |
| EV | Expected Value — the modeled ₹ gain/loss of taking a recovery action, used to decide whether to act |
| Recovery action | Automated response taken by the agent: retry, re-route, delay-and-retry, or escalate to human/customer nudge |
| Audit trail | Immutable log of every decision the agent made and why |

### 1.5 References
- Razorpay Buildathon Track 03 problem brief (AI Revenue Recovery)
- Industry benchmarks: ~70–75% of Indian cart abandonment is payment-failure-driven; smart retry logic recovers 15–20% of failed transactions in industry practice
- NPCI transaction decline classification (Business Decline vs. Technical Decline) — used as the grounding taxonomy for failure classification (Section 3, FR-2)

---

## 2. Overall Description

### 2.1 Product Perspective
A standalone backend system with an event-driven core (Kafka), a decision/classification layer (rules engine + LLM for ambiguous cases), a Redis-backed state/dedup layer, and a lightweight dashboard (React) for demo purposes. It simulates ingestion from a payment gateway rather than integrating with a live one.

### 2.2 Product Functions (Summary)
1. Ingest payment failure/degradation events
2. Classify failure type and root cause
3. Decide a bounded recovery action per policy rules
4. Execute the recovery action (simulated: retry call, delayed retry, alternate routing, customer nudge message)
5. Record outcome and update ledger
6. Report aggregate metrics (recovery rate, ₹ recovered, exceptions, false-retry cost)
7. Maintain a per-transaction audit trail

### 2.3 User Classes
| User | Description |
|---|---|
| Merchant/Finance Ops (demo persona) | Views dashboard, recovery metrics, and exception queue |
| System (autonomous agent) | Executes classification and recovery decisions without human intervention, within policy bounds |
| Evaluator/Judge | Reviews architecture, audit trail, and measured outcomes |

### 2.4 Operating Environment
- Backend: Spring Boot (core services), FastAPI (LLM/classification microservice)
- Messaging: Kafka (event stream for payment events)
- Cache/state: Redis (idempotency, retry counters, rate limits)
- Storage: PostgreSQL/MySQL (transaction ledger, audit log)
- Frontend: React dashboard
- Deployment: Docker Compose (local/demo), optionally AWS EC2

### 2.5 Design Constraints
- All recovery actions must be **bounded and gated** — no unlimited retries, no silent large-value auto-actions; anything above a configurable ₹ threshold or retry-count threshold routes to human escalation, not auto-execution
- Every decision must be explainable (rule fired or LLM rationale logged) and reversible in the audit trail
- No real financial movement — test-mode/synthetic only
- Must distinguish soft vs. hard failures before attempting any retry (retrying a hard failure wastes cost and irritates the customer)

### 2.6 Assumptions & Dependencies
- Synthetic dataset simulates realistic Indian payment failure distribution (bank timeout, OTP expiry, insufficient funds, blocked card, network drop, gateway timeout)
- Razorpay test-mode APIs (or a mocked equivalent) are used for the "recovery attempt" simulation
- LLM (Gemini/OpenAI-compatible) is used only for ambiguous classification and customer-nudge message generation, not for financial decisioning

---

## 3. System Features / Functional Requirements

### FR-1: Event Ingestion
- The system shall ingest payment lifecycle events (`payment.failed`, `payment.pending`, `payment.authorized`, `payment.captured`) via a Kafka topic `payment-events`.
- Each event shall include: transaction ID, merchant ID, amount, payment method, failure code (if any), timestamp, customer ID (pseudonymous), retry count so far.
- The system shall deduplicate events using Redis (idempotency key = transaction ID + event type).

### FR-2: Failure Classification
- The system shall classify each failed event using a **two-layer taxonomy**: first the NPCI-grounded top-level category, then a soft/hard retry-relevance tag derived from it.

  **Layer 1 — NPCI-grounded category:**
  | Category | Meaning | Example sub-reasons |
  |---|---|---|
  | Technical Decline | Infrastructure-side failure, not the customer's fault | bank server timeout, gateway timeout, network drop, switch unavailable |
  | Business Decline | Policy/customer-side decline | insufficient funds, limit exceeded, invalid CVV, blocked/expired card, OTP mismatch |

  **Layer 2 — Retry relevance (derived, not independently guessed):**
  - Technical Decline → mapped to **soft failure** (retry-worthy)
  - Business Decline → mapped to **hard failure** (not retry-worthy) — with one exception: OTP-not-delivered/OTP-expiry is a Business Decline in NPCI terms but behaves like a soft failure operationally (the payment itself was valid, delivery infra failed) — the system shall special-case this sub-reason into the soft-failure/retry-worthy path.

- Deterministic failure codes shall be classified via a rules table keyed to the taxonomy above.
- Ambiguous/unmapped failure codes or free-text gateway messages shall be classified via an LLM call, constrained to return one of the taxonomy's defined categories (never a free-form label), with the rationale logged.
- **If the LLM call fails or is rate-limited, the system shall fall back to a local regex/keyword classifier with zero added latency.** This fallback path shall explicitly emit a **lower confidence score** than a successful LLM classification would for the same input — the fallback is a narrower net than semantic classification and must not be treated as equally reliable. This ensures genuinely ambiguous cases still route to the exception queue via the confidence gate below, rather than being silently auto-actioned on a weaker classification.
- Classification confidence shall be recorded; low-confidence classifications route to the exception queue rather than auto-action.

### FR-3: Recovery Decision Engine
- The system shall not act on classification alone — every soft-failure (retry-worthy) transaction shall be passed through an **Expected Value (EV) gate** before any action is taken:

  ```
  EV(retry) = [P(recovery | failure_subtype, retry_attempt_no) × transaction_amount]
              − [bank_throttle_risk_cost + customer_friction_cost]
  ```

  **Note on cost terms:** Razorpay charges merchants only on successful transactions — failed retry attempts do not themselves cost a gateway fee. The real cost modeled here is (a) `bank_throttle_risk_cost`: a proxy cost representing the risk that issuing banks/UPI apps temporarily throttle or flag a card/UPI ID after repeated rapid retry attempts, which can cause the *customer's future legitimate payments* to fail too, and (b) `customer_friction_cost`: increasing with each successive retry on the same transaction, modeling checkout abandonment and trust erosion from repeated failed attempts. Both are configurable proxy weights, not literal rupee fees.

  - `P(recovery | ...)` is looked up from a fixed table seeded with industry-benchmark recovery probabilities per sub-reason (e.g., bank timeout ≈ higher recovery odds on first retry than on third). Self-updating from the system's own outcome history is a designed extension point, not implemented in this build (see Section 9).
  - The system shall attempt a retry **only if EV(retry) > 0**. If EV ≤ 0, the transaction is routed to customer nudge or exception queue instead of a wasted retry — this is what keeps the false-retry rate low and is the direct implementation of the brief's "measured $ recovered, not just retries attempted."

- For transactions that clear the EV gate: the system shall decide between **immediate retry**, **delayed retry** (exponential backoff), or **re-route** (alternate payment method/processor suggestion), based on failure sub-type and retry history.
- For hard failures, or soft failures that fail the EV gate: the system shall NOT retry; it shall generate a **customer nudge** (e.g., "try a different card/UPI app") instead.
- The system shall enforce a maximum retry count (configurable, default 3) and a cool-down window per transaction — enforced independently of the EV gate, as a hard ceiling.
- Transactions above a configurable value threshold (e.g., ₹50,000) shall always route to human escalation regardless of EV or failure type.

### FR-4: Recovery Execution
- The system shall simulate execution of the chosen action against a mock/test-mode payment API and capture the outcome (recovered / still failed / escalated).
- Each execution shall be idempotent — no duplicate charge attempts for the same transaction within the cool-down window.

### FR-5: Audit Trail
- The system shall persist an immutable log per transaction containing: event received, classification + rationale, decision made + rule/LLM reference, action executed, outcome, timestamp for each step.
- Audit records shall be queryable by transaction ID and by date range.

### FR-6: Metrics & Reporting
- The system shall compute and expose: overall recovery rate (%), total ₹ recovered, count of exceptions routed to humans, false-retry cost (retries attempted on transactions that turned out to be hard failures), average time-to-recovery.
- The dashboard shall display these metrics filterable by date range and failure type.

### FR-7: Exception Queue
- Low-confidence classifications, high-value transactions, and transactions exceeding max retries shall appear in a human-reviewable exception queue with full context.

---

## 4. External Interface Requirements

### 4.1 APIs
| Interface | Direction | Purpose |
|---|---|---|
| Kafka `payment-events` topic | Inbound | Receives simulated payment lifecycle events |
| Razorpay test-mode API (or mock) | Outbound | Simulates retry/re-route execution |
| LLM API (Gemini/OpenAI-compatible) | Outbound | Ambiguous classification + nudge message generation |
| Dashboard REST API | Inbound/Outbound | Serves metrics and audit data to React frontend |

### 4.2 Data Storage
- PostgreSQL/MySQL: transaction ledger, audit log, merchant/policy config
- Redis: idempotency keys, retry counters, rate-limit state

---

## 5. Non-Functional Requirements

| Category | Requirement |
|---|---|
| Performance | Classify and decide on an event within 2 seconds (p95), excluding LLM cold-start |
| Scalability | Kafka consumer group must handle at least 500 events/sec in a load test (demonstrates production-shape thinking even at buildathon scale) |
| Reliability | No transaction shall be double-charged; all retries idempotent |
| Auditability | 100% of decisions must have a logged rationale — no black-box auto-actions |
| Security | Customer/card data pseudonymized in all logs; no raw PAN/CVV stored |
| Explainability | Every auto-action must be traceable to a specific rule or LLM rationale string |
| Bounded autonomy | Hard ceiling on retry count and transaction value for autonomous action; everything else escalates |

---

## 6. User Flow

### 6.1 Primary Flow — Soft Failure Auto-Recovery
1. Payment event `payment.failed` (reason: `BANK_TIMEOUT`) arrives on Kafka
2. Classification service tags it as **soft failure** (rule match, high confidence)
3. Decision engine checks retry count (0/3) and transaction value (below threshold) → decides **delayed retry in 90s**
4. Recovery executor waits, re-attempts via test-mode API
5. Outcome: **success** → ledger updated, ₹ marked recovered, audit trail closed
6. Dashboard metrics refresh: recovery rate and ₹ recovered incremented

### 6.2 Alternate Flow — Hard Failure, No Retry
1. Event arrives: `payment.failed`, reason `INSUFFICIENT_FUNDS`
2. Classified as **hard failure**
3. Decision engine skips retry, generates customer nudge ("Try another payment method") via LLM
4. Nudge logged as sent (simulated); transaction marked **not recoverable automatically**
5. Audit trail records: no retry attempted, rationale: hard failure, action: nudge only

### 6.3 Alternate Flow — Ambiguous Classification → Exception Queue
1. Event arrives with an unmapped/free-text failure reason
2. Rules engine has no match → routed to LLM classifier
3. LLM returns classification with **confidence below threshold (e.g., <70%)**
4. System does NOT auto-act; transaction pushed to **exception queue** with full context
5. Human reviewer (demo: simulated) resolves manually; resolution fed back to improve rule table

### 6.4 Alternate Flow — High-Value Transaction
1. Event arrives: failed transaction, amount ₹75,000 (above ₹50,000 threshold)
2. Regardless of classification, system routes directly to **human escalation**
3. No autonomous retry or re-route executed
4. Audit trail records: escalation reason = value threshold exceeded

### 6.5 Flow Diagram

```mermaid
flowchart TD
    A[Payment Event Received] --> B{Dedup Check<br/>Redis}
    B -- Duplicate --> Z[Drop Event]
    B -- New --> C{Rule-based<br/>Classification}
    C -- Matched --> D{Failure Type?}
    C -- Unmatched --> E[LLM Classification]
    E --> F{Confidence >= 70%?}
    F -- No --> G[Exception Queue<br/>Human Review]
    F -- Yes --> D
    D -- Soft Failure<br/>Technical Decline / OTP --> H{Value > Threshold<br/>OR Retries >= Max?}
    D -- Hard Failure<br/>Business Decline --> I[Generate Customer Nudge<br/>No Retry]
    H -- Yes --> G
    H -- No --> EV{EV(retry) > 0?}
    EV -- No --> I
    EV -- Yes --> J[Decide Action:<br/>Immediate / Delayed Retry / Re-route]
    J --> K[Execute via Test-mode API]
    K --> L{Outcome?}
    L -- Success --> M[Mark Recovered<br/>Update Ledger]
    L -- Still Failed --> N{Retries Remaining?}
    N -- Yes --> J
    N -- No --> G
    I --> O[Log Audit Entry]
    M --> O
    G --> O
    O --> P[Update Dashboard Metrics]
```

---

## 7. Data Model (Key Entities)

**Transaction Event**
`transaction_id, merchant_id, customer_id_hash, amount, payment_method, failure_code, failure_message, retry_count, event_type, timestamp`

**Classification Record**
`transaction_id, classified_as (soft/hard), method (rule/llm), confidence, rationale, timestamp`

**Recovery Action Record**
`transaction_id, action_taken, executed_at, outcome, cost_of_attempt`

**Audit Log Entry**
`transaction_id, step, actor (system/human), detail, timestamp`

---

## 8. Success Metrics (for Demo/Evaluation)

| Metric | Target for demo dataset |
|---|---|
| Recovery rate on soft failures | ≥ 20% of soft-failure batch recovered (in line with industry benchmark of 15–20%) |
| False-retry rate | < 5% (hard failures mistakenly retried, or soft failures retried despite EV ≤ 0) |
| EV-gate precision | ≥ 90% of retries attempted should have positive realized ROI (recovered value > cost of attempts) |
| Audit completeness | 100% of transactions have a full, queryable audit trail |
| Exception queue precision | Low-confidence and high-value cases correctly routed, not auto-actioned |
| Latency | p95 classification+decision time < 2s |

---

## 9. Resilience & Fallback Behavior

| Risk | Mitigation | Reference |
|---|---|---|
| Public tunnel (Serveo) drops or is blocked mid-demo | Backend exposes local REST endpoint; identical webhook payloads can be replayed via a `curl` command against `/api/events/ingest` with zero functional difference | `start_services.ps1`, ingestion endpoint |
| Gemini API is slow, rate-limited, or unavailable | Classification falls back instantly to a local regex/keyword heuristic, at reduced confidence (see FR-2) — no downtime, degraded precision only on genuinely ambiguous cases | `main.py`, classification service |
| Duplicate webhook arrives while a transaction is already mid-retry | Two-layer guardrail: (1) event-level Redis lock keyed on `rzp_payment_id` with TTL; (2) state-level DB check before any workflow runs — transactions already in a terminal state (`RECOVERED`, `FAILED`, `ESCALATED`) are dropped, not reprocessed | `PaymentKafkaConsumer.java`, `RecoveryEngine.java` |
| `P(recovery)` probability source is not a published NPCI statistic | Values are seeded from published cart-recovery/gateway industry benchmarks (e.g., ~65% typical timeout-retry success), not claimed as exact NPCI data; designed to self-update from the system's own outcome history in a production deployment | `RecoveryEngine.java` |
| In-memory database loses all state on service restart | Uses H2 In-Memory Database for local development (which resets state on restart to keep test scenarios clean), but seamlessly switches to PostgreSQL in Docker profile configurations for full data persistence. | `application.yml` |

---

## 10. Appendix: Synthetic Dataset Schema (for testing)

Minimum 200 synthetic transaction records covering, tagged by NPCI category:
- 40% Technical Decline → soft failures (bank timeout, network drop, gateway timeout, switch unavailable)
- 5% Business Decline, OTP sub-type → treated as soft failure per the FR-2 special case (OTP expiry/not-delivered)
- 35% Business Decline, other → hard failures (insufficient funds, blocked/expired card, invalid CVV, limit exceeded)
- 15% ambiguous/free-text failure reasons (to exercise LLM classification path, constrained to return a valid taxonomy category)
- 5% high-value transactions (to exercise escalation path, independent of EV or classification)

Each record should also carry a simulated `P(recovery)` ground-truth value per sub-reason, so the EV gate's decisions can be checked against a known-correct answer during evaluation (i.e., you can prove your EV gate made the right call, not just that it ran).

---

*End of document.*
