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

#include <GLES2/gl2.h>

#include "TextureCache.h"

namespace android {
namespace uirenderer {

TextureCache::TextureCache(unsigned int maxEntries): mCache(maxEntries) {
    mCache.setOnEntryRemovedListener(this);
}

TextureCache::~TextureCache() {
    mCache.clear();
}

void TextureCache::operator()(SkBitmap* key, Texture* value) {
    LOGD("Entry removed");
    if (value) {
        glDeleteTextures(1, &value->id);
        delete value;
    }
}

Texture* TextureCache::get(SkBitmap* bitmap) {
    Texture* texture = mCache.get(bitmap);
    if (!texture) {
        texture = new Texture;
        generateTexture(bitmap, texture, false);
        mCache.put(bitmap, texture);
    } else if (bitmap->getGenerationID() != texture->generation) {
        generateTexture(bitmap, texture, true);
    }
    return texture;
}

Texture* TextureCache::remove(SkBitmap* bitmap) {
    return mCache.remove(bitmap);
}

void TextureCache::clear() {
    mCache.clear();
}

void TextureCache::generateTexture(SkBitmap* bitmap, Texture* texture, bool regenerate) {
    SkAutoLockPixels alp(*bitmap);
    if (!bitmap->readyToDraw()) {
        LOGE("Cannot generate texture from bitmap");
        return;
    }

    if (!regenerate) {
        texture->generation = bitmap->getGenerationID();
        texture->width = bitmap->width();
        texture->height = bitmap->height();

        glGenTextures(1, &texture->id);
    }

    glBindTexture(GL_TEXTURE_2D, texture->id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());

    switch (bitmap->getConfig()) {
    case SkBitmap::kRGB_565_Config:
        texture->blend = false;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, bitmap->rowBytesAsPixels(), texture->height, 0,
                GL_RGB, GL_UNSIGNED_SHORT_5_6_5, bitmap->getPixels());
        break;
    case SkBitmap::kARGB_8888_Config:
        texture->blend = true;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bitmap->rowBytesAsPixels(), texture->height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        break;
    default:
        break;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glBindTexture(GL_TEXTURE_2D, 0);
}

}; // namespace uirenderer
}; // namespace android
