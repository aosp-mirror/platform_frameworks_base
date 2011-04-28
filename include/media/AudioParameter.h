/*
 * Copyright (C) 2008-2011 The Android Open Source Project
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

#ifndef ANDROID_AUDIOPARAMETER_H_
#define ANDROID_AUDIOPARAMETER_H_

#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>

namespace android {

class AudioParameter {

public:
    AudioParameter() {}
    AudioParameter(const String8& keyValuePairs);
    virtual ~AudioParameter();

    // reserved parameter keys for changing standard parameters with setParameters() function.
    // Using these keys is mandatory for AudioFlinger to properly monitor audio output/input
    // configuration changes and act accordingly.
    //  keyRouting: to change audio routing, value is an int in audio_devices_t
    //  keySamplingRate: to change sampling rate routing, value is an int
    //  keyFormat: to change audio format, value is an int in audio_format_t
    //  keyChannels: to change audio channel configuration, value is an int in audio_channels_t
    //  keyFrameCount: to change audio output frame count, value is an int
    //  keyInputSource: to change audio input source, value is an int in audio_source_t
    //     (defined in media/mediarecorder.h)
    static const char *keyRouting;
    static const char *keySamplingRate;
    static const char *keyFormat;
    static const char *keyChannels;
    static const char *keyFrameCount;
    static const char *keyInputSource;

    String8 toString();

    status_t add(const String8& key, const String8& value);
    status_t addInt(const String8& key, const int value);
    status_t addFloat(const String8& key, const float value);

    status_t remove(const String8& key);

    status_t get(const String8& key, String8& value);
    status_t getInt(const String8& key, int& value);
    status_t getFloat(const String8& key, float& value);
    status_t getAt(size_t index, String8& key, String8& value);

    size_t size() { return mParameters.size(); }

private:
    String8 mKeyValuePairs;
    KeyedVector <String8, String8> mParameters;
};

};  // namespace android

#endif  /*ANDROID_AUDIOPARAMETER_H_*/
