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

#pragma once

#include <SkCamera.h>
#include <SkDrawable.h>
#include <SkMatrix.h>

#include <private/hwui/DrawGlInfo.h>

#include <utils/KeyedVector.h>
#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "CanvasProperty.h"
#include "Debug.h"
#include "GlFunctorLifecycleListener.h"
#include "Matrix.h"
#include "RenderProperties.h"
#include "TreeInfo.h"
#include "hwui/Bitmap.h"

#include <vector>

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;

namespace android {
namespace uirenderer {

class Rect;
class Layer;

struct RecordedOp;
struct RenderNodeOp;

typedef RecordedOp BaseOpType;
typedef RenderNodeOp NodeOpType;

namespace VectorDrawable {
class Tree;
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

struct FunctorContainer {
    Functor* functor;
    GlFunctorLifecycleListener* listener;
};

/**
 * Data structure that holds the list of commands used in display list stream
 */
class DisplayList {
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

        // clip at the beginning of a reorder section, applied to reordered children
        const ClipBase* reorderClip;
    };

    DisplayList();
    virtual ~DisplayList();

    // index of DisplayListOp restore, after which projected descendants should be drawn
    int projectionReceiveIndex;

    const LsaVector<Chunk>& getChunks() const { return chunks; }
    const LsaVector<BaseOpType*>& getOps() const { return ops; }

    const LsaVector<NodeOpType*>& getChildren() const { return children; }

    const LsaVector<sk_sp<Bitmap>>& getBitmapResources() const { return bitmapResources; }

    size_t addChild(NodeOpType* childOp);

    void ref(VirtualLightRefBase* prop) { referenceHolders.push_back(prop); }

    size_t getUsedSize() { return allocator.usedSize(); }

    virtual bool isEmpty() const { return ops.empty(); }
    virtual bool hasFunctor() const { return !functors.empty(); }
    virtual bool hasVectorDrawables() const { return !vectorDrawables.empty(); }
    virtual bool isSkiaDL() const { return false; }
    virtual bool reuseDisplayList(RenderNode* node, renderthread::CanvasContext* context) {
        return false;
    }

    virtual void syncContents();
    virtual void updateChildren(std::function<void(RenderNode*)> updateFn);
    virtual bool prepareListAndChildren(
            TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer,
            std::function<void(RenderNode*, TreeObserver&, TreeInfo&, bool)> childFn);

    virtual void output(std::ostream& output, uint32_t level);

protected:
    // allocator into which all ops and LsaVector arrays allocated
    LinearAllocator allocator;
    LinearStdAllocator<void*> stdAllocator;

private:
    LsaVector<Chunk> chunks;
    LsaVector<BaseOpType*> ops;

    // list of Ops referring to RenderNode children for quick, non-drawing traversal
    LsaVector<NodeOpType*> children;

    // Resources - Skia objects + 9 patches referred to by this DisplayList
    LsaVector<sk_sp<Bitmap>> bitmapResources;
    LsaVector<const SkPath*> pathResources;
    LsaVector<const Res_png_9patch*> patchResources;
    LsaVector<std::unique_ptr<const SkPaint>> paints;
    LsaVector<std::unique_ptr<const SkRegion>> regions;
    LsaVector<sp<VirtualLightRefBase>> referenceHolders;

    // List of functors
    LsaVector<FunctorContainer> functors;

    // List of VectorDrawables that need to be notified of pushStaging. Note that this list gets
    // nothing
    // but a callback during sync DisplayList, unlike the list of functors defined above, which
    // gets special treatment exclusive for webview.
    LsaVector<VectorDrawableRoot*> vectorDrawables;

    void cleanupResources();
};

};  // namespace uirenderer
};  // namespace android
