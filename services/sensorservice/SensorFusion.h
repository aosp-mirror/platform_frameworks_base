/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_SENSOR_FUSION_H
#define ANDROID_SENSOR_FUSION_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/SortedVector.h>
#include <utils/Singleton.h>
#include <utils/String8.h>

#include <gui/Sensor.h>

#include "Fusion.h"

// ---------------------------------------------------------------------------

namespace android {
// ---------------------------------------------------------------------------

class SensorDevice;

class SensorFusion : public Singleton<SensorFusion> {
    friend class Singleton<SensorFusion>;

    SensorDevice& mSensorDevice;
    Sensor mAcc;
    Sensor mMag;
    Sensor mGyro;
    Fusion mFusion;
    bool mEnabled;
    float mGyroRate;
    nsecs_t mTargetDelayNs;
    nsecs_t mGyroTime;
    vec4_t mAttitude;
    SortedVector<void*> mClients;

    SensorFusion();

public:
    void process(const sensors_event_t& event);

    bool isEnabled() const { return mEnabled; }
    bool hasEstimate() const { return mFusion.hasEstimate(); }
    mat33_t getRotationMatrix() const { return mFusion.getRotationMatrix(); }
    vec4_t getAttitude() const { return mAttitude; }
    vec3_t getGyroBias() const { return mFusion.getBias(); }
    float getEstimatedRate() const { return mGyroRate; }

    status_t activate(void* ident, bool enabled);
    status_t setDelay(void* ident, int64_t ns);

    float getPowerUsage() const;
    int32_t getMinDelay() const;

    void dump(String8& result, char* buffer, size_t SIZE);
};


// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SENSOR_FUSION_H
