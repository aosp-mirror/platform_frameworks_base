/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "fpdfview.h"

#define LOG_TAG "PdfUtils"
#include <utils/Log.h>

namespace android {

static int sUnmatchedPdfiumInitRequestCount = 0;

int getBlock(void* param, unsigned long position, unsigned char* outBuffer,
        unsigned long size) {
    const int fd = reinterpret_cast<intptr_t>(param);
    const int readCount = pread(fd, outBuffer, size, position);
    if (readCount < 0) {
        ALOGE("Cannot read from file descriptor. Error:%d", errno);
        return 0;
    }
    return 1;
}

// Check if the last pdfium command failed and if so, forward the error to java via an exception. If
// this function returns true an exception is pending.
bool forwardPdfiumError(JNIEnv* env) {
    long error = FPDF_GetLastError();
    switch (error) {
        case FPDF_ERR_SUCCESS:
            return false;
        case FPDF_ERR_FILE:
            jniThrowException(env, "java/io/IOException", "file not found or cannot be opened");
            break;
        case FPDF_ERR_FORMAT:
            jniThrowException(env, "java/io/IOException", "file not in PDF format or corrupted");
            break;
        case FPDF_ERR_PASSWORD:
            jniThrowException(env, "java/lang/SecurityException",
                    "password required or incorrect password");
            break;
        case FPDF_ERR_SECURITY:
            jniThrowException(env, "java/lang/SecurityException", "unsupported security scheme");
            break;
        case FPDF_ERR_PAGE:
            jniThrowException(env, "java/io/IOException", "page not found or content error");
            break;
#ifdef PDF_ENABLE_XFA
        case FPDF_ERR_XFALOAD:
            jniThrowException(env, "java/lang/Exception", "load XFA error");
            break;
        case FPDF_ERR_XFALAYOUT:
            jniThrowException(env, "java/lang/Exception", "layout XFA error");
            break;
#endif  // PDF_ENABLE_XFA
        case FPDF_ERR_UNKNOWN:
        default:
            jniThrowExceptionFmt(env, "java/lang/Exception", "unknown error %d", error);
    }

    return true;
}

static void initializeLibraryIfNeeded(JNIEnv* env) {
    if (sUnmatchedPdfiumInitRequestCount == 0) {
        FPDF_InitLibrary();
    }

    sUnmatchedPdfiumInitRequestCount++;
}

static void destroyLibraryIfNeeded(JNIEnv* env, bool handleError) {
    if (sUnmatchedPdfiumInitRequestCount == 1) {
        FPDF_DestroyLibrary();
    }

    sUnmatchedPdfiumInitRequestCount--;
}

jlong nativeOpen(JNIEnv* env, jclass thiz, jint fd, jlong size) {
    initializeLibraryIfNeeded(env);

    FPDF_FILEACCESS loader;
    loader.m_FileLen = size;
    loader.m_Param = reinterpret_cast<void*>(intptr_t(fd));
    loader.m_GetBlock = &getBlock;

    FPDF_DOCUMENT document = FPDF_LoadCustomDocument(&loader, NULL);
    if (!document) {
        forwardPdfiumError(env);
        destroyLibraryIfNeeded(env, false);
        return -1;
    }

    return reinterpret_cast<jlong>(document);
}

void nativeClose(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    FPDF_CloseDocument(document);

    destroyLibraryIfNeeded(env, true);
}

jint nativeGetPageCount(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);

    return FPDF_GetPageCount(document);
}

jboolean nativeScaleForPrinting(JNIEnv* env, jclass thiz, jlong documentPtr) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    FPDF_BOOL printScaling = FPDF_VIEWERREF_GetPrintScaling(document);

    return printScaling ? JNI_TRUE : JNI_FALSE;
}

};
