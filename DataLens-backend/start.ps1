$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if($env:JAVA_HOME){$env:PATH="$env:JAVA_HOME/bin;"+$env:PATH}
foreach($name in @('DATALENS_DB_USERNAME','DATALENS_DB_PASSWORD')){if(-not [Environment]::GetEnvironmentVariable($name,'Process')){[Environment]::SetEnvironmentVariable($name,[Environment]::GetEnvironmentVariable($name,'User'),'Process')}}
mvn spring-boot:run
exit $LASTEXITCODE
