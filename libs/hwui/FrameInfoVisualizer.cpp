/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "FrameInfoVisualizer.h"

#include "OpenGLRenderer.h"

#include <cutils/compiler.h>

#define RETURN_IF_PROFILING_DISABLED() if (CC_LIKELY(mType == ProfileType::None)) return
#define RETURN_IF_DISABLED() if (CC_LIKELY(mType == ProfileType::None && !mShowDirtyRegions)) return

#define PROFILE_DRAW_WIDTH 3
#define PROFILE_DRAW_THRESHOLD_STROKE_WIDTH 2
#define PROFILE_DRAW_DP_PER_MS 7

// Number of floats we want to display from FrameTimingData
// If this is changed make sure to update the indexes below
#define NUM_ELEMENTS 4

#define RECORD_INDEX 0
#define PREPARE_INDEX 1
#define PLAYBACK_INDEX 2
#define SWAPBUFFERS_INDEX 3

// Must be NUM_ELEMENTS in size
static const SkColor ELEMENT_COLORS[] = { 0xcf3e66cc, 0xcf8f00ff, 0xcfdc3912, 0xcfe69800 };
static const SkColor CURRENT_FRAME_COLOR = 0xcf5faa4d;
static const SkColor THRESHOLD_COLOR = 0xff5faa4d;

// We could get this from TimeLord and use the actual frame interval, but
// this is good enough
#define FRAME_THRESHOLD 16

