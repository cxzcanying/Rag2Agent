param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$Requests = 1000,
    [int]$Concurrency = 200,
    [double]$MaxErrorRate = 0.01,
    [double]$MaxP95Millis = 2000,
    [string]$OutputPath = "logs/capacity-report.json"
)

$ErrorActionPreference = "Stop"
$temporaryLoadReport = [System.IO.Path]::GetTempFileName()
try {
    $loadJson = & (Join-Path $PSScriptRoot "load-test-api.ps1") `
        -Url "$BaseUrl/api/health" `
        -Requests $Requests `
        -Concurrency $Concurrency `
        -OutputPath $temporaryLoadReport
    $load = Get-Content -Raw -LiteralPath $temporaryLoadReport | ConvertFrom-Json
    $healthy = 0
    if ($null -ne $load.statuses.'200') {
        $healthy = [int]$load.statuses.'200'
    }
    $errorRate = if ($Requests -eq 0) { 1 } else { 1 - ($healthy / $Requests) }
    $containerStats = @()
    try {
        $containerStats = docker stats --no-stream --format "{{json .}}" rag2agent-postgres rag2agent-redis rag2agent-minio rag2agent-docling |
            ForEach-Object { $_ | ConvertFrom-Json }
    } catch {
        $containerStats = @()
    }
    $report = [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        target = "$BaseUrl/api/health"
        requests = $Requests
        concurrency = $Concurrency
        errorRate = [Math]::Round($errorRate, 6)
        p95Millis = $load.p95Millis
        passed = ($errorRate -le $MaxErrorRate -and $load.p95Millis -le $MaxP95Millis)
        thresholds = [ordered]@{
            maxErrorRate = $MaxErrorRate
            maxP95Millis = $MaxP95Millis
        }
        load = $load
        containers = $containerStats
    }
    $directory = Split-Path -Parent $OutputPath
    if ($directory) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    $report | ConvertTo-Json -Depth 8
} finally {
    if (Test-Path -LiteralPath $temporaryLoadReport) {
        Remove-Item -LiteralPath $temporaryLoadReport
    }
}
