// #121 slice 2: golden-vector regenerator. Prints the {seed, publicKey, signature} fixture pinned
// in WorkletIdentityParityTest.kt. Run to reproduce / rotate the golden:
//   node worklet/derive.mjs [seedHex]     (default: 32 zero bytes)
// Requires `npm i` in worklet/ first. Also runnable under `bare`.
import { deriveIdentity, REGISTER_MESSAGE } from './identity.mjs'

const seedHex = (process.argv[2] ?? '00'.repeat(32)).toLowerCase()
const id = deriveIdentity(seedHex)
console.log(JSON.stringify({ seedHex, message: REGISTER_MESSAGE, ...id }, null, 2))
