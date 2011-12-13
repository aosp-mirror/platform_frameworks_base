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

#ifndef ANDROID_HWUI_TEXTURE_H
#define ANDROID_HWUI_TEXTURE_H

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

/**
 * Represents an OpenGL texture.
 */
struct Texture {
    Texture() {
        cleanup = false;
        bitmapSize = 0;

        wrapS = GL_CLAMP_TO_EDGE;
        wrapT = GL_CLAMP_TO_EDGE;

        minFilter = GL_NEAREST;
        magFilter = GL_NEAREST;

        firstFilter = true;
        firstWrap = true;
    }

    void setWrap(GLenum wrap, bool bindTexture = false, bool force = false,
                GLenum renderTarget = GL_TEXTURE_2D) {
        setWrapST(wrap, wrap, bindTexture, force, renderTarget);
    }

    void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false, bool force = false,
            GLenum renderTarget = GL_TEXTURE_2D) {

        if (firstWrap || force || wrapS != this->wrapS || wrapT != this->wrapT) {
            firstWrap = false;

            this->wrapS = wrapS;
            this->wrapT = wrapT;

            if (bindTexture) {
                glBindTexture(renderTarget, id);
            }

            glTexParameteri(renderTarget, GL_TEXTURE_WRAP_S, wrapS);
            glTexParameteri(renderTarget, GL_TEXTURE_WRAP_T, wrapT);
        }
    }

    void setFilter(GLenum filter, bool bindTexture = false, bool force = false,
                GLenum renderTarget = GL_TEXTURE_2D) {
        setFilterMinMag(filter, filter, bindTexture, force, renderTarget);
    }

    void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false, bool force = false,
            GLenum renderTarget = GL_TEXTURE_2D) {

        if (firstFilter || force || min != minFilter || mag != magFilter) {
            firstFilter = false;

            minFilter = min;
            magFilter = mag;

            if (bindTexture) {
                glBindTexture(renderTarget, id);
            }

            glTexParameteri(renderTarget, GL_TEXTURE_MIN_FILTER, min);
            glTexParameteri(renderTarget, GL_TEXTURE_MAG_FILTER, mag);
        }
    }

    /**
     * Name of the texture.
     */
    GLuint id;
    /**
     * Generation of the backing bitmap,
     */
    uint32_t generation;
    /**
     * Indicates whether the texture requires blending.
     */
    bool blend;
    /**
     * Width of the backing bitmap.
     */
    uint32_t width;
    /**
     * Height of the backing bitmap.
     */
    uint32_t height;
    /**
     * Indicates whether this texture should be cleaned up after use.
     */
    bool cleanup;
    /**
     * Optional, size of the original bitmap.
     */
    uint32_t bitmapSize;

    /**
     * Last wrap modes set on this texture. Defaults to GL_CLAMP_TO_EDGE.
     */
    GLenum wrapS;
    GLenum wrapT;

    /**
     * Last filters set on this texture. Defaults to GL_NEAREST.
     */
    GLenum minFilter;
    GLenum magFilter;

private:
    bool firstFilter;
    bool firstWrap;
}; // struct Texture

class AutoTexture {
public:
    AutoTexture(const Texture* texture): mTexture(texture) { }
    ~AutoTexture() {
        if (mTexture && mTexture->cleanup) {
            glDeleteTextures(1, &mTexture->id);
            delete mTexture;
        }
    }

private:
    const Texture* mTexture;
}; // class AutoTexture

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TEXTURE_H
