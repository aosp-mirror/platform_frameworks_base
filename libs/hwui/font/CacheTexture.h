/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_HWUI_CACHE_TEXTURE_H
#define ANDROID_HWUI_CACHE_TEXTURE_H

#include <GLES2/gl2.h>

#include <SkScalerContext.h>

#include <utils/Log.h>

#include "FontUtil.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

/**
 * CacheBlock is a node in a linked list of current free space areas in a CacheTexture.
 * Using CacheBlocks enables us to pack the cache from top to bottom as well as left to right.
 * When we add a glyph to the cache, we see if it fits within one of the existing columns that
 * have already been started (this is the case if the glyph fits vertically as well as
 * horizontally, and if its width is sufficiently close to the column width to avoid
 * sub-optimal packing of small glyphs into wide columns). If there is no column in which the
 * glyph fits, we check the final node, which is the remaining space in the cache, creating
 * a new column as appropriate.
 *
 * As columns fill up, we remove their CacheBlock from the list to avoid having to check
 * small blocks in the future.
 */
struct CacheBlock {
    uint16_t mX;
    uint16_t mY;
    uint16_t mWidth;
    uint16_t mHeight;
    CacheBlock* mNext;
    CacheBlock* mPrev;

    CacheBlock(uint16_t x, uint16_t y, uint16_t width, uint16_t height, bool empty = false):
            mX(x), mY(y), mWidth(width), mHeight(height), mNext(NULL), mPrev(NULL) {
    }

    static CacheBlock* insertBlock(CacheBlock* head, CacheBlock* newBlock);

    static CacheBlock* removeBlock(CacheBlock* head, CacheBlock* blockToRemove);

    void output() {
        CacheBlock* currBlock = this;
        while (currBlock) {
            ALOGD("Block: this, x, y, w, h = %p, %d, %d, %d, %d",
                    currBlock, currBlock->mX, currBlock->mY, currBlock->mWidth, currBlock->mHeight);
            currBlock = currBlock->mNext;
        }
    }
};

class CacheTexture {
public:
    CacheTexture(uint16_t width, uint16_t height) :
            mTexture(NULL), mTextureId(0), mWidth(width), mHeight(height),
            mLinearFiltering(false), mDirty(false), mNumGlyphs(0) {
        mCacheBlocks = new CacheBlock(TEXTURE_BORDER_SIZE, TEXTURE_BORDER_SIZE,
                mWidth - TEXTURE_BORDER_SIZE, mHeight - TEXTURE_BORDER_SIZE, true);
    }

    ~CacheTexture() {
        releaseTexture();
        reset();
    }

    void reset() {
        // Delete existing cache blocks
        while (mCacheBlocks != NULL) {
            CacheBlock* tmpBlock = mCacheBlocks;
            mCacheBlocks = mCacheBlocks->mNext;
            delete tmpBlock;
        }
        mNumGlyphs = 0;
    }

    void init() {
        // reset, then create a new remainder space to start again
        reset();
        mCacheBlocks = new CacheBlock(TEXTURE_BORDER_SIZE, TEXTURE_BORDER_SIZE,
                mWidth - TEXTURE_BORDER_SIZE, mHeight - TEXTURE_BORDER_SIZE, true);
    }

    void releaseTexture() {
        if (mTexture) {
            delete[] mTexture;
            mTexture = NULL;
        }
        if (mTextureId) {
            glDeleteTextures(1, &mTextureId);
            mTextureId = 0;
        }
        mDirty = false;
    }

    /**
     * This method assumes that the proper texture unit is active.
     */
    void allocateTexture() {
        if (!mTexture) {
            mTexture = new uint8_t[mWidth * mHeight];
        }

        if (!mTextureId) {
            glGenTextures(1, &mTextureId);

            glBindTexture(GL_TEXTURE_2D, mTextureId);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            // Initialize texture dimensions
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, mWidth, mHeight, 0,
                    GL_ALPHA, GL_UNSIGNED_BYTE, 0);

            const GLenum filtering = getLinearFiltering() ? GL_LINEAR : GL_NEAREST;
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
    }

    bool fitBitmap(const SkGlyph& glyph, uint32_t* retOriginX, uint32_t* retOriginY);

    inline uint16_t getWidth() const {
        return mWidth;
    }

    inline uint16_t getHeight() const {
        return mHeight;
    }

    inline const Rect* getDirtyRect() const {
        return &mDirtyRect;
    }

    inline uint8_t* getTexture() const {
        return mTexture;
    }

    GLuint getTextureId() {
        allocateTexture();
        return mTextureId;
    }

    inline bool isDirty() const {
        return mDirty;
    }

    inline void setDirty(bool dirty) {
        mDirty = dirty;
        if (!dirty) {
            mDirtyRect.setEmpty();
        }
    }

    inline bool getLinearFiltering() const {
        return mLinearFiltering;
    }

    /**
     * This method assumes that the proper texture unit is active.
     */
    void setLinearFiltering(bool linearFiltering, bool bind = true) {
        if (linearFiltering != mLinearFiltering) {
            mLinearFiltering = linearFiltering;

            const GLenum filtering = linearFiltering ? GL_LINEAR : GL_NEAREST;
            if (bind) glBindTexture(GL_TEXTURE_2D, getTextureId());
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filtering);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filtering);
        }
    }

    inline uint16_t getGlyphCount() const {
        return mNumGlyphs;
    }

private:
    uint8_t* mTexture;
    GLuint mTextureId;
    uint16_t mWidth;
    uint16_t mHeight;
    bool mLinearFiltering;
    bool mDirty;
    uint16_t mNumGlyphs;
    CacheBlock* mCacheBlocks;
    Rect mDirtyRect;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHE_TEXTURE_H
