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

#include <SkImage.h>

namespace android {
namespace uirenderer {
/**
 * A layer has dimensions and is backed by a VkImage.
 */
class VkLayer : public Layer {
public:
    VkLayer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight,
            sk_sp<SkColorFilter> colorFilter, int alpha, SkBlendMode mode, bool blend)
            : Layer(renderState, Api::Vulkan, colorFilter, alpha, mode)
            , mWidth(layerWidth)
            , mHeight(layerHeight)
            , mBlend(blend) {}

    virtual ~VkLayer() {}

    uint32_t getWidth() const override { return mWidth; }

    uint32_t getHeight() const override { return mHeight; }

    void setSize(uint32_t width, uint32_t height) override {
        mWidth = width;
        mHeight = height;
    }

    void setBlend(bool blend) override { mBlend = blend; }

    bool isBlend() const override { return mBlend; }

    sk_sp<SkImage> getImage() { return mImage; }

    void updateTexture();

    // If we've destroyed the vulkan context (VkInstance, VkDevice, etc.), we must make sure to
    // destroy any VkImages that were made with that context.
    void onVkContextDestroyed();

private:
    int mWidth;
    int mHeight;
    bool mBlend;

    sk_sp<SkImage> mImage;

};  // struct VkLayer

};  // namespace uirenderer
};  // namespace android
