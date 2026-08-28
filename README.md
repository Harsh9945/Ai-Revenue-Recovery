# AI-Powered Revenue Recovery Agent (Razorpay Buildathon 2026)

The **AI-Powered Revenue Recovery Agent** is a real-time system designed to tackle payment failures, reduce customer drop-offs, and optimize recovery costs for e-commerce merchants using Razorpay.

Instead of blindly retrying failed payments (which wastes gateway fees and causes customer drop-off), this system uses a combination of **Heuristic Rule Engines**, **Google Gemini AI**, and **Expected Value (EV) Decision Gates** to decide whether to automatically retry a transaction or nudge the customer with a custom recovery payment link.

---

## 🏗️ System Architecture & Services

The system is split into three main microservices, coordinated via Kafka and Redis:

```mermaid
flowchart TD
    %% Frontend / External
    RZP_Checkout["Razorpay Checkout Link"] -- "1. Payment Failure" --> Serveo["Serveo Public SSH Tunnel"]
    Serveo -- "2. Webhook Event" --> SpringBoot["Spring Boot Processor (Port 8080)"]

    %% Core Processing
    SpringBoot -- "3. Check Duplicate (Key-Value)" --> Redis[("Redis Cache")]
    SpringBoot -- "4. Save State" --> H2Database[("H2 Mem Database")]
    SpringBoot -- "5. Failure Payload" --> FastAPI["FastAPI Classification Service (Port 8000)"]

    %% AI Classification
    FastAPI -- "6. Deterministic Check" --> RuleEngine["Rule-based Taxonomy Mapping"]
    FastAPI -- "7. Semantic Analysis" --> Gemini["Google Gemini AI (1.5-Flash)"]

    %% Decision Logic
    FastAPI -- "8. Return Classification & Nudge Text" --> SpringBoot
    SpringBoot -- "9. Run EV Gate Decision" --> EV_Gate{"Expected Value Gate"}

    %% Recovery Actions
    EV_Gate -- "EV > 0" --> KafkaRetry["Publish to Kafka 'payment-events'"]
    KafkaRetry -- "Background Auto-Retry" --> RZP_Api["Razorpay Sandbox API"]
    EV_Gate -- "EV <= 0 (Or Hard Failure)" --> CreatePayLink["Create Dynamic Payment Link"]
    CreatePayLink -- "Send WhatsApp/SMS Nudge" --> Customer["Notify Customer"]
    
    %% Dashboard
    H2Database -- "Live Poll / SSE" --> ViteFrontend["React Dashboard (Port 5173)"]
```

### 1. Core Services Setup
* **`payment-processor` (Spring Boot, Port 8080):**
  Houses the core state machine, H2 database ledger, automated gateway simulators, and Kafka message listeners.
* **`classification-service` (FastAPI + Python, Port 8000):**
  Hosts the failure classification engine and Gemini API client. It categorizes errors into **Soft Failures** (transient network issues) or **Hard Failures** (limits, invalid details).
* **`dashboard-frontend` (React + Tailwind CSS + TypeScript, Port 5173):**
  Provides a real-time web portal showing transaction ledgers, audit trails, and financial recovery metrics.
* **`infrastructure` (Docker Compose):**
  Manages Kafka (KRaft mode) for transaction message queues and Redis for webhook deduplication.

---

## 🔄 Core Ingestion & Recovery Pipelines

### A. Webhook Ingestion & Redis Deduplication
1. Razorpay sends a `payment.failed` event to the `/api/events/ingest` endpoint.
2. The `payment-processor` intercepts the request and queries **Redis** with the payment ID (`rzp_payment_id`).
3. If the key exists, the request is flagged as a duplicate and ignored. If it is new, it is locked in Redis and saved to the database as `PENDING`.

