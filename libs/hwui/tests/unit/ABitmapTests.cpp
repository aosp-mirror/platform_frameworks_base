/*
 * Copyright 2020 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "android/graphics/bitmap.h"
#include "apex/TypeCast.h"
#include "hwui/Bitmap.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

TEST(ABitmap, notifyPixelsChanged) {
    // generate a bitmap and its public API handle
    sk_sp<Bitmap> bitmap(TestUtils::createBitmap(1, 1));
    ABitmap* abmp = android::TypeCast::toABitmap(bitmap.get());

    // verify that notification changes the genID
    uint32_t genID = bitmap->getGenerationID();
    ABitmap_notifyPixelsChanged(abmp);
    ASSERT_TRUE(bitmap->getGenerationID() != genID);

    // mark the bitmap as immutable
    ASSERT_FALSE(bitmap->isImmutable());
    bitmap->setImmutable();
    ASSERT_TRUE(bitmap->isImmutable());

    // attempt to notify that the pixels have changed
    genID = bitmap->getGenerationID();
    ABitmap_notifyPixelsChanged(abmp);
    ASSERT_TRUE(bitmap->getGenerationID() == genID);
}
