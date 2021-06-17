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

#define LOG_TAG "VelocityTracker-JNI"

#include <android_runtime/AndroidRuntime.h>
#include <cutils/properties.h>
#include <input/Input.h>
#include <input/VelocityTracker.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>
#include "android_view_MotionEvent.h"
#include "core_jni_helpers.h"

namespace android {

// Special constant to request the velocity of the active pointer.
static const int ACTIVE_POINTER_ID = -1;

static struct {
    jfieldID xCoeff;
    jfieldID yCoeff;
    jfieldID degree;
    jfieldID confidence;
} gEstimatorClassInfo;


// --- VelocityTrackerState ---

class VelocityTrackerState {
public:
    explicit VelocityTrackerState(const VelocityTracker::Strategy strategy);

    void clear();
    void addMovement(const MotionEvent* event);
    void computeCurrentVelocity(int32_t units, float maxVelocity);
    void getVelocity(int32_t id, float* outVx, float* outVy);
    bool getEstimator(int32_t id, VelocityTracker::Estimator* outEstimator);

private:
    struct Velocity {
        float vx, vy;
    };

    VelocityTracker mVelocityTracker;
    int32_t mActivePointerId;
    BitSet32 mCalculatedIdBits;
    Velocity mCalculatedVelocity[MAX_POINTERS];
};

VelocityTrackerState::VelocityTrackerState(const VelocityTracker::Strategy strategy)
      : mVelocityTracker(strategy), mActivePointerId(-1) {}

void VelocityTrackerState::clear() {
    mVelocityTracker.clear();
    mActivePointerId = -1;
    mCalculatedIdBits.clear();
}

void VelocityTrackerState::addMovement(const MotionEvent* event) {
    mVelocityTracker.addMovement(event);
}

void VelocityTrackerState::computeCurrentVelocity(int32_t units, float maxVelocity) {
    BitSet32 idBits(mVelocityTracker.getCurrentPointerIdBits());
    mCalculatedIdBits = idBits;

    for (uint32_t index = 0; !idBits.isEmpty(); index++) {
        uint32_t id = idBits.clearFirstMarkedBit();

        float vx, vy;
        mVelocityTracker.getVelocity(id, &vx, &vy);

        vx = vx * units / 1000;
        vy = vy * units / 1000;

        if (vx > maxVelocity) {
            vx = maxVelocity;
        } else if (vx < -maxVelocity) {
            vx = -maxVelocity;
        }
        if (vy > maxVelocity) {
            vy = maxVelocity;
        } else if (vy < -maxVelocity) {
            vy = -maxVelocity;
        }

        Velocity& velocity = mCalculatedVelocity[index];
        velocity.vx = vx;
        velocity.vy = vy;
    }
}

void VelocityTrackerState::getVelocity(int32_t id, float* outVx, float* outVy) {
    if (id == ACTIVE_POINTER_ID) {
        id = mVelocityTracker.getActivePointerId();
    }

    float vx, vy;
    if (id >= 0 && id <= MAX_POINTER_ID && mCalculatedIdBits.hasBit(id)) {
        uint32_t index = mCalculatedIdBits.getIndexOfBit(id);
        const Velocity& velocity = mCalculatedVelocity[index];
        vx = velocity.vx;
        vy = velocity.vy;
    } else {
        vx = 0;
        vy = 0;
    }

    if (outVx) {
        *outVx = vx;
    }
    if (outVy) {
        *outVy = vy;
    }
}

bool VelocityTrackerState::getEstimator(int32_t id, VelocityTracker::Estimator* outEstimator) {
    return mVelocityTracker.getEstimator(id, outEstimator);
}

// Return a strategy enum from integer value.
inline static VelocityTracker::Strategy getStrategyFromInt(const int32_t strategy) {
    if (strategy < static_cast<int32_t>(VelocityTracker::Strategy::MIN) ||
        strategy > static_cast<int32_t>(VelocityTracker::Strategy::MAX)) {
        return VelocityTracker::Strategy::DEFAULT;
    }
    return static_cast<VelocityTracker::Strategy>(strategy);
}

// --- JNI Methods ---

static jlong android_view_VelocityTracker_nativeInitialize(JNIEnv* env, jclass clazz,
                                                           jint strategy) {
    return reinterpret_cast<jlong>(new VelocityTrackerState(getStrategyFromInt(strategy)));
}

static void android_view_VelocityTracker_nativeDispose(JNIEnv* env, jclass clazz, jlong ptr) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    delete state;
}

