#include "android_metadata_listener.h"

namespace sendspin_jni {

namespace {

// Returns a new local-ref jstring for an optional, or nullptr when unset.
jstring OptString(JNIEnv* env, const std::optional<std::string>& value) {
    if (!value.has_value()) {
        return nullptr;
    }
    return env->NewStringUTF(value->c_str());
}

}  // namespace

void AndroidMetadataListener::on_metadata(const sendspin::ServerMetadataStateObject& metadata) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_metadata_update == nullptr) {
        return;
    }

    jstring title = OptString(env, metadata.title);
    jstring artist = OptString(env, metadata.artist);
    jstring album = OptString(env, metadata.album);
    jstring album_artist = OptString(env, metadata.album_artist);
    jstring artwork_url = OptString(env, metadata.artwork_url);

    // Sentinel -1 marks an absent value on the Kotlin side.
    const jint year = metadata.year.has_value() ? static_cast<jint>(*metadata.year) : -1;
    const jint track = metadata.track.has_value() ? static_cast<jint>(*metadata.track) : -1;
    jint progress_ms = -1;
    jint duration_ms = -1;
    if (metadata.progress.has_value()) {
        progress_ms = static_cast<jint>(metadata.progress->track_progress);
        duration_ms = static_cast<jint>(metadata.progress->track_duration);
    }

    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_metadata_update, title, artist,
                        album, album_artist, artwork_url, year, track, progress_ms, duration_ms);

    if (title != nullptr) env->DeleteLocalRef(title);
    if (artist != nullptr) env->DeleteLocalRef(artist);
    if (album != nullptr) env->DeleteLocalRef(album);
    if (album_artist != nullptr) env->DeleteLocalRef(album_artist);
    if (artwork_url != nullptr) env->DeleteLocalRef(artwork_url);
}

void AndroidMetadataListener::on_metadata_clear() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_metadata_clear == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_metadata_clear);
}

}  // namespace sendspin_jni
