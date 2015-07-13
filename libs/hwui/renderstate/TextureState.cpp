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
#include "renderstate/TextureState.h"

namespace android {
namespace uirenderer {

// Must define as many texture units as specified by kTextureUnitsCount
const GLenum kTextureUnits[] = {
    GL_TEXTURE0,
    GL_TEXTURE1,
    GL_TEXTURE2,
    GL_TEXTURE3
};

TextureState::TextureState()
        : mTextureUnit(0) {
    glActiveTexture(kTextureUnits[0]);
    resetBoundTextures();

    GLint maxTextureUnits;
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &maxTextureUnits);
    LOG_ALWAYS_FATAL_IF(maxTextureUnits < kTextureUnitsCount,
            "At least %d texture units are required!", kTextureUnitsCount);
}

void TextureState::activateTexture(GLuint textureUnit) {
    LOG_ALWAYS_FATAL_IF(textureUnit >= kTextureUnitsCount,
            "Tried to use texture unit index %d, only %d exist",
            textureUnit, kTextureUnitsCount);
    if (mTextureUnit != textureUnit) {
        glActiveTexture(kTextureUnits[textureUnit]);
        mTextureUnit = textureUnit;
    }
}

void TextureState::resetActiveTexture() {
    mTextureUnit = -1;
}

void TextureState::bindTexture(GLuint texture) {
    if (mBoundTextures[mTextureUnit] != texture) {
        glBindTexture(GL_TEXTURE_2D, texture);
        mBoundTextures[mTextureUnit] = texture;
    }
}

void TextureState::bindTexture(GLenum target, GLuint texture) {
    if (target == GL_TEXTURE_2D) {
        bindTexture(texture);
    } else {
        // GLConsumer directly calls glBindTexture() with
        // target=GL_TEXTURE_EXTERNAL_OES, don't cache this target
        // since the cached state could be stale
        glBindTexture(target, texture);
    }
}

void TextureState::deleteTexture(GLuint texture) {
    // When glDeleteTextures() is called on a currently bound texture,
    // OpenGL ES specifies that the texture is then considered unbound
    // Consider the following series of calls:
    //
    // glGenTextures -> creates texture name 2
    // glBindTexture(2)
    // glDeleteTextures(2) -> 2 is now unbound
    // glGenTextures -> can return 2 again
    //
    // If we don't call glBindTexture(2) after the second glGenTextures
    // call, any texture operation will be performed on the default
    // texture (name=0)

    unbindTexture(texture);

    glDeleteTextures(1, &texture);
}

void TextureState::resetBoundTextures() {
    for (int i = 0; i < kTextureUnitsCount; i++) {
        mBoundTextures[i] = 0;
    }
}

void TextureState::unbindTexture(GLuint texture) {
    for (int i = 0; i < kTextureUnitsCount; i++) {
        if (mBoundTextures[i] == texture) {
            mBoundTextures[i] = 0;
        }
    }
}

} /* namespace uirenderer */
} /* namespace android */

