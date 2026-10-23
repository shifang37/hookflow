#!/usr/bin/env pwsh
# Load / fairness bench. Produces a self-describing results directory that a
# report can quote verbatim: raw load-generator output, GC log, JFR recording,
# Micrometer counters, MySQL state, and the machine spec the numbers belong to.
#
#   .\scripts\bench.ps1 -Scenario api      -Rate 200  -Duration 30
#   .\scripts\bench.ps1 -Scenario api      -Rate 450  -Duration 30 -BatchSize 20
#   .\scripts\bench.ps1 -Scenario overload -Rate 600  -Duration 30
#   .\scripts\bench.ps1 -Scenario fairness -Rate 300  -Duration 12
#
# -Rate is always events/s. -BatchSize > 1 switches the generator to
# POST /v1/events/batch (that many events per request), which is how the v0.4
# batch-ingest claim is measured: same arrival rate, fewer transactions.
#
# -SampleSeconds > 0 adds a second, independent measurement to the same run: a
# sampler polls Micrometer, MySQL and the broker management API every N seconds
# and writes series.csv plus a downstream.txt rate table. That is how v0.4's B0
# answers "where does the next bottleneck bite" - an end-of-run snapshot says how
# much accumulated, not whether it was still accumulating. -TailSeconds keeps the
# sampler (and the app) alive after the load stops, so the backlog curve shows
# the drain-down phase too, not just the rise.
#
# -StatementDigest resets MySQL's per-digest statement counters before the app
# starts and dumps them afterwards (statements.txt), which is how "an ingest costs
# 20 ms" becomes "these statements cost these milliseconds".
#
# The `api` scenario disables the per-tenant watermark unless -TenantWatermark is
# given, because the `all` profile sets it to 200 for the demo console and a
# capacity run that inherits a demo value measures shedding instead of capacity.
# Use `overload` (not `api`) when the shedding threshold itself is the variable.
#
# Prerequisite: docker compose up -d   (use DB_PUBLISH_PORT if 3306 is taken)
#               .\mvnw.cmd -DskipTests package
#
# English-only on purpose (no non-ASCII) so it runs on any Windows PowerShell
# without the encoding trap described in docs/v0.3-plan.md.
#
# Why there is no --rate in the generator's "closed loop" mode: a closed-loop tool
# (ab, k6, JMeter) sends the next request only after the previous response, so
# when the platform slows down the tool slows down too and the queueing delay is
# never measured (coordinated omission). dev.hookflow.tools.LoadGen sends at a
# fixed rate and reports both views of the same run, so the error is visible.

param(
    [ValidateSet('api', 'overload', 'fairness')]
    [string]$Scenario = 'api',
    [int]$Rate = 200,
    [int]$Duration = 30,
    [int]$Warmup = 5,
    [int]$Threads = 4,
    [int]$PayloadBytes = 2048,
    [int]$BatchSize = 1,
    [int]$DbPort = 13306,
    [int]$FloodEventsForFairness = 10,
    [int]$FloodBudget = 4,
    [int]$ReceiverDelayMs = 0,
    [int]$VictimDelayMs = 0,
    [long]$TenantWatermark = 0,
    [string]$MysqlContainer = 'hookflow-mysql',
    [string]$RabbitContainer = 'hookflow-rabbitmq',
    [string]$JvmTuning = '',
    # Downstream sampling (v0.4 B0). 0 keeps the old behaviour: one snapshot at the end.
    [int]$SampleSeconds = 0,
    [int]$TailSeconds = 0,
    # v0.4 B3: reset MySQL's statement digests before the app starts and dump the
    # per-digest totals afterwards, so "where did the milliseconds go" is data
    # instead of a guess. The window covers app start-up, load and tail.
    [switch]$StatementDigest,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# Start-Process builds a child environment and refuses a duplicate proxy variable.
foreach ($k in @('no_proxy')) {
    try { [Environment]::SetEnvironmentVariable($k, $null) } catch { }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = Join-Path $root "bench-results\$stamp-$Scenario-b$BatchSize"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$java = $null
if ($env:JAVA_HOME) {
    foreach ($candidate in @('bin\java.exe', 'bin\java', 'java.exe')) {
        $path = Join-Path $env:JAVA_HOME $candidate
        if (Test-Path $path) { $java = $path; break }
    }
}
if (-not $java) {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if ($found) { $java = $found.Source }
}
if (-not $java) { throw 'No java found: set JAVA_HOME or put java on PATH' }
$classes = Join-Path $root 'target\classes'
# Newest, not first alphabetically: target/ accumulates jars from older versions and
# a bench that silently runs the previous release measures the previous release.
$jarItem = Get-ChildItem (Join-Path $root 'target') -Filter 'hookflow-*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not (Test-Path $classes) -or -not $jarItem) { throw 'Build first: .\mvnw.cmd -DskipTests package' }
$jar = $jarItem.FullName

$baseUrl = 'http://localhost:8080'
$receiverPort = 9099
$bootstrapToken = 'hookflow-dev-bootstrap-token'

# The generator's --rate is events/s in both modes, so the URL and the body shape
# are the only things that change between the single-event and batch runs.
$ingestUrl = if ($BatchSize -gt 1) { "$baseUrl/v1/events/batch" } else { "$baseUrl/v1/events" }

# Fixed heap: a bench whose heap size changes between runs measures the heap, not
# the code. GC logging and JFR are always on so the report has evidence, not just
# percentages.
$jvmArgs = @(
    '-Duser.timezone=UTC',
    '-Xms512m', '-Xmx512m', '-XX:+UseG1GC',
    "-Xlog:gc*:file=$runDir\gc.log:time,uptime,level,tags",
    "-XX:StartFlightRecording=filename=$runDir\jfr.jfr,settings=profile,dumponexit=true"
)
if ($JvmTuning) {
    # For the "did G1 tuning help" A/B: same load, same heap, different collector
    # knobs. Comma separated so the value never starts with '-' - PowerShell would
    # read that as another parameter name.
    foreach ($knob in $JvmTuning.Split(',')) {
        $jvmArgs += ('-XX:' + $knob.Trim())
    }
}
$appArgs = @(
    '--spring.profiles.active=all',
    '--hookflow.security.egress.allow-private-targets=true',
    '--hookflow.fairness.refresh-interval-ms=500',
    '--hookflow.relay.interval-ms=200',
    "--server.port=8080"
)
if ($TenantWatermark -gt 0) {
    # Lowering the per-tenant watermark is how you turn "latency explodes" into
    # "the platform says no": same arrival rate, different shedding threshold.
    $appArgs += "--hookflow.backpressure.tenant-high-watermark=$TenantWatermark"
} elseif ($Scenario -eq 'api') {
    # The `all` profile lowers the tenant watermark to 200 for the demo console
    # (application-all.yml). A capacity run that inherits it measures *shedding*,
    # not capacity: at 300 events/s almost every request comes back 429 and the
    # latencies look excellent because they are the latencies of rejections.
    # The api scenario therefore disables the per-tenant watermark unless the
    # caller asked for a specific one.
    $appArgs += '--hookflow.backpressure.tenant-high-watermark=1000000'
}
$receiverQuery = ''
if ($ReceiverDelayMs -gt 0) {
    $receiverQuery = "?delay=$ReceiverDelayMs"
}

function Stamp($msg) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) }

