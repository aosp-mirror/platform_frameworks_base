/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "JniConstants.h"

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

#define LOG_TAG "JniConstants"
#include <log/log.h>

// jclass constants list:
//   <class, signature, androidOnly>
#define JCLASS_CONSTANTS_LIST(V)                                            \
  V(FileDescriptor, "java/io/FileDescriptor", false)                        \
  V(NioBuffer, "java/nio/Buffer", false)                                    \
  V(NioByteBuffer, "java/nio/ByteBuffer", false)                            \
  V(NioShortBuffer, "java/nio/ShortBuffer", false)                          \
  V(NioCharBuffer, "java/nio/CharBuffer", false)                            \
  V(NioIntBuffer, "java/nio/IntBuffer", false)                              \
  V(NioFloatBuffer, "java/nio/FloatBuffer", false)                          \
  V(NioLongBuffer, "java/nio/LongBuffer", false)                            \
  V(NioDoubleBuffer, "java/nio/DoubleBuffer", false)

// jmethodID's of public methods constants list:
//   <Class, method, method-string, signature, is_static>
#define JMETHODID_CONSTANTS_LIST(V)                                                         \
  V(FileDescriptor, init, "<init>", "()V", false)                                           \
  V(NioBuffer, array, "array", "()Ljava/lang/Object;", false)                               \
  V(NioBuffer, hasArray, "hasArray", "()Z", false)                                          \
  V(NioBuffer, isDirect, "isDirect", "()Z", false)                                          \
  V(NioBuffer, arrayOffset, "arrayOffset", "()I", false)

// jfieldID constants list:
//   <Class, field, signature, is_static>
#define JFIELDID_CONSTANTS_LIST(V)                                          \
  V(FileDescriptor, fd, "I", false)                                         \
  V(NioBuffer, address, "J", false)                                         \
  V(NioBuffer, limit, "I", false)                                           \
  V(NioBuffer, position, "I", false)

#define CLASS_NAME(cls)             g_ ## cls
#define METHOD_NAME(cls, method)    g_ ## cls ## _ ## method
#define FIELD_NAME(cls, field)      g_ ## cls ## _ ## field

//
// Declare storage for cached classes, methods and fields.
//

#define JCLASS_DECLARE_STORAGE(cls, ...)                                    \
  static jclass CLASS_NAME(cls) = NULL;
JCLASS_CONSTANTS_LIST(JCLASS_DECLARE_STORAGE)
#undef JCLASS_DECLARE_STORAGE

#define JMETHODID_DECLARE_STORAGE(cls, method, ...)                         \
  static jmethodID METHOD_NAME(cls, method) = NULL;
JMETHODID_CONSTANTS_LIST(JMETHODID_DECLARE_STORAGE)
#undef JMETHODID_DECLARE_STORAGE

#define JFIELDID_DECLARE_STORAGE(cls, field, ...)                           \
  static jfieldID FIELD_NAME(cls, field) = NULL;
JFIELDID_CONSTANTS_LIST(JFIELDID_DECLARE_STORAGE)
#undef JFIELDID_DECLARE_STORAGE

//
// Helper methods
//

static jclass FindClass(JNIEnv* env, const char* signature, bool androidOnly) {
    jclass cls = (*env)->FindClass(env, signature);
    if (cls == NULL) {
        LOG_ALWAYS_FATAL_IF(!androidOnly, "Class not found: %s", signature);
        return NULL;
    }
    return (*env)->NewGlobalRef(env, cls);
}

static jmethodID FindMethod(JNIEnv* env, jclass cls,
                            const char* name, const char* signature, bool isStatic) {
    jmethodID method;
    if (isStatic) {
        method = (*env)->GetStaticMethodID(env, cls, name, signature);
    } else {
        method = (*env)->GetMethodID(env, cls, name, signature);
    }
    LOG_ALWAYS_FATAL_IF(method == NULL, "Method not found: %s:%s", name, signature);
    return method;
}

static jfieldID FindField(JNIEnv* env, jclass cls,
                          const char* name, const char* signature, bool isStatic) {
    jfieldID field;
    if (isStatic) {
        field = (*env)->GetStaticFieldID(env, cls, name, signature);
    } else {
        field = (*env)->GetFieldID(env, cls, name, signature);
    }
    LOG_ALWAYS_FATAL_IF(field == NULL, "Field not found: %s:%s", name, signature);
    return field;
}

