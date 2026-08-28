import os
import json
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import google.generativeai as genai

# Setup logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("classification-service")

app = FastAPI(title="AI Revenue Recovery - Classification Service")

# Initialize Gemini if API key is present
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
if GEMINI_API_KEY:
    logger.info("GEMINI_API_KEY found, initializing Gemini client...")
    genai.configure(api_key=GEMINI_API_KEY)
else:
    logger.info("GEMINI_API_KEY not found. Fallback Mock LLM will be used.")

class ClassificationRequest(BaseModel):
    transaction_id: str
    failure_code: str
    failure_message: str

class ClassificationResponse(BaseModel):
    classified_as: str  # "soft" or "hard"
    npci_category: str  # "Technical Decline" or "Business Decline"
    method: str         # "rule" or "llm"
    confidence: float
    rationale: str

class NudgeRequest(BaseModel):
    transaction_id: str
    failure_code: str
    failure_message: str
    amount: float
    payment_method: str

class NudgeResponse(BaseModel):
    nudge_message: str

# Rule-based taxonomy mappings
RULES_TAXONOMY = {
    # Technical Declines (Soft Failures)
    "BANK_TIMEOUT": {
        "npci_category": "Technical Decline",
        "classified_as": "soft",
        "rationale": "Rule match: Bank server timeouts are transient infrastructure issues."
    },
    "NETWORK_DROP": {
        "npci_category": "Technical Decline",
        "classified_as": "soft",
        "rationale": "Rule match: Network drops represent transient communication failures."
    },
    "GATEWAY_TIMEOUT": {
        "npci_category": "Technical Decline",
        "classified_as": "soft",
        "rationale": "Rule match: Gateway server timeout is a transient technical issue."
    },
    "SWITCH_UNAVAILABLE": {
        "npci_category": "Technical Decline",
        "classified_as": "soft",
        "rationale": "Rule match: NPCI or banking switch unavailability is a transient system failure."
    },
    # Special-case Business Declines (Soft Failures)
    "OTP_TIMEOUT": {
        "npci_category": "Business Decline",
        "classified_as": "soft",
        "rationale": "Rule match: OTP timeout is technically a Business Decline, but operationally retry-worthy."
    },
    "OTP_NOT_DELIVERED": {
        "npci_category": "Business Decline",
        "classified_as": "soft",
        "rationale": "Rule match: OTP delivery network failure behaves like a soft failure."
    },
    # Business Declines (Hard Failures)
    "INSUFFICIENT_FUNDS": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Insufficient funds is a deterministic user-side limit issue."
    },
    "LIMIT_EXCEEDED": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Transaction limit exceeded is a deterministic bank policy decline."
    },
    "INVALID_CVV": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Invalid CVV is an authentication failure requiring customer intervention."
    },
    "BLOCKED_CARD": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Blocked card is a deterministic security freeze."
    },
    "EXPIRED_CARD": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Expired card is a deterministic user profile error."
    },
    "OTP_MISMATCH": {
        "npci_category": "Business Decline",
        "classified_as": "hard",
        "rationale": "Rule match: Incorrect OTP entry requires fresh customer input, not automatic retry."
    }
}

def classify_via_mock_llm(code: str, message: str) -> dict:
    """Fallback parser that simulates LLM semantic analysis using keyword heuristics."""
    msg = message.lower()
    code_upper = code.upper()
    
    # 1. Look for technical / transient keywords (Soft Failures)
    tech_keywords = ["timeout", "network", "drop", "down", "hang", "unresponsive", "delay", "disconnect", "wobble", "error 5", "slow", "retryable"]
    if any(kw in msg for kw in tech_keywords) or any(kw in code_upper for kw in tech_keywords):
        return {
            "classified_as": "soft",
            "npci_category": "Technical Decline",
            "confidence": 0.85,
            "rationale": f"LLM (Mock): Identified infrastructure-related term in error message '{message}'."
        }
    
    # 2. Look for business / user policy declines (Hard Failures)
    biz_hard_keywords = ["fund", "balance", "limit", "block", "expired", "wrong", "invalid", "decline", "policy", "abandoned", "walked away", "cvv", "cancel", "deny", "fraud"]
    if any(kw in msg for kw in biz_hard_keywords) or any(kw in code_upper for kw in biz_hard_keywords):
        return {
            "classified_as": "hard",
            "npci_category": "Business Decline",
            "confidence": 0.90,
            "rationale": f"LLM (Mock): Detected user/policy constraint error in message '{message}'."
        }
        
    # 3. Handle OTP errors (behaves like soft failure)
    otp_keywords = ["otp timeout", "otp delay", "sms delay", "not received"]
    if any(kw in msg for kw in otp_keywords) or any(kw in code_upper for kw in otp_keywords):
        return {
            "classified_as": "soft",
            "npci_category": "Business Decline",
            "confidence": 0.80,
            "rationale": f"LLM (Mock): Message suggests OTP delivery issue, mapped as soft failure."
        }

    # 4. Completely ambiguous cases
    return {
        "classified_as": "hard",
        "npci_category": "Business Decline",
        "confidence": 0.55,
        "rationale": f"LLM (Mock): Failed to map ambiguous code '{code}' and message '{message}' with high confidence."
    }

