# Sendspin Android player

An Android client for [Sendspin](https://github.com/sendspin). It connects to a Sendspin-compatible server over WebSocket and plays timestamped PCM audio in sync with other devices.

The app targets low-memory devices on local networks. Cellular connections are unsupported. Its main purpose is audio playback.

## Features

### Playback

- Server-client clock alignment with drift estimation and timing correction
- Timestamp-based playout
- Late-frame detection and dropping
- Startup and restart catch-up

### Audio formats

- 16-bit, 24-bit, and 32-bit PCM output
- Configurable sample rates and channel counts

### Discovery and connection

- mDNS server discovery through `_sendspin-server._tcp`
- Manual server URL entry
- Saved server URL and connection settings

### Diagnostics

- Clock uncertainty, network quality, and stability
- Connection type
- Audio output queue depth and pipeline latency
- Memory usage
- Stream format and playback state

### Historical timing features

The former Kotlin client adjusted playback speed between 0.998x and 1.002x to control buffer depth. Proportional control and a deadband limited repeated adjustments. Its diagnostics displayed speed, drift, RTT, and late drops.

Those descriptions predate the native client. The current integration uses playback feedback and native sample corrections. See [Native client integration](docs/NATIVE_CLIENT.md) for current diagnostics and documentation follow-up.

### Background playback

- Foreground playback service
- Wake locks during operation
- Automatic startup after boot
- Smaller memory budgets for low-memory devices
- Connection watchdog and recovery

### App volume and ducking

- App volume and temporary ducking through `AudioTrack` gain
- Coroutine-driven volume ramps
- Broadcast intents for external apps to duck, restore, toggle, or set app volume
- Ducking leaves system volume and protocol player volume unchanged

### Crash and ANR reporting

Crash reports go to [Sentry](https://sentry.io) only when you enable reporting in **Settings**. On startup after a crash, the app prompts you to send a report.

Reporting also covers application-not-responding events and manually submitted audio issues.

The setting is available only in builds with a configured Sentry DSN. See [Build with crash reporting](#build-with-crash-reporting).

## Use the ducking intent API

Voice assistants, Home Assistant satellites, and Tasker can use Android broadcast intents to lower SendspinLite's playback volume while listening or speaking.

Ducking uses `AudioTrack.setVolume`, combined with mute and the startup volume ramp. It leaves `STREAM_MUSIC` and protocol player volume unchanged.

Start the playback service before sending an intent. The service registers the receiver for its lifetime.

Target `com.sendspinlite` with `setPackage`, Tasker's **Package** field, or the ADB `-p` option.

### Supported actions

| Action | Purpose | Extras |
|---|---|---|
| `com.sendspinlite.ACTION_DUCK` | Lower playback volume | `DUCK_PERCENT`: Int, 0-100, default 20; `RAMP_MS`: Long, default 200; optional `DURATION_MS`: Long, restore timeout |
| `com.sendspinlite.ACTION_UNDUCK` | Restore playback volume | `RAMP_MS`: Long, default 400 |
| `com.sendspinlite.ACTION_SET_APP_VOLUME` | Set base app volume | `VOLUME` or `PERCENT`: Int, 0-100 |
| `com.sendspinlite.ACTION_TOGGLE_DUCK` | Toggle ducking | `DUCK_PERCENT`: Int, 0-100, default 20; `RAMP_MS`: Long, default 200 |

### ADB examples

```bash
# Duck audio to 20% over 200 ms
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_DUCK --ei DUCK_PERCENT 20 --el RAMP_MS 200

# Restore the duck multiplier to 100% over 400 ms
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_UNDUCK --el RAMP_MS 400

# Duck audio for 3 seconds
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_DUCK --ei DUCK_PERCENT 15 --el DURATION_MS 3000

# Set app software volume to 80%
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_SET_APP_VOLUME --ei VOLUME 80
```

### Send an intent from Kotlin

```kotlin
val intent = Intent("com.sendspinlite.ACTION_DUCK").apply {
    setPackage("com.sendspinlite")
    putExtra("DUCK_PERCENT", 20)
    putExtra("RAMP_MS", 200L)
}
context.sendBroadcast(intent)
```

## Requirements

- Android 7.0 or later, API 24+. Earlier versions lack required AudioTrack synchronization support.
- Permissions:
  - `INTERNET` - WebSocket communication
  - `MODIFY_AUDIO_SETTINGS` - Audio playback control
  - `WAKE_LOCK` - Prevent sleep during playback
  - `FOREGROUND_SERVICE` - Background audio service
  - `POST_NOTIFICATIONS` - Playback notifications on Android 13 or later
  - `NEARBY_WIFI_DEVICES` - mDNS service discovery
  - `RECEIVE_BOOT_COMPLETED` - Auto-start on device boot
  - `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` - Network monitoring
- A Sendspin-compatible server, such as Music Assistant

## Connect to a server

1. Build and install the app on an Android device.
2. Grant required permissions when prompted.
3. Wait for mDNS discovery to fill in the server URL. If discovery fails, enter the URL:
   ```text
   ws://<host>:<port>/sendspin
   ```
4. Connect to the server. The server controls the stream configuration.

## Architecture

### Components

- `SendspinService`
  - Background service managing WebSocket connection lifecycle
  - Runs as foreground service with media playback notifications
  - Handles connection persistence and recovery

- `SendspinNativeClient` and `SendspinNative`
  - Kotlin wrapper around the native [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp) client
  - sendspin-cpp owns the WebSocket protocol, clock synchronization and playout scheduling
  - Drives the `AudioTrack` output and reports playback progress to the native sync task
  - See [docs/NATIVE_CLIENT.md](docs/NATIVE_CLIENT.md)

- `PcmAudioOutput`
  - Android AudioTrack wrapper
  - 16-bit, 24-bit, and 32-bit PCM support
  - Implements the `on_audio_write` contract and reports presented frames via `getPlaybackProgress()`
  - Pipeline latency estimation with smoothing
  - Buffer management for low-latency playback

- `ServiceDiscovery`
  - mDNS service discovery using Android NSD Manager
  - Automatic server detection on local network

- `PlayerViewModel` and `MainActivity`
  - Jetpack Compose UI state management
  - User preference persistence
  - Real-time diagnostics streaming

## Implemented protocol

The packet descriptions below refer to the implemented protocol. They are not a claim of conformance with the latest spec.

### Binary audio frames

- Type: `0x04`
- 8-byte big-endian server timestamp in microseconds
- Followed by PCM audio payload

### JSON control messages

- Handshake uses `client/hello` and `server/hello`.
- Clock synchronization uses `client/time` and `server/time`.
- Stream lifecycle uses `stream/start`, `stream/clear`, and `stream/end`.

## Spec conformance

- Historical checklist and review notes: [Spec conformance](docs/SPEC_CONFORMANCE.md)
- Spec reference: [sendspin/spec](https://github.com/sendspin/spec)

## Development status

The project is stable. Contributions and bug reports are welcome.

### Report an audio issue

Tap the bug-report icon next to the app title to open the audio issue reporter. The report includes:

- Audio configuration, including codec and static delay
- Audio pipeline statistics, including latency and buffer depth
- Network quality, connection type, and stability
- Recent logcat lines filtered to Sendspin and Android audio subsystem tags only

The report excludes server addresses, client and group names, and track metadata.

Two reporting options are offered:

| Option | When available | Notes |
|--------|----------------|-------|
| Upload to Sentry | Crash and ANR reporting enabled in Settings | Returns an event ID to include in your GitHub issue |
| Save to file | Always | Opens the system file picker to save the `.txt` report |

### Build from source

Install NDK `27.0.12077973` and CMake `3.22.1`. Initialize the submodules, then build:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

See [docs/NATIVE_CLIENT.md](docs/NATIVE_CLIENT.md) for details.

### Build with crash reporting

Sentry reporting requires both a valid DSN in the build and the user's consent in **Settings**.

Set `SENTRY_DSN` before building:

```bash
export SENTRY_DSN="https://<key>@<org>.ingest.sentry.io/<project>"
./gradlew assembleRelease
```

Without a DSN, the reporting toggle is disabled. Official release builds include a configured DSN.