static void android_view_VelocityTracker_nativeClear(JNIEnv* env, jclass clazz, jlong ptr) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    state->clear();
}

static void android_view_VelocityTracker_nativeAddMovement(JNIEnv* env, jclass clazz, jlong ptr,
        jobject eventObj) {
    const MotionEvent* event = android_view_MotionEvent_getNativePtr(env, eventObj);
    if (!event) {
        ALOGW("nativeAddMovement failed because MotionEvent was finalized.");
        return;
    }

    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    state->addMovement(event);
}

static void android_view_VelocityTracker_nativeComputeCurrentVelocity(JNIEnv* env, jclass clazz,
        jlong ptr, jint units, jfloat maxVelocity) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    state->computeCurrentVelocity(units, maxVelocity);
}

static jfloat android_view_VelocityTracker_nativeGetXVelocity(JNIEnv* env, jclass clazz,
        jlong ptr, jint id) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    float vx;
    state->getVelocity(id, &vx, NULL);
    return vx;
}

static jfloat android_view_VelocityTracker_nativeGetYVelocity(JNIEnv* env, jclass clazz,
        jlong ptr, jint id) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    float vy;
    state->getVelocity(id, NULL, &vy);
    return vy;
}

static jboolean android_view_VelocityTracker_nativeGetEstimator(JNIEnv* env, jclass clazz,
        jlong ptr, jint id, jobject outEstimatorObj) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    VelocityTracker::Estimator estimator;
    bool result = state->getEstimator(id, &estimator);

    jfloatArray xCoeffObj = jfloatArray(env->GetObjectField(outEstimatorObj,
            gEstimatorClassInfo.xCoeff));
    jfloatArray yCoeffObj = jfloatArray(env->GetObjectField(outEstimatorObj,
            gEstimatorClassInfo.yCoeff));

    env->SetFloatArrayRegion(xCoeffObj, 0, VelocityTracker::Estimator::MAX_DEGREE + 1,
            estimator.xCoeff);
    env->SetFloatArrayRegion(yCoeffObj, 0, VelocityTracker::Estimator::MAX_DEGREE + 1,
            estimator.yCoeff);
    env->SetIntField(outEstimatorObj, gEstimatorClassInfo.degree, estimator.degree);
    env->SetFloatField(outEstimatorObj, gEstimatorClassInfo.confidence, estimator.confidence);
    return result;
}

// --- JNI Registration ---

static const JNINativeMethod gVelocityTrackerMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInitialize", "(I)J", (void*)android_view_VelocityTracker_nativeInitialize},
        {"nativeDispose", "(J)V", (void*)android_view_VelocityTracker_nativeDispose},
        {"nativeClear", "(J)V", (void*)android_view_VelocityTracker_nativeClear},
        {"nativeAddMovement", "(JLandroid/view/MotionEvent;)V",
         (void*)android_view_VelocityTracker_nativeAddMovement},
        {"nativeComputeCurrentVelocity", "(JIF)V",
         (void*)android_view_VelocityTracker_nativeComputeCurrentVelocity},
        {"nativeGetXVelocity", "(JI)F", (void*)android_view_VelocityTracker_nativeGetXVelocity},
        {"nativeGetYVelocity", "(JI)F", (void*)android_view_VelocityTracker_nativeGetYVelocity},
        {"nativeGetEstimator", "(JILandroid/view/VelocityTracker$Estimator;)Z",
         (void*)android_view_VelocityTracker_nativeGetEstimator},
};

int register_android_view_VelocityTracker(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/VelocityTracker", gVelocityTrackerMethods,
                                   NELEM(gVelocityTrackerMethods));

    jclass clazz = FindClassOrDie(env, "android/view/VelocityTracker$Estimator");

    gEstimatorClassInfo.xCoeff = GetFieldIDOrDie(env, clazz, "xCoeff", "[F");
    gEstimatorClassInfo.yCoeff = GetFieldIDOrDie(env, clazz, "yCoeff", "[F");
    gEstimatorClassInfo.degree = GetFieldIDOrDie(env, clazz, "degree", "I");
    gEstimatorClassInfo.confidence = GetFieldIDOrDie(env, clazz, "confidence", "F");

    return res;
}

} // namespace android
