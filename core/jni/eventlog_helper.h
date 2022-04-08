/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef FRAMEWORKS_BASE_CORE_JNI_EVENTLOG_HELPER_H_
#define FRAMEWORKS_BASE_CORE_JNI_EVENTLOG_HELPER_H_

#include <memory>

#include <fcntl.h>

#include <android-base/macros.h>
#include <log/log_event_list.h>

#include <log/log.h>

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

template <log_id_t LogID, const char* EventClassDescriptor>
class EventLogHelper {
public:
    static void Init(JNIEnv* env) {
        struct { const char *name; jclass *clazz; } gClasses[] = {
                { EventClassDescriptor, &gEventClass },
                { "java/lang/Integer", &gIntegerClass },
                { "java/lang/Long", &gLongClass },
                { "java/lang/Float", &gFloatClass },
                { "java/lang/String", &gStringClass },
                { "java/util/Collection", &gCollectionClass },
        };
        struct { jclass *c; const char *name, *ft; jfieldID *id; } gFields[] = {
                { &gIntegerClass, "value", "I", &gIntegerValueID },
                { &gLongClass, "value", "J", &gLongValueID },
                { &gFloatClass, "value", "F", &gFloatValueID },
        };
        struct { jclass *c; const char *name, *mt; jmethodID *id; } gMethods[] = {
                { &gEventClass, "<init>", "([B)V", &gEventInitID },
                { &gCollectionClass, "add", "(Ljava/lang/Object;)Z", &gCollectionAddID },
        };

        for (size_t i = 0; i < NELEM(gClasses); ++i) {
            ScopedLocalRef<jclass> clazz(env, FindClassOrDie(env, gClasses[i].name));
            *gClasses[i].clazz = MakeGlobalRefOrDie(env, clazz.get());
        }
        for (size_t i = 0; i < NELEM(gFields); ++i) {
            *gFields[i].id = GetFieldIDOrDie(env,
                    *gFields[i].c, gFields[i].name, gFields[i].ft);
        }

        for (size_t i = 0; i < NELEM(gMethods); ++i) {
            *gMethods[i].id = GetMethodIDOrDie(env,
                    *gMethods[i].c, gMethods[i].name, gMethods[i].mt);
        }
    }

    static jint writeEventInteger(JNIEnv* env ATTRIBUTE_UNUSED, jobject clazz ATTRIBUTE_UNUSED,
            jint tag, jint value) {
        android_log_event_list ctx(tag);
        ctx << (int32_t)value;
        return ctx.write(LogID);
    }
    static jint writeEventLong(JNIEnv* env ATTRIBUTE_UNUSED, jobject clazz ATTRIBUTE_UNUSED,
            jint tag, jlong value) {
        android_log_event_list ctx(tag);
        ctx << (int64_t)value;
        return ctx.write(LogID);
    }
    static jint writeEventFloat(JNIEnv* env ATTRIBUTE_UNUSED, jobject clazz ATTRIBUTE_UNUSED,
            jint tag, jfloat value) {
        android_log_event_list ctx(tag);
        ctx << (float)value;
        return ctx.write(LogID);
    }
    static jint writeEventString(JNIEnv* env, jobject clazz ATTRIBUTE_UNUSED, jint tag,
            jstring value) {
        android_log_event_list ctx(tag);
        // Don't throw NPE -- I feel like it's sort of mean for a logging function
        // to be all crashy if you pass in NULL -- but make the NULL value explicit.
        ctx << (value != nullptr ? ScopedUtfChars(env, value).c_str() : "NULL");
        return ctx.write(LogID);
    }
    static jint writeEventArray(JNIEnv* env, jobject clazz ATTRIBUTE_UNUSED, jint tag,
            jobjectArray value) {
        android_log_event_list ctx(tag);

        if (value == nullptr) {
            ctx << "[NULL]";
            return ctx.write(LogID);
        }

        jsize copied = 0, num = env->GetArrayLength(value);
        for (; copied < num && copied < 255; ++copied) {
            if (ctx.status()) break;
            ScopedLocalRef<jobject> item(env, env->GetObjectArrayElement(value, copied));
            if (item == nullptr) {
                ctx << "NULL";
            } else if (env->IsInstanceOf(item.get(), gStringClass)) {
                ctx << ScopedUtfChars(env, (jstring) item.get()).c_str();
            } else if (env->IsInstanceOf(item.get(), gIntegerClass)) {
                ctx << (int32_t)env->GetIntField(item.get(), gIntegerValueID);
            } else if (env->IsInstanceOf(item.get(), gLongClass)) {
                ctx << (int64_t)env->GetLongField(item.get(), gLongValueID);
            } else if (env->IsInstanceOf(item.get(), gFloatClass)) {
                ctx << (float)env->GetFloatField(item.get(), gFloatValueID);
            } else {
                jniThrowException(env,
                        "java/lang/IllegalArgumentException",
                        "Invalid payload item type");
                return -1;
            }
        }
        return ctx.write(LogID);
    }

