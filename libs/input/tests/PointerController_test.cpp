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
    virtual void loadPointerIcon(SpriteIcon* icon, ui::LogicalDisplayId displayId) override;
    virtual void loadPointerResources(PointerResources* outResources,
                                      ui::LogicalDisplayId displayId) override;
    virtual void loadAdditionalMouseResources(
            std::map<PointerIconStyle, SpriteIcon>* outResources,
            std::map<PointerIconStyle, PointerAnimation>* outAnimationResources,
            ui::LogicalDisplayId displayId) override;
    virtual PointerIconStyle getDefaultPointerIconId() override;
    virtual PointerIconStyle getDefaultStylusIconId() override;
    virtual PointerIconStyle getCustomPointerIconId() override;

    bool allResourcesAreLoaded();
    bool noResourcesAreLoaded();

private:
    void loadPointerIconForType(SpriteIcon* icon, int32_t cursorType);

    bool pointerIconLoaded{false};
    bool pointerResourcesLoaded{false};
    bool additionalMouseResourcesLoaded{false};
};

void MockPointerControllerPolicyInterface::loadPointerIcon(SpriteIcon* icon, ui::LogicalDisplayId) {
    loadPointerIconForType(icon, CURSOR_TYPE_DEFAULT);
    pointerIconLoaded = true;
}

void MockPointerControllerPolicyInterface::loadPointerResources(PointerResources* outResources,
                                                                ui::LogicalDisplayId) {
    loadPointerIconForType(&outResources->spotHover, CURSOR_TYPE_HOVER);
    loadPointerIconForType(&outResources->spotTouch, CURSOR_TYPE_TOUCH);
    loadPointerIconForType(&outResources->spotAnchor, CURSOR_TYPE_ANCHOR);
    pointerResourcesLoaded = true;
}

