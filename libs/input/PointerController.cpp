/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "PointerController"
//#define LOG_NDEBUG 0

#include "PointerController.h"

#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <android-base/stringprintf.h>
#include <android-base/thread_annotations.h>
#include <ftl/enum.h>
#include <input/PrintTools.h>

#include <mutex>

#include "PointerControllerContext.h"

#define INDENT "  "
#define INDENT2 "    "
#define INDENT3 "      "

namespace android {

namespace {

const ui::Transform kIdentityTransform;

} // namespace

// --- PointerController::DisplayInfoListener ---

void PointerController::DisplayInfoListener::onWindowInfosChanged(
        const gui::WindowInfosUpdate& update) {
    std::scoped_lock lock(mLock);
    if (mPointerController == nullptr) return;

    // PointerController uses DisplayInfoListener's lock.
    base::ScopedLockAssertion assumeLocked(mPointerController->getLock());
    mPointerController->onDisplayInfosChangedLocked(update.displayInfos);
}

void PointerController::DisplayInfoListener::onPointerControllerDestroyed() {
    std::scoped_lock lock(mLock);
    mPointerController = nullptr;
}

// --- PointerController ---

std::shared_ptr<PointerController> PointerController::create(
        const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
        SpriteController& spriteController, ControllerType type) {
    // using 'new' to access non-public constructor
    std::shared_ptr<PointerController> controller;
    switch (type) {
        case ControllerType::MOUSE:
            controller = std::shared_ptr<PointerController>(
                    new MousePointerController(policy, looper, spriteController));
            break;
        case ControllerType::TOUCH:
            controller = std::shared_ptr<PointerController>(
                    new TouchPointerController(policy, looper, spriteController));
            break;
        case ControllerType::STYLUS:
            controller = std::shared_ptr<PointerController>(
                    new StylusPointerController(policy, looper, spriteController));
            break;
        default:
            LOG_ALWAYS_FATAL("Invalid ControllerType: %d", static_cast<int>(type));
    }

    /*
     * Now we need to hook up the constructed PointerController object to its callbacks.
     *
     * This must be executed after the constructor but before any other methods on PointerController
     * in order to ensure that the fully constructed object is visible on the Looper thread, since
     * that may be a different thread than where the PointerController is initially constructed.
     *
     * Unfortunately, this cannot be done as part of the constructor since we need to hand out
     * weak_ptr's which themselves cannot be constructed until there's at least one shared_ptr.
     */

    controller->mContext.setHandlerController(controller);
    controller->mContext.setCallbackController(controller);
    return controller;
}

PointerController::PointerController(const sp<PointerControllerPolicyInterface>& policy,
                                     const sp<Looper>& looper, SpriteController& spriteController)
      : PointerController(
                policy, looper, spriteController,
                [](const sp<android::gui::WindowInfosListener>& listener) {
                    auto initialInfo = std::make_pair(std::vector<android::gui::WindowInfo>{},
                                                      std::vector<android::gui::DisplayInfo>{});
                    SurfaceComposerClient::getDefault()->addWindowInfosListener(listener,
                                                                                &initialInfo);
                    return initialInfo.second;
                },
                [](const sp<android::gui::WindowInfosListener>& listener) {
                    SurfaceComposerClient::getDefault()->removeWindowInfosListener(listener);
                }) {}

PointerController::PointerController(const sp<PointerControllerPolicyInterface>& policy,
                                     const sp<Looper>& looper, SpriteController& spriteController,
                                     const WindowListenerRegisterConsumer& registerListener,
                                     WindowListenerUnregisterConsumer unregisterListener)
      : mContext(policy, looper, spriteController, *this),
        mCursorController(mContext),
        mDisplayInfoListener(sp<DisplayInfoListener>::make(this)),
        mUnregisterWindowInfosListener(std::move(unregisterListener)) {
    std::scoped_lock lock(getLock());
    mLocked.presentation = Presentation::SPOT;
    const auto& initialDisplayInfos = registerListener(mDisplayInfoListener);
    onDisplayInfosChangedLocked(initialDisplayInfos);
}

PointerController::~PointerController() {
    mDisplayInfoListener->onPointerControllerDestroyed();
    mUnregisterWindowInfosListener(mDisplayInfoListener);
}

std::mutex& PointerController::getLock() const {
    return mDisplayInfoListener->mLock;
}

void PointerController::move(float deltaX, float deltaY) {
    const ui::LogicalDisplayId displayId = mCursorController.getDisplayId();
    vec2 transformed;
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        transformed = transformWithoutTranslation(transform, {deltaX, deltaY});
    }
    mCursorController.move(transformed.x, transformed.y);
}

