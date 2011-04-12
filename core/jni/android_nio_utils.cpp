/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include "android_nio_utils.h"

struct NioJNIData {
    jclass nioAccessClass;

    jmethodID getBasePointerID;
    jmethodID getBaseArrayID;
    jmethodID getBaseArrayOffsetID;
};

static NioJNIData gNioJNI;

void* android::nio_getPointer(JNIEnv *_env, jobject buffer, jarray *array) {
    assert(array);

    jlong pointer;
    jint offset;
    void *data;

    pointer = _env->CallStaticLongMethod(gNioJNI.nioAccessClass,
                                         gNioJNI.getBasePointerID, buffer);
    if (pointer != 0L) {
        *array = NULL;
        return (void *) (jint) pointer;
    }

    *array = (jarray) _env->CallStaticObjectMethod(gNioJNI.nioAccessClass,
                                               gNioJNI.getBaseArrayID, buffer);
    offset = _env->CallStaticIntMethod(gNioJNI.nioAccessClass,
                                       gNioJNI.getBaseArrayOffsetID, buffer);
    data = _env->GetPrimitiveArrayCritical(*array, (jboolean *) 0);

    return (void *) ((char *) data + offset);
}


void android::nio_releasePointer(JNIEnv *_env, jarray array, void *data,
                                jboolean commit) {
    _env->ReleasePrimitiveArrayCritical(array, data,
                                        commit ? 0 : JNI_ABORT);
}

///////////////////////////////////////////////////////////////////////////////

android::AutoBufferPointer::AutoBufferPointer(JNIEnv* env, jobject nioBuffer,
                                              jboolean commit) {
    fEnv = env;
    fCommit = commit;
    fPointer = android::nio_getPointer(env, nioBuffer, &fArray);
}

android::AutoBufferPointer::~AutoBufferPointer() {
    if (NULL != fArray) {
        android::nio_releasePointer(fEnv, fArray, fPointer, fCommit);
    }
}

///////////////////////////////////////////////////////////////////////////////

static jclass findClass(JNIEnv* env, const char name[]) {
    jclass c = env->FindClass(name);
    LOG_FATAL_IF(!c, "Unable to find class %s", name);
    return c;
}

static jmethodID findStaticMethod(JNIEnv* env, jclass c, const char method[],
                                  const char params[]) {
    jmethodID m = env->GetStaticMethodID(c, method, params);
    LOG_FATAL_IF(!m, "Unable to find method %s", method);
    return m;
}

static jfieldID getFieldID(JNIEnv* env, jclass c, const char name[],
                           const char type[]) {
    jfieldID f = env->GetFieldID(c, name, type);
    LOG_FATAL_IF(!f, "Unable to find field %s", name);
    return f;
}

namespace android {

int register_android_nio_utils(JNIEnv* env) {
    jclass localClass = findClass(env, "java/nio/NIOAccess");
    gNioJNI.getBasePointerID = findStaticMethod(env, localClass,
                                    "getBasePointer", "(Ljava/nio/Buffer;)J");
    gNioJNI.getBaseArrayID = findStaticMethod(env, localClass,
                    "getBaseArray", "(Ljava/nio/Buffer;)Ljava/lang/Object;");
    gNioJNI.getBaseArrayOffsetID = findStaticMethod(env, localClass,
                                "getBaseArrayOffset", "(Ljava/nio/Buffer;)I");

    // now record a permanent version of the class ID
    gNioJNI.nioAccessClass = (jclass) env->NewGlobalRef(localClass);

    return 0;
}

}
