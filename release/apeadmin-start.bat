@echo off
rem =============================================================
rem ApeAdmin-Spring Windows 守护启动脚本
rem 关键机制：一键更新按约定退出码 90 退出后自动重新拉起。
rem =============================================================
setlocal enabledelayedexpansion
rem 打包时由 package-release.sh 注入实际版本号
set APP_VERSION=@VERSION@
set APE_APP_VERSION=%APP_VERSION%

echo ApeAdmin-Spring v%APP_VERSION% 启动中... (退出码 90 = 更新完成自动重启)
:loop
java -Dape.app.version=%APP_VERSION% -jar apeadmin.jar
set RC=!ERRORLEVEL!
if "!RC!"=="90" (
  echo 检测到更新退出码 90，1 秒后拉起新版本...
  timeout /t 1 /nobreak >nul
  goto loop
)
echo 进程退出（code=!RC!），守护结束。
endlocal
