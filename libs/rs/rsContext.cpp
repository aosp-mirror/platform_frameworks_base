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
#include <ui/egl/android_natives.h>

#include <sys/types.h>
#include <sys/resource.h>

#include <cutils/properties.h>

#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cutils/sched_policy.h>

using namespace android;
using namespace android::renderscript;

pthread_key_t Context::gThreadTLSKey = 0;
uint32_t Context::gThreadTLSKeyCount = 0;
uint32_t Context::gGLContextCount = 0;
pthread_mutex_t Context::gInitMutex = PTHREAD_MUTEX_INITIALIZER;

static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE) {
    if (returnVal != EGL_TRUE) {
        fprintf(stderr, "%s() returned %d\n", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        fprintf(stderr, "after %s() eglError %s (0x%x)\n", op, EGLUtils::strerror(error),
                error);
    }
}

void Context::initEGL(bool useGL2)
{
    mEGL.mNumConfigs = -1;
    EGLint configAttribs[128];
    EGLint *configAttribsPtr = configAttribs;
    EGLint context_attribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE, GL_NONE, EGL_NONE };

#ifdef HAS_CONTEXT_PRIORITY
#ifdef EGL_IMG_context_priority
#warning "using EGL_IMG_context_priority"
    if (mThreadPriority > 0) {
        context_attribs2[2] = EGL_CONTEXT_PRIORITY_LEVEL_IMG;
        context_attribs2[3] = EGL_CONTEXT_PRIORITY_LOW_IMG;
    }
#endif
#endif

    memset(configAttribs, 0, sizeof(configAttribs));

    configAttribsPtr[0] = EGL_SURFACE_TYPE;
    configAttribsPtr[1] = EGL_WINDOW_BIT;
    configAttribsPtr += 2;

    if (useGL2) {
        configAttribsPtr[0] = EGL_RENDERABLE_TYPE;
        configAttribsPtr[1] = EGL_OPENGL_ES2_BIT;
        configAttribsPtr += 2;
    }

    if (mUseDepth) {
        configAttribsPtr[0] = EGL_DEPTH_SIZE;
        configAttribsPtr[1] = 16;
        configAttribsPtr += 2;
    }

    if (mDev->mForceSW) {
        configAttribsPtr[0] = EGL_CONFIG_CAVEAT;
        configAttribsPtr[1] = EGL_SLOW_CONFIG;
        configAttribsPtr += 2;
    }

    configAttribsPtr[0] = EGL_NONE;
    rsAssert(configAttribsPtr < (configAttribs + (sizeof(configAttribs) / sizeof(EGLint))));

    LOGV("initEGL start");
    mEGL.mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    checkEglError("eglGetDisplay");

    eglInitialize(mEGL.mDisplay, &mEGL.mMajorVersion, &mEGL.mMinorVersion);
    checkEglError("eglInitialize");

    status_t err = EGLUtils::selectConfigForNativeWindow(mEGL.mDisplay, configAttribs, mWndSurface, &mEGL.mConfig);
    if (err) {
       LOGE("couldn't find an EGLConfig matching the screen format\n");
    }
    //eglChooseConfig(mEGL.mDisplay, configAttribs, &mEGL.mConfig, 1, &mEGL.mNumConfigs);


    if (useGL2) {
        mEGL.mContext = eglCreateContext(mEGL.mDisplay, mEGL.mConfig, EGL_NO_CONTEXT, context_attribs2);
    } else {
        mEGL.mContext = eglCreateContext(mEGL.mDisplay, mEGL.mConfig, EGL_NO_CONTEXT, NULL);
    }
    checkEglError("eglCreateContext");
    if (mEGL.mContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext returned EGL_NO_CONTEXT");
    }
    gGLContextCount++;
}

void Context::deinitEGL()
{
    LOGV("deinitEGL");
    setSurface(0, 0, NULL);
    eglDestroyContext(mEGL.mDisplay, mEGL.mContext);
    checkEglError("eglDestroyContext");

    gGLContextCount--;
    if (!gGLContextCount) {
        eglTerminate(mEGL.mDisplay);
    }
}


