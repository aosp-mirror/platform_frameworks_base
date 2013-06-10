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

#include "SkCanvas.h"
#include "SkPDFDevice.h"
#include "SkPDFDocument.h"
#include "SkRect.h"
#include "SkSize.h"
#include "CreateJavaOutputStreamAdaptor.h"

namespace android {

class PdfDocumentGlue {
public:

    static SkPDFDocument* createDocument(JNIEnv* env, jobject clazz) {
        return new SkPDFDocument();
    }

    static void finalize(JNIEnv* env, jobject thiz, SkPDFDocument* document) {
        delete document;
    }

    static SkCanvas* createPage(JNIEnv* env, jobject thiz, jobject pageSize,
            jobject contentSize, const SkMatrix* initialTransformation) {
        NPE_CHECK_RETURN_ZERO(env, pageSize);
        NPE_CHECK_RETURN_ZERO(env, contentSize);

        SkIRect skPageSizeRect;
        GraphicsJNI::jrect_to_irect(env, pageSize, &skPageSizeRect);
        SkISize skPageSize = SkISize::Make(skPageSizeRect.width(),
                skPageSizeRect.height());

        SkIRect skContentRect;
        GraphicsJNI::jrect_to_irect(env, contentSize, &skContentRect);
        SkISize skContentSize = SkISize::Make(skContentRect.width(),
                skContentRect.height());

        SkPDFDevice* skPdfDevice = new SkPDFDevice(skPageSize,
                skContentSize, *initialTransformation);

        return new SkCanvas(skPdfDevice);
    }

    static bool appendPage(JNIEnv* env, jobject thiz,
            SkPDFDocument* document, SkCanvas* page) {
        SkPDFDevice* device = reinterpret_cast<SkPDFDevice*>(page->getDevice());
        return document->appendPage(device);
    }

    static void write(JNIEnv* env, jobject clazz, SkPDFDocument* document,
            jobject out, jbyteArray chunk) {
        SkWStream* skWStream = CreateJavaOutputStreamAdaptor(env, out, chunk);
        document->emitPDF(skWStream);
        delete skWStream;
    }
};

static JNINativeMethod gPdfDocumentMethods[] = {
    {"native_createDocument", "()I", (void*) PdfDocumentGlue::createDocument},
    {"native_finalize", "(I)V", (void*) PdfDocumentGlue::finalize},
    {"native_createPage", "(Landroid/graphics/Rect;Landroid/graphics/Rect;I)I",
            (void*) PdfDocumentGlue::createPage},
    {"native_appendPage", "(II)Z", (void*) PdfDocumentGlue::appendPage},
    {"native_write", "(ILjava/io/OutputStream;[B)V", (void*) PdfDocumentGlue::write}
};

int register_android_print_PdfDocument(JNIEnv* env) {
    int result = android::AndroidRuntime::registerNativeMethods(
            env, "android/print/pdf/PdfDocument", gPdfDocumentMethods,
            NELEM(gPdfDocumentMethods));
    return result;
}
} // end namespace Andorid
