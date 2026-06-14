// ClientHandle implementation and JNI entry points for com.sendspinlite.client.SendspinNative.

#include "jni_bridge.h"

#include <android/log.h>

#include <chrono>
#include <string>
#include <thread>

#include "android_client_listener.h"
#include "android_metadata_listener.h"
#include "android_network_provider.h"
#include "android_player_listener.h"

#define LOG_TAG "SendspinJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sendspin_jni {

namespace {

std::string ToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

sendspin::SendspinGoodbyeReason ToGoodbyeReason(jint ordinal) {
    switch (ordinal) {
        case 0:
            return sendspin::SendspinGoodbyeReason::ANOTHER_SERVER;
        case 1:
            return sendspin::SendspinGoodbyeReason::SHUTDOWN;
        case 2:
            return sendspin::SendspinGoodbyeReason::RESTART;
        default:
            return sendspin::SendspinGoodbyeReason::USER_REQUEST;
    }
}

}  // namespace

ClientHandle::ClientHandle(JNIEnv* env, jobject callbacks, sendspin::SendspinClientConfig config,
                           sendspin::PlayerRoleConfig player_config, int initial_static_delay_ms)
    : client_(std::make_unique<sendspin::SendspinClient>(std::move(config))) {
    callbacks_ = env->NewGlobalRef(callbacks);

    jclass cls = env->GetObjectClass(callbacks);
    methods_.on_audio_write = env->GetMethodID(cls, "onAudioWrite", "(Ljava/nio/ByteBuffer;II)I");
    methods_.on_stream_start = env->GetMethodID(cls, "onStreamStart", "(III)V");
    methods_.on_stream_end = env->GetMethodID(cls, "onStreamEnd", "()V");
    methods_.on_volume_changed = env->GetMethodID(cls, "onVolumeChanged", "(I)V");
    methods_.on_mute_changed = env->GetMethodID(cls, "onMuteChanged", "(Z)V");
    methods_.on_static_delay_changed = env->GetMethodID(cls, "onStaticDelayChanged", "(I)V");
    methods_.on_metadata_update = env->GetMethodID(
        cls, "onMetadataUpdate",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/"
        "String;IIII)V");
    methods_.on_metadata_clear = env->GetMethodID(cls, "onMetadataClear", "()V");
    methods_.on_group_update = env->GetMethodID(
        cls, "onGroupUpdate", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    methods_.on_time_sync_updated = env->GetMethodID(cls, "onTimeSyncUpdated", "(F)V");
    methods_.on_request_high_performance =
        env->GetMethodID(cls, "onRequestHighPerformance", "()V");
    methods_.on_release_high_performance =
        env->GetMethodID(cls, "onReleaseHighPerformance", "()V");
    methods_.on_connection_state =
        env->GetMethodID(cls, "onConnectionState", "(Ljava/lang/String;Z)V");
    methods_.is_network_ready = env->GetMethodID(cls, "isNetworkReady", "()Z");
    env->DeleteLocalRef(cls);

    player_listener_ = std::make_unique<AndroidPlayerListener>(this);
    metadata_listener_ = std::make_unique<AndroidMetadataListener>(this);
    client_listener_ = std::make_unique<AndroidClientListener>(this);
    network_provider_ = std::make_unique<AndroidNetworkProvider>(this);

    player_ = &client_->add_player(std::move(player_config));
    player_->set_listener(player_listener_.get());
    player_->set_static_delay_adjustable(true);
    player_->update_static_delay(static_cast<uint16_t>(initial_static_delay_ms));

    metadata_ = &client_->add_metadata();
    metadata_->set_listener(metadata_listener_.get());

    client_->set_listener(client_listener_.get());
    client_->set_network_provider(network_provider_.get());
}

ClientHandle::~ClientHandle() {
    if (running_.exchange(false) && loop_thread_.joinable()) {
        loop_thread_.join();
    }
    client_.reset();
    if (callbacks_ != nullptr) {
        JNIEnv* env = GetEnv();
        if (env != nullptr) {
            env->DeleteGlobalRef(callbacks_);
        }
        callbacks_ = nullptr;
    }
}

void ClientHandle::Start() {
    if (!client_->start_server()) {
        LOGE("start_server() failed");
        return;
    }
    running_.store(true);
    loop_thread_ = std::thread(&ClientHandle::LoopThread, this);
}

void ClientHandle::Connect(const std::string& url) {
    client_->connect_to(url);
}

void ClientHandle::Disconnect(sendspin::SendspinGoodbyeReason reason) {
    // Stop the loop thread first so disconnect() does not race client_->loop().
    // On the host networking path the goodbye send is synchronous, so it still flushes.
    if (running_.exchange(false) && loop_thread_.joinable()) {
        loop_thread_.join();
    }
    if (client_) {
        client_->disconnect(reason);
    }
}

void ClientHandle::LoopThread() {
    GetEnv();  // attach this thread once; main-loop callbacks run here
    while (running_.load()) {
        client_->loop();

        const bool connected = client_->is_connected();
        if (connected != last_connected_.exchange(connected)) {
            JNIEnv* env = GetEnv();
            if (env != nullptr && methods_.on_connection_state != nullptr) {
                jstring status =
                    env->NewStringUTF(connected ? "ws_open" : "closed:connection_lost");
                env->CallVoidMethod(callbacks_, methods_.on_connection_state, status,
                                    static_cast<jboolean>(connected));
                if (status != nullptr) {
                    env->DeleteLocalRef(status);
                }
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

void ClientHandle::NotifyAudioPlayed(uint32_t frames, int64_t finish_timestamp_us) {
    if (player_ != nullptr) {
        player_->notify_audio_played(frames, finish_timestamp_us);
    }
}

void ClientHandle::UpdateVolume(uint8_t volume) {
    if (player_ != nullptr) {
        player_->update_volume(volume);
    }
}

void ClientHandle::UpdateMuted(bool muted) {
    if (player_ != nullptr) {
        player_->update_muted(muted);
    }
}

void ClientHandle::UpdateStaticDelay(uint16_t delay_ms) {
    if (player_ != nullptr) {
        player_->update_static_delay(delay_ms);
    }
}

bool ClientHandle::IsConnected() const {
    return client_->is_connected();
}

bool ClientHandle::IsTimeSynced() const {
    return client_->is_time_synced();
}

uint8_t ClientHandle::GetVolume() const {
    return player_ != nullptr ? player_->get_volume() : 0;
}

bool ClientHandle::GetMuted() const {
    return player_ != nullptr && player_->get_muted();
}

uint16_t ClientHandle::GetStaticDelayMs() const {
    return player_ != nullptr ? player_->get_static_delay_ms() : 0;
}

uint32_t ClientHandle::GetTrackProgressMs() const {
    return metadata_ != nullptr ? metadata_->get_track_progress_ms() : 0;
}

uint32_t ClientHandle::GetTrackDurationMs() const {
    return metadata_ != nullptr ? metadata_->get_track_duration_ms() : 0;
}

}  // namespace sendspin_jni

// ============================================================================
// JNI entry points
// ============================================================================

using sendspin_jni::ClientHandle;

extern "C" {

JNIEXPORT jlong JNICALL Java_com_sendspinlite_client_SendspinNative_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jobject callbacks, jstring client_id, jstring name,
    jstring product_name, jstring manufacturer, jstring software_version, jint fixed_delay_us,
    jlong audio_buffer_capacity, jint initial_static_delay_ms) {
    sendspin::SendspinClientConfig config;
    config.client_id = sendspin_jni::ToStdString(env, client_id);
    config.name = sendspin_jni::ToStdString(env, name);
    config.product_name = sendspin_jni::ToStdString(env, product_name);
    config.manufacturer = sendspin_jni::ToStdString(env, manufacturer);
    config.software_version = sendspin_jni::ToStdString(env, software_version);

    sendspin::PlayerRoleConfig player_config;
    // PCM-only formats advertised in the player hello message.
    player_config.audio_formats = {
        {sendspin::SendspinCodecFormat::PCM, 2, 48000, 16},
        {sendspin::SendspinCodecFormat::PCM, 2, 48000, 24},
        {sendspin::SendspinCodecFormat::PCM, 2, 48000, 32},
        {sendspin::SendspinCodecFormat::PCM, 2, 44100, 16},
        {sendspin::SendspinCodecFormat::PCM, 2, 44100, 24},
        {sendspin::SendspinCodecFormat::PCM, 2, 44100, 32},
    };
    player_config.audio_buffer_capacity = static_cast<size_t>(audio_buffer_capacity);
    player_config.fixed_delay_us = static_cast<int32_t>(fixed_delay_us);
    player_config.initial_static_delay_ms = static_cast<uint16_t>(initial_static_delay_ms);

    auto* handle = new ClientHandle(env, callbacks, std::move(config), std::move(player_config),
                                    initial_static_delay_ms);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeStart(JNIEnv* /*env*/,
                                                                               jobject /*thiz*/,
                                                                               jlong handle) {
    reinterpret_cast<ClientHandle*>(handle)->Start();
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeConnect(JNIEnv* env,
                                                                                 jobject /*thiz*/,
                                                                                 jlong handle,
                                                                                 jstring url) {
    reinterpret_cast<ClientHandle*>(handle)->Connect(sendspin_jni::ToStdString(env, url));
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeDisconnect(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint reason) {
    reinterpret_cast<ClientHandle*>(handle)->Disconnect(sendspin_jni::ToGoodbyeReason(reason));
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeDestroy(JNIEnv* /*env*/,
                                                                                 jobject /*thiz*/,
                                                                                 jlong handle) {
    delete reinterpret_cast<ClientHandle*>(handle);
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeNotifyAudioPlayed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint frames, jlong finish_timestamp_us) {
    reinterpret_cast<ClientHandle*>(handle)->NotifyAudioPlayed(
        static_cast<uint32_t>(frames), static_cast<int64_t>(finish_timestamp_us));
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeUpdateVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint volume) {
    reinterpret_cast<ClientHandle*>(handle)->UpdateVolume(static_cast<uint8_t>(volume));
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeUpdateMuted(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jboolean muted) {
    reinterpret_cast<ClientHandle*>(handle)->UpdateMuted(muted == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_sendspinlite_client_SendspinNative_nativeUpdateStaticDelay(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint delay_ms) {
    reinterpret_cast<ClientHandle*>(handle)->UpdateStaticDelay(static_cast<uint16_t>(delay_ms));
}

JNIEXPORT jboolean JNICALL Java_com_sendspinlite_client_SendspinNative_nativeIsConnected(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ClientHandle*>(handle)->IsConnected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_sendspinlite_client_SendspinNative_nativeIsTimeSynced(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ClientHandle*>(handle)->IsTimeSynced() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_sendspinlite_client_SendspinNative_nativeGetVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ClientHandle*>(handle)->GetVolume();
}

JNIEXPORT jboolean JNICALL Java_com_sendspinlite_client_SendspinNative_nativeGetMuted(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ClientHandle*>(handle)->GetMuted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_sendspinlite_client_SendspinNative_nativeGetStaticDelayMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<ClientHandle*>(handle)->GetStaticDelayMs();
}

JNIEXPORT jint JNICALL Java_com_sendspinlite_client_SendspinNative_nativeGetTrackProgressMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jint>(reinterpret_cast<ClientHandle*>(handle)->GetTrackProgressMs());
}

JNIEXPORT jint JNICALL Java_com_sendspinlite_client_SendspinNative_nativeGetTrackDurationMs(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jint>(reinterpret_cast<ClientHandle*>(handle)->GetTrackDurationMs());
}

JNIEXPORT jlong JNICALL Java_com_sendspinlite_client_SendspinNative_nativeMonotonicTimeUs(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // Must match sendspin::platform_time_us() (steady_clock microseconds).
    const auto now = std::chrono::steady_clock::now();
    return static_cast<jlong>(
        std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count());
}

}  // extern "C"
