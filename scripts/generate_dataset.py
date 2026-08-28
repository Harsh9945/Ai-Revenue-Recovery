import json
import random
import uuid
import urllib.request
import time

def generate_dataset():
    records = []
    merchants = ["mer_razor101", "mer_swiggy99", "mer_zomato77", "mer_netflix88", "mer_amazon44"]
    methods = ["UPI", "CARD", "NETBANKING"]

    print("Generating synthetic payment failure events...")

    # 1. Technical Declines (40% -> 80 records) - Soft Failures
    tech_codes = [
        ("BANK_TIMEOUT", "Bank server did not respond within the 15s window.", 0.70),
        ("NETWORK_DROP", "TCP connection reset by peer banking network switch.", 0.85),
        ("GATEWAY_TIMEOUT", "Processor gateway timed out during authorization handshake.", 0.65),
        ("SWITCH_UNAVAILABLE", "NPCI switch reported transient failure (HTTP 503).", 0.55)
    ]
    for _ in range(80):
        code, msg, p_rec = random.choice(tech_codes)
        records.append({
            "transactionId": f"pay_tech_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(100.0, 15000.0), 2),
            "paymentMethod": random.choice(methods),
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })

    # 2. Business Decline - OTP type (5% -> 10 records) - Soft Failures (Special Case)
    otp_codes = [
        ("OTP_TIMEOUT", "OTP verification timed out; no customer input within 60 seconds.", 0.80),
        ("OTP_NOT_DELIVERED", "Telecom carrier reported SMS delivery failure.", 0.85)
    ]
    for _ in range(10):
        code, msg, p_rec = random.choice(otp_codes)
        records.append({
            "transactionId": f"pay_otp_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(100.0, 5000.0), 2),
            "paymentMethod": "CARD",
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })

    # 3. Business Decline - Other (35% -> 70 records) - Hard Failures
    biz_codes = [
        ("INSUFFICIENT_FUNDS", "Declined by bank: Insufficient account balance.", 0.0),
        ("LIMIT_EXCEEDED", "Transaction amount exceeds cumulative daily bank limit.", 0.0),
        ("INVALID_CVV", "Card authorization failed: Invalid CVV code entered.", 0.0),
        ("BLOCKED_CARD", "Card blocked by issuer due to suspected fraudulent activity.", 0.0),
        ("EXPIRED_CARD", "Card authorization rejected: Card has expired.", 0.0),
        ("OTP_MISMATCH", "OTP verification failed: Invalid OTP code entered.", 0.0)
    ]
    for _ in range(70):
        code, msg, p_rec = random.choice(biz_codes)
        records.append({
            "transactionId": f"pay_biz_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(100.0, 20000.0), 2),
            "paymentMethod": "CARD" if code in ["INVALID_CVV", "EXPIRED_CARD", "BLOCKED_CARD"] else random.choice(methods),
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })

    # 4. Ambiguous declines (15% -> 30 records) - For testing LLM Classifier
    amb_soft = [
        ("UPSTREAM_FLAP", "Upstream gateway connection was flapping momentarily.", 0.75),
        ("AUTH_WOBBLE", "Bank authorization server experienced a temporary wobble.", 0.80),
        ("NETWORK_FLAP", "Internal switch connection was briefly interrupted.", 0.70)
    ]
    amb_hard = [
        ("CARD_EXPIRED_MOCK", "Your card credentials have passed the expiration threshold.", 0.0),
        ("INSUFFICIENT_FUNDS_ALT", "Declined due to inadequate funds in customer account.", 0.0),
        ("999_UNKNOWN", "The card was reported stolen or lost by the cardholder.", 0.0)
    ]
    for _ in range(15):
        code, msg, p_rec = random.choice(amb_soft)
        records.append({
            "transactionId": f"pay_amb_s_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(200.0, 10000.0), 2),
            "paymentMethod": random.choice(methods),
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })
    for _ in range(15):
        code, msg, p_rec = random.choice(amb_hard)
        records.append({
            "transactionId": f"pay_amb_h_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(200.0, 10000.0), 2),
            "paymentMethod": random.choice(methods),
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })

    # 5. High-Value transactions (5% -> 10 records) - Escalation Path
    for i in range(10):
        if i % 2 == 0:
            code, msg, p_rec = "BANK_TIMEOUT", "Bank server did not respond within the 15s window.", 0.70
        else:
            code, msg, p_rec = "INSUFFICIENT_FUNDS", "Declined by bank: Insufficient account balance.", 0.0
            
        records.append({
            "transactionId": f"pay_high_{uuid.uuid4().hex[:12]}",
            "merchantId": random.choice(merchants),
            "customerIdHash": f"cust_{random.randint(1000, 9999)}",
            "amount": round(random.uniform(50001.0, 100000.0), 2),
            "paymentMethod": random.choice(methods),
            "failureCode": code,
            "failureMessage": msg,
            "groundTruthPRecovery": p_rec,
            "eventType": "payment.failed"
        })

    # Shuffle to simulate random arrivals
    random.shuffle(records)

    # Write output to JSONL file
    filepath = "scripts/dataset.jsonl"
    with open(filepath, "w") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")

    print(f"Generated {len(records)} synthetic transaction records at {filepath}")
    
    # Auto-ingestion via Spring Boot API
    print("Streaming dataset to Payment Processor REST API (http://localhost:8080/api/events/ingest)...")
    url = "http://localhost:8080/api/events/ingest"
    success_count = 0
    error_count = 0

    for idx, record in enumerate(records):
        data = json.dumps(record).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=5) as response:
                if response.getcode() == 200:
                    success_count += 1
                else:
                    error_count += 1
        except Exception as e:
            error_count += 1
            # If the server is not up yet or fails completely, print a single message and break
            if idx == 0:
                print(f"Connection to backend failed: {e}. Make sure Spring Boot app is running on port 8080.")
                break

        # Slight throttle to simulate streaming
        if idx % 10 == 0 and idx > 0:
            print(f"Ingested {idx} / {len(records)} events...")
            time.sleep(0.1)

    print(f"\nIngestion summary:")
    print(f"Successfully Ingested: {success_count} events")
    print(f"Failed to Ingest: {error_count} events")

if __name__ == "__main__":
    generate_dataset()
