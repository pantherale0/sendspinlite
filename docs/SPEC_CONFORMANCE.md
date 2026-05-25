# SendSpin Spec Conformance Checklist

This checklist tracks conformance of this repository against the SendSpin spec.

- Spec URL: [https://github.com/sendspin/spec](https://github.com/sendspin/spec)
- Spec source doc: [https://raw.githubusercontent.com/Sendspin/spec/main/README.md](https://raw.githubusercontent.com/Sendspin/spec/main/README.md)
- SPEC_REVISION: `5e2c7bc2e17434cbf484a6bcc891e62419ed003d` (main branch head at review time)
- Product scope: player-only Android client, client-initiated connection mode
- Out of scope by design: server-initiated connection mode, non-player roles

## Status Legend

- Done: Implemented and aligned in current scope
- Partial: Implemented, but not fully aligned or has caveats
- Gap: Not implemented but needed for full conformance in scope
- N/A: Not applicable for this product scope

## Connection Establishment

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Client-initiated mDNS discovery (`_sendspin-server._tcp`) | Done | [app/src/main/java/com/sendspinlite/ServiceDiscovery.kt](../app/src/main/java/com/sendspinlite/ServiceDiscovery.kt) (`startDiscovery`) | Confirm discovery starts with `_sendspin-server._tcp.` and resolves host/port/path |
| Build WebSocket URL from TXT `path` with `/sendspin` fallback | Done | [app/src/main/java/com/sendspinlite/ServiceDiscovery.kt](../app/src/main/java/com/sendspinlite/ServiceDiscovery.kt) (`onServiceResolved`) | Confirm TXT path handling and URL assembly |
| Server-initiated mode (`_sendspin._tcp`, client listener) | N/A | [README.md](../README.md) (player-only, client-initiated behavior) | N/A in current product scope |
| Multiple-server server-initiated decision logic (`connection_reason`, last played `server_id`) | N/A | No implementation in player-only client-initiated flow | N/A in current product scope |

## Communication

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| JSON envelope with `type` and `payload` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendJson`) | Inspect emitted JSON frames in logs |
| Handshake order (`client/hello` first, then wait for `server/hello`) | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`onOpen`, `handleText`) | Confirm no other messages before `server/hello` branch starts loops |
| Binary message role IDs: player audio type `4` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleBinary`) | Validate type switch handles `4` and parses timestamp+payload |
| Reject binary if no active stream | Partial | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`streamEnded`, `handleBinary`) | Verify runtime behavior after `stream/end`; add explicit active-stream guard if needed |

## Clock Synchronization

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Send `client/time` with `client_transmitted` (microseconds) | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`startTimeSyncLoop`) | Confirm periodic `client/time` payload content |
| Consume `server/time` with 4 timestamps (`T1/T2/T3/T4`) | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleText` `server/time` case) | Confirm `clientRx` captured locally and passed into sync |
| Time filter / Kalman-based offset+drift estimation | Done | [app/src/main/java/com/sendspinlite/ClockSync.kt](../app/src/main/java/com/sendspinlite/ClockSync.kt) (`onServerTime`, `update`, `convertServerToClient`) | Validate convergence and conversion under network jitter |

## Playback Synchronization

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Drop late chunks to preserve sync | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (playout loop, catch-up/drop logic) | Simulate delayed packets and observe drops with continued sync |
| Use `static_delay_ms` in scheduling | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`totalPlayoutOffsetUs` includes `- staticDelayUs`) | Verify playout timing shifts when static delay changes |
| Report `state: synchronized` and `state: error` appropriately | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendClientStateSynchronized`, `sendClientStateError`) | Force underrun/health failure and inspect outbound state |

## Core Messages

