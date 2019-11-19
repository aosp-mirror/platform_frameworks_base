/*
 * Copyright 2015, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "JMediaDataSource-JNI"
#include <utils/Log.h>

#include "android_media_MediaDataSource.h"

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <binder/MemoryDealer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/ScopedLocalRef.h>

namespace android {

JMediaDataSource::JMediaDataSource(JNIEnv* env, jobject source)
    : mJavaObjStatus(OK), mSizeIsCached(false), mCachedSize(0), mMemory(NULL) {
    mMediaDataSourceObj = env->NewGlobalRef(source);
    CHECK(mMediaDataSourceObj != NULL);

    ScopedLocalRef<jclass> mediaDataSourceClass(env, env->GetObjectClass(mMediaDataSourceObj));
    CHECK(mediaDataSourceClass.get() != NULL);

    mReadMethod = env->GetMethodID(mediaDataSourceClass.get(), "readAt", "(J[BII)I");
    CHECK(mReadMethod != NULL);
    mGetSizeMethod = env->GetMethodID(mediaDataSourceClass.get(), "getSize", "()J");
    CHECK(mGetSizeMethod != NULL);
    mCloseMethod = env->GetMethodID(mediaDataSourceClass.get(), "close", "()V");
    CHECK(mCloseMethod != NULL);

    ScopedLocalRef<jbyteArray> tmp(env, env->NewByteArray(kBufferSize));
    mByteArrayObj = (jbyteArray)env->NewGlobalRef(tmp.get());
    CHECK(mByteArrayObj != NULL);

    sp<MemoryDealer> memoryDealer = new MemoryDealer(kBufferSize, "JMediaDataSource");
    mMemory = memoryDealer->allocate(kBufferSize);
    if (mMemory == NULL) {
        ALOGE("Failed to allocate memory!");
    }
}

JMediaDataSource::~JMediaDataSource() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mMediaDataSourceObj);
    env->DeleteGlobalRef(mByteArrayObj);
}

sp<IMemory> JMediaDataSource::getIMemory() {
    Mutex::Autolock lock(mLock);
    return mMemory;
}

ssize_t JMediaDataSource::readAt(off64_t offset, size_t size) {
    Mutex::Autolock lock(mLock);

    if (mJavaObjStatus != OK || mMemory == NULL) {
        return -1;
    }
    if (size > kBufferSize) {
        size = kBufferSize;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jint numread = env->CallIntMethod(mMediaDataSourceObj, mReadMethod,
            (jlong)offset, mByteArrayObj, (jint)0, (jint)size);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred in readAt()");
        LOGW_EX(env);
        env->ExceptionClear();
        mJavaObjStatus = UNKNOWN_ERROR;
        return -1;
    }
    if (numread < 0) {
        if (numread != -1) {
            ALOGW("An error occurred in readAt()");
            mJavaObjStatus = UNKNOWN_ERROR;
            return -1;
        } else {
            // numread == -1 indicates EOF
            return 0;
        }
    }
    if ((size_t)numread > size) {
        ALOGE("readAt read too many bytes.");
        mJavaObjStatus = UNKNOWN_ERROR;
        return -1;
    }

    ALOGV("readAt %lld / %zu => %d.", (long long)offset, size, numread);
    env->GetByteArrayRegion(mByteArrayObj, 0, numread,
        (jbyte*)mMemory->unsecurePointer());
    return numread;
}

status_t JMediaDataSource::getSize(off64_t* size) {
    Mutex::Autolock lock(mLock);

    if (mJavaObjStatus != OK) {
        return UNKNOWN_ERROR;
    }
    if (mSizeIsCached) {
        *size = mCachedSize;
        return OK;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    *size = env->CallLongMethod(mMediaDataSourceObj, mGetSizeMethod);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred in getSize()");
        LOGW_EX(env);
        env->ExceptionClear();
        // After returning an error, size shouldn't be used by callers.
        *size = UNKNOWN_ERROR;
        mJavaObjStatus = UNKNOWN_ERROR;
        return UNKNOWN_ERROR;
    }

    // The minimum size should be -1, which indicates unknown size.
    if (*size < 0) {
        *size = -1;
    }

    mCachedSize = *size;
    mSizeIsCached = true;
    return OK;
}

void JMediaDataSource::close() {
    Mutex::Autolock lock(mLock);

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mMediaDataSourceObj, mCloseMethod);
    // The closed state is effectively the same as an error state.
    mJavaObjStatus = UNKNOWN_ERROR;
}

uint32_t JMediaDataSource::getFlags() {
    return 0;
}

String8 JMediaDataSource::toString() {
    return String8::format("JMediaDataSource(pid %d, uid %d)", getpid(), getuid());
}

}  // namespace android
