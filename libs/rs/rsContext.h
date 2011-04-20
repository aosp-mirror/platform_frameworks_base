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
#include "rsType.h"
#include "rsAllocation.h"
#include "rsMesh.h"

#include "rs_hal.h"

#ifndef ANDROID_RS_SERIALIZE
#include "rsMutex.h"
#include "rsThreadIO.h"
#include "rsMatrix4x4.h"
#include "rsDevice.h"
#include "rsScriptC.h"
#include "rsAdapter.h"
#include "rsSampler.h"
#include "rsFont.h"
#include "rsProgramFragment.h"
#include "rsProgramStore.h"
#include "rsProgramRaster.h"
#include "rsProgramVertex.h"
#include "rsShaderCache.h"
#include "rsFBOCache.h"
#include "rsVertexArray.h"

#include "rsgApiStructs.h"
#include "rsLocklessFifo.h"

#include <ui/egl/android_natives.h>
#endif // ANDROID_RS_SERIALIZE

// ---------------------------------------------------------------------------
namespace android {

namespace renderscript {

#if 0
#define CHECK_OBJ(o) { \
    GET_TLS(); \
    if (!ObjectBase::isValid(rsc, (const ObjectBase *)o)) {  \
        LOGE("Bad object %p at %s, %i", o, __FILE__, __LINE__);  \
    } \
}
#define CHECK_OBJ_OR_NULL(o) { \
    GET_TLS(); \
    if (o && !ObjectBase::isValid(rsc, (const ObjectBase *)o)) {  \
        LOGE("Bad object %p at %s, %i", o, __FILE__, __LINE__);  \
    } \
}
#else
#define CHECK_OBJ(o)
#define CHECK_OBJ_OR_NULL(o)
#endif

#ifndef ANDROID_RS_SERIALIZE

class Context {
public:
    struct Hal {
        void * drv;

        RsdHalFunctions funcs;
    };
    Hal mHal;

    static Context * createContext(Device *, const RsSurfaceConfig *sc);
    ~Context();

    static pthread_key_t gThreadTLSKey;
    static uint32_t gThreadTLSKeyCount;
    static uint32_t gGLContextCount;
    static pthread_mutex_t gInitMutex;
    // Library mutex (for providing thread-safe calls from the runtime)
    static pthread_mutex_t gLibMutex;

    class PushState {
    public:
        PushState(Context *);
        ~PushState();

    private:
        ObjectBaseRef<ProgramFragment> mFragment;
        ObjectBaseRef<ProgramVertex> mVertex;
        ObjectBaseRef<ProgramStore> mStore;
        ObjectBaseRef<ProgramRaster> mRaster;
        ObjectBaseRef<Font> mFont;
        Context *mRsc;
    };

    ScriptTLSStruct *mTlsStruct;
    RsSurfaceConfig mUserSurfaceConfig;

    ElementState mStateElement;
    TypeState mStateType;
    SamplerState mStateSampler;
    ProgramFragmentState mStateFragment;
    ProgramStoreState mStateFragmentStore;
    ProgramRasterState mStateRaster;
    ProgramVertexState mStateVertex;
    VertexArrayState mStateVertexArray;
    FontState mStateFont;

    ScriptCState mScriptC;
    ShaderCache mShaderCache;
    FBOCache mFBOCache;

    void swapBuffers();
    void setRootScript(Script *);
    void setProgramRaster(ProgramRaster *);
    void setProgramVertex(ProgramVertex *);
    void setProgramFragment(ProgramFragment *);
    void setProgramStore(ProgramStore *);
    void setFont(Font *);

    void updateSurface(void *sur);

    ProgramFragment * getProgramFragment() {return mFragment.get();}
    ProgramStore * getProgramStore() {return mFragmentStore.get();}
    ProgramRaster * getProgramRaster() {return mRaster.get();}
    ProgramVertex * getProgramVertex() {return mVertex.get();}
    Font * getFont() {return mFont.get();}

    bool setupCheck();
    void setupProgramStore();

    void pause();
    void resume();
    void setSurface(uint32_t w, uint32_t h, ANativeWindow *sur);
    void setPriority(int32_t p);
    void destroyWorkerThreadResources();

    void assignName(ObjectBase *obj, const char *name, uint32_t len);
    void removeName(ObjectBase *obj);

    RsMessageToClientType peekMessageToClient(size_t *receiveLen, uint32_t *subID, bool wait);
    RsMessageToClientType getMessageToClient(void *data, size_t *receiveLen, uint32_t *subID, size_t bufferLen, bool wait);
    bool sendMessageToClient(const void *data, RsMessageToClientType cmdID, uint32_t subID, size_t len, bool waitForSpace) const;
    uint32_t runScript(Script *s);

