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

#include "Caches.h"
#include "utils/TraceUtils.h"

#include <GLES3/gl3.h>
#include <memory>
#include <SkCanvas.h>
#include <SkBitmap.h>

namespace android {
namespace uirenderer {

// Must define as many texture units as specified by kTextureUnitsCount
const GLenum kTextureUnits[] = {
    GL_TEXTURE0,
    GL_TEXTURE1,
    GL_TEXTURE2,
    GL_TEXTURE3
};

static void uploadToTexture(bool resize, GLenum format, GLenum type, GLsizei stride, GLsizei bpp,
        GLsizei width, GLsizei height, const GLvoid * data) {

    glPixelStorei(GL_UNPACK_ALIGNMENT, bpp);
    const bool useStride = stride != width
            && Caches::getInstance().extensions().hasUnpackRowLength();
    if ((stride == width) || useStride) {
        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, data);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
        }

        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    } else {
        //  With OpenGL ES 2.0 we need to copy the bitmap in a temporary buffer
        //  if the stride doesn't match the width

        GLvoid * temp = (GLvoid *) malloc(width * height * bpp);
        if (!temp) return;

        uint8_t * pDst = (uint8_t *)temp;
        uint8_t * pSrc = (uint8_t *)data;
        for (GLsizei i = 0; i < height; i++) {
            memcpy(pDst, pSrc, width * bpp);
            pDst += width * bpp;
            pSrc += stride * bpp;
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, temp);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, temp);
        }

        free(temp);
    }
}

static void uploadSkBitmapToTexture(const SkBitmap& bitmap,
        bool resize, GLenum format, GLenum type) {
    uploadToTexture(resize, format, type, bitmap.rowBytesAsPixels(), bitmap.bytesPerPixel(),
            bitmap.width(), bitmap.height(), bitmap.getPixels());
}

void TextureState::generateTexture(const SkBitmap* bitmap, Texture* texture, bool regenerate) {
    SkAutoLockPixels alp(*bitmap);

    if (!bitmap->readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    ATRACE_FORMAT("Upload %ux%u Texture", bitmap->width(), bitmap->height());

    // We could also enable mipmapping if both bitmap dimensions are powers
    // of 2 but we'd have to deal with size changes. Let's keep this simple
    const bool canMipMap = Caches::getInstance().extensions().hasNPot();

    // If the texture had mipmap enabled but not anymore,
    // force a glTexImage2D to discard the mipmap levels
    const bool resize = !regenerate || bitmap->width() != int(texture->width) ||
            bitmap->height() != int(texture->height) ||
            (regenerate && canMipMap && texture->mipMap && !bitmap->hasHardwareMipMap());

    if (!regenerate) {
        glGenTextures(1, &texture->id);
    }

    texture->generation = bitmap->getGenerationID();
    texture->width = bitmap->width();
    texture->height = bitmap->height();

    bindTexture(texture->id);

    switch (bitmap->colorType()) {
    case kAlpha_8_SkColorType:
        uploadSkBitmapToTexture(*bitmap, resize, GL_ALPHA, GL_UNSIGNED_BYTE);
        texture->blend = true;
        break;
    case kRGB_565_SkColorType:
        uploadSkBitmapToTexture(*bitmap, resize, GL_RGB, GL_UNSIGNED_SHORT_5_6_5);
        texture->blend = false;
        break;
    case kN32_SkColorType:
        uploadSkBitmapToTexture(*bitmap, resize, GL_RGBA, GL_UNSIGNED_BYTE);
        // Do this after calling getPixels() to make sure Skia's deferred
        // decoding happened
        texture->blend = !bitmap->isOpaque();
        break;
    case kARGB_4444_SkColorType:
    case kIndex_8_SkColorType: {
        SkBitmap rgbaBitmap;
        rgbaBitmap.allocPixels(SkImageInfo::MakeN32(texture->width, texture->height,
                bitmap->alphaType()));
        rgbaBitmap.eraseColor(0);

        SkCanvas canvas(rgbaBitmap);
        canvas.drawBitmap(*bitmap, 0.0f, 0.0f, nullptr);

        uploadSkBitmapToTexture(rgbaBitmap, resize, GL_RGBA, GL_UNSIGNED_BYTE);
        texture->blend = !bitmap->isOpaque();
        break;
    }
    default:
        ALOGW("Unsupported bitmap colorType: %d", bitmap->colorType());
        break;
    }

    if (canMipMap) {
        texture->mipMap = bitmap->hasHardwareMipMap();
        if (texture->mipMap) {
            glGenerateMipmap(GL_TEXTURE_2D);
        }
    }

    if (!regenerate) {
        texture->setFilter(GL_NEAREST);
        texture->setWrap(GL_CLAMP_TO_EDGE);
    }
}

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

