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

#include "AndroidPicture.h"
#include "SkCanvas.h"
#include "SkStream.h"

AndroidPicture::AndroidPicture(const AndroidPicture* src) {
    if (NULL != src) {
        mWidth = src->width();
        mHeight = src->height();
        if (NULL != src->mPicture.get()) {
            mPicture.reset(SkRef(src->mPicture.get()));
        } if (NULL != src->mRecorder.get()) {
            mPicture.reset(src->makePartialCopy());
        }
    } else {
        mWidth = 0;
        mHeight = 0;
    }
}

SkCanvas* AndroidPicture::beginRecording(int width, int height) {
    mPicture.reset(NULL);
    mRecorder.reset(new SkPictureRecorder);
    mWidth = width;
    mHeight = height;
    return mRecorder->beginRecording(width, height, NULL, 0);
}

void AndroidPicture::endRecording() {
    if (NULL != mRecorder.get()) {
        mPicture.reset(mRecorder->endRecording());
        mRecorder.reset(NULL);
    }
}

int AndroidPicture::width() const {
    if (NULL != mPicture.get()) {
        SkASSERT(mPicture->width() == mWidth);
        SkASSERT(mPicture->height() == mHeight);
    }

    return mWidth;
}

int AndroidPicture::height() const {
    if (NULL != mPicture.get()) {
        SkASSERT(mPicture->width() == mWidth);
        SkASSERT(mPicture->height() == mHeight);
    }

    return mHeight;
}

AndroidPicture* AndroidPicture::CreateFromStream(SkStream* stream) {
    AndroidPicture* newPict = new AndroidPicture;

    newPict->mPicture.reset(SkPicture::CreateFromStream(stream));
    if (NULL != newPict->mPicture.get()) {
        newPict->mWidth = newPict->mPicture->width();
        newPict->mHeight = newPict->mPicture->height();
    }

    return newPict;
}

void AndroidPicture::serialize(SkWStream* stream) const {
    if (NULL != mRecorder.get()) {
        SkAutoTDelete<SkPicture> tempPict(this->makePartialCopy());
        tempPict->serialize(stream);
    } else if (NULL != mPicture.get()) {
        mPicture->serialize(stream);
    } else {
        SkPicture empty;
        empty.serialize(stream);
    }
}

void AndroidPicture::draw(SkCanvas* canvas) {
    if (NULL != mRecorder.get()) {
        this->endRecording();
        SkASSERT(NULL != mPicture.get());
    }
    if (NULL != mPicture.get()) {
        // TODO: remove this const_cast once pictures are immutable
        const_cast<SkPicture*>(mPicture.get())->draw(canvas);
    }
}

SkPicture* AndroidPicture::makePartialCopy() const {
    SkASSERT(NULL != mRecorder.get());

    SkPictureRecorder reRecorder;

    SkCanvas* canvas = reRecorder.beginRecording(mWidth, mHeight, NULL, 0);
    mRecorder->partialReplay(canvas);
    return reRecorder.endRecording();
}
