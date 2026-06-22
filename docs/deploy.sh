#!/usr/bin/env bash
# Deploy the static API docs to Cloudflare Pages (project: viewrr-docs).
# Prereq: authenticate wrangler once — EITHER `bunx wrangler login` (interactive)
# OR `export CLOUDFLARE_API_TOKEN=<token with Pages:Edit>`.
set -euo pipefail
cd "$(dirname "$0")/.."
# keep the published spec in sync with the source of truth
cp server/src/main/resources/openapi/documentation.yaml docs/openapi.yaml
# create the project once (ignore error if it already exists)
bunx wrangler pages project create viewrr-docs --production-branch main 2>/dev/null || true
bunx wrangler pages deploy docs --project-name viewrr-docs --commit-dirty=true
echo "Now map the custom domain in the Cloudflare dashboard (Pages > viewrr-docs > Custom domains): docs.<your-domain>"
