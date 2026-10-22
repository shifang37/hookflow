#!/usr/bin/env pwsh
# Fault drills against real, separate processes.
#
# Runs the roles as independent JVMs (api / relay / worker), then:
#   D1  kills the relay while work is queued, restarts it, checks the backlog drains
#   D2  kills the worker mid-request, checks the lease is reclaimed and the delivery
#       still completes - with the dead attempt recorded as ABANDONED
#   D3  stops Redis and pauses RabbitMQ in turn, checks events are still accepted
#
# Every observation is printed with a timestamp so the output can be pasted
# straight into docs/failures/ as the drill record.
#
# Prerequisite: docker compose up -d   (use DB_PUBLISH_PORT if 3306 is taken)
#
# English-only on purpose (no non-ASCII) so it runs on any Windows PowerShell
# without encoding surprises.

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReceiverUrl = 'http://127.0.0.1:9099/hook',
    [int]$DbPort = 13306,
    [switch]$SkipSetup
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Start-Process fails when both NO_PROXY and no_proxy exist in the environment.
foreach ($k in @('no_proxy')) {
    try { [Environment]::SetEnvironmentVariable($k, $null) } catch { }
}

$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$jarItem = Get-ChildItem (Join-Path $root 'target') -Filter 'hookflow-*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
if (-not $jarItem) { throw 'Build first: .\mvnw.cmd -DskipTests package' }
$jar = $jarItem.FullName
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$script:procs = @{}
$script:token = $null

function Stamp($msg) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) }

function Start-Role {
    param([string]$Name, [string]$Profile, [string[]]$ExtraArgs = @())
    $out = Join-Path $logDir "$Name.log"
    if (Test-Path $out) { Remove-Item $out -Force }
    # A tiny .bat avoids Start-Process entirely: on this class of machine the
    # process environment carries both HTTP_PROXY and http_proxy, and Start-Process
    # refuses to build a child environment from that ("item already added").
    $bat = Join-Path $logDir "$Name.bat"
    $cmd = '"' + $java + '" -Duser.timezone=UTC -jar "' + $jar + '" ' +
           "--spring.profiles.active=$Profile " + ($ExtraArgs -join ' ') +
           ' > "' + $out + '" 2>&1'
    Set-Content -Path $bat -Value (@('@echo off') + $script:envLines + @($cmd)) -Encoding ASCII
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
        -Arguments @{ CommandLine = 'cmd.exe /c "' + $bat + '"' } | Out-Null
    Start-Sleep -Milliseconds 800
    Stamp "started $Name profile=$Profile"
}

function Get-RolePid {
    param([string]$Profile, [string]$Port)
    $filter = "profiles.active=$Profile"
    $candidates = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($filter) }
    if ($Profile -eq 'api' -and $candidates.Count -gt 1) {
        $candidates = $candidates | Select-Object -First 1
    }
    return ($candidates | Select-Object -First 1).ProcessId
}

function Stop-Role {
    param([string]$Name, [string]$Profile, [switch]$Hard)
    $procId = Get-RolePid -Profile $Profile
    if (-not $procId) { Stamp "no $Name process found (already stopped?)"; return }
    if ($Hard) {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Stamp "KILLED $Name (pid $procId) - SIGKILL equivalent, no graceful shutdown"
    } else {
        Stop-Process -Id $procId -ErrorAction SilentlyContinue
        Stamp "stopped $Name (pid $procId)"
    }
    Start-Sleep -Milliseconds 500
}

function Wait-Health {
    param([int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 3
            if ($h.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [hashtable]$Headers = @{})
    $req = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; ContentType = 'application/json' }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    Invoke-RestMethod @req
}

function Get-Unpublished {
    $out = docker exec -e MYSQL_PWD=hookflow hookflow-mysql mysql -uhookflow -N -B -e `
        "SELECT COUNT(*) FROM hookflow.outbox WHERE status IN ('NEW','PUBLISHING')" 2>$null
    return ($out | Select-Object -First 1)
}

function Submit-Event {
    param([string]$Type = 'order.paid')
    $headers = @{ Authorization = "Bearer $script:token"; 'Idempotency-Key' = 'drill-' + [guid]::NewGuid().ToString('N') }
    return (Invoke-Api -Method POST -Path '/v1/events' -Headers $headers `
        -Body @{ eventType = $Type; payload = @{ amount = 42; drill = $true } }).eventId
}

