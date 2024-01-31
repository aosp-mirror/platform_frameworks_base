/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <com_android_input_flags.h>
#include <flag_macros.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <input/PointerController.h>
#include <input/SpriteController.h>

#include <atomic>
#include <thread>

#include "input/Input.h"
#include "mocks/MockSprite.h"
#include "mocks/MockSpriteController.h"

namespace android {

namespace input_flags = com::android::input::flags;

enum TestCursorType {
    CURSOR_TYPE_DEFAULT = 0,
    CURSOR_TYPE_HOVER,
    CURSOR_TYPE_TOUCH,
    CURSOR_TYPE_ANCHOR,
    CURSOR_TYPE_ADDITIONAL,
    CURSOR_TYPE_ADDITIONAL_ANIM,
    CURSOR_TYPE_STYLUS,
    CURSOR_TYPE_CUSTOM = -1,
};

using ::testing::AllOf;
using ::testing::Field;
using ::testing::NiceMock;
using ::testing::Return;
using ::testing::Test;

std::pair<float, float> getHotSpotCoordinatesForType(int32_t type) {
    return std::make_pair(type * 10, type * 10 + 5);
}

class MockPointerControllerPolicyInterface : public PointerControllerPolicyInterface {
public:
    virtual void loadPointerIcon(SpriteIcon* icon, int32_t displayId) override;
    virtual void loadPointerResources(PointerResources* outResources, int32_t displayId) override;
    virtual void loadAdditionalMouseResources(
            std::map<PointerIconStyle, SpriteIcon>* outResources,
            std::map<PointerIconStyle, PointerAnimation>* outAnimationResources,
            int32_t displayId) override;
    virtual PointerIconStyle getDefaultPointerIconId() override;
    virtual PointerIconStyle getDefaultStylusIconId() override;
    virtual PointerIconStyle getCustomPointerIconId() override;
    virtual void onPointerDisplayIdChanged(int32_t displayId, const FloatPoint& position) override;

    bool allResourcesAreLoaded();
    bool noResourcesAreLoaded();
    std::optional<int32_t> getLastReportedPointerDisplayId() { return latestPointerDisplayId; }

private:
    void loadPointerIconForType(SpriteIcon* icon, int32_t cursorType);