# Every process this run starts carries this marker, so cleanup is exact: matching
# on "HookFlowApplication" stopped working the moment the app was launched with
# -jar, and a leaked app makes the next run measure the previous one.
$marker = "-Dhookflow.bench.run=$stamp"

function Get-FloodPids {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -and $_.CommandLine.Contains($marker) -and
            $_.CommandLine.Contains('-Dhookflow.bench.role=flood')
        } |
        ForEach-Object { $_.ProcessId }
}

function Get-AppPid {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -and $_.CommandLine.Contains($marker) -and
            $_.CommandLine.Contains('-jar')
        } |
        Select-Object -First 1 -ExpandProperty ProcessId
}

# JFR's dumponexit never runs: the bench kills the app, and a killed process has no
# shutdown hook. An explicit dump is the only way the recording is not a 0-byte file.
function Dump-Jfr {
    $appPid = Get-AppPid
    if (-not $appPid) { return }
    $jcmd = Join-Path (Split-Path $java -Parent) 'jcmd.exe'
    if (-not (Test-Path $jcmd)) { $jcmd = 'jcmd' }
    try {
        & $jcmd $appPid JFR.dump "filename=$runDir\jfr.jfr" 2>&1 | Out-Null
        Stamp 'JFR recording dumped'
    } catch {
        Stamp "could not dump JFR: $($_.Exception.Message)"
    }
}

