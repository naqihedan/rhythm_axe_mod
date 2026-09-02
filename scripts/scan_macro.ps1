param([string]$Root)
Get-ChildItem $Root -Recurse -Filter *.mcfunction | ForEach-Object {
    $text = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    $lines = $text -split "`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $l = $lines[$i].Trim()
        # 含宏变量但行首没有 $ 且上一行不是续行结尾
        if ($l.Contains('$(') -and -not $l.StartsWith('$') -and $i -gt 0 -and -not $lines[$i-1].Trim().EndsWith('\')) {
            Write-Output ($_.Name + "  L" + ($i + 1) + ": " + $l.Substring(0, [Math]::Min(70, $l.Length)))
        }
    }
}
Write-Output '--- scan done ---'
