/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_GUI_SENSOR_H
#define ANDROID_GUI_SENSOR_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Flattenable.h>
#include <utils/String8.h>
#include <utils/Timers.h>

#include <hardware/sensors.h>

#include <android/sensor.h>

// ----------------------------------------------------------------------------
// Concrete types for the NDK
struct ASensor { };

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

class Parcel;

// ----------------------------------------------------------------------------

class Sensor : public ASensor, public Flattenable
{
public:
    enum {
        TYPE_ACCELEROMETER  = ASENSOR_TYPE_ACCELEROMETER,
        TYPE_MAGNETIC_FIELD = ASENSOR_TYPE_MAGNETIC_FIELD,
        TYPE_GYROSCOPE      = ASENSOR_TYPE_GYROSCOPE,
        TYPE_LIGHT          = ASENSOR_TYPE_LIGHT,
        TYPE_PROXIMITY      = ASENSOR_TYPE_PROXIMITY
    };

            Sensor();
            Sensor(struct sensor_t const* hwSensor);
    virtual ~Sensor();

    const String8& getName() const;
    const String8& getVendor() const;
    int32_t getHandle() const;
    int32_t getType() const;
    float getMinValue() const;
    float getMaxValue() const;
    float getResolution() const;
    float getPowerUsage() const;
    int32_t getMinDelay() const;
    nsecs_t getMinDelayNs() const;
    int32_t getVersion() const;

    // Flattenable interface
    virtual size_t getFlattenedSize() const;
    virtual size_t getFdCount() const;
    virtual status_t flatten(void* buffer, size_t size,
            int fds[], size_t count) const;
    virtual status_t unflatten(void const* buffer, size_t size,
            int fds[], size_t count);

private:
    String8 mName;
    String8 mVendor;
    int32_t mHandle;
    int32_t mType;
    float   mMinValue;
    float   mMaxValue;
    float   mResolution;
    float   mPower;
    int32_t mMinDelay;
    int32_t mVersion;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_SENSOR_H
