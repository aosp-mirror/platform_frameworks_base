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

#ifndef ANDROID_GRAPHICS_PICTURE_H_
#define ANDROID_GRAPHICS_PICTURE_H_

#include "SkPicture.h"
#include "SkPictureRecorder.h"
#include "SkRefCnt.h"

#include <memory>

class SkStream;
class SkWStream;

namespace android {

class Canvas;

// Skia's SkPicture class has been split into an SkPictureRecorder
// and an SkPicture. AndroidPicture recreates the functionality
// of the old SkPicture interface by flip-flopping between the two
// new classes.
class Picture {
public:
    explicit Picture(const Picture* src = NULL);
    explicit Picture(sk_sp<SkPicture>&& src);

    Canvas* beginRecording(int width, int height);

    void endRecording();

    int width() const;

    int height() const;

    static Picture* CreateFromStream(SkStream* stream);

    void serialize(SkWStream* stream) const;

    void draw(Canvas* canvas);

private:
    int mWidth;
    int mHeight;
    sk_sp<SkPicture> mPicture;
    std::unique_ptr<SkPictureRecorder> mRecorder;

    // Make a copy of a picture that is in the midst of being recorded. The
    // resulting picture will have balanced saves and restores.
    sk_sp<SkPicture> makePartialCopy() const;
};

}; // namespace android
#endif // ANDROID_GRAPHICS_PICTURE_H_
