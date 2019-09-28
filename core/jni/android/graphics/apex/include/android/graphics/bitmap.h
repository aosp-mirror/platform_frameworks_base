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
#include <jni.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

/**
 * Opaque handle for a native graphics bitmap.
 */
typedef struct ABitmap ABitmap;

ABitmap* ABitmap_acquireBitmapFromJava(JNIEnv* env, jobject bitmapObj);

ABitmap* ABitmap_copy(ABitmap* srcBitmap, AndroidBitmapFormat dstFormat);

void ABitmap_acquireRef(ABitmap* bitmap);
void ABitmap_releaseRef(ABitmap* bitmap);

AndroidBitmapInfo ABitmap_getInfo(ABitmap* bitmap);

void* ABitmap_getPixels(ABitmap* bitmap);

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

        const ABitmap* get() const { return mBitmap; }

        AndroidBitmapInfo getInfo() const { return ABitmap_getInfo(mBitmap); }
        void* getPixels() const { return ABitmap_getPixels(mBitmap); }
    private:
        // takes ownership of the provided ABitmap
        Bitmap(ABitmap* bitmap) : mBitmap(bitmap) {}

        ABitmap* mBitmap;
    };
}; // namespace graphics
}; // namespace android
#endif // __cplusplus

#endif // ANDROID_GRAPHICS_BITMAP_H