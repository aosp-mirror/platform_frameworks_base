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

#include "jni.h"
#include "JNIHelp.h"
#include "GraphicsJNI.h"
#include "SkBitmap.h"
#include "SkMatrix.h"
#include "fpdfview.h"

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdelete-non-virtual-dtor"
#include "fsdk_rendercontext.h"
#pragma GCC diagnostic pop

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

// See PdfEditor.cpp
extern int sUnmatchedPdfiumInitRequestCount;

static void initializeLibraryIfNeeded() {
    if (sUnmatchedPdfiumInitRequestCount == 0) {
        FPDF_InitLibrary();
    }
    sUnmatchedPdfiumInitRequestCount++;
}

static void destroyLibraryIfNeeded() {
    sUnmatchedPdfiumInitRequestCount--;
    if (sUnmatchedPdfiumInitRequestCount == 0) {
       FPDF_DestroyLibrary();
    }
}

static int getBlock(void* param, unsigned long position, unsigned char* outBuffer,
        unsigned long size) {
    const int fd = reinterpret_cast<intptr_t>(param);
    const int readCount = pread(fd, outBuffer, size, position);
    if (readCount < 0) {
        ALOGE("Cannot read from file descriptor. Error:%d", errno);
        return 0;
    }
    return 1;
}

static jlong nativeCreate(JNIEnv* env, jclass thiz, jint fd, jlong size) {
    initializeLibraryIfNeeded();

    FPDF_FILEACCESS loader;
    loader.m_FileLen = size;
    loader.m_Param = reinterpret_cast<void*>(intptr_t(fd));
    loader.m_GetBlock = &getBlock;

    FPDF_DOCUMENT document = FPDF_LoadCustomDocument(&loader, NULL);

    if (!document) {
        const long error = FPDF_GetLastError();
        switch (error) {
            case FPDF_ERR_PASSWORD:
            case FPDF_ERR_SECURITY: {
                jniThrowExceptionFmt(env, "java/lang/SecurityException",
                        "cannot create document. Error: %ld", error);
            } break;
            default: {
                jniThrowExceptionFmt(env, "java/io/IOException",
                        "cannot create document. Error: %ld", error);
            } break;
        }
        destroyLibraryIfNeeded();
        return -1;
    }

    return reinterpret_cast<jlong>(document);
}

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

    const int result = FPDF_GetPageSizeByIndex(document, pageIndex, &width, &height);

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

static void nativeClose(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    FPDF_CloseDocument(document);
    destroyLibraryIfNeeded();
}

static jint nativeGetPageCount(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    return FPDF_GetPageCount(document);
}

static jboolean nativeScaleForPrinting(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    return FPDF_VIEWERREF_GetPrintScaling(document);
}

static void DropContext(void* data) {
    delete (CRenderContext*) data;
}

