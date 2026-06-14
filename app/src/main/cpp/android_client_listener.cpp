#include "android_client_listener.h"

namespace sendspin_jni {

void AndroidClientListener::on_group_update(const sendspin::GroupUpdateObject& group) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_group_update == nullptr) {
        return;
    }

    jstring playback_state = nullptr;
    if (group.playback_state.has_value()) {
        const char* state =
            *group.playback_state == sendspin::SendspinPlaybackState::PLAYING ? "playing" : "stopped";
        playback_state = env->NewStringUTF(state);
    }
    jstring group_id = group.group_id.has_value() ? env->NewStringUTF(group.group_id->c_str()) : nullptr;
    jstring group_name =
        group.group_name.has_value() ? env->NewStringUTF(group.group_name->c_str()) : nullptr;

    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_group_update, playback_state,
                        group_id, group_name);

    if (playback_state != nullptr) env->DeleteLocalRef(playback_state);
    if (group_id != nullptr) env->DeleteLocalRef(group_id);
    if (group_name != nullptr) env->DeleteLocalRef(group_name);
}

void AndroidClientListener::on_time_sync_updated(float error) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_time_sync_updated == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_time_sync_updated,
                        static_cast<jfloat>(error));
}

void AndroidClientListener::on_request_high_performance() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_request_high_performance == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_request_high_performance);
}

void AndroidClientListener::on_release_high_performance() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_release_high_performance == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_release_high_performance);
}

}  // namespace sendspin_jni
