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
#ifndef RENDERSTATE_H
#define RENDERSTATE_H

#include <set>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <private/hwui/DrawGlInfo.h>

#include "Caches.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

namespace renderthread {
class RenderThread;
}

// TODO: Replace Cache's GL state tracking with this. For now it's more a thin
// wrapper of Caches for users to migrate to.
class RenderState {
    PREVENT_COPY_AND_ASSIGN(RenderState);
public:
    void onGLContextCreated();
    void onGLContextDestroyed();

    void setViewport(GLsizei width, GLsizei height);
    void getViewport(GLsizei* outWidth, GLsizei* outHeight);

    void bindFramebuffer(GLuint fbo);
    GLint getFramebuffer() { return mFramebuffer; }

    void invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info);

    void debugOverdraw(bool enable, bool clear);

    void registerLayer(const Layer* layer) {
        mActiveLayers.insert(layer);
    }
    void unregisterLayer(const Layer* layer) {
        mActiveLayers.erase(layer);
    }

private:
    friend class renderthread::RenderThread;

    void interruptForFunctorInvoke();
    void resumeFromFunctorInvoke();

    RenderState();
    ~RenderState();

    Caches* mCaches;
    std::set<const Layer*> mActiveLayers;

    GLsizei mViewportWidth;
    GLsizei mViewportHeight;
    GLuint mFramebuffer;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERSTATE_H */
