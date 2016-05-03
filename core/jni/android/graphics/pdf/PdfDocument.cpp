/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include "core_jni_helpers.h"
#include <vector>

#include "CreateJavaOutputStreamAdaptor.h"

#include "SkDocument.h"
#include "SkPicture.h"
#include "SkPictureRecorder.h"
#include "SkStream.h"
#include "SkRect.h"

#include <hwui/Canvas.h>

namespace android {

struct PageRecord {

    PageRecord(int width, int height, const SkRect& contentRect)
            : mPictureRecorder(new SkPictureRecorder())
            , mPicture(NULL)
            , mWidth(width)
            , mHeight(height) {
        mContentRect = contentRect;
    }

    ~PageRecord() {
        delete mPictureRecorder;
        if (NULL != mPicture) {
            mPicture->unref();
        }
    }

    SkPictureRecorder* mPictureRecorder;
    SkPicture* mPicture;
    const int mWidth;
    const int mHeight;
    SkRect mContentRect;
};

class PdfDocument {
public:
    PdfDocument() {
        mCurrentPage = NULL;
    }

    SkCanvas* startPage(int width, int height,
            int contentLeft, int contentTop, int contentRight, int contentBottom) {
        assert(mCurrentPage == NULL);

        SkRect contentRect = SkRect::MakeLTRB(
                contentLeft, contentTop, contentRight, contentBottom);
        PageRecord* page = new PageRecord(width, height, contentRect);
        mPages.push_back(page);
        mCurrentPage = page;

        SkCanvas* canvas = page->mPictureRecorder->beginRecording(
                SkRect::MakeWH(contentRect.width(), contentRect.height()));

        return canvas;
    }

    void finishPage() {
        assert(mCurrentPage != NULL);
        assert(mCurrentPage->mPictureRecorder != NULL);
        assert(mCurrentPage->mPicture == NULL);
        mCurrentPage->mPicture = mCurrentPage->mPictureRecorder->endRecording();
        delete mCurrentPage->mPictureRecorder;
        mCurrentPage->mPictureRecorder = NULL;
        mCurrentPage = NULL;
    }

    void write(SkWStream* stream) {
        SkAutoTUnref<SkDocument> document(SkDocument::CreatePDF(stream));
        for (unsigned i = 0; i < mPages.size(); i++) {
            PageRecord* page =  mPages[i];

            SkCanvas* canvas = document->beginPage(page->mWidth, page->mHeight,
                    &(page->mContentRect));

            canvas->drawPicture(page->mPicture);

            document->endPage();
        }
        document->close();
    }

    void close() {
        assert(NULL == mCurrentPage);
        for (unsigned i = 0; i < mPages.size(); i++) {
            delete mPages[i];
        }
    }

private:
    ~PdfDocument() {
        close();
    }

    std::vector<PageRecord*> mPages;
    PageRecord* mCurrentPage;
};

static jlong nativeCreateDocument(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<jlong>(new PdfDocument());
}

static jlong nativeStartPage(JNIEnv* env, jobject thiz, jlong documentPtr,
        jint pageWidth, jint pageHeight,
        jint contentLeft, jint contentTop, jint contentRight, jint contentBottom) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    SkCanvas* canvas = document->startPage(pageWidth, pageHeight,
            contentLeft, contentTop, contentRight, contentBottom);
    return reinterpret_cast<jlong>(Canvas::create_canvas(canvas));
}

static void nativeFinishPage(JNIEnv* env, jobject thiz, jlong documentPtr) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    document->finishPage();
}

static void nativeWriteTo(JNIEnv* env, jobject thiz, jlong documentPtr, jobject out,
        jbyteArray chunk) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    SkWStream* skWStream = CreateJavaOutputStreamAdaptor(env, out, chunk);
    document->write(skWStream);
    delete skWStream;
}

static void nativeClose(JNIEnv* env, jobject thiz, jlong documentPtr) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    document->close();
}

static const JNINativeMethod gPdfDocument_Methods[] = {
    {"nativeCreateDocument", "()J", (void*) nativeCreateDocument},
    {"nativeStartPage", "(JIIIIII)J", (void*) nativeStartPage},
    {"nativeFinishPage", "(J)V", (void*) nativeFinishPage},
    {"nativeWriteTo", "(JLjava/io/OutputStream;[B)V", (void*) nativeWriteTo},
    {"nativeClose", "(J)V", (void*) nativeClose}
};

int register_android_graphics_pdf_PdfDocument(JNIEnv* env) {
    return RegisterMethodsOrDie(
            env, "android/graphics/pdf/PdfDocument", gPdfDocument_Methods,
            NELEM(gPdfDocument_Methods));
}

};
