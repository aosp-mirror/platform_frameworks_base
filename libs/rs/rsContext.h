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

#include "rsThreadIO.h"
#include "rsType.h"
#include "rsMatrix.h"
#include "rsAllocation.h"
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
#include "rsProgramRaster.h"
#include "rsProgramVertex.h"
#include "rsShaderCache.h"
#include "rsVertexArray.h"

#include "rsgApiStructs.h"
#include "rsLocklessFifo.h"

#include <ui/egl/android_natives.h>

// ---------------------------------------------------------------------------
namespace android {

namespace renderscript {

class Context
{
public:
    Context(Device *, bool isGraphics, bool useDepth);
    ~Context();

    static pthread_key_t gThreadTLSKey;
    static uint32_t gThreadTLSKeyCount;
    static uint32_t gGLContextCount;
    static pthread_mutex_t gInitMutex;

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
    ProgramRasterState mStateRaster;
    ProgramVertexState mStateVertex;
    LightState mStateLight;
    VertexArrayState mStateVertexArray;

    ScriptCState mScriptC;
    ShaderCache mShaderCache;

    void swapBuffers();
    void setRootScript(Script *);
    void setRaster(ProgramRaster *);
    void setVertex(ProgramVertex *);
    void setFragment(ProgramFragment *);
    void setFragmentStore(ProgramFragmentStore *);

    void updateSurface(void *sur);

    const ProgramFragment * getFragment() {return mFragment.get();}
    const ProgramFragmentStore * getFragmentStore() {return mFragmentStore.get();}
    const ProgramRaster * getRaster() {return mRaster.get();}
    const ProgramVertex * getVertex() {return mVertex.get();}

    bool setupCheck();
    bool checkDriver() const {return mEGL.mSurface != 0;}

    void pause();
    void resume();
    void setSurface(uint32_t w, uint32_t h, ANativeWindow *sur);
    void setPriority(int32_t p);

    void assignName(ObjectBase *obj, const char *name, uint32_t len);
    void removeName(ObjectBase *obj);
    ObjectBase * lookupName(const char *name) const;
    void appendNameDefines(String8 *str) const;

    uint32_t getMessageToClient(void *data, size_t *receiveLen, size_t bufferLen, bool wait);
    bool sendMessageToClient(void *data, uint32_t cmdID, size_t len, bool waitForSpace);
    uint32_t runScript(Script *s, uint32_t launchID);

    void initToClient();
    void deinitToClient();

    ProgramFragment * getDefaultProgramFragment() const {
        return mStateFragment.mDefault.get();
    }
    ProgramVertex * getDefaultProgramVertex() const {
        return mStateVertex.mDefault.get();
    }
    ProgramFragmentStore * getDefaultProgramFragmentStore() const {
        return mStateFragmentStore.mDefault.get();
    }
    ProgramRaster * getDefaultProgramRaster() const {
        return mStateRaster.mDefault.get();
    }

    uint32_t getWidth() const {return mEGL.mWidth;}
    uint32_t getHeight() const {return mEGL.mHeight;}


    ThreadIO mIO;
    void objDestroyAdd(ObjectBase *);

    // Timers
    enum Timers {
        RS_TIMER_IDLE,
        RS_TIMER_INTERNAL,
        RS_TIMER_SCRIPT,
        RS_TIMER_CLEAR_SWAP,
        _RS_TIMER_TOTAL
    };
    uint64_t getTime() const;
    void timerInit();
    void timerReset();
    void timerSet(Timers);
    void timerPrint();
    void timerFrame();

    bool checkVersion1_1() const {return (mGL.mMajorVersion > 1) || (mGL.mMinorVersion >= 1); }
    bool checkVersion2_0() const {return mGL.mMajorVersion >= 2; }

    struct {
        bool mLogTimes;
        bool mLogScripts;
        bool mLogObjects;
        bool mLogShaders;
    } props;

    void dumpDebug() const;
    void checkError(const char *) const;
    const char * getError(RsError *);
    void setError(RsError e, const char *msg);

    mutable const ObjectBase * mObjHead;

    bool ext_OES_texture_npot() const {return mGL.OES_texture_npot;}
    bool ext_GL_IMG_texture_npot() const {return mGL.GL_IMG_texture_npot;}

protected:
    Device *mDev;

    struct {
        EGLint mNumConfigs;
        EGLint mMajorVersion;
        EGLint mMinorVersion;
        EGLConfig mConfig;
        EGLContext mContext;
        EGLSurface mSurface;
        EGLint mWidth;
        EGLint mHeight;
        EGLDisplay mDisplay;
    } mEGL;

    struct {
        const uint8_t * mVendor;
        const uint8_t * mRenderer;
        const uint8_t * mVersion;
        const uint8_t * mExtensions;

        uint32_t mMajorVersion;
        uint32_t mMinorVersion;

        int32_t mMaxVaryingVectors;
        int32_t mMaxTextureImageUnits;

        int32_t mMaxFragmentTextureImageUnits;
        int32_t mMaxFragmentUniformVectors;

        int32_t mMaxVertexAttribs;
        int32_t mMaxVertexUniformVectors;
        int32_t mMaxVertexTextureUnits;

        bool OES_texture_npot;
        bool GL_IMG_texture_npot;
    } mGL;

    uint32_t mWidth;
    uint32_t mHeight;
    int32_t mThreadPriority;
    bool mIsGraphicsContext;

    bool mRunning;
    bool mExit;
    bool mUseDepth;
    bool mPaused;
    RsError mError;
    const char *mErrorMsg;

    pthread_t mThreadId;
    pid_t mNativeThreadId;

    ObjectBaseRef<Script> mRootScript;
    ObjectBaseRef<ProgramFragment> mFragment;
    ObjectBaseRef<ProgramVertex> mVertex;
    ObjectBaseRef<ProgramFragmentStore> mFragmentStore;
    ObjectBaseRef<ProgramRaster> mRaster;


    struct ObjDestroyOOB {
        pthread_mutex_t mMutex;
        Vector<ObjectBase *> mDestroyList;
        bool mNeedToEmpty;
    };
    ObjDestroyOOB mObjDestroy;
    bool objDestroyOOBInit();
    void objDestroyOOBRun();
    void objDestroyOOBDestroy();

private:
    Context();

    void initEGL(bool useGL2);
    void deinitEGL();

    uint32_t runRootScript();

    static void * threadProc(void *);

    ANativeWindow *mWndSurface;

    Vector<ObjectBase *> mNames;

    uint64_t mTimers[_RS_TIMER_TOTAL];
    Timers mTimerActive;
    uint64_t mTimeLast;
    uint64_t mTimeFrame;
    uint64_t mTimeLastFrame;
    uint32_t mTimeMSLastFrame;
    uint32_t mTimeMSLastScript;
    uint32_t mTimeMSLastSwap;
};

}
}
#endif
