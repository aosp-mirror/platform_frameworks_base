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

#include <gtest/gtest.h>

#include "hwui/Bitmap.h"

#include <SkBitmap.h>
#include <SkColorTable.h>
#include <SkImageInfo.h>

#include <tests/common/TestUtils.h>

using namespace android;
using namespace android::uirenderer;

TEST(Bitmap, colorTableRefCounting) {
    const SkPMColor c[] = { SkPackARGB32(0x80, 0x80, 0, 0) };
    SkColorTable* ctable = new SkColorTable(c, SK_ARRAY_COUNT(c));

    SkBitmap* bm = new SkBitmap();
    bm->allocPixels(SkImageInfo::Make(1, 1, kIndex_8_SkColorType, kPremul_SkAlphaType),
            nullptr, ctable);
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(bm, ctable);
    EXPECT_FALSE(ctable->unique());
    delete bm;
    bitmap.reset();
    EXPECT_TRUE(ctable->unique());
    ctable->unref();
}

