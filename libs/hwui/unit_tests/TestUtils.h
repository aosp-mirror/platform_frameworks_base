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
#ifndef TEST_UTILS_H
#define TEST_UTILS_H

#include <Matrix.h>
#include <Rect.h>
#include <RenderNode.h>
#include <renderstate/RenderState.h>
#include <renderthread/RenderThread.h>
#include <Snapshot.h>

#include <memory>

namespace android {
namespace uirenderer {

#define EXPECT_MATRIX_APPROX_EQ(a, b) \
    EXPECT_TRUE(TestUtils::matricesAreApproxEqual(a, b))

#define EXPECT_RECT_APPROX_EQ(a, b) \
    EXPECT_TRUE(MathUtils::areEqual(a.left, b.left) \
            && MathUtils::areEqual(a.top, b.top) \
            && MathUtils::areEqual(a.right, b.right) \
            && MathUtils::areEqual(a.bottom, b.bottom));

class TestUtils {
public:
    static bool matricesAreApproxEqual(const Matrix4& a, const Matrix4& b) {
        for (int i = 0; i < 16; i++) {
            if (!MathUtils::areEqual(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    static std::unique_ptr<Snapshot> makeSnapshot(const Matrix4& transform, const Rect& clip) {
        std::unique_ptr<Snapshot> snapshot(new Snapshot());
        snapshot->clip(clip.left, clip.top, clip.right, clip.bottom, SkRegion::kReplace_Op);
        *(snapshot->transform) = transform;
        return snapshot;
    }

    static SkBitmap createSkBitmap(int width, int height) {
        SkBitmap bitmap;
        SkImageInfo info = SkImageInfo::MakeUnknown(width, height);
        bitmap.setInfo(info);
        bitmap.allocPixels(info);
        return bitmap;
    }

    template<class CanvasType>
    static std::unique_ptr<DisplayList> createDisplayList(int width, int height,
            std::function<void(CanvasType& canvas)> canvasCallback) {
        CanvasType canvas(width, height);
        canvasCallback(canvas);
        return std::unique_ptr<DisplayList>(canvas.finishRecording());
    }

    template<class CanvasType>
    static sp<RenderNode> createNode(int left, int top, int right, int bottom,
            std::function<void(CanvasType& canvas)> canvasCallback) {
        sp<RenderNode> node = new RenderNode();
        node->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom);
        node->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        CanvasType canvas(
                node->stagingProperties().getWidth(), node->stagingProperties().getHeight());
        canvasCallback(canvas);
        node->setStagingDisplayList(canvas.finishRecording());
        return node;
    }

    static void syncNodePropertiesAndDisplayList(sp<RenderNode>& node) {
        node->syncProperties();
        node->syncDisplayList();
    }

    typedef std::function<void(RenderState& state, Caches& caches)> RtCallback;

    class TestTask : public renderthread::RenderTask {
    public:
        TestTask(RtCallback rtCallback)
                : rtCallback(rtCallback) {}
        virtual ~TestTask() {}
        virtual void run() override {
            // RenderState only valid once RenderThread is running, so queried here
            RenderState& renderState = renderthread::RenderThread::getInstance().renderState();

            renderState.onGLContextCreated();
            rtCallback(renderState, Caches::getInstance());
            renderState.onGLContextDestroyed();
        };
        RtCallback rtCallback;
    };

    /**
     * NOTE: requires surfaceflinger to run, otherwise this method will wait indefinitely.
     */
    static void runOnRenderThread(RtCallback rtCallback) {
        TestTask task(rtCallback);
        renderthread::RenderThread::getInstance().queueAndWait(&task);
    }
}; // class TestUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* TEST_UTILS_H */