    bool pointerIconLoaded{false};
    bool pointerResourcesLoaded{false};
    bool additionalMouseResourcesLoaded{false};
    std::optional<int32_t /*displayId*/> latestPointerDisplayId;
};

void MockPointerControllerPolicyInterface::loadPointerIcon(SpriteIcon* icon, int32_t) {
    loadPointerIconForType(icon, CURSOR_TYPE_DEFAULT);
    pointerIconLoaded = true;
}

void MockPointerControllerPolicyInterface::loadPointerResources(PointerResources* outResources,
        int32_t) {
    loadPointerIconForType(&outResources->spotHover, CURSOR_TYPE_HOVER);
    loadPointerIconForType(&outResources->spotTouch, CURSOR_TYPE_TOUCH);
    loadPointerIconForType(&outResources->spotAnchor, CURSOR_TYPE_ANCHOR);
    pointerResourcesLoaded = true;
}

void MockPointerControllerPolicyInterface::loadAdditionalMouseResources(
        std::map<PointerIconStyle, SpriteIcon>* outResources,
        std::map<PointerIconStyle, PointerAnimation>* outAnimationResources, int32_t) {
    SpriteIcon icon;
    PointerAnimation anim;

    // CURSOR_TYPE_ADDITIONAL doesn't have animation resource.
    int32_t cursorType = CURSOR_TYPE_ADDITIONAL;
    loadPointerIconForType(&icon, cursorType);
    (*outResources)[static_cast<PointerIconStyle>(cursorType)] = icon;

    // CURSOR_TYPE_ADDITIONAL_ANIM has animation resource.
    cursorType = CURSOR_TYPE_ADDITIONAL_ANIM;
    loadPointerIconForType(&icon, cursorType);
    anim.animationFrames.push_back(icon);
    anim.durationPerFrame = 10;
    (*outResources)[static_cast<PointerIconStyle>(cursorType)] = icon;
    (*outAnimationResources)[static_cast<PointerIconStyle>(cursorType)] = anim;

    // CURSOR_TYPE_STYLUS doesn't have animation resource.
    cursorType = CURSOR_TYPE_STYLUS;
    loadPointerIconForType(&icon, cursorType);
    (*outResources)[static_cast<PointerIconStyle>(cursorType)] = icon;

    additionalMouseResourcesLoaded = true;
}

PointerIconStyle MockPointerControllerPolicyInterface::getDefaultPointerIconId() {
    return static_cast<PointerIconStyle>(CURSOR_TYPE_DEFAULT);
}

PointerIconStyle MockPointerControllerPolicyInterface::getDefaultStylusIconId() {
    return static_cast<PointerIconStyle>(CURSOR_TYPE_STYLUS);
}

PointerIconStyle MockPointerControllerPolicyInterface::getCustomPointerIconId() {
    return static_cast<PointerIconStyle>(CURSOR_TYPE_CUSTOM);
}

bool MockPointerControllerPolicyInterface::allResourcesAreLoaded() {
    return pointerIconLoaded && pointerResourcesLoaded && additionalMouseResourcesLoaded;
}

bool MockPointerControllerPolicyInterface::noResourcesAreLoaded() {
    return !(pointerIconLoaded || pointerResourcesLoaded || additionalMouseResourcesLoaded);
}

void MockPointerControllerPolicyInterface::loadPointerIconForType(SpriteIcon* icon, int32_t type) {
    icon->style = static_cast<PointerIconStyle>(type);
    std::pair<float, float> hotSpot = getHotSpotCoordinatesForType(type);
    icon->hotSpotX = hotSpot.first;
    icon->hotSpotY = hotSpot.second;
}

void MockPointerControllerPolicyInterface::onPointerDisplayIdChanged(int32_t displayId,
                                                                     const FloatPoint& /*position*/
) {
    latestPointerDisplayId = displayId;
}

class TestPointerController : public PointerController {
public:
    TestPointerController(sp<android::gui::WindowInfosListener>& registeredListener,
                          sp<PointerControllerPolicyInterface> policy, const sp<Looper>& looper,
                          SpriteController& spriteController)
          : PointerController(
                    policy, looper, spriteController,
                    /*enabled=*/true,
                    [&registeredListener](const sp<android::gui::WindowInfosListener>& listener)
                            -> std::pair<std::vector<gui::WindowInfo>,
                                         std::vector<gui::DisplayInfo>> {
                        // Register listener
                        registeredListener = listener;
                        return {};
                    },
                    [&registeredListener](const sp<android::gui::WindowInfosListener>& listener) {
                        // Unregister listener
                        if (registeredListener == listener) registeredListener = nullptr;
                    }) {}
    ~TestPointerController() override {}
};

class PointerControllerTest : public Test {
protected:
    PointerControllerTest();
    ~PointerControllerTest();

    void ensureDisplayViewportIsSet(int32_t displayId = ADISPLAY_ID_DEFAULT);

    sp<MockSprite> mPointerSprite;
    sp<MockPointerControllerPolicyInterface> mPolicy;
    std::unique_ptr<MockSpriteController> mSpriteController;
    std::shared_ptr<PointerController> mPointerController;
    sp<android::gui::WindowInfosListener> mRegisteredListener;

private:
    void loopThread();

    std::atomic<bool> mRunning = true;
    class MyLooper : public Looper {
    public:
        MyLooper() : Looper(false) {}
        ~MyLooper() = default;
    };
    sp<MyLooper> mLooper;
    std::thread mThread;
};

PointerControllerTest::PointerControllerTest() : mPointerSprite(new NiceMock<MockSprite>),
        mLooper(new MyLooper), mThread(&PointerControllerTest::loopThread, this) {
    mSpriteController.reset(new NiceMock<MockSpriteController>(mLooper));
    mPolicy = new MockPointerControllerPolicyInterface();

    EXPECT_CALL(*mSpriteController, createSprite())
            .WillOnce(Return(mPointerSprite));

    mPointerController = std::make_unique<TestPointerController>(mRegisteredListener, mPolicy,
                                                                 mLooper, *mSpriteController);
}

PointerControllerTest::~PointerControllerTest() {
    mPointerController.reset();
    mRunning.store(false, std::memory_order_relaxed);
    mThread.join();
}

void PointerControllerTest::ensureDisplayViewportIsSet(int32_t displayId) {
    DisplayViewport viewport;
    viewport.displayId = displayId;
    viewport.logicalRight = 1600;
    viewport.logicalBottom = 1200;
    viewport.physicalRight = 800;
    viewport.physicalBottom = 600;
    viewport.deviceWidth = 400;
    viewport.deviceHeight = 300;
    mPointerController->setDisplayViewport(viewport);
}

void PointerControllerTest::loopThread() {
    Looper::setForThread(mLooper);

    while (mRunning.load(std::memory_order_relaxed)) {
        mLooper->pollOnce(100);
    }
}

TEST_F(PointerControllerTest, useDefaultCursorTypeByDefault) {
    ensureDisplayViewportIsSet();
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);

