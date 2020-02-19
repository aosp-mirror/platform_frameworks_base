/*
 * Copyright (C) 2019 The Android Open Source Project
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
#ifndef ANDROID_GRAPHICS_BITMAP_H
#define ANDROID_GRAPHICS_BITMAP_H

#include <android/bitmap.h>
#include <android/data_space.h>
#include <cutils/compiler.h>
#include <jni.h>
#include <sys/cdefs.h>

struct AHardwareBuffer;

__BEGIN_DECLS

/**
 * Opaque handle for a native graphics bitmap.
 */
typedef struct ABitmap ABitmap;

/**
 * Retrieve bitmapInfo for the provided java bitmap even if it has been recycled.  In the case of a
 * recycled bitmap the values contained in the bitmap before it was recycled are returned.
 *
 * NOTE: This API does not need to remain as an APEX API if/when we pull libjnigraphics into the
 *       UI module.
 */
ANDROID_API AndroidBitmapInfo ABitmap_getInfoFromJava(JNIEnv* env, jobject bitmapObj);

/**
 *
 * @return ptr to an opaque handle to the native bitmap or null if the java bitmap has been recycled
 *         or does not exist.
 */
ANDROID_API ABitmap* ABitmap_acquireBitmapFromJava(JNIEnv* env, jobject bitmapObj);

ANDROID_API ABitmap* ABitmap_copy(ABitmap* srcBitmap, AndroidBitmapFormat dstFormat);

ANDROID_API void ABitmap_acquireRef(ABitmap* bitmap);
ANDROID_API void ABitmap_releaseRef(ABitmap* bitmap);

ANDROID_API AndroidBitmapInfo ABitmap_getInfo(ABitmap* bitmap);
ANDROID_API ADataSpace ABitmap_getDataSpace(ABitmap* bitmap);

ANDROID_API void* ABitmap_getPixels(ABitmap* bitmap);
ANDROID_API void ABitmap_notifyPixelsChanged(ABitmap* bitmap);

ANDROID_API AndroidBitmapFormat ABitmapConfig_getFormatFromConfig(JNIEnv* env, jobject bitmapConfigObj);
ANDROID_API jobject ABitmapConfig_getConfigFromFormat(JNIEnv* env, AndroidBitmapFormat format);

// NDK access
ANDROID_API int ABitmap_compress(const AndroidBitmapInfo* info, ADataSpace dataSpace, const void* pixels,
                     AndroidBitmapCompressFormat format, int32_t quality, void* userContext,
                     AndroidBitmap_CompressWriteFunc);
/**
 *  Retrieve the native object associated with a HARDWARE Bitmap.
 *
 *  Client must not modify it while a Bitmap is wrapping it.
 *
 *  @param bitmap Handle to an android.graphics.Bitmap.
 *  @return on success, a pointer to the
 *         AHardwareBuffer associated with bitmap. This acquires
 *         a reference on the buffer, and the client must call
 *         AHardwareBuffer_release when finished with it.
 */
ANDROID_API AHardwareBuffer* ABitmap_getHardwareBuffer(ABitmap* bitmap);

__END_DECLS

#ifdef	__cplusplus
namespace android {
namespace graphics {
    class Bitmap {
    public:
        Bitmap() : mBitmap(nullptr) {}
        Bitmap(JNIEnv* env, jobject bitmapObj) :
                mBitmap(ABitmap_acquireBitmapFromJava(env, bitmapObj)) {}
        Bitmap(const Bitmap& src) : mBitmap(src.mBitmap) { ABitmap_acquireRef(src.mBitmap); }
        ~Bitmap() { ABitmap_releaseRef(mBitmap); }

        // copy operator
        Bitmap& operator=(const Bitmap& other) {
            if (&other != this) {
                ABitmap_releaseRef(mBitmap);
                mBitmap = other.mBitmap;
                ABitmap_acquireRef(mBitmap);
            }
            return *this;
        }

        // move operator
        Bitmap& operator=(Bitmap&& other) {
            if (&other != this) {
                ABitmap_releaseRef(mBitmap);
                mBitmap = other.mBitmap;
                other.mBitmap = nullptr;
            }
            return *this;
        }

        Bitmap copy(AndroidBitmapFormat dstFormat) const {
            return Bitmap(ABitmap_copy(mBitmap, dstFormat));
        }

        bool isValid() const { return mBitmap != nullptr; }
        bool isEmpty() const {
            AndroidBitmapInfo info = getInfo();
            return info.width <= 0 || info.height <= 0;
        }
        void reset() {
            ABitmap_releaseRef(mBitmap);
            mBitmap = nullptr;
        }

        ABitmap* get() const { return mBitmap; }

        AndroidBitmapInfo getInfo() const { return ABitmap_getInfo(mBitmap); }
        ADataSpace getDataSpace() const { return ABitmap_getDataSpace(mBitmap); }
        void* getPixels() const { return ABitmap_getPixels(mBitmap); }
        void notifyPixelsChanged() const { ABitmap_notifyPixelsChanged(mBitmap); }
        AHardwareBuffer* getHardwareBuffer() const { return ABitmap_getHardwareBuffer(mBitmap); }

    private:
        // takes ownership of the provided ABitmap
        Bitmap(ABitmap* bitmap) : mBitmap(bitmap) {}

        ABitmap* mBitmap;
    };
}; // namespace graphics
}; // namespace android
#endif // __cplusplus

#endif // ANDROID_GRAPHICS_BITMAP_H
