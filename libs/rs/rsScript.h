/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_SCRIPT_H
#define ANDROID_RS_SCRIPT_H

#include "rsAllocation.h"


// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramVertex;
class ProgramFragment;
class ProgramRaster;
class ProgramFragmentStore;

#define MAX_SCRIPT_BANKS 16

class Script : public ObjectBase
{
public:
    typedef void (* InvokeFunc_t)(void);

    Script(Context *);
    virtual ~Script();


    struct Enviroment_t {
        bool mIsRoot;
        float mClearColor[4];
        float mClearDepth;
        uint32_t mClearStencil;

        uint32_t mStartTimeMillis;
        const char* mTimeZone;

        ObjectBaseRef<ProgramVertex> mVertex;
        ObjectBaseRef<ProgramFragment> mFragment;
        ObjectBaseRef<ProgramRaster> mRaster;
        ObjectBaseRef<ProgramFragmentStore> mFragmentStore;
        InvokeFunc_t mInvokables[MAX_SCRIPT_BANKS];
        char * mScriptText;
        uint32_t mScriptTextLength;
    };
    Enviroment_t mEnviroment;

    uint32_t mCounstantBufferCount;


    ObjectBaseRef<Allocation> mSlots[MAX_SCRIPT_BANKS];
    ObjectBaseRef<const Type> mTypes[MAX_SCRIPT_BANKS];
    String8 mSlotNames[MAX_SCRIPT_BANKS];
    bool mSlotWritable[MAX_SCRIPT_BANKS];



    virtual void setupScript() = 0;
    virtual uint32_t run(Context *, uint32_t launchID) = 0;
};



}
}
#endif

