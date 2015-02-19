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
#include "DrawProfiler.h"

#include <cutils/compiler.h>

#include "OpenGLRenderer.h"
#include "Properties.h"

#define DEFAULT_MAX_FRAMES 128

#define RETURN_IF_PROFILING_DISABLED() if (CC_LIKELY(mType == kNone)) return
#define RETURN_IF_DISABLED() if (CC_LIKELY(mType == kNone && !mShowDirtyRegions)) return

#define NANOS_TO_MILLIS_FLOAT(nanos) ((nanos) * 0.000001f)

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

DrawProfiler::DrawProfiler()
        : mType(kNone)
        , mDensity(0)
        , mData(NULL)
        , mDataSize(0)
        , mCurrentFrame(-1)
        , mPreviousTime(0)
        , mVerticalUnit(0)
        , mHorizontalUnit(0)
        , mThresholdStroke(0)
        , mShowDirtyRegions(false)
        , mFlashToggle(false) {
    setDensity(1);
}

DrawProfiler::~DrawProfiler() {
    destroyData();
}

void DrawProfiler::setDensity(float density) {
    if (CC_UNLIKELY(mDensity != density)) {
        mDensity = density;
        mVerticalUnit = dpToPx(PROFILE_DRAW_DP_PER_MS, density);
        mHorizontalUnit = dpToPx(PROFILE_DRAW_WIDTH, density);
        mThresholdStroke = dpToPx(PROFILE_DRAW_THRESHOLD_STROKE_WIDTH, density);
    }
}

void DrawProfiler::startFrame(nsecs_t recordDurationNanos) {
    RETURN_IF_PROFILING_DISABLED();
    mData[mCurrentFrame].record = NANOS_TO_MILLIS_FLOAT(recordDurationNanos);
    mPreviousTime = systemTime(CLOCK_MONOTONIC);
}

void DrawProfiler::markPlaybackStart() {
    RETURN_IF_PROFILING_DISABLED();
    nsecs_t now = systemTime(CLOCK_MONOTONIC);
    mData[mCurrentFrame].prepare = NANOS_TO_MILLIS_FLOAT(now - mPreviousTime);
    mPreviousTime = now;
}

void DrawProfiler::markPlaybackEnd() {
    RETURN_IF_PROFILING_DISABLED();
    nsecs_t now = systemTime(CLOCK_MONOTONIC);
    mData[mCurrentFrame].playback = NANOS_TO_MILLIS_FLOAT(now - mPreviousTime);
    mPreviousTime = now;
}

void DrawProfiler::finishFrame() {
    RETURN_IF_PROFILING_DISABLED();
    nsecs_t now = systemTime(CLOCK_MONOTONIC);
    mData[mCurrentFrame].swapBuffers = NANOS_TO_MILLIS_FLOAT(now - mPreviousTime);
    mPreviousTime = now;
    mCurrentFrame = (mCurrentFrame + 1) % mDataSize;
}

void DrawProfiler::unionDirty(SkRect* dirty) {
    RETURN_IF_DISABLED();
    // Not worth worrying about minimizing the dirty region for debugging, so just
    // dirty the entire viewport.
    if (dirty) {
        mDirtyRegion = *dirty;
        dirty->setEmpty();
    }
}

void DrawProfiler::draw(OpenGLRenderer* canvas) {
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

    if (mType == kBars) {
        prepareShapes(canvas->getViewportHeight());
        drawGraph(canvas);
        drawCurrentFrame(canvas);
        drawThreshold(canvas);
    }
}

void DrawProfiler::createData() {
    if (mData) return;

    mDataSize = property_get_int32(PROPERTY_PROFILE_MAXFRAMES, DEFAULT_MAX_FRAMES);
    if (mDataSize <= 0) mDataSize = 1;
    if (mDataSize > 4096) mDataSize = 4096; // Reasonable maximum
    mData = (FrameTimingData*) calloc(mDataSize, sizeof(FrameTimingData));
    mRects = new float*[NUM_ELEMENTS];
    for (int i = 0; i < NUM_ELEMENTS; i++) {
        // 4 floats per rect
        mRects[i] = (float*) calloc(mDataSize, 4 * sizeof(float));
    }
    mCurrentFrame = 0;
}

void DrawProfiler::destroyData() {
    delete mData;
    mData = NULL;
}

void DrawProfiler::addRect(Rect& r, float data, float* shapeOutput) {
    r.top = r.bottom - (data * mVerticalUnit);
    shapeOutput[0] = r.left;
    shapeOutput[1] = r.top;
    shapeOutput[2] = r.right;
    shapeOutput[3] = r.bottom;
    r.bottom = r.top;
}

