/*
 * Copyright 2023 The Android Open Source Project
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
 *
 */

#include <android/choreographer.h>
#include <android/log.h>
#include <android/surface_control_jni.h>
#include <jni.h>
#include <private/surface_control_private.h>
#include <time.h>
#include <utils/Log.h>
#include <utils/Mutex.h>

#include <chrono>
#include <cmath>
#include <condition_variable>
#include <mutex>
#include <thread>

#undef LOG_TAG
#define LOG_TAG "AttachedChoreographerNativeTest"

// Copied from cts/tests/tests/view/jni/jniAssert.h, to be removed when integrated in CTS.
#define ASSERT(condition, format, args...) \
    if (!(condition)) {                    \
        fail(env, format, ##args);         \
        return;                            \
    }

using namespace std::chrono_literals;

static constexpr std::chrono::nanoseconds kMaxRuntime{1s};
static constexpr float kFpsTolerance = 5.0f;

static constexpr int kNumOfFrames = 20;

struct {
    struct {
        jclass clazz;
        jmethodID endTest;
    } attachedChoreographerNativeTest;
} gJni;

struct CallbackData {
    std::mutex mutex;

    // Condition to signal callbacks are done running and test can be verified.
    std::condition_variable_any condition;

    // Flag to ensure not to lock on the condition if notify is called before wait_for.
    bool callbacksComplete = false;

    AChoreographer* choreographer = nullptr;
    int count GUARDED_BY(mutex){0};
    std::chrono::nanoseconds frameTime GUARDED_BY(mutex){0};
    std::chrono::nanoseconds startTime;
    std::chrono::nanoseconds endTime GUARDED_BY(mutex){0};
};

static std::chrono::nanoseconds now() {
    return std::chrono::steady_clock::now().time_since_epoch();
}

static void vsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data) {
    ALOGI("%s: Vsync callback running", __func__);
    long frameTimeNanos = AChoreographerFrameCallbackData_getFrameTimeNanos(callbackData);

    auto* cb = static_cast<CallbackData*>(data);
    {
        std::lock_guard<std::mutex> _l(cb->mutex);
        cb->count++;
        cb->endTime = now();
        cb->frameTime = std::chrono::nanoseconds{frameTimeNanos};

        ALOGI("%s: ran callback now %ld, frameTimeNanos %ld, new count %d", __func__,
              static_cast<long>(cb->endTime.count()), frameTimeNanos, cb->count);
        if (cb->endTime - cb->startTime > kMaxRuntime) {
            cb->callbacksComplete = true;
            cb->condition.notify_all();
            return;
        }
    }

    ALOGI("%s: Posting next callback", __func__);
    AChoreographer_postVsyncCallback(cb->choreographer, vsyncCallback, data);
}

static void fail(JNIEnv* env, const char* format, ...) {
    va_list args;

    va_start(args, format);
    char* msg;
    int rc = vasprintf(&msg, format, args);
    va_end(args);

    jclass exClass;
    const char* className = "java/lang/AssertionError";
    exClass = env->FindClass(className);
    env->ThrowNew(exClass, msg);
    free(msg);
}

jlong SurfaceControl_getChoreographer(JNIEnv* env, jclass, jobject surfaceControlObj) {
    return reinterpret_cast<jlong>(
            ASurfaceControl_getChoreographer(ASurfaceControl_fromJava(env, surfaceControlObj)));
}

static bool frameRateEquals(float fr1, float fr2) {
    return std::abs(fr1 - fr2) <= kFpsTolerance;
}

static void endTest(JNIEnv* env, jobject clazz) {
    env->CallVoidMethod(clazz, gJni.attachedChoreographerNativeTest.endTest);
}

static void android_view_ChoreographerNativeTest_testPostVsyncCallbackAtFrameRate(
        JNIEnv* env, jobject clazz, jlong choreographerPtr, jfloat expectedFrameRate) {
    AChoreographer* choreographer = reinterpret_cast<AChoreographer*>(choreographerPtr);
    CallbackData cb;
    cb.choreographer = choreographer;
    cb.startTime = now();
    ALOGI("%s: Post first callback at %ld", __func__, static_cast<long>(cb.startTime.count()));
    AChoreographer_postVsyncCallback(choreographer, vsyncCallback, &cb);

    std::scoped_lock<std::mutex> conditionLock(cb.mutex);
    ASSERT(cb.condition.wait_for(cb.mutex, 2 * kMaxRuntime, [&cb] { return cb.callbacksComplete; }),
           "Never received callbacks!");

    float actualFrameRate = static_cast<float>(cb.count) /
            (static_cast<double>((cb.endTime - cb.startTime).count()) / 1'000'000'000.0);
    ALOGI("%s: callback called %d times with final start time %ld, end time %ld, effective "
          "frame rate %f",
          __func__, cb.count, static_cast<long>(cb.startTime.count()),
          static_cast<long>(cb.endTime.count()), actualFrameRate);
    ASSERT(frameRateEquals(actualFrameRate, expectedFrameRate),
           "Effective frame rate is %f but expected to be %f", actualFrameRate, expectedFrameRate);

    endTest(env, clazz);
}

static JNINativeMethod gMethods[] = {
        {"nativeSurfaceControl_getChoreographer", "(Landroid/view/SurfaceControl;)J",
         (void*)SurfaceControl_getChoreographer},
        {"nativeTestPostVsyncCallbackAtFrameRate", "(JF)V",
         (void*)android_view_ChoreographerNativeTest_testPostVsyncCallbackAtFrameRate},
};

int register_android_android_view_tests_ChoreographerNativeTest(JNIEnv* env) {
    jclass clazz =
            env->FindClass("android/view/choreographertests/AttachedChoreographerNativeTest");
    gJni.attachedChoreographerNativeTest.clazz = static_cast<jclass>(env->NewGlobalRef(clazz));
    gJni.attachedChoreographerNativeTest.endTest = env->GetMethodID(clazz, "endTest", "()V");
    return env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(JNINativeMethod));
}
