import urllib.request
import json

payload = {
    "transactionId": "pay_REHEARSAL_202",
    "merchantId": "mer_demo",
    "customerIdHash": "cust_999",
    "amount": 1945.00,
    "paymentMethod": "CARD",
    "failureCode": "BANK_TIMEOUT",
    "failureMessage": "Bank server timeout.",
    "groundTruthPRecovery": 0.0
}

url = "http://localhost:8080/api/events/ingest"
headers = {"Content-Type": "application/json"}

try:
    req = urllib.request.Request(
        url, 
        data=json.dumps(payload).encode("utf-8"), 
        headers=headers,
        method="POST"
    )
    with urllib.request.urlopen(req) as res:
        print(f"Status Code: {res.status}")
        print(f"Response: {res.read().decode()}")
except Exception as e:
    print(f"Error calling endpoint: {e}")
