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

#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include "log/logger.h"

#define UNUSED  __attribute__((__unused__))

// The size of the tag number comes out of the payload size.
#define MAX_EVENT_PAYLOAD (LOGGER_ENTRY_MAX_PAYLOAD - sizeof(int32_t))

namespace android {

static jclass gCollectionClass;
static jmethodID gCollectionAddID;

static jclass gEventClass;
static jmethodID gEventInitID;

static jclass gIntegerClass;
static jfieldID gIntegerValueID;

static jclass gLongClass;
static jfieldID gLongValueID;

static jclass gStringClass;

/*
 * In class android.util.EventLog:
 *  static native int writeEvent(int tag, int value)
 */
static jint android_util_EventLog_writeEvent_Integer(JNIEnv* env UNUSED,
                                                     jobject clazz UNUSED,
                                                     jint tag, jint value)
{
    return android_btWriteLog(tag, EVENT_TYPE_INT, &value, sizeof(value));
}

/*
 * In class android.util.EventLog:
 *  static native int writeEvent(long tag, long value)
 */
static jint android_util_EventLog_writeEvent_Long(JNIEnv* env UNUSED,
                                                  jobject clazz UNUSED,
                                                  jint tag, jlong value)
{
    return android_btWriteLog(tag, EVENT_TYPE_LONG, &value, sizeof(value));
}

/*
 * In class android.util.EventLog:
 *  static native int writeEvent(int tag, String value)
 */
static jint android_util_EventLog_writeEvent_String(JNIEnv* env,
                                                    jobject clazz UNUSED,
                                                    jint tag, jstring value) {
    uint8_t buf[MAX_EVENT_PAYLOAD];

    // Don't throw NPE -- I feel like it's sort of mean for a logging function
    // to be all crashy if you pass in NULL -- but make the NULL value explicit.
    const char *str = value != NULL ? env->GetStringUTFChars(value, NULL) : "NULL";
    uint32_t len = strlen(str);
    size_t max = sizeof(buf) - sizeof(len) - 2;  // Type byte, final newline
    if (len > max) len = max;

    buf[0] = EVENT_TYPE_STRING;
    memcpy(&buf[1], &len, sizeof(len));
    memcpy(&buf[1 + sizeof(len)], str, len);
    buf[1 + sizeof(len) + len] = '\n';

    if (value != NULL) env->ReleaseStringUTFChars(value, str);
    return android_bWriteLog(tag, buf, 2 + sizeof(len) + len);
}

/*
 * In class android.util.EventLog:
 *  static native int writeEvent(long tag, Object... value)
 */
static jint android_util_EventLog_writeEvent_Array(JNIEnv* env, jobject clazz,
                                                   jint tag, jobjectArray value) {
    if (value == NULL) {
        return android_util_EventLog_writeEvent_String(env, clazz, tag, NULL);
    }

    uint8_t buf[MAX_EVENT_PAYLOAD];
    const size_t max = sizeof(buf) - 1;  // leave room for final newline
    size_t pos = 2;  // Save room for type tag & array count

    jsize copied = 0, num = env->GetArrayLength(value);
    for (; copied < num && copied < 255; ++copied) {
        jobject item = env->GetObjectArrayElement(value, copied);
        if (item == NULL || env->IsInstanceOf(item, gStringClass)) {
            if (pos + 1 + sizeof(jint) > max) break;
            const char *str = item != NULL ? env->GetStringUTFChars((jstring) item, NULL) : "NULL";
            jint len = strlen(str);
            if (pos + 1 + sizeof(len) + len > max) len = max - pos - 1 - sizeof(len);
            buf[pos++] = EVENT_TYPE_STRING;
            memcpy(&buf[pos], &len, sizeof(len));
            memcpy(&buf[pos + sizeof(len)], str, len);
            pos += sizeof(len) + len;
            if (item != NULL) env->ReleaseStringUTFChars((jstring) item, str);
        } else if (env->IsInstanceOf(item, gIntegerClass)) {
            jint intVal = env->GetIntField(item, gIntegerValueID);
            if (pos + 1 + sizeof(intVal) > max) break;
            buf[pos++] = EVENT_TYPE_INT;
            memcpy(&buf[pos], &intVal, sizeof(intVal));
            pos += sizeof(intVal);
        } else if (env->IsInstanceOf(item, gLongClass)) {
            jlong longVal = env->GetLongField(item, gLongValueID);
            if (pos + 1 + sizeof(longVal) > max) break;
            buf[pos++] = EVENT_TYPE_LONG;
            memcpy(&buf[pos], &longVal, sizeof(longVal));
            pos += sizeof(longVal);
        } else {
            jniThrowException(env,
                    "java/lang/IllegalArgumentException",
                    "Invalid payload item type");
            return -1;
        }
        env->DeleteLocalRef(item);
    }

    buf[0] = EVENT_TYPE_LIST;
    buf[1] = copied;
    buf[pos++] = '\n';
    return android_bWriteLog(tag, buf, pos);
}

/*
 * In class android.util.EventLog:
 *  static native void readEvents(int[] tags, Collection<Event> output)
 *
 *  Reads events from the event log
 */
static void android_util_EventLog_readEvents(JNIEnv* env, jobject clazz UNUSED,
                                             jintArray tags,
                                             jobject out) {

    if (tags == NULL || out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    struct logger_list *logger_list = android_logger_list_open(
        LOG_ID_EVENTS, O_RDONLY | O_NONBLOCK, 0, 0);

    if (!logger_list) {
        jniThrowIOException(env, errno);
        return;
    }

    jsize tagLength = env->GetArrayLength(tags);
    jint *tagValues = env->GetIntArrayElements(tags, NULL);

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

        int32_t tag = * (int32_t *) log_msg.msg();

        int found = 0;
        for (int i = 0; !found && i < tagLength; ++i) {
            found = (tag == tagValues[i]);
        }

        if (found) {
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
        }
    }

    android_logger_list_close(logger_list);

    env->ReleaseIntArrayElements(tags, tagValues, 0);
}

/*
 * JNI registration.
 */
static JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "writeEvent", "(II)I", (void*) android_util_EventLog_writeEvent_Integer },
    { "writeEvent", "(IJ)I", (void*) android_util_EventLog_writeEvent_Long },
    { "writeEvent",
      "(ILjava/lang/String;)I",
      (void*) android_util_EventLog_writeEvent_String
    },
    { "writeEvent",
      "(I[Ljava/lang/Object;)I",
      (void*) android_util_EventLog_writeEvent_Array
    },
    { "readEvents",
      "([ILjava/util/Collection;)V",
      (void*) android_util_EventLog_readEvents
    },
};

