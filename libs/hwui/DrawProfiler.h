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
#ifndef DRAWPROFILER_H
#define DRAWPROFILER_H

#include <utils/Timers.h>
#include "Rect.h"

namespace android {
namespace uirenderer {

class OpenGLRenderer;

class DrawProfiler {
public:
    DrawProfiler();
    ~DrawProfiler();

    bool loadSystemProperties();
    void setDensity(float density);

    void startFrame(nsecs_t recordDurationNanos = 0);
    void markPlaybackStart();
    void markPlaybackEnd();
    void finishFrame();

    void unionDirty(SkRect* dirty);
    void draw(OpenGLRenderer* canvas);

    void dumpData(int fd);

private:
    enum ProfileType {
        kNone,
        kConsole,
        kBars,
    };

    typedef struct {
        float record;
        float prepare;
        float playback;
        float swapBuffers;
    } FrameTimingData;

    void createData();
    void destroyData();

    void addRect(Rect& r, float data, float* shapeOutput);
    void prepareShapes(const int baseline);
    void drawGraph(OpenGLRenderer* canvas);
    void drawCurrentFrame(OpenGLRenderer* canvas);
    void drawThreshold(OpenGLRenderer* canvas);

    ProfileType loadRequestedProfileType();

    ProfileType mType;
    float mDensity;

    FrameTimingData* mData;
    int mDataSize;

    int mCurrentFrame;
    nsecs_t mPreviousTime;

    int mVerticalUnit;
    int mHorizontalUnit;
    int mThresholdStroke;

    /*
     * mRects represents an array of rect shapes, divided into NUM_ELEMENTS
     * groups such that each group is drawn with the same paint.
     * For example mRects[0] is the array of rect floats suitable for
     * OpenGLRenderer:drawRects() that makes up all the FrameTimingData:record
     * information.
     */
    float** mRects;

    bool mShowDirtyRegions;
    SkRect mDirtyRegion;
    bool mFlashToggle;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DRAWPROFILER_H */
