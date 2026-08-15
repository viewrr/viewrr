#!/usr/bin/env bash
# Deploy the static API docs to Cloudflare Pages (project: viewrr-docs).
# Prereq: authenticate wrangler once — EITHER `bunx wrangler login` (interactive)
# OR `export CLOUDFLARE_API_TOKEN=<token with Pages:Edit>`.
set -euo pipefail
cd "$(dirname "$0")/.."
# Single source of truth: docs/api/openapi.yaml (served directly by docs/index.html).
# Regenerate the server's embedded 3.0.3 copy so /swagger stays in sync too.
python3 docs/api/gen-embedded-spec.py
# create the project once (ignore error if it already exists)
bunx wrangler pages project create viewrr-docs --production-branch main 2>/dev/null || true
bunx wrangler pages deploy docs --project-name viewrr-docs --commit-dirty=true
echo "Now map the custom domain in the Cloudflare dashboard (Pages > viewrr-docs > Custom domains): docs.<your-domain>"
