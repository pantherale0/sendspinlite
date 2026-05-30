# SendSpin Android Player - Architecture & Developer Documentation

Welcome to the official developer and architecture documentation for the **SendSpin PCM Player Android Application**. 

This document provides a deep, granular look into the system's design, the mathematics governing our time-synchronization engine, the audio playout scheduler pipeline, and practical guidelines for developers working on the codebase.

---

## 🏛️ 1. Architecture & Package Structure

The application is structured around a highly decoupled, modular **Component-Module Package Architecture** under the `com.sendspinlite` namespace. This prevents tight coupling and ensures each component has a single, clear responsibility.

### Architectural Package Map

```mermaid
graph TD
    %% Define Nodes
    Root[com.sendspinlite - Application Root]
    UI[com.sendspinlite.ui - UI/Compose & ViewModels]
    Service[com.sendspinlite.service - Foreground Player Service]
    Client[com.sendspinlite.client - Core PCM Client & States]
    Sync[com.sendspinlite.sync - Kalman Time Synchronization]
    Playback[com.sendspinlite.playback - Playout, JitterBuffer, Output]
    Protocol[com.sendspinlite.protocol - JSON SerDe & Handlers]
    Network[com.sendspinlite.network - mDNS & Port Verification]
    Diag[com.sendspinlite.diagnostics - Telemetry & Issue Reports]
    Sys[com.sendspinlite.system - OS Flags, Battery & Receivers]

    %% Package Connections
    UI --> Service
    Service --> Client
    Client --> Protocol
    Client --> Playback
    Client --> Sync
    Playback --> Sync
    Service --> Network
    UI --> Diag
    Diag --> Sys
    Root --> Sys
```

### Component Directory Layout

- **`com.sendspinlite` (Root)**:
  - `SendspinApplication.kt`: Application entry-point; configures dependency baselines and triggers background crash reporting services.
- **`com.sendspinlite.ui`**:
  - `MainActivity.kt`: Contains Compose layout screens (volume controls, server state charts, network telemetries, log dumps).
  - `PlayerViewModel.kt`: Intermediates between the frontend UI and the background foreground service.
- **`com.sendspinlite.service`**:
  - `SendspinService.kt`: Run as an Android foreground service with a persistent status notification to ensure the OS does not terminate active audio playout when the app is in the background.
- **`com.sendspinlite.client`**:
  - `SendspinPcmClient.kt`: The central controller managing the socket connection, coordinating sync loops, watchdog heartbeats, and frame scheduling.
  - `ClientState.kt`: Decoupled dataclasses representing UI telemetry diagnostics (`ClientDiagnostics`) and outbound server events (`ClientEvent`).
- **`com.sendspinlite.sync`**:
  - `ClockSync.kt`: Employs a **two-dimensional Kalman filter** to compute monotonic time offset and clock drift relative to the server.
- **`com.sendspinlite.playback`**:
  - `AudioJitterBuffer.kt`: Lock-free queue managing incoming timestamped chunks, sorting frames chronologically, and measuring buffer-ahead capacities.
  - `PcmAudioOutput.kt`: Low-level wrapper of Android's `AudioTrack` class; configures channel maps, bit depths, and adjusts native sample rates for sync corrections.
  - `PlaybackDiagnostics.kt`: Constants representing active playout recovery states.
  - `PlaybackSpeedController.kt`: Computes proportional clock adjustments using Exponential Moving Averages (EMA) of buffer capacities.
  - `SendspinAudioWarmup.kt`: Silent PCM format warmup checks used to estimate baseline device pipeline delay.
- **`com.sendspinlite.protocol`**:
  - `SendspinPayloadFactory.kt`: Serializes standard out-bound JSON handshake messages (`client/hello`, `client/state`, `client/goodbye`).
  - `SendspinProtocolHandler.kt` / `SendspinProtocolListener`: WebSocket event listener that parses text commands into strongly-typed callback notifications.
- **`com.sendspinlite.network`**:
  - `ServiceDiscovery.kt`: Leverages Android's Network Service Discovery (NSD) to resolve `_sendspin-server._tcp.local` servers via mDNS.
  - `PortChecker.kt`: Fast background socket verification tool used to verify server availability before connecting.
- **`com.sendspinlite.diagnostics`**:
  - `AudioIssueReporter.kt`: Gathers system telemetries and bundles anonymized playout logs for Sentry diagnostic dumps.
  - `CrashReportingManager.kt` / `ReportingUtils.kt`: Local files used to store unhandled application crash events.
- **`com.sendspinlite.system`**:
  - `SendspinSystemUtils.kt`: Gathers connection interfaces (Ethernet vs WiFi), device memories, and system media stream volumes.
  - `BootReceiver.kt`: Re-registers foreground services upon system reboot.

