# ========================================================
# AI REVENUE RECOVERY AGENT STARTUP SCRIPT
# Loads credentials from .env and launches all services
# ========================================================

# 1. Parse and load environment variables from .env
if (Test-Path ".env") {
    Write-Host "Loading environment variables from .env..." -ForegroundColor Green
    Get-Content .env | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $key, $value = $line -split '=', 2
            if ($key -and $value) {
                $envKey = $key.Trim()
                $envValue = $value.Trim()
                [System.Environment]::SetEnvironmentVariable($envKey, $envValue, [System.EnvironmentVariableTarget]::Process)
            }
        }
    }
} else {
    Write-Host "[ERROR] .env file not found! Please create it." -ForegroundColor Red
    exit 1
}

# 2. Check if placeholders were replaced
if ($env:RAZORPAY_KEY_ID -eq "rzp_test_YOUR_KEY_ID_HERE" -or $env:RAZORPAY_KEY_ID -eq "") {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Yellow
    Write-Host "WARNING: You need to replace the placeholders in the .env file!" -ForegroundColor Yellow
    Write-Host "Please open d:\razorpay\.env and paste your actual Razorpay keys." -ForegroundColor Yellow
    Write-Host "==================================================================" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# 3. Start Classification Service (FastAPI) in background
Write-Host "Starting Classification Service (FastAPI) on port 8000..." -ForegroundColor Cyan
Start-Process -FilePath "d:\razorpay\classification-service\venv\Scripts\python.exe" -ArgumentList "-m uvicorn main:app --host 127.0.0.1 --port 8000" -WorkingDirectory "d:\razorpay\classification-service" -WindowStyle Hidden

# 4. Start React Frontend in background
Write-Host "Starting Dashboard Frontend (Vite) on port 5173..." -ForegroundColor Cyan
Start-Process -FilePath "npm.cmd" -ArgumentList "run dev" -WorkingDirectory "d:\razorpay\dashboard-frontend" -WindowStyle Hidden

# 5. Start Core Payment Processor (Spring Boot) in foreground
Write-Host "Starting Core Payment Processor (Spring Boot) on port 8080..." -ForegroundColor Green
cd d:\razorpay\payment-processor
java -jar target/payment-processor-0.0.1-SNAPSHOT.jar