void PointerController::setPosition(float x, float y) {
    const ui::LogicalDisplayId displayId = mCursorController.getDisplayId();
    vec2 transformed;
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        transformed = transform.transform(x, y);
    }
    mCursorController.setPosition(transformed.x, transformed.y);
}

FloatPoint PointerController::getPosition() const {
    const ui::LogicalDisplayId displayId = mCursorController.getDisplayId();
    const auto p = mCursorController.getPosition();
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        return FloatPoint{transform.inverse().transform(p.x, p.y)};
    }
}

ui::LogicalDisplayId PointerController::getDisplayId() const {
    return mCursorController.getDisplayId();
}

void PointerController::fade(Transition transition) {
    std::scoped_lock lock(getLock());
    mCursorController.fade(transition);
}

void PointerController::unfade(Transition transition) {
    std::scoped_lock lock(getLock());
    mCursorController.unfade(transition);
}

void PointerController::setPresentation(Presentation presentation) {
    std::scoped_lock lock(getLock());

    if (mLocked.presentation == presentation) {
        return;
    }

    mLocked.presentation = presentation;

    // The presentation mode is only set once when the PointerController is constructed,
    // before the display viewport is provided.
    mCursorController.setStylusHoverMode(presentation == Presentation::STYLUS_HOVER);
}

void PointerController::setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                                 BitSet32 spotIdBits, ui::LogicalDisplayId displayId) {
    std::scoped_lock lock(getLock());
    std::array<PointerCoords, MAX_POINTERS> outSpotCoords{};
    const ui::Transform& transform = getTransformForDisplayLocked(displayId);

    for (BitSet32 idBits(spotIdBits); !idBits.isEmpty();) {
        const uint32_t index = spotIdToIndex[idBits.clearFirstMarkedBit()];

        const vec2 xy = transform.transform(spotCoords[index].getXYValue());
        outSpotCoords[index].setAxisValue(AMOTION_EVENT_AXIS_X, xy.x);
        outSpotCoords[index].setAxisValue(AMOTION_EVENT_AXIS_Y, xy.y);

        float pressure = spotCoords[index].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE);
        outSpotCoords[index].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, pressure);
    }

    auto it = mLocked.spotControllers.find(displayId);
    if (it == mLocked.spotControllers.end()) {
        mLocked.spotControllers.try_emplace(displayId, displayId, mContext);
    }
    bool skipScreenshot = mLocked.displaysToSkipScreenshot.find(displayId) !=
            mLocked.displaysToSkipScreenshot.end();
    mLocked.spotControllers.at(displayId).setSpots(outSpotCoords.data(), spotIdToIndex, spotIdBits,
                                                   skipScreenshot);
}

void PointerController::clearSpots() {
    std::scoped_lock lock(getLock());
    clearSpotsLocked();
}

void PointerController::clearSpotsLocked() {
    for (auto& [displayId, spotController] : mLocked.spotControllers) {
        spotController.clearSpots();
    }
}

void PointerController::setInactivityTimeout(InactivityTimeout inactivityTimeout) {
    mContext.setInactivityTimeout(inactivityTimeout);
}

void PointerController::reloadPointerResources() {
    std::scoped_lock lock(getLock());

    for (auto& [displayId, spotController] : mLocked.spotControllers) {
        spotController.reloadSpotResources();
    }

    if (mCursorController.resourcesLoaded()) {
        bool getAdditionalMouseResources = false;
        if (mLocked.presentation == PointerController::Presentation::POINTER ||
            mLocked.presentation == PointerController::Presentation::STYLUS_HOVER) {
            getAdditionalMouseResources = true;
        }
        mCursorController.reloadPointerResources(getAdditionalMouseResources);
    }
}

void PointerController::setDisplayViewport(const DisplayViewport& viewport) {
    { // acquire lock
        std::scoped_lock lock(getLock());

        bool getAdditionalMouseResources = false;
        if (mLocked.presentation == PointerController::Presentation::POINTER ||
            mLocked.presentation == PointerController::Presentation::STYLUS_HOVER) {
            getAdditionalMouseResources = true;
        }
        mCursorController.setDisplayViewport(viewport, getAdditionalMouseResources);
        if (viewport.displayId != mLocked.pointerDisplayId) {
            mLocked.pointerDisplayId = viewport.displayId;
        }
    } // release lock
}