void MockPointerControllerPolicyInterface::loadAdditionalMouseResources(
        std::map<PointerIconStyle, SpriteIcon>* outResources,
        std::map<PointerIconStyle, PointerAnimation>* outAnimationResources, ui::LogicalDisplayId) {
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

class TestPointerController : public PointerController {
public:
    TestPointerController(sp<android::gui::WindowInfosListener>& registeredListener,
                          sp<PointerControllerPolicyInterface> policy, const sp<Looper>& looper,
                          SpriteController& spriteController)
          : PointerController(
                    policy, looper, spriteController,
                    [&registeredListener](const sp<android::gui::WindowInfosListener>& listener)
                            -> std::vector<gui::DisplayInfo> {
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
private:
    void loopThread();

    std::atomic<bool> mRunning = true;
    class MyLooper : public Looper {
    public:
        MyLooper() : Looper(false) {}
        ~MyLooper() = default;
    };

protected:
    PointerControllerTest();
    ~PointerControllerTest();

    void ensureDisplayViewportIsSet(ui::LogicalDisplayId displayId = ui::LogicalDisplayId::DEFAULT);

    sp<MockSprite> mPointerSprite;
    sp<MockPointerControllerPolicyInterface> mPolicy;
    std::unique_ptr<MockSpriteController> mSpriteController;
    std::shared_ptr<PointerController> mPointerController;
    sp<android::gui::WindowInfosListener> mRegisteredListener;
    sp<MyLooper> mLooper;

private:
    std::thread mThread;
};

PointerControllerTest::PointerControllerTest()
      : mPointerSprite(new NiceMock<MockSprite>),
        mLooper(new MyLooper),
        mThread(&PointerControllerTest::loopThread, this) {
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

void PointerControllerTest::ensureDisplayViewportIsSet(ui::LogicalDisplayId displayId) {
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

TEST_F(PointerControllerTest, setPresentationBeforeDisplayViewportDoesNotLoadResources) {
    // Setting the presentation mode before a display viewport is set will not load any resources.
    mPointerController->setPresentation(PointerController::Presentation::POINTER);
    ASSERT_TRUE(mPolicy->noResourcesAreLoaded());

    // When the display is set, then the resources are loaded.
    ensureDisplayViewportIsSet();
    ASSERT_TRUE(mPolicy->allResourcesAreLoaded());
}

TEST_F(PointerControllerTest, updatePointerIconWithChoreographer) {
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

TEST_F(PointerControllerTest, updatesSkipScreenshotFlagForTouchSpots) {
    ensureDisplayViewportIsSet();

    PointerCoords testSpotCoords;
    testSpotCoords.clear();
    testSpotCoords.setAxisValue(AMOTION_EVENT_AXIS_X, 1);
    testSpotCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, 1);
    BitSet32 testIdBits;
    testIdBits.markBit(0);
    std::array<uint32_t, MAX_POINTER_ID + 1> testIdToIndex;

    sp<MockSprite> testSpotSprite(new NiceMock<MockSprite>);

    // By default sprite is not marked secure
    EXPECT_CALL(*mSpriteController, createSprite).WillOnce(Return(testSpotSprite));
    EXPECT_CALL(*testSpotSprite, setSkipScreenshot).With(testing::Args<0>(false));

    // Update spots to sync state with sprite
    mPointerController->setSpots(&testSpotCoords, testIdToIndex.cbegin(), testIdBits,
                                 ui::LogicalDisplayId::DEFAULT);
    testing::Mock::VerifyAndClearExpectations(testSpotSprite.get());

    // Marking the display to skip screenshot should update sprite as well
    mPointerController->setSkipScreenshotFlagForDisplay(ui::LogicalDisplayId::DEFAULT);
    EXPECT_CALL(*testSpotSprite, setSkipScreenshot).With(testing::Args<0>(true));

    // Update spots to sync state with sprite
    mPointerController->setSpots(&testSpotCoords, testIdToIndex.cbegin(), testIdBits,
                                 ui::LogicalDisplayId::DEFAULT);
    testing::Mock::VerifyAndClearExpectations(testSpotSprite.get());

    // Reset flag and verify again
    mPointerController->clearSkipScreenshotFlags();
    EXPECT_CALL(*testSpotSprite, setSkipScreenshot).With(testing::Args<0>(false));
    mPointerController->setSpots(&testSpotCoords, testIdToIndex.cbegin(), testIdBits,
                                 ui::LogicalDisplayId::DEFAULT);
    testing::Mock::VerifyAndClearExpectations(testSpotSprite.get());
}

class PointerControllerSkipScreenshotFlagTest
      : public PointerControllerTest,
        public testing::WithParamInterface<PointerControllerInterface::ControllerType> {};

TEST_P(PointerControllerSkipScreenshotFlagTest, updatesSkipScreenshotFlag) {
    sp<MockSprite> testPointerSprite(new NiceMock<MockSprite>);
    EXPECT_CALL(*mSpriteController, createSprite).WillOnce(Return(testPointerSprite));

    // Create a pointer controller
    mPointerController =
            PointerController::create(mPolicy, mLooper, *mSpriteController, GetParam());
    ensureDisplayViewportIsSet(ui::LogicalDisplayId::DEFAULT);

    // By default skip screenshot flag is not set for the sprite
    EXPECT_CALL(*testPointerSprite, setSkipScreenshot).With(testing::Args<0>(false));

    // Update pointer to sync state with sprite
    mPointerController->setPosition(100, 100);
    testing::Mock::VerifyAndClearExpectations(testPointerSprite.get());

    // Marking the controller to skip screenshot should update pointer sprite
    mPointerController->setSkipScreenshotFlagForDisplay(ui::LogicalDisplayId::DEFAULT);
    EXPECT_CALL(*testPointerSprite, setSkipScreenshot).With(testing::Args<0>(true));

    // Update pointer to sync state with sprite
    mPointerController->move(10, 10);
    testing::Mock::VerifyAndClearExpectations(testPointerSprite.get());

    // Reset flag and verify again
    mPointerController->clearSkipScreenshotFlags();
    EXPECT_CALL(*testPointerSprite, setSkipScreenshot).With(testing::Args<0>(false));
    mPointerController->move(10, 10);
    testing::Mock::VerifyAndClearExpectations(testPointerSprite.get());
}

INSTANTIATE_TEST_SUITE_P(PointerControllerSkipScreenshotFlagTest,
                         PointerControllerSkipScreenshotFlagTest,
                         testing::Values(PointerControllerInterface::ControllerType::MOUSE,
                                         PointerControllerInterface::ControllerType::STYLUS));

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
