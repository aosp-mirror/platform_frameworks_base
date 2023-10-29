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

#include <android-base/logging.h>
#include <android_runtime/AndroidRuntime.h>
#include <cutils/properties.h>
#include <input/Input.h>
#include <input/VelocityTracker.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

#include "android_view_MotionEvent.h"
#include "core_jni_helpers.h"

namespace android {

// Special constant to request the velocity of the active pointer.
static const int ACTIVE_POINTER_ID = -1;

// --- VelocityTrackerState ---

class VelocityTrackerState {
public:
    explicit VelocityTrackerState(const VelocityTracker::Strategy strategy);

    void clear();
    void addMovement(const MotionEvent& event);
    // TODO(b/32830165): consider supporting an overload that supports computing velocity only for
    // a subset of the supported axes.
    void computeCurrentVelocity(int32_t units, float maxVelocity);
    float getVelocity(int32_t axis, int32_t id);

private:
    VelocityTracker mVelocityTracker;
    VelocityTracker::ComputedVelocity mComputedVelocity;
};

VelocityTrackerState::VelocityTrackerState(const VelocityTracker::Strategy strategy)
      : mVelocityTracker(strategy) {}

void VelocityTrackerState::clear() {
    mVelocityTracker.clear();
}

void VelocityTrackerState::addMovement(const MotionEvent& event) {
    mVelocityTracker.addMovement(event);
}

void VelocityTrackerState::computeCurrentVelocity(int32_t units, float maxVelocity) {
    mComputedVelocity = mVelocityTracker.getComputedVelocity(units, maxVelocity);
}

float VelocityTrackerState::getVelocity(int32_t axis, int32_t id) {
    if (id == ACTIVE_POINTER_ID) {
        id = mVelocityTracker.getActivePointerId();
    }

    return mComputedVelocity.getVelocity(axis, id).value_or(0);
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
    if (event == nullptr) {
        LOG(WARNING) << "nativeAddMovement failed because MotionEvent was finalized.";
        return;
    }

    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    state->addMovement(*event);
}

static void android_view_VelocityTracker_nativeComputeCurrentVelocity(JNIEnv* env, jclass clazz,
        jlong ptr, jint units, jfloat maxVelocity) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    state->computeCurrentVelocity(units, maxVelocity);
}

static jfloat android_view_VelocityTracker_nativeGetVelocity(JNIEnv* env, jclass clazz, jlong ptr,
                                                             jint axis, jint id) {
    VelocityTrackerState* state = reinterpret_cast<VelocityTrackerState*>(ptr);
    return state->getVelocity(axis, id);
}

static jboolean android_view_VelocityTracker_nativeIsAxisSupported(JNIEnv* env, jclass clazz,
                                                                   jint axis) {
    return VelocityTracker::isAxisSupported(axis);
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
        {"nativeGetVelocity", "(JII)F", (void*)android_view_VelocityTracker_nativeGetVelocity},
        {"nativeIsAxisSupported", "(I)Z",
         (void*)android_view_VelocityTracker_nativeIsAxisSupported},
};

int register_android_view_VelocityTracker(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/view/VelocityTracker", gVelocityTrackerMethods,
                                NELEM(gVelocityTrackerMethods));
}

} // namespace android
