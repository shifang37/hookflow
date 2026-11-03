#!/usr/bin/env pwsh
# Backup -> empty MySQL instance -> restore -> verify -> watch the platform heal.
#
# The v0.2 drills (scripts/drill.ps1) cover process and dependency faults. This
# one covers the only scenario that actually loses data: the MySQL primary.
#
#   1. seeds a tenant, delivers history, then freezes with work in flight in
#      three shapes: DELIVERING (worker killed mid-request), PENDING whose
#      broker message is about to be lost (published, then purged), and PENDING
#      whose message was never published (relay stopped before the submit)
#   2. mysqldump --single-transaction into logs/backup-restore/
#   3. starts an EMPTY mysql:8.4 container and restores the dump into it
#   4. asserts every table's row count, the delivery/outbox/attempt status
#      distributions and the flyway history are identical to the source
#   5. purges the broker queue (simulate losing the broker too), points the
#      three roles at the restored instance and watches DeliveryRecovery heal
#      all three shapes with NO broker message carrying the state - proving the
#      claim in docs/operations.md section 2 that MySQL state, not the broker,
#      is what saves a delivery
#   6. deletes the `drill-backup-*` tenant it created, and sweeps any tenant a
#      killed earlier run left behind, so replaying the drill does not pile up
#      tenants (and their frozen in-flight rows) in the demo database. Pass
#      -KeepTenant to leave them in place for inspection instead.
#
# Every observation is printed with a timestamp so the output can be pasted
# straight into docs/failures/ as the drill record. The RTO funnel printed at
# the end feeds docs/failures/0008-backup-restore-drill.md.
#
# Prerequisite: docker compose up -d (DB_PUBLISH_PORT=13306 on this machine),
# and no HookFlow JVM already listening on 8080/8081/8082.
#
# English-only on purpose (no non-ASCII) so it runs on any Windows PowerShell
# without encoding surprises.

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReceiverUrl = 'http://127.0.0.1:9099/hook',
    [int]$DbPort = 13306,
    [int]$RestorePort = 13307,
    [switch]$KeepRestore,
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

$sourceContainer = 'hookflow-mysql'
$restoreContainer = 'hookflow-mysql-restore'

$logDir = Join-Path $root 'logs'
$dumpDir = Join-Path $logDir 'backup-restore'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
$dumpFile = Join-Path $dumpDir ('hookflow-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')

$jarItem = Get-ChildItem (Join-Path $root 'target') -Filter 'hookflow-*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jarItem) { throw 'Build first: .\mvnw.cmd -DskipTests package' }
$jar = $jarItem.FullName
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$script:procs = @{}
$script:token = $null

function Stamp($msg) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) }

function Test-PortBusy {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Start-Role {
    param(
        [string]$Name,
        [string]$Profile,
        [int]$Port,
        [string[]]$ExtraArgs = @(),
        [string]$LogName = ''
    )
    if (-not $LogName) { $LogName = $Name }
    $out = Join-Path $logDir "$LogName.log"
    if (Test-Path $out) { Remove-Item $out -Force }
    # A tiny .bat avoids Start-Process entirely: on this class of machine the
    # process environment carries both HTTP_PROXY and http_proxy, and Start-Process
    # refuses to build a child environment from that ("item already added").
    $bat = Join-Path $logDir "$LogName.bat"
    $cmd = '"' + $java + '" -Duser.timezone=UTC -jar "' + $jar + '" ' +
           "--spring.profiles.active=$Profile " + ($ExtraArgs -join ' ') +
           ' > "' + $out + '" 2>&1'
    $envLines = @(
        "set DB_PORT=$Port",
        'set HOOKFLOW_MASTER_KEY=hookflow-dev-master-key-change-in-production-32',
        'set HOOKFLOW_BOOTSTRAP_TOKEN=hookflow-dev-bootstrap-token'
    )
    Set-Content -Path $bat -Value (@('@echo off') + $envLines + @($cmd)) -Encoding ASCII
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
        -Arguments @{ CommandLine = 'cmd.exe /c "' + $bat + '"' } | Out-Null
    Start-Sleep -Milliseconds 800
    Stamp "started $Name (profile=$Profile, DB_PORT=$Port, log=$LogName.log)"
}

function Get-RolePid {
    param([string]$Profile)
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

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSec, [int]$PollMs = 1000)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { return $true }
        Start-Sleep -Milliseconds $PollMs
    }
    return $false
}

# Every docker call goes through this. Two PowerShell 5.1 traps sank the first
# replay of this drill, and both of them failed silently:
#
#   1. Under $ErrorActionPreference = 'Stop', one stderr byte from a native
#      command is a *terminating* error. `docker rm -f` on a container that does
#      not exist wrote "No such container" to stderr, so the finally block
#      replaced the real failure with its own message and the drill record lost
#      the reason.
#   2. A command's stderr has to be left alone for $LASTEXITCODE to stay
#      truthful; redirecting stderr with 'Stop' in force is what turns trap 1
#      into an exception in the first place.
#
# 'Continue' plus merging stderr into the success stream keeps the exit code
# exact, throws nothing, and hands the caller both streams to print or to keep
# quiet. stdout and stderr stay separated by type (ErrorRecord vs string).
function Invoke-Docker {
    param([string[]]$Arguments, [switch]$Quiet)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) { $all = & docker @Arguments 2>$null; $code = $LASTEXITCODE }
        else { $all = & docker @Arguments 2>&1; $code = $LASTEXITCODE }
    } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{
        ExitCode = $code
        Stdout   = @($all | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
        Stderr   = @($all | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } |
                     ForEach-Object { $_.ToString() })
    }
}

