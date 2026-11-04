#!/usr/bin/env pwsh
# Fault drills against real, separate processes.
#
# Runs the roles as independent JVMs (api / relay / worker), then:
#   D1  kills the relay while work is queued, restarts it, checks the backlog drains
#   D2  kills the worker mid-request, checks the lease is reclaimed and the delivery
#       still completes - with the dead attempt recorded as ABANDONED
#   D3  stops Redis and pauses RabbitMQ in turn, checks events are still accepted
#
# On the way out the drill deletes the `drill-*` tenant it provisioned, and
# pre-flight sweeps any tenant a killed earlier run left behind, so replaying
# the drill does not pile up tenants (and their frozen rows) in the demo
# database. Pass -KeepTenant to leave them in place for inspection instead.
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
    [switch]$SkipSetup,
    [switch]$KeepTenant
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
           Where-Object { $_.Name -notmatch 'sources|javadoc' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jarItem) { throw 'Build first: .\mvnw.cmd -DskipTests package' }
$jar = $jarItem.FullName
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$script:procs = @{}
$script:token = $null
$tenantId = $null
$tenantCleaned = $false

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

# ---------------------------------------------------------------------------
# Tenant cleanup: D1-D3 remove the tenant they created
# ---------------------------------------------------------------------------
# Every replay used to leave one `drill-*` tenant behind, frozen in the shapes
# the drills created (PENDING and DELIVERING rows whose broker messages were
# never settled). This is the same verified cleanup backup-restore.ps1 uses:
# delete the tenant this run created, and sweep any tenant a run killed too hard
# to clean up after itself left at pre-flight.

