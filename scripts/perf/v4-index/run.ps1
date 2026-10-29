# =====================================================================
# V4 index benchmark: run it.
#
#   pwsh scripts/perf/v4-index/run.ps1
#
# Starts a throwaway MySQL 8.4, seeds the dataset from seed.sql, then runs the
# same three measurements twice: once with idx_delivery_updated_status dropped
# ("without-index", the state before V4) and once with it in place.
# Raw output goes to docs/perf/2026-10-29-v4-index/; the report there quotes
# only those files.
#
# The container is left running so the numbers can be re-queried:
#   docker rm -f hookflow-v4-bench
# =====================================================================
param(
    [string]$Container = 'hookflow-v4-bench',
    [string]$Image     = 'mysql:8.4',
    [int]   $Port      = 33161,
    [string]$OutDir    = 'docs/perf/2026-10-29-v4-index'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$sqlDir   = Join-Path $repoRoot 'scripts\perf\v4-index'
$outDir   = Join-Path $repoRoot $OutDir
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Invoke-Sql([string]$file, [string]$outFile) {
    $path = Join-Path $sqlDir $file
    $cmd = "docker exec -i -e MYSQL_PWD=bench $Container mysql -h127.0.0.1 -uroot hookflow --table < `"$path`""
    $output = & cmd /c $cmd 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | Out-Host
        throw "mysql failed for $file (exit $LASTEXITCODE)"
    }
    if ($outFile) {
        $output | Out-File -FilePath (Join-Path $outDir $outFile) -Encoding ascii
        Write-Host "-- $file -> $outFile"
    }
    return $output
}

function Invoke-SqlStatement([string]$sql) {
    $cmd = "docker exec -e MYSQL_PWD=bench $Container mysql -h127.0.0.1 -uroot hookflow --table -e `"$sql`""
    $output = & cmd /c $cmd 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | Out-Host
        throw "mysql failed for statement (exit $LASTEXITCODE)"
    }
    return $output
}

Write-Host "== container =="
& docker rm -f $Container 2>&1 | Out-Null
& docker run -d --name $Container `
    -e MYSQL_ROOT_PASSWORD=bench -e MYSQL_DATABASE=hookflow `
    -p "${Port}:3306" $Image `
    --innodb-buffer-pool-size=2G --innodb-flush-log-at-trx-commit=1 --skip-log-bin | Out-Null

# The image starts a temporary server to run its init script and then restarts the
# real one, so "ping succeeds" is true for a moment and then false again. Probe twice,
# a couple of seconds apart, over TCP — the same path the benchmark itself uses.
$ready = $false
$previousPreference = $ErrorActionPreference
# "Can't connect" is the expected answer while the server is booting; PS 5.1 turns a
# native command's stderr into an error record, which Stop would turn into a dead script.
$ErrorActionPreference = 'SilentlyContinue'
for ($i = 0; $i -lt 120; $i++) {
    & cmd /c "docker exec -e MYSQL_PWD=bench $Container mysql -h127.0.0.1 -uroot -e `"SELECT 1`"" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Start-Sleep -Seconds 3
        & cmd /c "docker exec -e MYSQL_PWD=bench $Container mysql -h127.0.0.1 -uroot -e `"SELECT 1`"" 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    Start-Sleep -Seconds 1
}
$ErrorActionPreference = $previousPreference
if (-not $ready) { throw "MySQL did not become ready" }

Write-Host "== meta =="
$meta = @()
$meta += "date: $(Get-Date -Format o)"
$meta += "git: $(git -C $repoRoot rev-parse --short HEAD)"
$meta += "docker: $((docker version --format '{{.Server.Version}}') | Select-Object -First 1)"
$meta += "image: $Image"
$meta += "host cpu: $((Get-CimInstance Win32_Processor | Select-Object -First 1).Name)"
$meta += "host logical cpus: $((Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors)"
$meta += "host ram gb: $([math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1))"
$meta += "container flags: --innodb-buffer-pool-size=2G --innodb-flush-log-at-trx-commit=1 --skip-log-bin"
$meta += ""
$meta += (Invoke-SqlStatement "SELECT VERSION() AS version, @@innodb_buffer_pool_size / 1024 / 1024 AS pool_mb, @@innodb_flush_log_at_trx_commit AS flush_log")
$meta | Out-File -FilePath (Join-Path $outDir 'meta.txt') -Encoding ascii

Write-Host "== schema =="
Invoke-Sql 'schema.sql' 'schema.txt' | Out-Null

Write-Host "== seed (1M deliveries + 200k write rows; takes a few minutes) =="
$seed = Invoke-Sql 'seed.sql' 'seed.txt'
$seed | Out-Host

Invoke-Sql 'bench-procedures.sql' 'procedures.txt' | Out-Null

function Run-Arm([string]$label, [int]$insertStartEvent) {
    Write-Host "== arm: $label =="
    $lines = @()
    $lines += "arm: $label"
    $lines += ""

    $lines += "--- settlement chart, 24h / 15m buckets, platform-wide (3 runs) ---"
    foreach ($run in 1..3) {
        $lines += Invoke-SqlStatement "CALL bench_chart(1);"
    }

    $lines += ""
    $lines += "--- settle: 50k point updates in one transaction (3 runs, fresh DEAD rows each) ---"
    foreach ($run in 1..3) {
        Invoke-Sql 'reset-write.sql' $null | Out-Null
        $lines += Invoke-SqlStatement "CALL bench_settle(1, 50000);"
    }

    $lines += ""
    $lines += "--- ingest: 100k single-row inserts in one transaction ---"
    Invoke-Sql 'reset-write.sql' $null | Out-Null
    $lines += Invoke-SqlStatement "CALL bench_insert($insertStartEvent, 100000);"

    $lines | Out-File -FilePath (Join-Path $outDir "$label.txt") -Encoding ascii
}

# The fresh schema has no V4 index yet, so "without-index" is simply the state
# after seeding. index-drop.sql exists to reproduce the reverse order.
Run-Arm 'without-index' 150001
Invoke-Sql 'explain.sql' 'explain-without-index.txt' | Out-Null

Invoke-Sql 'index-add.sql' 'index-add.txt' | Out-Null
Run-Arm 'with-index' 250001
Invoke-Sql 'explain.sql' 'explain-with-index.txt' | Out-Null

Write-Host "done. Raw output in $outDir"
