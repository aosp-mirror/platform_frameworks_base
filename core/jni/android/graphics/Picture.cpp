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

#include "Picture.h"
#include "SkStream.h"

#include <memory>
#include <hwui/Canvas.h>

namespace android {

Picture::Picture(const Picture* src) {
    if (NULL != src) {
        mWidth = src->width();
        mHeight = src->height();
        if (NULL != src->mPicture.get()) {
            mPicture = src->mPicture;
        } else if (NULL != src->mRecorder.get()) {
            mPicture = src->makePartialCopy();
        }
    } else {
        mWidth = 0;
        mHeight = 0;
    }
}

Picture::Picture(sk_sp<SkPicture>&& src) {
    mPicture = std::move(src);
    mWidth = 0;
    mHeight = 0;
}

Canvas* Picture::beginRecording(int width, int height) {
    mPicture.reset(NULL);
    mRecorder.reset(new SkPictureRecorder);
    mWidth = width;
    mHeight = height;
    SkCanvas* canvas = mRecorder->beginRecording(SkIntToScalar(width), SkIntToScalar(height));
    return Canvas::create_canvas(canvas);
}

void Picture::endRecording() {
    if (NULL != mRecorder.get()) {
        mPicture = mRecorder->finishRecordingAsPicture();
        mRecorder.reset(NULL);
    }
}

int Picture::width() const {
    return mWidth;
}

int Picture::height() const {
    return mHeight;
}

Picture* Picture::CreateFromStream(SkStream* stream) {
    Picture* newPict = new Picture;

    sk_sp<SkPicture> skPicture = SkPicture::MakeFromStream(stream);
    if (NULL != skPicture) {
        newPict->mPicture = skPicture;

        const SkIRect cullRect = skPicture->cullRect().roundOut();
        newPict->mWidth = cullRect.width();
        newPict->mHeight = cullRect.height();
    }

    return newPict;
}

void Picture::serialize(SkWStream* stream) const {
    if (NULL != mRecorder.get()) {
        this->makePartialCopy()->serialize(stream);
    } else if (NULL != mPicture.get()) {
        mPicture->serialize(stream);
    } else {
        // serialize "empty" picture
        SkPictureRecorder recorder;
        recorder.beginRecording(0, 0);
        recorder.finishRecordingAsPicture()->serialize(stream);
    }
}

void Picture::draw(Canvas* canvas) {
    if (NULL != mRecorder.get()) {
        this->endRecording();
        SkASSERT(NULL != mPicture.get());
    }
    if (NULL != mPicture.get()) {
        mPicture->playback(canvas->asSkCanvas());
    }
}

sk_sp<SkPicture> Picture::makePartialCopy() const {
    SkASSERT(NULL != mRecorder.get());

    SkPictureRecorder reRecorder;

    SkCanvas* canvas = reRecorder.beginRecording(mWidth, mHeight, NULL, 0);
    mRecorder->partialReplay(canvas);
    return reRecorder.finishRecordingAsPicture();
}

}; // namespace android
