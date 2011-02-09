/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef NATIVE_WINDOW_WRAPPER_H_

#define NATIVE_WINDOW_WRAPPER_H_

#include <surfaceflinger/Surface.h>
#include <gui/SurfaceTextureClient.h>

namespace android {

// Both Surface and SurfaceTextureClient are RefBase that implement the
// ANativeWindow interface, but at different addresses. ANativeWindow is not
// a RefBase but acts like one for use with sp<>.  This wrapper converts a
// Surface or SurfaceTextureClient into a single reference-counted object
// that holds an sp reference to the underlying Surface or SurfaceTextureClient,
// It provides a method to get the ANativeWindow.

struct NativeWindowWrapper : RefBase {
    NativeWindowWrapper(
            const sp<Surface> &surface) :
        mSurface(surface) { }

    NativeWindowWrapper(
            const sp<SurfaceTextureClient> &surfaceTextureClient) :
        mSurfaceTextureClient(surfaceTextureClient) { }

    sp<ANativeWindow> getNativeWindow() const {
        if (mSurface != NULL) {
            return mSurface;
        } else {
            return mSurfaceTextureClient;
        }
    }

    // If needed later we can provide a method to ask what kind of native window

private:
    // At most one of mSurface and mSurfaceTextureClient will be non-NULL
    const sp<Surface> mSurface;
    const sp<SurfaceTextureClient> mSurfaceTextureClient;

    DISALLOW_EVIL_CONSTRUCTORS(NativeWindowWrapper);
};

}  // namespace android

#endif  // NATIVE_WINDOW_WRAPPER_H_
