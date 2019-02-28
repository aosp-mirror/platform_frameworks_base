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

#pragma once

#include <system/audio.h>
#include <media/AudioSystem.h>

namespace android {

// keep in sync with AudioManager.AudioVolumeGroupChangeHandler.java
#define AUDIOVOLUMEGROUP_EVENT_VOLUME_CHANGED      1000
#define AUDIOVOLUMEGROUP_EVENT_SERVICE_DIED        1001

class JNIAudioVolumeGroupCallback: public AudioSystem::AudioVolumeGroupCallback
{
public:
    JNIAudioVolumeGroupCallback(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIAudioVolumeGroupCallback();

    void onAudioVolumeGroupChanged(volume_group_t group, int flags) override;
    void onServiceDied() override;

private:
    void sendEvent(int event);

    jclass      mClass; /**< Reference to AudioVolumeGroupChangeHandler class. */
    jobject     mObject; /**< Weak ref to AudioVolumeGroupChangeHandler object to call on. */
};

} // namespace android
