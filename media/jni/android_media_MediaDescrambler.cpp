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
#define LOG_TAG "MediaDescrambler-JNI"
#include <utils/Log.h>

#include "android_media_MediaDescrambler.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_util_Binder.h"
#include "JNIHelp.h"

#include <android/media/IDescrambler.h>
#include <binder/MemoryDealer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/ScopedLocalRef.h>

namespace android {
using media::MediaDescrambler::DescrambleInfo;

struct fields_t {
    jfieldID context;
};

static fields_t gFields;

static sp<JDescrambler> getDescrambler(JNIEnv *env, jobject thiz) {
    return (JDescrambler *)env->GetLongField(thiz, gFields.context);
}

static void setDescrambler(
        JNIEnv *env, jobject thiz, const sp<JDescrambler> &descrambler) {
    sp<JDescrambler> old = (JDescrambler *)env->GetLongField(thiz, gFields.context);
    if (descrambler != NULL) {
        descrambler->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, gFields.context, (jlong)descrambler.get());
}

static status_t getBufferAndSize(
        JNIEnv *env, jobject byteBuf, jint offset, size_t length,
        void **outPtr, jbyteArray *outByteArray) {
    void *ptr = env->GetDirectBufferAddress(byteBuf);

    size_t bufSize;
    jbyteArray byteArray = NULL;

    ScopedLocalRef<jclass> byteBufClass(env, env->FindClass("java/nio/ByteBuffer"));
    CHECK(byteBufClass.get() != NULL);

    if (ptr == NULL) {
        jmethodID arrayID =
            env->GetMethodID(byteBufClass.get(), "array", "()[B");
        CHECK(arrayID != NULL);

        byteArray =
            (jbyteArray)env->CallObjectMethod(byteBuf, arrayID);

        if (byteArray == NULL) {
            return INVALID_OPERATION;
        }

        jboolean isCopy;
        ptr = env->GetByteArrayElements(byteArray, &isCopy);

        bufSize = (size_t) env->GetArrayLength(byteArray);
    } else {
        bufSize = (size_t) env->GetDirectBufferCapacity(byteBuf);
    }

    if (length + offset > bufSize) {
        if (byteArray != NULL) {
            env->ReleaseByteArrayElements(byteArray, (jbyte *)ptr, 0);
        }

        return -ERANGE;
    }

    *outPtr = ptr;
    *outByteArray = byteArray;

    return OK;
}

JDescrambler::JDescrambler(JNIEnv *env, jobject descramblerBinderObj) {
    sp<IDescrambler> cas;
    if (descramblerBinderObj != NULL) {
        sp<IBinder> binder = ibinderForJavaObject(env, descramblerBinderObj);
        mDescrambler = interface_cast<IDescrambler>(binder);
    }
}

JDescrambler::~JDescrambler() {
    // Don't call release() here, it's called by Java class
    mDescrambler.clear();
    mMem.clear();
    mDealer.clear();
}

void JDescrambler::ensureBufferCapacity(size_t neededSize) {
    if (mMem != NULL && mMem->size() >= neededSize) {
        return;
    }

    ALOGV("ensureBufferCapacity: current size %zu, new size %zu",
            mMem == NULL ? 0 : mMem->size(), neededSize);

    size_t alignment = MemoryDealer::getAllocationAlignment();
    neededSize = (neededSize + (alignment - 1)) & ~(alignment - 1);
    // Align to multiples of 64K.
    neededSize = (neededSize + 65535) & ~65535;
    mDealer = new MemoryDealer(neededSize, "JDescrambler");
    mMem = mDealer->allocate(neededSize);
}

ssize_t JDescrambler::descramble(
        jbyte key,
        size_t numSubSamples,
        ssize_t totalLength,
        DescramblerPlugin::SubSample *subSamples,
        const void *srcPtr,
        jint srcOffset,
        void *dstPtr,
        jint dstOffset) {
    // TODO: IDescrambler::descramble() is re-entrant, however because we
    // only have 1 shared mem buffer, we can only do 1 descramble at a time.
    // Concurrency might be improved by allowing on-demand allocation of up
    // to 2 shared mem buffers.
    Mutex::Autolock autolock(mSharedMemLock);

    ensureBufferCapacity(totalLength);

    memcpy(mMem->pointer(),
            (const void*)((const uint8_t*)srcPtr + srcOffset), totalLength);

    DescrambleInfo info;
    info.dstType = DescrambleInfo::kDestinationTypeVmPointer;
    info.numSubSamples = numSubSamples;
    info.scramblingControl = (DescramblerPlugin::ScramblingControl) key;
    info.subSamples = subSamples;
    info.srcMem = mMem;
    info.srcOffset = 0;
    info.dstPtr = NULL;
    info.dstOffset = 0;

    int32_t result;
    binder::Status status = mDescrambler->descramble(info, &result);

    if (!status.isOk() || result > totalLength) {
        return -1;
    }
    if (result > 0) {
        memcpy((void*)((uint8_t*)dstPtr + dstOffset), mMem->pointer(), result);
    }
    return result;
}

}  // namespace android

using namespace android;

static void android_media_MediaDescrambler_native_release(JNIEnv *env, jobject thiz) {
    setDescrambler(env, thiz, NULL);
}

