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

#include "rsDevice.h"
#include "rsContext.h"
#include "rsThreadIO.h"
#include <ui/FramebufferNativeWindow.h>
#include <ui/EGLUtils.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;

pthread_key_t Context::gThreadTLSKey = 0;

void Context::initEGL()
{
    mEGL.mNumConfigs = -1;
    EGLint configAttribs[128];
    EGLint *configAttribsPtr = configAttribs;

    memset(configAttribs, 0, sizeof(configAttribs));

    configAttribsPtr[0] = EGL_SURFACE_TYPE;
    configAttribsPtr[1] = EGL_WINDOW_BIT;
    configAttribsPtr += 2;

    if (mUseDepth) {
        configAttribsPtr[0] = EGL_DEPTH_SIZE;
        configAttribsPtr[1] = 16;
        configAttribsPtr += 2;
    }
    configAttribsPtr[0] = EGL_NONE;
    rsAssert(configAttribsPtr < (configAttribs + (sizeof(configAttribs) / sizeof(EGLint))));

    mEGL.mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(mEGL.mDisplay, &mEGL.mMajorVersion, &mEGL.mMinorVersion);

    status_t err = EGLUtils::selectConfigForNativeWindow(mEGL.mDisplay, configAttribs, mWndSurface, &mEGL.mConfig);
    if (err) {
     LOGE("couldn't find an EGLConfig matching the screen format\n");
    }
    //eglChooseConfig(mEGL.mDisplay, configAttribs, &mEGL.mConfig, 1, &mEGL.mNumConfigs);

    if (mWndSurface) {
        mEGL.mSurface = eglCreateWindowSurface(mEGL.mDisplay, mEGL.mConfig, mWndSurface, NULL);
    } else {
        mEGL.mSurface = eglCreateWindowSurface(mEGL.mDisplay, mEGL.mConfig,
             android_createDisplaySurface(),
             NULL);
    }

    mEGL.mContext = eglCreateContext(mEGL.mDisplay, mEGL.mConfig, NULL, NULL);
    eglMakeCurrent(mEGL.mDisplay, mEGL.mSurface, mEGL.mSurface, mEGL.mContext);
    eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_WIDTH, &mEGL.mWidth);
    eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_HEIGHT, &mEGL.mHeight);


    mGL.mVersion = glGetString(GL_VERSION);
    mGL.mVendor = glGetString(GL_VENDOR);
    mGL.mRenderer = glGetString(GL_RENDERER);
    mGL.mExtensions = glGetString(GL_EXTENSIONS);

    //LOGV("EGL Version %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
    //LOGV("GL Version %s", mGL.mVersion);
    //LOGV("GL Vendor %s", mGL.mVendor);
    //LOGV("GL Renderer %s", mGL.mRenderer);
    //LOGV("GL Extensions %s", mGL.mExtensions);

    if ((strlen((const char *)mGL.mVersion) < 12) || memcmp(mGL.mVersion, "OpenGL ES-CM", 12)) {
        LOGE("Error, OpenGL ES Lite not supported");
    } else {
        sscanf((const char *)mGL.mVersion + 13, "%i.%i", &mGL.mMajorVersion, &mGL.mMinorVersion);
    }
}

bool Context::runScript(Script *s, uint32_t launchID)
{
    ObjectBaseRef<ProgramFragment> frag(mFragment);
    ObjectBaseRef<ProgramVertex> vtx(mVertex);
    ObjectBaseRef<ProgramFragmentStore> store(mFragmentStore);

    bool ret = s->run(this, launchID);

    mFragment.set(frag);
    mVertex.set(vtx);
    mFragmentStore.set(store);
    return ret;
}


bool Context::runRootScript()
{
#if RS_LOG_TIMES
    timerSet(RS_TIMER_CLEAR_SWAP);
#endif
    rsAssert(mRootScript->mEnviroment.mIsRoot);

    //glColor4f(1,1,1,1);
    //glEnable(GL_LIGHT0);
    glViewport(0, 0, mEGL.mWidth, mEGL.mHeight);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glClearColor(mRootScript->mEnviroment.mClearColor[0],
                 mRootScript->mEnviroment.mClearColor[1],
                 mRootScript->mEnviroment.mClearColor[2],
                 mRootScript->mEnviroment.mClearColor[3]);
    if (mUseDepth) {
        glDepthMask(GL_TRUE);
        glClearDepthf(mRootScript->mEnviroment.mClearDepth);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    } else {
        glClear(GL_COLOR_BUFFER_BIT);
    }

#if RS_LOG_TIMES
    timerSet(RS_TIMER_SCRIPT);
#endif
    bool ret = runScript(mRootScript.get(), 0);
    return ret;
}

