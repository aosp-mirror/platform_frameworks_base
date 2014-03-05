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

#ifndef CANVASCONTEXT_H_
#define CANVASCONTEXT_H_

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkBitmap.h>
#include <utils/Functor.h>
#include <utils/Vector.h>

#include "RenderTask.h"

#define FUNCTOR_PROCESS_DELAY 4

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class DisplayList;
class DisplayListData;
class OpenGLRenderer;
class Rect;

namespace renderthread {

class GlobalContext;
class CanvasContext;
class RenderThread;

class InvokeFunctorsTask : public RenderTask {
public:
    InvokeFunctorsTask(CanvasContext* context)
        : mContext(context) {}

    virtual void run();

private:
    CanvasContext* mContext;
};

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
class CanvasContext {
public:
    CanvasContext(bool translucent);
    ~CanvasContext();

    bool initialize(EGLNativeWindowType window);
    void updateSurface(EGLNativeWindowType window);
    void setup(int width, int height);
    void swapDisplayListData(DisplayList* displayList, DisplayListData* newData);
    void processLayerUpdates(const Vector<DeferredLayerUpdater*>* layerUpdaters);
    void drawDisplayList(DisplayList* displayList, Rect* dirty);
    void destroyCanvas();

    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);

    void attachFunctor(Functor* functor);
    void detachFunctor(Functor* functor);

    void runWithGlContext(RenderTask* task);

private:
    void setSurface(EGLNativeWindowType window);
    void swapBuffers();
    void makeCurrent();

    friend class InvokeFunctorsTask;
    void invokeFunctors();
    void handleFunctorStatus(int status, const Rect& redrawClip);
    void removeFunctorsTask();
    void queueFunctorsTask(int delayMs = FUNCTOR_PROCESS_DELAY);

    void requireGlContext();

    GlobalContext* mGlobalContext;
    RenderThread& mRenderThread;
    EGLSurface mEglSurface;
    bool mDirtyRegionsEnabled;

    bool mOpaque;
    OpenGLRenderer* mCanvas;
    bool mHaveNewSurface;

    bool mInvokeFunctorsPending;
    InvokeFunctorsTask mInvokeFunctorsTask;

};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* CANVASCONTEXT_H_ */
