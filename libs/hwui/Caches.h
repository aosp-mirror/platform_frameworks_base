/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_CACHES_H
#define ANDROID_UI_CACHES_H

#define LOG_TAG "OpenGLRenderer"

#include <utils/Singleton.h>

#include "TextureCache.h"
#include "LayerCache.h"
#include "GradientCache.h"
#include "PatchCache.h"
#include "FontRenderer.h"
#include "ProgramCache.h"
#include "PathCache.h"
#include "TextDropShadowCache.h"

namespace android {
namespace uirenderer {

struct CacheLogger {
    CacheLogger() {
        LOGD("Creating caches");
    }
}; // struct CacheLogger

class Caches: public Singleton<Caches> {
    Caches(): Singleton<Caches>(), blend(false), lastSrcMode(GL_ZERO),
            lastDstMode(GL_ZERO), currentProgram(NULL) {
        dropShadowCache.setFontRenderer(fontRenderer);
    }

    friend class Singleton<Caches>;

    CacheLogger logger;

public:
    bool blend;
    GLenum lastSrcMode;
    GLenum lastDstMode;
    Program* currentProgram;

    TextureCache textureCache;
    LayerCache layerCache;
    GradientCache gradientCache;
    ProgramCache programCache;
    PathCache pathCache;
    PatchCache patchCache;
    TextDropShadowCache dropShadowCache;
    FontRenderer fontRenderer;
}; // class Caches

}; // namespace uirenderer

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Caches);
#endif

}; // namespace android

#endif // ANDROID_UI_CACHES_H