uint32_t Context::runScript(Script *s, uint32_t launchID)
{
    ObjectBaseRef<ProgramFragment> frag(mFragment);
    ObjectBaseRef<ProgramVertex> vtx(mVertex);
    ObjectBaseRef<ProgramFragmentStore> store(mFragmentStore);
    ObjectBaseRef<ProgramRaster> raster(mRaster);

    uint32_t ret = s->run(this, launchID);

    mFragment.set(frag);
    mVertex.set(vtx);
    mFragmentStore.set(store);
    mRaster.set(raster);
    return ret;
}

void Context::checkError(const char *msg) const
{
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("GL Error, 0x%x, from %s", err, msg);
    }
}

uint32_t Context::runRootScript()
{
    timerSet(RS_TIMER_CLEAR_SWAP);
    rsAssert(mRootScript->mEnviroment.mIsRoot);

    eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_WIDTH, &mEGL.mWidth);
    eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_HEIGHT, &mEGL.mHeight);
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

    timerSet(RS_TIMER_SCRIPT);
    mStateFragmentStore.mLast.clear();
    uint32_t ret = runScript(mRootScript.get(), 0);

    checkError("runRootScript");
    if (mError != RS_ERROR_NONE) {
        // If we have an error condition we stop rendering until
        // somthing changes that might fix it.
        ret = 0;
    }
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
    mTimeFrame = mTimeLast;
    mTimeLastFrame = mTimeLast;
    mTimerActive = RS_TIMER_INTERNAL;
    timerReset();
}

void Context::timerFrame()
{
    mTimeLastFrame = mTimeFrame;
    mTimeFrame = getTime();
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
    uint64_t frame = mTimeFrame - mTimeLastFrame;
    mTimeMSLastFrame = frame / 1000000;
    mTimeMSLastScript = mTimers[RS_TIMER_SCRIPT] / 1000000;
    mTimeMSLastSwap = mTimers[RS_TIMER_CLEAR_SWAP] / 1000000;


    if (props.mLogTimes) {
        LOGV("RS: Frame (%i),   Script %2.1f (%i),  Clear & Swap %2.1f (%i),  Idle %2.1f (%lli),  Internal %2.1f (%lli)",
             mTimeMSLastFrame,
             100.0 * mTimers[RS_TIMER_SCRIPT] / total, mTimeMSLastScript,
             100.0 * mTimers[RS_TIMER_CLEAR_SWAP] / total, mTimeMSLastSwap,
             100.0 * mTimers[RS_TIMER_IDLE] / total, mTimers[RS_TIMER_IDLE] / 1000000,
             100.0 * mTimers[RS_TIMER_INTERNAL] / total, mTimers[RS_TIMER_INTERNAL] / 1000000);
    }
}

bool Context::setupCheck()
{
    if (checkVersion2_0()) {
        if (!mShaderCache.lookup(this, mVertex.get(), mFragment.get())) {
            LOGE("Context::setupCheck() 1 fail");
            return false;
        }

        mFragmentStore->setupGL2(this, &mStateFragmentStore);
        mFragment->setupGL2(this, &mStateFragment, &mShaderCache);
        mRaster->setupGL2(this, &mStateRaster);
        mVertex->setupGL2(this, &mStateVertex, &mShaderCache);

    } else {
        mFragmentStore->setupGL(this, &mStateFragmentStore);
        mFragment->setupGL(this, &mStateFragment);
        mRaster->setupGL(this, &mStateRaster);
        mVertex->setupGL(this, &mStateVertex);
    }
    return true;
}

static bool getProp(const char *str)
{
    char buf[PROPERTY_VALUE_MAX];
    property_get(str, buf, "0");
    return 0 != strcmp(buf, "0");
}