static struct { const char *name; jclass *clazz; } gClasses[] = {
    { "android/util/EventLog$Event", &gEventClass },
    { "java/lang/Integer", &gIntegerClass },
    { "java/lang/Long", &gLongClass },
    { "java/lang/String", &gStringClass },
    { "java/util/Collection", &gCollectionClass },
};

static struct { jclass *c; const char *name, *ft; jfieldID *id; } gFields[] = {
    { &gIntegerClass, "value", "I", &gIntegerValueID },
    { &gLongClass, "value", "J", &gLongValueID },
};

static struct { jclass *c; const char *name, *mt; jmethodID *id; } gMethods[] = {
    { &gEventClass, "<init>", "([B)V", &gEventInitID },
    { &gCollectionClass, "add", "(Ljava/lang/Object;)Z", &gCollectionAddID },
};

int register_android_util_EventLog(JNIEnv* env) {
    for (int i = 0; i < NELEM(gClasses); ++i) {
        jclass clazz = env->FindClass(gClasses[i].name);
        if (clazz == NULL) {
            ALOGE("Can't find class: %s\n", gClasses[i].name);
            return -1;
        }
        *gClasses[i].clazz = (jclass) env->NewGlobalRef(clazz);
    }

    for (int i = 0; i < NELEM(gFields); ++i) {
        *gFields[i].id = env->GetFieldID(
                *gFields[i].c, gFields[i].name, gFields[i].ft);
        if (*gFields[i].id == NULL) {
            ALOGE("Can't find field: %s\n", gFields[i].name);
            return -1;
        }
    }

    for (int i = 0; i < NELEM(gMethods); ++i) {
        *gMethods[i].id = env->GetMethodID(
                *gMethods[i].c, gMethods[i].name, gMethods[i].mt);
        if (*gMethods[i].id == NULL) {
            ALOGE("Can't find method: %s\n", gMethods[i].name);
            return -1;
        }
    }

    return AndroidRuntime::registerNativeMethods(
            env,
            "android/util/EventLog",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
