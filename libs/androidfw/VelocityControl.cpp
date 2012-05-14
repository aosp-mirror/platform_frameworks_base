/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "VelocityControl"
//#define LOG_NDEBUG 0

// Log debug messages about acceleration.
#define DEBUG_ACCELERATION 0

#include <math.h>
#include <limits.h>

#include <androidfw/VelocityControl.h>
#include <utils/BitSet.h>
#include <utils/Timers.h>

namespace android {

// --- VelocityControl ---

const nsecs_t VelocityControl::STOP_TIME;

VelocityControl::VelocityControl() {
    reset();
}

void VelocityControl::setParameters(const VelocityControlParameters& parameters) {
    mParameters = parameters;
    reset();
}

void VelocityControl::reset() {
    mLastMovementTime = LLONG_MIN;
    mRawPosition.x = 0;
    mRawPosition.y = 0;
    mVelocityTracker.clear();
}

void VelocityControl::move(nsecs_t eventTime, float* deltaX, float* deltaY) {
    if ((deltaX && *deltaX) || (deltaY && *deltaY)) {
        if (eventTime >= mLastMovementTime + STOP_TIME) {
#if DEBUG_ACCELERATION
            ALOGD("VelocityControl: stopped, last movement was %0.3fms ago",
                    (eventTime - mLastMovementTime) * 0.000001f);
#endif
            reset();
        }

        mLastMovementTime = eventTime;
        if (deltaX) {
            mRawPosition.x += *deltaX;
        }
        if (deltaY) {
            mRawPosition.y += *deltaY;
        }
        mVelocityTracker.addMovement(eventTime, BitSet32(BitSet32::valueForBit(0)), &mRawPosition);

        float vx, vy;
        float scale = mParameters.scale;
        if (mVelocityTracker.getVelocity(0, &vx, &vy)) {
            float speed = hypotf(vx, vy) * scale;
            if (speed >= mParameters.highThreshold) {
                // Apply full acceleration above the high speed threshold.
                scale *= mParameters.acceleration;
            } else if (speed > mParameters.lowThreshold) {
                // Linearly interpolate the acceleration to apply between the low and high
                // speed thresholds.
                scale *= 1 + (speed - mParameters.lowThreshold)
                        / (mParameters.highThreshold - mParameters.lowThreshold)
                        * (mParameters.acceleration - 1);
            }

#if DEBUG_ACCELERATION
            ALOGD("VelocityControl(%0.3f, %0.3f, %0.3f, %0.3f): "
                    "vx=%0.3f, vy=%0.3f, speed=%0.3f, accel=%0.3f",
                    mParameters.scale, mParameters.lowThreshold, mParameters.highThreshold,
                    mParameters.acceleration,
                    vx, vy, speed, scale / mParameters.scale);
#endif
        } else {
#if DEBUG_ACCELERATION
            ALOGD("VelocityControl(%0.3f, %0.3f, %0.3f, %0.3f): unknown velocity",
                    mParameters.scale, mParameters.lowThreshold, mParameters.highThreshold,
                    mParameters.acceleration);
#endif
        }

        if (deltaX) {
            *deltaX *= scale;
        }
        if (deltaY) {
            *deltaY *= scale;
        }
    }
}

} // namespace android
