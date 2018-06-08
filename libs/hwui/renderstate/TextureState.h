/*
 * Copyright (C) 2015 The Android Open Source Project
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
#ifndef RENDERSTATE_TEXTURESTATE_H
#define RENDERSTATE_TEXTURESTATE_H

#include "Texture.h"
#include "Vertex.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <memory>

namespace android {
namespace uirenderer {

class Texture;

class TextureState {
    friend class Caches;  // TODO: move to RenderState
public:
    void constructTexture(Caches& caches);

    /**
     * Activate the specified texture unit. The texture unit must
     * be specified using an integer number (0 for GL_TEXTURE0 etc.)
     */
    void activateTexture(GLuint textureUnit);

    /**
     * Invalidate the cached value of the active texture unit.
     */
    void resetActiveTexture();

    /**
     * Binds the specified texture as a GL_TEXTURE_2D texture.
     * All texture bindings must be performed with this method or
     * bindTexture(GLenum, GLuint).
     */
    void bindTexture(GLuint texture);

    /**
     * Binds the specified texture with the specified render target.
     * All texture bindings must be performed with this method or
     * bindTexture(GLuint).
     */
    void bindTexture(GLenum target, GLuint texture);

    /**
     * Deletes the specified texture and clears it from the cache
     * of bound textures.
     * All textures must be deleted using this method.
     */
    void deleteTexture(GLuint texture);

    /**
     * Signals that the cache of bound textures should be cleared.
     * Other users of the context may have altered which textures are bound.
     */
    void resetBoundTextures();

    /**
     * Clear the cache of bound textures.
     */
    void unbindTexture(GLuint texture);

    Texture* getShadowLutTexture() { return mShadowLutTexture.get(); }

private:
    // total number of texture units available for use
    static const int kTextureUnitsCount = 4;

    TextureState();
    ~TextureState();
    GLuint mTextureUnit;

    // Caches texture bindings for the GL_TEXTURE_2D target
    GLuint mBoundTextures[kTextureUnitsCount];

    std::unique_ptr<Texture> mShadowLutTexture;
};

} /* namespace uirenderer */
} /* namespace android */

#endif  // RENDERSTATE_BLEND_H
