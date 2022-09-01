/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <SkDrawable.h>
#include <Skottie.h>
#include <utils/Timers.h>

class SkCanvas;

namespace android {

/**
 * Native component of android.graphics.drawable.LottieDrawable.java.
 * This class can be drawn into Canvas.h and maintains the state needed to drive
 * the animation from the RenderThread.
 */
class LottieDrawable : public SkDrawable {
public:
    static sk_sp<LottieDrawable> Make(sk_sp<skottie::Animation> animation, size_t bytes);

    // Draw to software canvas
    void drawStaging(SkCanvas* canvas);

    // Returns true if the animation was started; false otherwise (e.g. it was
    // already running)
    bool start();
    // Returns true if the animation was stopped; false otherwise (e.g. it was
    // already stopped)
    bool stop();
    bool isRunning();

    // TODO: Is dirty should take in a time til next frame to determine if it is dirty
    bool isDirty();

    SkRect onGetBounds() override;

    size_t byteSize() const { return sizeof(*this) + mBytesUsed; }

protected:
    void onDraw(SkCanvas* canvas) override;

private:
    LottieDrawable(sk_sp<skottie::Animation> animation, size_t bytes_used);

    sk_sp<skottie::Animation> mAnimation;
    bool mRunning = false;
    // The start time for the drawable itself.
    nsecs_t mStartTime = 0;
    const size_t mBytesUsed = 0;
};

}  // namespace android
