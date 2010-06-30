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
        texture = generateTexture(bitmap);
        mCache.put(bitmap, texture);
    }
    return texture;
}

Texture* TextureCache::remove(SkBitmap* bitmap) {
    return mCache.remove(bitmap);
}

void TextureCache::clear() {
    mCache.clear();
}

Texture* TextureCache::generateTexture(SkBitmap* bitmap) {
    Texture* texture = new Texture;

    texture->width = bitmap->width();
    texture->height = bitmap->height();

    glGenTextures(1, &texture->id);
    glBindTexture(GL_TEXTURE_2D, texture->id);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    switch (bitmap->getConfig()) {
    case SkBitmap::kRGB_565_Config:
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB565, texture->width, texture->height,
                0, GL_RGB565, GL_UNSIGNED_SHORT_5_6_5, bitmap->getPixels());
        break;
    case SkBitmap::kARGB_8888_Config:
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texture->width, texture->height,
                0, GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        break;
    }

    return texture;
}

}; // namespace uirenderer
}; // namespace android
