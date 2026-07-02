// #121 inc-2: the DETERMINISTIC replication proof (primary, no network).
//
// Two Hypercores in one process, wired over a direct in-memory duplex (replicateDirect) — NOT the
// DHT. The writer appends two blocks; a read-only replica opened on the writer's key replicates and
// reads the SAME bytes back. This asserts the core data path (append -> sign -> replicate -> verify
// -> read) with zero swarm/network flakiness, which is why it's the CI-safe assertion the Kotlin
// WorkletHypercoreSmokeTest gates on bare+deps alone. Exit 0 = proven, non-zero = failed/timed out;
// a self-timer guarantees it can never hang the JVM test that spawns it.
import { openCore, append, get, replicateDirect } from './core.mjs'
import { exit } from './stdio.mjs'

const FAIL = (msg) => {
  console.log('[hyper-selftest] FAIL: ' + msg)
  exit(1)
}

// Hard self-timeout: if replication ever wedges, exit non-zero instead of parking the event loop.
const timer = setTimeout(() => FAIL('timed out (10s) before reading replicated blocks'), 10_000)

try {
  const writer = await openCore()
  await append({ handle: writer.handle, data: 'hello' })
  await append({ handle: writer.handle, data: 'world' })

  // Read-only replica of the writer's key: no blocks yet until it replicates.
  const reader = await openCore({ keyHex: writer.keyHex })
  replicateDirect(writer.handle, reader.handle)

  const b0 = await get({ handle: reader.handle, seq: 0 })
  const b1 = await get({ handle: reader.handle, seq: 1 })

  clearTimeout(timer)
  if (b0.data !== 'hello' || b1.data !== 'world') {
    FAIL(`replica read back ${JSON.stringify([b0.data, b1.data])}, expected ["hello","world"]`)
  }
  console.log(`[hyper-selftest] REPLICATE_OK: writer key ${writer.keyHex.slice(0, 12)}… ` +
    `-> replica read "${b0.data}","${b1.data}" over a direct duplex`)
  exit(0)
} catch (e) {
  clearTimeout(timer)
  FAIL(String(e?.stack ?? e))
}
