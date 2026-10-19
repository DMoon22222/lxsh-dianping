param([Parameter(Mandatory=$true)][string]$RunRoot)
$ErrorActionPreference = 'Stop'
$moduleRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$runPath = (Resolve-Path -LiteralPath $RunRoot).Path
$expectedParent = [IO.Path]::GetFullPath((Join-Path $moduleRoot 'target/http-capacity')) + [IO.Path]::DirectorySeparatorChar
if (-not $runPath.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase)) { throw 'Run directory must be under target/http-capacity' }
$sessionInfo = Get-Content -Raw -LiteralPath (Join-Path $runPath 'session.json') | ConvertFrom-Json
if ($sessionInfo.schema -notmatch '^hmdp_http_r[0-9]+_[a-f0-9]{8}$' -or $sessionInfo.vhost -ne $sessionInfo.schema -or $sessionInfo.schema -ne ('hmdp_http_' + $sessionInfo.runId) -or $sessionInfo.redisDb -le 0 -or -not $sessionInfo.redisWasEmpty) { throw 'Isolation ownership check failed' }
$stageDirectories = Get-ChildItem -LiteralPath $runPath -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'stage.json') }
foreach ($stage in $stageDirectories) {
    $summaryPath = Join-Path $stage.FullName 'summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath)) { throw "Incomplete stage: $($stage.Name)" }
    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    if (-not $summary.invariantsValid) { throw "Unresolved order/inventory check: $($stage.Name)" }
}
$rawProcess = Get-Content -Raw -LiteralPath (Join-Path $runPath 'application-process.json')
$processInfo = $rawProcess | ConvertFrom-Json
$startText = [regex]::Match($rawProcess, '"startTime"\s*:\s*"([^"]+)"').Groups[1].Value
$expectedStart = [DateTimeOffset]::Parse($startText).UtcDateTime
$testProcess = Get-Process -Id $processInfo.pid -ErrorAction SilentlyContinue
if ($null -ne $testProcess) {
    if ($testProcess.ProcessName -ne 'java' -or [Math]::Abs(($testProcess.StartTime.ToUniversalTime() - $expectedStart).TotalSeconds) -ge 2) { throw 'Test application process ownership mismatch' }
    Stop-Process -Id $testProcess.Id
    $testProcess.WaitForExit(20000) | Out-Null
    if (-not $testProcess.HasExited) { throw 'Test application has not stopped' }
}
$queueLines = & docker exec hmdp-rabbitmq rabbitmqctl list_queues -p $sessionInfo.vhost name messages_ready messages_unacknowledged --no-table-headers
if ($LASTEXITCODE -ne 0) { throw 'Could not verify isolated queues' }
$parsedQueues = 0
foreach ($line in $queueLines) {
    $parts = $line.Trim() -split '\s+'
    if ($parts.Count -ge 3 -and $parts[-1] -match '^\d+$' -and $parts[-2] -match '^\d+$') {
        $parsedQueues++
        if ([long]$parts[-1] -ne 0 -or [long]$parts[-2] -ne 0) { throw "Queue not drained: $($parts[0])" }
    }
}
if ($parsedQueues -eq 0) { throw 'No isolated queues were verified' }
$runtimeClasspath = (Get-Content -Raw -LiteralPath (Join-Path $moduleRoot 'target/jdbc-connection-check/runtime-classpath.txt')).Trim()
$supportClasspath = (Join-Path $moduleRoot 'target/http-capacity/classes') + ';' + $runtimeClasspath
& java -cp $supportClasspath HttpCapacitySupport cleanup (Join-Path $moduleRoot '.env') $runPath
if ($LASTEXITCODE -ne 0) { throw 'Database/Redis cleanup failed' }
& docker exec hmdp-rabbitmq rabbitmqctl delete_vhost $sessionInfo.vhost
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ vhost cleanup failed' }
& java -cp $supportClasspath HttpCapacitySupport verify-cleanup (Join-Path $moduleRoot '.env') $runPath
if ($LASTEXITCODE -ne 0) { throw 'Cleanup verification failed' }
$verificationPath = Join-Path $runPath 'cleanup-validation.json'
$verification = Get-Content -Raw -LiteralPath $verificationPath | ConvertFrom-Json
$verification | Add-Member -NotePropertyName testRabbitVhostDeleted -NotePropertyValue $true
$original = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/shop/1' -TimeoutSec 10
$verification | Add-Member -NotePropertyName originalAppHealthy -NotePropertyValue ([bool]$original.success)
$verification | Add-Member -NotePropertyName checkedStages -NotePropertyValue $stageDirectories.Count
$verification | ConvertTo-Json | Set-Content -LiteralPath $verificationPath -Encoding UTF8
if (-not $verification.originalAppHealthy -or $verification.testSchemaRemaining -ne 0 -or $verification.testRedisKeysRemaining -ne 0) { throw 'Post-cleanup verification did not pass' }
$tokenFiles = Get-ChildItem -LiteralPath $runPath -File -Recurse | Where-Object { $_.Name -eq 'tokens.csv' -or $_.Name -eq 'duplicate-tokens.csv' }
foreach ($tokenFile in $tokenFiles) {
    $resolvedToken = [IO.Path]::GetFullPath($tokenFile.FullName)
    if (-not $resolvedToken.StartsWith($runPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected token file path' }
    Remove-Item -LiteralPath $resolvedToken
}
Write-Output ('FINISHED ' + ($verification | ConvertTo-Json -Compress))