void * Context::threadProc(void *vrsc)
{
     Context *rsc = static_cast<Context *>(vrsc);
     rsc->mNativeThreadId = gettid();

     setpriority(PRIO_PROCESS, rsc->mNativeThreadId, ANDROID_PRIORITY_DISPLAY);
     rsc->mThreadPriority = ANDROID_PRIORITY_DISPLAY;

     rsc->props.mLogTimes = getProp("debug.rs.profile");
     rsc->props.mLogScripts = getProp("debug.rs.script");
     rsc->props.mLogObjects = getProp("debug.rs.object");
     rsc->props.mLogShaders = getProp("debug.rs.shader");

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

     if (rsc->mIsGraphicsContext) {
         rsc->mStateRaster.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
         rsc->setRaster(NULL);
         rsc->mStateVertex.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
         rsc->setVertex(NULL);
         rsc->mStateFragment.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
         rsc->setFragment(NULL);
         rsc->mStateFragmentStore.init(rsc, rsc->mEGL.mWidth, rsc->mEGL.mHeight);
         rsc->setFragmentStore(NULL);
         rsc->mStateVertexArray.init(rsc);
     }

     rsc->mRunning = true;
     bool mDraw = true;
     while (!rsc->mExit) {
         mDraw |= rsc->mIO.playCoreCommands(rsc, !mDraw);
         mDraw &= (rsc->mRootScript.get() != NULL);
         mDraw &= (rsc->mWndSurface != NULL);

         uint32_t targetTime = 0;
         if (mDraw && rsc->mIsGraphicsContext) {
             targetTime = rsc->runRootScript();
             mDraw = targetTime && !rsc->mPaused;
             rsc->timerSet(RS_TIMER_CLEAR_SWAP);
             eglSwapBuffers(rsc->mEGL.mDisplay, rsc->mEGL.mSurface);
             rsc->timerFrame();
             rsc->timerSet(RS_TIMER_INTERNAL);
             rsc->timerPrint();
             rsc->timerReset();
         }
         if (rsc->mObjDestroy.mNeedToEmpty) {
             rsc->objDestroyOOBRun();
         }
         if (rsc->mThreadPriority > 0 && targetTime) {
             int32_t t = (targetTime - (int32_t)(rsc->mTimeMSLastScript + rsc->mTimeMSLastSwap)) * 1000;
             if (t > 0) {
                 usleep(t);
             }
         }
     }

     LOGV("RS Thread exiting");
     if (rsc->mIsGraphicsContext) {
         rsc->mRaster.clear();
         rsc->mFragment.clear();
         rsc->mVertex.clear();
         rsc->mFragmentStore.clear();
         rsc->mRootScript.clear();
         rsc->mStateRaster.deinit(rsc);
         rsc->mStateVertex.deinit(rsc);
         rsc->mStateFragment.deinit(rsc);
         rsc->mStateFragmentStore.deinit(rsc);
     }
     ObjectBase::zeroAllUserRef(rsc);

     rsc->mObjDestroy.mNeedToEmpty = true;
     rsc->objDestroyOOBRun();

     if (rsc->mIsGraphicsContext) {
         pthread_mutex_lock(&gInitMutex);
         rsc->deinitEGL();
         pthread_mutex_unlock(&gInitMutex);
     }

     LOGV("RS Thread exited");
     return NULL;
}

void Context::setPriority(int32_t p)
{
    // Note: If we put this in the proper "background" policy
    // the wallpapers can become completly unresponsive at times.
    // This is probably not what we want for something the user is actively
    // looking at.
    mThreadPriority = p;
#if 0
    SchedPolicy pol = SP_FOREGROUND;
    if (p > 0) {
        pol = SP_BACKGROUND;
    }
    if (!set_sched_policy(mNativeThreadId, pol)) {
        // success; reset the priority as well
    }
#else
        setpriority(PRIO_PROCESS, mNativeThreadId, p);
#endif
}

Context::Context(Device *dev, bool isGraphics, bool useDepth)
{
    pthread_mutex_lock(&gInitMutex);

    dev->addContext(this);
    mDev = dev;
    mRunning = false;
    mExit = false;
    mUseDepth = useDepth;
    mPaused = false;
    mObjHead = NULL;
    mError = RS_ERROR_NONE;
    mErrorMsg = NULL;

    memset(&mEGL, 0, sizeof(mEGL));
    memset(&mGL, 0, sizeof(mGL));
    mIsGraphicsContext = isGraphics;

    int status;
    pthread_attr_t threadAttr;

    if (!gThreadTLSKeyCount) {
        status = pthread_key_create(&gThreadTLSKey, NULL);
        if (status) {
            LOGE("Failed to init thread tls key.");
            pthread_mutex_unlock(&gInitMutex);
            return;
        }
    }
    gThreadTLSKeyCount++;
    pthread_mutex_unlock(&gInitMutex);

    // Global init done at this point.

    status = pthread_attr_init(&threadAttr);
    if (status) {
        LOGE("Failed to init thread attribute.");
        return;
    }

    mWndSurface = NULL;

    objDestroyOOBInit();
    timerInit();
    timerSet(RS_TIMER_INTERNAL);

    LOGV("RS Launching thread");
    status = pthread_create(&mThreadId, &threadAttr, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
    }

    while(!mRunning) {
        usleep(100);
    }

    pthread_attr_destroy(&threadAttr);
}

