/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Layer.h"

#include "Texture.h"

namespace android {
namespace uirenderer {

// Forward declarations
class Caches;

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
class GlLayer : public Layer {
public:
    GlLayer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight,
            sk_sp<SkColorFilter> colorFilter, int alpha, SkBlendMode mode, bool blend);
    virtual ~GlLayer();

    uint32_t getWidth() const override { return texture.mWidth; }

    uint32_t getHeight() const override { return texture.mHeight; }

    void setSize(uint32_t width, uint32_t height) override {
        texture.updateLayout(width, height, texture.internalFormat(), texture.format(),
                             texture.target());
    }

    void setBlend(bool blend) override { texture.blend = blend; }

    bool isBlend() const override { return texture.blend; }

    inline GLuint getTextureId() const { return texture.id(); }

    inline Texture& getTexture() { return texture; }

    inline GLenum getRenderTarget() const { return texture.target(); }

    inline bool isRenderable() const { return texture.target() != GL_NONE; }

    void setRenderTarget(GLenum renderTarget);

    void generateTexture();

    /**
     * Lost the GL context but the layer is still around, mark it invalid internally
     * so the dtor knows not to do any GL work
     */
    void onGlContextLost();

private:
    Caches& caches;

    /**
     * The texture backing this layer.
     */
    Texture texture;
};  // struct GlLayer

};  // namespace uirenderer
};  // namespace android
