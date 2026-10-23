param(
    [string]$Url = "http://localhost:18080/api/health",
    [int]$Requests = 1000,
    [int]$Concurrency = 200,
    [string]$Token = "",
    [string]$OutputPath = "logs/load-test.json"
)

$ErrorActionPreference = "Stop"
$handler = [System.Net.Http.HttpClientHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$gate = [System.Threading.SemaphoreSlim]::new($Concurrency, $Concurrency)
$statuses = [System.Collections.Concurrent.ConcurrentDictionary[int, int]]::new()
$latencies = [System.Collections.Concurrent.ConcurrentBag[double]]::new()
$watch = [System.Diagnostics.Stopwatch]::StartNew()

$tasks = 1..$Requests | ForEach-Object {
    [System.Threading.Tasks.Task]::Run([Action]{
        param($index)
        $gate.Wait()
        $requestWatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Url)
            if ($Token) {
                $request.Headers.Add("satoken", $Token)
            }
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
            $statuses.AddOrUpdate([int]$response.StatusCode, 1, { param($key, $value) $value + 1 })
            $response.Dispose()
        } catch {
            $statuses.AddOrUpdate(-1, 1, { param($key, $value) $value + 1 })
        } finally {
            $latencies.Add($requestWatch.Elapsed.TotalMilliseconds)
            $gate.Release()
        }
    }.GetNewClosure(), $_)
}

[System.Threading.Tasks.Task]::WaitAll($tasks)
$watch.Stop()
$sorted = $latencies.ToArray() | Sort-Object
$p95 = if ($sorted.Count -eq 0) { 0 } elseif ($sorted.Count -eq 1) {
    $sorted[0]
} else {
    $sorted[[Math]::Min($sorted.Count - 1, [int][Math]::Ceiling($sorted.Count * 0.95) - 1)]
}

$report = [ordered]@{
    url = $Url
    requests = $Requests
    concurrency = $Concurrency
    durationSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 2)
    p95Millis = [Math]::Round($p95, 2)
    memoryMb = [Math]::Round([GC]::GetTotalMemory($false) / 1MB, 2)
    statuses = [ordered]@{}
}
foreach ($entry in $statuses.GetEnumerator() | Sort-Object Key) {
    $report.statuses[[string]$entry.Key] = $entry.Value
}

$directory = Split-Path -Parent $OutputPath
if ($directory) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
$report | ConvertTo-Json -Depth 5
