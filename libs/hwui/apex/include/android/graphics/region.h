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
#ifndef ANDROID_GRAPHICS_REGION_H
#define ANDROID_GRAPHICS_REGION_H

#include <cutils/compiler.h>
#include <android/rect.h>
#include <sys/cdefs.h>
#include <jni.h>

__BEGIN_DECLS

/**
* Opaque handle for a native graphics region iterator.
*/
typedef struct ARegionIterator ARegionIterator;

/**
 * Returns a iterator for a Java android.graphics.Region
 *
 * @param env
 * @param region
 * @return ARegionIterator that must be closed and must not live longer than the life
 *         of the jobject.  It returns nullptr if the region is not a valid object.
 */
ANDROID_API ARegionIterator* ARegionIterator_acquireIterator(JNIEnv* env, jobject region);

ANDROID_API void ARegionIterator_releaseIterator(ARegionIterator* iterator);

ANDROID_API bool ARegionIterator_isComplex(ARegionIterator* iterator);

ANDROID_API bool ARegionIterator_isDone(ARegionIterator* iterator);

ANDROID_API void ARegionIterator_next(ARegionIterator* iterator);

ANDROID_API ARect ARegionIterator_getRect(ARegionIterator* iterator);

ANDROID_API ARect ARegionIterator_getTotalBounds(ARegionIterator* iterator);

__END_DECLS

#ifdef	__cplusplus
namespace android {
namespace graphics {
    class RegionIterator {
    public:
        RegionIterator(JNIEnv* env, jobject region)
                : mIterator(ARegionIterator_acquireIterator(env, region)) {}
        ~RegionIterator() { ARegionIterator_releaseIterator(mIterator); }

        bool isValid() const { return mIterator != nullptr; }
        bool isComplex() { return ARegionIterator_isComplex(mIterator); }
        bool isDone() { return ARegionIterator_isDone(mIterator); }
        void next() { ARegionIterator_next(mIterator); }
        ARect getRect() { return ARegionIterator_getRect(mIterator); }
        ARect getTotalBounds() const { return ARegionIterator_getTotalBounds(mIterator); }
    private:
        ARegionIterator* mIterator;
    };
}; // namespace graphics
}; // namespace android

#endif // __cplusplus
#endif // ANDROID_GRAPHICS_REGION_H