/*
 * Copyright 2013, The Android Open Source Project
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
#define LOG_TAG "MediaMuxer-JNI"
#include <utils/Log.h>

#include "android_media_Streams.h"
#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <unistd.h>
#include <fcntl.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaMuxer.h>

namespace android {

struct fields_t {
    jmethodID arrayID;
};

static fields_t gFields;

}

using namespace android;

static jint android_media_MediaMuxer_addTrack(
        JNIEnv *env, jclass /* clazz */, jlong nativeObject, jobjectArray keys,
        jobjectArray values) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Muxer was not set up correctly");
        return -1;
    }

    sp<AMessage> trackformat;
    status_t err = ConvertKeyValueArraysToMessage(env, keys, values,
                                                  &trackformat);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "ConvertKeyValueArraysToMessage got an error");
        return err;
    }

    // Return negative value when errors happen in addTrack.
    jint trackIndex = muxer->addTrack(trackformat);

    if (trackIndex < 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to add the track to the muxer");
        return -1;
    }
    return trackIndex;
}

static void android_media_MediaMuxer_writeSampleData(
        JNIEnv *env, jclass /* clazz */, jlong nativeObject, jint trackIndex,
        jobject byteBuf, jint offset, jint size, jlong timeUs, jint flags) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Muxer was not set up correctly");
        return;
    }

    // Try to convert the incoming byteBuffer into ABuffer
    void *dst = env->GetDirectBufferAddress(byteBuf);

    jlong dstSize;
    jbyteArray byteArray = NULL;

    if (dst == NULL) {

        byteArray =
            (jbyteArray)env->CallObjectMethod(byteBuf, gFields.arrayID);

        if (byteArray == NULL) {
            jniThrowException(env, "java/lang/IllegalArgumentException",
                              "byteArray is null");
            return;
        }

        jboolean isCopy;
        dst = env->GetByteArrayElements(byteArray, &isCopy);

        dstSize = env->GetArrayLength(byteArray);
    } else {
        dstSize = env->GetDirectBufferCapacity(byteBuf);
    }

    if (dstSize < (offset + size)) {
        ALOGE("writeSampleData saw wrong dstSize %lld, size  %d, offset %d",
              (long long)dstSize, size, offset);
        if (byteArray != NULL) {
            env->ReleaseByteArrayElements(byteArray, (jbyte *)dst, 0);
        }
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "sample has a wrong size");
        return;
    }

    sp<ABuffer> buffer = new ABuffer((char *)dst + offset, size);

    status_t err = muxer->writeSampleData(buffer, trackIndex, timeUs, flags);

    if (byteArray != NULL) {
        env->ReleaseByteArrayElements(byteArray, (jbyte *)dst, 0);
    }

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "writeSampleData returned an error");
    }
    return;
}

// Constructor counterpart.
static jlong android_media_MediaMuxer_native_setup(
        JNIEnv *env, jclass clazz, jobject fileDescriptor,
        jint format) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    ALOGV("native_setup: fd %d", fd);

    // It appears that if an invalid file descriptor is passed through
    // binder calls, the server-side of the inter-process function call
    // is skipped. As a result, the check at the server-side to catch
    // the invalid file descritpor never gets invoked. This is to workaround
    // this issue by checking the file descriptor first before passing
    // it through binder call.
    int flags = fcntl(fd, F_GETFL);
    if (flags == -1) {
        ALOGE("Fail to get File Status Flags err: %s", strerror(errno));
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "Invalid file descriptor");
        return 0;
    }

    // fd must be in read-write mode or write-only mode.
    if ((flags & (O_RDWR | O_WRONLY)) == 0) {
        ALOGE("File descriptor is not in read-write mode or write-only mode");
        jniThrowException(env, "java/io/IOException",
                "File descriptor is not in read-write mode or write-only mode");
        return 0;
    }

    MediaMuxer::OutputFormat fileFormat =
        static_cast<MediaMuxer::OutputFormat>(format);
    sp<MediaMuxer> muxer = new MediaMuxer(fd, fileFormat);
    muxer->incStrong(clazz);
    return reinterpret_cast<jlong>(muxer.get());
}

static void android_media_MediaMuxer_setOrientationHint(
        JNIEnv *env, jclass /* clazz */, jlong nativeObject, jint degrees) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Muxer was not set up correctly");
        return;
    }
    status_t err = muxer->setOrientationHint(degrees);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to set orientation hint");
        return;
    }

}

static void android_media_MediaMuxer_setLocation(
        JNIEnv *env, jclass /* clazz */, jlong nativeObject, jint latitude, jint longitude) {
    MediaMuxer* muxer = reinterpret_cast<MediaMuxer *>(nativeObject);

    status_t res = muxer->setLocation(latitude, longitude);
    if (res != OK) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to set location");
        return;
    }
}

static void android_media_MediaMuxer_start(JNIEnv *env, jclass /* clazz */,
                                           jlong nativeObject) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Muxer was not set up correctly");
        return;
    }
    status_t err = muxer->start();

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to start the muxer");
        return;
    }

}

static void android_media_MediaMuxer_stop(JNIEnv *env, jclass /* clazz */,
                                          jlong nativeObject) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Muxer was not set up correctly");
        return;
    }

    status_t err = muxer->stop();

    if (err != OK) {
        ALOGE("Error during stop:%d", err);
        jniThrowException(env, "java/lang/IllegalStateException",
                    "Error during stop(), muxer would have stopped already");
        return;
    }
}

static void android_media_MediaMuxer_native_release(
        JNIEnv* /* env */, jclass clazz, jlong nativeObject) {
    sp<MediaMuxer> muxer(reinterpret_cast<MediaMuxer *>(nativeObject));
    if (muxer != NULL) {
        muxer->decStrong(clazz);
    }
}

static const JNINativeMethod gMethods[] = {

    { "nativeAddTrack", "(J[Ljava/lang/String;[Ljava/lang/Object;)I",
        (void *)android_media_MediaMuxer_addTrack },

    { "nativeSetOrientationHint", "(JI)V",
        (void *)android_media_MediaMuxer_setOrientationHint},

    { "nativeSetLocation", "(JII)V",
        (void *)android_media_MediaMuxer_setLocation},

    { "nativeStart", "(J)V", (void *)android_media_MediaMuxer_start},

    { "nativeWriteSampleData", "(JILjava/nio/ByteBuffer;IIJI)V",
        (void *)android_media_MediaMuxer_writeSampleData },

    { "nativeStop", "(J)V", (void *)android_media_MediaMuxer_stop},

    { "nativeSetup", "(Ljava/io/FileDescriptor;I)J",
        (void *)android_media_MediaMuxer_native_setup },

    { "nativeRelease", "(J)V",
        (void *)android_media_MediaMuxer_native_release },

};

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaMuxer(JNIEnv *env) {
    int err = AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaMuxer", gMethods, NELEM(gMethods));

    jclass byteBufClass = env->FindClass("java/nio/ByteBuffer");
    CHECK(byteBufClass != NULL);

    gFields.arrayID =
        env->GetMethodID(byteBufClass, "array", "()[B");
    CHECK(gFields.arrayID != NULL);

    return err;
}
