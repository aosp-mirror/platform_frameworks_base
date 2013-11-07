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
#include <android_runtime/AndroidRuntime.h>
#include <vector>

#include "CreateJavaOutputStreamAdaptor.h"

#include "SkCanvas.h"
#include "SkDocument.h"
#include "SkPicture.h"
#include "SkStream.h"
#include "SkRect.h"

namespace android {

struct PageRecord {

    PageRecord(int width, int height, const SkRect& contentRect)
            : mPicture(new SkPicture()), mWidth(width), mHeight(height) {
        mContentRect = contentRect;
    }

    ~PageRecord() {
        mPicture->unref();
    }

    SkPicture* const mPicture;
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

        SkCanvas* canvas = page->mPicture->beginRecording(
                contentRect.width(), contentRect.height(), 0);

        // We pass this canvas to Java where it is used to construct
        // a Java Canvas object which dereferences the pointer when it
        // is destroyed, so we have to bump up the reference count.
        canvas->ref();

        return canvas;
    }

    void finishPage() {
        assert(mCurrentPage != NULL);
        mCurrentPage->mPicture->endRecording();
        mCurrentPage = NULL;
    }

    void write(SkWStream* stream) {
        SkDocument* document = SkDocument::CreatePDF(stream);
        for (unsigned i = 0; i < mPages.size(); i++) {
            PageRecord* page =  mPages[i];

            SkCanvas* canvas = document->beginPage(page->mWidth, page->mHeight,
                    &(page->mContentRect));

            canvas->clipRect(page->mContentRect);
            canvas->translate(page->mContentRect.left(), page->mContentRect.top());
            canvas->drawPicture(*page->mPicture);

            document->endPage();
        }
        document->close();
    }

    void close() {
        for (unsigned i = 0; i < mPages.size(); i++) {
            delete mPages[i];
        }
        delete mCurrentPage;
        mCurrentPage = NULL;
    }

private:
    ~PdfDocument() {
        close();
    }

    std::vector<PageRecord*> mPages;
    PageRecord* mCurrentPage;
};

static jint nativeCreateDocument(JNIEnv* env, jobject thiz) {
    return reinterpret_cast<jint>(new PdfDocument());
}

static jint nativeStartPage(JNIEnv* env, jobject thiz, jint documentPtr,
        jint pageWidth, jint pageHeight,
        jint contentLeft, jint contentTop, jint contentRight, jint contentBottom) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    return reinterpret_cast<jint>(document->startPage(pageWidth, pageHeight,
            contentLeft, contentTop, contentRight, contentBottom));
}

static void nativeFinishPage(JNIEnv* env, jobject thiz, jint documentPtr) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    document->finishPage();
}

static void nativeWriteTo(JNIEnv* env, jobject thiz, jint documentPtr, jobject out,
        jbyteArray chunk) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    SkWStream* skWStream = CreateJavaOutputStreamAdaptor(env, out, chunk);
    document->write(skWStream);
    delete skWStream;
}

static void nativeClose(JNIEnv* env, jobject thiz, jint documentPtr) {
    PdfDocument* document = reinterpret_cast<PdfDocument*>(documentPtr);
    document->close();
}

static JNINativeMethod gPdfDocument_Methods[] = {
    {"nativeCreateDocument", "()I", (void*) nativeCreateDocument},
    {"nativeStartPage", "(IIIIIII)I", (void*) nativeStartPage},
    {"nativeFinishPage", "(I)V", (void*) nativeFinishPage},
    {"nativeWriteTo", "(ILjava/io/OutputStream;[B)V", (void*) nativeWriteTo},
    {"nativeClose", "(I)V", (void*) nativeClose}
};

int register_android_graphics_pdf_PdfDocument(JNIEnv* env) {
    int result = android::AndroidRuntime::registerNativeMethods(
            env, "android/graphics/pdf/PdfDocument", gPdfDocument_Methods,
            NELEM(gPdfDocument_Methods));
    return result;
}

};