uint64_t Context::getTime() const
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_nsec + ((uint64_t)t.tv_sec * 1000 * 1000 * 1000);
}

void Context::timerReset()
{
    for (int ct=0; ct < _RS_TIMER_TOTAL; ct++) {
        mTimers[ct] = 0;
    }
}

void Context::timerInit()
{
    mTimeLast = getTime();
    mTimerActive = RS_TIMER_INTERNAL;
    timerReset();
}

void Context::timerSet(Timers tm)
{
    uint64_t last = mTimeLast;
    mTimeLast = getTime();
    mTimers[mTimerActive] += mTimeLast - last;
    mTimerActive = tm;
}

void Context::timerPrint()
{
    double total = 0;
    for (int ct = 0; ct < _RS_TIMER_TOTAL; ct++) {
        total += mTimers[ct];
    }

    LOGV("RS Time Data: Idle %2.1f (%lli),  Internal %2.1f (%lli),  Script %2.1f (%lli),  Clear & Swap %2.1f (%lli)",
         100.0 * mTimers[RS_TIMER_IDLE] / total, mTimers[RS_TIMER_IDLE] / 1000000,
         100.0 * mTimers[RS_TIMER_INTERNAL] / total, mTimers[RS_TIMER_INTERNAL] / 1000000,
         100.0 * mTimers[RS_TIMER_SCRIPT] / total, mTimers[RS_TIMER_SCRIPT] / 1000000,
         100.0 * mTimers[RS_TIMER_CLEAR_SWAP] / total, mTimers[RS_TIMER_CLEAR_SWAP] / 1000000);
}

void Context::setupCheck()
{
    if (mFragmentStore.get()) {
        mFragmentStore->setupGL(this, &mStateFragmentStore);
    }
    if (mFragment.get()) {
        mFragment->setupGL(this, &mStateFragment);
    }
    if (mVertex.get()) {
        mVertex->setupGL(this, &mStateVertex);
    }

}


void * Context::threadProc(void *vrsc)
{
     Context *rsc = static_cast<Context *>(vrsc);

     rsc->initEGL();

     ScriptTLSStruct *tlsStruct = new ScriptTLSStruct;
     if (!tlsStruct) {
         LOGE("Error allocating tls storage");
         return NULL;
     }
     tlsStruct->mContext = rsc;
     tlsStruct->mScript = NULL;
     int status = pthread_setspecific(rsc->gThreadTLSKey, tlsStruct);
     if (status) {
         LOGE("pthread_setspecific %i", status);
     }

     rsc->mStateVertex.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
     rsc->setVertex(NULL);
     rsc->mStateFragment.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
     rsc->setFragment(NULL);
     rsc->mStateFragmentStore.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
     rsc->setFragmentStore(NULL);

     rsc->mRunning = true;
     bool mDraw = true;
     while (!rsc->mExit) {
         mDraw |= rsc->mIO.playCoreCommands(rsc, !mDraw);
         mDraw &= (rsc->mRootScript.get() != NULL);

         if (mDraw) {
             mDraw = rsc->runRootScript();
#if RS_LOG_TIMES
             rsc->timerSet(RS_TIMER_CLEAR_SWAP);
#endif
             eglSwapBuffers(rsc->mEGL.mDisplay, rsc->mEGL.mSurface);
#if RS_LOG_TIMES
             rsc->timerSet(RS_TIMER_INTERNAL);
             rsc->timerPrint();
             rsc->timerReset();
#endif
         }
         if (rsc->mObjDestroy.mNeedToEmpty) {
             rsc->objDestroyOOBRun();
         }
     }

     LOGV("RS Thread exiting");
     glClearColor(0,0,0,0);
     glClear(GL_COLOR_BUFFER_BIT);
     eglSwapBuffers(rsc->mEGL.mDisplay, rsc->mEGL.mSurface);
     eglTerminate(rsc->mEGL.mDisplay);
     rsc->objDestroyOOBRun();
     LOGV("RS Thread exited");
     return NULL;
}

