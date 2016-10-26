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

#pragma once

#include "FrameInfo.h"
#include "Properties.h"
#include "Rect.h"
#include "utils/RingBuffer.h"

#include <utils/Timers.h>

#include <memory>

namespace android {
namespace uirenderer {

class IProfileRenderer;

// TODO: This is a bit awkward as it needs to match the thing in CanvasContext
// A better abstraction here would be nice but iterators are painful
// and RingBuffer having the size baked into the template is also painful
// But making DrawProfiler also be templated is ALSO painful
// At least this is a compile failure if this doesn't match, so there's that.
typedef RingBuffer<FrameInfo, 120> FrameInfoSource;

class FrameInfoVisualizer {
public:
    explicit FrameInfoVisualizer(FrameInfoSource& source);
    ~FrameInfoVisualizer();

    bool consumeProperties();
    void setDensity(float density);

    void unionDirty(SkRect* dirty);
    void draw(IProfileRenderer& renderer);

    void dumpData(int fd);

private:
    void createData();
    void destroyData();

    void initializeRects(const int baseline, const int width);
    void nextBarSegment(FrameInfoIndex start, FrameInfoIndex end);
    void drawGraph(IProfileRenderer& renderer);
    void drawThreshold(IProfileRenderer& renderer);

    inline float durationMS(size_t index, FrameInfoIndex start, FrameInfoIndex end) {
        float duration = mFrameSource[index].duration(start, end) * 0.000001f;
        // Clamp to large to avoid spiking off the top of the screen
        duration = duration > 50.0f ? 50.0f : duration;
        return duration > 0.0f ? duration : 0.0f;
    }

    ProfileType mType = ProfileType::None;
    float mDensity = 0;

    FrameInfoSource& mFrameSource;

    int mVerticalUnit = 0;
    int mThresholdStroke = 0;

    int mNumFastRects;
    std::unique_ptr<float[]> mFastRects;
    int mNumJankyRects;
    std::unique_ptr<float[]> mJankyRects;

    bool mShowDirtyRegions = false;
    SkRect mDirtyRegion;
    bool mFlashToggle = false;
    nsecs_t mLastFrameLogged = 0;
};

} /* namespace uirenderer */
} /* namespace android */
