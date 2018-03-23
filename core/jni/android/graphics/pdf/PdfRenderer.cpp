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

#include "PdfUtils.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "GraphicsJNI.h"
#include "SkBitmap.h"
#include "SkMatrix.h"
#include "fpdfview.h"

#include "core_jni_helpers.h"
#include <vector>
#include <utils/Log.h>
#include <unistd.h>
#include <sys/types.h>
#include <unistd.h>

namespace android {

static const int RENDER_MODE_FOR_DISPLAY = 1;
static const int RENDER_MODE_FOR_PRINT = 2;

static struct {
    jfieldID x;
    jfieldID y;
} gPointClassInfo;

static jlong nativeOpenPageAndGetSize(JNIEnv* env, jclass thiz, jlong documentPtr,
        jint pageIndex, jobject outSize) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);

    FPDF_PAGE page = FPDF_LoadPage(document, pageIndex);
    if (!page) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "cannot load page");
        return -1;
    }

    double width = 0;
    double height = 0;

    int result = FPDF_GetPageSizeByIndex(document, pageIndex, &width, &height);
    if (!result) {
        jniThrowException(env, "java/lang/IllegalStateException",
                    "cannot get page size");
        return -1;
    }

    env->SetIntField(outSize, gPointClassInfo.x, width);
    env->SetIntField(outSize, gPointClassInfo.y, height);

    return reinterpret_cast<jlong>(page);
}

static void nativeClosePage(JNIEnv* env, jclass thiz, jlong pagePtr) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    FPDF_ClosePage(page);
}

static void nativeRenderPage(JNIEnv* env, jclass thiz, jlong documentPtr, jlong pagePtr,
        jobject jbitmap, jint clipLeft, jint clipTop, jint clipRight, jint clipBottom,
        jlong transformPtr, jint renderMode) {
    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    SkBitmap skBitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &skBitmap);

    const int stride = skBitmap.width() * 4;

    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(skBitmap.width(), skBitmap.height(),
            FPDFBitmap_BGRA, skBitmap.getPixels(), stride);

    int renderFlags = FPDF_REVERSE_BYTE_ORDER;
    if (renderMode == RENDER_MODE_FOR_DISPLAY) {
        renderFlags |= FPDF_LCD_TEXT;
    } else if (renderMode == RENDER_MODE_FOR_PRINT) {
        renderFlags |= FPDF_PRINTING;
    }

    SkMatrix matrix = *reinterpret_cast<SkMatrix*>(transformPtr);
    SkScalar transformValues[6];
    if (!matrix.asAffine(transformValues)) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "transform matrix has perspective. Only affine matrices are allowed.");
        return;
    }

    FS_MATRIX transform = {transformValues[SkMatrix::kAScaleX], transformValues[SkMatrix::kASkewY],
                           transformValues[SkMatrix::kASkewX], transformValues[SkMatrix::kAScaleY],
                           transformValues[SkMatrix::kATransX],
                           transformValues[SkMatrix::kATransY]};

    FS_RECTF clip = {(float) clipLeft, (float) clipTop, (float) clipRight, (float) clipBottom};

    FPDF_RenderPageBitmapWithMatrix(bitmap, page, &transform, &clip, renderFlags);

    skBitmap.notifyPixelsChanged();
}

static const JNINativeMethod gPdfRenderer_Methods[] = {
    {"nativeCreate", "(IJ)J", (void*) nativeOpen},
    {"nativeClose", "(J)V", (void*) nativeClose},
    {"nativeGetPageCount", "(J)I", (void*) nativeGetPageCount},
    {"nativeScaleForPrinting", "(J)Z", (void*) nativeScaleForPrinting},
    {"nativeRenderPage", "(JJLandroid/graphics/Bitmap;IIIIJI)V", (void*) nativeRenderPage},
    {"nativeOpenPageAndGetSize", "(JILandroid/graphics/Point;)J", (void*) nativeOpenPageAndGetSize},
    {"nativeClosePage", "(J)V", (void*) nativeClosePage}
};

int register_android_graphics_pdf_PdfRenderer(JNIEnv* env) {
    int result = RegisterMethodsOrDie(
            env, "android/graphics/pdf/PdfRenderer", gPdfRenderer_Methods,
            NELEM(gPdfRenderer_Methods));

    jclass clazz = FindClassOrDie(env, "android/graphics/Point");
    gPointClassInfo.x = GetFieldIDOrDie(env, clazz, "x", "I");
    gPointClassInfo.y = GetFieldIDOrDie(env, clazz, "y", "I");

    return result;
};

};