Context::Context(Device *dev, Surface *sur, bool useDepth)
{
    dev->addContext(this);
    mDev = dev;
    mRunning = false;
    mExit = false;
    mUseDepth = useDepth;

    int status;
    pthread_attr_t threadAttr;

    status = pthread_key_create(&gThreadTLSKey, NULL);
    if (status) {
        LOGE("Failed to init thread tls key.");
        return;
    }

    status = pthread_attr_init(&threadAttr);
    if (status) {
        LOGE("Failed to init thread attribute.");
        return;
    }

    sched_param sparam;
    sparam.sched_priority = ANDROID_PRIORITY_DISPLAY;
    pthread_attr_setschedparam(&threadAttr, &sparam);

    mWndSurface = sur;

    objDestroyOOBInit();
    timerInit();

    LOGV("RS Launching thread");
    status = pthread_create(&mThreadId, &threadAttr, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
    }

    while(!mRunning) {
        sleep(1);
    }

    pthread_attr_destroy(&threadAttr);
}

Context::~Context()
{
    LOGV("Context::~Context");
    mExit = true;
    void *res;

    mIO.shutdown();
    int status = pthread_join(mThreadId, &res);
    objDestroyOOBRun();

    if (mDev) {
        mDev->removeContext(this);
        pthread_key_delete(gThreadTLSKey);
    }

    objDestroyOOBDestroy();
}

void Context::setRootScript(Script *s)
{
    mRootScript.set(s);
}

void Context::setFragmentStore(ProgramFragmentStore *pfs)
{
    if (pfs == NULL) {
        mFragmentStore.set(mStateFragmentStore.mDefault);
    } else {
        mFragmentStore.set(pfs);
    }
}

void Context::setFragment(ProgramFragment *pf)
{
    if (pf == NULL) {
        mFragment.set(mStateFragment.mDefault);
    } else {
        mFragment.set(pf);
    }
}

void Context::allocationCheck(const Allocation *a)
{
    mVertex->checkUpdatedAllocation(a);
    mFragment->checkUpdatedAllocation(a);
    mFragmentStore->checkUpdatedAllocation(a);
}

void Context::setVertex(ProgramVertex *pv)
{
    if (pv == NULL) {
        mVertex.set(mStateVertex.mDefault);
    } else {
        mVertex.set(pv);
    }
}

void Context::assignName(ObjectBase *obj, const char *name, uint32_t len)
{
    rsAssert(!obj->getName());
    obj->setName(name, len);
    mNames.add(obj);
}

void Context::removeName(ObjectBase *obj)
{
    for(size_t ct=0; ct < mNames.size(); ct++) {
        if (obj == mNames[ct]) {
            mNames.removeAt(ct);
            return;
        }
    }
}

ObjectBase * Context::lookupName(const char *name) const
{
    for(size_t ct=0; ct < mNames.size(); ct++) {
        if (!strcmp(name, mNames[ct]->getName())) {
            return mNames[ct];
        }
    }
    return NULL;
}

void Context::appendNameDefines(String8 *str) const
{
    char buf[256];
    for (size_t ct=0; ct < mNames.size(); ct++) {
        str->append("#define NAMED_");
        str->append(mNames[ct]->getName());
        str->append(" ");
        sprintf(buf, "%i\n", (int)mNames[ct]);
        str->append(buf);
    }
}