### B. Failure Taxonomy & Gemini Classification
When a transaction fails, it is sent to the FastAPI classification service. It processes the error in two layers:
1. **Rule Engine:** Matches deterministic codes (e.g., `LIMIT_EXCEEDED` $\rightarrow$ Hard, `BANK_TIMEOUT` $\rightarrow$ Soft).
2. **Gemini LLM (Gemini 1.5-Flash):** If the error code is unknown or ambiguous, Gemini parses the failure message semantically to determine if it is retryable, returning a JSON response.
3. **Nudge Generation:** If the failure is a Hard error, Gemini generates a custom, friendly, 15-word customer nudge copy adapted to the failure context.

### C. The Expected Value (EV) Decision Gate
For transient Soft Failures, the backend decides whether to auto-retry based on expected profit vs friction costs:

\[EV = (P_{\text{recovery}} \times \text{Amount}) - \text{Total Cost}\]

Where:
* $P_{\text{recovery}}$ is the probability of recovery (derived from NPCI historical bank timeout recovery rates).
* **Total Cost** = Gateway Retry Fee + Bank Query Load Cost + Customer Fatigue Friction Penalty.
* **Customer Fatigue (Friction Cost):** Every retry adds a delay penalty ($currentAttempt \times ₹2$) to represent loss of customer interest. If $EV > 0$, the system pushes the event to **Kafka** to trigger a background retry. If $EV \le 0$, it terminates retries and sends a customer nudge.

---

## 📊 Dataset Loading & Verification
To test the system at scale, you can ingest a synthetic dataset representing hundreds of transactions:

1. **`generate_dataset.py`:** Generates 200 mock transactions representing technical declines, timeouts, blocked cards, and insufficient funds.
2. **Kafka Producer:** Publishes these events directly to the Kafka pipeline (`payment-events`).
3. **Ledger Update:** The backend processes these events asynchronously, showcasing how the Expected Value gate and the rules handle high volume on your React dashboard.

---

## 💡 Domain Concepts & Hackathon Highlights

### A. NPCI Decline Standards (National Payments Corporation of India)
Our failure categorization directly matches NPCI guidelines used by Indian banks and gateways (like UPI & RuPay):
* **Technical Declines (TD):** Failures due to bank host downtime, network timeout, connection drops, or system unavailability. *Operationally, these are transient and safe for automatic background retries.*
* **Business Declines (BD):** Failures due to insufficient funds, customer cancellation, incorrect OTP/PIN inputs, card limits, or security blocks. *Operationally, these are deterministic and require user intervention (a nudge).*

### B. Mathematical Definitions of Dashboard Metrics
* **Recovery Rate:** The percentage of recovered payment volume relative to total failed transactions:
  \[\text{Recovery Rate} = \left( \frac{\text{Recovered Count}}{\text{Total Ingested}} \right) \times 100\]
* **False-Retry Wasted Cost:** Sum of gateway fees spent on retry attempts that ultimately still failed:
  \[\text{Wasted Cost} = \sum (\text{Retry Fee} + \text{Bank Load Cost}) \text{ for actions ending in FAILED}\]
  *This is the direct cost savings metric our Expected Value Gate optimizes.*
* **Realized Recovery Gains:** Total transaction volume successfully recovered via automated retries and smart nudges.

### C. Redis Deduplication Schema
To prevent duplicate webhook processing during network retries, the processor uses Redis as a lock store:
* **Key Schema:** `dedup:<transaction_id>` (locks active transactions with a TTL).
* **Action:** Incoming webhooks check this key. If the key exists, the message is immediately acknowledged and discarded, preventing race conditions or double-charging.

---

## 🚀 Running the Project Locally

### Prerequisites
* Java 21+ & Python 3.10+
* Docker Desktop (running)

### Setup & Launch
1. Clone the repository and configure your `.env` file in the root folder:
   ```env
   GEMINI_API_KEY=your_gemini_api_key_here
   RAZORPAY_KEY_ID=your_razorpay_key_id
   RAZORPAY_KEY_SECRET=your_razorpay_key_secret
   ```
2. Start the Docker containers:
   ```bash
   docker-compose up -d
   ```
3. Run the automated startup script to launch all microservices:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\start_services.ps1
   ```
4. Access the React dashboard at: **`http://localhost:5173`**
