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

#ifndef ANDROID_HWUI_DISPLAY_LIST_H
#define ANDROID_HWUI_DISPLAY_LIST_H

#include <SkCamera.h>
#include <SkMatrix.h>

#include <private/hwui/DrawGlInfo.h>

#include <utils/KeyedVector.h>
#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "Debug.h"
#include "CanvasProperty.h"
#include "DeferredDisplayList.h"
#include "GlFunctorLifecycleListener.h"
#include "Matrix.h"
#include "RenderProperties.h"

#include <vector>

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;

namespace android {
namespace uirenderer {

class DeferredDisplayList;
class DisplayListOp;
class DisplayListCanvas;
class OpenGLRenderer;
class Rect;
class Layer;

#if HWUI_NEW_OPS
struct RecordedOp;
struct RenderNodeOp;

typedef RecordedOp BaseOpType;
typedef RenderNodeOp NodeOpType;
#else
class DrawRenderNodeOp;

typedef DisplayListOp BaseOpType;
typedef DrawRenderNodeOp NodeOpType;
#endif

namespace VectorDrawable {
class Tree;
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

/**
 * Holds data used in the playback a tree of DisplayLists.
 */
struct PlaybackStateStruct {
protected:
    PlaybackStateStruct(OpenGLRenderer& renderer, int replayFlags, LinearAllocator* allocator)
            : mRenderer(renderer)
            , mReplayFlags(replayFlags)
            , mAllocator(allocator) {}

public:
    OpenGLRenderer& mRenderer;
    const int mReplayFlags;

    // Allocator with the lifetime of a single frame. replay uses an Allocator owned by the struct,
    // while defer shares the DeferredDisplayList's Allocator
    // TODO: move this allocator to be owned by object with clear frame lifecycle
    LinearAllocator * const mAllocator;

    SkPath* allocPathForFrame() {
        return mRenderer.allocPathForFrame();
    }
};

struct DeferStateStruct : public PlaybackStateStruct {
    DeferStateStruct(DeferredDisplayList& deferredList, OpenGLRenderer& renderer, int replayFlags)
            : PlaybackStateStruct(renderer, replayFlags, &(deferredList.mAllocator)),
            mDeferredList(deferredList) {}

    DeferredDisplayList& mDeferredList;
};

struct ReplayStateStruct : public PlaybackStateStruct {
    ReplayStateStruct(OpenGLRenderer& renderer, Rect& dirty, int replayFlags)
            : PlaybackStateStruct(renderer, replayFlags, &mReplayAllocator),
            mDirty(dirty) {}

    Rect& mDirty;
    LinearAllocator mReplayAllocator;
};

struct FunctorContainer {
    Functor* functor;
    GlFunctorLifecycleListener* listener;
};

/**
 * Data structure that holds the list of commands used in display list stream
 */
class DisplayList {
    friend class DisplayListCanvas;
    friend class RecordingCanvas;
public:
    struct Chunk {
        // range of included ops in DisplayList::ops()
        size_t beginOpIndex;
        size_t endOpIndex;

        // range of included children in DisplayList::children()
        size_t beginChildIndex;
        size_t endChildIndex;

        // whether children with non-zero Z in the chunk should be reordered
        bool reorderChildren;
#if HWUI_NEW_OPS
        const ClipBase* reorderClip;
#endif
    };

    DisplayList();
    ~DisplayList();

    // index of DisplayListOp restore, after which projected descendants should be drawn
    int projectionReceiveIndex;

    const LsaVector<Chunk>& getChunks() const { return chunks; }
    const LsaVector<BaseOpType*>& getOps() const { return ops; }

    const LsaVector<NodeOpType*>& getChildren() const { return children; }

    const LsaVector<const SkBitmap*>& getBitmapResources() const { return bitmapResources; }
    const LsaVector<FunctorContainer>& getFunctors() const { return functors; }
    const LsaVector<VectorDrawableRoot*>& getVectorDrawables() { return vectorDrawables; }

    size_t addChild(NodeOpType* childOp);


    void ref(VirtualLightRefBase* prop) {
        referenceHolders.push_back(prop);
    }

    size_t getUsedSize() {
        return allocator.usedSize();
    }
    bool isEmpty() {
#if HWUI_NEW_OPS
        return ops.empty();
#else
        return !hasDrawOps;
#endif
    }

private:
    // allocator into which all ops and LsaVector arrays allocated
    LinearAllocator allocator;
    LinearStdAllocator<void*> stdAllocator;

    LsaVector<Chunk> chunks;
    LsaVector<BaseOpType*> ops;

    // list of Ops referring to RenderNode children for quick, non-drawing traversal
    LsaVector<NodeOpType*> children;

    // Resources - Skia objects + 9 patches referred to by this DisplayList
    LsaVector<const SkBitmap*> bitmapResources;
    LsaVector<const SkPath*> pathResources;
    LsaVector<const Res_png_9patch*> patchResources;
    LsaVector<std::unique_ptr<const SkPaint>> paints;
    LsaVector<std::unique_ptr<const SkRegion>> regions;
    LsaVector< sp<VirtualLightRefBase> > referenceHolders;

    // List of functors
    LsaVector<FunctorContainer> functors;

    // List of VectorDrawables that need to be notified of pushStaging. Note that this list gets nothing
    // but a callback during sync DisplayList, unlike the list of functors defined above, which
    // gets special treatment exclusive for webview.
    LsaVector<VectorDrawableRoot*> vectorDrawables;

    bool hasDrawOps; // only used if !HWUI_NEW_OPS

    void cleanupResources();
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H