void Context::appendVarDefines(String8 *str) const
{
    char buf[256];
    for (size_t ct=0; ct < mInt32Defines.size(); ct++) {
        str->append("#define ");
        str->append(mInt32Defines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%i\n", (int)mInt32Defines.valueAt(ct));
        str->append(buf);

    }
    for (size_t ct=0; ct < mFloatDefines.size(); ct++) {
        str->append("#define ");
        str->append(mFloatDefines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%ff\n", mFloatDefines.valueAt(ct));
        str->append(buf);
    }
}

bool Context::objDestroyOOBInit()
{
    int status = pthread_mutex_init(&mObjDestroy.mMutex, NULL);
    if (status) {
        LOGE("Context::ObjDestroyOOBInit mutex init failure");
        return false;
    }
    return true;
}

void Context::objDestroyOOBRun()
{
    if (mObjDestroy.mNeedToEmpty) {
        int status = pthread_mutex_lock(&mObjDestroy.mMutex);
        if (status) {
            LOGE("Context::ObjDestroyOOBRun: error %i locking for OOBRun.", status);
            return;
        }

        for (size_t ct = 0; ct < mObjDestroy.mDestroyList.size(); ct++) {
            mObjDestroy.mDestroyList[ct]->decRef();
        }
        mObjDestroy.mDestroyList.clear();
        mObjDestroy.mNeedToEmpty = false;

        status = pthread_mutex_unlock(&mObjDestroy.mMutex);
        if (status) {
            LOGE("Context::ObjDestroyOOBRun: error %i unlocking for set condition.", status);
        }
    }
}

void Context::objDestroyOOBDestroy()
{
    rsAssert(!mObjDestroy.mNeedToEmpty);
    pthread_mutex_destroy(&mObjDestroy.mMutex);
}

void Context::objDestroyAdd(ObjectBase *obj)
{
    int status = pthread_mutex_lock(&mObjDestroy.mMutex);
    if (status) {
        LOGE("Context::ObjDestroyOOBRun: error %i locking for OOBRun.", status);
        return;
    }

    mObjDestroy.mNeedToEmpty = true;
    mObjDestroy.mDestroyList.add(obj);

    status = pthread_mutex_unlock(&mObjDestroy.mMutex);
    if (status) {
        LOGE("Context::ObjDestroyOOBRun: error %i unlocking for set condition.", status);
    }
}



///////////////////////////////////////////////////////////////////////////////////////////
//

namespace android {
namespace renderscript {


void rsi_ContextBindRootScript(Context *rsc, RsScript vs)
{
    Script *s = static_cast<Script *>(vs);
    rsc->setRootScript(s);
}

void rsi_ContextBindSampler(Context *rsc, uint32_t slot, RsSampler vs)
{
    Sampler *s = static_cast<Sampler *>(vs);

    if (slot > RS_MAX_SAMPLER_SLOT) {
        LOGE("Invalid sampler slot");
        return;
    }

    s->bindToContext(&rsc->mStateSampler, slot);
}

void rsi_ContextBindProgramFragmentStore(Context *rsc, RsProgramFragmentStore vpfs)
{
    ProgramFragmentStore *pfs = static_cast<ProgramFragmentStore *>(vpfs);
    rsc->setFragmentStore(pfs);
}

void rsi_ContextBindProgramFragment(Context *rsc, RsProgramFragment vpf)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    rsc->setFragment(pf);
}

void rsi_ContextBindProgramVertex(Context *rsc, RsProgramVertex vpv)
{
    ProgramVertex *pv = static_cast<ProgramVertex *>(vpv);
    rsc->setVertex(pv);
}

void rsi_AssignName(Context *rsc, void * obj, const char *name, uint32_t len)
{
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    rsc->assignName(ob, name, len);
}

void rsi_ObjDestroy(Context *rsc, void *obj)
{
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    rsc->removeName(ob);
    ob->decRef();
}

void rsi_ContextSetDefineF(Context *rsc, const char* name, float value)
{
    rsc->addInt32Define(name, value);
}

void rsi_ContextSetDefineI32(Context *rsc, const char* name, int32_t value)
{
    rsc->addFloatDefine(name, value);
}

}
}


RsContext rsContextCreate(RsDevice vdev, void *sur, uint32_t version, bool useDepth)
{
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = new Context(dev, (Surface *)sur, useDepth);
    return rsc;
}

void rsContextDestroy(RsContext vrsc)
{
    Context * rsc = static_cast<Context *>(vrsc);
    delete rsc;
}

void rsObjDestroyOOB(RsContext vrsc, void *obj)
{
    Context * rsc = static_cast<Context *>(vrsc);
    rsc->objDestroyAdd(static_cast<ObjectBase *>(obj));
}

