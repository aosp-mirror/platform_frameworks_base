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
#include <array>

#define RETURN_IF_PROFILING_DISABLED() if (CC_LIKELY(mType == ProfileType::None)) return
#define RETURN_IF_DISABLED() if (CC_LIKELY(mType == ProfileType::None && !mShowDirtyRegions)) return

#define PROFILE_DRAW_WIDTH 3
#define PROFILE_DRAW_THRESHOLD_STROKE_WIDTH 2
#define PROFILE_DRAW_DP_PER_MS 7

// Must be NUM_ELEMENTS in size
static const SkColor CURRENT_FRAME_COLOR = 0xcf5faa4d;
static const SkColor THRESHOLD_COLOR = 0xff5faa4d;
static const SkColor BAR_ALPHA = 0xCF000000;

// We could get this from TimeLord and use the actual frame interval, but
// this is good enough
#define FRAME_THRESHOLD 16

namespace android {
namespace uirenderer {

struct BarSegment {
    FrameInfoIndex start;
    FrameInfoIndex end;
    SkColor color;
};

static const std::array<BarSegment,9> Bar {{
    { FrameInfoIndex::IntendedVsync, FrameInfoIndex::Vsync, 0x00695C },
    { FrameInfoIndex::Vsync, FrameInfoIndex::HandleInputStart, 0x00796B },
    { FrameInfoIndex::HandleInputStart, FrameInfoIndex::AnimationStart, 0x00897B },
    { FrameInfoIndex::AnimationStart, FrameInfoIndex::PerformTraversalsStart, 0x009688 },
    { FrameInfoIndex::PerformTraversalsStart, FrameInfoIndex::DrawStart, 0x26A69A},
    { FrameInfoIndex::DrawStart, FrameInfoIndex::SyncStart, 0x2196F3},
    { FrameInfoIndex::SyncStart, FrameInfoIndex::IssueDrawCommandsStart, 0x4FC3F7},
    { FrameInfoIndex::IssueDrawCommandsStart, FrameInfoIndex::SwapBuffers, 0xF44336},
    { FrameInfoIndex::SwapBuffers, FrameInfoIndex::FrameCompleted, 0xFF9800},
}};

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
        initializeRects(canvas->getViewportHeight());
        drawGraph(canvas);
        drawCurrentFrame(canvas->getViewportHeight(), canvas);
        drawThreshold(canvas);
    }
}

void FrameInfoVisualizer::createData() {
    if (mRects.get()) return;

    mRects.reset(new float[mFrameSource.capacity() * 4]);
}

void FrameInfoVisualizer::destroyData() {
    mRects.reset(nullptr);
}

void FrameInfoVisualizer::initializeRects(const int baseline) {
    float left = 0;
    // Set the bottom of all the shapes to the baseline
    for (size_t i = 0; i < (mFrameSource.capacity() * 4); i += 4) {
        // Rects are LTRB
        mRects[i + 0] = left;
        mRects[i + 1] = baseline;
        left += mHorizontalUnit;
        mRects[i + 2] = left;
        mRects[i + 3] = baseline;
    }
}

void FrameInfoVisualizer::nextBarSegment(FrameInfoIndex start, FrameInfoIndex end) {
    for (size_t fi = 0, ri = 0; fi < mFrameSource.size(); fi++, ri += 4) {
        // TODO: Skipped frames will leave little holes in the graph, but this
        // is better than bogus and freaky lines, so...
        if (mFrameSource[fi][FrameInfoIndex::Flags] & FrameInfoFlags::SkippedFrame) {
            continue;
        }

        // Set the bottom to the old top (build upwards)
        mRects[ri + 3] = mRects[ri + 1];
        // Move the top up by the duration
        mRects[ri + 1] -= mVerticalUnit * duration(fi, start, end);
    }
}

void FrameInfoVisualizer::drawGraph(OpenGLRenderer* canvas) {
    SkPaint paint;
    for (size_t i = 0; i < Bar.size(); i++) {
        paint.setColor(Bar[i].color | BAR_ALPHA);
        nextBarSegment(Bar[i].start, Bar[i].end);
        canvas->drawRects(mRects.get(), (mFrameSource.size() - 1) * 4, &paint);
    }
}

void FrameInfoVisualizer::drawCurrentFrame(const int baseline, OpenGLRenderer* canvas) {
    // This draws a solid rect over the entirety of the current frame's shape
    // To do so we use the bottom of mRects[0] and the top of mRects[NUM_ELEMENTS-1]
    // which will therefore fully overlap the previously drawn rects
    SkPaint paint;
    paint.setColor(CURRENT_FRAME_COLOR);
    size_t fi = mFrameSource.size() - 1;
    size_t ri = fi * 4;
    float top = baseline - (mVerticalUnit * duration(fi,
            FrameInfoIndex::IntendedVsync, FrameInfoIndex::IssueDrawCommandsStart));
    canvas->drawRect(mRects[ri], top, mRects[ri + 2], baseline, &paint);
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
        if (mFrameSource[i][FrameInfoIndex::IntendedVsync] <= mLastFrameLogged) {
            continue;
        }
        mLastFrameLogged = mFrameSource[i][FrameInfoIndex::IntendedVsync];
        fprintf(file, "\t%3.2f\t%3.2f\t%3.2f\t%3.2f\n",
                duration(i, FrameInfoIndex::IntendedVsync, FrameInfoIndex::SyncStart),
                duration(i, FrameInfoIndex::SyncStart, FrameInfoIndex::IssueDrawCommandsStart),
                duration(i, FrameInfoIndex::IssueDrawCommandsStart, FrameInfoIndex::SwapBuffers),
                duration(i, FrameInfoIndex::SwapBuffers, FrameInfoIndex::FrameCompleted));
    }

    fflush(file);
}

} /* namespace uirenderer */
} /* namespace android */
