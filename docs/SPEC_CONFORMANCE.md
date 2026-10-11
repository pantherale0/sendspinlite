# Sendspin spec conformance checklist

This is a historical checklist against the Sendspin spec revision listed below. Its statuses have not been revalidated for the current native client or the latest spec.

The [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp) submodule handles the protocol,
clock synchronization, decoding, and scheduling through the [JNI bridge](../app/src/main/cpp)
and [Kotlin wrapper](../app/src/main/java/com/sendspinlite/client/SendspinNativeClient.kt).
Some evidence rows still name methods from the removed Kotlin client. See
[Native client integration](NATIVE_CLIENT.md) for the current integration.

- Spec URL: [https://github.com/sendspin/spec](https://github.com/sendspin/spec)
- Spec source doc: [https://raw.githubusercontent.com/Sendspin/spec/main/README.md](https://raw.githubusercontent.com/Sendspin/spec/main/README.md)
- Recorded SPEC_REVISION: `5e2c7bc2e17434cbf484a6bcc891e62419ed003d`
- Scope of the historical review: player-only Android client with client-initiated connections
- Excluded from that review: server-initiated connections and non-player roles

## Review notes

The current app enables player, metadata, and controller roles. Recheck the historical N/A entries and native evidence before using this checklist as a conformance report.

The latest spec also changes the handshake, audio header, availability state, output-delay fields, and player timing requirements. A new review must cover those requirements rather than carry forward the Done statuses below.

## Status legend

- Done: The historical review recorded the requirement as implemented.
- Partial: The review found incomplete support or limitations.
- Gap: The review found a missing requirement.
- N/A: The requirement was outside that review's scope.

## Connection establishment

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Client-initiated mDNS discovery (`_sendspin-server._tcp`) | Done | [ServiceDiscovery.kt](../app/src/main/java/com/sendspinlite/network/ServiceDiscovery.kt), `startDiscovery` | Check service type and resolved host, port, and path |
| Build WebSocket URL from TXT `path` with `/sendspin` fallback | Done | [ServiceDiscovery.kt](../app/src/main/java/com/sendspinlite/network/ServiceDiscovery.kt), `onServiceResolved` | Check TXT path handling and URL assembly |
| Server-initiated mode (`_sendspin._tcp`, client listener) | N/A | [README.md](../README.md), historical client-initiated scope | Excluded from the historical review |
| Multiple-server server-initiated decision logic (`connection_reason`, last played `server_id`) | N/A | No implementation in player-only client-initiated flow | N/A in current product scope |

## Communication

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| JSON envelope with `type` and `payload` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`sendJson`) | Inspect emitted JSON frames in logs |
| Handshake order (`client/hello` first, then wait for `server/hello`) | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`onOpen`, `handleText`) | Confirm no other messages before `server/hello` branch starts loops |
| Binary message role IDs: player audio type `4` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleBinary`) | Validate type switch handles `4` and parses timestamp+payload |
| Reject binary if no active stream | Partial | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`streamEnded`, `handleBinary`) | Verify runtime behavior after `stream/end`; add explicit active-stream guard if needed |

## Clock synchronization

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Send `client/time` with `client_transmitted` (microseconds) | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`startTimeSyncLoop`) | Confirm periodic `client/time` payload content |
| Consume `server/time` with 4 timestamps (`T1/T2/T3/T4`) | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleText` `server/time` case) | Confirm `clientRx` captured locally and passed into sync |
| Time filter / Kalman-based offset+drift estimation | Done | [sendspin-cpp](../sendspin-cpp) (`src/time_filter.cpp`, `src/time_burst.cpp`) | Validate convergence and conversion under network jitter |

## Playback synchronization

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Drop late chunks to preserve sync | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (playout loop, catch-up/drop logic) | Simulate delayed packets and observe drops with continued sync |
| Use `static_delay_ms` in scheduling | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`totalPlayoutOffsetUs` includes `- staticDelayUs`) | Verify playout timing shifts when static delay changes |
| Report `state: synchronized` and `state: error` appropriately | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`sendClientStateSynchronized`, `sendClientStateError`) | Force underrun/health failure and inspect outbound state |

