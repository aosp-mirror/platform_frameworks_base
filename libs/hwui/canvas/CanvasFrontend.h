/*
 * Copyright (C) 2020 The Android Open Source Project
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

// TODO: Can we get the dependencies scoped down more?
#include "CanvasOps.h"
#include "CanvasOpBuffer.h"
#include <SaveFlags.h>

#include <ui/FatVector.h>

#include <optional>

namespace android::uirenderer {

// Exists to avoid forcing all this common logic into the templated class
class CanvasStateHelper {
protected:
    CanvasStateHelper(int width, int height);
    ~CanvasStateHelper() = default;

    struct SaveEntry {
        bool clip : 1 = false;
        bool matrix : 1 = false;
        bool layer : 1 = false;
    };

    template <typename T>
    struct DeferredEntry {
        T entry;
        int deferredSaveCount = 0;

        DeferredEntry() = default;
        DeferredEntry(const T& t) : entry(t) {}
    };

    struct ConservativeClip {
        SkIRect bounds = SkIRect::MakeEmpty();
        bool rect = true;
        bool aa = false;

        bool quickReject(const SkMatrix& matrix, const SkRect& bounds) const;

        void apply(SkClipOp op, const SkMatrix& matrix, const SkRect& bounds, bool aa,
                   bool fillsBounds);
    };

    constexpr SaveEntry saveEntryForLayer() {
        return {
            .clip = true,
            .matrix = true,
            .layer = true,
        };
    }

    constexpr SaveEntry flagsToSaveEntry(SaveFlags::Flags flags) {
        return SaveEntry {
            .clip = static_cast<bool>(flags & SaveFlags::Clip),
            .matrix = static_cast<bool>(flags & SaveFlags::Matrix),
            .layer = false
        };
    }

    bool internalSave(SaveEntry saveEntry);

    void internalSaveLayer(const SkCanvas::SaveLayerRec& layerRec) {
        internalSave({
            .clip = true,
            .matrix = true,
            .layer = true
        });
        internalClipRect(*layerRec.fBounds, SkClipOp::kIntersect);
    }

    bool internalRestore();

    void internalClipRect(const SkRect& rect, SkClipOp op);
    void internalClipPath(const SkPath& path, SkClipOp op);

    // The canvas' clip will never expand beyond these bounds since intersect
    // and difference operations only subtract pixels.
    SkIRect mInitialBounds;
    // Every save() gets a SaveEntry to track what needs to be restored.
    FatVector<SaveEntry, 6> mSaveStack;
    // Transform and clip entries record a deferred save count and do not
    // make a new entry until that particular state is modified.
    FatVector<DeferredEntry<SkMatrix>, 6> mTransformStack;
    FatVector<DeferredEntry<ConservativeClip>, 6> mClipStack;

    const ConservativeClip& clip() const { return mClipStack.back().entry; }

    ConservativeClip& clip();

    void resetState(int width, int height);

    // Stack manipulation for transform and clip stacks
    template <typename T, size_t N>
    void pushEntry(FatVector<DeferredEntry<T>, N>* stack) {
        stack->back().deferredSaveCount += 1;
    }

    template <typename T, size_t N>
    void popEntry(FatVector<DeferredEntry<T>, N>* stack) {
        if (!(stack->back().deferredSaveCount--)) {
            stack->pop_back();
        }
    }

    template <typename T, size_t N>
    T& writableEntry(FatVector<DeferredEntry<T>, N>* stack) {
        DeferredEntry<T>& back = stack->back();
        if (back.deferredSaveCount == 0) {
            return back.entry;
        } else {
            back.deferredSaveCount -= 1;
            // saved in case references move when re-allocating vector storage
            T state = back.entry;
            return stack->emplace_back(state).entry;
        }
    }

public:
    int saveCount() const { return mSaveStack.size(); }

    SkRect getClipBounds() const;
    bool quickRejectRect(float left, float top, float right, float bottom) const;
    bool quickRejectPath(const SkPath& path) const;

    bool isClipAA() const { return clip().aa; }
    bool isClipEmpty() const { return clip().bounds.isEmpty(); }
    bool isClipRect() const { return clip().rect; }
    bool isClipComplex() const { return !isClipEmpty() && (isClipAA() || !isClipRect()); }

    const SkMatrix& transform() const { return mTransformStack.back().entry; }

    SkMatrix& transform();

    // For compat with existing HWUI Canvas interface
    void getMatrix(SkMatrix* outMatrix) const {
        *outMatrix = transform();
    }

    void setMatrix(const SkMatrix& matrix) {
        transform() = matrix;
    }

    void concat(const SkMatrix& matrix) {
        transform().preConcat(matrix);
    }

    void rotate(float degrees) {
        SkMatrix m;
        m.setRotate(degrees);
        concat(m);
    }

    void scale(float sx, float sy) {
        SkMatrix m;
        m.setScale(sx, sy);
        concat(m);
    }

    void skew(float sx, float sy) {
        SkMatrix m;
        m.setSkew(sx, sy);
        concat(m);
    }

    void translate(float dx, float dy) {
        transform().preTranslate(dx, dy);
    }
};

// Front-end canvas that handles queries, up-front state, and produces CanvasOp<> output downstream
template <typename CanvasOpReceiver>
class CanvasFrontend final : public CanvasStateHelper {
public:
    template<class... Args>
    CanvasFrontend(int width, int height, Args&&... args) : CanvasStateHelper(width, height),
            mReceiver(std::in_place, std::forward<Args>(args)...) { }

    void save(SaveFlags::Flags flags = SaveFlags::MatrixClip) {
        if (internalSave(flagsToSaveEntry(flags))) {
            submit<CanvasOpType::Save>({});
        }
    }

    void restore() {
        if (internalRestore()) {
            submit<CanvasOpType::Restore>({});
        }
    }

    template <CanvasOpType T>
    void draw(CanvasOp<T>&& op) {
        // The front-end requires going through certain front-doors, which these aren't.
        static_assert(T != CanvasOpType::Save, "Must use CanvasFrontend::save() call instead");
        static_assert(T != CanvasOpType::Restore, "Must use CanvasFrontend::restore() call instead");

        if constexpr (T == CanvasOpType::SaveLayer) {
            internalSaveLayer(op.saveLayerRec);
        }
        if constexpr (T == CanvasOpType::SaveBehind) {
            // Don't use internalSaveLayer as this doesn't apply clipping, it's a "regular" save
            // But we do want to flag it as a layer, such that restore is Definitely Required
            internalSave(saveEntryForLayer());
        }
        if constexpr (T == CanvasOpType::ClipRect) {
            internalClipRect(op.rect, op.op);
        }
        if constexpr (T == CanvasOpType::ClipPath) {
            internalClipPath(op.path, op.op);
        }

        submit(std::move(op));
    }

    const CanvasOpReceiver& receiver() const {
        LOG_ALWAYS_FATAL_IF(!mReceiver.has_value());
        return *mReceiver;
    }

    CanvasOpReceiver finish() {
        auto ret = std::move(mReceiver.value());
        mReceiver.reset();
        return std::move(ret);
    }

    template<class... Args>
    void reset(int newWidth, int newHeight, Args&&... args) {
        resetState(newWidth, newHeight);
        mReceiver.emplace(std::forward<Args>(args)...);
    }

private:
    std::optional<CanvasOpReceiver> mReceiver;

    template <CanvasOpType T>
    void submit(CanvasOp<T>&& op) {
        LOG_ALWAYS_FATAL_IF(!mReceiver.has_value());
        mReceiver->push_container(CanvasOpContainer(std::move(op), transform()));
    }
};

} // namespace android::uirenderer
