[CmdletBinding()]
param([switch]$Full)

$composeArgs = @()
if ($Full) { $composeArgs += @("--profile", "full") }
$composeArgs += @("ps", "--format", "json")

try {
    $raw = (& docker compose @composeArgs 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw $raw }
} catch {
    Write-Error "无法读取 Docker Compose 状态。请确认 Docker Desktop 已启动。$($_.Exception.Message)"
    exit 1
}

$services = @()
if ($raw) {
    try {
        # Docker Compose 每行输出一个 JSON 对象，而不是 JSON 数组。
        $services = @($raw -split "`r?`n" | Where-Object { $_.Trim() } |
            ForEach-Object { $_ | ConvertFrom-Json })
    }
    catch { Write-Error "Docker Compose 返回了无法解析的状态：$raw"; exit 1 }
}
if ($services.Count -eq 0) {
    Write-Host "没有正在运行的 Compose 服务。执行 docker compose up -d（完整拓扑加 -Full）。"
    exit 0
}

$services | Sort-Object Service | Format-Table `
    @{Label="服务"; Expression={$_.Service}},
    @{Label="容器"; Expression={$_.Name}},
    @{Label="状态"; Expression={$_.State}},
    @{Label="健康"; Expression={if ($_.Health) {$_.Health} else {"-"}}},
    @{Label="端口"; Expression={if ($_.Publishers) {
        ($_.Publishers | Where-Object { $_.PublishedPort -gt 0 } |
            ForEach-Object { "$($_.PublishedPort):$($_.TargetPort)" }) -join ", "
    } else { "-" }}} -AutoSize

$unhealthy = @($services | Where-Object {
    $_.State -ne "running" -or ($_.Health -and $_.Health -notin @("healthy", "-"))
})
if ($unhealthy.Count -gt 0) {
    Write-Host ""
    Write-Host "常见排查："
    Write-Host "- exited/created：查看 docker compose logs <service>，确认镜像、配置和依赖启动顺序。"
    Write-Host "- unhealthy：检查 healthcheck 命令及容器内端口；Windows 宿主端口请保持项目约定的高位端口。"
    Write-Host "- 应用连接失败：确认 application-dev.yml 与宿主映射端口一致，尤其是 PostgreSQL 15432、Redis 16379。"
    exit 2
}
Write-Host "所有已启动服务均处于 running/healthy。"
