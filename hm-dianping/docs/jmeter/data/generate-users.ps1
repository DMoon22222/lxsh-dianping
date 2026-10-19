[CmdletBinding()]
param(
    [ValidateRange(1, 10000000)]
    [int]$Count = 100000,

    [ValidateRange(0, 9999999)]
    [int]$StartIndex = 0,

    [string]$OutputFile
)

$ErrorActionPreference = 'Stop'

if ([long]$StartIndex + $Count -gt 10000000) {
    throw 'User indices must fit the existing seven-digit phone suffix.'
}

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $PSScriptRoot ("users-{0}.csv" -f $Count)
}

$taskOutputPath = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $taskOutputPath) {
    throw "Output already exists and was preserved: $taskOutputPath"
}
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($taskOutputPath))

$taskStream = [IO.File]::Open(
    $taskOutputPath,
    [IO.FileMode]::CreateNew,
    [IO.FileAccess]::Write,
    [IO.FileShare]::None
)
$taskWriter = [IO.StreamWriter]::new($taskStream, [Text.UTF8Encoding]::new($false), 65536)
try {
    $taskWriter.WriteLine('"phone","nickName","icon"')
    for ($taskOffset = 0; $taskOffset -lt $Count; $taskOffset++) {
        $taskIndex = $StartIndex + $taskOffset
        $taskPhone = '1399' + $taskIndex.ToString('D7', [Globalization.CultureInfo]::InvariantCulture)
        $taskWriter.WriteLine(('"{0}","load-test-{1}",""' -f $taskPhone, $taskIndex))
    }
} finally {
    $taskWriter.Dispose()
}

[pscustomobject]@{
    file = $taskOutputPath
    records = $Count
    firstPhone = '1399' + $StartIndex.ToString('D7', [Globalization.CultureInfo]::InvariantCulture)
    lastPhone = '1399' + ($StartIndex + $Count - 1).ToString('D7', [Globalization.CultureInfo]::InvariantCulture)
    encoding = 'UTF-8 without BOM'
} | ConvertTo-Json -Compress
