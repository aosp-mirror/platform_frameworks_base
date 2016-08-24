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

#include <DeviceInfo.h>
#include <DisplayList.h>
#include <Matrix.h>
#include <Rect.h>
#include <RenderNode.h>
#include <renderstate/RenderState.h>
#include <renderthread/RenderThread.h>
#include <Snapshot.h>

#if HWUI_NEW_OPS
#include <RecordedOp.h>
#include <RecordingCanvas.h>
#else
#include <DisplayListOp.h>
#include <DisplayListCanvas.h>
#endif

#include <memory>

namespace android {
namespace uirenderer {

#if HWUI_NEW_OPS
typedef RecordingCanvas TestCanvas;
#else
typedef DisplayListCanvas TestCanvas;
#endif

#define EXPECT_MATRIX_APPROX_EQ(a, b) \
    EXPECT_TRUE(TestUtils::matricesAreApproxEqual(a, b))

#define EXPECT_RECT_APPROX_EQ(a, b) \
    EXPECT_TRUE(MathUtils::areEqual(a.left, b.left) \
            && MathUtils::areEqual(a.top, b.top) \
            && MathUtils::areEqual(a.right, b.right) \
            && MathUtils::areEqual(a.bottom, b.bottom));

#define EXPECT_CLIP_RECT(expRect, clipStatePtr) \
        EXPECT_NE(nullptr, (clipStatePtr)) << "Op is unclipped"; \
        if ((clipStatePtr)->mode == ClipMode::Rectangle) { \
            EXPECT_EQ((expRect), reinterpret_cast<const ClipRect*>(clipStatePtr)->rect); \
        } else { \
            ADD_FAILURE() << "ClipState not a rect"; \
        }
/**
 * Like gtest's TEST, but runs on the RenderThread, and 'renderThread' is passed, in top level scope
 * (for e.g. accessing its RenderState)
 */
#define RENDERTHREAD_TEST(test_case_name, test_name) \
    class test_case_name##_##test_name##_RenderThreadTest { \
    public: \
        static void doTheThing(renderthread::RenderThread& renderThread); \
    }; \
    TEST(test_case_name, test_name) { \
        TestUtils::runOnRenderThread(test_case_name##_##test_name##_RenderThreadTest::doTheThing); \
    }; \
    void test_case_name##_##test_name##_RenderThreadTest::doTheThing(renderthread::RenderThread& renderThread)

/**
 * Sets a property value temporarily, generally for the duration of a test, restoring the previous
 * value when going out of scope.
 *
 * Can be used e.g. to test behavior only active while Properties::debugOverdraw is enabled.
 */
template <typename T>
class ScopedProperty {
public:
    ScopedProperty(T& property, T newValue)
        : mPropertyPtr(&property)
        , mOldValue(property) {
        property = newValue;
    }
    ~ScopedProperty() {
        *mPropertyPtr = mOldValue;
    }
private:
    T* mPropertyPtr;
    T mOldValue;
};

class TestUtils {
public:
    class SignalingDtor {
    public:
        SignalingDtor()
                : mSignal(nullptr) {}
        SignalingDtor(int* signal)
                : mSignal(signal) {}
        void setSignal(int* signal) {
            mSignal = signal;
        }
        ~SignalingDtor() {
            if (mSignal) {
                (*mSignal)++;
            }
        }
    private:
        int* mSignal;
    };

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
        snapshot->clip(clip, SkRegion::kReplace_Op); // store clip first, so it isn't transformed
        *(snapshot->transform) = transform;
        return snapshot;
    }

    static SkBitmap createSkBitmap(int width, int height,
            SkColorType colorType = kN32_SkColorType) {
        SkBitmap bitmap;
        SkImageInfo info = SkImageInfo::Make(width, height,
                colorType, kPremul_SkAlphaType);
        bitmap.setInfo(info);
        bitmap.allocPixels(info);
        return bitmap;
    }

    static sp<DeferredLayerUpdater> createTextureLayerUpdater(
            renderthread::RenderThread& renderThread, uint32_t width, uint32_t height,
            const SkMatrix& transform);

    template<class CanvasType>
    static std::unique_ptr<DisplayList> createDisplayList(int width, int height,
            std::function<void(CanvasType& canvas)> canvasCallback) {
        CanvasType canvas(width, height);
        canvasCallback(canvas);
        return std::unique_ptr<DisplayList>(canvas.finishRecording());
    }

    static sp<RenderNode> createNode(int left, int top, int right, int bottom,
            std::function<void(RenderProperties& props, TestCanvas& canvas)> setup) {
#if HWUI_NULL_GPU
        // if RenderNodes are being sync'd/used, device info will be needed, since
        // DeviceInfo::maxTextureSize() affects layer property
        DeviceInfo::initialize();
#endif

        sp<RenderNode> node = new RenderNode();
        RenderProperties& props = node->mutateStagingProperties();
        props.setLeftTopRightBottom(left, top, right, bottom);
        if (setup) {
            TestCanvas canvas(props.getWidth(), props.getHeight());
            setup(props, canvas);
            node->setStagingDisplayList(canvas.finishRecording(), nullptr);
        }
        node->setPropertyFieldsDirty(0xFFFFFFFF);
        return node;
    }

    static void recordNode(RenderNode& node,
            std::function<void(TestCanvas&)> contentCallback) {
       TestCanvas canvas(node.stagingProperties().getWidth(),
               node.stagingProperties().getHeight());
       contentCallback(canvas);
       node.setStagingDisplayList(canvas.finishRecording(), nullptr);
    }

    /**
     * Forces a sync of a tree of RenderNode, such that every descendant will have its staging
     * properties and DisplayList moved to the render copies.
     *
     * Note: does not check dirtiness bits, so any non-staging DisplayLists will be discarded.
     * For this reason, this should generally only be called once on a tree.
     */
    static void syncHierarchyPropertiesAndDisplayList(sp<RenderNode>& node) {
        syncHierarchyPropertiesAndDisplayListImpl(node.get());
    }

    static sp<RenderNode>& getSyncedNode(sp<RenderNode>& node) {
        syncHierarchyPropertiesAndDisplayList(node);
        return node;
    }

    typedef std::function<void(renderthread::RenderThread& thread)> RtCallback;

    class TestTask : public renderthread::RenderTask {
    public:
        TestTask(RtCallback rtCallback)
                : rtCallback(rtCallback) {}
        virtual ~TestTask() {}
        virtual void run() override;
        RtCallback rtCallback;
    };

    /**
     * NOTE: requires surfaceflinger to run, otherwise this method will wait indefinitely.
     */
    static void runOnRenderThread(RtCallback rtCallback) {
        TestTask task(rtCallback);
        renderthread::RenderThread::getInstance().queueAndWait(&task);
    }

    static bool isRenderThreadRunning() {
        return renderthread::RenderThread::hasInstance();
    }

    static SkColor interpolateColor(float fraction, SkColor start, SkColor end);

    static void layoutTextUnscaled(const SkPaint& paint, const char* text,
            std::vector<glyph_t>* outGlyphs, std::vector<float>* outPositions,
            float* outTotalAdvance, Rect* outBounds);

    static void drawUtf8ToCanvas(Canvas* canvas, const char* text,
            const SkPaint& paint, float x, float y);

    static void drawUtf8ToCanvas(Canvas* canvas, const char* text,
            const SkPaint& paint, const SkPath& path);

    static std::unique_ptr<uint16_t[]> asciiToUtf16(const char* str);

private:
    static void syncHierarchyPropertiesAndDisplayListImpl(RenderNode* node) {
        node->syncProperties();
        node->syncDisplayList(nullptr);
        auto displayList = node->getDisplayList();
        if (displayList) {
            for (auto&& childOp : displayList->getChildren()) {
                syncHierarchyPropertiesAndDisplayListImpl(childOp->renderNode);
            }
        }
    }

}; // class TestUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* TEST_UTILS_H */
