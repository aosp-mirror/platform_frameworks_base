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

#ifndef ANDROID_RS_CONTEXT_H
#define ANDROID_RS_CONTEXT_H

#include "rsUtils.h"

#include <ui/Surface.h>

#include "rsType.h"
#include "rsMatrix.h"
#include "rsAllocation.h"
#include "rsTriangleMesh.h"
#include "rsSimpleMesh.h"
#include "rsMesh.h"
#include "rsDevice.h"
#include "rsScriptC.h"
#include "rsAllocation.h"
#include "rsAdapter.h"
#include "rsSampler.h"
#include "rsLight.h"
#include "rsProgramFragment.h"
#include "rsProgramFragmentStore.h"
#include "rsProgramVertex.h"

#include "rsgApiStructs.h"
#include "rsLocklessFifo.h"


// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Context
{
public:
    Context(Device *, Surface *);
    ~Context();

    static pthread_key_t gThreadTLSKey;
    struct ScriptTLSStruct {
        Context * mContext;
        Script * mScript;
    };


    //StructuredAllocationContext mStateAllocation;
    ElementState mStateElement;
    TypeState mStateType;
    SamplerState mStateSampler;
    ProgramFragmentState mStateFragment;
    ProgramFragmentStoreState mStateFragmentStore;
    ProgramVertexState mStateVertex;
    LightState mStateLight;

    TriangleMeshContext mStateTriangleMesh;

    ScriptCState mScriptC;

    static Context * getContext() {return gCon;}

    void swapBuffers();
    void setRootScript(Script *);
    void setVertex(ProgramVertex *);
    void setFragment(ProgramFragment *);
    void setFragmentStore(ProgramFragmentStore *);

    void updateSurface(void *sur);

    const ProgramFragment * getFragment() {return mFragment.get();}
    const ProgramFragmentStore * getFragmentStore() {return mFragmentStore.get();}
    const ProgramVertex * getVertex() {return mVertex.get();}

    void setupCheck();
    void allocationCheck(const Allocation *);

    void assignName(ObjectBase *obj, const char *name, uint32_t len);
    void removeName(ObjectBase *obj);
    ObjectBase * lookupName(const char *name) const;
    void appendNameDefines(String8 *str) const;
    void appendVarDefines(String8 *str) const;

    ProgramFragment * getDefaultProgramFragment() const {
        return mStateFragment.mDefault.get();
    }
    ProgramVertex * getDefaultProgramVertex() const {
        return mStateVertex.mDefault.get();
    }
    ProgramFragmentStore * getDefaultProgramFragmentStore() const {
        return mStateFragmentStore.mDefault.get();
    }

    void addInt32Define(const char* name, int32_t value) {
        mInt32Defines.add(String8(name), value);
    }

    void addFloatDefine(const char* name, float value) {
        mFloatDefines.add(String8(name), value);
    }

    uint32_t getWidth() const {return mWidth;}
    uint32_t getHeight() const {return mHeight;}

protected:
    Device *mDev;

    EGLint mNumConfigs;
    EGLint mMajorVersion;
    EGLint mMinorVersion;
    EGLConfig mConfig;
    EGLContext mContext;
    EGLSurface mSurface;
    EGLint mWidth;
    EGLint mHeight;
    EGLDisplay mDisplay;

    bool mRunning;
    bool mExit;

    pthread_t mThreadId;

    ObjectBaseRef<Script> mRootScript;
    ObjectBaseRef<ProgramFragment> mFragment;
    ObjectBaseRef<ProgramVertex> mVertex;
    ObjectBaseRef<ProgramFragmentStore> mFragmentStore;

private:
    Context();

    void initEGL();

    bool runScript(Script *s, uint32_t launchID);
    bool runRootScript();

    static void * threadProc(void *);

    // todo: put in TLS
    static Context *gCon;
    Surface *mWndSurface;

    Vector<ObjectBase *> mNames;
    KeyedVector<String8,int> mInt32Defines;
    KeyedVector<String8,float> mFloatDefines;
};


}
}
#endif
