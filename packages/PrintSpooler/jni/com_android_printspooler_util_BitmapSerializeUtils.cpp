/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "BitmapSerializeUtils"

#include <jni.h>
#include <JNIHelp.h>

#include <android/bitmap.h>
#include <android/log.h>

namespace android {

#define RGBA_8888_COLOR_DEPTH 4

static bool writeAllBytes(const int fd, void* buffer, const size_t byteCount) {
    char* writeBuffer = static_cast<char*>(buffer);
    size_t remainingBytes = byteCount;
    while (remainingBytes > 0) {
        ssize_t writtenByteCount = write(fd, writeBuffer, remainingBytes);
        if (writtenByteCount == -1) {
            if (errno == EINTR) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                    "Error writing to buffer: %d", errno);
            return false;
        }
        remainingBytes -= writtenByteCount;
        writeBuffer += writtenByteCount;
    }
    return true;
}

static bool readAllBytes(const int fd, void* buffer, const size_t byteCount) {
    char* readBuffer = static_cast<char*>(buffer);
    size_t remainingBytes = byteCount;
    while (remainingBytes > 0) {
        ssize_t readByteCount = read(fd, readBuffer, remainingBytes);
        if (readByteCount == -1) {
            if (errno == EINTR) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                    "Error reading from buffer: %d", errno);
            return false;
        }
        remainingBytes -= readByteCount;
        readBuffer += readByteCount;
    }
    return true;
}

static void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    env->ThrowNew(exceptionClass, message);
}

static void throwIllegalStateException(JNIEnv* env, char *message) {
    const char* className = "java/lang/IllegalStateException";
    throwException(env, className, message);
}

static void throwIllegalArgumentException(JNIEnv* env, char* message) {
    const char* className = "java/lang/IllegalArgumentException";
    throwException(env, className, message);
}

static void readBitmapPixels(JNIEnv* env, jclass clazz, jobject jbitmap, jint fd) {
    // Read the info.
    AndroidBitmapInfo readInfo;
    bool read = readAllBytes(fd, (void*) &readInfo, sizeof(AndroidBitmapInfo));
    if (!read) {
        throwIllegalStateException(env, (char*) "Cannot read bitmap info");
        return;
    }

    // Get the info of the target bitmap.
    AndroidBitmapInfo targetInfo;
    int result = AndroidBitmap_getInfo(env, jbitmap, &targetInfo);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot get bitmap info");
        return;
    }

    // Enforce we can reuse the bitmap.
    if (readInfo.width != targetInfo.width || readInfo.height != targetInfo.height
            || readInfo.stride != targetInfo.stride || readInfo.format != targetInfo.format
            || readInfo.flags != targetInfo.flags) {
        throwIllegalArgumentException(env, (char*) "Cannot reuse bitmap");
        return;
    }

    // Lock the pixels.
    void* pixels;
    result = AndroidBitmap_lockPixels(env, jbitmap, &pixels);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot lock bitmap pixels");
        return;
    }

    // Read the pixels.
    size_t byteCount = readInfo.stride * readInfo.height;
    read = readAllBytes(fd, (void*) pixels, byteCount);
    if (!read) {
        throwIllegalStateException(env, (char*) "Cannot read bitmap pixels");
        return;
    }

    // Unlock the pixels.
    result = AndroidBitmap_unlockPixels(env, jbitmap);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot unlock bitmap pixels");
    }
}

static void writeBitmapPixels(JNIEnv* env, jclass clazz, jobject jbitmap, jint fd) {
    // Get the info.
    AndroidBitmapInfo info;
    int result = AndroidBitmap_getInfo(env, jbitmap, &info);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot get bitmap info");
        return;
    }

    // Write the info.
    bool written = writeAllBytes(fd, (void*) &info, sizeof(AndroidBitmapInfo));
    if (!written) {
        throwIllegalStateException(env, (char*) "Cannot write bitmap info");
        return;
    }

    // Lock the pixels.
    void* pixels;
    result = AndroidBitmap_lockPixels(env, jbitmap, &pixels);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot lock bitmap pixels");
        return;
    }

    // Write the pixels.
    size_t byteCount = info.stride * info.height;
    written = writeAllBytes(fd, (void*) pixels, byteCount);
    if (!written) {
        throwIllegalStateException(env, (char*) "Cannot write bitmap pixels");
        return;
    }

    // Unlock the pixels.
    result = AndroidBitmap_unlockPixels(env, jbitmap);
    if (result < 0) {
        throwIllegalStateException(env, (char*) "Cannot unlock bitmap pixels");
    }
}

static JNINativeMethod sMethods[] = {
    {"nativeReadBitmapPixels", "(Landroid/graphics/Bitmap;I)V", (void *) readBitmapPixels},
    {"nativeWriteBitmapPixels", "(Landroid/graphics/Bitmap;I)V", (void *) writeBitmapPixels},
};

int register_com_android_printspooler_util_BitmapSerializeUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/printspooler/util/BitmapSerializeUtils",
        sMethods, NELEM(sMethods));
}

}

jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv *env = NULL;
    if (jvm->GetEnv((void**) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (android::register_com_android_printspooler_util_BitmapSerializeUtils(env) == -1) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
