/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

#ifndef ANDROID_RENDERSCRIPT_H
#define ANDROID_RENDERSCRIPT_H


#include <pthread.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include "rsDefines.h"

class Element;
class Type;
class Allocation;

class RenderScript {
    friend class BaseObj;
    friend class Allocation;
    friend class Element;
    friend class Type;
    friend class Script;
    friend class ScriptC;

public:
    RenderScript();
    virtual ~RenderScript();

    typedef void (*ErrorHandlerFunc_t)(uint32_t errorNum, const char *errorText);
    typedef void (*MessageHandlerFunc_t)(uint32_t msgNum, const void *msgData, size_t msgLen);


    void setErrorHandler(ErrorHandlerFunc_t func);
    ErrorHandlerFunc_t getErrorHandler() {return mErrorFunc;}

    void setMessageHandler(MessageHandlerFunc_t func);
    MessageHandlerFunc_t getMessageHandler() {return mMessageFunc;}

    bool init(int targetApi);
    void contextDump();
    void finish();

private:
    static bool gInitialized;
    static pthread_mutex_t gInitMutex;

    pthread_t mMessageThreadId;
    pid_t mNativeMessageThreadId;
    bool mMessageRun;

    RsDevice mDev;
    RsContext mContext;

    ErrorHandlerFunc_t mErrorFunc;
    MessageHandlerFunc_t mMessageFunc;

    struct {
        Element *U8;
        Element *I8;
        Element *U16;
        Element *I16;
        Element *U32;
        Element *I32;
        Element *U64;
        Element *I64;
        Element *F32;
        Element *F64;
        Element *BOOLEAN;

        Element *ELEMENT;
        Element *TYPE;
        Element *ALLOCATION;
        Element *SAMPLER;
        Element *SCRIPT;
        Element *MESH;
        Element *PROGRAM_FRAGMENT;
        Element *PROGRAM_VERTEX;
        Element *PROGRAM_RASTER;
        Element *PROGRAM_STORE;

        Element *A_8;
        Element *RGB_565;
        Element *RGB_888;
        Element *RGBA_5551;
        Element *RGBA_4444;
        Element *RGBA_8888;

        Element *FLOAT_2;
        Element *FLOAT_3;
        Element *FLOAT_4;

        Element *DOUBLE_2;
        Element *DOUBLE_3;
        Element *DOUBLE_4;

        Element *UCHAR_2;
        Element *UCHAR_3;
        Element *UCHAR_4;

        Element *CHAR_2;
        Element *CHAR_3;
        Element *CHAR_4;

        Element *USHORT_2;
        Element *USHORT_3;
        Element *USHORT_4;

        Element *SHORT_2;
        Element *SHORT_3;
        Element *SHORT_4;

        Element *UINT_2;
        Element *UINT_3;
        Element *UINT_4;

        Element *INT_2;
        Element *INT_3;
        Element *INT_4;

        Element *ULONG_2;
        Element *ULONG_3;
        Element *ULONG_4;

        Element *LONG_2;
        Element *LONG_3;
        Element *LONG_4;

        Element *MATRIX_4X4;
        Element *MATRIX_3X3;
        Element *MATRIX_2X2;
    } mElements;



    void throwError(const char *err) const;

    static void * threadProc(void *);

};

#endif

