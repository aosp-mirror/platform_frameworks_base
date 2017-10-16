/*
 * Copyright (C) 2007-2014 The Android Open Source Project
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

#include <fcntl.h>
#include <log/log_event_list.h>

#include <log/log.h>

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "jni.h"

#define UNUSED  __attribute__((__unused__))

namespace android {

static jclass gCollectionClass;
static jmethodID gCollectionAddID;

static jclass gIntegerClass;
static jfieldID gIntegerValueID;

static jclass gLongClass;
static jfieldID gLongValueID;

static jclass gFloatClass;
static jfieldID gFloatValueID;

static jclass gStringClass;

/*
 * In class android.util.StatsLog:
 *  static native int writeInt(int tag, int value)
 */
static jint android_util_StatsLog_write_Integer(JNIEnv* env UNUSED,
                                                     jobject clazz UNUSED,
                                                     jint tag, jint value)
{
    android_log_event_list ctx(tag);
    ctx << (int32_t)value;
    return ctx.write(LOG_ID_STATS);
}

/*
 * In class android.util.StatsLog:
 *  static native int writeLong(long tag, long value)
 */
static jint android_util_StatsLog_write_Long(JNIEnv* env UNUSED,
                                                  jobject clazz UNUSED,
                                                  jint tag, jlong value)
{
    android_log_event_list ctx(tag);
    ctx << (int64_t)value;
    return ctx.write(LOG_ID_STATS);
}

/*
 * In class android.util.StatsLog:
 *  static native int writeFloat(long tag, float value)
 */
static jint android_util_StatsLog_write_Float(JNIEnv* env UNUSED,
                                                  jobject clazz UNUSED,
                                                  jint tag, jfloat value)
{
    android_log_event_list ctx(tag);
    ctx << (float)value;
    return ctx.write(LOG_ID_STATS);
}

/*
 * In class android.util.StatsLog:
 *  static native int writeString(int tag, String value)
 */
static jint android_util_StatsLog_write_String(JNIEnv* env,
                                                    jobject clazz UNUSED,
                                                    jint tag, jstring value) {
    android_log_event_list ctx(tag);
    // Don't throw NPE -- I feel like it's sort of mean for a logging function
    // to be all crashy if you pass in NULL -- but make the NULL value explicit.
    if (value != NULL) {
        const char *str = env->GetStringUTFChars(value, NULL);
        ctx << str;
        env->ReleaseStringUTFChars(value, str);
    } else {
        ctx << "NULL";
    }
    return ctx.write(LOG_ID_STATS);
}

/*
 * In class android.util.StatsLog:
 *  static native int writeArray(long tag, Object... value)
 */
static jint android_util_StatsLog_write_Array(JNIEnv* env, jobject clazz,
                                                   jint tag, jobjectArray value) {
    android_log_event_list ctx(tag);

    if (value == NULL) {
        ctx << "[NULL]";
        return ctx.write(LOG_ID_STATS);
    }

    jsize copied = 0, num = env->GetArrayLength(value);
    for (; copied < num && copied < 255; ++copied) {
        if (ctx.status()) break;
        jobject item = env->GetObjectArrayElement(value, copied);
        if (item == NULL) {
            ctx << "NULL";
        } else if (env->IsInstanceOf(item, gStringClass)) {
            const char *str = env->GetStringUTFChars((jstring) item, NULL);
            ctx << str;
            env->ReleaseStringUTFChars((jstring) item, str);
        } else if (env->IsInstanceOf(item, gIntegerClass)) {
            ctx << (int32_t)env->GetIntField(item, gIntegerValueID);
        } else if (env->IsInstanceOf(item, gLongClass)) {
            ctx << (int64_t)env->GetLongField(item, gLongValueID);
        } else if (env->IsInstanceOf(item, gFloatClass)) {
            ctx << (float)env->GetFloatField(item, gFloatValueID);
        } else {
            jniThrowException(env,
                    "java/lang/IllegalArgumentException",
                    "Invalid payload item type");
            return -1;
        }
        env->DeleteLocalRef(item);
    }
    return ctx.write(LOG_ID_STATS);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "writeInt", "(II)I", (void*) android_util_StatsLog_write_Integer },
    { "writeLong", "(IJ)I", (void*) android_util_StatsLog_write_Long },
    { "writeFloat", "(IF)I", (void*) android_util_StatsLog_write_Float },
    { "writeString",
      "(ILjava/lang/String;)I",
      (void*) android_util_StatsLog_write_String
    },
    { "writeArray",
      "(I[Ljava/lang/Object;)I",
      (void*) android_util_StatsLog_write_Array
    },
};

static struct { const char *name; jclass *clazz; } gClasses[] = {
    { "java/lang/Integer", &gIntegerClass },
    { "java/lang/Long", &gLongClass },
    { "java/lang/Float", &gFloatClass },
    { "java/lang/String", &gStringClass },
    { "java/util/Collection", &gCollectionClass },
};

static struct { jclass *c; const char *name, *ft; jfieldID *id; } gFields[] = {
    { &gIntegerClass, "value", "I", &gIntegerValueID },
    { &gLongClass, "value", "J", &gLongValueID },
    { &gFloatClass, "value", "F", &gFloatValueID },
};

static struct { jclass *c; const char *name, *mt; jmethodID *id; } gMethods[] = {
    { &gCollectionClass, "add", "(Ljava/lang/Object;)Z", &gCollectionAddID },
};

int register_android_util_StatsLog(JNIEnv* env) {
    for (int i = 0; i < NELEM(gClasses); ++i) {
        jclass clazz = FindClassOrDie(env, gClasses[i].name);
        *gClasses[i].clazz = MakeGlobalRefOrDie(env, clazz);
    }

    for (int i = 0; i < NELEM(gFields); ++i) {
        *gFields[i].id = GetFieldIDOrDie(env,
                *gFields[i].c, gFields[i].name, gFields[i].ft);
    }

    for (int i = 0; i < NELEM(gMethods); ++i) {
        *gMethods[i].id = GetMethodIDOrDie(env,
                *gMethods[i].c, gMethods[i].name, gMethods[i].mt);
    }

    return RegisterMethodsOrDie(
            env,
            "android/util/StatsLog",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