## Core messages

| Message | Status | Evidence | Verification |
|---|---|---|---|
| `client/hello` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`sendClientHello`) | Validate required fields: `client_id`, `name`, `version`, `supported_roles` |
| `server/hello` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleText`) | Verify handshake completion and loop startup only here |
| `client/time` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`startTimeSyncLoop`) | Confirm interval adapts and payload stays in microseconds |
| `server/time` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleText`) | Confirm timestamps are forwarded to `ClockSync` |
| `client/state` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`sendClientStateSynchronized`, `sendClientStatePlayer`) | Confirm initial full state after `server/hello`, then delta updates |
| `server/state` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleText`) | Validate merge-style UI state updates |
| `server/command` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`server/command` branch) | Verify `volume`, `mute`, `set_static_delay` handling |
| `stream/start` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`stream/start` branch) | Confirm stream params update and buffer reset behavior |
| `stream/clear` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`stream/clear` branch) | Confirm jitter/decoder reset on clear |
| `stream/end` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`stream/end` branch) | Confirm playback pause and queue clear |
| `group/update` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`group/update` branch) | Confirm playback/group info updates |
| `client/command` | N/A | Player role only (no `controller@v1` advertised) | N/A in current scope |
| `stream/request-format` | Gap | No implementation found in current repo | Add message builder/sender and trigger policy if needed |
| `client/goodbye` | Partial | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp), [SendspinService.kt](../app/src/main/java/com/sendspinlite/service/SendspinService.kt), `disconnect` and `recoverService` | Check reason values against the recorded spec enum |

## Player messages

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Advertise `player@v1_support` | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`buildPlayerSupportObject`, `sendClientHello`) | Confirm support object appears in `client/hello` |
| Provide `supported_formats` with codec/rate/channels/bit depth | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`buildPlayerSupportObject`) | Verify PCM entries and optional Opus entries |
| Include `buffer_capacity` and `supported_commands` (`volume`, `mute`) | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`buildPlayerSupportObject`) | Validate values in emitted `client/hello` |
| Send player state with volume/mute/static delay | Partial | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`sendClientStateSynchronized`, `sendClientStatePlayer`) | Initial message includes all fields; delta updates may omit `static_delay_ms` |
| Receive player commands (`volume`, `mute`, `set_static_delay`) | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`server/command` branch) | Confirm local apply + state echo |
| Parse binary audio frame type `4` with 8-byte BE timestamp | Done | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`handleBinary`, `readInt64BE`) | Validate parser with known test frame |
| FLAC decoding support | N/A | [app/build.gradle.kts](../app/build.gradle.kts) (comment indicates FLAC planned later) | N/A for current player capability advertisement |

## Other roles

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| Advertise non-player roles | N/A | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp) (`supported_roles` only includes `player@v1`) | N/A in product scope |
| Controller command send (`client/command`) | N/A | No controller role support advertised | N/A in product scope |
| Artwork/visualizer binary handling | N/A | No role support advertised; binary handler only processes type `4` | N/A in product scope |
| Metadata and controller state parsing for UI | Done, best effort | [sendspin-cpp](../sendspin-cpp) via [JNI bridge](../app/src/main/cpp), `server/state` parsing | Check role conformance separately from tolerant parsing |

## External source handling

| Requirement | Status | Evidence | Verification |
|---|---|---|---|
| `client/state` with `state: external_source` | Gap | No outbound `external_source` state found in current repo | Implement only if product needs external source takeover behavior |

## Historical action items

1. Normalize all `client/goodbye` reasons to the spec enum.
2. Add explicit active-stream guard for binary frame acceptance after `stream/end`.
3. Implement `stream/request-format` if adaptive renegotiation is required.
4. Decide whether `external_source` is in product scope; if yes, implement corresponding state transitions.

## Update the checklist

1. Pin a new `SPEC_REVISION` commit SHA from `sendspin/spec`.
2. Check each requirement against the current implementation, including new requirements absent from these tables.
3. Replace obsolete method references and scope assumptions.
4. Update statuses and evidence links with the behavior changes.