---

## ⚡ 2. Audio Synchronization Engine

The primary design constraint of the Sendspin protocol is maintaining **microsecond-level timing sync** across multiple physical client devices playing the same audio stream.

```
       [WebSocket Frame]
               │
               ▼
   [com.sendspinlite.protocol]
      • Parse Message Type 4
      • Extract serverTimestampUs
               │
               ▼
   [com.sendspinlite.playback] (AudioJitterBuffer)
      • Sort chronologically
      • Track bufferAheadMs
               │
               ▼  (Playout Loop Thread)
   [com.sendspinlite.sync] (ClockSync)
      • Server time → Client monotonic time (using Kalman offset)
      • Apply Playout Offset adjustments
               │
               ▼
   [com.sendspinlite.playback] (PlaybackSpeedController)
      • Compute EMA buffer-ahead errors
      • Adjust AudioTrack playout sample rate
               │
               ▼
      [Native AudioTrack] (PcmAudioOutput)
```

### 1. Clock Synchronization Math (Kalman Filtering)

To translate server-clock timelines into local system times, `ClockSync.kt` utilizes a NTP-style 4-timestamp exchange fed into a two-dimensional Kalman filter tracking **clock offset** and **drift**.

#### A. NTP Offset and Delay Calculations
Each sync transaction exchanges four timestamps:
- $T_1$: Client transmitted request time.
- $T_2$: Server received request time.
- $T_3$: Server transmitted response time.
- $T_4$: Client received response time.

$$\text{NTP Offset (Measurement)} = \frac{(T_1 - T_2) + (T_4 - T_3)}{2}$$

$$\text{Max Error (Half RTT)} = \frac{(T_4 - T_1) - (T_3 - T_2)}{2}$$

#### B. The State Vector
The Kalman filter tracks the state vector $x_k = \begin{bmatrix} \theta_k & \delta_k \end{bmatrix}^T$ where:
- $\theta_k$: Monotonic clock offset (Server Time - Client Time) in microseconds.
- $\delta_k$: Monotonic clock drift (rate of change of offset, dimensionless in $\mu s/\mu s$).

#### C. The Prediction Phase
At step $k$ with elapsed microsecond time $dt = t_k - t_{k-1}$:

$$\hat{x}_{k|k-1} = F x_{k-1|k-1} \implies \begin{bmatrix} \hat{\theta}_k \\ \hat{\delta}_k \end{bmatrix} = \begin{bmatrix} 1 & dt \\ 0 & 1 \end{bmatrix} \begin{bmatrix} \theta_{k-1} \\ \delta_{k-1} \end{bmatrix}$$

$$P_{k|k-1} = F P_{k-1|k-1} F^T + Q$$

where $P$ is the $2 \times 2$ covariance matrix, and $Q$ is the process noise covariance matrix updated proportionally to process variance $\sigma_w^2$ and drift variance $\sigma_d^2$:

$$Q = \begin{bmatrix} dt \cdot \sigma_w^2 & 0 \\ 0 & dt \cdot \sigma_d^2 \end{bmatrix}$$

#### D. The Correction Phase
Using NTP offset $z_k$ as measurement and measurement variance $R_k$ derived from delay max-error:

$$\text{Residual (Innovation)} = y_k = z_k - H \hat{x}_{k|k-1} \implies y_k = z_k - \hat{\theta}_k$$

$$\text{Uncertainty Covariance} = S_k = H P_{k|k-1} H^T + R_k \implies S_k = P_{\theta\theta} + R_k$$

$$\text{Kalman Gain} = K_k = P_{k|k-1} H^T S_k^{-1} \implies K_k = \frac{1}{S_k} \begin{bmatrix} P_{\theta\theta} \\ P_{\theta\delta} \end{bmatrix}$$

$$\text{Updated State} = x_{k|k} = \hat{x}_{k|k-1} + K_k y_k$$

$$\text{Updated Covariance} = P_{k|k} = (I - K_k H) P_{k|k-1}$$

#### E. Adaptive Forgetting
If the residual $|y_k|$ exceeds $3 \times \text{max\_error}$ due to network congestion or GC pause:
- Multiply $P$ covariance by a `forgetFactor` to let the filter quickly discard outdated predictions and converge on new network conditions.

---

### 2. Playout Loop & Jitter Scheduling

In `SendspinPcmClient.kt`, the playout loop thread continuously polls chunk frames from `AudioJitterBuffer.kt` and calculates the exact local playback target time.

