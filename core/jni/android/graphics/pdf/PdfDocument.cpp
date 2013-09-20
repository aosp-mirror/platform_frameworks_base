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
#include "JNIHelp.h"

namespace android {

#define LOGD(x...) do { Log::Instance()->printf(Log::ELogD, x); } while(0)

static jint nativeCreateDocument(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jint>(new SkPDFDocument());
}

static void nativeFinalize(JNIEnv* env, jobject thiz, jint documentPtr) {
    delete reinterpret_cast<SkPDFDocument*>(documentPtr);
}

static jint nativeCreatePage(JNIEnv* env, jobject thiz, jint pageWidth, jint pageHeight,
        jint contentLeft, jint contentTop, jint contentRight, jint contentBottom) {

    SkMatrix transformation;
    transformation.setTranslate(contentLeft, contentTop);

    SkISize skPageSize = SkISize::Make(pageWidth, pageHeight);
    SkISize skContentSize = SkISize::Make(contentRight - contentLeft, contentBottom - contentTop);

    SkPDFDevice* skPdfDevice = new SkPDFDevice(skPageSize, skContentSize, transformation);
    return reinterpret_cast<jint>(new SkCanvas(skPdfDevice));
}

static void nativeAppendPage(JNIEnv* env, jobject thiz, jint documentPtr, jint pagePtr) {
    SkCanvas* page = reinterpret_cast<SkCanvas*>(pagePtr);
    SkPDFDocument* document = reinterpret_cast<SkPDFDocument*>(documentPtr);
    SkPDFDevice* device = static_cast<SkPDFDevice*>(page->getDevice());
    document->appendPage(device);
}

static void nativeWriteTo(JNIEnv* env, jobject clazz, jint documentPtr,
        jobject out, jbyteArray chunk) {
    SkWStream* skWStream = CreateJavaOutputStreamAdaptor(env, out, chunk);
    SkPDFDocument* document = reinterpret_cast<SkPDFDocument*>(documentPtr);
    document->emitPDF(skWStream);
    delete skWStream;
}

static JNINativeMethod gPdfDocument_Methods[] = {
    {"nativeCreateDocument", "()I", (void*) nativeCreateDocument},
    {"nativeFinalize", "(I)V", (void*) nativeFinalize},
    {"nativeCreatePage", "(IIIIII)I",
            (void*) nativeCreatePage},
    {"nativeAppendPage", "(II)V", (void*) nativeAppendPage},
    {"nativeWriteTo", "(ILjava/io/OutputStream;[B)V", (void*) nativeWriteTo}
};

int register_android_graphics_pdf_PdfDocument(JNIEnv* env) {
    int result = android::AndroidRuntime::registerNativeMethods(
            env, "android/graphics/pdf/PdfDocument", gPdfDocument_Methods,
            NELEM(gPdfDocument_Methods));
    return result;
}

};
