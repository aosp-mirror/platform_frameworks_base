/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "DeferredLayerUpdater.h"
#include "GlLayer.h"
#include "Properties.h"

#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_TEST(DeferredLayerUpdater, updateLayer) {
    sp<DeferredLayerUpdater> layerUpdater = TestUtils::createTextureLayerUpdater(renderThread);
    layerUpdater->setSize(100, 100);
    layerUpdater->setBlend(true);

    // updates are deferred so the backing layer should still be in its default state
    if (layerUpdater->backingLayer()->getApi() == Layer::Api::OpenGL) {
        GlLayer* glLayer = static_cast<GlLayer*>(layerUpdater->backingLayer());
        EXPECT_EQ((uint32_t)GL_NONE, glLayer->getRenderTarget());
    }
    EXPECT_EQ(0u, layerUpdater->backingLayer()->getWidth());
    EXPECT_EQ(0u, layerUpdater->backingLayer()->getHeight());
    EXPECT_FALSE(layerUpdater->backingLayer()->getForceFilter());
    EXPECT_FALSE(layerUpdater->backingLayer()->isBlend());
    EXPECT_EQ(Matrix4::identity(), layerUpdater->backingLayer()->getTexTransform());

    // push the deferred updates to the layer
    Matrix4 scaledMatrix;
    scaledMatrix.loadScale(0.5, 0.5, 0.0);
    layerUpdater->updateLayer(true, scaledMatrix.data, HAL_DATASPACE_UNKNOWN);
    if (layerUpdater->backingLayer()->getApi() == Layer::Api::OpenGL) {
        GlLayer* glLayer = static_cast<GlLayer*>(layerUpdater->backingLayer());
        glLayer->setRenderTarget(GL_TEXTURE_EXTERNAL_OES);
    }

    // the backing layer should now have all the properties applied.
    if (layerUpdater->backingLayer()->getApi() == Layer::Api::OpenGL) {
        GlLayer* glLayer = static_cast<GlLayer*>(layerUpdater->backingLayer());
        EXPECT_EQ((uint32_t)GL_TEXTURE_EXTERNAL_OES, glLayer->getRenderTarget());
    }
    EXPECT_EQ(100u, layerUpdater->backingLayer()->getWidth());
    EXPECT_EQ(100u, layerUpdater->backingLayer()->getHeight());
    EXPECT_TRUE(layerUpdater->backingLayer()->getForceFilter());
    EXPECT_TRUE(layerUpdater->backingLayer()->isBlend());
    EXPECT_EQ(scaledMatrix, layerUpdater->backingLayer()->getTexTransform());
}
