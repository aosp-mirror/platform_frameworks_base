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
#include "fpdfview.h"
#include "fpdfedit.h"
#include "fpdfsave.h"

#include <android_runtime/AndroidRuntime.h>
#include <vector>
#include <utils/Log.h>
#include <unistd.h>
#include <sys/types.h>
#include <unistd.h>

namespace android {

static Mutex sLock;

static int sUnmatchedInitRequestCount = 0;

static void initializeLibraryIfNeeded() {
    Mutex::Autolock _l(sLock);
    if (sUnmatchedInitRequestCount == 0) {
        FPDF_InitLibrary(NULL);
    }
    sUnmatchedInitRequestCount++;
}

static void destroyLibraryIfNeeded() {
    Mutex::Autolock _l(sLock);
    sUnmatchedInitRequestCount--;
    if (sUnmatchedInitRequestCount == 0) {
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

static jlong nativeOpen(JNIEnv* env, jclass thiz, jint fd, jlong size) {
    initializeLibraryIfNeeded();

    FPDF_FILEACCESS loader;
    loader.m_FileLen = size;
    loader.m_Param = reinterpret_cast<void*>(intptr_t(fd));
    loader.m_GetBlock = &getBlock;

    FPDF_DOCUMENT document = FPDF_LoadCustomDocument(&loader, NULL);

    if (!document) {
        const long error = FPDF_GetLastError();
        jniThrowException(env, "java/io/IOException",
                "cannot create document. Error:" + error);
        destroyLibraryIfNeeded();
        return -1;
    }

    return reinterpret_cast<jlong>(document);
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

static jint nativeRemovePage(JNIEnv* env, jclass thiz, jlong documentPtr, jint pageIndex) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    FPDFPage_Delete(document, pageIndex);
    return FPDF_GetPageCount(document);
}

struct PdfToFdWriter : FPDF_FILEWRITE {
    int dstFd;
};

static bool writeAllBytes(const int fd, const void* buffer, const size_t byteCount) {
    char* writeBuffer = static_cast<char*>(const_cast<void*>(buffer));
    size_t remainingBytes = byteCount;
    while (remainingBytes > 0) {
        ssize_t writtenByteCount = write(fd, writeBuffer, remainingBytes);
        if (writtenByteCount == -1) {
            if (errno == EINTR) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                    "Error writing to buffer: %d", errno);
            return false;
        }
        remainingBytes -= writtenByteCount;
        writeBuffer += writtenByteCount;
    }
    return true;
}

static int writeBlock(FPDF_FILEWRITE* owner, const void* buffer, unsigned long size) {
    const PdfToFdWriter* writer = reinterpret_cast<PdfToFdWriter*>(owner);
    const bool success = writeAllBytes(writer->dstFd, buffer, size);
    if (success < 0) {
        ALOGE("Cannot write to file descriptor. Error:%d", errno);
        return 0;
    }
    return 1;
}

static void nativeWrite(JNIEnv* env, jclass thiz, jlong documentPtr, jint fd) {
    FPDF_DOCUMENT document = reinterpret_cast<FPDF_DOCUMENT>(documentPtr);
    PdfToFdWriter writer;
    writer.dstFd = fd;
    writer.WriteBlock = &writeBlock;
    const bool success = FPDF_SaveAsCopy(document, &writer, FPDF_NO_INCREMENTAL);
    if (!success) {
        jniThrowException(env, "java/io/IOException",
                "cannot write to fd. Error:" + errno);
        destroyLibraryIfNeeded();
    }
}

static JNINativeMethod gPdfEditor_Methods[] = {
    {"nativeOpen", "(IJ)J", (void*) nativeOpen},
    {"nativeClose", "(J)V", (void*) nativeClose},
    {"nativeGetPageCount", "(J)I", (void*) nativeGetPageCount},
    {"nativeRemovePage", "(JI)I", (void*) nativeRemovePage},
    {"nativeWrite", "(JI)V", (void*) nativeWrite}
};

int register_android_graphics_pdf_PdfEditor(JNIEnv* env) {
    return android::AndroidRuntime::registerNativeMethods(
            env, "android/graphics/pdf/PdfEditor", gPdfEditor_Methods,
            NELEM(gPdfEditor_Methods));
};

};