function Invoke-MySql {
    param([string]$Sql, [switch]$Quiet)
    # Under $ErrorActionPreference = 'Stop' one stderr byte from a native command
    # is a terminating error. 'Continue' plus 2>&1 keeps $LASTEXITCODE truthful
    # and lets the caller decide what a failed statement means.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) {
            $out = & docker exec -e MYSQL_PWD=hookflow hookflow-mysql mysql -uhookflow -N -B -D hookflow -e $Sql 2>$null
        } else {
            $out = & docker exec -e MYSQL_PWD=hookflow hookflow-mysql mysql -uhookflow -N -B -D hookflow -e $Sql 2>&1
        }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return [pscustomobject]@{
        ExitCode = $code
        Stdout   = @($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
        Stderr   = @($out | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    }
}

function Get-DrillTenantIds {
    # GROUP_CONCAT keeps the answer on one line, so "no tenant matches" (the
    # normal case) is distinguishable from "the query failed": the caller wraps
    # this in @(...), and each id is emitted as its own pipeline object.
    #
    # `drill-backup-*` is backup-restore.ps1's prefix, and that script sweeps its
    # own leftovers. Excluding it here means a tenant kept with `-KeepTenant`
    # there is not deleted just because this drill happens to run next.
    $res = Invoke-MySql -Sql "SELECT IFNULL(GROUP_CONCAT(id ORDER BY id), '') FROM hookflow.tenant WHERE name LIKE 'drill-%' AND name NOT LIKE 'drill-backup-%'"
    if ($res.ExitCode -ne 0) { throw 'could not list the tenants in the source database' }
    $line = @($res.Stdout)[0]
    if ($null -eq $line -or "$line".Trim() -eq '') { return }
    foreach ($part in "$line".Trim().Split(',')) { [long]$part.Trim() }
}

function Remove-DrillTenant {
    param([long]$TenantId)

    # Foreign-key order. delivery and delivery_attempt have no tenant_id of their
    # own (the join scopes them); outbox and audit_log carry the column with no
    # foreign key at all, so a failure midway leaves a partially deleted tenant
    # that the next run's pre-flight sweep finishes. ROW_COUNT() after each
    # statement reports the row count of the statement right before it, which is
    # the per-table evidence line the caller prints.
    $sql = "DELETE da FROM hookflow.delivery_attempt da JOIN hookflow.delivery d ON d.id = da.delivery_id WHERE d.tenant_id = $TenantId;" +
           " SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.outbox WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.delivery WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.event WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.endpoint WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.api_key WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.audit_log WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM hookflow.tenant WHERE id = $TenantId; SELECT ROW_COUNT();"
    $res = Invoke-MySql -Sql $sql
    if ($res.ExitCode -ne 0) { return $null }
    $rows = @($res.Stdout | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
    if ($rows.Count -lt 8) { return $null }
    $n = @($rows | ForEach-Object { [int]$_ })
    return [pscustomobject]@{
        TenantId   = $TenantId
        Attempts   = $n[0]
        Outbox     = $n[1]
        Deliveries = $n[2]
        Events     = $n[3]
        Endpoints  = $n[4]
        ApiKeys    = $n[5]
        AuditLog   = $n[6]
        TenantRows = $n[7]
    }
}

function Format-DrillTenantCounts {
    param($Counts)
    $line = 'attempts={0} outbox={1} delivery={2} event={3} endpoint={4} api_key={5} audit_log={6} tenant={7}'
    return ($line -f $Counts.Attempts, $Counts.Outbox, $Counts.Deliveries, $Counts.Events, $Counts.Endpoints, $Counts.ApiKeys, $Counts.AuditLog, $Counts.TenantRows)
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

# ---------------------------------------------------------------------------
# Pre-flight: clean up what a killed earlier run could not
# ---------------------------------------------------------------------------
# A normal replay deletes its tenant before it exits. This sweep is the safety
# net for a run that was killed too hard to reach its cleanup, and it is also
# what finally clears the tenants the earlier versions of this script left.
Write-Host ''
Write-Host '=== pre-flight ===========================================' -ForegroundColor Cyan

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is not on PATH' }
$probe = Invoke-MySql -Sql 'SELECT 1' -Quiet
if ($probe.ExitCode -ne 0) {
    throw 'hookflow-mysql is not reachable; run: docker compose up -d (DB_PUBLISH_PORT=13306 on this machine)'
}

$staleTenants = @(Get-DrillTenantIds)
if ($KeepTenant) {
    Stamp "tenant cleanup disabled by -KeepTenant: $($staleTenants.Count) leftover drill tenant(s) will stay in the database"
} elseif ($staleTenants.Count -eq 0) {
    Stamp 'no leftover drill tenants in the source database'
} else {
    $swept = 0
    foreach ($staleId in $staleTenants) {
        $gone = Remove-DrillTenant -TenantId $staleId
        if ($null -eq $gone) {
            Write-Host "  WARNING: could not delete leftover drill tenant $staleId; the next run will retry" -ForegroundColor Yellow
            continue
        }
        if ($gone.TenantRows -gt 0) { $swept++ }
        Stamp ("swept leftover drill tenant {0}: {1}" -f $gone.TenantId, (Format-DrillTenantCounts $gone))
    }
    Stamp "$swept leftover drill tenant(s) removed; the source database holds only its own tenants again"
}

# A swept tenant's broker messages become orphans. The worker drops a message
# whose delivery row is gone, and unlike backup-restore.ps1 there is no
# freeze-point queue inventory to keep clean, so this sweep leaves the shared
# queue alone instead of purging other tenants' ready messages with it.

# A leftover MockReceiver from a previous drill would fight for port 9099.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains('MockReceiver') } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

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
        $tenantId = $tenant.tenantId
        Stamp "tenant $tenantId provisioned"

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

    # Nothing above needs the tenant any more: every observation is printed, and
    # the rows it froze are what used to pile up in the demo database. Deleting
    # it keeps a replay from handing the next setup stale in-flight rows to heal.
    Write-Host ''
    Write-Host '=== cleanup ==============================================' -ForegroundColor Cyan
    if ($null -eq $tenantId) {
        Stamp 'no tenant was provisioned by this run (-SkipSetup); nothing to delete'
        $tenantCleaned = $true
    } elseif ($KeepTenant) {
        Stamp "tenant $tenantId kept (-KeepTenant): it stays in the source database for inspection"
        $tenantCleaned = $true
    } else {
        $cleanup = Remove-DrillTenant -TenantId $tenantId
        if ($null -eq $cleanup) { throw "could not delete drill tenant $tenantId from the source database" }
        if ($cleanup.TenantRows -le 0) { throw "drill tenant $tenantId is still present after the delete" }
        $tenantCleaned = $true
        Stamp ("cleaned up drill tenant {0}: {1}" -f $cleanup.TenantId, (Format-DrillTenantCounts $cleanup))
        Stamp "drill tenants left in the source database: $(@(Get-DrillTenantIds).Count)"
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
    # A controlled abort (a failed health wait, a drill assertion) would otherwise
    # leave the tenant frozen behind. The next run's pre-flight sweep is the real
    # safety net - it also covers kill -9 - but cleaning up here keeps the
    # database tidy in the common case.
    if (($null -ne $tenantId) -and (-not $tenantCleaned)) {
        if ($KeepTenant) {
            Write-Host "aborted-run cleanup skipped by -KeepTenant: drill tenant $tenantId stays in the source database"
        } else {
            $abortCleanup = Remove-DrillTenant -TenantId $tenantId
            if ($null -ne $abortCleanup) {
                Write-Host ("aborted-run cleanup: drill tenant {0} removed from the source database ({1})" -f $abortCleanup.TenantId, (Format-DrillTenantCounts $abortCleanup))
            } else {
                Write-Host "aborted-run cleanup failed for tenant $tenantId - the next run's pre-flight sweep will remove it" -ForegroundColor Yellow
            }
        }
    }
    Write-Host 'all drill processes stopped'
}





