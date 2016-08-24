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

#include "PixelBuffer.h"
#include "Rect.h"
#include "Texture.h"
#include "Vertex.h"

#include <GLES3/gl3.h>
#include <SkGlyph.h>
#include <utils/Log.h>


namespace android {
namespace uirenderer {

class Caches;

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

    CacheBlock(uint16_t x, uint16_t y, uint16_t width, uint16_t height):
            mX(x), mY(y), mWidth(width), mHeight(height), mNext(nullptr), mPrev(nullptr) {
    }

    static CacheBlock* insertBlock(CacheBlock* head, CacheBlock* newBlock);
    static CacheBlock* removeBlock(CacheBlock* head, CacheBlock* blockToRemove);

    void output() {
        CacheBlock* currBlock = this;
        while (currBlock) {
            ALOGD("Block: this, x, y, w, h = %p, %d, %d, %d, %d",
                    currBlock, currBlock->mX, currBlock->mY,
                    currBlock->mWidth, currBlock->mHeight);
            currBlock = currBlock->mNext;
        }
    }
};

class CacheTexture {
public:
    CacheTexture(uint16_t width, uint16_t height, GLenum format, uint32_t maxQuadCount);
    ~CacheTexture();

    void reset();
    void init();

    void releaseMesh();
    void releasePixelBuffer();

    void allocatePixelBuffer();
    void allocateMesh();

    // Returns true if glPixelStorei(GL_UNPACK_ROW_LENGTH) must be reset
    // This method will also call setDirty(false)
    bool upload();

    bool fitBitmap(const SkGlyph& glyph, uint32_t* retOriginX, uint32_t* retOriginY);

    inline uint16_t getWidth() const {
        return mWidth;
    }

    inline uint16_t getHeight() const {
        return mHeight;
    }

    inline GLenum getFormat() const {
        return mFormat;
    }

    inline uint32_t getOffset(uint16_t x, uint16_t y) const {
        return (y * getWidth() + x) * PixelBuffer::formatSize(mFormat);
    }

    inline const Rect* getDirtyRect() const {
        return &mDirtyRect;
    }

    inline PixelBuffer* getPixelBuffer() const {
        return mPixelBuffer;
    }

    Texture& getTexture() {
        allocatePixelBuffer();
        return mTexture;
    }

    GLuint getTextureId() {
        allocatePixelBuffer();
        return mTexture.id();
    }

    inline bool isDirty() const {
        return mDirty;
    }

    inline bool getLinearFiltering() const {
        return mLinearFiltering;
    }

    /**
     * This method assumes that the proper texture unit is active.
     */
    void setLinearFiltering(bool linearFiltering);

    inline uint16_t getGlyphCount() const {
        return mNumGlyphs;
    }

    TextureVertex* mesh() const {
        return mMesh;
    }

    uint32_t meshElementCount() const {
        return mCurrentQuad * 6;
    }

    uint16_t* indices() const {
        return (uint16_t*) nullptr;
    }

    void resetMesh() {
        mCurrentQuad = 0;
    }

    inline void addQuad(float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float x4, float y4, float u4, float v4) {
        TextureVertex* mesh = mMesh + mCurrentQuad * 4;
        TextureVertex::set(mesh++, x2, y2, u2, v2);
        TextureVertex::set(mesh++, x3, y3, u3, v3);
        TextureVertex::set(mesh++, x1, y1, u1, v1);
        TextureVertex::set(mesh++, x4, y4, u4, v4);
        mCurrentQuad++;
    }

    bool canDraw() const {
        return mCurrentQuad > 0;
    }

    bool endOfMesh() const {
        return mCurrentQuad == mMaxQuadCount;
    }

private:
    void setDirty(bool dirty);

    PixelBuffer* mPixelBuffer = nullptr;
    Texture mTexture;
    uint32_t mWidth, mHeight;
    GLenum mFormat;
    bool mLinearFiltering = false;
    bool mDirty = false;
    uint16_t mNumGlyphs = 0;
    TextureVertex* mMesh = nullptr;
    uint32_t mCurrentQuad = 0;
    uint32_t mMaxQuadCount;
    Caches& mCaches;
    CacheBlock* mCacheBlocks;
    bool mHasUnpackRowLength;
    Rect mDirtyRect;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHE_TEXTURE_H
