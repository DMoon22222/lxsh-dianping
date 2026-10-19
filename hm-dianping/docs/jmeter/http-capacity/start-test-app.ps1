param(
    [Parameter(Mandatory=$true)][string]$RunDirectory,
    [ValidateSet('true','false')][string]$RateLimit='false'
)
$ErrorActionPreference='Stop'
$taskModule=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskSession=Get-Content -LiteralPath (Join-Path $RunDirectory 'session.json') -Raw | ConvertFrom-Json
foreach($taskLine in Get-Content -LiteralPath (Join-Path $taskModule '.env')) {
    if($taskLine -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim([char]34).Trim([char]39),'Process')
    }
}
$env:HMDP_DATASOURCE_URL=$taskSession.isolatedUrl
$env:HMDP_RABBITMQ_VIRTUAL_HOST=$taskSession.vhost
$taskCp=(Join-Path $taskModule 'target/classes')+';'+(Get-Content -LiteralPath (Join-Path $taskModule 'target/jdbc-connection-check/runtime-classpath.txt') -Raw).Trim()
$taskArguments=@('-Xms256m','-Xmx1024m','-cp',('"'+$taskCp+'"'),'com.hmdp.HmDianPingApplication',
    '--server.port=8082',('--spring.redis.database='+$taskSession.redisDb),
    ('--hmdp.instance-id=http-'+$taskSession.runId),'--hmdp.id-generator.worker-id=2',
    ('--hmdp.rate-limit.enabled='+$RateLimit))
$taskLog=Join-Path $RunDirectory ('application-limit-'+$RateLimit+'.log')
$taskErrorLog=Join-Path $RunDirectory ('application-limit-'+$RateLimit+'.error.log')
$taskProcess=Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $taskArguments -WorkingDirectory $taskModule -WindowStyle Hidden -RedirectStandardOutput $taskLog -RedirectStandardError $taskErrorLog -PassThru
[pscustomobject]@{pid=$taskProcess.Id;rateLimit=$RateLimit;port=8082;startTime=$taskProcess.StartTime.ToUniversalTime().ToString('o');log=$taskLog} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDirectory 'application-process.json') -Encoding utf8
Write-Output ('TEST_APP_STARTED pid='+$taskProcess.Id+' rateLimit='+$RateLimit+' port=8082')
$taskProcess.WaitForExit()
Write-Output ('TEST_APP_EXIT code='+$taskProcess.ExitCode)
