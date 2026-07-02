// #121 inc-2 worklet entry — the Hyper* surface over newline-delimited JSON-RPC on stdio (Bare).
//
// Sibling to ping.mjs, kept separate so the slice-1/2/3 announce worklet stays untouched. Same wire
// (WorkletRpc speaks it); dispatch is the shared serveRpc loop from stdio.mjs, so this file is just
// the method table wiring the Hypercore ops in core.mjs to the seam.
//
//   ping                                  -> "pong"                        (health)
//   coreOpen  { keyHex?, storageDir? }    -> { handle, keyHex, writable, length }
//   append    { handle, data }            -> { length }
//   get       { handle, seq }             -> { seq, data }   (awaits replication on a reader)
//   length    { handle }                  -> { length }
//   swarmJoin { handle, topicHex }        -> { topicHex, server, client }
//
// Run under `bare` (bare-native stdio); needs `bun install` in worklet/ for the Hyper* deps.
import { serveRpc } from './stdio.mjs'
import { openCore, append, get, length, swarmJoin } from './core.mjs'

serveRpc({
  ping: () => 'pong',
  coreOpen: (params) => openCore(params ?? {}),
  append,
  get,
  length,
  swarmJoin,
})
