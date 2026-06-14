// PlayerRoleListener implementation that forwards decoded PCM and player events to Kotlin.

#pragma once

#include "jni_bridge.h"
#include "sendspin/player_role.h"

namespace sendspin_jni {

class AndroidPlayerListener : public sendspin::PlayerRoleListener {
public:
    explicit AndroidPlayerListener(ClientHandle* handle) : handle_(handle) {}

    // Fires on the sync task background thread.
    size_t on_audio_write(uint8_t* data, size_t length, uint32_t timeout_ms) override;

    // The remaining callbacks fire on the main loop thread.
    void on_stream_start() override;
    void on_stream_end() override;
    void on_volume_changed(uint8_t volume) override;
    void on_mute_changed(bool muted) override;
    void on_static_delay_changed(uint16_t delay_ms) override;

private:
    ClientHandle* handle_;
};

}  // namespace sendspin_jni
