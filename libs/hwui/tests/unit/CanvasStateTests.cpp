/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "CanvasState.h"

#include "Matrix.h"
#include "Rect.h"
#include "hwui/Canvas.h"
#include "utils/LinearAllocator.h"

#include <gtest/gtest.h>
#include <SkPath.h>
#include <SkRegion.h>

namespace android {
namespace uirenderer {

class NullClient: public CanvasStateClient {
    void onViewportInitialized() override {}
    void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}
    GLuint getTargetFbo() const override { return 0; }
};

static NullClient sNullClient;

static bool approxEqual(const Matrix4& a, const Matrix4& b) {
    for (int i = 0; i < 16; i++) {
        if (!MathUtils::areEqual(a[i], b[i])) {
            return false;
        }
    }
    return true;
}

TEST(CanvasState, gettersAndSetters) {
    CanvasState state(sNullClient);
    state.initializeSaveStack(200, 200,
            0, 0, 200, 200, Vector3());

    ASSERT_EQ(state.getWidth(), 200);
    ASSERT_EQ(state.getHeight(), 200);

    Matrix4 simpleTranslate;
    simpleTranslate.loadTranslate(10, 20, 0);
    state.setMatrix(simpleTranslate);

    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(200, 200));
    ASSERT_EQ(state.getLocalClipBounds(), Rect(-10, -20, 190, 180));
    EXPECT_TRUE(approxEqual(*state.currentTransform(), simpleTranslate));
    EXPECT_TRUE(state.clipIsSimple());
}

TEST(CanvasState, simpleClipping) {
    CanvasState state(sNullClient);
    state.initializeSaveStack(200, 200,
            0, 0, 200, 200, Vector3());

    state.clipRect(0, 0, 100, 100, SkRegion::kIntersect_Op);
    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(100, 100));

    state.clipRect(10, 10, 200, 200, SkRegion::kIntersect_Op);
    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(10, 10, 100, 100));

    state.clipRect(50, 50, 150, 150, SkRegion::kReplace_Op);
    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(50, 50, 150, 150));
}

TEST(CanvasState, complexClipping) {
    CanvasState state(sNullClient);
    state.initializeSaveStack(200, 200,
            0, 0, 200, 200, Vector3());

    state.save(SaveFlags::MatrixClip);
    {
        // rotated clip causes complex clip
        state.rotate(10);
        EXPECT_TRUE(state.clipIsSimple());
        state.clipRect(0, 0, 200, 200, SkRegion::kIntersect_Op);
        EXPECT_FALSE(state.clipIsSimple());
    }
    state.restore();

    state.save(SaveFlags::MatrixClip);
    {
        // subtracted clip causes complex clip
        EXPECT_TRUE(state.clipIsSimple());
        state.clipRect(50, 50, 150, 150, SkRegion::kDifference_Op);
        EXPECT_FALSE(state.clipIsSimple());
    }
    state.restore();

    state.save(SaveFlags::MatrixClip);
    {
        // complex path causes complex clip
        SkPath path;
        path.addOval(SkRect::MakeWH(200, 200));
        EXPECT_TRUE(state.clipIsSimple());
        state.clipPath(&path, SkRegion::kDifference_Op);
        EXPECT_FALSE(state.clipIsSimple());
    }
    state.restore();
}

TEST(CanvasState, saveAndRestore) {
    CanvasState state(sNullClient);
    state.initializeSaveStack(200, 200,
            0, 0, 200, 200, Vector3());

    state.save(SaveFlags::Clip);
    {
        state.clipRect(0, 0, 10, 10, SkRegion::kIntersect_Op);
        ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(10, 10));
    }
    state.restore();
    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(200, 200)); // verify restore

    Matrix4 simpleTranslate;
    simpleTranslate.loadTranslate(10, 10, 0);
    state.save(SaveFlags::Matrix);
    {
        state.translate(10, 10, 0);
        EXPECT_TRUE(approxEqual(*state.currentTransform(), simpleTranslate));
    }
    state.restore();
    EXPECT_FALSE(approxEqual(*state.currentTransform(), simpleTranslate));
}

TEST(CanvasState, saveAndRestoreButNotTooMuch) {
    CanvasState state(sNullClient);
    state.initializeSaveStack(200, 200,
            0, 0, 200, 200, Vector3());

    state.save(SaveFlags::Matrix); // NOTE: clip not saved
    {
        state.clipRect(0, 0, 10, 10, SkRegion::kIntersect_Op);
        ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(10, 10));
    }
    state.restore();
    ASSERT_EQ(state.getRenderTargetClipBounds(), Rect(10, 10)); // verify not restored

    Matrix4 simpleTranslate;
    simpleTranslate.loadTranslate(10, 10, 0);
    state.save(SaveFlags::Clip); // NOTE: matrix not saved
    {
        state.translate(10, 10, 0);
        EXPECT_TRUE(approxEqual(*state.currentTransform(), simpleTranslate));
    }
    state.restore();
    EXPECT_TRUE(approxEqual(*state.currentTransform(), simpleTranslate)); // verify not restored
}

}
}
