# viewrr — Context Glossary

Canonical terms for the viewrr domain. Glossary only — no implementation detail.

## Clients

### Mobile client
viewrr's Android + iOS app, a fork of **AFinity** migrated to **Compose
Multiplatform** (one Kotlin codebase for both). Plays via libmpv. Talks to the
viewrr API (not the Jellyfin API the original fork used).

### Web client
A first-party **Vue 3** app for browser and TV (10-foot) surfaces, styled to the
**Apple TV** design language, built with Tailwind CSS + Tailwind Plus.

### Apple TV design language
The agreed visual direction for every viewrr client surface: content shelves, a
large hero, and focus-driven 10-foot navigation on TV. Not the tvOS platform —
the design vocabulary, applied across web, TV, and mobile.

## Roles

### Hub
The central viewrr server. Holds the database, performs HLS transcoding, and
serves streams to playback clients. Formerly called "master" in early notes.

### Node
A machine that stores raw media files as-is and runs a viewrr **agent** so the
Hub can discover and retrieve those files. Many Nodes can exist (a NAS, a
desktop, etc.). Formerly called "slave" / "the client on the NAS". A Node owns
the original bytes; it does not transcode.

### Agent
The viewrr software installed on a Node. Registers the Node's media with the Hub
and ships raw bytes to the Hub on demand. Distinct from a playback client.
An Agent is **stateless** — it owns no database; it reads its own filesystem on
command and reports to the Hub, which holds all state.

### Playback client
The app a person watches with — Nuvio / any Stremio-protocol client. Consumes
HLS streams from the Hub. Already exists in viewrr; unchanged by the
Hub/Node split.

## Concepts

### Title
A logical work — one movie, one episode, one track. Carries metadata (tmdbId,
poster, show/season/episode, etc.) and is what the catalog lists. A Title may
have many physical Copies. Distinct from the bytes on disk.

### Copy
A physical media file on a specific Node: `(node, path, size, codecs)`. One
Title can have several Copies spread across Nodes. The Hub fetches bytes from an
*online* Copy when serving. Matched to its Title by tmdbId (movie/TV), tags /
MusicBrainz (music), or content-hash fallback.

### Node status
A Node is `online` or `offline`, tracked by heartbeat. A Title with zero online
Copies is shown in the catalog but **disabled** (visible, not playable) — it
never blocks the client; the Hub looks for the Title on other Nodes and, failing
that, triggers re-acquisition.

### Downloader
The online box (Hub or any Node) with the fastest download link, chosen per
acquisition to fetch a torrent quickly. Transient — it holds the bytes only until
handoff, then deletes its copy.

### Owner
The triggering node/Hub — where an acquired Copy permanently lives. The Downloader
copies the finished file to the Owner (seedbox pattern: fast box fetches, home box
keeps). For auto re-acquisition the trigger is the Hub, so the Hub is the Owner.

### Seeding handoff
After download, the file (plus its torrent metadata) moves to the seeding box,
which force-rechecks and seeds to a 1:1 ratio. The source copy is removed only
after the seeder is verified-complete and announcing, so the swarm never loses
its last seed.

### Capability profile
A declaration of what a given playback device needs: the codecs/containers it
can decode, and its resolution / bitrate ceiling. The Hub transcodes a title to
match the requesting device's profile rather than to a fixed maximum. Carried on
a per-device key (see below).

### Per-device key
The Stremio install key, scoped to a single playback device (not just a user).
Each device's key carries its capability profile, giving the Hub a channel the
Stremio protocol itself lacks. Resolving a key yields both the user (for
parental scoping) and the profile (for transcode targeting).

### Direct-play
Serving a title to a device that can already decode the source codec/container,
with only a remux (no re-encode). Lowest Hub CPU. Whether direct-play applies is
decided by the device's capability profile.

### Prefetch
When a device starts playing an item, the Hub speculatively warm-transcodes the
*next* item (next episode for a show, next track in an album) to the same
capability profile, so playback continues without a cold-start wait. Movies have
no successor and are not prefetched.

### Infra plane
The private overlay mesh (Headscale / WireGuard) connecting Hub and Nodes.
Carries control, heartbeat, the Hub's raw fetch of transcode source bytes, and
Node↔Node seeding handoff. Reachable only by Hub and Nodes — never by playback
clients. Each box has a **mesh address** here.

### Client plane
How playback clients reach media without joining the mesh (a TV can't run a VPN).
Each Node also has a **client-facing address** (its LAN IP) used by same-LAN
clients; off-LAN clients fall back to the Hub's public address. Distinct from the
infra plane.

### Locality serving
Choosing the source nearest the playback client. The Hub compares the client's
egress IP to each Node's egress IP; a match means the client shares a LAN with
that Node, so eligible media is served from the Node's client-facing address
(LAN speed, no Hub round-trip) instead of from the Hub.

### Acquisition
Obtaining media viewrr does not yet hold. The arr apps (Radarr/Sonarr/Lidarr/
Readarr/Whisparr) handle search, monitoring and quality; Prowlarr aggregates
indexers. viewrr **orchestrates** them — it never reimplements their logic. A
grabbed release is handed to viewrr via a **blackhole** (arr writes the
torrent/magnet to a watched dir); from there viewrr owns the download (fastest
Downloader → seedbox handoff to Owner → Copy registration). Distinct from
Serving (delivering media already held). viewrr *serves* video (movies, TV, adult)
and audio (music, audiobooks via the music path); books are **acquire-only** —
downloaded into a watched dir for a dedicated reader app, never rendered by viewrr.

## Flow

A playback client asks the Hub for a title → the Hub retrieves the raw file
from the Node that holds it → the Hub transcodes to HLS → the Hub serves the
stream to the playback client.
