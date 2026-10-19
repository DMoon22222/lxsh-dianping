param(
    [int]$Clients=128,
    [int]$Pipeline=1,
    [int]$Requests=200000,
    [string]$OutputFile=''
)
$ErrorActionPreference='Stop'
$taskModule=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$taskConfig=@{}
foreach($taskLine in Get-Content -LiteralPath (Join-Path $taskModule '.env')){
    if($taskLine -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$'){$taskConfig[$Matches[1]]=$Matches[2].Trim().Trim([char]34).Trim([char]39)}
}
$taskKey='capacity:crosscheck:'+ [Guid]::NewGuid().ToString('N')
$taskSocket=[Net.Sockets.TcpClient]::new()
if(!$taskSocket.ConnectAsync($taskConfig['HMDP_REDIS_HOST'],[int]$taskConfig['HMDP_REDIS_PORT']).Wait(5000)){throw 'Redis connect timeout'}
$taskStream=$taskSocket.GetStream();$taskStream.ReadTimeout=10000
$taskReader=[IO.StreamReader]::new($taskStream,[Text.Encoding]::UTF8,$false,4096,$true)
function Invoke-TaskRedisCommand([string[]]$Parts){
    $taskPacket='*'+$Parts.Count+"`r`n"
    foreach($taskPart in $Parts){$taskPacket+='$'+[Text.Encoding]::UTF8.GetByteCount($taskPart)+"`r`n"+$taskPart+"`r`n"}
    $taskBytes=[Text.Encoding]::UTF8.GetBytes($taskPacket);$taskStream.Write($taskBytes,0,$taskBytes.Length)
    $taskReply=$taskReader.ReadLine();if($taskReply.StartsWith('-')){throw $taskReply.Substring(1)};return $taskReply
}
try {
    if($taskConfig['HMDP_REDIS_PASSWORD']){[void](Invoke-TaskRedisCommand @('AUTH',$taskConfig['HMDP_REDIS_PASSWORD']))}
    [void](Invoke-TaskRedisCommand @('SET',$taskKey,('x'*1024),'EX','600'))
    $taskInfo=[Diagnostics.ProcessStartInfo]::new();$taskInfo.FileName=(Get-Command docker.exe).Source
    $taskInfo.UseShellExecute=$false;$taskInfo.CreateNoWindow=$true;$taskInfo.RedirectStandardOutput=$true;$taskInfo.RedirectStandardError=$true
    $taskInfo.Environment['REDISCLI_AUTH']=$taskConfig['HMDP_REDIS_PASSWORD']
    foreach($taskArgument in @('run','--rm','--env','REDISCLI_AUTH','redis:6.2.6-alpine','sh','-c','exec redis-benchmark -a "$REDISCLI_AUTH" "$@"','capacity-bench','-h',$taskConfig['HMDP_REDIS_HOST'],'-p',$taskConfig['HMDP_REDIS_PORT'],'-c',"$Clients",'-P',"$Pipeline",'-n',"$Requests",'-q','GET',$taskKey)){
        [void]$taskInfo.ArgumentList.Add($taskArgument)
    }
    $taskProcess=[Diagnostics.Process]::new();$taskProcess.StartInfo=$taskInfo
    Write-Output "NATIVE_REDIS_START clients=$Clients pipeline=$Pipeline requests=$Requests payload=1024"
    [void]$taskProcess.Start();$taskOut=$taskProcess.StandardOutput.ReadToEndAsync();$taskErr=$taskProcess.StandardError.ReadToEndAsync()
    $taskProcess.WaitForExit();$taskText=$taskOut.GetAwaiter().GetResult();$taskError=$taskErr.GetAwaiter().GetResult()
    if($taskConfig['HMDP_REDIS_PASSWORD']){$taskText=$taskText.Replace($taskConfig['HMDP_REDIS_PASSWORD'],'[redacted]');$taskError=$taskError.Replace($taskConfig['HMDP_REDIS_PASSWORD'],'[redacted]')}
    if($OutputFile){$taskText|Set-Content -LiteralPath $OutputFile -Encoding utf8}
    $taskFinalLines=@($taskText -split "`r?`n|`r" | Where-Object {$_ -match 'requests per second'})
    if($taskFinalLines.Count){$taskFinalLines|Select-Object -Last 2|Write-Output}else{Write-Output $taskText}
    if($taskProcess.ExitCode -ne 0){throw "redis-benchmark failed: $taskError"}
    $taskProcess.Dispose();[void]$taskInfo.Environment.Remove('REDISCLI_AUTH')
} finally {
    try{[void](Invoke-TaskRedisCommand @('UNLINK',$taskKey))}finally{$taskReader.Dispose();$taskSocket.Dispose()}
}
