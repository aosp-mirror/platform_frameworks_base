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
#include <midi/MidiDeviceRegistry.h>
#include <nativehelper/jni.h>
#include <utils/Log.h>

using namespace android;
using namespace android::media::midi;

extern "C" jint Java_android_media_midi_MidiDevice_mirrorToNative(
        JNIEnv *env, jobject thiz, jobject midiDeviceServer, jint id)
{
    (void)thiz;
    sp<IBinder> serverBinder = ibinderForJavaObject(env, midiDeviceServer);
    if (serverBinder.get() == NULL) {
        ALOGE("Could not obtain IBinder from passed jobject");
        return -EINVAL;
    }
    // return MidiDeviceManager::getInstance().addDevice(serverBinder, uid);
    return MidiDeviceRegistry::getInstance().addDevice(
               new BpMidiDeviceServer(serverBinder), id);
}

extern "C" jint Java_android_media_midi_MidiDevice_removeFromNative(
        JNIEnv *env, jobject thiz, jint uid)
{
    (void)env;
    (void)thiz;
    // return MidiDeviceManager::getInstance().removeDevice(uid);
    return MidiDeviceRegistry::getInstance().removeDevice(uid);
}
