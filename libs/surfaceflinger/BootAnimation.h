/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_BOOTANIMATION_H
#define ANDROID_BOOTANIMATION_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>
#include <utils/AssetManager.h>

#include <ui/ISurfaceComposer.h>
#include <ui/SurfaceComposerClient.h>

#include <EGL/egl.h>
#include <GLES/gl.h>

#include "Barrier.h"

class SkBitmap;

namespace android {

class AssetManager;
class EGLNativeWindowSurface;

// ---------------------------------------------------------------------------

class BootAnimation : public Thread
{
public:
                BootAnimation(const sp<ISurfaceComposer>& composer);
    virtual     ~BootAnimation();

    const sp<SurfaceComposerClient>& session() const;
    virtual void        requestExit();

private:
    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

    struct Texture {
        GLint   w;
        GLint   h;
        GLuint  name;
    };

    status_t initTexture(Texture* texture, AssetManager& asset, const char* name);
    bool android();

    sp<SurfaceComposerClient>       mSession;
    AssetManager mAssets;
    Texture     mAndroid[2];
    int         mWidth;
    int         mHeight;
    EGLDisplay  mDisplay;
    EGLDisplay  mContext;
    EGLDisplay  mSurface;
    sp<Surface> mFlingerSurface;
    sp<EGLNativeWindowSurface> mNativeWindowSurface;
    Barrier mBarrier;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_BOOTANIMATION_H
