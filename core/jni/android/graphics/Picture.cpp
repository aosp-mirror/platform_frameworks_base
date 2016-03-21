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
            mPicture.reset(SkRef(src->mPicture.get()));
        } else if (NULL != src->mRecorder.get()) {
            mPicture.reset(src->makePartialCopy());
        }
        validate();
    } else {
        mWidth = 0;
        mHeight = 0;
    }
}

Canvas* Picture::beginRecording(int width, int height) {
    mPicture.reset(NULL);
    mRecorder.reset(new SkPictureRecorder);
    mWidth = width;
    mHeight = height;
    SkCanvas* canvas = mRecorder->beginRecording(width, height, NULL, 0);
    return Canvas::create_canvas(canvas);
}

void Picture::endRecording() {
    if (NULL != mRecorder.get()) {
        mPicture.reset(mRecorder->endRecording());
        validate();
        mRecorder.reset(NULL);
    }
}

int Picture::width() const {
    validate();
    return mWidth;
}

int Picture::height() const {
    validate();
    return mHeight;
}

Picture* Picture::CreateFromStream(SkStream* stream) {
    Picture* newPict = new Picture;

    SkPicture* skPicture = SkPicture::CreateFromStream(stream);
    if (NULL != skPicture) {
        newPict->mPicture.reset(skPicture);

        const SkIRect cullRect = skPicture->cullRect().roundOut();
        newPict->mWidth = cullRect.width();
        newPict->mHeight = cullRect.height();
    }

    return newPict;
}

void Picture::serialize(SkWStream* stream) const {
    if (NULL != mRecorder.get()) {
        std::unique_ptr<SkPicture> tempPict(this->makePartialCopy());
        tempPict->serialize(stream);
    } else if (NULL != mPicture.get()) {
        validate();
        mPicture->serialize(stream);
    } else {
        SkPictureRecorder recorder;
        recorder.beginRecording(0, 0);
        std::unique_ptr<SkPicture> empty(recorder.endRecording());
        empty->serialize(stream);
    }
}

void Picture::draw(Canvas* canvas) {
    if (NULL != mRecorder.get()) {
        this->endRecording();
        SkASSERT(NULL != mPicture.get());
    }
    validate();
    if (NULL != mPicture.get()) {
        mPicture.get()->playback(canvas->asSkCanvas());
    }
}

SkPicture* Picture::makePartialCopy() const {
    SkASSERT(NULL != mRecorder.get());

    SkPictureRecorder reRecorder;

    SkCanvas* canvas = reRecorder.beginRecording(mWidth, mHeight, NULL, 0);
    mRecorder->partialReplay(canvas);
    return reRecorder.endRecording();
}

void Picture::validate() const {
#ifdef SK_DEBUG
    if (NULL != mPicture.get()) {
        SkRect cullRect = mPicture->cullRect();
        SkRect myRect = SkRect::MakeWH(SkIntToScalar(mWidth), SkIntToScalar(mHeight));
        SkASSERT(cullRect == myRect);
    }
#endif
}

}; // namespace android
