// #121 slice 2: golden-vector regenerator. Prints the {seed, publicKey, signature} fixture pinned
// in WorkletIdentityParityTest.kt. Run to reproduce / rotate the golden:
//   bare worklet/derive.mjs [seedHex]     (default: 32 zero bytes)
// Requires `bun install` in worklet/ first. ponytail: bare-native like the rest of the worklet —
// argv comes from the shared stdio shim (Bare.argv), not Node's `process` global, so run under bare.
import { deriveIdentity, REGISTER_MESSAGE } from './identity.mjs'
import { argv } from './stdio.mjs'

const seedHex = (argv[2] ?? '00'.repeat(32)).toLowerCase()
const id = deriveIdentity(seedHex)
console.log(JSON.stringify({ seedHex, message: REGISTER_MESSAGE, ...id }, null, 2))
