#include "android_player_listener.h"

namespace sendspin_jni {

size_t AndroidPlayerListener::on_audio_write(uint8_t* data, size_t length, uint32_t timeout_ms) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_audio_write == nullptr) {
        return 0;
    }
    // Zero-copy: hand the native PCM buffer to Kotlin as a direct ByteBuffer. The
    // buffer is only valid for the duration of this synchronous call.
    jobject buffer = env->NewDirectByteBuffer(data, static_cast<jlong>(length));
    if (buffer == nullptr) {
        return 0;
    }
    jint written = env->CallIntMethod(handle_->callbacks(), handle_->methods().on_audio_write,
                                      buffer, static_cast<jint>(length),
                                      static_cast<jint>(timeout_ms));
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return written < 0 ? 0U : static_cast<size_t>(written);
}

void AndroidPlayerListener::on_stream_start() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_stream_start == nullptr) {
        return;
    }
    const auto& params = handle_->player()->get_current_stream_params();
    const jint sample_rate = static_cast<jint>(params.sample_rate.value_or(0));
    const jint channels = static_cast<jint>(params.channels.value_or(0));
    const jint bit_depth = static_cast<jint>(params.bit_depth.value_or(0));
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_stream_start, sample_rate,
                        channels, bit_depth);
}

void AndroidPlayerListener::on_stream_end() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_stream_end == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_stream_end);
}

void AndroidPlayerListener::on_volume_changed(uint8_t volume) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_volume_changed == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_volume_changed,
                        static_cast<jint>(volume));
}

void AndroidPlayerListener::on_mute_changed(bool muted) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_mute_changed == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_mute_changed,
                        static_cast<jboolean>(muted));
}

void AndroidPlayerListener::on_static_delay_changed(uint16_t delay_ms) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_static_delay_changed == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_static_delay_changed,
                        static_cast<jint>(delay_ms));
}

}  // namespace sendspin_jni
