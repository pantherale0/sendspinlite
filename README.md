# Sendspin Android Player

A basic Android client for [Sendspin](https://github.com/sendspin) that provides synchronized network audio playback. It connects to a Sendspin-compatible server (e.g., Home Assistant) over WebSocket, receives timestamped PCM or Opus audio frames, performs clock synchronization and jitter buffering, and plays audio in tight sync with other devices.

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
- **PCM and Opus** — both advertised in `client/hello` (PCM listed first as preferred); the server selects the stream format
  - Opus decoding via Concentus (pure Java library)
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

### Crash & ANR Reporting (opt-in)
- **Privacy-first crash reporting**
  - Detects hard crashes and App Not Responding (ANR) conditions
  - On startup after a crash, the app prompts to send an anonymous report
  - Reports are sent to [Sentry](https://sentry.io) only when the feature is explicitly enabled (opt-in)
  - Toggle is available in the **Settings** section of the main screen
  - No data is ever collected or transmitted unless opted-in
  - Sentry is not used for anything other than reports for crashes/ANR or audio issues (triggered manually by pressing the dedicated button)
  - Only available in builds where a Sentry DSN has been configured (see [Development](#development-status))

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

- **SendspinPcmClient**
  - WebSocket protocol implementation
  - Audio stream lifecycle management
  - Clock synchronization loops
  - Playout scheduling and timing control
  - Memory monitoring and watchdog systems

- **ClockSync**
  - RTT-based offset estimation
  - Drift calculation and uncertainty tracking
  - SNR-based quality assessment

- **AudioJitterBuffer**
  - Timestamp-ordered queue management
  - Late-frame detection and dropping
  - Restart recovery logic for buffer deadlock prevention

- **OpusDecoder**
  - Concentus-based Opus to PCM decoding
  - Automatic fallback when unavailable

- **PcmAudioOutput**
  - AndroidX AudioTrack wrapper
  - Multi-bit-depth support (16/24/32-bit)
  - Playback speed control for adaptive timing
  - Pipeline latency estimation with smoothing
  - Real-time latency and speed reporting
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
- Followed by PCM or Opus audio payload

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
