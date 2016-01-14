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

#include "AssetAtlas.h"
#include "Caches.h"
#include "Image.h"

#include <GLES2/gl2ext.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

void AssetAtlas::init(sp<GraphicBuffer> buffer, int64_t* map, int count) {
    if (mImage) {
        return;
    }

    ATRACE_NAME("AssetAtlas::init");

    mImage = new Image(buffer);
    if (mImage->getTexture()) {
        if (!mTexture) {
            Caches& caches = Caches::getInstance();
            mTexture = new Texture(caches);
            mTexture->wrap(mImage->getTexture(),
                    buffer->getWidth(), buffer->getHeight(), GL_RGBA);
            createEntries(caches, map, count);
        }
    } else {
        ALOGW("Could not create atlas image");
        terminate();
    }
}

void AssetAtlas::terminate() {
    delete mImage;
    mImage = nullptr;
    delete mTexture;
    mTexture = nullptr;
    mEntries.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Entries
///////////////////////////////////////////////////////////////////////////////

AssetAtlas::Entry* AssetAtlas::getEntry(const SkPixelRef* pixelRef) const {
    auto result = mEntries.find(pixelRef);
    return result != mEntries.end() ? result->second.get() : nullptr;
}

Texture* AssetAtlas::getEntryTexture(const SkPixelRef* pixelRef) const {
    auto result = mEntries.find(pixelRef);
    return result != mEntries.end() ? result->second->texture : nullptr;
}

/**
 * Delegates changes to wrapping and filtering to the base atlas texture
 * instead of applying the changes to the virtual textures.
 */
struct DelegateTexture: public Texture {
    DelegateTexture(Caches& caches, Texture* delegate)
            : Texture(caches), mDelegate(delegate) { }

    virtual void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) override {
        mDelegate->setWrapST(wrapS, wrapT, bindTexture, force, renderTarget);
    }

    virtual void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) override {
        mDelegate->setFilterMinMag(min, mag, bindTexture, force, renderTarget);
    }

private:
    Texture* const mDelegate;
}; // struct DelegateTexture

void AssetAtlas::createEntries(Caches& caches, int64_t* map, int count) {
    const float width = float(mTexture->width());
    const float height = float(mTexture->height());

    for (int i = 0; i < count; ) {
        SkPixelRef* pixelRef = reinterpret_cast<SkPixelRef*>(map[i++]);
        // NOTE: We're converting from 64 bit signed values to 32 bit
        // signed values. This is guaranteed to be safe because the "x"
        // and "y" coordinate values are guaranteed to be representable
        // with 32 bits. The array is 64 bits wide so that it can carry
        // pointers on 64 bit architectures.
        const int x = static_cast<int>(map[i++]);
        const int y = static_cast<int>(map[i++]);

        // Bitmaps should never be null, we're just extra paranoid
        if (!pixelRef) continue;

        const UvMapper mapper(
                x / width, (x + pixelRef->info().width()) / width,
                y / height, (y + pixelRef->info().height()) / height);

        Texture* texture = new DelegateTexture(caches, mTexture);
        texture->blend = !SkAlphaTypeIsOpaque(pixelRef->info().alphaType());
        texture->wrap(mTexture->id(), pixelRef->info().width(),
                pixelRef->info().height(), mTexture->format());

        std::unique_ptr<Entry> entry(new Entry(pixelRef, texture, mapper, *this));
        texture->uvMapper = &entry->uvMapper;

        mEntries.emplace(entry->pixelRef, std::move(entry));
    }
}

}; // namespace uirenderer
}; // namespace android
