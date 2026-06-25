# Self-hosted Scalar API docs for docs.viewrr.stream.
# Build context = repo ROOT (so it can copy the canonical spec from docs/api/).
#   docker build -f docs/scalar/Dockerfile -t viewrr-docs .
# Dokploy: new Application, Dockerfile path docs/scalar/Dockerfile, context "/", domain docs.viewrr.stream.
FROM nginx:alpine
COPY docs/scalar/index.html /usr/share/nginx/html/index.html
COPY docs/api/openapi.yaml /usr/share/nginx/html/openapi.yaml
# nginx:alpine already serves /usr/share/nginx/html on :80
