/*
 * Copyright 2017, The Android Open Source Project
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
#define LOG_TAG "JDataSourceCallback-JNI"
#include <utils/Log.h>

#include "android_media_DataSourceCallback.h"

#include "log/log.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <drm/drm_framework_common.h>
#include <mediaplayer2/JavaVMHelper.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/ScopedLocalRef.h>

namespace android {

static const size_t kBufferSize = 64 * 1024;

JDataSourceCallback::JDataSourceCallback(JNIEnv* env, jobject source)
    : mJavaObjStatus(OK),
      mSizeIsCached(false),
      mCachedSize(0) {
    mDataSourceCallbackObj = env->NewGlobalRef(source);
    CHECK(mDataSourceCallbackObj != NULL);

    ScopedLocalRef<jclass> media2DataSourceClass(env, env->GetObjectClass(mDataSourceCallbackObj));
    CHECK(media2DataSourceClass.get() != NULL);

    mReadAtMethod = env->GetMethodID(media2DataSourceClass.get(), "readAt", "(J[BII)I");
    CHECK(mReadAtMethod != NULL);
    mGetSizeMethod = env->GetMethodID(media2DataSourceClass.get(), "getSize", "()J");
    CHECK(mGetSizeMethod != NULL);
    mCloseMethod = env->GetMethodID(media2DataSourceClass.get(), "close", "()V");
    CHECK(mCloseMethod != NULL);

    ScopedLocalRef<jbyteArray> tmp(env, env->NewByteArray(kBufferSize));
    mByteArrayObj = (jbyteArray)env->NewGlobalRef(tmp.get());
    CHECK(mByteArrayObj != NULL);
}

JDataSourceCallback::~JDataSourceCallback() {
    JNIEnv* env = JavaVMHelper::getJNIEnv();
    env->DeleteGlobalRef(mDataSourceCallbackObj);
    env->DeleteGlobalRef(mByteArrayObj);
}

status_t JDataSourceCallback::initCheck() const {
    return OK;
}

ssize_t JDataSourceCallback::readAt(off64_t offset, void *data, size_t size) {
    Mutex::Autolock lock(mLock);

    if (mJavaObjStatus != OK) {
        return -1;
    }
    if (size > kBufferSize) {
        size = kBufferSize;
    }

    JNIEnv* env = JavaVMHelper::getJNIEnv();
    jint numread = env->CallIntMethod(mDataSourceCallbackObj, mReadAtMethod,
            (jlong)offset, mByteArrayObj, (jint)0, (jint)size);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred in readAt()");
        jniLogException(env, ANDROID_LOG_WARN, LOG_TAG);
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
    env->GetByteArrayRegion(mByteArrayObj, 0, numread, (jbyte*)data);
    return numread;
}

status_t JDataSourceCallback::getSize(off64_t* size) {
    Mutex::Autolock lock(mLock);

    if (mJavaObjStatus != OK) {
        return UNKNOWN_ERROR;
    }
    if (mSizeIsCached) {
        *size = mCachedSize;
        return OK;
    }

    JNIEnv* env = JavaVMHelper::getJNIEnv();
    *size = env->CallLongMethod(mDataSourceCallbackObj, mGetSizeMethod);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred in getSize()");
        jniLogException(env, ANDROID_LOG_WARN, LOG_TAG);
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

void JDataSourceCallback::close() {
    Mutex::Autolock lock(mLock);

    JNIEnv* env = JavaVMHelper::getJNIEnv();
    env->CallVoidMethod(mDataSourceCallbackObj, mCloseMethod);
    // The closed state is effectively the same as an error state.
    mJavaObjStatus = UNKNOWN_ERROR;
}

String8 JDataSourceCallback::toString() {
    return String8::format("JDataSourceCallback(pid %d, uid %d)", getpid(), getuid());
}

String8 JDataSourceCallback::getMIMEType() const {
    return String8("application/octet-stream");
}

}  // namespace android
