$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$jdk=$null
if(Test-Path "$PSScriptRoot/.runtime"){$jdk=Get-ChildItem "$PSScriptRoot/.runtime" -Directory | Select-Object -First 1}
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME/bin;"+$env:PATH}
foreach($name in @('DATALENS_DB_USERNAME','DATALENS_DB_PASSWORD')){if(-not [Environment]::GetEnvironmentVariable($name,'Process')){[Environment]::SetEnvironmentVariable($name,[Environment]::GetEnvironmentVariable($name,'User'),'Process')}}
mvn spring-boot:run
exit $LASTEXITCODE