function Invoke-Sql {
    param([string]$Container, [string]$Sql, [string]$User = 'root', [string]$Password = 'root',
          [string]$Database = '', [switch]$Quiet)
    $cmdArgs = @('exec', '-e', "MYSQL_PWD=$Password", $Container, 'mysql', "-u$User", '-N', '-B')
    if ($Database) { $cmdArgs += $Database }
    $cmdArgs += @('-e', $Sql)
    $res = Invoke-Docker -Arguments $cmdArgs -Quiet:$Quiet
    if ($res.ExitCode -ne 0) {
        if (-not $Quiet) {
            Stamp "  sql failed (exit $($res.ExitCode)): $Sql"
            foreach ($line in $res.Stderr) { Stamp "    $line" }
        }
        return $null
    }
    if ($res.Stdout.Count -eq 0) { return $null }
    # The leading comma matters: `return @($oneLine)` is unwrapped to a plain
    # string, and then $row[0] reads the *character* '2' of "20" instead of the
    # row. [int] made that 50, so a warm-up assertion of -eq 20 could never hold.
    return ,$res.Stdout
}

function Get-Scalar {
    param([string]$Container, [string]$Sql, [switch]$Quiet)
    $rows = Invoke-Sql -Container $Container -Sql $Sql -Quiet:$Quiet
    if ($null -eq $rows) { return $null }
    return @($rows)[0]
}

function Get-Count {
    param([string]$Container, [string]$Sql, [switch]$Quiet)
    $rows = Invoke-Sql -Container $Container -Sql $Sql -Quiet:$Quiet
    if ($null -eq $rows) { return $null }
    $first = @($rows)[0]
    if ($null -eq $first -or "$first".Trim() -eq '') { return $null }
    return [int]("$first".Trim())
}

function Get-Dist {
    param([string]$Container, [string]$Sql)
    $r = Invoke-Sql -Container $Container -Sql $Sql
    if ($null -eq $r) { return $null }
    return (@($r) | Sort-Object) -join ' | '
}

# ready and unacked are read separately on purpose: purge_queue only removes
# ready messages, so a message still held by a killed worker's channel survives
# a purge and would let a later heal look broker-driven when it is not.
function Get-QueueStat {
    param([string]$Queue)
    $res = Invoke-Docker -Arguments @('exec', 'hookflow-rabbitmq', 'rabbitmqctl', '--quiet',
                                      'list_queues', 'name', 'messages_ready', 'messages_unacknowledged')
    if ($res.ExitCode -ne 0) { return $null }
    foreach ($line in $res.Stdout) {
        $parts = $line -split "`t"
        if ($parts.Count -ge 3 -and $parts[0].Trim() -eq $Queue) {
            return [pscustomobject]@{ Ready = [int]$parts[1].Trim(); Unacked = [int]$parts[2].Trim() }
        }
    }
    return $null
}

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body, [hashtable]$Headers = @{})
    $req = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers; ContentType = 'application/json' }
    if ($null -ne $Body) { $req.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    Invoke-RestMethod @req
}