    std::pair<float, float> hotspot = getHotSpotCoordinatesForType(CURSOR_TYPE_DEFAULT);
    EXPECT_CALL(*mPointerSprite, setVisible(true));
    EXPECT_CALL(*mPointerSprite, setAlpha(1.0f));
    EXPECT_CALL(*mPointerSprite,
                setIcon(AllOf(Field(&SpriteIcon::style,
                                    static_cast<PointerIconStyle>(CURSOR_TYPE_DEFAULT)),
                              Field(&SpriteIcon::hotSpotX, hotspot.first),
                              Field(&SpriteIcon::hotSpotY, hotspot.second))));
    mPointerController->reloadPointerResources();
}

TEST_F(PointerControllerTest, useStylusTypeForStylusHover) {
    ensureDisplayViewportIsSet();
    mPointerController->setPresentation(PointerController::Presentation::STYLUS_HOVER);
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);
    std::pair<float, float> hotspot = getHotSpotCoordinatesForType(CURSOR_TYPE_STYLUS);
    EXPECT_CALL(*mPointerSprite, setVisible(true));
    EXPECT_CALL(*mPointerSprite, setAlpha(1.0f));
    EXPECT_CALL(*mPointerSprite,
                setIcon(AllOf(Field(&SpriteIcon::style,
                                    static_cast<PointerIconStyle>(CURSOR_TYPE_STYLUS)),
                              Field(&SpriteIcon::hotSpotX, hotspot.first),
                              Field(&SpriteIcon::hotSpotY, hotspot.second))));
    mPointerController->reloadPointerResources();
}

TEST_F_WITH_FLAGS(PointerControllerTest, setPresentationBeforeDisplayViewportDoesNotLoadResources,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(input_flags, enable_pointer_choreographer))) {
    // Setting the presentation mode before a display viewport is set will not load any resources.
    mPointerController->setPresentation(PointerController::Presentation::POINTER);
    ASSERT_TRUE(mPolicy->noResourcesAreLoaded());

    // When the display is set, then the resources are loaded.
    ensureDisplayViewportIsSet();
    ASSERT_TRUE(mPolicy->allResourcesAreLoaded());
}

TEST_F_WITH_FLAGS(PointerControllerTest, updatePointerIcon,
                  REQUIRES_FLAGS_DISABLED(ACONFIG_FLAG(input_flags,
                                                       enable_pointer_choreographer))) {
    ensureDisplayViewportIsSet();
    mPointerController->setPresentation(PointerController::Presentation::POINTER);
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);

    int32_t type = CURSOR_TYPE_ADDITIONAL;
    std::pair<float, float> hotspot = getHotSpotCoordinatesForType(type);
    EXPECT_CALL(*mPointerSprite, setVisible(true));
    EXPECT_CALL(*mPointerSprite, setAlpha(1.0f));
    EXPECT_CALL(*mPointerSprite,
                setIcon(AllOf(Field(&SpriteIcon::style, static_cast<PointerIconStyle>(type)),
                              Field(&SpriteIcon::hotSpotX, hotspot.first),
                              Field(&SpriteIcon::hotSpotY, hotspot.second))));
    mPointerController->updatePointerIcon(static_cast<PointerIconStyle>(type));
}

