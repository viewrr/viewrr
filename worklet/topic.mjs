// #121 slice 3 (P2P-ADR 0008): swarm topic derivation for content-addressed availability.
//
// swarmTopic(contentUuid) = hypercore-crypto.hash(uuidBytes) → 32-byte Hyperswarm topic. A node
// holding a copy joins hash(content_uuid) as a provider; a peer wanting it joins the same topic.
// Purely deterministic, no coordination — the P2P analogue of a BitTorrent infohash swarm.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web reproduce byte-for-byte):
//   uuidBytes = the 16 raw bytes of the content_uuid (dashes stripped, hex-decoded)
//   topic     = hypercore-crypto hash(uuidBytes)   (32 bytes, lowercase hex)
// GOLDEN: content_uuid "bc592db3-805a-58ff-9f95-b90687681997"
//      -> topic        "a4f704e6350a26c2910080d281a6097163a86aa7f19f4921fc10f7bac0df1643"
import crypto from 'hypercore-crypto'

/** contentUuidHex: a content_uuid with or without dashes. Returns the 32-byte topic as hex. */
export function swarmTopic(contentUuidHex) {
  const hex = contentUuidHex.replace(/-/g, '').toLowerCase()
  const bytes = Buffer.from(hex, 'hex')
  if (bytes.length !== 16) throw new Error(`content_uuid must be 16 bytes, got ${bytes.length}`)
  return crypto.hash(bytes).toString('hex')
}
