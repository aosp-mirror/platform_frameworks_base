/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_AUDIO_TRACK_CALLBACK_H
#define ANDROID_MEDIA_AUDIO_TRACK_CALLBACK_H

#include <android/media/BnAudioTrackCallback.h>

namespace android {

#define AUDIO_NATIVE_EVENT_CODEC_FORMAT_CHANGE 100

// TODO(b/149870866) : Extract common part for JNIAudioTrackCallback and JNIDeviceCallback
class JNIAudioTrackCallback : public media::BnAudioTrackCallback {
public:
    JNIAudioTrackCallback(JNIEnv* env, jobject thiz, jobject weak_thiz,
                          jmethodID postEventFromNative);
    ~JNIAudioTrackCallback() override;

    binder::Status onCodecFormatChanged(const std::vector<uint8_t>& audioMetadata) override;

private:
    jclass mClass;                   // Reference to AudioTrack class
    jobject mObject;                 // Weak ref to AudioTrack Java object to call on
    jmethodID mPostEventFromNative;  // postEventFromNative method ID
    jclass mByteBufferClass;         // Reference to ByteBuffer class
    jmethodID mAllocateDirectMethod; // ByteBuffer.allocateDirect method ID
};

}; // namespace android

#endif // ANDROID_MEDIA_AUDIO_TRACK_CALLBACK_H
