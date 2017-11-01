/*
 * Copyright (C) 2017 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "Midi-JNI"

#include <android_util_Binder.h>
#include <jni.h>
#include <midi_internal.h>
#include <utils/Log.h>

using namespace android;
using namespace android::media::midi;

extern "C" jlong Java_android_media_midi_MidiDevice_native_1mirrorToNative(
        JNIEnv *env, jobject, jobject midiDeviceServer, jint id)
{
    // ALOGI("native_mirrorToNative(%p)...", midiDeviceServer);
    sp<IBinder> serverBinder = ibinderForJavaObject(env, midiDeviceServer);
    if (serverBinder.get() == NULL) {
        ALOGE("Could not obtain IBinder from passed jobject");
        return -EINVAL;
    }

    AMIDI_Device* devicePtr = new AMIDI_Device;
    devicePtr->server = new BpMidiDeviceServer(serverBinder);
    devicePtr->deviceId = id;

    return (jlong)devicePtr;
}

extern "C" void Java_android_media_midi_MidiDevice_native_removeFromNative(
        JNIEnv *, jobject , jlong nativeToken)
{
    AMIDI_Device* devicePtr = (AMIDI_Device*)nativeToken;
    delete devicePtr;
}