namespace android {
namespace uirenderer {

static int dpToPx(int dp, float density) {
    return (int) (dp * density + 0.5f);
}

FrameInfoVisualizer::FrameInfoVisualizer(FrameInfoSource& source)
        : mFrameSource(source) {
    setDensity(1);
}

FrameInfoVisualizer::~FrameInfoVisualizer() {
    destroyData();
}

void FrameInfoVisualizer::setDensity(float density) {
    if (CC_UNLIKELY(mDensity != density)) {
        mDensity = density;
        mVerticalUnit = dpToPx(PROFILE_DRAW_DP_PER_MS, density);
        mHorizontalUnit = dpToPx(PROFILE_DRAW_WIDTH, density);
        mThresholdStroke = dpToPx(PROFILE_DRAW_THRESHOLD_STROKE_WIDTH, density);
    }
}

void FrameInfoVisualizer::unionDirty(SkRect* dirty) {
    RETURN_IF_DISABLED();
    // Not worth worrying about minimizing the dirty region for debugging, so just
    // dirty the entire viewport.
    if (dirty) {
        mDirtyRegion = *dirty;
        dirty->setEmpty();
    }
}

void FrameInfoVisualizer::draw(OpenGLRenderer* canvas) {
    RETURN_IF_DISABLED();

    if (mShowDirtyRegions) {
        mFlashToggle = !mFlashToggle;
        if (mFlashToggle) {
            SkPaint paint;
            paint.setColor(0x7fff0000);
            canvas->drawRect(mDirtyRegion.fLeft, mDirtyRegion.fTop,
                    mDirtyRegion.fRight, mDirtyRegion.fBottom, &paint);
        }
    }

    if (mType == ProfileType::Bars) {
        prepareShapes(canvas->getViewportHeight());
        drawGraph(canvas);
        drawCurrentFrame(canvas);
        drawThreshold(canvas);
    }
}

void FrameInfoVisualizer::createData() {
    if (mRects.get()) return;

    mRects.reset(new float*[mFrameSource.capacity()]);
    for (int i = 0; i < NUM_ELEMENTS; i++) {
        // 4 floats per rect
        mRects.get()[i] = (float*) calloc(mFrameSource.capacity(), 4 * sizeof(float));
    }
}

void FrameInfoVisualizer::destroyData() {
    mRects.reset(nullptr);
}

void FrameInfoVisualizer::addRect(Rect& r, float data, float* shapeOutput) {
    r.top = r.bottom - (data * mVerticalUnit);
    shapeOutput[0] = r.left;
    shapeOutput[1] = r.top;
    shapeOutput[2] = r.right;
    shapeOutput[3] = r.bottom;
    r.bottom = r.top;
}

void FrameInfoVisualizer::prepareShapes(const int baseline) {
    Rect r;
    r.right = mHorizontalUnit;
    for (size_t i = 0; i < mFrameSource.size(); i++) {
        const int shapeIndex = i * 4;
        r.bottom = baseline;
        addRect(r, recordDuration(i), mRects.get()[RECORD_INDEX] + shapeIndex);
        addRect(r, prepareDuration(i), mRects.get()[PREPARE_INDEX] + shapeIndex);
        addRect(r, issueDrawDuration(i), mRects.get()[PLAYBACK_INDEX] + shapeIndex);
        addRect(r, swapBuffersDuration(i), mRects.get()[SWAPBUFFERS_INDEX] + shapeIndex);
        r.translate(mHorizontalUnit, 0);
    }
}

void FrameInfoVisualizer::drawGraph(OpenGLRenderer* canvas) {
    SkPaint paint;
    for (int i = 0; i < NUM_ELEMENTS; i++) {
        paint.setColor(ELEMENT_COLORS[i]);
        canvas->drawRects(mRects.get()[i], mFrameSource.capacity() * 4, &paint);
    }
}

void FrameInfoVisualizer::drawCurrentFrame(OpenGLRenderer* canvas) {
    // This draws a solid rect over the entirety of the current frame's shape
    // To do so we use the bottom of mRects[0] and the top of mRects[NUM_ELEMENTS-1]
    // which will therefore fully overlap the previously drawn rects
    SkPaint paint;
    paint.setColor(CURRENT_FRAME_COLOR);
    const int i = (mFrameSource.size() - 1) * 4;
    canvas->drawRect(mRects.get()[0][i], mRects.get()[NUM_ELEMENTS-1][i+1],
            mRects.get()[0][i+2], mRects.get()[0][i+3], &paint);
}

void FrameInfoVisualizer::drawThreshold(OpenGLRenderer* canvas) {
    SkPaint paint;
    paint.setColor(THRESHOLD_COLOR);
    paint.setStrokeWidth(mThresholdStroke);

    float pts[4];
    pts[0] = 0.0f;
    pts[1] = pts[3] = canvas->getViewportHeight() - (FRAME_THRESHOLD * mVerticalUnit);
    pts[2] = canvas->getViewportWidth();
    canvas->drawLines(pts, 4, &paint);
}

bool FrameInfoVisualizer::consumeProperties() {
    bool changed = false;
    ProfileType newType = Properties::getProfileType();
    if (newType != mType) {
        mType = newType;
        if (mType == ProfileType::None) {
            destroyData();
        } else {
            createData();
        }
        changed = true;
    }

    bool showDirty = Properties::showDirtyRegions;
    if (showDirty != mShowDirtyRegions) {
        mShowDirtyRegions = showDirty;
        changed = true;
    }
    return changed;
}

void FrameInfoVisualizer::dumpData(int fd) {
    RETURN_IF_PROFILING_DISABLED();

    // This method logs the last N frames (where N is <= mDataSize) since the
    // last call to dumpData(). In other words if there's a dumpData(), draw frame,
    // dumpData(), the last dumpData() should only log 1 frame.

    FILE *file = fdopen(fd, "a");
    fprintf(file, "\n\tDraw\tPrepare\tProcess\tExecute\n");

    for (size_t i = 0; i < mFrameSource.size(); i++) {
        if (mFrameSource[i][FrameInfoIndex::kIntendedVsync] <= mLastFrameLogged) {
            continue;
        }
        mLastFrameLogged = mFrameSource[i][FrameInfoIndex::kIntendedVsync];
        fprintf(file, "\t%3.2f\t%3.2f\t%3.2f\t%3.2f\n",
                recordDuration(i), prepareDuration(i),
                issueDrawDuration(i), swapBuffersDuration(i));
    }

    fflush(file);
}

} /* namespace uirenderer */
} /* namespace android */
