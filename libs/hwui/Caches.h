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

#include <utils/Singleton.h>

#include <cutils/compiler.h>

#include "thread/TaskProcessor.h"
#include "thread/TaskManager.h"

#include "FontRenderer.h"
#include "GammaFontRenderer.h"
#include "TextureCache.h"
#include "LayerCache.h"
#include "RenderBufferCache.h"
#include "GradientCache.h"
#include "PatchCache.h"
#include "ProgramCache.h"
#include "PathCache.h"
#include "TextDropShadowCache.h"
#include "FboCache.h"
#include "ResourceCache.h"
#include "Stencil.h"
#include "Dither.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Globals
///////////////////////////////////////////////////////////////////////////////

#define REQUIRED_TEXTURE_UNITS_COUNT 3

#define REGION_MESH_QUAD_COUNT 512

// Generates simple and textured vertices
#define FV(x, y, u, v) { { x, y }, { u, v } }

// This array is never used directly but used as a memcpy source in the
// OpenGLRenderer constructor
static const TextureVertex gMeshVertices[] = {
        FV(0.0f, 0.0f, 0.0f, 0.0f),
        FV(1.0f, 0.0f, 1.0f, 0.0f),
        FV(0.0f, 1.0f, 0.0f, 1.0f),
        FV(1.0f, 1.0f, 1.0f, 1.0f)
};
static const GLsizei gMeshStride = sizeof(TextureVertex);
static const GLsizei gVertexStride = sizeof(Vertex);
static const GLsizei gAlphaVertexStride = sizeof(AlphaVertex);
static const GLsizei gMeshTextureOffset = 2 * sizeof(float);
static const GLsizei gVertexAlphaOffset = 2 * sizeof(float);
static const GLsizei gVertexAAWidthOffset = 2 * sizeof(float);
static const GLsizei gVertexAALengthOffset = 3 * sizeof(float);
static const GLsizei gMeshCount = 4;

static const GLenum gTextureUnits[] = {
    GL_TEXTURE0,
    GL_TEXTURE1,
    GL_TEXTURE2
};

///////////////////////////////////////////////////////////////////////////////
// Debug
///////////////////////////////////////////////////////////////////////////////

struct CacheLogger {
    CacheLogger() {
        INIT_LOGD("Creating OpenGL renderer caches");
    }
}; // struct CacheLogger

///////////////////////////////////////////////////////////////////////////////
// Caches
///////////////////////////////////////////////////////////////////////////////

class DisplayList;

class ANDROID_API Caches: public Singleton<Caches> {
    Caches();

    friend class Singleton<Caches>;

    CacheLogger mLogger;

public:
    enum FlushMode {
        kFlushMode_Layers = 0,
        kFlushMode_Moderate,
        kFlushMode_Full
    };

    /**
     * Initialize caches.
     */
    void init();

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
     * Call this on each frame to ensure that garbage is deleted from
     * GPU memory.
     */
    void clearGarbage();

    /**
     * Can be used to delete a layer from a non EGL thread.
     */
    void deleteLayerDeferred(Layer* layer);

    /*
     * Can be used to delete a display list from a non EGL thread.
     */
    void deleteDisplayListDeferred(DisplayList* layer);

    /**
     * Binds the VBO used to render simple textured quads.
     */
    bool bindMeshBuffer();

    /**
     * Binds the specified VBO if needed.
     */
    bool bindMeshBuffer(const GLuint buffer);

    /**
     * Unbinds the VBO used to render simple textured quads.
     */
    bool unbindMeshBuffer();

    bool bindIndicesBuffer(const GLuint buffer);
    bool unbindIndicesBuffer();

    /**
     * Binds the specified buffer as the current GL unpack pixel buffer.
     */
    bool bindPixelBuffer(const GLuint buffer);

    /**
     * Resets the current unpack pixel buffer to 0 (default value.)
     */
    bool unbindPixelBuffer();

    /**
     * Binds an attrib to the specified float vertex pointer.
     * Assumes a stride of gMeshStride and a size of 2.
     */
    void bindPositionVertexPointer(bool force, GLvoid* vertices, GLsizei stride = gMeshStride);

    /**
     * Binds an attrib to the specified float vertex pointer.
     * Assumes a stride of gMeshStride and a size of 2.
     */
    void bindTexCoordsVertexPointer(bool force, GLvoid* vertices, GLsizei stride = gMeshStride);

    /**
     * Resets the vertex pointers.
     */
    void resetVertexPointers();
    void resetTexCoordsVertexPointer();

    void enableTexCoordsVertexArray();
    void disableTexCoordsVertexArray();

    /**
     * Activate the specified texture unit. The texture unit must
     * be specified using an integer number (0 for GL_TEXTURE0 etc.)
     */
    void activeTexture(GLuint textureUnit);

    /**
     * Sets the scissor for the current surface.
     */
    bool setScissor(GLint x, GLint y, GLint width, GLint height);

    /**
     * Resets the scissor state.
     */
    void resetScissor();

    bool enableScissor();
    bool disableScissor();
    void setScissorEnabled(bool enabled);

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
    bool scissorEnabled;

    bool drawDeferDisabled;
    bool drawReorderDisabled;

    // VBO to draw with
    GLuint meshBuffer;

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
    TextDropShadowCache dropShadowCache;
    FboCache fboCache;
    ResourceCache resourceCache;

    GammaFontRenderer* fontRenderer;

    TaskManager tasks;

    Dither dither;
    Stencil stencil;

    // Debug methods
    PFNGLINSERTEVENTMARKEREXTPROC eventMark;
    PFNGLPUSHGROUPMARKEREXTPROC startMark;
    PFNGLPOPGROUPMARKEREXTPROC endMark;

    PFNGLLABELOBJECTEXTPROC setLabel;
    PFNGLGETOBJECTLABELEXTPROC getLabel;

private:
    void initFont();
    void initExtensions();
    void initConstraints();

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

    GLuint mCurrentBuffer;
    GLuint mCurrentIndicesBuffer;
    GLuint mCurrentPixelBuffer;
    void* mCurrentPositionPointer;
    GLsizei mCurrentPositionStride;
    void* mCurrentTexCoordsPointer;
    GLsizei mCurrentTexCoordsStride;

    bool mTexCoordsArrayEnabled;

    GLuint mTextureUnit;

    GLint mScissorX;
    GLint mScissorY;
    GLint mScissorWidth;
    GLint mScissorHeight;

    Extensions& mExtensions;

    // Used to render layers
    TextureVertex* mRegionMesh;
    GLuint mRegionMeshIndices;

    mutable Mutex mGarbageLock;
    Vector<Layer*> mLayerGarbage;
    Vector<DisplayList*> mDisplayListGarbage;

    DebugLevel mDebugLevel;
    bool mInitialized;

    uint32_t mFunctorsCount;
}; // class Caches

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CACHES_H