void PointerController::updatePointerIcon(PointerIconStyle iconId) {
    std::scoped_lock lock(getLock());
    mCursorController.updatePointerIcon(iconId);
}

void PointerController::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock lock(getLock());
    mCursorController.setCustomPointerIcon(icon);
}

void PointerController::setSkipScreenshotFlagForDisplay(ui::LogicalDisplayId displayId) {
    std::scoped_lock lock(getLock());
    mLocked.displaysToSkipScreenshot.insert(displayId);
    mCursorController.setSkipScreenshot(true);
}

void PointerController::clearSkipScreenshotFlags() {
    std::scoped_lock lock(getLock());
    mLocked.displaysToSkipScreenshot.clear();
    mCursorController.setSkipScreenshot(false);
}

void PointerController::doInactivityTimeout() {
    fade(Transition::GRADUAL);
}

void PointerController::onDisplayViewportsUpdated(const std::vector<DisplayViewport>& viewports) {
    std::unordered_set<ui::LogicalDisplayId> displayIdSet;
    for (const DisplayViewport& viewport : viewports) {
        displayIdSet.insert(viewport.displayId);
    }

    std::scoped_lock lock(getLock());
    for (auto it = mLocked.spotControllers.begin(); it != mLocked.spotControllers.end();) {
        ui::LogicalDisplayId displayId = it->first;
        if (!displayIdSet.count(displayId)) {
            /*
             * Ensures that an in-progress animation won't dereference
             * a null pointer to TouchSpotController.
             */
            mContext.removeAnimationCallback(displayId);
            it = mLocked.spotControllers.erase(it);
        } else {
            ++it;
        }
    }
}

void PointerController::onDisplayInfosChangedLocked(
        const std::vector<gui::DisplayInfo>& displayInfo) {
    mLocked.mDisplayInfos = displayInfo;
}

const ui::Transform& PointerController::getTransformForDisplayLocked(
        ui::LogicalDisplayId displayId) const {
    const auto& di = mLocked.mDisplayInfos;
    auto it = std::find_if(di.begin(), di.end(), [displayId](const gui::DisplayInfo& info) {
        return info.displayId == displayId;
    });
    return it != di.end() ? it->transform : kIdentityTransform;
}

std::string PointerController::dump() {
    std::string dump = INDENT "PointerController:\n";
    std::scoped_lock lock(getLock());
    dump += StringPrintf(INDENT2 "Presentation: %s\n",
                         ftl::enum_string(mLocked.presentation).c_str());
    dump += StringPrintf(INDENT2 "Pointer Display ID: %s\n",
                         mLocked.pointerDisplayId.toString().c_str());
    dump += StringPrintf(INDENT2 "Viewports:\n");
    for (const auto& info : mLocked.mDisplayInfos) {
        info.dump(dump, INDENT3);
    }
    dump += INDENT2 "Spot Controllers:\n";
    for (const auto& [_, spotController] : mLocked.spotControllers) {
        spotController.dump(dump, INDENT3);
    }
    dump += INDENT2 "Cursor Controller:\n";
    dump += addLinePrefix(mCursorController.dump(), INDENT3);
    return dump;
}

// --- MousePointerController ---

MousePointerController::MousePointerController(const sp<PointerControllerPolicyInterface>& policy,
                                               const sp<Looper>& looper,
                                               SpriteController& spriteController)
      : PointerController(policy, looper, spriteController) {
    PointerController::setPresentation(Presentation::POINTER);
}

MousePointerController::~MousePointerController() {
    MousePointerController::fade(Transition::IMMEDIATE);
}

// --- TouchPointerController ---

TouchPointerController::TouchPointerController(const sp<PointerControllerPolicyInterface>& policy,
                                               const sp<Looper>& looper,
                                               SpriteController& spriteController)
      : PointerController(policy, looper, spriteController) {
    PointerController::setPresentation(Presentation::SPOT);
}

TouchPointerController::~TouchPointerController() {
    TouchPointerController::clearSpots();
}

// --- StylusPointerController ---

StylusPointerController::StylusPointerController(const sp<PointerControllerPolicyInterface>& policy,
                                                 const sp<Looper>& looper,
                                                 SpriteController& spriteController)
      : PointerController(policy, looper, spriteController) {
    PointerController::setPresentation(Presentation::STYLUS_HOVER);
}

StylusPointerController::~StylusPointerController() {
    StylusPointerController::fade(Transition::IMMEDIATE);
}

} // namespace android