function Stop-Children {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains($marker) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# Children are started through WMI (Start-Process is unusable here - see the
# v0.3 plan's environment notes), so every environment variable is written into a
# tiny .bat instead of being inherited.
function Start-Background([string]$name, [string]$commandLine) {
    $bat = Join-Path $runDir "$name.bat"
    $lines = @('@echo off', "set DB_PORT=$DbPort", "set TZ=UTC", $commandLine)
    Set-Content -Path $bat -Value $lines -Encoding ASCII
    Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
        -Arguments @{ CommandLine = 'cmd.exe /c "' + $bat + '"' } | Out-Null
}

function Wait-Health([int]$timeoutSec = 120) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 3
            if ($h.status -eq 'UP') { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-Api([string]$method, [string]$path, $body, [hashtable]$headers = @{}) {
    $req = @{ Method = $method; Uri = "$baseUrl$path"; Headers = $headers; ContentType = 'application/json' }
    if ($null -ne $body) { $req.Body = ($body | ConvertTo-Json -Depth 8 -Compress) }
    Invoke-RestMethod @req
}

function Invoke-Sql([string]$query) {
    # Native stderr becomes an ErrorRecord, and with ErrorActionPreference=Stop that
    # throws; a bench helper should surface MySQL's message as text instead.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& docker exec -e MYSQL_PWD=hookflow $MysqlContainer mysql -uhookflow -N -B -e $query 2>&1)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-SqlAsRoot([string]$query) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& docker exec -e MYSQL_PWD=root $MysqlContainer mysql -uroot -N -B -e $query 2>&1)
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Statement digests are cumulative for the server's lifetime, so a run that does not
# reset them measures every previous run as well. Truncating needs the root account:
# the application's own user is not allowed to touch performance_schema.
function Reset-Statements {
    $result = Invoke-SqlAsRoot 'TRUNCATE performance_schema.events_statements_summary_by_digest'
    if (($result -join ' ').Trim() -ne '') {
        Stamp "could not reset statement digests: $($result -join ' ')"
    } else {
        Stamp 'statement digests reset (window = this run)'
    }
}

function Write-StatementDigest {
    $sql = "SELECT COUNT_STAR AS n, ROUND(SUM_TIMER_WAIT/1000000000000,2) AS total_s, " +
           "ROUND(AVG_TIMER_WAIT/1000000000,3) AS avg_ms, " +
           "ROUND(SUM_ROWS_AFFECTED/COUNT_STAR,1) AS rows_per_call, " +
           "ROUND(SUM_LOCK_TIME/1000000000000,2) AS lock_s, " +
           "LEFT(DIGEST_TEXT,120) AS digest " +
           "FROM performance_schema.events_statements_summary_by_digest " +
           "WHERE SCHEMA_NAME='hookflow' ORDER BY SUM_TIMER_WAIT DESC LIMIT 40"
    $lines = @("n`ttotal_s`tavg_ms`trows_per_call`tlock_s`tdigest") + (Invoke-SqlAsRoot $sql)
    $lines | Set-Content -Path (Join-Path $runDir 'statements.txt') -Encoding UTF8
    Write-Host ''
    Write-Host 'top statements by total time (MySQL digest, schema hookflow):'
    $lines | Select-Object -First 15 | ForEach-Object { Write-Host $_ }
}

function Get-Unpublished {
    $value = Invoke-Sql "SELECT COUNT(*) FROM hookflow.outbox WHERE status IN ('NEW','PUBLISHING')"
    return ($value | Select-Object -First 1)
}

function Invoke-Rabbit([string]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& docker exec $RabbitContainer rabbitmqctl -q $arguments.Split(' ') 2>&1)
    } finally {
        $ErrorActionPreference = $previous
    }
}

# The management HTTP API is not usable on this machine (every /api/queues request
# times out behind the local proxy, cold or warm), so the broker is read the same
# way the database is: through rabbitmqctl inside the container.
function Get-QueueStats {
    $json = Invoke-Rabbit 'list_queues --formatter json name messages_ready messages_unacknowledged consumers consumer_utilisation'
    $text = ($json -join "`n")
    if (-not $text.Trim().StartsWith('[')) { return $null }
    $queues = $text | ConvertFrom-Json
    foreach ($queue in $queues) {
        if ($queue.name -eq 'hookflow.delivery') { return $queue }
    }
    return $null
}

# A queue declared with different arguments can never be re-declared: RabbitMQ
# answers PRECONDITION_FAILED, the app logs it every few seconds, and the *old*
# queue - typically one without the length cap - keeps serving. A run on top of
# that measures a topology no configuration file describes, so the bench drops the
# queues and lets the app it starts recreate them from application.yml.
function Reset-Broker {
    foreach ($queue in @('hookflow.delivery', 'hookflow.delivery.dlq')) {
        Invoke-Rabbit "delete_queue $queue" | Out-Null
    }
    Stamp 'broker queues deleted (the app will recreate the declared topology)'
}

# --------------------------------------------------------------- sampling ----
# Three sources, because each one alone is ambiguous:
#   Micrometer  what the service says it did (published / succeeded / shed)
#   MySQL       row counts - the only place a backlog can actually be verified
#   broker      queue depth and consumer utilisation, which is what separates
#               "the relay cannot publish fast enough" (queue empty, worker idle)
#               from "the worker cannot keep up" (queue growing, util ~1)
# The sampler runs in its own process so it cannot be slowed down by the load
# generator, and it stops on a stop file rather than Stop-Job so the last line of
# the CSV is never a half-written one.
$samplerStopFile = Join-Path $runDir 'sampler.stop'
$seriesFile = Join-Path $runDir 'series.csv'
$samplerLog = Join-Path $runDir 'sampler.log'

$seriesColumns = @('t', 'elapsed_s', 'phase',
    'accepted', 'published', 'outbox_backlog', 'outbox_new_db', 'delivery_pending_db',
    'delivery_succeeded', 'publish_rejected', 'publish_failed', 'hikaricp_pending',
    'lock_conflict', 'ingest_shed', 'consumer_acked', 'consumer_requeued',
    'worker_over_capacity', 'mq_ready', 'mq_unacked', 'mq_consumers', 'mq_utilisation')

function Start-DownstreamSampler {
    if ($SampleSeconds -le 0) { return $null }
    if (Test-Path $samplerStopFile) { Remove-Item $samplerStopFile -Force }
    Set-Content -Path $seriesFile -Value (($seriesColumns) -join ',') -Encoding ASCII
    Set-Content -Path $samplerLog -Value '' -Encoding ASCII
    $arguments = @("$baseUrl/actuator/prometheus", $seriesFile, $samplerStopFile, $samplerLog,
        ($SampleSeconds * 1000), $MysqlContainer, $RabbitContainer, $Warmup, $Duration, $TailSeconds)
    Write-Host "sampling every $SampleSeconds s -> series.csv"
    return Start-Job -ArgumentList $arguments -ScriptBlock {
        param($promUrl, $outFile, $stopFile, $logFile, $intervalMs, $container, $rabbitContainer, $warmup, $duration, $tail)

        $ErrorActionPreference = 'Continue'
        $start = Get-Date
        $endOfWarmup = [double]$warmup
        $endOfLoad = [double]($warmup + $duration)

        # Sums every series with this exact metric name (all label sets). A metric
        # that has never been touched is absent from the scrape; it is reported as
        # $null rather than 0 so a missing source cannot look like a real dip.
        function Sum-Metric([string[]]$lines, [string]$name, [string[]]$mustContain = @()) {
            $total = 0.0
            $seen = $false
            foreach ($line in $lines) {
                if (-not $line.StartsWith($name)) { continue }
                $rest = $line.Substring($name.Length)
                if ($rest.Length -gt 0 -and $rest[0] -ne '{' -and $rest[0] -ne ' ') { continue }
                $matched = $true
                foreach ($needle in $mustContain) {
                    if (-not $line.Contains($needle)) { $matched = $false; break }
                }
                if (-not $matched) { continue }
                $value = ($line -split '\s+')[-1]
                $parsed = 0.0
                if ([double]::TryParse($value, [ref]$parsed)) { $total += $parsed; $seen = $true }
            }
            if (-not $seen) { return $null }
            return $total
        }

        function Column($value) {
            if ($null -eq $value) { return '' }
            if ($value -is [double]) { return [string]([math]::Round($value, 3)) }
            return [string]$value
        }

        $sample_error = ''
        while (-not (Test-Path $stopFile)) {
            $now = Get-Date
            $elapsed = [math]::Round(($now - $start).TotalSeconds, 2)
            $phase = if ($elapsed -lt $endOfWarmup) { 'WARMUP' }
                     elseif ($elapsed -le $endOfLoad) { 'LOAD' }
                     else { 'TAIL' }

            $accepted = ''; $published = ''; $backlog = ''; $newDb = ''; $pendingDb = ''
            $succeeded = ''; $rejected = ''; $failed = ''; $hikariPending = ''
            $lockConflict = ''; $shed = ''; $acked = ''; $requeued = ''; $overCapacity = ''
            $mqReady = ''; $mqUnacked = ''; $mqConsumers = ''; $mqUtilisation = ''

            try {
                $prom = (Invoke-WebRequest -Uri $promUrl -TimeoutSec 15 -UseBasicParsing).Content
                $lines = $prom -split "`n"
                # /v1/events and /v1/events/batch both start with uri="/v1/events, and
                # 200 is a batch replayed-as-duplicate while 202 is new work.
                $accepted202 = Sum-Metric $lines 'http_server_requests_seconds_count' @('uri="/v1/events', 'status="202"')
                $accepted200 = Sum-Metric $lines 'http_server_requests_seconds_count' @('uri="/v1/events', 'status="200"')
                if ($null -ne $accepted202 -and $null -ne $accepted200) { $accepted = $accepted202 + $accepted200 }
                elseif ($null -ne $accepted202) { $accepted = $accepted202 }
                elseif ($null -ne $accepted200) { $accepted = $accepted200 }
                $published = Sum-Metric $lines 'hookflow_outbox_published_total'
                $succeeded = Sum-Metric $lines 'hookflow_delivery_succeeded_total'
                $rejected = Sum-Metric $lines 'hookflow_outbox_publish_rejected_total'
                $failed = Sum-Metric $lines 'hookflow_outbox_publish_failed_total'
                $hikariPending = Sum-Metric $lines 'hikaricp_connections_pending'
                $lockConflict = Sum-Metric $lines 'hookflow_db_lock_conflict_total'
                $shed = Sum-Metric $lines 'hookflow_ingest_shed_total'
                $acked = Sum-Metric $lines 'hookflow_consumer_acked_total'
                $requeued = Sum-Metric $lines 'hookflow_consumer_requeued_total'
                $overCapacity = Sum-Metric $lines 'hookflow_worker_over_capacity_total'
            } catch {
                Add-Content -Path $logFile -Value ("[{0}] prometheus: {1}" -f $now.ToString('HH:mm:ss'), $_.Exception.Message)
            }

            try {
                # Schema-qualified: the CLI has no default database here, and an
                # unqualified query fails with "No database selected" - which would
                # silently turn the backlog column into a blank.
                $sql = "SELECT (SELECT COUNT(*) FROM hookflow.outbox WHERE status='NEW')," +
                       "(SELECT COUNT(*) FROM hookflow.delivery WHERE status='PENDING')," +
                       "(SELECT COUNT(*) FROM hookflow.outbox WHERE status IN ('NEW','PUBLISHING'))"
                $raw = & docker exec -e MYSQL_PWD=hookflow $container mysql -uhookflow -N -B -e $sql 2>&1
                $parts = ([string]($raw | Select-Object -First 1)) -split "`t"
                if ($parts.Count -ge 3) {
                    $newDb = [double]$parts[0]
                    $pendingDb = [double]$parts[1]
                    $backlog = [double]$parts[2]
                } else {
                    Add-Content -Path $logFile -Value ("[{0}] mysql: {1}" -f $now.ToString('HH:mm:ss'), ([string]($raw | Select-Object -First 1)))
                }
            } catch {
                Add-Content -Path $logFile -Value ("[{0}] mysql: {1}" -f $now.ToString('HH:mm:ss'), $_.Exception.Message)
            }

            try {
                $raw = & docker exec $rabbitContainer rabbitmqctl -q list_queues --formatter json `
                    name messages_ready messages_unacknowledged consumers consumer_utilisation 2>&1
                $text = ([string]($raw -join "`n")).Trim()
                if ($text.StartsWith('[')) {
                    foreach ($queue in ($text | ConvertFrom-Json)) {
                        if ($queue.name -ne 'hookflow.delivery') { continue }
                        $mqReady = [double]$queue.messages_ready
                        $mqUnacked = [double]$queue.messages_unacknowledged
                        $mqConsumers = [double]$queue.consumers
                        if ([string]$queue.consumer_utilisation -ne '') { $mqUtilisation = [double]$queue.consumer_utilisation }
                    }
                }
            } catch {
                Add-Content -Path $logFile -Value ("[{0}] broker: {1}" -f $now.ToString('HH:mm:ss'), $_.Exception.Message)
            }

            $row = @($now.ToString('HH:mm:ss'), (Column $elapsed), $phase,
                (Column $accepted), (Column $published), (Column $backlog), (Column $newDb),
                (Column $pendingDb), (Column $succeeded), (Column $rejected), (Column $failed),
                (Column $hikariPending), (Column $lockConflict), (Column $shed),
                (Column $acked), (Column $requeued), (Column $overCapacity),
                (Column $mqReady), (Column $mqUnacked), (Column $mqConsumers), (Column $mqUtilisation))
            Add-Content -Path $outFile -Value ($row -join ',')
            Start-Sleep -Milliseconds $intervalMs
        }
    }
}

function Stop-DownstreamSampler($job) {
    if (-not $job) { return }
    New-Item -ItemType File -Force -Path $samplerStopFile | Out-Null
    Wait-Job $job -Timeout 60 | Out-Null
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    Stamp "sampler stopped ($samplerLog)"
}

# Turns series.csv into the table the report actually quotes: per-interval rates
# instead of cumulative counters. Anything that is not a number (a failed poll)
# makes that pair of rows unusable rather than being counted as 0 - a hole in the
# data must not look like a dip in throughput.
function Write-DownstreamReport {
    if ($SampleSeconds -le 0 -or -not (Test-Path $seriesFile)) { return }
    $rows = @(Import-Csv -Path $seriesFile)
    if ($rows.Count -lt 2) { return }

    function ToNumber($value) {
        $parsed = 0.0
        if ([double]::TryParse([string]$value, [ref]$parsed)) { return $parsed }
        return $null
    }

    $lines = @()
    $lines += ("# {0} events/s, batch {1}, warmup {2}s, load {3}s, tail {4}s" -f $Rate, $BatchSize, $Warmup, $Duration, $TailSeconds)
    $lines += ''

    function Format-Rate($value) {
        if ($null -eq $value) { return '-' }
        return ('{0:0.0}' -f [double]$value)
    }
    function Format-Count($value) {
        if ($null -eq $value) { return '-' }
        return ('{0:0}' -f [double]$value)
    }
    function Format-Row([string[]]$cells) {
        $widths = @(8, 7, 9, 10, 10, 10, 9, 7, 7, 6, 8, 8)
        $parts = @()
        for ($c = 0; $c -lt $cells.Count; $c++) { $parts += $cells[$c].PadLeft($widths[$c]) }
        return ($parts -join ' ')
    }

    $lines += (Format-Row -cells @('elapsed', 'phase', 'accept/s', 'publish/s', 'succeed/s',
            'backlog', 'delivPend', 'mqReady', 'mqUnack', 'hikPend', 'reject/s', 'lock/s'))

    $steady = New-Object System.Collections.ArrayList
    for ($i = 1; $i -lt $rows.Count; $i++) {
        $previous = $rows[$i - 1]
        $current = $rows[$i]
        $elapsed = ToNumber $current.elapsed_s
        $previousElapsed = ToNumber $previous.elapsed_s
        if ($null -eq $elapsed -or $null -eq $previousElapsed) { continue }
        $dt = $elapsed - $previousElapsed
        if ($dt -le 0) { continue }

        $rate = @{}
        foreach ($name in @('accepted', 'published', 'delivery_succeeded', 'publish_rejected', 'lock_conflict')) {
            $now = ToNumber $current.$name
            $before = ToNumber $previous.$name
            if ($null -ne $now -and $null -ne $before) {
                $rate[$name] = ($now - $before) / $dt
            } else {
                $rate[$name] = $null
            }
        }

        $lines += (Format-Row -cells @(
            ('{0:0.0}' -f $elapsed),
            $current.phase,
            (Format-Rate $rate.accepted),
            (Format-Rate $rate.published),
            (Format-Rate $rate.delivery_succeeded),
            (Format-Count (ToNumber $current.outbox_backlog)),
            (Format-Count (ToNumber $current.delivery_pending_db)),
            (Format-Count (ToNumber $current.mq_ready)),
            (Format-Count (ToNumber $current.mq_unacked)),
            (Format-Rate (ToNumber $current.hikaricp_pending)),
            (Format-Rate $rate.publish_rejected),
            (Format-Rate $rate.lock_conflict)
        ))
        if ($current.phase -eq 'LOAD' -and $null -ne $rate.published) { $steady.Add($rate.published) | Out-Null }
    }

    if ($steady.Count -gt 0) {
        $average = ($steady | Measure-Object -Average).Average
        $lines += ''
        $lines += ("# steady-window publish rate: mean {0:0.0} events/s over {1} intervals (min {2:0.0}, max {3:0.0})" -f
            $average, $steady.Count, ($steady | Measure-Object -Minimum).Minimum, ($steady | Measure-Object -Maximum).Maximum)
    }
    $lines | Set-Content -Path (Join-Path $runDir 'downstream.txt') -Encoding ASCII
    Write-Host ''
    $lines | ForEach-Object { Write-Host $_ }
}

# A bench against a database that still holds the previous run's backlog measures
# the previous run. Foreign keys are switched off for the whole statement batch
# (session scoped, so it has to be one mysql invocation), which also keeps the
# delete order from mattering.
function Reset-Database {
    $sql = 'SET FOREIGN_KEY_CHECKS=0; ' +
           'DELETE FROM hookflow.outbox; DELETE FROM hookflow.delivery_attempt; ' +
           'DELETE FROM hookflow.delivery; DELETE FROM hookflow.event; ' +
           'DELETE FROM hookflow.audit_log; DELETE FROM hookflow.endpoint; ' +
           'DELETE FROM hookflow.api_key; DELETE FROM hookflow.tenant; ' +
           'SET FOREIGN_KEY_CHECKS=1;'
    Invoke-Sql $sql | Out-Null
    Stamp 'database reset (deliveries, events, outbox, endpoints, tenants)'
}

function Get-Percentile([long[]]$values, [double]$p) {
    if ($values.Count -eq 0) { return 0 }
    $sorted = $values | Sort-Object
    $index = [Math]::Ceiling($p / 100.0 * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    return $sorted[$index]
}

function New-Tenant([string]$label, [int]$maxInFlight) {
    $body = @{ name = "$label-" + (Get-Date -Format 'HHmmssff'); rateLimit = 0; maxInFlight = $maxInFlight; weight = 1 }
    return Invoke-Api -method POST -path '/v1/admin/tenants' -body $body `
        -headers @{ 'X-Bootstrap-Token' = $bootstrapToken }
}

# ------------------------------------------------------------------ setup ----
try {
    if (-not $NoStart) {
        if (-not $KeepData) { Reset-Database; Reset-Broker }
        if ($StatementDigest) { Reset-Statements }
        Stamp 'starting receiver and application'
        Start-Background 'receiver' ('"' + $java + '" ' + $marker + ' -cp "' + $classes +
            '" dev.hookflow.tools.MockReceiver ' +
            $receiverPort + ' "" quiet > "' + $runDir + '\receiver.log" 2>&1')
        Start-Background 'app' ('"' + $java + '" ' + $marker + ' ' + ($jvmArgs -join ' ') + ' -jar "' + $jar +
            '" ' + ($appArgs -join ' ') + ' > "' + $runDir + '\app.log" 2>&1')
        if (-not (Wait-Health)) { throw "application never became healthy - see $runDir\app.log" }
        Stamp 'application is UP'
    }

    $outboxUnpublished = Get-Unpublished
    Write-Host "outbox backlog before the run: $outboxUnpublished"

    switch ($Scenario) {
        'api' {
            $tenant = New-Tenant 'bench' 0
            Invoke-Api -method POST -path '/v1/endpoints' -headers @{ Authorization = "Bearer $($tenant.apiKey)" } `
                -body @{ url = "http://127.0.0.1:$receiverPort/hook$receiverQuery"; subscribedTypes = 'load.gen'; maxAttempts = 3 } | Out-Null
            Stamp "tenant $($tenant.tenantId) provisioned (rate limit disabled: measuring capacity, not quota)"

            $sampler = Start-DownstreamSampler
            $loadgen = Join-Path $runDir 'loadgen.txt'
            & $java -cp $classes dev.hookflow.tools.LoadGen `
                --url $ingestUrl --api-key $tenant.apiKey `
                --rate $Rate --duration $Duration --warmup $Warmup --threads $Threads `
                --payload-bytes $PayloadBytes --batch-size $BatchSize --tag $Scenario `
                --timeline (Join-Path $runDir 'timeline.csv') 2>&1 | Tee-Object -FilePath $loadgen
            if ($TailSeconds -gt 0) {
                Stamp "load finished; watching $TailSeconds s of drain-down (sampler keeps running)"
                Start-Sleep -Seconds $TailSeconds
            }
            Stop-DownstreamSampler $sampler
            Write-DownstreamReport
        }
        'overload' {
            $tenant = New-Tenant 'overload' 0
            Invoke-Api -method POST -path '/v1/endpoints' -headers @{ Authorization = "Bearer $($tenant.apiKey)" } `
                -body @{ url = "http://127.0.0.1:$receiverPort/hook$receiverQuery"; subscribedTypes = 'load.gen'; maxAttempts = 3 } | Out-Null
            Stamp "tenant $($tenant.tenantId) provisioned; arrival rate is $Rate req/s"

            $sampler = Start-DownstreamSampler
            $loadgen = Join-Path $runDir 'loadgen.txt'
            & $java -cp $classes dev.hookflow.tools.LoadGen `
                --url $ingestUrl --api-key $tenant.apiKey `
                --rate $Rate --duration $Duration --warmup $Warmup --threads $Threads `
                --payload-bytes $PayloadBytes --batch-size $BatchSize --tag $Scenario `
                --timeline (Join-Path $runDir 'timeline.csv') 2>&1 | Tee-Object -FilePath $loadgen
            Stamp "outbox backlog right after the run: $(Get-Unpublished)"
            Start-Sleep -Seconds 15
            Stamp "outbox backlog 15s later (did it die or did it drain?): $(Get-Unpublished)"
            Stop-DownstreamSampler $sampler
            Write-DownstreamReport
        }
        'fairness' {
            $results = @()
            foreach ($budget in @($FloodBudget, 0)) {
                $label = "budget$budget"
                Stamp "=== fairness run: flood tenant max_in_flight=$budget ==="
                $flood = New-Tenant 'flood' $budget
                $victim = New-Tenant 'victim' 32
                # The flood tenant's receiver is slow so deliveries queue up; the
                # victim's is fast so its measured latency is (almost) purely the time
                # it spends waiting behind somebody else's queue. That separation is
                # the whole point of the drill.
                Invoke-Api -method POST -path '/v1/endpoints' `
                    -headers @{ Authorization = "Bearer $($flood.apiKey)" } `
                    -body @{ url = "http://127.0.0.1:$receiverPort/hook$receiverQuery"; subscribedTypes = 'load.gen'; maxAttempts = 3 } | Out-Null
                Invoke-Api -method POST -path '/v1/endpoints' `
                    -headers @{ Authorization = "Bearer $($victim.apiKey)" } `
                    -body @{ url = "http://127.0.0.1:$receiverPort/hook?delay=$VictimDelayMs"; subscribedTypes = 'load.gen'; maxAttempts = 3 } | Out-Null

                $floodLog = Join-Path $runDir "flood-$label.txt"
                Start-Background "flood-$label" ('"' + $java + '" ' + $marker +
                    ' -Dhookflow.bench.role=flood -cp "' + $classes +
                    '" dev.hookflow.tools.LoadGen --url "' + $baseUrl + '/v1/events" --api-key ' + $flood.apiKey +
                    ' --rate ' + $Rate + ' --duration ' + $Duration + ' --warmup 0 --threads ' + $Threads +
                    ' --payload-bytes ' + $PayloadBytes + ' --tag ' + $label + ' > "' + $floodLog + '" 2>&1')

                Start-Sleep -Seconds 2
                $victimIds = @()
                foreach ($i in 1..$FloodEventsForFairness) {
                    $r = Invoke-Api -method POST -path '/v1/events' `
                        -headers @{ Authorization = "Bearer $($victim.apiKey)";
                                    'Idempotency-Key' = "victim-$label-$i-" + [guid]::NewGuid().ToString('N') } `
                        -body @{ eventType = 'load.gen'; payload = @{ n = $i } }
                    $victimIds += $r.eventId
                }
                Stamp "$($victimIds.Count) victim events submitted while the flood is running"

                # Let the generator finish first: its own output is evidence, and a
                # still-running flood would keep creating deliveries after we cancel.
                $floodDeadline = (Get-Date).AddSeconds($Duration + 60)
                while ((Get-Date) -lt $floodDeadline) {
                    if (-not (Get-FloodPids)) { break }
                    Start-Sleep -Milliseconds 500
                }

                # Wait for the victim's deliveries to settle. Their end-to-end
                # latency is the measurement; the flood is only background load.
                $deadline = (Get-Date).AddSeconds(120)
                $stillPending = -1
                while ((Get-Date) -lt $deadline) {
                    $stillPending = [int](Invoke-Sql ("SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id=" +
                        $victim.tenantId + " AND status IN ('PENDING','DELIVERING','RETRY_WAIT')"))
                    if ($stillPending -eq 0) { break }
                    Start-Sleep -Milliseconds 500
                }
                if ($stillPending -ne 0) {
                    throw "victim deliveries did not settle within 120s ($stillPending still open) - a 0 ms result would be a lie"
                }

                $idList = ($victimIds -join ',')
                $raw = Invoke-Sql ("SELECT TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) " +
                    "FROM hookflow.delivery WHERE tenant_id=" + $victim.tenantId +
                    " AND event_id IN (" + $idList + ") AND status = 'SUCCEEDED'")
                $latencies = @()
                foreach ($line in $raw) { if ($line -match '^\d+$') { $latencies += [long]([long]$line / 1000) } }
                $maxMs = 0
                if ($latencies.Count -gt 0) { $maxMs = ($latencies | Measure-Object -Maximum).Maximum }

                $summary = [pscustomobject]@{
                    budget       = $budget
                    victimEvents = $latencies.Count
                    p50Ms        = Get-Percentile $latencies 50
                    p95Ms        = Get-Percentile $latencies 95
                    p99Ms        = Get-Percentile $latencies 99
                    maxMs        = $maxMs
                    floodPending = (Invoke-Sql ("SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id=" +
                        $flood.tenantId + " AND status IN ('PENDING','DELIVERING','RETRY_WAIT')"))
                }
                $results += $summary
                Stamp ("FAIRNESS[" + $label + "] n=" + $summary.victimEvents + " p50=" + $summary.p50Ms +
                    "ms p95=" + $summary.p95Ms + "ms p99=" + $summary.p99Ms + "ms max=" + $summary.maxMs +
                    "ms floodStillQueued=" + $summary.floodPending)

                # Clean up instead of draining: cancel the flood's queued deliveries and
                # drop its pending outbox rows, otherwise the next run inherits them and
                # measures the previous run.
                Invoke-Sql ("UPDATE hookflow.delivery SET status='CANCELED' WHERE tenant_id=" +
                    $flood.tenantId + " AND status IN ('PENDING','RETRY_WAIT')") | Out-Null
                Invoke-Sql ("DELETE FROM hookflow.outbox WHERE tenant_id IN (" + $flood.tenantId +
                    "," + $victim.tenantId + ")") | Out-Null
                $drainDeadline = (Get-Date).AddSeconds(60)
                while ((Get-Date) -lt $drainDeadline) {
                    $inFlight = [int](Invoke-Sql ("SELECT COUNT(*) FROM hookflow.delivery WHERE tenant_id IN (" +
                        $flood.tenantId + "," + $victim.tenantId + ") AND status = 'DELIVERING'"))
                    if ($inFlight -eq 0) { break }
                    Start-Sleep -Milliseconds 300
                }
            }
            $results | Format-Table -AutoSize | Tee-Object -FilePath (Join-Path $runDir 'fairness.txt')
            $results | ConvertTo-Json | Set-Content -Path (Join-Path $runDir 'fairness.json') -Encoding UTF8
        }
    }

    # ------------------------------------------------------------- evidence --
    Stamp 'collecting metrics and database state'
    try {
        $prom = (Invoke-WebRequest -Uri "$baseUrl/actuator/prometheus" -TimeoutSec 20 -UseBasicParsing).Content
        $interesting = @('hookflow_outbox_backlog', 'hookflow_outbox_published_total',
            'hookflow_outbox_publish_failed_total', 'hookflow_outbox_publish_rejected_total',
            'hookflow_fairness_admitted_total', 'hookflow_fairness_skipped_over_budget_total',
            'hookflow_ingest_shed_total', 'hookflow_consumer_acked_total', 'hookflow_consumer_requeued_total',
            'hookflow_delivery_succeeded_total', 'hookflow_delivery_retry_scheduled_total',
            'hookflow_worker_over_capacity_total',
            'http_server_requests_seconds', 'jvm_gc_pause_seconds', 'hikaricp_connections')
        $selected = $prom -split "`n" | Where-Object {
            $line = $_
            ($interesting | Where-Object { $line -like "$_*" }).Count -gt 0
        }
        $selected | Set-Content -Path (Join-Path $runDir 'metrics.txt') -Encoding UTF8
    } catch {
        Stamp "could not read /actuator/prometheus: $($_.Exception.Message)"
    }

    $db = @(
        'SELECT status, COUNT(*) FROM hookflow.delivery GROUP BY status ORDER BY status;',
        'SELECT status, COUNT(*) FROM hookflow.outbox GROUP BY status ORDER BY status;',
        'SELECT COUNT(*) AS deliveries, ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, updated_at))/1000,1) AS avg_ms FROM hookflow.delivery WHERE status = ''SUCCEEDED'';'
    ) -join ' '
    Invoke-Sql $db | Set-Content -Path (Join-Path $runDir 'db.txt') -Encoding UTF8

    if ($StatementDigest) { Write-StatementDigest }

    $queueStats = Get-QueueStats
    if ($null -ne $queueStats) {
        $queueStats | ConvertTo-Json | Set-Content -Path (Join-Path $runDir 'broker.txt') -Encoding UTF8
    }

    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $os = Get-CimInstance Win32_OperatingSystem
    $meta = @()
    $meta += "scenario        $Scenario"
    $meta += "rate            $Rate events/s (open loop)"
    $meta += "batch size      $BatchSize event(s) per request ($ingestUrl)"
    $meta += "duration        $Duration s (warmup $Warmup s)"
    $meta += "senders         $Threads"
    $meta += "payload         $PayloadBytes bytes"
    $meta += "host cpu        $($cpu.Name), $($cpu.NumberOfCores)C/$($cpu.NumberOfLogicalProcessors)T"
    $meta += "host memory     $([math]::Round($os.TotalVisibleMemorySize / 1MB, 1)) GiB"
    $meta += "os              $($os.Caption) build $($os.BuildNumber)"
    # `java -version` writes to stderr, and with ErrorActionPreference=Stop that
    # becomes a terminating NativeCommandError - the same trap as Invoke-Sql.
    $javaVersion = 'unknown'
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $javaVersion = (& $java -version 2>&1 | Select-Object -First 1)
    } finally {
        $ErrorActionPreference = $previous
    }
    $meta += "java            $javaVersion"
    $meta += "jvm args        $($jvmArgs -join ' ')"
    $meta += "app args        $($appArgs -join ' ')"
    $meta += "git             $(git rev-parse --short HEAD)"
    $meta += "docker          $(docker version --format '{{.Server.Version}}')"
    $meta += "started         $stamp"
    $meta | Set-Content -Path (Join-Path $runDir 'meta.txt') -Encoding UTF8

    Write-Host ''
    Write-Host "results in $runDir" -ForegroundColor Green
    Get-ChildItem $runDir | Select-Object Name, Length | Format-Table -AutoSize
}
finally {
    if (-not $NoStart) {
        Dump-Jfr
        Stop-Children
        Write-Host 'bench processes stopped'
    }
}