void DrawProfiler::prepareShapes(const int baseline) {
    Rect r;
    r.right = mHorizontalUnit;
    for (int i = 0; i < mDataSize; i++) {
        const int shapeIndex = i * 4;
        r.bottom = baseline;
        addRect(r, mData[i].record, mRects[RECORD_INDEX] + shapeIndex);
        addRect(r, mData[i].prepare, mRects[PREPARE_INDEX] + shapeIndex);
        addRect(r, mData[i].playback, mRects[PLAYBACK_INDEX] + shapeIndex);
        addRect(r, mData[i].swapBuffers, mRects[SWAPBUFFERS_INDEX] + shapeIndex);
        r.translate(mHorizontalUnit, 0);
    }
}

void DrawProfiler::drawGraph(OpenGLRenderer* canvas) {
    SkPaint paint;
    for (int i = 0; i < NUM_ELEMENTS; i++) {
        paint.setColor(ELEMENT_COLORS[i]);
        canvas->drawRects(mRects[i], mDataSize * 4, &paint);
    }
}

void DrawProfiler::drawCurrentFrame(OpenGLRenderer* canvas) {
    // This draws a solid rect over the entirety of the current frame's shape
    // To do so we use the bottom of mRects[0] and the top of mRects[NUM_ELEMENTS-1]
    // which will therefore fully overlap the previously drawn rects
    SkPaint paint;
    paint.setColor(CURRENT_FRAME_COLOR);
    const int i = mCurrentFrame * 4;
    canvas->drawRect(mRects[0][i], mRects[NUM_ELEMENTS-1][i+1], mRects[0][i+2],
            mRects[0][i+3], &paint);
}

void DrawProfiler::drawThreshold(OpenGLRenderer* canvas) {
    SkPaint paint;
    paint.setColor(THRESHOLD_COLOR);
    paint.setStrokeWidth(mThresholdStroke);

    float pts[4];
    pts[0] = 0.0f;
    pts[1] = pts[3] = canvas->getViewportHeight() - (FRAME_THRESHOLD * mVerticalUnit);
    pts[2] = canvas->getViewportWidth();
    canvas->drawLines(pts, 4, &paint);
}

DrawProfiler::ProfileType DrawProfiler::loadRequestedProfileType() {
    ProfileType type = kNone;
    char buf[PROPERTY_VALUE_MAX] = {'\0',};
    if (property_get(PROPERTY_PROFILE, buf, "") > 0) {
        if (!strcmp(buf, PROPERTY_PROFILE_VISUALIZE_BARS)) {
            type = kBars;
        } else if (!strcmp(buf, "true")) {
            type = kConsole;
        }
    }
    return type;
}

bool DrawProfiler::loadSystemProperties() {
    bool changed = false;
    ProfileType newType = loadRequestedProfileType();
    if (newType != mType) {
        mType = newType;
        if (mType == kNone) {
            destroyData();
        } else {
            createData();
        }
        changed = true;
    }
    bool showDirty = property_get_bool(PROPERTY_DEBUG_SHOW_DIRTY_REGIONS, false);
    if (showDirty != mShowDirtyRegions) {
        mShowDirtyRegions = showDirty;
        changed = true;
    }
    return changed;
}

void DrawProfiler::dumpData(int fd) {
    RETURN_IF_PROFILING_DISABLED();

    // This method logs the last N frames (where N is <= mDataSize) since the
    // last call to dumpData(). In other words if there's a dumpData(), draw frame,
    // dumpData(), the last dumpData() should only log 1 frame.

    const FrameTimingData emptyData = {0, 0, 0, 0};

    FILE *file = fdopen(fd, "a");
    fprintf(file, "\n\tDraw\tPrepare\tProcess\tExecute\n");

    for (int frameOffset = 1; frameOffset <= mDataSize; frameOffset++) {
        int i = (mCurrentFrame + frameOffset) % mDataSize;
        if (!memcmp(mData + i, &emptyData, sizeof(FrameTimingData))) {
            continue;
        }
        fprintf(file, "\t%3.2f\t%3.2f\t%3.2f\t%3.2f\n",
                mData[i].record, mData[i].prepare, mData[i].playback, mData[i].swapBuffers);
    }
    // reset the buffer
    memset(mData, 0, sizeof(FrameTimingData) * mDataSize);
    mCurrentFrame = 0;

    fflush(file);
}

} /* namespace uirenderer */
} /* namespace android */