Context::~Context()
{
    LOGV("Context::~Context");
    mExit = true;
    mPaused = false;
    void *res;

    mIO.shutdown();
    int status = pthread_join(mThreadId, &res);
    mObjDestroy.mNeedToEmpty = true;
    objDestroyOOBRun();

    // Global structure cleanup.
    pthread_mutex_lock(&gInitMutex);
    if (mDev) {
        mDev->removeContext(this);
        --gThreadTLSKeyCount;
        if (!gThreadTLSKeyCount) {
            pthread_key_delete(gThreadTLSKey);
        }
        mDev = NULL;
    }
    pthread_mutex_unlock(&gInitMutex);

    objDestroyOOBDestroy();
}

void Context::setSurface(uint32_t w, uint32_t h, ANativeWindow *sur)
{
    rsAssert(mIsGraphicsContext);

    EGLBoolean ret;
    if (mEGL.mSurface != NULL) {
        ret = eglMakeCurrent(mEGL.mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        checkEglError("eglMakeCurrent", ret);

        ret = eglDestroySurface(mEGL.mDisplay, mEGL.mSurface);
        checkEglError("eglDestroySurface", ret);

        mEGL.mSurface = NULL;
        mEGL.mWidth = 0;
        mEGL.mHeight = 0;
        mWidth = 0;
        mHeight = 0;
    }

    mWndSurface = sur;
    if (mWndSurface != NULL) {
        bool first = false;
        if (!mEGL.mContext) {
            first = true;
            pthread_mutex_lock(&gInitMutex);
            initEGL(true);
            pthread_mutex_unlock(&gInitMutex);
        }

        mEGL.mSurface = eglCreateWindowSurface(mEGL.mDisplay, mEGL.mConfig, mWndSurface, NULL);
        checkEglError("eglCreateWindowSurface");
        if (mEGL.mSurface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface returned EGL_NO_SURFACE");
        }

        ret = eglMakeCurrent(mEGL.mDisplay, mEGL.mSurface, mEGL.mSurface, mEGL.mContext);
        checkEglError("eglMakeCurrent", ret);

        eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_WIDTH, &mEGL.mWidth);
        eglQuerySurface(mEGL.mDisplay, mEGL.mSurface, EGL_HEIGHT, &mEGL.mHeight);
        mWidth = w;
        mHeight = h;
        mStateVertex.updateSize(this, w, h);

        if ((int)mWidth != mEGL.mWidth || (int)mHeight != mEGL.mHeight) {
            LOGE("EGL/Surface mismatch  EGL (%i x %i)  SF (%i x %i)", mEGL.mWidth, mEGL.mHeight, mWidth, mHeight);
        }

        if (first) {
            mGL.mVersion = glGetString(GL_VERSION);
            mGL.mVendor = glGetString(GL_VENDOR);
            mGL.mRenderer = glGetString(GL_RENDERER);
            mGL.mExtensions = glGetString(GL_EXTENSIONS);

            //LOGV("EGL Version %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
            LOGV("GL Version %s", mGL.mVersion);
            //LOGV("GL Vendor %s", mGL.mVendor);
            LOGV("GL Renderer %s", mGL.mRenderer);
            //LOGV("GL Extensions %s", mGL.mExtensions);

            const char *verptr = NULL;
            if (strlen((const char *)mGL.mVersion) > 9) {
                if (!memcmp(mGL.mVersion, "OpenGL ES-CM", 12)) {
                    verptr = (const char *)mGL.mVersion + 12;
                }
                if (!memcmp(mGL.mVersion, "OpenGL ES ", 10)) {
                    verptr = (const char *)mGL.mVersion + 9;
                }
            }

            if (!verptr) {
                LOGE("Error, OpenGL ES Lite not supported");
            } else {
                sscanf(verptr, " %i.%i", &mGL.mMajorVersion, &mGL.mMinorVersion);
            }

            glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &mGL.mMaxVertexAttribs);
            glGetIntegerv(GL_MAX_VERTEX_UNIFORM_VECTORS, &mGL.mMaxVertexUniformVectors);
            glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, &mGL.mMaxVertexTextureUnits);

            glGetIntegerv(GL_MAX_VARYING_VECTORS, &mGL.mMaxVaryingVectors);
            glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &mGL.mMaxTextureImageUnits);

            glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &mGL.mMaxFragmentTextureImageUnits);
            glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &mGL.mMaxFragmentUniformVectors);

            mGL.OES_texture_npot = NULL != strstr((const char *)mGL.mExtensions, "GL_OES_texture_npot");
            mGL.GL_IMG_texture_npot = NULL != strstr((const char *)mGL.mExtensions, "GL_IMG_texture_npot");
        }

    }
}