    void initToClient();
    void deinitToClient();

    ProgramFragment * getDefaultProgramFragment() const {
        return mStateFragment.mDefault.get();
    }
    ProgramVertex * getDefaultProgramVertex() const {
        return mStateVertex.mDefault.get();
    }
    ProgramStore * getDefaultProgramStore() const {
        return mStateFragmentStore.mDefault.get();
    }
    ProgramRaster * getDefaultProgramRaster() const {
        return mStateRaster.mDefault.get();
    }
    Font* getDefaultFont() const {
        return mStateFont.mDefault.get();
    }

    uint32_t getWidth() const {return mWidth;}
    uint32_t getHeight() const {return mHeight;}

    mutable ThreadIO mIO;

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

    struct {
        bool mLogTimes;
        bool mLogScripts;
        bool mLogObjects;
        bool mLogShaders;
        bool mLogShadersAttr;
        bool mLogShadersUniforms;
        bool mLogVisual;
    } props;

    void dumpDebug() const;
    void checkError(const char *, bool isFatal = false) const;
    void setError(RsError e, const char *msg = NULL) const;

    mutable const ObjectBase * mObjHead;

    bool ext_OES_texture_npot() const {return mGL.OES_texture_npot;}
    bool ext_GL_IMG_texture_npot() const {return mGL.GL_IMG_texture_npot;}
    bool ext_GL_NV_texture_npot_2D_mipmap() const {return mGL.GL_NV_texture_npot_2D_mipmap;}
    float ext_texture_max_aniso() const {return mGL.EXT_texture_max_aniso; }
    uint32_t getMaxFragmentTextures() const {return mGL.mMaxFragmentTextureImageUnits;}
    uint32_t getMaxFragmentUniformVectors() const {return mGL.mMaxFragmentUniformVectors;}
    uint32_t getMaxVertexUniformVectors() const {return mGL.mMaxVertexUniformVectors;}
    uint32_t getMaxVertexAttributes() const {return mGL.mMaxVertexAttribs;}

    uint32_t getDPI() const {return mDPI;}
    void setDPI(uint32_t dpi) {mDPI = dpi;}

    Device *mDev;
protected:

    struct {
        int32_t mMaxVaryingVectors;
        int32_t mMaxTextureImageUnits;

        int32_t mMaxFragmentTextureImageUnits;
        int32_t mMaxFragmentUniformVectors;

        int32_t mMaxVertexAttribs;
        int32_t mMaxVertexUniformVectors;
        int32_t mMaxVertexTextureUnits;

        bool OES_texture_npot;
        bool GL_IMG_texture_npot;
        bool GL_NV_texture_npot_2D_mipmap;
        float EXT_texture_max_aniso;
    } mGL;

    uint32_t mDPI;
    uint32_t mWidth;
    uint32_t mHeight;
    int32_t mThreadPriority;
    bool mIsGraphicsContext;

    bool mRunning;
    bool mExit;
    bool mPaused;
    mutable RsError mError;

    pthread_t mThreadId;
    pid_t mNativeThreadId;

    ObjectBaseRef<Script> mRootScript;
    ObjectBaseRef<ProgramFragment> mFragment;
    ObjectBaseRef<ProgramVertex> mVertex;
    ObjectBaseRef<ProgramStore> mFragmentStore;
    ObjectBaseRef<ProgramRaster> mRaster;
    ObjectBaseRef<Font> mFont;

    void displayDebugStats();

private:
    Context();
    bool initContext(Device *, const RsSurfaceConfig *sc);


    bool initGLThread();
    void deinitEGL();

    uint32_t runRootScript();

    static void * threadProc(void *);
    static void * helperThreadProc(void *);

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
    uint32_t mAverageFPSFrameCount;
    uint64_t mAverageFPSStartTime;
    uint32_t mAverageFPS;
};

#else

class Context {
public:
    Context() {
        mObjHead = NULL;
    }
    ~Context() {
        ObjectBase::zeroAllUserRef(this);
    }

    ElementState mStateElement;
    TypeState mStateType;

    struct {
        bool mLogTimes;
        bool mLogScripts;
        bool mLogObjects;
        bool mLogShaders;
        bool mLogShadersAttr;
        bool mLogShadersUniforms;
        bool mLogVisual;
    } props;

    void setError(RsError e, const char *msg = NULL) {  }

    mutable const ObjectBase * mObjHead;

protected:

};
#endif //ANDROID_RS_SERIALIZE

} // renderscript
} // android
#endif