static void android_media_MediaDescrambler_native_init(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaDescrambler"));
    CHECK(clazz.get() != NULL);

    gFields.context = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    CHECK(gFields.context != NULL);
}

static void android_media_MediaDescrambler_native_setup(
        JNIEnv *env, jobject thiz, jobject descramblerBinderObj) {
    setDescrambler(env, thiz, new JDescrambler(env, descramblerBinderObj));
}

static ssize_t getSubSampleInfo(JNIEnv *env, jint numSubSamples,
        jintArray numBytesOfClearDataObj, jintArray numBytesOfEncryptedDataObj,
        DescramblerPlugin::SubSample **outSubSamples) {

    if (numSubSamples <= 0 || numSubSamples >=
            (signed)(INT32_MAX / sizeof(DescramblerPlugin::SubSample)) ) {
        // subSamples array may silently overflow if number of samples are
        // too large.  Use INT32_MAX as maximum allocation size may be less
        // than SIZE_MAX on some platforms.
        ALOGE("numSubSamples is invalid!");
        return -1;
    }

    jboolean isCopy;
    ssize_t totalSize = 0;

    jint *numBytesOfClearData =
        (numBytesOfClearDataObj == NULL)
            ? NULL
            : env->GetIntArrayElements(numBytesOfClearDataObj, &isCopy);

    jint *numBytesOfEncryptedData =
        (numBytesOfEncryptedDataObj == NULL)
            ? NULL
            : env->GetIntArrayElements(numBytesOfEncryptedDataObj, &isCopy);

    DescramblerPlugin::SubSample *subSamples =
            new(std::nothrow) DescramblerPlugin::SubSample[numSubSamples];

    if (subSamples == NULL) {
        ALOGE("Failed to allocate SubSample array!");
        return -1;
    }

    for (jint i = 0; i < numSubSamples; ++i) {
        subSamples[i].mNumBytesOfClearData =
            (numBytesOfClearData == NULL) ? 0 : numBytesOfClearData[i];

        subSamples[i].mNumBytesOfEncryptedData =
            (numBytesOfEncryptedData == NULL)
                ? 0 : numBytesOfEncryptedData[i];

        totalSize += subSamples[i].mNumBytesOfClearData +
                subSamples[i].mNumBytesOfEncryptedData;
    }

    if (numBytesOfEncryptedData != NULL) {
        env->ReleaseIntArrayElements(
                numBytesOfEncryptedDataObj, numBytesOfEncryptedData, 0);
        numBytesOfEncryptedData = NULL;
    }

    if (numBytesOfClearData != NULL) {
        env->ReleaseIntArrayElements(
                numBytesOfClearDataObj, numBytesOfClearData, 0);
        numBytesOfClearData = NULL;
    }

    *outSubSamples = subSamples;

    return totalSize;
}

static jint android_media_MediaDescrambler_native_descramble(
        JNIEnv *env, jobject thiz, jbyte key, jint numSubSamples,
        jintArray numBytesOfClearDataObj, jintArray numBytesOfEncryptedDataObj,
        jobject srcBuf, jint srcOffset, jobject dstBuf, jint dstOffset) {
    sp<JDescrambler> descrambler = getDescrambler(env, thiz);
    if (descrambler == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1;
    }

    DescramblerPlugin::SubSample *subSamples = NULL;
    ssize_t totalLength = getSubSampleInfo(
            env, numSubSamples, numBytesOfClearDataObj,
            numBytesOfEncryptedDataObj, &subSamples);
    if (totalLength < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "Invalid sub sample info!");
        return -1;
    }

    ssize_t result = -1;
    void *srcPtr = NULL, *dstPtr = NULL;
    jbyteArray srcArray = NULL, dstArray = NULL;
    status_t err = getBufferAndSize(
            env, srcBuf, srcOffset, totalLength, &srcPtr, &srcArray);

    if (err == OK) {
        if (dstBuf == NULL) {
            dstPtr = srcPtr;
        } else {
            err = getBufferAndSize(
                    env, dstBuf, dstOffset, totalLength, &dstPtr, &dstArray);
        }
    }

    if (err == OK) {
        result = descrambler->descramble(
                key, numSubSamples, totalLength, subSamples,
                srcPtr, srcOffset, dstPtr, dstOffset);
    }

    delete[] subSamples;
    if (srcArray != NULL) {
        env->ReleaseByteArrayElements(srcArray, (jbyte *)srcPtr, 0);
    }
    if (dstArray != NULL) {
        env->ReleaseByteArrayElements(dstArray, (jbyte *)dstPtr, 0);
    }
    return result;
}

static const JNINativeMethod gMethods[] = {
    { "native_release", "()V",
            (void *)android_media_MediaDescrambler_native_release },
    { "native_init", "()V",
            (void *)android_media_MediaDescrambler_native_init },
    { "native_setup", "(Landroid/os/IBinder;)V",
            (void *)android_media_MediaDescrambler_native_setup },
    { "native_descramble", "(BI[I[ILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;I)I",
            (void *)android_media_MediaDescrambler_native_descramble },
};

int register_android_media_Descrambler(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaDescrambler", gMethods, NELEM(gMethods));
}

