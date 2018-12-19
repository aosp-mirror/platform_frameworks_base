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
#include "Properties.h"

#include "tests/common/TestUtils.h"

#include <SkBitmap.h>
#include <SkImage.h>
#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_TEST(DeferredLayerUpdater, updateLayer) {
    sp<DeferredLayerUpdater> layerUpdater = TestUtils::createTextureLayerUpdater(renderThread);
    layerUpdater->setSize(100, 100);
    layerUpdater->setBlend(true);

    // updates are deferred so the backing layer should still be in its default state
    EXPECT_EQ(0u, layerUpdater->backingLayer()->getWidth());
    EXPECT_EQ(0u, layerUpdater->backingLayer()->getHeight());
    EXPECT_FALSE(layerUpdater->backingLayer()->getForceFilter());
    EXPECT_FALSE(layerUpdater->backingLayer()->isBlend());
    EXPECT_EQ(Matrix4::identity(), layerUpdater->backingLayer()->getTexTransform());

    // push the deferred updates to the layer
    SkMatrix scaledMatrix = SkMatrix::MakeScale(0.5, 0.5);
    SkBitmap bitmap;
    bitmap.allocN32Pixels(16, 16);
    sk_sp<SkImage> layerImage = SkImage::MakeFromBitmap(bitmap);
    layerUpdater->updateLayer(true, scaledMatrix, layerImage);

    // the backing layer should now have all the properties applied.
    EXPECT_EQ(100u, layerUpdater->backingLayer()->getWidth());
    EXPECT_EQ(100u, layerUpdater->backingLayer()->getHeight());
    EXPECT_TRUE(layerUpdater->backingLayer()->getForceFilter());
    EXPECT_TRUE(layerUpdater->backingLayer()->isBlend());
    EXPECT_EQ(scaledMatrix, layerUpdater->backingLayer()->getTexTransform());
}
