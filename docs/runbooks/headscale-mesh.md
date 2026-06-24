# Runbook — Headscale mesh (Phase 14 #70)

The **infra plane**: a private WireGuard overlay joining the Hub and every Node.
Carries control, heartbeat, the Hub's raw-fetch of transcode source bytes, and
Node↔Node seeding handoff. **Playback clients never join it** (see the
[Network](https://github.com/viewrr/viewrr/wiki/Network) wiki page).

This is an ops task — deploy + join, no viewrr code. viewrr only *consumes* the
resulting addresses: each Agent reports its mesh address at register
(`AGENT_MESH_ADDR`), and its client-facing LAN address (`AGENT_CLIENT_ADDR`) for
the client plane.

## 1. Headscale control server (on the Hub box, or any public host)

```sh
# container is simplest; pin a version in real use
docker run -d --name headscale -p 8085:8080 \
  -v ./headscale:/etc/headscale \
  headscale/headscale:latest headscale serve
# create a user/namespace for this deployment
docker exec headscale headscale users create viewrr
```

Config (`/etc/headscale/config.yaml`): set `server_url` to the Hub's public
address, keep the default `100.64.0.0/10` IP range (the mesh addresses).

## 2. Join the Hub and each Node (Tailscale client against Headscale)

```sh
# on every box (Hub + each Node)
tailscale up --login-server https://<hub-public>:8085
# approve the node key:
docker exec headscale headscale --user viewrr nodes register --key <nodekey>
# or pre-auth: headscale --user viewrr preauthkeys create --reusable --expiration 24h
```

Each box now has a stable `100.64.x.y` mesh IP (`tailscale ip -4`).

## 3. Point viewrr at the mesh

- **Agent** (`.env` on each Node): `AGENT_MESH_ADDR=100.64.x.y:8090` (the Node's
  mesh IP + the port its raw endpoint will listen on, #75), and
  `AGENT_CLIENT_ADDR=<lan-ip>:8090` for same-LAN clients.
- **Hub**: reaches a Node at its registered `mesh_address`; reaches clients/serves
  HLS at `PUBLIC_BASE_URL`.

## Verify

```sh
tailscale status                       # all boxes Connected
# from the Hub, the Node's raw port is reachable over the mesh (once #75 lands):
curl -sf http://100.64.x.y:8090/health
```

## Notes / ceilings

- TLS deferred on the mesh (WireGuard already encrypts the tunnel); the per-node
  token (#73) still guards the raw endpoint at the app layer.
- CGNAT home Nodes work — WireGuard does NAT traversal; that's the whole point of
  the mesh vs. port-forwarding.
- Nebula is the alternative (lighthouse + cert model); chosen against for ops
  weight — see [ADR / Network wiki](https://github.com/viewrr/viewrr/wiki/Network).
