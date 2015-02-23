/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Log.h>

#include "Caches.h"
#include "Texture.h"

namespace android {
namespace uirenderer {

void Texture::setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture, bool force,
        GLenum renderTarget) {

    if (mFirstWrap || force || wrapS != mWrapS || wrapT != mWrapT) {
        mFirstWrap = false;

        mWrapS = wrapS;
        mWrapT = wrapT;

        if (bindTexture) {
            mCaches.textureState().bindTexture(renderTarget, id);
        }

        glTexParameteri(renderTarget, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(renderTarget, GL_TEXTURE_WRAP_T, wrapT);
    }
}

void Texture::setFilterMinMag(GLenum min, GLenum mag, bool bindTexture, bool force,
        GLenum renderTarget) {

    if (mFirstFilter || force || min != mMinFilter || mag != mMagFilter) {
        mFirstFilter = false;

        mMinFilter = min;
        mMagFilter = mag;

        if (bindTexture) {
            mCaches.textureState().bindTexture(renderTarget, id);
        }

        if (mipMap && min == GL_LINEAR) min = GL_LINEAR_MIPMAP_LINEAR;

        glTexParameteri(renderTarget, GL_TEXTURE_MIN_FILTER, min);
        glTexParameteri(renderTarget, GL_TEXTURE_MAG_FILTER, mag);
    }
}

void Texture::deleteTexture() const {
    mCaches.textureState().deleteTexture(id);
}

}; // namespace uirenderer
}; // namespace android
