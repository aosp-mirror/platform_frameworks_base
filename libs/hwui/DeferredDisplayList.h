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

#ifndef ANDROID_HWUI_DEFERRED_DISPLAY_LIST_H
#define ANDROID_HWUI_DEFERRED_DISPLAY_LIST_H

#include <utils/Errors.h>
#include <utils/Vector.h>

#include "Matrix.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

class DrawOp;
class DrawOpBatch;
class OpenGLRenderer;
class SkiaShader;

class DeferredDisplayList {
public:
    DeferredDisplayList() { clear(); }
    ~DeferredDisplayList() { clear(); }

    enum OpBatchId {
        kOpBatch_None = -1, // Don't batch
        kOpBatch_Bitmap,
        kOpBatch_Patch,
        kOpBatch_AlphaVertices,
        kOpBatch_Vertices,
        kOpBatch_AlphaMaskTexture,
        kOpBatch_Text,
        kOpBatch_ColorText,

        kOpBatch_Count, // Add other batch ids before this
    };

    bool isEmpty() { return mBatches.isEmpty(); }

    /**
     * Plays back all of the draw ops recorded into batches to the renderer.
     * Adjusts the state of the renderer as necessary, and restores it when complete
     */
    status_t flush(OpenGLRenderer& renderer, Rect& dirty, int32_t flags,
            uint32_t level);

    /**
     * Add a draw op into the DeferredDisplayList, reordering as needed (for performance) if
     * disallowReorder is false, respecting draw order when overlaps occur
     */
    void add(DrawOp* op, bool disallowReorder);

private:
    void clear();


    Vector<DrawOpBatch*> mBatches;
    int mBatchIndices[kOpBatch_Count];
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DEFERRED_DISPLAY_LIST_H
