#include "android_controller_listener.h"

namespace sendspin_jni {

namespace {

const char* ControllerCommandToString(sendspin::SendspinControllerCommand cmd) {
    switch (cmd) {
        case sendspin::SendspinControllerCommand::PLAY:
            return "play";
        case sendspin::SendspinControllerCommand::PAUSE:
            return "pause";
        case sendspin::SendspinControllerCommand::STOP:
            return "stop";
        case sendspin::SendspinControllerCommand::NEXT:
            return "next";
        case sendspin::SendspinControllerCommand::PREVIOUS:
            return "previous";
        case sendspin::SendspinControllerCommand::VOLUME:
            return "volume";
        case sendspin::SendspinControllerCommand::MUTE:
            return "mute";
        case sendspin::SendspinControllerCommand::REPEAT_OFF:
            return "repeat_off";
        case sendspin::SendspinControllerCommand::REPEAT_ONE:
            return "repeat_one";
        case sendspin::SendspinControllerCommand::REPEAT_ALL:
            return "repeat_all";
        case sendspin::SendspinControllerCommand::SHUFFLE:
            return "shuffle";
        case sendspin::SendspinControllerCommand::UNSHUFFLE:
            return "unshuffle";
        case sendspin::SendspinControllerCommand::SWITCH:
            return "switch";
        default:
            return "unknown";
    }
}

}  // namespace

void AndroidControllerListener::on_controller_state(
    const sendspin::ServerStateControllerObject& state) {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_controller_state == nullptr) {
        return;
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return;
    }

    const jsize command_count = static_cast<jsize>(state.supported_commands.size());
    jobjectArray commands =
        env->NewObjectArray(command_count, string_class, nullptr);
    for (jsize i = 0; i < command_count; ++i) {
        const char* cmd = ControllerCommandToString(state.supported_commands[static_cast<size_t>(i)]);
        jstring value = env->NewStringUTF(cmd);
        env->SetObjectArrayElement(commands, i, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(string_class);

    const char* repeat =
        state.repeat == sendspin::SendspinRepeatMode::ONE
            ? "one"
            : (state.repeat == sendspin::SendspinRepeatMode::ALL ? "all" : "off");
    jstring repeat_mode = env->NewStringUTF(repeat);

    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_controller_state, commands,
                        static_cast<jint>(state.volume), static_cast<jboolean>(state.muted),
                        repeat_mode, static_cast<jboolean>(state.shuffle));

    env->DeleteLocalRef(commands);
    env->DeleteLocalRef(repeat_mode);
}

void AndroidControllerListener::on_controller_state_clear() {
    JNIEnv* env = GetEnv();
    if (env == nullptr || handle_->methods().on_controller_state_clear == nullptr) {
        return;
    }
    env->CallVoidMethod(handle_->callbacks(), handle_->methods().on_controller_state_clear);
}

}  // namespace sendspin_jni