function Submit-Event {
    param([string]$Type)
    $headers = @{ Authorization = "Bearer $script:token"; 'Idempotency-Key' = 'drill-backup-' + [guid]::NewGuid().ToString('N') }
    return (Invoke-Api -Method POST -Path '/v1/events' -Headers $headers `
        -Body @{ eventType = $Type; payload = @{ amount = 42; drill = 'backup-restore' } }).eventId
}

# ---------------------------------------------------------------------------
# Tenant cleanup: the drill removes the tenant it created
# ---------------------------------------------------------------------------
# Every replay used to leave one `drill-backup-*` tenant behind, frozen in the
# shapes the drill created (PENDING and DELIVERING rows whose broker messages
# were never settled). The next replay's setup healed those stale rows as a side
# effect - that is where the stray "Lease expired for delivery 3872" line in the
# 0008 record came from. These helpers delete such a tenant and everything that
# belongs to it.

function Get-DrillTenantIds {
    # GROUP_CONCAT keeps the answer to one line, so "no tenant matches" (the
    # normal case) is distinguishable from "the query failed": Invoke-Sql returns
    # $null only for a failure, and an empty result set would look the same.
    #
    # Each id is emitted as its own pipeline object and the caller wraps the call
    # in @(...). Do NOT `return ,$array` here: the caller's @() would then hold a
    # single element - the array itself - and foreach would hand Remove-DrillTenant
    # an Object[] where it wants a [long]. (Hit on the first replay of this change.)
    $line = Get-Scalar -Container $sourceContainer -Sql "SELECT IFNULL(GROUP_CONCAT(id ORDER BY id), '') FROM hookflow.tenant WHERE name LIKE 'drill-backup-%'"
    if ($null -eq $line) { throw 'could not list the tenants in the source database' }
    if ("$line".Trim() -eq '') { return }
    foreach ($part in "$line".Trim().Split(',')) { [long]$part.Trim() }
}

function Remove-DrillTenant {
    param([long]$TenantId)

    # Foreign-key order. delivery and delivery_attempt have no tenant_id of their
    # own (the join scopes them); outbox and audit_log carry the column with no
    # foreign key at all, so a failure midway leaves a partially deleted tenant
    # that the next run's pre-flight sweep finishes. One round trip per tenant:
    # ROW_COUNT() after each statement reports the row count of the statement
    # right before it, which is the per-table evidence line the caller prints.
    $sql = "DELETE da FROM delivery_attempt da JOIN delivery d ON d.id = da.delivery_id WHERE d.tenant_id = $TenantId;" +
           " SELECT ROW_COUNT();" +
           " DELETE FROM outbox WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM delivery WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM event WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM endpoint WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM api_key WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM audit_log WHERE tenant_id = $TenantId; SELECT ROW_COUNT();" +
           " DELETE FROM tenant WHERE id = $TenantId; SELECT ROW_COUNT();"
    $rows = Invoke-Sql -Container $sourceContainer -Sql $sql -Database 'hookflow'
    if ($null -eq $rows -or @($rows).Count -lt 8) { return $null }
    $n = @($rows | ForEach-Object { $t = "$_".Trim(); if ($t -eq '') { 0 } else { [int]$t } })
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

function Get-Delivery {
    param([long]$EventId)
    $page = Invoke-Api -Method GET -Path "/v1/deliveries?eventId=$EventId" -Headers @{ Authorization = "Bearer $script:token" }
    if ($page.items.Count -eq 0) { return $null }
    return (Invoke-Api -Method GET -Path "/v1/deliveries/$($page.items[0].id)" -Headers @{ Authorization = "Bearer $script:token" })
}

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '=== pre-flight ===========================================' -ForegroundColor Cyan

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is not on PATH' }
$dockVer = Invoke-Docker -Arguments @('version', '--format', '{{.Server.Version}}')
if ($dockVer.ExitCode -ne 0) { throw 'Docker daemon is not reachable; is Docker Desktop running?' }

$health = Invoke-Docker -Arguments @('inspect', '-f', '{{.State.Health.Status}}', $sourceContainer) -Quiet
$healthState = @($health.Stdout)[0]
if ($health.ExitCode -ne 0 -or $healthState -ne 'healthy') {
    throw "$sourceContainer is not healthy (got '$healthState'); run: docker compose up -d"
}

$image = Invoke-Docker -Arguments @('image', 'inspect', 'mysql:8.4') -Quiet
if ($image.ExitCode -ne 0) { throw 'mysql:8.4 image is missing locally and pulls are unreliable on this machine' }

foreach ($port in @(8080, 8081, 8082)) {
    if (Test-PortBusy -Port $port) {
        throw "port $port is already in use - a HookFlow JVM is probably still running; stop it and retry"
    }
}
Stamp 'docker up, mysql:8.4 present, ports 8080/8081/8082 free'

# ---------------------------------------------------------------------------
# Leftover sweep: a killed run leaves its tenant frozen in the source database
# ---------------------------------------------------------------------------
# The drill deletes its own tenant before it exits, so the normal replay path
# leaves nothing behind. This sweep is the safety net for a run that was killed
# too hard to reach its cleanup, and it is also what finally clears the tenants
# the earlier versions of this script accumulated.
$staleTenants = @(Get-DrillTenantIds)
if ($KeepTenant) {
    Stamp "tenant cleanup disabled by -KeepTenant: $($staleTenants.Count) drill tenant(s) will stay in the database"
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
    if ($swept -gt 0) {
        # The swept tenants' broker messages are orphans now. The worker drops a
        # message whose delivery row is gone, so this is hygiene, not correctness
        # - but purging keeps the freeze-point queue inventory in the record down
        # to this run's own work.
        #
        # The "after" number has to be polled, not read once: rabbitmqctl reads
        # the broker's stats database, which lags the queue by the stats emission
        # interval (5s by default), so an immediate read still shows messages the
        # purge already removed - a purged queue of 4 read back as 4 on
        # 2026-11-03 until the stats caught up. A message still unacked on a
        # killed worker's channel does survive a purge, so this can legitimately
        # time out; the drill purges the queue again before the heal step, so
        # warn rather than fail.
        $queueBefore = Get-QueueStat -Queue 'hookflow.delivery'
        [void](Invoke-Docker -Arguments @('exec', 'hookflow-rabbitmq', 'rabbitmqctl', '--quiet', 'purge_queue', 'hookflow.delivery'))
        $emptied = Wait-Until -TimeoutSec 15 -PollMs 1000 -Condition {
            $s = Get-QueueStat -Queue 'hookflow.delivery'
            ($null -ne $s) -and ($s.Ready -eq 0) -and ($s.Unacked -eq 0)
        }
        $queueAfter = Get-QueueStat -Queue 'hookflow.delivery'
        if (($null -ne $queueBefore) -and ($null -ne $queueAfter)) {
            Stamp "purged orphan broker messages: ready $($queueBefore.Ready) -> $($queueAfter.Ready)"
        }
        if (-not $emptied) {
            Write-Host '  WARNING: the broker queue did not read back as empty; the drill purges it again before the heal step' -ForegroundColor Yellow
        }
    }
    Stamp "$swept leftover drill tenant(s) removed; the source database holds only its own tenants again"
}

# A leftover MockReceiver from a previous drill would fight for port 9099.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains('MockReceiver') } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# ---------------------------------------------------------------------------
# Setup + seed, then freeze with work in flight in three shapes
# ---------------------------------------------------------------------------
$backlogEvents = @()
$lostEvents = @()
$slowEvent = $null
$tenantId = $null
$tenantCleaned = $false
$converged = $false

try {
    Write-Host ''
    Write-Host '=== setup: start roles and seed history ==================' -ForegroundColor Cyan

    # Short lease so the DELIVERING shape is reclaimed seconds after the
    # restored instance is up instead of a minute later. A single worker keeps
    # "the victim really was holding the delivery" deterministic.
    $leaseArgs = @('--hookflow.lease.ttl-seconds=8', '--hookflow.lease.sweep-interval-ms=2000')

    # The demo receiver is on loopback and the production profiles refuse that
    # on purpose (SSRF defence). The drill relaxes it explicitly, which also
    # demonstrates that the default really is strict.
    Start-Role -Name 'api' -Profile 'api' -Port $DbPort -ExtraArgs @('--hookflow.security.egress.allow-private-targets=true')
    Start-Role -Name 'relay' -Profile 'relay' -Port $DbPort
    Start-Role -Name 'worker' -Profile 'worker' -Port $DbPort -ExtraArgs $leaseArgs

    $mockBat = Join-Path $logDir 'receiver.bat'
    $mockCmd = '"' + $java + '" -cp "' + (Join-Path $root 'target\classes') +
               '" dev.hookflow.tools.MockReceiver 9099 > "' + (Join-Path $logDir 'receiver.log') + '" 2>&1'
    Set-Content -Path $mockBat -Value (@('@echo off') + @($mockCmd)) -Encoding ASCII
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
        -Arguments @{ CommandLine = 'cmd.exe /c "' + $mockBat + '"' } | Out-Null
    Stamp 'started receiver on 9099'

    if (-not (Wait-Health)) { throw 'api never became healthy - see logs/api.log' }
    Stamp 'api is UP'

    $tenant = Invoke-Api -Method POST -Path '/v1/admin/tenants' `
        -Headers @{ 'X-Bootstrap-Token' = 'hookflow-dev-bootstrap-token' } `
        -Body @{ name = 'drill-backup-' + (Get-Date -Format 'HHmmss'); rateLimit = 1000 }
    $script:token = $tenant.apiKey
    $tenantId = $tenant.tenantId
    Stamp "tenant $tenantId provisioned"

    $auth = @{ Authorization = "Bearer $($script:token)" }
    $fast = Invoke-Api -Method POST -Path '/v1/endpoints' -Headers $auth `
        -Body @{ url = $ReceiverUrl; subscribedTypes = 'drill.fast'; maxAttempts = 6 }
    Stamp "fast endpoint $($fast.id) -> $ReceiverUrl"

    # 3s response keeps the attempt in flight long enough to be killed, while
    # staying under the 10s read timeout so the retry can succeed.
    $slowUrl = $ReceiverUrl + '?delay=3000'
    $slow = Invoke-Api -Method POST -Path '/v1/endpoints' -Headers $auth `
        -Body @{ url = $slowUrl; subscribedTypes = 'drill.slow'; maxAttempts = 6 }
    Stamp "slow endpoint $($slow.id) -> $slowUrl"

    foreach ($i in 1..20) { [void](Submit-Event -Type 'drill.fast') }
    $warmOk = Wait-Until -TimeoutSec 90 -Condition {
        (Get-Count -Container $sourceContainer -Sql "SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id=$tenantId AND status='SUCCEEDED'") -eq 20
    }
    if (-not $warmOk) { throw 'warm-up deliveries never reached 20 SUCCEEDED' }
    Stamp 'warm-up: 20/20 SUCCEEDED - normal path proven, history for the backup'

    Write-Host ''
    Write-Host '=== freeze with work in flight (three shapes) ============' -ForegroundColor Cyan

    # Shape 1: DELIVERING. Kill the worker while the 3s request is in flight.
    $slowEvent = Submit-Event -Type 'drill.slow'
    $claimed = Wait-Until -TimeoutSec 10 -PollMs 200 -Condition {
        (Get-Scalar -Container $sourceContainer -Sql "SELECT status FROM hookflow.delivery WHERE event_id=$slowEvent" -Quiet) -eq 'DELIVERING'
    }
    if (-not $claimed) { throw 'slow delivery never reached DELIVERING' }
    Stamp "event $slowEvent is DELIVERING (worker is inside the 3s HTTP call)"
    Stop-Role -Name 'worker' -Profile 'worker' -Hard

    # Shape 2: PENDING + message PUBLISHED. The message will be purged from the
    # broker after the restore, i.e. "lost after acknowledgement".
    foreach ($i in 1..3) { $lostEvents += (Submit-Event -Type 'drill.fast') }
    $published = Wait-Until -TimeoutSec 20 -Condition {
        $n = Get-Count -Container $sourceContainer -Sql "SELECT COUNT(*) FROM hookflow.outbox WHERE tenant_id=$tenantId AND status='PUBLISHED'"
        $n -ge 24
    }
    if (-not $published) { throw 'the three lost-message events never reached PUBLISHED' }
    Stamp "events $($lostEvents -join ', ') are PENDING with their broker message PUBLISHED (worker is down, nothing consumes)"

    # Shape 3: PENDING + message NEW. Stop the relay before submitting, so no
    # broker message is ever created for these.
    Stop-Role -Name 'relay' -Profile 'relay'
    foreach ($i in 1..5) { $backlogEvents += (Submit-Event -Type 'drill.fast') }
    Stamp "events $($backlogEvents -join ', ') are PENDING with outbox rows NEW (relay is down)"

    Stop-Role -Name 'api' -Profile 'api'
    Stamp 'all roles are down; the database is frozen at the disaster point'

    Write-Host ''
    Write-Host '--- frozen inventory (drill tenant) ---'
    # a.status, not status: the join makes the bare column ambiguous and MySQL
    # answers 1052 instead of a number.
    foreach ($q in @(
        "SELECT CONCAT('  delivery ', status, ': ', COUNT(*)) FROM delivery WHERE tenant_id=$tenantId GROUP BY status",
        "SELECT CONCAT('  outbox ', status, ': ', COUNT(*)) FROM outbox WHERE tenant_id=$tenantId GROUP BY status",
        "SELECT CONCAT('  attempt ', a.status, ': ', COUNT(*)) FROM delivery_attempt a JOIN delivery d ON d.id=a.delivery_id WHERE d.tenant_id=$tenantId GROUP BY a.status")) {
        foreach ($line in @(Invoke-Sql -Container $sourceContainer -Sql $q -User 'hookflow' -Password 'hookflow' -Database 'hookflow')) {
            Write-Host $line
        }
    }

    # -----------------------------------------------------------------------
    # Backup
    # -----------------------------------------------------------------------
    Write-Host ''
    Write-Host '=== backup ==============================================' -ForegroundColor Cyan

    $dumpWatch = [Diagnostics.Stopwatch]::StartNew()
    Stamp "dump: docker exec $sourceContainer sh -c 'mysqldump --single-transaction --no-tablespaces -uroot hookflow > /tmp/hookflow-backup.sql'"
    $dump = Invoke-Docker -Arguments @('exec', '-e', 'MYSQL_PWD=root', $sourceContainer, 'sh', '-c',
                                       'mysqldump --single-transaction --no-tablespaces -uroot hookflow > /tmp/hookflow-backup.sql')
    if ($dump.ExitCode -ne 0) { throw ("mysqldump failed (exit $($dump.ExitCode)): " + ($dump.Stderr -join ' ')) }
    $dumpCopy = Invoke-Docker -Arguments @('cp', "${sourceContainer}:/tmp/hookflow-backup.sql", $dumpFile)
    if ($dumpCopy.ExitCode -ne 0) { throw ("docker cp of the dump failed: " + ($dumpCopy.Stderr -join ' ')) }
    [void](Invoke-Docker -Arguments @('exec', $sourceContainer, 'rm', '-f', '/tmp/hookflow-backup.sql') -Quiet)
    $dumpWatch.Stop()
    $dumpSizeMb = (Get-Item $dumpFile).Length / 1MB
    Stamp ('backup written: {0} ({1:N1} MB) in {2:N1}s' -f $dumpFile, $dumpSizeMb, $dumpWatch.Elapsed.TotalSeconds)

    # -----------------------------------------------------------------------
    # Restore into an empty mysql:8.4 instance
    # -----------------------------------------------------------------------
    Write-Host ''
    Write-Host '=== restore into an empty instance ======================' -ForegroundColor Cyan

    [void](Invoke-Docker -Arguments @('rm', '-f', $restoreContainer) -Quiet)

    $emptyWatch = [Diagnostics.Stopwatch]::StartNew()
    Stamp "start: docker run -d --name $restoreContainer ... -p $($RestorePort):3306 mysql:8.4 (empty; no data volume)"
    $run = Invoke-Docker -Arguments @('run', '-d', '--name', $restoreContainer,
        '-e', 'MYSQL_ROOT_PASSWORD=root', '-e', 'MYSQL_DATABASE=hookflow',
        '-e', 'MYSQL_USER=hookflow', '-e', 'MYSQL_PASSWORD=hookflow', '-e', 'TZ=UTC',
        '-p', "${RestorePort}:3306", 'mysql:8.4',
        '--innodb-flush-log-at-trx-commit=1', '--max-connections=300')
    if ($run.ExitCode -ne 0) { throw ("docker run of the restore instance failed: " + ($run.Stderr -join ' ')) }

    $ready = Wait-Until -TimeoutSec 180 -PollMs 2000 -Condition {
        (Get-Scalar -Container $restoreContainer -Sql 'SELECT 1' -Quiet) -eq '1'
    }
    if (-not $ready) { throw 'the restore instance never became reachable' }
    $emptyWatch.Stop()
    Stamp ('empty instance is up ({0:N1}s); it contains zero business rows' -f $emptyWatch.Elapsed.TotalSeconds)
    $emptyTables = Get-Count -Container $restoreContainer -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hookflow'"
    Stamp "tables in the empty instance before restore: $emptyTables"

    $restoreWatch = [Diagnostics.Stopwatch]::StartNew()
    $copyIn = Invoke-Docker -Arguments @('cp', $dumpFile, "${restoreContainer}:/tmp/restore.sql")
    if ($copyIn.ExitCode -ne 0) { throw ("docker cp of the dump into the restore instance failed: " + ($copyIn.Stderr -join ' ')) }
    Stamp "apply: docker exec $restoreContainer sh -c 'mysql -uroot hookflow < /tmp/restore.sql'"
    $apply = Invoke-Docker -Arguments @('exec', '-e', 'MYSQL_PWD=root', $restoreContainer, 'sh', '-c',
                                        'mysql -uroot hookflow < /tmp/restore.sql')
    if ($apply.ExitCode -ne 0) { throw ("restoring the dump failed (exit $($apply.ExitCode)): " + ($apply.Stderr -join ' ')) }
    foreach ($line in $apply.Stderr) { Stamp "  restore stderr: $line" }
    [void](Invoke-Docker -Arguments @('exec', $restoreContainer, 'rm', '-f', '/tmp/restore.sql') -Quiet)
    $restoreWatch.Stop()
    Stamp ('restore applied in {0:N1}s' -f $restoreWatch.Elapsed.TotalSeconds)

    # -----------------------------------------------------------------------
    # Verification: source vs restored, row counts and status distributions
    # -----------------------------------------------------------------------
    Write-Host ''
    Write-Host '=== verification (source vs restored) ===================' -ForegroundColor Cyan

    $verifyWatch = [Diagnostics.Stopwatch]::StartNew()
    $checks = 0
    $failed = 0

    foreach ($t in @('tenant', 'api_key', 'endpoint', 'event', 'delivery', 'delivery_attempt', 'outbox', 'audit_log', 'flyway_schema_history')) {
        $src = Get-Count -Container $sourceContainer -Sql "SELECT COUNT(*) FROM hookflow.$t"
        $dst = Get-Count -Container $restoreContainer -Sql "SELECT COUNT(*) FROM hookflow.$t"
        $checks++
        $ok = ($src -eq $dst)
        if (-not $ok) { $failed++ }
        Stamp ("check {0,-22} source={1,-7} restored={2,-7} {3}" -f $t, $src, $dst, $(if ($ok) { 'PASS' } else { 'FAIL' }))
    }

    foreach ($p in @(
        @('delivery.status', 'SELECT status, COUNT(*) FROM hookflow.delivery GROUP BY status'),
        @('outbox.status', 'SELECT status, COUNT(*) FROM hookflow.outbox GROUP BY status'),
        @('attempt.status', 'SELECT status, COUNT(*) FROM hookflow.delivery_attempt GROUP BY status'))) {
        $src = Get-Dist -Container $sourceContainer -Sql $p[1]
        $dst = Get-Dist -Container $restoreContainer -Sql $p[1]
        $checks++
        $ok = ($src -eq $dst)
        if (-not $ok) { $failed++ }
        Stamp ("check {0,-22} {1}" -f $p[0], $(if ($ok) { 'PASS' } else { "FAIL source=[$src] restored=[$dst]" }))
    }

    $flywayBad = Get-Count -Container $restoreContainer -Sql 'SELECT COUNT(*) FROM hookflow.flyway_schema_history WHERE success=0'
    $checks++
    if ($flywayBad -ne 0) { $failed++ }
    Stamp ("check {0,-22} failed migrations={1} {2}" -f 'flyway.integrity', $flywayBad, $(if ($flywayBad -eq 0) { 'PASS' } else { 'FAIL' }))

    $srcTz = Get-Scalar -Container $sourceContainer -Sql 'SELECT @@system_time_zone'
    $dstTz = Get-Scalar -Container $restoreContainer -Sql 'SELECT @@system_time_zone'
    $checks++
    if ($dstTz -ne 'UTC') { $failed++ }
    Stamp ("check {0,-22} source={1} restored={2} {3}" -f 'instance.time_zone', $srcTz, $dstTz, $(if ($dstTz -eq 'UTC') { 'PASS' } else { 'FAIL' }))

    $verifyWatch.Stop()
    Stamp ("verification: {0}/{1} checks passed in {2:N1}s" -f ($checks - $failed), $checks, $verifyWatch.Elapsed.TotalSeconds)
    if ($failed -gt 0) { throw "$failed verification check(s) failed - the backup is NOT trustworthy" }

    # -----------------------------------------------------------------------
    # Self-healing on the restored instance, with the broker queue purged
    # -----------------------------------------------------------------------
    Write-Host ''
    Write-Host '=== the broker loses everything too =====================' -ForegroundColor Cyan
    $queue = 'hookflow.delivery'
    $stat = Get-QueueStat -Queue $queue
    Stamp "broker queue ${queue} at the freeze point: ready=$($stat.Ready) unacked=$($stat.Unacked)"
    # The killed worker's channel can still be holding the in-flight message.
    # RabbitMQ requeues it once it notices the dead connection, so wait for the
    # unacked set to empty before purging; otherwise the purge silently leaves
    # messages behind and the heal below would not prove anything.
    $settled = Wait-Until -TimeoutSec 60 -PollMs 2000 -Condition {
        $s = Get-QueueStat -Queue $queue
        ($null -ne $s) -and ($s.Unacked -eq 0)
    }
    if (-not $settled) { throw "the broker still holds unacked messages on $queue after 60s; nothing to purge yet" }
    $purge = Invoke-Docker -Arguments @('exec', 'hookflow-rabbitmq', 'rabbitmqctl', '--quiet', 'purge_queue', $queue)
    if ($purge.ExitCode -ne 0) { throw ("purging the broker queue failed: " + ($purge.Stderr -join ' ')) }
    $emptied = Wait-Until -TimeoutSec 30 -PollMs 1000 -Condition {
        $s = Get-QueueStat -Queue $queue
        ($null -ne $s) -and ($s.Ready -eq 0) -and ($s.Unacked -eq 0)
    }
    $after = Get-QueueStat -Queue $queue
    Stamp "queue after the purge: ready=$($after.Ready) unacked=$($after.Unacked)"
    if (-not $emptied) { throw 'the broker queue is not empty; a heal from it would not prove MySQL drives recovery' }
    Stamp 'no broker message carries any of the frozen work now - only MySQL state can save it'

    Write-Host ''
    Write-Host '=== heal: roles point at the restored instance ==========' -ForegroundColor Cyan

    $healthWatch = [Diagnostics.Stopwatch]::StartNew()
    # Compressed recovery knobs so the drill finishes in minutes, not hours:
    # stuck-pending grace 10s (default 60s), stuck scan every 5s (default 30s).
    $healWorkerArgs = $leaseArgs + @('--hookflow.relay.stale-publishing-seconds=10', '--hookflow.retry.stuck-scan-interval-ms=5000')
    Start-Role -Name 'api' -Profile 'api' -Port $RestorePort -LogName 'api-restored' -ExtraArgs @('--hookflow.security.egress.allow-private-targets=true')
    Start-Role -Name 'relay' -Profile 'relay' -Port $RestorePort -LogName 'relay-restored'
    Start-Role -Name 'worker' -Profile 'worker' -Port $RestorePort -LogName 'worker-restored' -ExtraArgs $healWorkerArgs

    if (-not (Wait-Health)) { throw 'api never became healthy on the restored instance - see logs/api-restored.log' }
    $healthWatch.Stop()
    Stamp ('api is UP on the restored instance ({0:N1}s after role start)' -f $healthWatch.Elapsed.TotalSeconds)

    $convergeWatch = [Diagnostics.Stopwatch]::StartNew()
    $converged = Wait-Until -TimeoutSec 180 -PollMs 2000 -Condition {
        $bad = Get-Count -Container $restoreContainer -Sql "SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id=$tenantId AND status NOT IN ('SUCCEEDED','DEAD','CANCELED')"
        $unpub = Get-Count -Container $restoreContainer -Sql "SELECT COUNT(*) FROM hookflow.outbox WHERE tenant_id=$tenantId AND status <> 'PUBLISHED'"
        $done = Get-Count -Container $restoreContainer -Sql "SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id=$tenantId AND status='SUCCEEDED'"
        ($bad -eq 0) -and ($unpub -eq 0) -and ($done -eq 29)
    }
    $convergeWatch.Stop()
    if ($converged) {
        Stamp ('converged: 29/29 deliveries SUCCEEDED, outbox fully published, in {0:N1}s' -f $convergeWatch.Elapsed.TotalSeconds)
    } else {
        Stamp 'DID NOT converge within 180s - final state below'
    }

    Write-Host ''
    Write-Host '--- per-event outcome ---'
    foreach ($e in $backlogEvents) {
        $d = Get-Delivery -EventId $e
        Stamp ("  backlog (message never published) event $e -> $($d.delivery.status) attempts=$($d.delivery.attemptCount)")
    }
    foreach ($e in $lostEvents) {
        $d = Get-Delivery -EventId $e
        Stamp ("  lost-message event $e -> $($d.delivery.status) attempts=$($d.delivery.attemptCount)")
    }
    if ($null -ne $slowEvent) {
        $d = Get-Delivery -EventId $slowEvent
        Stamp "  in-flight event $slowEvent -> $($d.delivery.status) attempts=$($d.delivery.attemptCount)"
        foreach ($a in $d.attempts) {
            Stamp "    attempt $($a.attemptNo) $($a.status) error=$($a.errorClass)"
        }
    }

    Write-Host ''
    Write-Host '--- recovery evidence from the restored-instance logs ---'
    Select-String -Path (Join-Path $logDir 'worker-restored.log') -Pattern 'reclaimed|re-enqueued|stuck' -ErrorAction SilentlyContinue |
        ForEach-Object { Stamp ('  ' + $_.Line.Trim()) }

    $finalDist = Get-Dist -Container $restoreContainer -Sql "SELECT status, COUNT(*) FROM hookflow.delivery WHERE tenant_id=$tenantId GROUP BY status"
    Stamp "final tenant delivery distribution: $finalDist"

    # -----------------------------------------------------------------------
    # Clean up the drill tenant in the SOURCE database
    # -----------------------------------------------------------------------
    # Nothing above needs it any more: the frozen shapes live in the restored
    # instance and in the dump, and every assertion has been made. Deleting the
    # tenant is what keeps a replay from piling up `drill-backup-*` tenants in the
    # demo database, and from handing the next replay's setup stale in-flight rows
    # to heal as a side effect.
    if ($KeepTenant) {
        Stamp "tenant $tenantId kept (-KeepTenant): it stays in the source database for inspection"
    } else {
        $cleanup = Remove-DrillTenant -TenantId $tenantId
        if ($null -eq $cleanup) { throw "could not delete drill tenant $tenantId from the source database" }
        if ($cleanup.TenantRows -le 0) { throw "drill tenant $tenantId is still present after the delete" }
        $tenantCleaned = $true
        Stamp ("cleaned up drill tenant {0}: {1}" -f $cleanup.TenantId, (Format-DrillTenantCounts $cleanup))
        Stamp "drill tenants left in the source database: $(@(Get-DrillTenantIds).Count)"
    }

    # -----------------------------------------------------------------------
    # RTO funnel
    # -----------------------------------------------------------------------
    $rtoRestore = $emptyWatch.Elapsed.TotalSeconds + $restoreWatch.Elapsed.TotalSeconds +
                  $verifyWatch.Elapsed.TotalSeconds + $healthWatch.Elapsed.TotalSeconds
    $rtoEndToEnd = $dumpWatch.Elapsed.TotalSeconds + $rtoRestore
    Write-Host ''
    Write-Host '=== RTO funnel ===========================================' -ForegroundColor Cyan
    Write-Host ('  dump (--single-transaction)      {0,7:N1}s   ({1:N1} MB)' -f $dumpWatch.Elapsed.TotalSeconds, $dumpSizeMb)
    Write-Host ('  empty instance ready             {0,7:N1}s' -f $emptyWatch.Elapsed.TotalSeconds)
    Write-Host ('  restore SQL applied              {0,7:N1}s' -f $restoreWatch.Elapsed.TotalSeconds)
    Write-Host ('  verification ({1} checks)         {0,7:N1}s' -f $verifyWatch.Elapsed.TotalSeconds, $checks)
    Write-Host ('  app healthy on restored data     {0,7:N1}s' -f $healthWatch.Elapsed.TotalSeconds)
    Write-Host ('  --- restore RTO (empty -> healthy) {0,6:N1}s' -f $rtoRestore)
    Write-Host ('  --- end to end (dump -> healthy)   {0,6:N1}s' -f $rtoEndToEnd)
    Write-Host ('  catch-up after healthy           {0,7:N1}s' -f $convergeWatch.Elapsed.TotalSeconds)

    Write-Host ''
    if ($converged) {
        Write-Host '=== drill PASS: data survived the restore, the platform healed itself ===' -ForegroundColor Green
    } else {
        Write-Host '=== drill FAIL: the restored platform did not converge ===' -ForegroundColor Red
    }
}
catch {
    Write-Host ''
    Write-Host '=== drill ABORTED =======================================' -ForegroundColor Red
    Write-Host ("  " + $_.Exception.Message)
    throw
}
finally {
    foreach ($profile in @('api', 'relay', 'worker')) {
        $leftover = Get-RolePid -Profile $profile
        if ($leftover) { Stop-Process -Id $leftover -Force -ErrorAction SilentlyContinue }
    }
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains('MockReceiver') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    # A controlled abort (a failed verification, a container that would not start)
    # would otherwise leave the drill tenant frozen in the source database. The
    # next run's pre-flight sweep is the real safety net - it also covers kill -9 -
    # but cleaning up here keeps the database tidy in the common case.
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
    if (-not $KeepRestore) {
        [void](Invoke-Docker -Arguments @('rm', '-f', $restoreContainer) -Quiet)
        Write-Host "restore container removed (use -KeepRestore to keep it for inspection)"
    } else {
        Write-Host "restore container kept: $restoreContainer (port $RestorePort)"
    }
    if (Test-Path $dumpFile) { Write-Host "backup file kept: $dumpFile" }
    Write-Host 'all drill processes stopped'
}
