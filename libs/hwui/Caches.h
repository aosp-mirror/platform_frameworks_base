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

#ifndef ANDROID_HWUI_CACHES_H
#define ANDROID_HWUI_CACHES_H

#include "AssetAtlas.h"
#include "Dither.h"
#include "Extensions.h"
#include "FboCache.h"
#include "GammaFontRenderer.h"
#include "GradientCache.h"
#include "LayerCache.h"
#include "PatchCache.h"
#include "ProgramCache.h"
#include "PathCache.h"
#include "RenderBufferCache.h"
#include "renderstate/PixelBufferState.h"
#include "renderstate/TextureState.h"
#include "ResourceCache.h"
#include "TessellationCache.h"
#include "TextDropShadowCache.h"
#include "TextureCache.h"
#include "thread/TaskProcessor.h"
#include "thread/TaskManager.h"

#include <vector>
#include <memory>

#include <GLES3/gl3.h>

#include <utils/KeyedVector.h>

#include <cutils/compiler.h>

#include <SkPath.h>

#include <vector>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Caches
///////////////////////////////////////////////////////////////////////////////

class RenderNode;
class RenderState;

class ANDROID_API Caches {
public:
    static Caches& createInstance(RenderState& renderState) {
        LOG_ALWAYS_FATAL_IF(sInstance, "double create of Caches attempted");
        sInstance = new Caches(renderState);
        return *sInstance;
    }

    static Caches& getInstance() {
        LOG_ALWAYS_FATAL_IF(!sInstance, "instance not yet created");
        return *sInstance;
    }

    static bool hasInstance() {
        return sInstance != nullptr;
    }
private:
    Caches(RenderState& renderState);
    static Caches* sInstance;

public:
    enum class FlushMode {
        Layers = 0,
        Moderate,
        Full
    };

    /**
     * Initialize caches.
     */
    bool init();

    bool isInitialized() { return mInitialized; }

    /**
     * Flush the cache.
     *
     * @param mode Indicates how much of the cache should be flushed
     */
    void flush(FlushMode mode);

    /**
     * Destroys all resources associated with this cache. This should
     * be called after a flush(FlushMode::Full).
     */
    void terminate();

    /**
     * Returns a non-premultiplied ARGB color for the specified
     * amount of overdraw (1 for 1x, 2 for 2x, etc.)
     */
    uint32_t getOverdrawColor(uint32_t amount) const;

    /**
     * Call this on each frame to ensure that garbage is deleted from
     * GPU memory.
     */
    void clearGarbage();

    /**
     * Can be used to delete a layer from a non EGL thread.
     */
    void deleteLayerDeferred(Layer* layer);

    /**
     * Returns the mesh used to draw regions. Calling this method will
     * bind a VBO of type GL_ELEMENT_ARRAY_BUFFER that contains the
     * indices for the region mesh.
     */
    TextureVertex* getRegionMesh();

    /**
     * Displays the memory usage of each cache and the total sum.
     */
    void dumpMemoryUsage();
    void dumpMemoryUsage(String8& log);

    // Misc
    GLint maxTextureSize;

private:
    // Declared before gradientCache and programCache which need this to initialize.
    // TODO: cleanup / move elsewhere
    Extensions mExtensions;
public:
    TextureCache textureCache;
    LayerCache layerCache;
    RenderBufferCache renderBufferCache;
    GradientCache gradientCache;
    PatchCache patchCache;
    PathCache pathCache;
    ProgramCache programCache;
    TessellationCache tessellationCache;
    TextDropShadowCache dropShadowCache;
    FboCache fboCache;

    GammaFontRenderer fontRenderer;

    TaskManager tasks;

    Dither dither;

    bool gpuPixelBuffersEnabled;

    // Debug methods
    PFNGLINSERTEVENTMARKEREXTPROC eventMark;
    PFNGLPUSHGROUPMARKEREXTPROC startMark;
    PFNGLPOPGROUPMARKEREXTPROC endMark;

    void setProgram(const ProgramDescription& description);
    void setProgram(Program* program);

    Extensions& extensions() { return mExtensions; }
    Program& program() { return *mProgram; }
    PixelBufferState& pixelBufferState() { return *mPixelBufferState; }
    TextureState& textureState() { return *mTextureState; }

private:
    void initExtensions();
    void initConstraints();
    void initStaticProperties();

    static void eventMarkNull(GLsizei length, const GLchar* marker) { }
    static void startMarkNull(GLsizei length, const GLchar* marker) { }
    static void endMarkNull() { }

    RenderState* mRenderState;

    // Used to render layers
    std::unique_ptr<TextureVertex[]> mRegionMesh;

    mutable Mutex mGarbageLock;
    std::vector<Layer*> mLayerGarbage;

    bool mInitialized;

    // TODO: move below to RenderState
    PixelBufferState* mPixelBufferState = nullptr;
    TextureState* mTextureState = nullptr;
    Program* mProgram = nullptr; // note: object owned by ProgramCache

}; // class Caches

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHES_H
