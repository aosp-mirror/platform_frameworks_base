#ifndef ANDROIDTVREMOTE_SERVICE_JNI_KEYS_H_
#define ANDROIDTVREMOTE_SERVICE_JNI_KEYS_H_

#include <android/keycodes.h>
#include <linux/input.h>

namespace android {

// Map the keys specified in virtual-remote.kl.
// Only specify the keys actually used in the layout here.
struct Key {
    int linuxKeyCode;
    int32_t androidKeyCode;
};

// List of all of the keycodes that the emote is capable of sending.
static Key KEYS[] = {
        // Volume Control
        {KEY_VOLUMEDOWN, AKEYCODE_VOLUME_DOWN},
        {KEY_VOLUMEUP, AKEYCODE_VOLUME_UP},
        {KEY_MUTE, AKEYCODE_VOLUME_MUTE},
        {KEY_MUTE, AKEYCODE_MUTE},

        {KEY_POWER, AKEYCODE_POWER},
        {KEY_HOMEPAGE, AKEYCODE_HOME},
        {KEY_BACK, AKEYCODE_BACK},

        // Media Control
        {KEY_PLAYPAUSE, AKEYCODE_MEDIA_PLAY_PAUSE},
        {KEY_PLAY, AKEYCODE_MEDIA_PLAY},
        {KEY_PAUSECD, AKEYCODE_MEDIA_PAUSE},
        {KEY_NEXTSONG, AKEYCODE_MEDIA_NEXT},
        {KEY_PREVIOUSSONG, AKEYCODE_MEDIA_PREVIOUS},
        {KEY_STOPCD, AKEYCODE_MEDIA_STOP},
        {KEY_RECORD, AKEYCODE_MEDIA_RECORD},
        {KEY_REWIND, AKEYCODE_MEDIA_REWIND},
        {KEY_FASTFORWARD, AKEYCODE_MEDIA_FAST_FORWARD},

        // TV Control
        {KEY_0, AKEYCODE_0},
        {KEY_1, AKEYCODE_1},
        {KEY_2, AKEYCODE_2},
        {KEY_3, AKEYCODE_3},
        {KEY_4, AKEYCODE_4},
        {KEY_5, AKEYCODE_5},
        {KEY_6, AKEYCODE_6},
        {KEY_7, AKEYCODE_7},
        {KEY_8, AKEYCODE_8},
        {KEY_9, AKEYCODE_9},
        {KEY_BACKSPACE, AKEYCODE_DEL},
        {KEY_ENTER, AKEYCODE_ENTER},
        {KEY_CHANNELUP, AKEYCODE_CHANNEL_UP},
        {KEY_CHANNELDOWN, AKEYCODE_CHANNEL_DOWN},

        // Old School TV Controls
        {KEY_F1, AKEYCODE_F1},
        {KEY_F2, AKEYCODE_F2},
        {KEY_F3, AKEYCODE_F3},
        {KEY_F4, AKEYCODE_F4},
        {KEY_F5, AKEYCODE_F5},
        {KEY_F6, AKEYCODE_F6},
        {KEY_F7, AKEYCODE_F7},
        {KEY_F8, AKEYCODE_F8},
        {KEY_F9, AKEYCODE_F9},
        {KEY_F10, AKEYCODE_F10},
        {KEY_F11, AKEYCODE_F11},
        {KEY_F12, AKEYCODE_F12},
        {KEY_FN_F1, AKEYCODE_F1},
        {KEY_FN_F2, AKEYCODE_F2},
        {KEY_FN_F3, AKEYCODE_F3},
        {KEY_FN_F4, AKEYCODE_F4},
        {KEY_FN_F5, AKEYCODE_F5},
        {KEY_FN_F6, AKEYCODE_F6},
        {KEY_FN_F7, AKEYCODE_F7},
        {KEY_FN_F8, AKEYCODE_F8},
        {KEY_FN_F9, AKEYCODE_F9},
        {KEY_FN_F10, AKEYCODE_F10},
        {KEY_FN_F11, AKEYCODE_F11},
        {KEY_FN_F12, AKEYCODE_F12},
        {KEY_TV, AKEYCODE_TV},
        {KEY_RED, AKEYCODE_PROG_RED},
        {KEY_GREEN, AKEYCODE_PROG_GREEN},
        {KEY_YELLOW, AKEYCODE_PROG_YELLOW},
        {KEY_BLUE, AKEYCODE_PROG_BLUE},

        {KEY_FAVORITES, AKEYCODE_BUTTON_MODE},
        {KEY_WWW, AKEYCODE_EXPLORER},
        {KEY_MENU, AKEYCODE_MENU},
        {KEY_INFO, AKEYCODE_INFO},
        {KEY_EPG, AKEYCODE_GUIDE},
        {KEY_TEXT, AKEYCODE_TV_TELETEXT},
        {KEY_SUBTITLE, AKEYCODE_CAPTIONS},
        {KEY_PVR, AKEYCODE_DVR},
        {KEY_VIDEO, AKEYCODE_TV_INPUT},
        {KEY_AUDIO, AKEYCODE_MEDIA_AUDIO_TRACK},
        {KEY_AUDIO_DESC, AKEYCODE_TV_AUDIO_DESCRIPTION},
        {KEY_OPTION, AKEYCODE_SETTINGS},
        {KEY_DOT,  AKEYCODE_PERIOD},

        // Gamepad buttons
        {KEY_UP, AKEYCODE_DPAD_UP},
        {KEY_DOWN, AKEYCODE_DPAD_DOWN},
        {KEY_LEFT, AKEYCODE_DPAD_LEFT},
        {KEY_RIGHT, AKEYCODE_DPAD_RIGHT},
        {KEY_SELECT, AKEYCODE_DPAD_CENTER},
        {BTN_A, AKEYCODE_BUTTON_A},
        {BTN_B, AKEYCODE_BUTTON_B},
        {BTN_X, AKEYCODE_BUTTON_X},
        {BTN_Y, AKEYCODE_BUTTON_Y},

        {KEY_SEARCH, AKEYCODE_SEARCH},
        {KEY_ASSISTANT, AKEYCODE_ASSIST},
};

} // namespace android

#endif // ANDROIDTVREMOTE_SERVICE_JNI_KEYS_H_
