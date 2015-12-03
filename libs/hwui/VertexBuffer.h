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

#ifndef ANDROID_HWUI_VERTEX_BUFFER_H
#define ANDROID_HWUI_VERTEX_BUFFER_H

#include <algorithm>

namespace android {
namespace uirenderer {

class VertexBuffer {
public:
    enum MeshFeatureFlags {
        kNone = 0,
        kAlpha = 1 << 0,
        kIndices = 1 << 1,
    };

    VertexBuffer()
            : mBuffer(nullptr)
            , mIndices(nullptr)
            , mVertexCount(0)
            , mIndexCount(0)
            , mAllocatedVertexCount(0)
            , mAllocatedIndexCount(0)
            , mByteCount(0)
            , mMeshFeatureFlags(kNone)
            , mReallocBuffer(nullptr)
            , mCleanupMethod(nullptr)
            , mCleanupIndexMethod(nullptr)
    {}

    ~VertexBuffer() {
        if (mCleanupMethod) mCleanupMethod(mBuffer);
        if (mCleanupIndexMethod) mCleanupIndexMethod(mIndices);
    }

    /**
       This should be the only method used by the Tessellator. Subsequent calls to
       alloc will allocate space within the first allocation (useful if you want to
       eventually allocate multiple regions within a single VertexBuffer, such as
       with PathTessellator::tessellateLines())
     */
    template <class TYPE>
    TYPE* alloc(int vertexCount) {
        if (mVertexCount) {
            TYPE* reallocBuffer = (TYPE*)mReallocBuffer;
            // already have allocated the buffer, re-allocate space within
            if (mReallocBuffer != mBuffer) {
                // not first re-allocation, leave space for degenerate triangles to separate strips
                reallocBuffer += 2;
            }
            mReallocBuffer = reallocBuffer + vertexCount;
            return reallocBuffer;
        }
        mAllocatedVertexCount = vertexCount;
        mVertexCount = vertexCount;
        mByteCount = mVertexCount * sizeof(TYPE);
        mReallocBuffer = mBuffer = (void*)new TYPE[vertexCount];

        mCleanupMethod = &(cleanup<TYPE>);

        return (TYPE*)mBuffer;
    }

    template <class TYPE>
    TYPE* allocIndices(int indexCount) {
        mAllocatedIndexCount = indexCount;
        mIndexCount = indexCount;
        mIndices = (void*)new TYPE[indexCount];

        mCleanupIndexMethod = &(cleanup<TYPE>);

        return (TYPE*)mIndices;
    }

    template <class TYPE>
    void copyInto(const VertexBuffer& srcBuffer, float xOffset, float yOffset) {
        int verticesToCopy = srcBuffer.getVertexCount();

        TYPE* dst = alloc<TYPE>(verticesToCopy);
        TYPE* src = (TYPE*)srcBuffer.getBuffer();

        for (int i = 0; i < verticesToCopy; i++) {
            TYPE::copyWithOffset(&dst[i], src[i], xOffset, yOffset);
        }
    }

    /**
     * Brute force bounds computation, used only if the producer of this
     * vertex buffer can't determine bounds more simply/efficiently
     */
    template <class TYPE>
    void computeBounds(int vertexCount = 0) {
        if (!mVertexCount) {
            mBounds.setEmpty();
            return;
        }

        // default: compute over every vertex
        if (vertexCount == 0) vertexCount = mVertexCount;

        TYPE* current = (TYPE*)mBuffer;
        TYPE* end = current + vertexCount;
        mBounds.set(current->x, current->y, current->x, current->y);
        for (; current < end; current++) {
            mBounds.expandToCover(current->x, current->y);
        }
    }

    const void* getBuffer() const { return mBuffer; }
    const void* getIndices() const { return mIndices; }
    const Rect& getBounds() const { return mBounds; }
    unsigned int getVertexCount() const { return mVertexCount; }
    unsigned int getSize() const { return mByteCount; }
    unsigned int getIndexCount() const { return mIndexCount; }
    void updateIndexCount(unsigned int newCount)  {
        mIndexCount = std::min(newCount, mAllocatedIndexCount);
    }
    void updateVertexCount(unsigned int newCount)  {
        mVertexCount = std::min(newCount, mAllocatedVertexCount);
    }
    MeshFeatureFlags getMeshFeatureFlags() const { return mMeshFeatureFlags; }
    void setMeshFeatureFlags(int flags) {
        mMeshFeatureFlags = static_cast<MeshFeatureFlags>(flags);
    }

    void setBounds(Rect bounds) { mBounds = bounds; }

    template <class TYPE>
    void createDegenerateSeparators(int allocSize) {
        TYPE* end = (TYPE*)mBuffer + mVertexCount;
        for (TYPE* degen = (TYPE*)mBuffer + allocSize; degen < end; degen += 2 + allocSize) {
            memcpy(degen, degen - 1, sizeof(TYPE));
            memcpy(degen + 1, degen + 2, sizeof(TYPE));
        }
    }

private:
    template <class TYPE>
    static void cleanup(void* buffer) {
        delete[] (TYPE*)buffer;
    }

    Rect mBounds;

    void* mBuffer;
    void* mIndices;

    unsigned int mVertexCount;
    unsigned int mIndexCount;
    unsigned int mAllocatedVertexCount;
    unsigned int mAllocatedIndexCount;
    unsigned int mByteCount;

    MeshFeatureFlags mMeshFeatureFlags;

    void* mReallocBuffer; // used for multi-allocation

    void (*mCleanupMethod)(void*);
    void (*mCleanupIndexMethod)(void*);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_VERTEX_BUFFER_H
