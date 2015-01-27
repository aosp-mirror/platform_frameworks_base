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

#ifndef LOG_TAG
    #define LOG_TAG "OpenGLRenderer"
#endif


#include "AssetAtlas.h"
#include "Dither.h"
#include "Extensions.h"
#include "FboCache.h"
#include "GradientCache.h"
#include "LayerCache.h"
#include "PatchCache.h"
#include "ProgramCache.h"
#include "PathCache.h"
#include "RenderBufferCache.h"
#include "renderstate/PixelBufferState.h"
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
#include <utils/Singleton.h>
#include <utils/Vector.h>

#include <cutils/compiler.h>

#include <SkPath.h>

namespace android {
namespace uirenderer {

class GammaFontRenderer;

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

// GL ES 2.0 defines that at least 16 texture units must be supported
#define REQUIRED_TEXTURE_UNITS_COUNT 3

// Must define as many texture units as specified by REQUIRED_TEXTURE_UNITS_COUNT
static const GLenum gTextureUnits[] = {
    GL_TEXTURE0,
    GL_TEXTURE1,
    GL_TEXTURE2
};

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
        return sInstance != 0;
    }
private:
    Caches(RenderState& renderState);
    static Caches* sInstance;

public:
    enum FlushMode {
        kFlushMode_Layers = 0,
        kFlushMode_Moderate,
        kFlushMode_Full
    };

    /**
     * Initialize caches.
     */
    bool init();

    /**
     * Initialize global system properties.
     */
    bool initProperties();

    /**
     * Flush the cache.
     *
     * @param mode Indicates how much of the cache should be flushed
     */
    void flush(FlushMode mode);

    /**
     * Destroys all resources associated with this cache. This should
     * be called after a flush(kFlushMode_Full).
     */
    void terminate();

    /**
     * Indicates whether the renderer is in debug mode.
     * This debug mode provides limited information to app developers.
     */
    DebugLevel getDebugLevel() const {
        return mDebugLevel;
    }

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
     * Activate the specified texture unit. The texture unit must
     * be specified using an integer number (0 for GL_TEXTURE0 etc.)
     */
    void activeTexture(GLuint textureUnit);

    /**
     * Invalidate the cached value of the active texture unit.
     */
    void resetActiveTexture();

    /**
     * Binds the specified texture as a GL_TEXTURE_2D texture.
     * All texture bindings must be performed with this method or
     * bindTexture(GLenum, GLuint).
     */
    void bindTexture(GLuint texture);

    /**
     * Binds the specified texture with the specified render target.
     * All texture bindings must be performed with this method or
     * bindTexture(GLuint).
     */
    void bindTexture(GLenum target, GLuint texture);

    /**
     * Deletes the specified texture and clears it from the cache
     * of bound textures.
     * All textures must be deleted using this method.
     */
    void deleteTexture(GLuint texture);

    /**
     * Signals that the cache of bound textures should be cleared.
     * Other users of the context may have altered which textures are bound.
     */
    void resetBoundTextures();

    /**
     * Clear the cache of bound textures.
     */
    void unbindTexture(GLuint texture);

    void startTiling(GLuint x, GLuint y, GLuint width, GLuint height, bool discard);
    void endTiling();

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

    bool hasRegisteredFunctors();
    void registerFunctors(uint32_t functorCount);
    void unregisterFunctors(uint32_t functorCount);

    bool blend;
    GLenum lastSrcMode;
    GLenum lastDstMode;
    Program* currentProgram;

    bool drawDeferDisabled;
    bool drawReorderDisabled;

    // Misc
    GLint maxTextureSize;

    // Debugging
    bool debugLayersUpdates;
    bool debugOverdraw;

    enum StencilClipDebug {
        kStencilHide,
        kStencilShowHighlight,
        kStencilShowRegion
    };
    StencilClipDebug debugStencilClip;

    TextureCache textureCache;
    LayerCache layerCache;
    RenderBufferCache renderBufferCache;
    GradientCache gradientCache;
    ProgramCache programCache;
    PathCache pathCache;
    PatchCache patchCache;
    TessellationCache tessellationCache;
    TextDropShadowCache dropShadowCache;
    FboCache fboCache;

    GammaFontRenderer* fontRenderer;

    TaskManager tasks;

    Dither dither;

    bool gpuPixelBuffersEnabled;

    // Debug methods
    PFNGLINSERTEVENTMARKEREXTPROC eventMark;
    PFNGLPUSHGROUPMARKEREXTPROC startMark;
    PFNGLPOPGROUPMARKEREXTPROC endMark;

    PFNGLLABELOBJECTEXTPROC setLabel;
    PFNGLGETOBJECTLABELEXTPROC getLabel;

    // TEMPORARY properties
    void initTempProperties();
    void setTempProperty(const char* name, const char* value);

    float propertyLightDiameter;
    float propertyLightPosY;
    float propertyLightPosZ;
    float propertyAmbientRatio;
    int propertyAmbientShadowStrength;
    int propertySpotShadowStrength;

    PixelBufferState& pixelBuffer() { return *mPixelBufferState; }

private:
    enum OverdrawColorSet {
        kColorSet_Default = 0,
        kColorSet_Deuteranomaly
    };

    void initFont();
    void initExtensions();
    void initConstraints();
    void initStaticProperties();

    static void eventMarkNull(GLsizei length, const GLchar* marker) { }
    static void startMarkNull(GLsizei length, const GLchar* marker) { }
    static void endMarkNull() { }

    static void setLabelNull(GLenum type, uint object, GLsizei length,
            const char* label) { }
    static void getLabelNull(GLenum type, uint object, GLsizei bufferSize,
            GLsizei* length, char* label) {
        if (length) *length = 0;
        if (label) *label = '\0';
    }

    RenderState* mRenderState;

    std::unique_ptr<PixelBufferState> mPixelBufferState; // TODO: move to RenderState

    GLuint mTextureUnit;

    Extensions& mExtensions;

    // Used to render layers
    std::unique_ptr<TextureVertex[]> mRegionMesh;

    mutable Mutex mGarbageLock;
    Vector<Layer*> mLayerGarbage;

    DebugLevel mDebugLevel;
    bool mInitialized;

    uint32_t mFunctorsCount;

    // Caches texture bindings for the GL_TEXTURE_2D target
    GLuint mBoundTextures[REQUIRED_TEXTURE_UNITS_COUNT];

    OverdrawColorSet mOverdrawDebugColorSet;
}; // class Caches

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHES_H
