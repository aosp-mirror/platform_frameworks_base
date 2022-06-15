/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_MEDIA_AMIDI_INTERNAL_H_
#define ANDROID_MEDIA_AMIDI_INTERNAL_H_

#include <jni.h>

#include "android/media/midi/BpMidiDeviceServer.h"

typedef struct {
    int32_t type;            /* one of AMIDI_DEVICE_TYPE_* constants */
    int32_t inputPortCount;  /* number of input (send) ports associated with the device */
    int32_t outputPortCount; /* number of output (received) ports associated with the device */
} AMidiDeviceInfo;

struct AMidiDevice {
    android::sp<android::media::midi::BpMidiDeviceServer>
        server;             /* The Binder interface to the MIDI server (from the Java MidiDevice) */
    int32_t deviceId;       /* The integer id of the device assigned in the Java API */
    JavaVM* javaVM;         /* The Java VM (so we can obtain the JNIEnv in the
                               AMidiDevice_close function) */
    jobject midiDeviceObj;  /* NewGlobalRef() reference to the Java MidiDevice associated with
                               this native AMidiDevice. */
    AMidiDeviceInfo deviceInfo; /* Attributes of the device. */
};

#endif // ANDROID_MEDIA_AMIDI_INTERNAL_H_
