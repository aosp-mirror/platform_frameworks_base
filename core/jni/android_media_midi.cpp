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

#define LOG_TAG "NativeMIDI_JNI"

#include <core_jni_helpers.h>

namespace android { namespace midi {
//  MidiDevice Fields
static jobject gMidiDeviceClassGlobalRef = nullptr;     // A GlobalRef for MidiDevice Class
jfieldID gFidMidiNativeHandle = nullptr;         // MidiDevice.mNativeHandle
jfieldID gFidMidiDeviceServerBinder = nullptr;   // MidiDevice.mDeviceServerBinder
jfieldID gFidMidiDeviceInfo = nullptr;           // MidiDevice.mDeviceInfo

//  MidiDeviceInfo Fields
static jobject gMidiDeviceInfoClassGlobalRef = nullptr; // A GlobalRef for MidiDeviceInfoClass
jfieldID mFidMidiDeviceId = nullptr;             // MidiDeviceInfo.mId
}}

using namespace android::midi;

int register_android_media_midi(JNIEnv *env) {
    jclass deviceClass = android::FindClassOrDie(env, "android/media/midi/MidiDevice");
    gMidiDeviceClassGlobalRef = env->NewGlobalRef(deviceClass);

    // MidiDevice Field IDs
    gFidMidiNativeHandle = android::GetFieldIDOrDie(env, deviceClass, "mNativeHandle", "J");
    gFidMidiDeviceServerBinder = android::GetFieldIDOrDie(env, deviceClass,
            "mDeviceServerBinder", "Landroid/os/IBinder;");
    gFidMidiDeviceInfo = android::GetFieldIDOrDie(env, deviceClass,
            "mDeviceInfo", "Landroid/media/midi/MidiDeviceInfo;");

    // MidiDeviceInfo Field IDs
    jclass deviceInfoClass = android::FindClassOrDie(env, "android/media/midi/MidiDeviceInfo");
    gMidiDeviceInfoClassGlobalRef = env->NewGlobalRef(deviceInfoClass);
    mFidMidiDeviceId = android::GetFieldIDOrDie(env, deviceInfoClass, "mId", "I");

    return 0;
}
