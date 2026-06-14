// Shared declarations for the SendSpin Android JNI bridge.
//
// The bridge wraps a sendspin::SendspinClient (player + metadata roles) and forwards
// protocol/audio events to a Kotlin SendspinNativeCallbacks object via JNI.

#pragma once

#include <jni.h>

#include <atomic>
#include <memory>
#include <string>
#include <thread>

#include "sendspin/client.h"
#include "sendspin/metadata_role.h"
#include "sendspin/player_role.h"

namespace sendspin_jni {

// Process-wide JavaVM, captured in JNI_OnLoad.
extern JavaVM* g_vm;

// Returns a JNIEnv for the calling thread, attaching it to the JVM if needed.
// Native threads created by sendspin-cpp (sync task) are auto-detached at thread
// exit via a thread_local guard, so callers must not detach manually.
JNIEnv* GetEnv();

// Cached jmethodIDs for the Kotlin SendspinNativeCallbacks interface.
struct CallbackMethods {
    jmethodID on_audio_write = nullptr;       // (Ljava/nio/ByteBuffer;II)I
    jmethodID on_stream_start = nullptr;      // (III)V
    jmethodID on_stream_end = nullptr;        // ()V
    jmethodID on_volume_changed = nullptr;    // (I)V
    jmethodID on_mute_changed = nullptr;      // (Z)V
    jmethodID on_static_delay_changed = nullptr;  // (I)V
    jmethodID on_metadata_update = nullptr;   // (5×String,I,I,I,I)V
    jmethodID on_metadata_clear = nullptr;    // ()V
    jmethodID on_group_update = nullptr;      // (3×String)V
    jmethodID on_time_sync_updated = nullptr; // (F)V
    jmethodID on_request_high_performance = nullptr;  // ()V
    jmethodID on_release_high_performance = nullptr;  // ()V
    jmethodID on_connection_state = nullptr;  // (Ljava/lang/String;Z)V
    jmethodID is_network_ready = nullptr;     // ()Z
};

// Forward declarations of the listener implementations.
class AndroidPlayerListener;
class AndroidMetadataListener;
class AndroidClientListener;
class AndroidNetworkProvider;

// Owns the native client, its roles, listeners and the main-loop thread.
// Created in nativeCreate and destroyed in nativeDestroy. The Kotlin callbacks
// object is held as a global ref for the lifetime of the handle.
class ClientHandle {
public:
    ClientHandle(JNIEnv* env, jobject callbacks, sendspin::SendspinClientConfig config,
                 sendspin::PlayerRoleConfig player_config, int initial_static_delay_ms);
    ~ClientHandle();

    void Start();
    void Connect(const std::string& url);
    void Disconnect(sendspin::SendspinGoodbyeReason reason);

    void NotifyAudioPlayed(uint32_t frames, int64_t finish_timestamp_us);
    void UpdateVolume(uint8_t volume);
    void UpdateMuted(bool muted);
    void UpdateStaticDelay(uint16_t delay_ms);

    bool IsConnected() const;
    bool IsTimeSynced() const;
    uint8_t GetVolume() const;
    bool GetMuted() const;
    uint16_t GetStaticDelayMs() const;
    uint32_t GetTrackProgressMs() const;
    uint32_t GetTrackDurationMs() const;

    // Accessors used by the listener implementations.
    jobject callbacks() const { return callbacks_; }
    const CallbackMethods& methods() const { return methods_; }
    sendspin::PlayerRole* player() const { return player_; }

private:
    void LoopThread();

    jobject callbacks_ = nullptr;  // global ref
    CallbackMethods methods_{};

    std::unique_ptr<sendspin::SendspinClient> client_;
    sendspin::PlayerRole* player_ = nullptr;
    sendspin::MetadataRole* metadata_ = nullptr;

    std::unique_ptr<AndroidPlayerListener> player_listener_;
    std::unique_ptr<AndroidMetadataListener> metadata_listener_;
    std::unique_ptr<AndroidClientListener> client_listener_;
    std::unique_ptr<AndroidNetworkProvider> network_provider_;

    std::thread loop_thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> last_connected_{false};
};

}  // namespace sendspin_jni
