/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_SF_GLEXTENSION_H
#define ANDROID_SF_GLEXTENSION_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/String8.h>
#include <utils/SortedVector.h>
#include <utils/Singleton.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

namespace android {
// ---------------------------------------------------------------------------

class GLExtensions : public Singleton<GLExtensions>
{
    friend class Singleton<GLExtensions>;

    bool mHaveTextureExternal   : 1;
    bool mHaveNpot              : 1;
    bool mHaveDirectTexture     : 1;
    bool mHaveFramebufferObject : 1;

    String8 mVendor;
    String8 mRenderer;
    String8 mVersion;
    String8 mExtensions;
    String8 mEglVendor;
    String8 mEglVersion;
    String8 mEglExtensions;
    SortedVector<String8> mExtensionList;

    GLExtensions(const GLExtensions&);
    GLExtensions& operator = (const GLExtensions&);

protected:
    GLExtensions();

public:
    inline bool haveTextureExternal() const {
        return mHaveTextureExternal;
    }
    inline bool haveNpot() const {
        return mHaveNpot;
    }
    inline bool haveDirectTexture() const {
        return mHaveDirectTexture;
    }

    inline bool haveFramebufferObject() const {
        return mHaveFramebufferObject;
    }

    void initWithGLStrings(
            GLubyte const* vendor,
            GLubyte const* renderer,
            GLubyte const* version,
            GLubyte const* extensions,
            char const* egl_vendor,
            char const* egl_version,
            char const* egl_extensions);

    char const* getVendor() const;
    char const* getRenderer() const;
    char const* getVersion() const;
    char const* getExtension() const;

    char const* getEglVendor() const;
    char const* getEglVersion() const;
    char const* getEglExtension() const;

    bool hasExtension(char const* extension) const;
};


// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SF_GLEXTENSION_H
