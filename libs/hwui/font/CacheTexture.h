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
        mX(x), mY(y), mWidth(width), mHeight(height), mNext(NULL), mPrev(NULL)
    {
    }

    static CacheBlock* insertBlock(CacheBlock* head, CacheBlock *newBlock);

    static CacheBlock* removeBlock(CacheBlock* head, CacheBlock *blockToRemove);

    void output() {
        CacheBlock *currBlock = this;
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
        if (mTexture) {
            delete[] mTexture;
        }
        if (mTextureId) {
            glDeleteTextures(1, &mTextureId);
        }
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

    bool fitBitmap(const SkGlyph& glyph, uint32_t *retOriginX, uint32_t *retOriginY);

    uint8_t* mTexture;
    GLuint mTextureId;
    uint16_t mWidth;
    uint16_t mHeight;
    bool mLinearFiltering;
    bool mDirty;
    uint16_t mNumGlyphs;
    CacheBlock* mCacheBlocks;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHE_TEXTURE_H
