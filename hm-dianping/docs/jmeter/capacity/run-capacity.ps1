param(
    [string]$JmeterRoot = 'F:\hmdpcode\apache-jmeter-5.6.3\apache-jmeter-5.6.3',
    [string]$Scenario = 'redis-get',
    [int[]]$Clients = @(8,32,128),
    [int]$Duration = 25,
    [int]$Warmup = 5,
    [int]$Batch = 256,
    [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
$taskModule = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if (!$OutputRoot) { $OutputRoot = Join-Path $taskModule ('target/capacity/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
[void][IO.Directory]::CreateDirectory($OutputRoot)
$taskBuild = Join-Path $taskModule 'target/capacity/sampler'
[void][IO.Directory]::CreateDirectory((Join-Path $taskBuild 'classes'))
$taskRuntimeFile = Join-Path $taskModule 'target/jdbc-connection-check/runtime-classpath.txt'
if (!(Test-Path -LiteralPath $taskRuntimeFile)) { throw 'Generate the runtime classpath with Maven dependency:build-classpath first.' }
$taskRuntime = (Get-Content -LiteralPath $taskRuntimeFile -Raw).Trim().Split(';')
$taskDriver = @($taskRuntime | Where-Object { $_ -match '[\\/]com[\\/]mysql[\\/]mysql-connector-j[\\/]' })[0]
$taskRabbit = @($taskRuntime | Where-Object { $_ -match '[\\/]com[\\/]rabbitmq[\\/]amqp-client[\\/]' })[0]
$taskJmeterJars = @(Get-ChildItem -LiteralPath (Join-Path $JmeterRoot 'lib') -Filter '*.jar' | Select-Object -ExpandProperty FullName) + @(Get-ChildItem -LiteralPath (Join-Path $JmeterRoot 'lib/ext') -Filter '*.jar' | Select-Object -ExpandProperty FullName)
$taskCompileClasspath = (@($taskJmeterJars) + @($taskRabbit)) -join ';'
& javac --release 8 -proc:none -Xlint:-options -cp $taskCompileClasspath -d (Join-Path $taskBuild 'classes') (Join-Path $PSScriptRoot 'CapacitySampler.java') (Join-Path $PSScriptRoot 'SummarizeStage.java')
if ($LASTEXITCODE -ne 0) { throw 'Sampler compilation failed.' }
$taskSamplerJar = Join-Path $taskBuild 'capacity-sampler.jar'
& jar --create --file $taskSamplerJar -C (Join-Path $taskBuild 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'Sampler packaging failed.' }
foreach ($taskClients in $Clients) {
    $taskStageName = $Scenario + '-' + $taskClients + '-' + (Get-Date -Format 'HHmmss')
    $taskStage = Join-Path $OutputRoot $taskStageName
    [void][IO.Directory]::CreateDirectory($taskStage)
    $taskRunId = ('r' + (Get-Date -Format 'yyyyMMddHHmmss') + '_' + [Guid]::NewGuid().ToString('N').Substring(0,8))
    $taskArguments = @('-n','-t',(Join-Path $PSScriptRoot 'component-capacity.jmx'),'-l',(Join-Path $taskStage 'samples.jtl'),'-j',(Join-Path $taskStage 'jmeter.log'),
        ('-Jsearch_paths='+$taskSamplerJar),('-Juser.classpath='+$taskDriver+';'+$taskRabbit),('-Jscenario='+$Scenario),('-Jthreads='+$taskClients),('-Jduration='+$Duration),('-Jbatch='+$Batch),
        ('-JrunId='+$taskRunId),('-JresultDir='+$taskStage),('-JenvFile='+(Join-Path $taskModule '.env')),('-JluaFile='+(Join-Path $taskModule 'src/main/resources/seckill.lua')),
        '-Jjmeter.save.saveservice.timestamp_format=ms','-Jjmeter.save.saveservice.response_message=true','-Jjmeter.save.saveservice.response_data=false')
    Write-Output ('START ' + $taskStageName)
    & (Join-Path $JmeterRoot 'bin/jmeter.bat') @taskArguments > (Join-Path $taskStage 'console.log') 2>&1
    if ($LASTEXITCODE -ne 0) { throw "JMeter failed: $taskStage" }
    $taskValidationPath = Join-Path $taskStage 'validation.properties'
    if (!(Test-Path -LiteralPath $taskValidationPath)) { throw "No validation file: $taskStage. Inspect jmeter.log." }
    $taskValidation = @{}
    foreach ($taskLine in Get-Content -LiteralPath $taskValidationPath) { if ($taskLine -match '^([^#!][^=]*)=(.*)$') { $taskValidation[$Matches[1]] = $Matches[2] } }
    $taskUnit = if ($Scenario.StartsWith('rabbit') -or $Scenario.EndsWith('-batch')) { $Batch } else { 1 }
    & java -cp $taskSamplerJar capacity.SummarizeStage $taskStage $Scenario $Duration $Warmup $taskUnit $taskClients
    if ($LASTEXITCODE -ne 0) { throw "Stage analysis failed: $taskStage" }
    if ($taskValidation['invariantsValid'] -ne 'true') { throw "Stage validation failed: $taskStage" }
}
Write-Output ('RESULT_ROOT ' + $OutputRoot)
