#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
command -v java >/dev/null || { echo '请先安装 Java 11 或以上版本'; exit 1; }
if [[ -z "${DATALENS_DB_USERNAME:-}" ]]; then
  read -r -p 'MySQL 账号：' DATALENS_DB_USERNAME
fi
if [[ -z "${DATALENS_DB_PASSWORD:-}" ]]; then
  read -r -s -p 'MySQL 密码（隐藏输入）：' DATALENS_DB_PASSWORD
  echo
fi
export DATALENS_DB_USERNAME DATALENS_DB_PASSWORD
if [[ ! -f target/datalens-0.1.0.jar ]]; then
  echo '请先在 DataLens-backend 目录执行 mvn package。'
  exit 1
fi
exec java -jar target/datalens-0.1.0.jar
