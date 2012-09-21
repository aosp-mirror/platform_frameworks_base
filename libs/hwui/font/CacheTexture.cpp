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

#include <utils/Log.h>

#include "Debug.h"
#include "CacheTexture.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// CacheBlock
///////////////////////////////////////////////////////////////////////////////

/**
 * Insert new block into existing linked list of blocks. Blocks are sorted in increasing-width
 * order, except for the final block (the remainder space at the right, since we fill from the
 * left).
 */
CacheBlock* CacheBlock::insertBlock(CacheBlock* head, CacheBlock* newBlock) {
#if DEBUG_FONT_RENDERER
    ALOGD("insertBlock: this, x, y, w, h = %p, %d, %d, %d, %d",
            newBlock, newBlock->mX, newBlock->mY,
            newBlock->mWidth, newBlock->mHeight);
#endif

    CacheBlock* currBlock = head;
    CacheBlock* prevBlock = NULL;

    while (currBlock && currBlock->mY != TEXTURE_BORDER_SIZE) {
        if (newBlock->mWidth < currBlock->mWidth) {
            newBlock->mNext = currBlock;
            newBlock->mPrev = prevBlock;
            currBlock->mPrev = newBlock;

            if (prevBlock) {
                prevBlock->mNext = newBlock;
                return head;
            } else {
                return newBlock;
            }
        }

        prevBlock = currBlock;
        currBlock = currBlock->mNext;
    }

    // new block larger than all others - insert at end (but before the remainder space, if there)
    newBlock->mNext = currBlock;
    newBlock->mPrev = prevBlock;

    if (currBlock) {
        currBlock->mPrev = newBlock;
    }

    if (prevBlock) {
        prevBlock->mNext = newBlock;
        return head;
    } else {
        return newBlock;
    }
}

CacheBlock* CacheBlock::removeBlock(CacheBlock* head, CacheBlock* blockToRemove) {
#if DEBUG_FONT_RENDERER
    ALOGD("removeBlock: this, x, y, w, h = %p, %d, %d, %d, %d",
            blockToRemove, blockToRemove->mX, blockToRemove->mY,
            blockToRemove->mWidth, blockToRemove->mHeight);
#endif

    CacheBlock* newHead = head;
    CacheBlock* nextBlock = blockToRemove->mNext;
    CacheBlock* prevBlock = blockToRemove->mPrev;

    if (prevBlock) {
        prevBlock->mNext = nextBlock;
    } else {
        newHead = nextBlock;
    }

    if (nextBlock) {
        nextBlock->mPrev = prevBlock;
    }

    delete blockToRemove;

    return newHead;
}

///////////////////////////////////////////////////////////////////////////////
// CacheTexture
///////////////////////////////////////////////////////////////////////////////

bool CacheTexture::fitBitmap(const SkGlyph& glyph, uint32_t* retOriginX, uint32_t* retOriginY) {
    if (glyph.fHeight + TEXTURE_BORDER_SIZE * 2 > mHeight) {
        return false;
    }

    uint16_t glyphW = glyph.fWidth + TEXTURE_BORDER_SIZE;
    uint16_t glyphH = glyph.fHeight + TEXTURE_BORDER_SIZE;

    // roundedUpW equals glyphW to the next multiple of CACHE_BLOCK_ROUNDING_SIZE.
    // This columns for glyphs that are close but not necessarily exactly the same size. It trades
    // off the loss of a few pixels for some glyphs against the ability to store more glyphs
    // of varying sizes in one block.
    uint16_t roundedUpW = (glyphW + CACHE_BLOCK_ROUNDING_SIZE - 1) & -CACHE_BLOCK_ROUNDING_SIZE;

    CacheBlock* cacheBlock = mCacheBlocks;
    while (cacheBlock) {
        // Store glyph in this block iff: it fits the block's remaining space and:
        // it's the remainder space (mY == 0) or there's only enough height for this one glyph
        // or it's within ROUNDING_SIZE of the block width
        if (roundedUpW <= cacheBlock->mWidth && glyphH <= cacheBlock->mHeight &&
                (cacheBlock->mY == TEXTURE_BORDER_SIZE ||
                        (cacheBlock->mWidth - roundedUpW < CACHE_BLOCK_ROUNDING_SIZE))) {
            if (cacheBlock->mHeight - glyphH < glyphH) {
                // Only enough space for this glyph - don't bother rounding up the width
                roundedUpW = glyphW;
            }

            *retOriginX = cacheBlock->mX;
            *retOriginY = cacheBlock->mY;

            // If this is the remainder space, create a new cache block for this column. Otherwise,
            // adjust the info about this column.
            if (cacheBlock->mY == TEXTURE_BORDER_SIZE) {
                uint16_t oldX = cacheBlock->mX;
                // Adjust remainder space dimensions
                cacheBlock->mWidth -= roundedUpW;
                cacheBlock->mX += roundedUpW;

                if (mHeight - glyphH >= glyphH) {
                    // There's enough height left over to create a new CacheBlock
                    CacheBlock* newBlock = new CacheBlock(oldX, glyphH + TEXTURE_BORDER_SIZE,
                            roundedUpW, mHeight - glyphH - TEXTURE_BORDER_SIZE);
#if DEBUG_FONT_RENDERER
                    ALOGD("fitBitmap: Created new block: this, x, y, w, h = %p, %d, %d, %d, %d",
                            newBlock, newBlock->mX, newBlock->mY,
                            newBlock->mWidth, newBlock->mHeight);
#endif
                    mCacheBlocks = CacheBlock::insertBlock(mCacheBlocks, newBlock);
                }
            } else {
                // Insert into current column and adjust column dimensions
                cacheBlock->mY += glyphH;
                cacheBlock->mHeight -= glyphH;
#if DEBUG_FONT_RENDERER
                ALOGD("fitBitmap: Added to existing block: this, x, y, w, h = %p, %d, %d, %d, %d",
                        cacheBlock, cacheBlock->mX, cacheBlock->mY,
                        cacheBlock->mWidth, cacheBlock->mHeight);
#endif
            }

            if (cacheBlock->mHeight < fmin(glyphH, glyphW)) {
                // If remaining space in this block is too small to be useful, remove it
                mCacheBlocks = CacheBlock::removeBlock(mCacheBlocks, cacheBlock);
            }

            mDirty = true;
            const Rect r(*retOriginX - TEXTURE_BORDER_SIZE, *retOriginY - TEXTURE_BORDER_SIZE,
                    *retOriginX + glyphW, *retOriginY + glyphH);
            mDirtyRect.unionWith(r);
            mNumGlyphs++;

#if DEBUG_FONT_RENDERER
            ALOGD("fitBitmap: current block list:");
            mCacheBlocks->output();
#endif

            return true;
        }
        cacheBlock = cacheBlock->mNext;
    }
#if DEBUG_FONT_RENDERER
    ALOGD("fitBitmap: returning false for glyph of size %d, %d", glyphW, glyphH);
#endif
    return false;
}

}; // namespace uirenderer
}; // namespace android