static pthread_once_t g_initialized = PTHREAD_ONCE_INIT;
static JNIEnv* g_init_env;

static void InitializeConstants() {
    // Initialize cached classes.
#define JCLASS_INITIALIZE(cls, signature, androidOnly)                      \
    CLASS_NAME(cls) = FindClass(g_init_env, signature, androidOnly);
    JCLASS_CONSTANTS_LIST(JCLASS_INITIALIZE)
#undef JCLASS_INITIALIZE

    // Initialize cached methods.
#define JMETHODID_INITIALIZE(cls, method, name, signature, isStatic)        \
    METHOD_NAME(cls, method) =                                              \
        FindMethod(g_init_env, CLASS_NAME(cls), name, signature, isStatic);
    JMETHODID_CONSTANTS_LIST(JMETHODID_INITIALIZE)
#undef JMETHODID_INITIALIZE

    // Initialize cached fields.
#define JFIELDID_INITIALIZE(cls, field, signature, isStatic)                \
    FIELD_NAME(cls, field) =                                                \
        FindField(g_init_env, CLASS_NAME(cls), #field, signature, isStatic);
    JFIELDID_CONSTANTS_LIST(JFIELDID_INITIALIZE)
#undef JFIELDID_INITIALIZE
}

void EnsureInitialized(JNIEnv* env) {
    // This method has to be called in every cache accesses because library can be built
    // 2 different ways and existing usage for compat version doesn't have a good hook for
    // initialization and is widely used.
    g_init_env = env;
    pthread_once(&g_initialized, InitializeConstants);
}

// API exported by libnativehelper_api.h.

void jniUninitializeConstants() {
    // Uninitialize cached classes, methods and fields.
    //
    // NB we assume the runtime is stopped at this point and do not delete global
    // references.
#define JCLASS_INVALIDATE(cls, ...) CLASS_NAME(cls) = NULL;
    JCLASS_CONSTANTS_LIST(JCLASS_INVALIDATE);
#undef JCLASS_INVALIDATE

#define JMETHODID_INVALIDATE(cls, method, ...) METHOD_NAME(cls, method) = NULL;
    JMETHODID_CONSTANTS_LIST(JMETHODID_INVALIDATE);
#undef JMETHODID_INVALIDATE

#define JFIELDID_INVALIDATE(cls, field, ...) FIELD_NAME(cls, field) = NULL;
    JFIELDID_CONSTANTS_LIST(JFIELDID_INVALIDATE);
#undef JFIELDID_INVALIDATE

    // If jniConstantsUninitialize is called, runtime has shutdown. Reset
    // state as some tests re-start the runtime.
    pthread_once_t o = PTHREAD_ONCE_INIT;
    memcpy(&g_initialized, &o, sizeof(o));
}

//
// Accessors
//

#define JCLASS_ACCESSOR_IMPL(cls, ...)                                      \
jclass JniConstants_ ## cls ## Class(JNIEnv* env) {                         \
    EnsureInitialized(env);                                                 \
    return CLASS_NAME(cls);                                                 \
}
JCLASS_CONSTANTS_LIST(JCLASS_ACCESSOR_IMPL)
#undef JCLASS_ACCESSOR_IMPL

#define JMETHODID_ACCESSOR_IMPL(cls, method, ...)                           \
jmethodID JniConstants_ ## cls ## _ ## method(JNIEnv* env) {                \
    EnsureInitialized(env);                                                 \
    return METHOD_NAME(cls, method);                                        \
}
JMETHODID_CONSTANTS_LIST(JMETHODID_ACCESSOR_IMPL)

#define JFIELDID_ACCESSOR_IMPL(cls, field, ...)                             \
jfieldID JniConstants_ ## cls ## _ ## field(JNIEnv* env) {                  \
    EnsureInitialized(env);                                                 \
    return FIELD_NAME(cls, field);                                          \
}
JFIELDID_CONSTANTS_LIST(JFIELDID_ACCESSOR_IMPL)
