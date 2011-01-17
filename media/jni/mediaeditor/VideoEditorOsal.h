/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef VIDEO_EDITOR_OSAL_H
#define VIDEO_EDITOR_OSAL_H

#include <jni.h>
#include <JNIHelp.h>

extern "C" {
#include <M4OSA_Error.h>
#include <M4OSA_Thread.h>
#include <M4OSA_FileReader.h>
#include <M4OSA_FileWriter.h>
};

const char*
videoEditOsal_getResultString(
                M4OSA_ERR                           result);

void*
videoEditOsal_alloc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                size_t                              size,
                const char*                         pDescription);

void
videoEditOsal_free(
                void*                               pData);

void
videoEditOsal_startThread(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                int                                 stackSize,
                M4OSA_ThreadDoIt                    callback,
                M4OSA_Context*                      pContext,
                void*                               pParam);

void
videoEditOsal_stopThread(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                M4OSA_Context*                      pContext);

void
videoEditOsal_getFilePointers ( M4OSA_FileReadPointer *pOsaFileReadPtr,
                                M4OSA_FileWriterPointer *pOsaFileWritePtr);

#endif // VIDEO_EDITOR_OSAL_H

