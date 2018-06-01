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

#pragma once

#include "DeviceInfo.h"
#include "Extensions.h"
#include "ResourceCache.h"
#include "renderstate/PixelBufferState.h"
#include "renderstate/TextureState.h"
#include "thread/TaskManager.h"
#include "thread/TaskProcessor.h"

#include <memory>
#include <vector>

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

    static bool hasInstance() { return sInstance != nullptr; }

private:
    explicit Caches(RenderState& renderState);
    static Caches* sInstance;

public:
    enum class FlushMode { Layers = 0, Moderate, Full };

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
     * Call this on each frame to ensure that garbage is deleted from
     * GPU memory.
     */
    void clearGarbage();

    /**
     * Returns the GL RGBA internal format to use for the current device
     * If the device supports linear blending and needSRGB is true,
     * this function returns GL_SRGB8_ALPHA8, otherwise it returns GL_RGBA
     */
    constexpr GLint rgbaInternalFormat(bool needSRGB = true) const {
        return extensions().hasLinearBlending() && needSRGB ? GL_SRGB8_ALPHA8 : GL_RGBA;
    }

public:
    TaskManager tasks;

    bool gpuPixelBuffersEnabled;

    const Extensions& extensions() const { return DeviceInfo::get()->extensions(); }
    PixelBufferState& pixelBufferState() { return *mPixelBufferState; }
    TextureState& textureState() { return *mTextureState; }

private:
    void initStaticProperties();

    static void eventMarkNull(GLsizei length, const GLchar* marker) {}
    static void startMarkNull(GLsizei length, const GLchar* marker) {}
    static void endMarkNull() {}

    // Used to render layers
    std::unique_ptr<TextureVertex[]> mRegionMesh;

    bool mInitialized;

    // TODO: move below to RenderState
    PixelBufferState* mPixelBufferState = nullptr;
    TextureState* mTextureState = nullptr;

};  // class Caches

};  // namespace uirenderer
};  // namespace android