#### Playout Offset Calculation
To align speakers, the scheduler must compensate for three factors:
- **Playout Offset**: Baseline delay ($\approx -50\text{ms}$) to account for Android's internal audio pipeline latency.
- **Playout Offset Adjustment**: Per-device user-defined adjustment ($\pm 100\text{ms}$) to align mismatched hardware.
- **Static Delay** ($static\_delay\_ms$): Spec-defined parameter compensating for external speaker/amplifier latency.

$$\text{Total Playout Offset } (\mu s) = \text{PlayoutOffset} + \text{PlayoutOffsetAdjustment} - \text{StaticDelay}$$

$$\text{Client Target Time } (T_{play}) = \text{convertServerToClient}(T_{server\_timestamp}) + \text{Total Playout Offset}$$

#### Lateness Evaluation
The scheduler computes the early playout offset $E$:

$$E = T_{play} - t_{now}$$

- If $E > \text{AudioTrack Latency}$: The thread delays (sleeps) for the difference to ensure precise playout timing.
- If $E < \text{AudioTrack Latency}$: The frame is considered late. If $E$ falls beyond the late drop threshold, the frame is discarded to catch up to the live stream timeline.

---

### 3. Proportional Speed Adjustment Math

To correct tiny drifting errors without audible audio cuts, the `PlaybackSpeedController.kt` dynamically modifies the native sample rate of Android's `AudioTrack`.

1. **Calculate Smoothed Buffer Ahead**: Uses a short window Exponential Moving Average (EMA) to estimate current buffer-ahead depth $B_{ms}$.
2. **Compute Error**: Target buffer depth is $80\text{ms}$. The error is:

$$e_k = B_{ms} - \text{TargetBufferDepth}$$

3. **Sample Rate Scaler**: The sample rate adjustment coefficient is proportionally governed by:

$$\text{Adjustment} = e_k \times K_p$$

- If $e_k > 0$ (buffer is growing): Speed up playout slightly ($\max +1.5\%$).
- If $e_k < 0$ (buffer is shrinking): Slow down playout slightly ($\min -1.5\%$).
- **Deadband**: If $|e_k| < 5\text{ms}$, speed remains at a nominal $1.0\times$ to avoid constant jitter adjustments.

---

## 📡 3. Communication Protocol Flow

```
[Client]                                                          [Server]
   │                                                                 │
   ├─────── client/hello (Supported roles: player@v1) ──────────────>│
   │                                                                 │
   │<────── server/hello (Active roles: player@v1, metadata) ────────┤
   │                                                                 │
   │   ┌────────────────────────────────────────────────────────┐    │
   │   │ Start continuous Clock Sync & Watchdog Loop            │    │
   │   └────────────────────────────────────────────────────────┘    │
   │                                                                 │
   ├─────── client/state (synchronized, player: delay/vol) ─────────>│
   │                                                                 │
   │   ┌─── LOOP: Sync Timings ─────────────────────────────────┐    │
   ├───│─── client/time (client_transmitted) ──────────────────>│    │
   │   │                                                        │    │
   │   │<── server/time (T1, T2, T3) ───────────────────────────│────┤
   │   └────────────────────────────────────────────────────────┘    │
   │                                                                 │
   │<────── stream/start (PCM codec details, sample rate, play_at) ──┤
   │                                                                 │
   │<────── Binary Chunk Type 4 (Audio data payload) ───────────────┤
   │                                                                 │
   │<────── server/state (Group metadata, volume, muted) ───────────┤
   │                                                                 │
   ├─────── client/goodbye (reason: shutdown / another_server) ─────>│
```

---

## 🛠️ 4. Developer's Guide & Verification

### 1. Development Requirements
- **Java Development Kit**: JDK 17 (recommended: OpenJDK 17).
- **Android Target**: Android 12 (API 31) minimum, compiling with Android 14 SDK (API 34).
- **Gradle Version**: $\ge 8.3$.

### 2. Compilation and Build Commands

Run all commands using the persistent Java 17 path variable:

```bash
# Compile and check syntax correctness for Kotlin sources
./gradlew compileDebugKotlin --no-configuration-cache -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk

# Compile and check syntax correctness for Unit Tests
./gradlew compileDebugUnitTestKotlin --no-configuration-cache -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk

# Assemble the final debug APK package
./gradlew assembleDebug --no-configuration-cache -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk
```

### 3. Automated Test Verification
Unit tests are written using standard JUnit and **Google Truth** assertion wrappers.

```bash
# Execute the complete test suite
./gradlew test --no-configuration-cache -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk
```

### 4. Telemetry and Diagnostics Flow
- When `Sentry` crash reporting is enabled, unhandled exceptions and ANR errors are automatically uploaded.
- To export a raw dump of local timing and playback jitter logs, the user can **tap the title "Sendspin Lite Player" on the main screen exactly 5 times**. This triggers a system-level save action to write the logs to an external text file.