    static void readEvents(JNIEnv* env, int loggerMode, jlong startTime, jobject out) {
        readEvents(env, loggerMode, nullptr, startTime, out);
    }

    static void readEvents(JNIEnv* env, int loggerMode, jintArray jTags, jlong startTime,
            jobject out) {
        std::unique_ptr<struct logger_list, decltype(&android_logger_list_close)> logger_list(
                nullptr, android_logger_list_close);
        if (startTime) {
            logger_list.reset(android_logger_list_alloc_time(loggerMode,
                    log_time(startTime / NS_PER_SEC, startTime % NS_PER_SEC), 0));
        } else {
            logger_list.reset(android_logger_list_alloc(loggerMode, 0, 0));
        }
        if (!logger_list) {
            jniThrowIOException(env, errno);
            return;
        }

        if (!android_logger_open(logger_list.get(), LogID)) {
            jniThrowIOException(env, errno);
            return;
        }

        ScopedIntArrayRO tags(env);
        if (jTags != nullptr) {
            tags.reset(jTags);
        }

        while (1) {
            log_msg log_msg;
            int ret = android_logger_list_read(logger_list.get(), &log_msg);

            if (ret == 0) {
                return;
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
                return;
            }

            if (log_msg.id() != LogID) {
                continue;
            }

            int32_t tag = * (int32_t *) log_msg.msg();

            if (jTags != nullptr) {
                bool found = false;
                for (size_t i = 0; !found && i < tags.size(); ++i) {
                    found = (tag == tags[i]);
                }
                if (!found) {
                    continue;
                }
            }

            jsize len = ret;
            ScopedLocalRef<jbyteArray> array(env, env->NewByteArray(len));
            if (array == nullptr) {
                return;
            }

            {
                ScopedByteArrayRW bytes(env, array.get());
                memcpy(bytes.get(), log_msg.buf, len);
            }

            ScopedLocalRef<jobject> event(env,
                    env->NewObject(gEventClass, gEventInitID, array.get()));
            if (event == nullptr) {
                return;
            }

            env->CallBooleanMethod(out, gCollectionAddID, event.get());
            if (env->ExceptionCheck() == JNI_TRUE) {
                return;
            }
        }
    }

private:
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
};

// Explicit instantiation declarations.
template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gCollectionClass;
template <log_id_t LogID, const char* EventClassDescriptor>
jmethodID EventLogHelper<LogID, EventClassDescriptor>::gCollectionAddID;

template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gEventClass;
template <log_id_t LogID, const char* EventClassDescriptor>
jmethodID EventLogHelper<LogID, EventClassDescriptor>::gEventInitID;

template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gIntegerClass;
template <log_id_t LogID, const char* EventClassDescriptor>
jfieldID EventLogHelper<LogID, EventClassDescriptor>::gIntegerValueID;

template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gLongClass;
template <log_id_t LogID, const char* EventClassDescriptor>
jfieldID EventLogHelper<LogID, EventClassDescriptor>::gLongValueID;

template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gFloatClass;
template <log_id_t LogID, const char* EventClassDescriptor>
jfieldID EventLogHelper<LogID, EventClassDescriptor>::gFloatValueID;

template <log_id_t LogID, const char* EventClassDescriptor>
jclass EventLogHelper<LogID, EventClassDescriptor>::gStringClass;

}  // namespace android

#endif  // FRAMEWORKS_BASE_CORE_JNI_EVENTLOG_HELPER_H_
