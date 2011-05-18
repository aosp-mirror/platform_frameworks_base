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

#include "SensorDevice.h"
#include "SensorFusion.h"
#include "SensorService.h"

namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE(SensorFusion)

SensorFusion::SensorFusion()
    : mSensorDevice(SensorDevice::getInstance()),
      mEnabled(false), mHasGyro(false), mGyroTime(0), mRotationMatrix(1),
      mLowPass(M_SQRT1_2, 1.0f), mAccData(mLowPass),
      mFilteredMag(0.0f), mFilteredAcc(0.0f)
{
    sensor_t const* list;
    size_t count = mSensorDevice.getSensorList(&list);
    for (size_t i=0 ; i<count ; i++) {
        if (list[i].type == SENSOR_TYPE_ACCELEROMETER) {
            mAcc = Sensor(list + i);
        }
        if (list[i].type == SENSOR_TYPE_MAGNETIC_FIELD) {
            mMag = Sensor(list + i);
        }
        if (list[i].type == SENSOR_TYPE_GYROSCOPE) {
            mGyro = Sensor(list + i);
            // 200 Hz for gyro events is a good compromise between precision
            // and power/cpu usage.
            mTargetDelayNs = 1000000000LL/200;
            mGyroRate = 1000000000.0f / mTargetDelayNs;
            mHasGyro = true;
        }
    }
    mFusion.init();
    mAccData.init(vec3_t(0.0f));
}

void SensorFusion::process(const sensors_event_t& event) {

    if (event.type == SENSOR_TYPE_GYROSCOPE && mHasGyro) {
        if (mGyroTime != 0) {
            const float dT = (event.timestamp - mGyroTime) / 1000000000.0f;
            const float freq = 1 / dT;
            const float alpha = 2 / (2 + dT); // 2s time-constant
            mGyroRate = mGyroRate*alpha +  freq*(1 - alpha);
        }
        mGyroTime = event.timestamp;
        mFusion.handleGyro(vec3_t(event.data), 1.0f/mGyroRate);
    } else if (event.type == SENSOR_TYPE_MAGNETIC_FIELD) {
        const vec3_t mag(event.data);
        if (mHasGyro) {
            mFusion.handleMag(mag);
        } else {
            const float l(length(mag));
            if (l>5 && l<100) {
                mFilteredMag = mag * (1/l);
            }
        }
    } else if (event.type == SENSOR_TYPE_ACCELEROMETER) {
        const vec3_t acc(event.data);
        if (mHasGyro) {
            mFusion.handleAcc(acc);
            mRotationMatrix = mFusion.getRotationMatrix();
        } else {
            const float l(length(acc));
            if (l > 0.981f) {
                // remove the linear-acceleration components
                mFilteredAcc = mAccData(acc * (1/l));
            }
            if (length(mFilteredAcc)>0 && length(mFilteredMag)>0) {
                vec3_t up(mFilteredAcc);
                vec3_t east(cross_product(mFilteredMag, up));
                east *= 1/length(east);
                vec3_t north(cross_product(up, east));
                mRotationMatrix << east << north << up;
            }
        }
    }
}

template <typename T> inline T min(T a, T b) { return a<b ? a : b; }
template <typename T> inline T max(T a, T b) { return a>b ? a : b; }

status_t SensorFusion::activate(void* ident, bool enabled) {

    LOGD_IF(DEBUG_CONNECTIONS,
            "SensorFusion::activate(ident=%p, enabled=%d)",
            ident, enabled);

    const ssize_t idx = mClients.indexOf(ident);
    if (enabled) {
        if (idx < 0) {
            mClients.add(ident);
        }
    } else {
        if (idx >= 0) {
            mClients.removeItemsAt(idx);
        }
    }

    mSensorDevice.activate(ident, mAcc.getHandle(), enabled);
    mSensorDevice.activate(ident, mMag.getHandle(), enabled);
    if (mHasGyro) {
        mSensorDevice.activate(ident, mGyro.getHandle(), enabled);
    }

    const bool newState = mClients.size() != 0;
    if (newState != mEnabled) {
        mEnabled = newState;
        if (newState) {
            mFusion.init();
        }
    }
    return NO_ERROR;
}

status_t SensorFusion::setDelay(void* ident, int64_t ns) {
    if (mHasGyro) {
        mSensorDevice.setDelay(ident, mAcc.getHandle(), ns);
        mSensorDevice.setDelay(ident, mMag.getHandle(), ms2ns(20));
        mSensorDevice.setDelay(ident, mGyro.getHandle(), mTargetDelayNs);
    } else {
        const static double NS2S = 1.0 / 1000000000.0;
        mSensorDevice.setDelay(ident, mAcc.getHandle(), ns);
        mSensorDevice.setDelay(ident, mMag.getHandle(), max(ns, mMag.getMinDelayNs()));
        mLowPass.setSamplingPeriod(ns*NS2S);
    }
    return NO_ERROR;
}


float SensorFusion::getPowerUsage() const {
    float power = mAcc.getPowerUsage() + mMag.getPowerUsage();
    if (mHasGyro) {
        power += mGyro.getPowerUsage();
    }
    return power;
}

int32_t SensorFusion::getMinDelay() const {
    return mAcc.getMinDelay();
}

void SensorFusion::dump(String8& result, char* buffer, size_t SIZE) {
    const Fusion& fusion(mFusion);
    snprintf(buffer, SIZE, "Fusion (%s) %s (%d clients), gyro-rate=%7.2fHz, "
            "MRPS=< %g, %g, %g > (%g), "
            "BIAS=< %g, %g, %g >\n",
            mHasGyro ? "9-axis" : "6-axis",
            mEnabled ? "enabled" : "disabled",
            mClients.size(),
            mGyroRate,
            fusion.getAttitude().x,
            fusion.getAttitude().y,
            fusion.getAttitude().z,
            dot_product(fusion.getAttitude(), fusion.getAttitude()),
            fusion.getBias().x,
            fusion.getBias().y,
            fusion.getBias().z);
    result.append(buffer);
}

// ---------------------------------------------------------------------------
}; // namespace android