async def classify_via_gemini(code: str, message: str) -> Optional[dict]:
    """Call Gemini to classify the failed payment event."""
    if not GEMINI_API_KEY:
        return None
        
    prompt = f"""
    You are a payment failure analysis model.
    Classify the following payment failure event:
    Failure Code: {code}
    Failure Message: {message}

    Taxonomy Rules:
    1. Category must be either "Technical Decline" (bank server timeouts, network drop, switches unavailable) or "Business Decline" (insufficient funds, limit exceeded, incorrect OTP, invalid CVV, blocked card).
    2. Retry Relevance (classified_as) must be "soft" (temporary/transient issue worthy of automatic retry) or "hard" (permanent policy/authentication issue).
    3. Note: OTP-timeout or OTP-not-delivered is a "Business Decline" but operationally behaves as a "soft" failure.
    
    Response must be a valid JSON object matching this schema:
    {{
        "classified_as": "soft" | "hard",
        "npci_category": "Technical Decline" | "Business Decline",
        "confidence": <float between 0.0 and 1.0>,
        "rationale": "<brief explanation of the classification decision>"
    }}
    Do not output any markdown formatting, only the JSON block.
    """
    try:
        model = genai.GenerativeModel('gemini-1.5-flash')
        response = model.generate_content(prompt)
        text = response.text.strip()
        # Clean potential markdown wrap
        if text.startswith("```json"):
            text = text[7:-3].strip()
        elif text.startswith("```"):
            text = text[3:-3].strip()
        data = json.loads(text)
        return data
    except Exception as e:
        logger.error(f"Error calling Gemini: {e}")
        return None

@app.post("/classify", response_model=ClassificationResponse)
async def classify_failure(request: ClassificationRequest):
    code_upper = request.failure_code.upper()
    
    # Layer 1: Check deterministic rule engine
    if code_upper in RULES_TAXONOMY:
        match = RULES_TAXONOMY[code_upper]
        return ClassificationResponse(
            classified_as=match["classified_as"],
            npci_category=match["npci_category"],
            method="rule",
            confidence=1.0,
            rationale=match["rationale"]
        )
    
    # Layer 2: LLM analysis (Gemini with Mock fallback)
    llm_res = None
    if GEMINI_API_KEY:
        llm_res = await classify_via_gemini(request.failure_code, request.failure_message)
        
    if llm_res:
        return ClassificationResponse(
            classified_as=llm_res.get("classified_as", "hard"),
            npci_category=llm_res.get("npci_category", "Business Decline"),
            method="llm",
            confidence=llm_res.get("confidence", 0.70),
            rationale=llm_res.get("rationale", "Classified via Gemini AI.")
        )
    else:
        # Fallback to local regex-based mock LLM
        mock_res = classify_via_mock_llm(request.failure_code, request.failure_message)
        return ClassificationResponse(
            classified_as=mock_res["classified_as"],
            npci_category=mock_res["npci_category"],
            method="llm",
            confidence=mock_res["confidence"],
            rationale=mock_res["rationale"]
        )

@app.post("/nudge", response_model=NudgeResponse)
async def generate_nudge(request: NudgeRequest):
    msg = request.failure_message.lower()
    code = request.failure_code.upper()
    
    # Check if Gemini key is available
    if GEMINI_API_KEY:
        prompt = f"""
        Generate a friendly, concise customer nudge notification (SMS / WhatsApp) for a failed payment.
        Failure Context:
        - Failure Code: {code}
        - Failure Message: {request.failure_message}
        - Amount: Rs. {request.amount}
        - Payment Method: {request.payment_method}
        
        Keep it extremely short (under 15 words). Provide direct, actionable advice (e.g., check account balance, try paying via UPI, etc.).
        Do not use placeholder text or quotes in the final output.
        """
        try:
            model = genai.GenerativeModel('gemini-1.5-flash')
            response = model.generate_content(prompt)
            nudge_text = response.text.strip()
            return NudgeResponse(nudge_message=nudge_text)
        except Exception as e:
            logger.error(f"Error generating nudge via Gemini: {e}")
            # Fall back to template
            
    # Default Rule-based Templates
    if "balance" in msg or "insufficient" in msg or code == "INSUFFICIENT_FUNDS":
        nudge = "Insufficient funds in your account. Please pay using another card or UPI to complete order."
    elif "otp" in msg or code in ["OTP_TIMEOUT", "OTP_NOT_DELIVERED", "OTP_MISMATCH"]:
        nudge = "OTP verification failed. Please try again or pay via another payment method."
    elif "cvv" in msg or "card" in msg or code in ["INVALID_CVV", "EXPIRED_CARD", "BLOCKED_CARD"]:
        nudge = "Payment declined by card bank. Try using UPI or another card."
    else:
        nudge = f"Your payment of Rs. {request.amount:.2f} failed due to a bank server error. Click here to try again via UPI."
        
    return NudgeResponse(nudge_message=nudge)

@app.get("/health")
def health_check():
    return {"status": "ok", "llm_mode": "Gemini API" if GEMINI_API_KEY else "Fallback Mock LLM"}
