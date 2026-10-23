param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$CredentialsPath = "D:\code\RAG2Agent\eval-data\miracl\prepared\credentials.json",
    [switch]$ExpectRegistrationDisabled,
    [switch]$ExpectRuntimeConfigForbidden,
    [switch]$VerifyRedisFallback
)

$ErrorActionPreference = "Stop"

function Assert-Status {
    param(
        [string]$Name,
        [int]$Actual,
        [int]$Expected
    )
    if ($Actual -ne $Expected) {
        throw "$Name expected HTTP $Expected but got $Actual"
    }
    Write-Host "PASS $Name -> HTTP $Actual"
}

$health = Invoke-WebRequest -Uri "$BaseUrl/api/health" -SkipHttpErrorCheck
Assert-Status "public health" $health.StatusCode 200

$prometheus = Invoke-WebRequest -Uri "$BaseUrl/actuator/prometheus" -SkipHttpErrorCheck
Assert-Status "internal prometheus" $prometheus.StatusCode 200

$anonymousKb = Invoke-WebRequest -Uri "$BaseUrl/api/knowledge-bases" -SkipHttpErrorCheck
Assert-Status "anonymous knowledge bases" $anonymousKb.StatusCode 401

$options = Invoke-WebRequest -Uri "$BaseUrl/api/auth/registration-options" -SkipHttpErrorCheck
Assert-Status "registration options" $options.StatusCode 200
$optionData = $options.Content | ConvertFrom-Json
if ($ExpectRegistrationDisabled -and $optionData.data.registrationEnabled) {
    throw "registration is expected to be disabled"
}
Write-Host "INFO registrationEnabled=$($optionData.data.registrationEnabled)"

if ($ExpectRegistrationDisabled) {
    $registerBody = ConvertTo-Json @{
        username = "security_probe_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        password = "probe-pass-123"
        nickname = "security probe"
        inviteCode = "invalid"
    }
    $register = Invoke-WebRequest -Uri "$BaseUrl/api/auth/register" -Method Post `
        -ContentType "application/json" -Body $registerBody -SkipHttpErrorCheck
    Assert-Status "disabled registration" $register.StatusCode 403
}

if (-not (Test-Path -LiteralPath $CredentialsPath)) {
    Write-Host "SKIP authenticated checks: credentials file not found"
    exit 0
}

$credentials = Get-Content -Raw -LiteralPath $CredentialsPath | ConvertFrom-Json
$login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ username = $credentials.username; password = $credentials.password })
if ($login.code -ne "0" -or [string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "login did not return a token"
}

$headers = @{ satoken = $login.data.token }
$authenticatedKb = Invoke-WebRequest -Uri "$BaseUrl/api/knowledge-bases" `
    -Headers $headers -SkipHttpErrorCheck
Assert-Status "authenticated knowledge bases" $authenticatedKb.StatusCode 200

$costs = Invoke-WebRequest -Uri "$BaseUrl/api/costs/summary" `
    -Headers $headers -SkipHttpErrorCheck
Assert-Status "cost summary" $costs.StatusCode 200

$budget = Invoke-WebRequest -Uri "$BaseUrl/api/costs/budget" `
    -Headers $headers -SkipHttpErrorCheck
Assert-Status "cost budget" $budget.StatusCode 200

$memories = Invoke-WebRequest -Uri "$BaseUrl/api/agent/memories?limit=1" `
    -Headers $headers -SkipHttpErrorCheck
Assert-Status "memory list" $memories.StatusCode 200

$runtime = Invoke-WebRequest -Uri "$BaseUrl/api/runtime-config" `
    -Headers $headers -SkipHttpErrorCheck
if ($ExpectRuntimeConfigForbidden) {
    Assert-Status "ordinary user runtime config" $runtime.StatusCode 403
}
Write-Host "INFO runtimeConfigStatus=$($runtime.StatusCode)"

if ($VerifyRedisFallback) {
    docker stop rag2agent-redis | Out-Null
    try {
        $statuses = @()
        foreach ($attempt in 1..8) {
            $response = Invoke-WebRequest -Uri "$BaseUrl/api/documents/upload" `
                -Headers $headers -SkipHttpErrorCheck
            $statuses += $response.StatusCode
        }
        if (@($statuses | Where-Object { $_ -in @(429, 503) }).Count -eq 0) {
            throw "Redis outage did not protect high-risk requests: $($statuses -join ',')"
        }
        Write-Host "PASS redis outage high-risk requests rejected -> $($statuses -join ',')"
    }
    finally {
        docker start rag2agent-redis | Out-Null
        foreach ($attempt in 1..20) {
            docker exec rag2agent-redis redis-cli ping 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                break
            }
            Start-Sleep -Milliseconds 500
        }
    }
}
