/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include <log/log_id.h>
#include <private/android_logger.h>

#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

static jclass gCollectionClass;
static jmethodID gCollectionAddID;

static jclass gEventClass;
static jmethodID gEventInitID;

static jclass gIntegerClass;
static jfieldID gIntegerValueID;

static jclass gLongClass;
static jfieldID gLongValueID;

static jclass gFloatClass;
static jfieldID gFloatValueID;

static jclass gStringClass;


static jboolean android_app_admin_SecurityLog_isLoggingEnabled(JNIEnv* env,
                                                    jobject /* clazz */) {
    return (bool)__android_log_security();
}

static jint android_app_admin_SecurityLog_writeEvent_String(JNIEnv* env,
                                                    jobject /* clazz */,
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
    return ctx.write(log_id_t::LOG_ID_SECURITY);
}

static jint android_app_admin_SecurityLog_writeEvent_Array(JNIEnv* env, jobject clazz,
                                                   jint tag, jobjectArray value) {
    android_log_event_list ctx(tag);

    if (value == NULL) {
        ctx << "[NULL]";
        return ctx.write(log_id_t::LOG_ID_SECURITY);
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
    return ctx.write(log_id_t::LOG_ID_SECURITY);
}

static void readEvents(JNIEnv* env, int loggerMode, jlong startTime, jobject out) {
    struct logger_list *logger_list;
    if (startTime) {
        logger_list = android_logger_list_alloc_time(loggerMode,
                log_time(startTime / NS_PER_SEC, startTime % NS_PER_SEC), 0);
    } else {
        logger_list = android_logger_list_alloc(loggerMode, 0, 0);
    }
    if (!logger_list) {
        jniThrowIOException(env, errno);
        return;
    }

    if (!android_logger_open(logger_list, LOG_ID_SECURITY)) {
        jniThrowIOException(env, errno);
        android_logger_list_free(logger_list);
        return;
    }

    while (1) {
        log_msg log_msg;
        int ret = android_logger_list_read(logger_list, &log_msg);

        if (ret == 0) {
            break;
        }
        if (ret < 0) {
            if (ret == -EINTR) {
                continue;
            }
            if (ret == -EINVAL) {
                jniThrowException(env, "java/io/IOException", "Event too short");
            } else if (ret != -EAGAIN) {
                jniThrowIOException(env, -ret);  // Will throw on return
            }
            break;
        }

        if (log_msg.id() != LOG_ID_SECURITY) {
            continue;
        }

        jsize len = ret;
        jbyteArray array = env->NewByteArray(len);
        if (array == NULL) {
            break;
        }

        jbyte *bytes = env->GetByteArrayElements(array, NULL);
        memcpy(bytes, log_msg.buf, len);
        env->ReleaseByteArrayElements(array, bytes, 0);

        jobject event = env->NewObject(gEventClass, gEventInitID, array);
        if (event == NULL) {
            break;
        }

        env->CallBooleanMethod(out, gCollectionAddID, event);
        env->DeleteLocalRef(event);
        env->DeleteLocalRef(array);
        if (env->ExceptionCheck() == JNI_TRUE) {
            break;
        }
    }

    android_logger_list_close(logger_list);
}

static void android_app_admin_SecurityLog_readEvents(JNIEnv* env, jobject /* clazz */,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    readEvents(env, ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK, 0, out);
}

static void android_app_admin_SecurityLog_readEventsSince(JNIEnv* env, jobject /* clazz */,
                                             jlong timestamp,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    readEvents(env, ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK, timestamp, out);
}

static void android_app_admin_SecurityLog_readPreviousEvents(JNIEnv* env, jobject /* clazz */,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    readEvents(env, ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK | ANDROID_LOG_PSTORE, 0, out);
}

static void android_app_admin_SecurityLog_readEventsOnWrapping(JNIEnv* env, jobject /* clazz */,
                                             jlong timestamp,
                                             jobject out) {
    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    readEvents(env, ANDROID_LOG_RDONLY | ANDROID_LOG_NONBLOCK | ANDROID_LOG_WRAP, timestamp, out);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "isLoggingEnabled",
      "()Z",
      (void*) android_app_admin_SecurityLog_isLoggingEnabled
    },
    { "writeEvent",
      "(ILjava/lang/String;)I",
      (void*) android_app_admin_SecurityLog_writeEvent_String
    },
    { "writeEvent",
      "(I[Ljava/lang/Object;)I",
      (void*) android_app_admin_SecurityLog_writeEvent_Array
    },
    { "readEvents",
      "(Ljava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEvents
    },
    { "readEventsSince",
      "(JLjava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEventsSince
    },
    { "readPreviousEvents",
      "(Ljava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readPreviousEvents
    },
    { "readEventsOnWrapping",
      "(JLjava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEventsOnWrapping
    },
};

static struct { const char *name; jclass *clazz; } gClasses[] = {
    { "android/app/admin/SecurityLog$SecurityEvent", &gEventClass },
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
    { &gEventClass, "<init>", "([B)V", &gEventInitID },
    { &gCollectionClass, "add", "(Ljava/lang/Object;)Z", &gCollectionAddID },
};

int register_android_app_admin_SecurityLog(JNIEnv* env) {
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
            "android/app/admin/SecurityLog",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
