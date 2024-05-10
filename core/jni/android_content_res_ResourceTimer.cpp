/*
 * Copyright 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <core_jni_helpers.h>
#include <utils/misc.h>
#include <androidfw/ResourceTimer.h>

namespace android {

// ----------------------------------------------------------------------------

static struct {
  jfieldID maxTimer;
  jfieldID maxBuckets;
  jfieldID maxLargest;
  jfieldID timers;
} gConfigOffsets;

static struct {
  jfieldID count;
  jfieldID total;
  jfieldID mintime;
  jfieldID maxtime;
  jfieldID largest;
  jfieldID percentile;
} gTimerOffsets;

// ----------------------------------------------------------------------------


static int NativeGetTimers(JNIEnv* env, jobject /*clazz*/, jobjectArray timer, jboolean reset) {
  size_t size = ResourceTimer::counterSize;
  if (size_t st = env->GetArrayLength(timer); st < size) {
      // Shrink the size to the minimum of the available counters and the available space.
      size = st;
  }
  for (size_t i = 0; i < size; i++) {
    ResourceTimer::Timer src;
    ResourceTimer::copy(i, src, reset);
    jobject dst = env->GetObjectArrayElement(timer, i);
    env->SetIntField(dst, gTimerOffsets.count, src.count);
    if (src.count == 0) {
      continue;
    }

    src.compute();
    env->SetIntField(dst, gTimerOffsets.count, src.count);
    env->SetLongField(dst, gTimerOffsets.total, src.total);
    env->SetIntField(dst, gTimerOffsets.mintime, src.mintime);
    env->SetIntField(dst, gTimerOffsets.maxtime, src.maxtime);
    jintArray percentile =
        reinterpret_cast<jintArray>(env->GetObjectField(dst, gTimerOffsets.percentile));
    env->SetIntArrayRegion(percentile, 0, 1, &src.pvalues.p50.nominal);
    env->SetIntArrayRegion(percentile, 1, 1, &src.pvalues.p90.nominal);
    env->SetIntArrayRegion(percentile, 2, 1, &src.pvalues.p95.nominal);
    env->SetIntArrayRegion(percentile, 3, 1, &src.pvalues.p99.nominal);
    jintArray largest =
        reinterpret_cast<jintArray>(env->GetObjectField(dst, gTimerOffsets.largest));
    env->SetIntArrayRegion(largest, 0, ResourceTimer::Timer::MaxLargest, src.largest);
  }
  return size;
}

static jstring counterName(JNIEnv *env, int counter) {
  char const *s = ResourceTimer::toString(static_cast<ResourceTimer::Counter>(counter));
  return env->NewStringUTF(s);
}

static int NativeEnableTimers(JNIEnv* env, jobject /*clazz*/, jobject config) {
  ResourceTimer::enable();

  env->SetIntField(config, gConfigOffsets.maxTimer, ResourceTimer::counterSize);
  env->SetIntField(config, gConfigOffsets.maxBuckets, 4);       // Number of ints in PValues
  env->SetIntField(config, gConfigOffsets.maxLargest, ResourceTimer::Timer::MaxLargest);

  jclass str = env->FindClass("java/lang/String");
  jstring empty = counterName(env, 0);
  jobjectArray timers = env->NewObjectArray(ResourceTimer::counterSize, str, empty);
  for (int i = 0; i < ResourceTimer::counterSize; i++) {
    env->SetObjectArrayElement(timers, i, counterName(env, i));
  }
  env->SetObjectField(config, gConfigOffsets.timers, timers);
  return 0;
}

// ----------------------------------------------------------------------------

// JNI registration.
static const JNINativeMethod gResourceTimerMethods[] = {
  {"nativeEnableTimers", "(Landroid/content/res/ResourceTimer$Config;)I",
   (void *) NativeEnableTimers},
  {"nativeGetTimers", "([Landroid/content/res/ResourceTimer$Timer;Z)I",
   (void *) NativeGetTimers},
};

int register_android_content_res_ResourceTimer(JNIEnv* env) {
  jclass config = FindClassOrDie(env, "android/content/res/ResourceTimer$Config");
  gConfigOffsets.maxTimer = GetFieldIDOrDie(env, config, "maxTimer", "I");
  gConfigOffsets.maxBuckets = GetFieldIDOrDie(env, config, "maxBuckets", "I");
  gConfigOffsets.maxLargest = GetFieldIDOrDie(env, config, "maxLargest", "I");
  gConfigOffsets.timers = GetFieldIDOrDie(env, config, "timers", "[Ljava/lang/String;");

  jclass timers = FindClassOrDie(env, "android/content/res/ResourceTimer$Timer");
  gTimerOffsets.count = GetFieldIDOrDie(env, timers, "count", "I");
  gTimerOffsets.total = GetFieldIDOrDie(env, timers, "total", "J");
  gTimerOffsets.mintime = GetFieldIDOrDie(env, timers, "mintime", "I");
  gTimerOffsets.maxtime = GetFieldIDOrDie(env, timers, "maxtime", "I");
  gTimerOffsets.largest = GetFieldIDOrDie(env, timers, "largest", "[I");
  gTimerOffsets.percentile = GetFieldIDOrDie(env, timers, "percentile", "[I");

  return RegisterMethodsOrDie(env, "android/content/res/ResourceTimer", gResourceTimerMethods,
                              NELEM(gResourceTimerMethods));
}

}; // namespace android