void Context::pause()
{
    rsAssert(mIsGraphicsContext);
    mPaused = true;
}

void Context::resume()
{
    rsAssert(mIsGraphicsContext);
    mPaused = false;
}

void Context::setRootScript(Script *s)
{
    rsAssert(mIsGraphicsContext);
    mRootScript.set(s);
}

void Context::setFragmentStore(ProgramFragmentStore *pfs)
{
    rsAssert(mIsGraphicsContext);
    if (pfs == NULL) {
        mFragmentStore.set(mStateFragmentStore.mDefault);
    } else {
        mFragmentStore.set(pfs);
    }
}

void Context::setFragment(ProgramFragment *pf)
{
    rsAssert(mIsGraphicsContext);
    if (pf == NULL) {
        mFragment.set(mStateFragment.mDefault);
    } else {
        mFragment.set(pf);
    }
}

void Context::setRaster(ProgramRaster *pr)
{
    rsAssert(mIsGraphicsContext);
    if (pr == NULL) {
        mRaster.set(mStateRaster.mDefault);
    } else {
        mRaster.set(pr);
    }
}

void Context::setVertex(ProgramVertex *pv)
{
    rsAssert(mIsGraphicsContext);
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
            mObjDestroy.mDestroyList[ct]->decUserRef();
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

uint32_t Context::getMessageToClient(void *data, size_t *receiveLen, size_t bufferLen, bool wait)
{
    //LOGE("getMessageToClient %i %i", bufferLen, wait);
    if (!wait) {
        if (mIO.mToClient.isEmpty()) {
            // No message to get and not going to wait for one.
            receiveLen = 0;
            return 0;
        }
    }

    //LOGE("getMessageToClient 2 con=%p", this);
    uint32_t bytesData = 0;
    uint32_t commandID = 0;
    const void *d = mIO.mToClient.get(&commandID, &bytesData);
    //LOGE("getMessageToClient 3    %i  %i", commandID, bytesData);

    *receiveLen = bytesData;
    if (bufferLen >= bytesData) {
        memcpy(data, d, bytesData);
        mIO.mToClient.next();
        return commandID;
    }
    return 0;
}

bool Context::sendMessageToClient(void *data, uint32_t cmdID, size_t len, bool waitForSpace)
{
    //LOGE("sendMessageToClient %i %i %i", cmdID, len, waitForSpace);
    if (cmdID == 0) {
        LOGE("Attempting to send invalid command 0 to client.");
        return false;
    }
    if (!waitForSpace) {
        if (mIO.mToClient.getFreeSpace() < len) {
            // Not enough room, and not waiting.
            return false;
        }
    }
    //LOGE("sendMessageToClient 2");
    void *p = mIO.mToClient.reserve(len);
    memcpy(p, data, len);
    mIO.mToClient.commit(cmdID, len);
    //LOGE("sendMessageToClient 3");
    return true;
}

void Context::initToClient()
{
    while(!mRunning) {
        usleep(100);
    }
}

void Context::deinitToClient()
{
    mIO.mToClient.shutdown();
}

const char * Context::getError(RsError *err)
{
    *err = mError;
    mError = RS_ERROR_NONE;
    if (*err != RS_ERROR_NONE) {
        return mErrorMsg;
    }
    return NULL;
}

void Context::setError(RsError e, const char *msg)
{
    mError = e;
    mErrorMsg = msg;
}


void Context::dumpDebug() const
{
    LOGE("RS Context debug %p", this);
    LOGE("RS Context debug");

    LOGE(" EGL ver %i %i", mEGL.mMajorVersion, mEGL.mMinorVersion);
    LOGE(" EGL context %p  surface %p,  w=%i h=%i  Display=%p", mEGL.mContext,
         mEGL.mSurface, mEGL.mWidth, mEGL.mHeight, mEGL.mDisplay);
    LOGE(" GL vendor: %s", mGL.mVendor);
    LOGE(" GL renderer: %s", mGL.mRenderer);
    LOGE(" GL Version: %s", mGL.mVersion);
    LOGE(" GL Extensions: %s", mGL.mExtensions);
    LOGE(" GL int Versions %i %i", mGL.mMajorVersion, mGL.mMinorVersion);
    LOGE(" RS width %i, height %i", mWidth, mHeight);
    LOGE(" RS running %i, exit %i, useDepth %i, paused %i", mRunning, mExit, mUseDepth, mPaused);
    LOGE(" RS pThreadID %li, nativeThreadID %i", mThreadId, mNativeThreadId);

    LOGV("MAX Textures %i, %i  %i", mGL.mMaxVertexTextureUnits, mGL.mMaxFragmentTextureImageUnits, mGL.mMaxTextureImageUnits);
    LOGV("MAX Attribs %i", mGL.mMaxVertexAttribs);
    LOGV("MAX Uniforms %i, %i", mGL.mMaxVertexUniformVectors, mGL.mMaxFragmentUniformVectors);
    LOGV("MAX Varyings %i", mGL.mMaxVaryingVectors);
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

void rsi_ContextBindProgramRaster(Context *rsc, RsProgramRaster vpr)
{
    ProgramRaster *pr = static_cast<ProgramRaster *>(vpr);
    rsc->setRaster(pr);
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
    ob->decUserRef();
}

void rsi_ContextPause(Context *rsc)
{
    rsc->pause();
}

void rsi_ContextResume(Context *rsc)
{
    rsc->resume();
}

void rsi_ContextSetSurface(Context *rsc, uint32_t w, uint32_t h, ANativeWindow *sur)
{
    rsc->setSurface(w, h, sur);
}

void rsi_ContextSetPriority(Context *rsc, int32_t p)
{
    rsc->setPriority(p);
}

void rsi_ContextDump(Context *rsc, int32_t bits)
{
    ObjectBase::dumpAll(rsc);
}

const char * rsi_ContextGetError(Context *rsc, RsError *e)
{
    const char *msg = rsc->getError(e);
    if (*e != RS_ERROR_NONE) {
        LOGE("RS Error %i %s", *e, msg);
    }
    return msg;
}

}
}


RsContext rsContextCreate(RsDevice vdev, uint32_t version)
{
    LOGV("rsContextCreate %p", vdev);
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = new Context(dev, false, false);
    return rsc;
}

RsContext rsContextCreateGL(RsDevice vdev, uint32_t version, bool useDepth)
{
    LOGV("rsContextCreateGL %p, %i", vdev, useDepth);
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = new Context(dev, true, useDepth);
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

uint32_t rsContextGetMessage(RsContext vrsc, void *data, size_t *receiveLen, size_t bufferLen, bool wait)
{
    Context * rsc = static_cast<Context *>(vrsc);
    return rsc->getMessageToClient(data, receiveLen, bufferLen, wait);
}

void rsContextInitToClient(RsContext vrsc)
{
    Context * rsc = static_cast<Context *>(vrsc);
    rsc->initToClient();
}

void rsContextDeinitToClient(RsContext vrsc)
{
    Context * rsc = static_cast<Context *>(vrsc);
    rsc->deinitToClient();
}