static void renderPageBitmap(FPDF_BITMAP bitmap, FPDF_PAGE page, int destLeft, int destTop,
        int destRight, int destBottom, SkMatrix* transform, int flags) {
    // Note: this code ignores the currently unused RENDER_NO_NATIVETEXT,
    // FPDF_RENDER_LIMITEDIMAGECACHE, FPDF_RENDER_FORCEHALFTONE, FPDF_GRAYSCALE,
    // and FPDF_ANNOT flags. To add support for that refer to FPDF_RenderPage_Retail
    // in fpdfview.cpp

    CRenderContext* pContext = new CRenderContext;

    CPDF_Page* pPage = (CPDF_Page*) page;
    pPage->SetPrivateData((void*) 1, pContext, DropContext);

    CFX_FxgeDevice* fxgeDevice = new CFX_FxgeDevice;
    pContext->m_pDevice = fxgeDevice;

    // Reverse the bytes (last argument TRUE) since the Android
    // format is ARGB while the renderer uses BGRA internally.
    fxgeDevice->Attach((CFX_DIBitmap*) bitmap, 0, TRUE);

    CPDF_RenderOptions* renderOptions = pContext->m_pOptions;

    if (!renderOptions) {
        renderOptions = new CPDF_RenderOptions;
        pContext->m_pOptions = renderOptions;
    }

    if (flags & FPDF_LCD_TEXT) {
        renderOptions->m_Flags |= RENDER_CLEARTYPE;
    } else {
        renderOptions->m_Flags &= ~RENDER_CLEARTYPE;
    }

    const CPDF_OCContext::UsageType usage = (flags & FPDF_PRINTING)
            ? CPDF_OCContext::Print : CPDF_OCContext::View;

    renderOptions->m_AddFlags = flags >> 8;
    renderOptions->m_pOCContext = new CPDF_OCContext(pPage->m_pDocument, usage);

    fxgeDevice->SaveState();

    FX_RECT clip;
    clip.left = destLeft;
    clip.right = destRight;
    clip.top = destTop;
    clip.bottom = destBottom;
    fxgeDevice->SetClip_Rect(&clip);

    CPDF_RenderContext* pageContext = new CPDF_RenderContext(pPage);
    pContext->m_pContext = pageContext;

    CFX_Matrix matrix;
    if (!transform) {
        pPage->GetDisplayMatrix(matrix, destLeft, destTop, destRight - destLeft,
                destBottom - destTop, 0);
    } else {
        // PDF's coordinate system origin is left-bottom while
        // in graphics it is the top-left, so remap the origin.
        matrix.Set(1, 0, 0, -1, 0, pPage->GetPageHeight());

        SkScalar transformValues[6];
        if (transform->asAffine(transformValues)) {
            matrix.Concat(transformValues[SkMatrix::kAScaleX], transformValues[SkMatrix::kASkewY],
                    transformValues[SkMatrix::kASkewX], transformValues[SkMatrix::kAScaleY],
                    transformValues[SkMatrix::kATransX], transformValues[SkMatrix::kATransY]);
        } else {
            // Already checked for a return value of false in the caller, so this should never
            // happen.
            ALOGE("Error rendering page!");
        }

    }
    pageContext->AppendObjectList(pPage, &matrix);

    pContext->m_pRenderer = new CPDF_ProgressiveRenderer(pageContext, fxgeDevice, renderOptions);
    pContext->m_pRenderer->Start(NULL);

    fxgeDevice->RestoreState();

    pPage->RemovePrivateData((void*) 1);

    delete pContext;
}

static void nativeRenderPage(JNIEnv* env, jclass thiz, jlong documentPtr, jlong pagePtr,
        jobject jbitmap, jint destLeft, jint destTop, jint destRight, jint destBottom,
        jlong matrixPtr, jint renderMode) {

    FPDF_PAGE page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    SkMatrix* skMatrix = reinterpret_cast<SkMatrix*>(matrixPtr);

    SkBitmap skBitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &skBitmap);

    SkAutoLockPixels alp(skBitmap);

    const int stride = skBitmap.width() * 4;

    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(skBitmap.width(), skBitmap.height(),
            FPDFBitmap_BGRA, skBitmap.getPixels(), stride);

    if (!bitmap) {
        ALOGE("Erorr creating bitmap");
        return;
    }

    int renderFlags = 0;
    if (renderMode == RENDER_MODE_FOR_DISPLAY) {
        renderFlags |= FPDF_LCD_TEXT;
    } else if (renderMode == RENDER_MODE_FOR_PRINT) {
        renderFlags |= FPDF_PRINTING;
    }

    if (skMatrix && !skMatrix->asAffine(NULL)) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "transform matrix has perspective. Only affine matrices are allowed.");
        return;
    }

    renderPageBitmap(bitmap, page, destLeft, destTop, destRight,
            destBottom, skMatrix, renderFlags);

    skBitmap.notifyPixelsChanged();
}

static const JNINativeMethod gPdfRenderer_Methods[] = {
    {"nativeCreate", "(IJ)J", (void*) nativeCreate},
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