| Message | Status | Evidence | Verification |
|---|---|---|---|
| `client/hello` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendClientHello`) | Validate required fields: `client_id`, `name`, `version`, `supported_roles` |
| `server/hello` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleText`) | Verify handshake completion and loop startup only here |
| `client/time` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`startTimeSyncLoop`) | Confirm interval adapts and payload stays in microseconds |
| `server/time` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleText`) | Confirm timestamps are forwarded to `ClockSync` |
| `client/state` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendClientStateSynchronized`, `sendClientStatePlayer`) | Confirm initial full state after `server/hello`, then delta updates |
| `server/state` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleText`) | Validate merge-style UI state updates |
| `server/command` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`server/command` branch) | Verify `volume`, `mute`, `set_static_delay` handling |
| `stream/start` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`stream/start` branch) | Confirm stream params update and buffer reset behavior |
| `stream/clear` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`stream/clear` branch) | Confirm jitter/decoder reset on clear |
| `stream/end` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`stream/end` branch) | Confirm playback pause and queue clear |
| `group/update` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`group/update` branch) | Confirm playback/group info updates |
| `client/command` | N/A | Player role only (no `controller@v1` advertised) | N/A in current scope |
| `stream/request-format` | Gap | No implementation found in current repo | Add message builder/sender and trigger policy if needed |
| `client/goodbye` | Partial | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendClientGoodbye`), [app/src/main/java/com/sendspinlite/SendspinService.kt](../app/src/main/java/com/sendspinlite/SendspinService.kt) (`disconnect`, `recoverService`) | Align reason values to spec enum (`another_server`, `shutdown`, `restart`, `user_request`) |

## Player Messages

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Advertise `player@v1_support` | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`buildPlayerSupportObject`, `sendClientHello`) | Confirm support object appears in `client/hello` |
| Provide `supported_formats` with codec/rate/channels/bit depth | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`buildPlayerSupportObject`) | Verify PCM entries and optional Opus entries |
| Include `buffer_capacity` and `supported_commands` (`volume`, `mute`) | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`buildPlayerSupportObject`) | Validate values in emitted `client/hello` |
| Send player state with volume/mute/static delay | Partial | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`sendClientStateSynchronized`, `sendClientStatePlayer`) | Initial message includes all fields; delta updates may omit `static_delay_ms` |
| Receive player commands (`volume`, `mute`, `set_static_delay`) | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`server/command` branch) | Confirm local apply + state echo |
| Parse binary audio frame type `4` with 8-byte BE timestamp | Done | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`handleBinary`, `readInt64BE`) | Validate parser with known test frame |
| FLAC decoding support | N/A | [app/build.gradle.kts](../app/build.gradle.kts) (comment indicates FLAC planned later) | N/A for current player capability advertisement |

## Other Roles (Controller, Metadata, Artwork, Visualizer, Color)

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Advertise non-player roles | N/A | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`supported_roles` only includes `player@v1`) | N/A in product scope |
| Controller command send (`client/command`) | N/A | No controller role support advertised | N/A in product scope |
| Artwork/visualizer binary handling | N/A | No role support advertised; binary handler only processes type `4` | N/A in product scope |
| Metadata/controller state parsing for UI | Done (best-effort) | [app/src/main/java/com/sendspinlite/SendspinPcmClient.kt](../app/src/main/java/com/sendspinlite/SendspinPcmClient.kt) (`server/state` parsing) | Confirm tolerant parsing does not imply role conformance claims |

## External Source Handling

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| `client/state` with `state: external_source` | Gap | No outbound `external_source` state found in current repo | Implement only if product needs external source takeover behavior |

## Known Action Items

1. Normalize all `client/goodbye` reasons to the spec enum.
2. Add explicit active-stream guard for binary frame acceptance after `stream/end`.
3. Implement `stream/request-format` if adaptive renegotiation is required.
4. Decide whether `external_source` is in product scope; if yes, implement corresponding state transitions.

## Review Procedure

When updating this checklist:

1. Pin a new `SPEC_REVISION` commit SHA from `sendspin/spec`.
2. Re-check each row against current implementation.
3. Update status and evidence links in the same PR as behavior changes.
