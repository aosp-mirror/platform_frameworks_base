/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_DEVICE_CALLBACK_H
#define ANDROID_MEDIA_DEVICE_CALLBACK_H

#include <system/audio.h>
#include <media/AudioSystem.h>

namespace android {

// keep in sync with AudioSystem.java
#define AUDIO_NATIVE_EVENT_ROUTING_CHANGE      1000

class JNIDeviceCallback: public AudioSystem::AudioDeviceCallback
{
public:
    JNIDeviceCallback(JNIEnv* env, jobject thiz, jobject weak_thiz, jmethodID postEventFromNative);
    ~JNIDeviceCallback();

    virtual void onAudioDeviceUpdate(audio_io_handle_t audioIo,
                                     audio_port_handle_t deviceId);

private:
    void sendEvent(int event);

    jclass      mClass;     // Reference to AudioTrack/AudioRecord class
    jobject     mObject;    // Weak ref to AudioTrack/AudioRecord Java object to call on
    jmethodID   mPostEventFromNative; // postEventFromNative method ID.
};

}; // namespace android

#endif // ANDROID_MEDIA_DEVICE_CALLBACK_H
