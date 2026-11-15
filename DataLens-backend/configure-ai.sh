#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
echo '仅在当前进程中配置，不写入文件。请使用新建的凭据，不要粘贴到聊天或源码。'
read -r -s -p '腾讯云 SecretId（隐藏输入）：' DATALENS_TENCENT_SECRET_ID
echo
read -r -s -p '腾讯云 SecretKey（隐藏输入）：' DATALENS_TENCENT_SECRET_KEY
echo
read -r -s -p 'DeepSeek API Key（隐藏输入）：' DATALENS_DEEPSEEK_API_KEY
echo
export DATALENS_TENCENT_SECRET_ID DATALENS_TENCENT_SECRET_KEY DATALENS_DEEPSEEK_API_KEY
exec ./start.sh
