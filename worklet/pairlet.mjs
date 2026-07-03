// #126 worklet entry — the private-topic + Vault-Link pairing surface over newline-delimited
// JSON-RPC on stdio (Bare). Sibling to hyperlet.mjs; kept separate so the inc-2 Hypercore worklet
// stays untouched. Same wire (Kotlin WorkletRpc speaks it); dispatch is the shared serveRpc loop.
//
//   ping                                       -> "pong"                     (health)
//   deriveTopic     { secretHex, label }       -> { topicHex }              (deterministic; no swarm)
//   vaultLinkEncode { pairingSecretHex }       -> { qr }                    (pure; QR wire format)
//   vaultLinkDecode { qr }                     -> { pairingSecretHex, topicHex }  (pure; verified)
//   pairBegin       { vaultHex }               -> { pairingSecretHex, topicHex, qr }  (host)
//   pairFinish                                 -> { delivered }             (host; awaits + teardown)
//   pairJoin        { pairingSecretHex | qr }  -> { vaultHex }              (new device; + teardown)
//   pairAbort                                  -> { aborted }              (force teardown)
//
// Run under `bare` (bare-native stdio); needs `bun install` in worklet/ for the Hyper*/sodium deps.
import { serveRpc } from './stdio.mjs'
import {
  deriveTopic,
  vaultLinkEncode,
  vaultLinkDecode,
  pairBegin,
  pairFinish,
  pairJoin,
  pairAbort,
} from './pairing.mjs'

serveRpc({
  ping: () => 'pong',
  deriveTopic,
  vaultLinkEncode,
  vaultLinkDecode,
  pairBegin,
  pairFinish,
  pairJoin,
  pairAbort,
})
