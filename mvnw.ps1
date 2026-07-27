# Kangaroo build wrapper.
# Pins the exact JDK 26 build this project is developed and verified against, so that a stray
# JAVA_HOME on the machine cannot silently build the project against an older release.
param([Parameter(ValueFromRemainingArguments = $true)] $Args)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$toolchain = Join-Path (Split-Path -Parent $root) '.toolchain'

$jdk = Join-Path $toolchain 'jdk-26.0.1+8'
$mvn = Join-Path $toolchain 'apache-maven-3.9.11\bin\mvn.cmd'

if (-not (Test-Path $jdk)) { throw "JDK 26 not found at $jdk. See README 'Building'." }
if (-not (Test-Path $mvn)) { throw "Maven not found at $mvn. See README 'Building'." }

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path

& $mvn @Args
exit $LASTEXITCODE