function Get-Delivery {
    param([long]$EventId)
    $page = Invoke-Api -Method GET -Path "/v1/deliveries?eventId=$EventId" -Headers @{ Authorization = "Bearer $script:token" }
    if ($page.items.Count -eq 0) { return $null }
    return (Invoke-Api -Method GET -Path "/v1/deliveries/$($page.items[0].id)" -Headers @{ Authorization = "Bearer $script:token" })
}

$env:DB_PORT = $DbPort
$env:HOOKFLOW_MASTER_KEY = 'hookflow-dev-master-key-change-in-production-32'
$env:HOOKFLOW_BOOTSTRAP_TOKEN = 'hookflow-dev-bootstrap-token'

# Children are launched through WMI, so they do NOT inherit this session's
# environment. Every variable the app needs is written into each .bat instead.
$script:envLines = @(
    "set DB_PORT=$DbPort",
    "set HOOKFLOW_MASTER_KEY=$env:HOOKFLOW_MASTER_KEY",
    "set HOOKFLOW_BOOTSTRAP_TOKEN=$env:HOOKFLOW_BOOTSTRAP_TOKEN"
)

try {
    if (-not $SkipSetup) {
        Write-Host ''
        Write-Host '=== setup ================================================' -ForegroundColor Cyan

        # Short lease so D2 finishes in seconds instead of a minute. A single worker
        # is deliberate: it makes "the victim really was holding the delivery"
        # deterministic instead of a coin flip.
        $leaseArgs = @('--hookflow.lease.ttl-seconds=8', '--hookflow.lease.sweep-interval-ms=2000')

        # The demo receiver is on loopback, and the production profiles refuse that
        # on purpose (SSRF defence). The drill relaxes it explicitly, which also
        # demonstrates that the default really is strict.
        Start-Role -Name 'api' -Profile 'api' -ExtraArgs @('--hookflow.security.egress.allow-private-targets=true')
        Start-Role -Name 'relay' -Profile 'relay'
        Start-Role -Name 'worker' -Profile 'worker' -ExtraArgs $leaseArgs

        $mockBat = Join-Path $logDir 'receiver.bat'
        $mockCmd = '"' + $java + '" -cp "' + (Join-Path $root 'target\classes') +
                   '" dev.hookflow.tools.MockReceiver 9099 > "' + (Join-Path $logDir 'receiver.log') + '" 2>&1'
        Set-Content -Path $mockBat -Value (@('@echo off') + $script:envLines + @($mockCmd)) -Encoding ASCII
        Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
            -Arguments @{ CommandLine = 'cmd.exe /c "' + $mockBat + '"' } | Out-Null
        Stamp 'started receiver on 9099'

        if (-not (Wait-Health)) { throw 'api never became healthy - see logs/api.log' }
        Stamp 'api is UP'

        $tenant = Invoke-Api -Method POST -Path '/v1/admin/tenants' `
            -Headers @{ 'X-Bootstrap-Token' = $env:HOOKFLOW_BOOTSTRAP_TOKEN } `
            -Body @{ name = 'drill-' + (Get-Date -Format 'HHmmss'); rateLimit = 1000 }
        $script:token = $tenant.apiKey
        Stamp "tenant $($tenant.tenantId) provisioned"

        $auth = @{ Authorization = "Bearer $($script:token)" }
        $fast = Invoke-Api -Method POST -Path '/v1/endpoints' -Headers $auth `
            -Body @{ url = $ReceiverUrl; subscribedTypes = 'order.paid'; maxAttempts = 6 }
        Stamp "fast endpoint $($fast.id) -> $ReceiverUrl"

        # 3s response keeps the attempt in flight long enough to be killed, while
        # staying under the 10s read timeout so the retry can succeed.
        $slowUrl = $ReceiverUrl + '?delay=3000'
        $slow = Invoke-Api -Method POST -Path '/v1/endpoints' -Headers $auth `
            -Body @{ url = $slowUrl; subscribedTypes = 'drill.slow'; maxAttempts = 6 }
        Stamp "slow endpoint $($slow.id) -> $slowUrl"

        $warmId = Submit-Event
        Start-Sleep -Seconds 4
        $warm = Get-Delivery -EventId $warmId
        Stamp "warm-up delivery $($warm.delivery.id) status=$($warm.delivery.status) attempts=$($warm.delivery.attemptCount)"
    }

    Write-Host ''
    Write-Host '=== D1: kill the relay while work is queued ==============' -ForegroundColor Cyan
    Stop-Role -Name 'relay' -Profile 'relay' -Hard

    $d1Events = @()
    foreach ($i in 1..5) { $d1Events += (Submit-Event) }
    Stamp "submitted 5 events while the relay is dead"
    Start-Sleep -Seconds 3
    Stamp "unpublished outbox rows = $(Get-Unpublished)   <- parked, not lost"

    Start-Role -Name 'relay' -Profile 'relay'
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ((Get-Unpublished) -eq '0') { break }
        Start-Sleep -Seconds 2
    }
    Stamp "relay back after $([int]$stopwatch.Elapsed.TotalSeconds)s; unpublished outbox rows = $(Get-Unpublished)"
    foreach ($id in $d1Events) {
        $d = Get-Delivery -EventId $id
        Stamp "  event $id -> $($d.delivery.status) attempts=$($d.delivery.attemptCount) attemptsRecorded=$($d.attempts.Count)"
    }

    Write-Host ''
    Write-Host '=== D2: kill the worker mid-request ======================' -ForegroundColor Cyan
    $d2 = Submit-Event -Type 'drill.slow'
    Stamp "submitted event $d2 against the 3s-slow endpoint"
    Start-Sleep -Seconds 1
    Stop-Role -Name 'worker' -Profile 'worker' -Hard
    Stamp 'worker killed while the request is in flight; now waiting out the 8s lease'
    Start-Role -Name 'worker' -Profile 'worker' -ExtraArgs @('--hookflow.lease.ttl-seconds=8', '--hookflow.lease.sweep-interval-ms=2000')

    $deadline = (Get-Date).AddSeconds(90)
    $d2detail = $null
    while ((Get-Date) -lt $deadline) {
        $d2detail = Get-Delivery -EventId $d2
        if ($null -ne $d2detail -and $d2detail.delivery.status -in @('SUCCEEDED', 'DEAD')) { break }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $d2detail) { Stamp 'D2: no delivery row found' }
    else {
        Stamp "delivery $($d2detail.delivery.id) status=$($d2detail.delivery.status) attempts=$($d2detail.delivery.attemptCount)"
        foreach ($a in $d2detail.attempts) {
            $dur = if ($null -eq $a.durationMs) { 'n/a' } else { "$($a.durationMs)ms" }
            Stamp "  attempt $($a.attemptNo) $($a.status) error=$($a.errorClass) duration=$dur"
        }
    }

    Write-Host ''
    Write-Host '=== D3: stop dependencies ================================' -ForegroundColor Cyan
    docker stop hookflow-redis | Out-Null
    Stamp 'redis stopped'
    $d3a = Submit-Event
    Stamp "event $d3a accepted while Redis is down (fail-open) -> ok"
    docker start hookflow-redis | Out-Null
    Stamp 'redis started'

    docker pause hookflow-rabbitmq | Out-Null
    Stamp 'rabbitmq paused'
    $d3b = Submit-Event
    Start-Sleep -Seconds 3
    Stamp "event $d3b accepted while RabbitMQ is paused; unpublished outbox = $(Get-Unpublished)"
    docker unpause hookflow-rabbitmq | Out-Null
    Stamp 'rabbitmq resumed'

    $deadline = (Get-Date).AddSeconds(60)
    $d3detail = $null
    while ((Get-Date) -lt $deadline) {
        $d3detail = Get-Delivery -EventId $d3b
        if ($null -ne $d3detail -and $d3detail.delivery.status -eq 'SUCCEEDED') { break }
        Start-Sleep -Seconds 2
    }
    if ($null -ne $d3detail) {
        Stamp "event $d3b -> $($d3detail.delivery.status) attempts=$($d3detail.delivery.attemptCount)"
        Stamp "unpublished outbox after resume = $(Get-Unpublished)"
    }

    Write-Host ''
    Write-Host '=== drills complete; logs in ./logs =====================' -ForegroundColor Green
}
finally {
    foreach ($profile in @('api', 'relay', 'worker')) {
        $leftover = Get-RolePid -Profile $profile
        if ($leftover) { Stop-Process -Id $leftover -Force -ErrorAction SilentlyContinue }
    }
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains('MockReceiver') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Host 'all drill processes stopped'
}





