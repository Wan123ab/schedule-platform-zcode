#!/usr/bin/env bash
# 本地一键起依赖（docs/09 M0 交付物：本地开发脚本）
# 用法：仅起 PG + Redis 供 IDE 直连调试；全栈验证用 deploy/docker-compose.yml
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> 启动 PostgreSQL 16 + Redis 7（docker）"
docker run -d --name flowops-pg -p 5432:5432 \
  -e POSTGRES_DB=flowops -e POSTGRES_USER=flowops -e POSTGRES_PASSWORD=flowops \
  postgres:16-alpine 2>/dev/null || echo "flowops-pg 已在运行"

docker run -d --name flowops-redis -p 6379:6379 \
  redis:7-alpine --maxmemory-policy noeviction 2>/dev/null || echo "flowops-redis 已在运行"

echo "==> 就绪："
echo "  PostgreSQL jdbc:postgresql://localhost:5432/flowops (flowops/flowops)"
echo "  Redis      redis://localhost:6379"
echo "启动 flowops-server 前请设置环境变量 FLOWOPS_CRED_MASTER_KEY（M-06）"
