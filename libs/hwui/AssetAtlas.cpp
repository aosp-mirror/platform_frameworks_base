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

#include "AssetAtlas.h"
#include "Caches.h"

#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

void AssetAtlas::init(sp<GraphicBuffer> buffer, int* map, int count) {
    if (mImage) {
        return;
    }

    mImage = new Image(buffer);

    if (mImage->getTexture()) {
        Caches& caches = Caches::getInstance();

        mTexture = new Texture(caches);
        mTexture->id = mImage->getTexture();
        mTexture->width = buffer->getWidth();
        mTexture->height = buffer->getHeight();

        createEntries(caches, map, count);
    } else {
        ALOGW("Could not create atlas image");

        delete mImage;
        mImage = NULL;
        mTexture = NULL;
    }

    mGenerationId++;
}

void AssetAtlas::terminate() {
    if (mImage) {
        delete mImage;
        mImage = NULL;

        delete mTexture;
        mTexture = NULL;

        for (size_t i = 0; i < mEntries.size(); i++) {
            delete mEntries.valueAt(i);
        }
        mEntries.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Entries
///////////////////////////////////////////////////////////////////////////////

AssetAtlas::Entry* AssetAtlas::getEntry(SkBitmap* const bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? mEntries.valueAt(index) : NULL;
}

Texture* AssetAtlas::getEntryTexture(SkBitmap* const bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? mEntries.valueAt(index)->texture : NULL;
}

/**
 * Delegates changes to wrapping and filtering to the base atlas texture
 * instead of applying the changes to the virtual textures.
 */
struct DelegateTexture: public Texture {
    DelegateTexture(Caches& caches, Texture* delegate): Texture(caches), mDelegate(delegate) { }

    virtual void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) {
        mDelegate->setWrapST(wrapS, wrapT, bindTexture, force, renderTarget);
    }

    virtual void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) {
        mDelegate->setFilterMinMag(min, mag, bindTexture, force, renderTarget);
    }

private:
    Texture* const mDelegate;
}; // struct DelegateTexture

/**
 * TODO: This method does not take the rotation flag into account
 */
void AssetAtlas::createEntries(Caches& caches, int* map, int count) {
    const float width = float(mTexture->width);
    const float height = float(mTexture->height);

    for (int i = 0; i < count; ) {
        SkBitmap* bitmap = (SkBitmap*) map[i++];
        int x = map[i++];
        int y = map[i++];
        bool rotated = map[i++] > 0;

        // Bitmaps should never be null, we're just extra paranoid
        if (!bitmap) continue;

        const UvMapper mapper(
                x / width, (x + bitmap->width()) / width,
                y / height, (y + bitmap->height()) / height);

        Texture* texture = new DelegateTexture(caches, mTexture);
        texture->id = mTexture->id;
        texture->blend = !bitmap->isOpaque();
        texture->width = bitmap->width();
        texture->height = bitmap->height();

        Entry* entry = new Entry(bitmap, x, y, rotated, texture, mapper, *this);
        texture->uvMapper = &entry->uvMapper;

        mEntries.add(entry->bitmap, entry);
    }
}

}; // namespace uirenderer
}; // namespace android
