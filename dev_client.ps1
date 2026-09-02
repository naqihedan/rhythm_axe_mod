# 开发模式启动游戏（热更新工作流）：
# 1. 把与开发类路径重复的 jar（rhythm_axe_mod / fabric-api）临时移出 mods 文件夹
# 2. 用 gradle 启动开发客户端（游戏目录 = PCL 实例目录，存档/数据包/资源包共用）
# 3. 游戏关闭后自动把 jar 移回，恢复 PCL 正常启动
# 用法：powershell -ExecutionPolicy Bypass -File dev_client.ps1
$ErrorActionPreference = 'Continue'
$mods = 'D:\Program Files (x86)\Minecrafts\PCL 正式版 2.10.3 (1)\.minecraft\versions\节奏地图 高版本重制版\mods'
$backup = Join-Path $mods '_dev_tmp_out'
$env:JAVA_HOME = 'C:\Users\q1689\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon'
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$moved = @()
Get-ChildItem $mods -File | Where-Object { $_.Name -match '^rhythm_axe_mod|^fabric-api' } | ForEach-Object {
    Move-Item $_.FullName -Destination (Join-Path $backup $_.Name) -Force
    $moved += $_.Name
}
if ($moved.Count -gt 0) { Write-Host "[dev] 已临时移出: $($moved -join ', ')" }
Set-Location 'D:\Program Files (x86)\Minecrafts\MC资源\mod源文件\rhythm_axe_mod'
try {
    & .\gradlew.bat runClient --console=plain
} finally {
    Get-ChildItem $backup -File -ErrorAction SilentlyContinue | ForEach-Object {
        if (Test-Path (Join-Path $mods $_.Name)) {
            Remove-Item $_.FullName -Force
        } else {
            Move-Item $_.FullName -Destination (Join-Path $mods $_.Name) -Force
        }
    }
    Remove-Item $backup -Force -ErrorAction SilentlyContinue
    Write-Host '[dev] jar 已移回 mods 文件夹'
}