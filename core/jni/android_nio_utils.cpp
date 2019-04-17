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

#include "core_jni_helpers.h"

namespace android {

AutoBufferPointer::AutoBufferPointer(JNIEnv* env, jobject nioBuffer, jboolean commit)
        : fEnv(env), fCommit(commit) {
    jlong pointer = jniGetNioBufferPointer(fEnv, nioBuffer);
    if (pointer != 0L) {
        // Buffer is backed by a direct buffer.
        fArray = nullptr;
        fElements = nullptr;
        fPointer = reinterpret_cast<void*>(pointer);
    } else {
        // Buffer is backed by a managed array.
        jint byteOffset = jniGetNioBufferBaseArrayOffset(fEnv, nioBuffer);
        fArray = jniGetNioBufferBaseArray(fEnv, nioBuffer);
        fElements = fEnv->GetPrimitiveArrayCritical(fArray, /* isCopy= */ nullptr);
        fPointer = reinterpret_cast<void*>(reinterpret_cast<char*>(fElements) + byteOffset);
    }
}

AutoBufferPointer::~AutoBufferPointer() {
    if (nullptr != fArray) {
        fEnv->ReleasePrimitiveArrayCritical(fArray, fElements, fCommit ? 0 : JNI_ABORT);
    }
}

}  // namespace android
