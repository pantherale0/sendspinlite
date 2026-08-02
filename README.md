# Sendspin Android Player

A basic Android client for [Sendspin](https://github.com/sendspin) that provides synchronized network audio playback. It connects to a Sendspin-compatible server (e.g., Home Assistant) over WebSocket, receives timestamped PCM audio frames, performs clock synchronization and jitter buffering, and plays audio in tight sync with other devices.

This project is specially designed for low memory devices and a local network connection only. Connections via cellular will not be supported. The client is designed to offer only a Sendspin player.

## Features

### Core Playback
- **Synchronized audio playback across network devices**
  - Server-client clock alignment with drift estimation and real-time correction
  - Timestamp-based playout with adjustable real-time offset for fine-tuning sync
  - RTT-based network latency measurement
  - Adaptive jitter buffering with late-frame detection and dropping
  - Startup and restart catch-up logic to prevent buffer deadlock

### Audio Codec Support
- **PCM playback**
  - Support for 16-bit, 24-bit, and 32-bit PCM output
  - Configurable sample rates and channel counts

### Discovery & Connection
- **Automatic server discovery**
  - mDNS service discovery (`_sendspin-server._tcp`)
  - Manual URL entry fallback
  - Persistence of server URL and connection settings

### Diagnostics & Tuning
- **Real-time diagnostics dashboard**
  - Sync drift (ppm) and uncertainty measurements
  - Network quality and stability assessment
  - Connection type and RTT latency
  - Buffer depth and late frame drops
  - Audio pipeline latency monitoring
  - Automatic playback speed adjustment (0.998x-1.002x) for buffer timing
  - Memory usage monitoring for low-end devices
  - Detailed stream information and state

### Automatic Timing Control
- **Adaptive playback speed adjustment**
  - Continuous buffer-ahead monitoring
  - Proportional control to maintain target latency
  - Automatic speed tuning (0.998x to 1.002x) without user intervention
  - Dampened deadband to prevent audible artifacts
  - Real-time latency and speed display

### Reliability & Performance
- **Background service support**
  - Foreground service for persistent playback
  - Wake lock management for sustained operation
  - Boot completion receiver for auto-start
  - Memory-aware operation for low-end devices
  - Watchdog monitoring for connection stability

### Audio Ducking & Intent API
- **Independent App Volume & Software Ducking**
  - Smooth coroutine-driven audio volume ducking (fade-in / fade-out) via local `AudioTrack` gain
  - Independent of Android system master volume so external Voice Assistants (e.g. Home Assistant, Tasker, Rhasspy) remain crisp and clear
  - Broadcast intent integration allowing external apps to duck, unduck, toggle, or adjust app volume dynamically
  - Ducking does not publish temporary levels as protocol player volume

### Crash & ANR Reporting (opt-in)
- **Privacy-first crash reporting**
  - Detects hard crashes and App Not Responding (ANR) conditions
  - On startup after a crash, the app prompts to send an anonymous report
  - Reports are sent to [Sentry](https://sentry.io) only when the feature is explicitly enabled (opt-in)
  - Toggle is available in the **Settings** section of the main screen
  - No data is ever collected or transmitted unless opted-in
  - Sentry is not used for anything other than reports for crashes/ANR or audio issues (triggered manually by pressing the dedicated button)
  - Only available in builds where a Sentry DSN has been configured (see [Development](#development-status))

## Audio Ducking & Intent API Guide

SendSpin supports external volume ducking via Android Broadcast Intents. This allows Voice Assistants, Home Assistant Satellite devices, or Tasker to duck SendSpin's playback volume when listening or speaking without affecting system master volume or the Sendspin protocol player volume.

Ducking is applied as local `AudioTrack.setVolume` gain (composed with mute and soft-start), not by changing `STREAM_MUSIC`.

**Requirement:** SendSpin's playback service must already be running (connected/playing). The duck receiver is registered dynamically while the service is alive so other apps can deliver custom broadcast actions.

**Recommended:** Target the SendSpin package when sending (`setPackage("com.sendspinlite")` / Tasker "Package" field / `adb -p com.sendspinlite`).

### Supported Broadcast Intent Actions

| Action | Purpose | Extras |
|---|---|---|
| `com.sendspinlite.ACTION_DUCK` | Ducks audio playback volume | `DUCK_PERCENT` (Int 0-100, default 20), `RAMP_MS` (Long, default 200), `DURATION_MS` (Long, optional auto-restore timeout) |
| `com.sendspinlite.ACTION_UNDUCK` | Restores audio volume to normal | `RAMP_MS` (Long, default 400) |
| `com.sendspinlite.ACTION_SET_APP_VOLUME` | Sets base app audio volume | `VOLUME` or `PERCENT` (Int 0-100) |
| `com.sendspinlite.ACTION_TOGGLE_DUCK` | Toggles ducking state | `DUCK_PERCENT` (Int 0-100, default 20), `RAMP_MS` (Long, default 200) |

### ADB Examples

```bash
# Duck audio to 20% over 200ms (package-targeted)
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_DUCK --ei DUCK_PERCENT 20 --el RAMP_MS 200

# Unduck audio back to 100% over 400ms
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_UNDUCK --el RAMP_MS 400

# Duck audio for a temporary 3-second duration
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_DUCK --ei DUCK_PERCENT 15 --el DURATION_MS 3000

# Set app software volume to 80%
adb shell am broadcast -p com.sendspinlite -a com.sendspinlite.ACTION_SET_APP_VOLUME --ei VOLUME 80
```

### From another app (Kotlin)

```kotlin
val intent = Intent("com.sendspinlite.ACTION_DUCK").apply {
    setPackage("com.sendspinlite")
    putExtra("DUCK_PERCENT", 20)
    putExtra("RAMP_MS", 200L)
}
context.sendBroadcast(intent)
```

## Requirements

- **Android**: API 24+ (Android 7.0 and later), in theory this could be dropped to 21, however this app relies on `AudioTrack` to play audio streams and a number of required sync features are unavailable prior to Android 7.0.
- **Permissions**: 
  - `INTERNET` - WebSocket communication
  - `MODIFY_AUDIO_SETTINGS` - Audio playback control
  - `WAKE_LOCK` - Prevent sleep during playback
  - `FOREGROUND_SERVICE` - Background audio service
  - `POST_NOTIFICATIONS` - Playback notifications (Android 13+)
  - `NEARBY_WIFI_DEVICES` - mDNS service discovery (Android 12+)
  - `RECEIVE_BOOT_COMPLETED` - Auto-start on device boot
  - `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` - Network monitoring
- **Server**: Sendspin-compatible server (e.g., Music Assistant)

## Getting Started

### Basic Setup

1. Build and install the app on an Android device.
2. Grant required permissions when prompted.
3. The app will attempt automatic server discovery via mDNS.
   - If discovery succeeds, the server URL is populated automatically.
   - If discovery fails or times out, manually enter your server URL:
     ```
     ws://<host>:<port>/sendspin
     ```
4. Connect to the server.
5. All configuration is performed server side.
## Architecture

### Core Components

- **SendspinService**
  - Background service managing WebSocket connection lifecycle
  - Runs as foreground service with media playback notifications
  - Handles connection persistence and recovery

- **SendspinNativeClient / SendspinNative (JNI)**
  - Kotlin wrapper around the native [sendspin-cpp](https://github.com/Sendspin/sendspin-cpp) client
  - sendspin-cpp owns the WebSocket protocol, clock synchronization and playout scheduling
  - Drives the `AudioTrack` output and reports playback progress to the native sync task
  - See [docs/NATIVE_CLIENT.md](docs/NATIVE_CLIENT.md)

- **PcmAudioOutput**
  - AndroidX AudioTrack wrapper
  - Multi-bit-depth support (16/24/32-bit)
  - Implements the `on_audio_write` contract and reports presented frames via `getPlaybackProgress()`
  - Pipeline latency estimation with smoothing
  - Buffer management for low-latency playback

- **ServiceDiscovery**
  - mDNS service discovery using Android NSD Manager
  - Automatic server detection on local network

- **PlayerViewModel / MainActivity**
  - Jetpack Compose UI state management
  - User preference persistence
  - Real-time diagnostics streaming

## Protocol Overview

### Binary Audio Frames
- Type: `0x04`
- 8-byte big-endian server timestamp (microseconds)
- Followed by PCM audio payload

### JSON Control Messages
- **Handshake**: `client/hello`, `server/hello`
- **Time Sync**: `client/time`, `server/time`
- **Stream Lifecycle**: `stream/start`, `stream/end`

## Spec Conformance

- Checklist: [docs/SPEC_CONFORMANCE.md](docs/SPEC_CONFORMANCE.md)
- Spec reference: [sendspin/spec](https://github.com/sendspin/spec)

## Development Status

This project is **stable**. Contributions and bug reports are welcome.

### Reporting Audio / Playback Issues

Tap the **bug-report icon** (🐛) next to the app title to open the audio issue reporter.
It collects a privacy-redacted diagnostics snapshot that includes:

- Audio configuration (codec, static delay, playback speed)
- Audio pipeline statistics (latency, drift, RTT, buffer, sync counts)
- Network quality metrics (connection type, stability)
- Recent logcat lines filtered to Sendspin and Android audio subsystem tags only

**Personal data is intentionally excluded**: server addresses, client/group names, and all track metadata (title, artist, album, etc.) are never collected.

Two reporting options are offered:

| Option | When available | Notes |
|--------|----------------|-------|
| **Upload to Sentry** | Crash & ANR reporting enabled in Settings | Returns a unique event ID to include in GitHub issues |
| **Save to File** | Always | System file picker lets you choose where to save the `.txt` report |

### Building from Source

The native client lives in the `sendspin-cpp` git submodule and is compiled with the Android NDK,
so initialise submodules and install the native toolchain (NDK `27.0.12077973`, CMake `3.22.1`)
before building:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

See [docs/NATIVE_CLIENT.md](docs/NATIVE_CLIENT.md) for details.

### Building with Crash Reporting

Crash and ANR reporting via Sentry is **disabled by default** and only activates when:
1. The build is compiled with a valid Sentry DSN, **and**
2. The user explicitly enables the feature in the app's Settings section.

To enable crash reporting in your own build, set the `SENTRY_DSN` environment variable before building:
```bash
export SENTRY_DSN="https://<key>@<org>.ingest.sentry.io/<project>"
./gradlew assembleRelease
```

Without this variable the feature is unavailable (the toggle in Settings will be disabled). Official release builds published by this project include a configured DSN.

---