TEST_F_WITH_FLAGS(PointerControllerTest, updatePointerIconWithChoreographer,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(input_flags, enable_pointer_choreographer))) {
    // When PointerChoreographer is enabled, the presentation mode is set before the viewport.
    mPointerController->setPresentation(PointerController::Presentation::POINTER);
    ensureDisplayViewportIsSet();
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);

    int32_t type = CURSOR_TYPE_ADDITIONAL;
    std::pair<float, float> hotspot = getHotSpotCoordinatesForType(type);
    EXPECT_CALL(*mPointerSprite, setVisible(true));
    EXPECT_CALL(*mPointerSprite, setAlpha(1.0f));
    EXPECT_CALL(*mPointerSprite,
                setIcon(AllOf(Field(&SpriteIcon::style, static_cast<PointerIconStyle>(type)),
                              Field(&SpriteIcon::hotSpotX, hotspot.first),
                              Field(&SpriteIcon::hotSpotY, hotspot.second))));
    mPointerController->updatePointerIcon(static_cast<PointerIconStyle>(type));
}

TEST_F(PointerControllerTest, setCustomPointerIcon) {
    ensureDisplayViewportIsSet();
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);

    int32_t style = CURSOR_TYPE_CUSTOM;
    float hotSpotX = 15;
    float hotSpotY = 20;

    SpriteIcon icon;
    icon.style = static_cast<PointerIconStyle>(style);
    icon.hotSpotX = hotSpotX;
    icon.hotSpotY = hotSpotY;

    EXPECT_CALL(*mPointerSprite, setVisible(true));
    EXPECT_CALL(*mPointerSprite, setAlpha(1.0f));
    EXPECT_CALL(*mPointerSprite,
                setIcon(AllOf(Field(&SpriteIcon::style, static_cast<PointerIconStyle>(style)),
                              Field(&SpriteIcon::hotSpotX, hotSpotX),
                              Field(&SpriteIcon::hotSpotY, hotSpotY))));
    mPointerController->setCustomPointerIcon(icon);
}

TEST_F(PointerControllerTest, doesNotGetResourcesBeforeSettingViewport) {
    mPointerController->setPresentation(PointerController::Presentation::POINTER);
    mPointerController->setPosition(1.0f, 1.0f);
    mPointerController->move(1.0f, 1.0f);
    mPointerController->unfade(PointerController::Transition::IMMEDIATE);
    mPointerController->fade(PointerController::Transition::IMMEDIATE);

    EXPECT_TRUE(mPolicy->noResourcesAreLoaded());

    ensureDisplayViewportIsSet();
}

TEST_F(PointerControllerTest, notifiesPolicyWhenPointerDisplayChanges) {
    EXPECT_FALSE(mPolicy->getLastReportedPointerDisplayId())
            << "A pointer display change does not occur when PointerController is created.";

    ensureDisplayViewportIsSet(ADISPLAY_ID_DEFAULT);

    const auto lastReportedPointerDisplayId = mPolicy->getLastReportedPointerDisplayId();
    ASSERT_TRUE(lastReportedPointerDisplayId)
            << "The policy is notified of a pointer display change when the viewport is first set.";
    EXPECT_EQ(ADISPLAY_ID_DEFAULT, *lastReportedPointerDisplayId)
            << "Incorrect pointer display notified.";

    ensureDisplayViewportIsSet(42);

    EXPECT_EQ(42, *mPolicy->getLastReportedPointerDisplayId())
            << "The policy is notified when the pointer display changes.";

    // Release the PointerController.
    mPointerController = nullptr;

    EXPECT_EQ(ADISPLAY_ID_NONE, *mPolicy->getLastReportedPointerDisplayId())
            << "The pointer display changes to invalid when PointerController is destroyed.";
}

class PointerControllerWindowInfoListenerTest : public Test {};

TEST_F(PointerControllerWindowInfoListenerTest,
       doesNotCrashIfListenerCalledAfterPointerControllerDestroyed) {
    sp<Looper> looper = new Looper(false);
    auto spriteController = NiceMock<MockSpriteController>(looper);
    sp<android::gui::WindowInfosListener> registeredListener;
    sp<android::gui::WindowInfosListener> localListenerCopy;
    sp<MockPointerControllerPolicyInterface> policy = new MockPointerControllerPolicyInterface();
    {
        TestPointerController pointerController(registeredListener, policy, looper,
                                                spriteController);
        ASSERT_NE(nullptr, registeredListener) << "WindowInfosListener was not registered";
        localListenerCopy = registeredListener;
    }
    EXPECT_EQ(nullptr, registeredListener) << "WindowInfosListener was not unregistered";
    localListenerCopy->onWindowInfosChanged({{}, {}, 0, 0});
}

}  // namespace android
