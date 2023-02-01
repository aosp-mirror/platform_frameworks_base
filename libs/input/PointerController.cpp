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
#include <android-base/thread_annotations.h>

#include "PointerControllerContext.h"

namespace android {

namespace {

const ui::Transform kIdentityTransform;

} // namespace

// --- PointerController::DisplayInfoListener ---

void PointerController::DisplayInfoListener::onWindowInfosChanged(
        const std::vector<android::gui::WindowInfo>&,
        const std::vector<android::gui::DisplayInfo>& displayInfos) {
    std::scoped_lock lock(mLock);
    if (mPointerController == nullptr) return;

    // PointerController uses DisplayInfoListener's lock.
    base::ScopedLockAssertion assumeLocked(mPointerController->getLock());
    mPointerController->onDisplayInfosChangedLocked(displayInfos);
}

void PointerController::DisplayInfoListener::onPointerControllerDestroyed() {
    std::scoped_lock lock(mLock);
    mPointerController = nullptr;
}

// --- PointerController ---

std::shared_ptr<PointerController> PointerController::create(
        const sp<PointerControllerPolicyInterface>& policy, const sp<Looper>& looper,
        const sp<SpriteController>& spriteController) {
    // using 'new' to access non-public constructor
    std::shared_ptr<PointerController> controller = std::shared_ptr<PointerController>(
            new PointerController(policy, looper, spriteController));

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
                                     const sp<Looper>& looper,
                                     const sp<SpriteController>& spriteController)
      : PointerController(
                policy, looper, spriteController,
                [](const sp<android::gui::WindowInfosListener>& listener) {
                    SurfaceComposerClient::getDefault()->addWindowInfosListener(listener);
                },
                [](const sp<android::gui::WindowInfosListener>& listener) {
                    SurfaceComposerClient::getDefault()->removeWindowInfosListener(listener);
                }) {}

PointerController::PointerController(const sp<PointerControllerPolicyInterface>& policy,
                                     const sp<Looper>& looper,
                                     const sp<SpriteController>& spriteController,
                                     WindowListenerConsumer registerListener,
                                     WindowListenerConsumer unregisterListener)
      : mContext(policy, looper, spriteController, *this),
        mCursorController(mContext),
        mDisplayInfoListener(new DisplayInfoListener(this)),
        mUnregisterWindowInfosListener(std::move(unregisterListener)) {
    std::scoped_lock lock(getLock());
    mLocked.presentation = Presentation::SPOT;
    registerListener(mDisplayInfoListener);
}

PointerController::~PointerController() {
    mDisplayInfoListener->onPointerControllerDestroyed();
    mUnregisterWindowInfosListener(mDisplayInfoListener);
    mContext.getPolicy()->onPointerDisplayIdChanged(ADISPLAY_ID_NONE, 0, 0);
}

std::mutex& PointerController::getLock() const {
    return mDisplayInfoListener->mLock;
}

bool PointerController::getBounds(float* outMinX, float* outMinY, float* outMaxX,
                                  float* outMaxY) const {
    return mCursorController.getBounds(outMinX, outMinY, outMaxX, outMaxY);
}

void PointerController::move(float deltaX, float deltaY) {
    const int32_t displayId = mCursorController.getDisplayId();
    vec2 transformed;
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        transformed = transformWithoutTranslation(transform, {deltaX, deltaY});
    }
    mCursorController.move(transformed.x, transformed.y);
}

void PointerController::setButtonState(int32_t buttonState) {
    mCursorController.setButtonState(buttonState);
}

int32_t PointerController::getButtonState() const {
    return mCursorController.getButtonState();
}

void PointerController::setPosition(float x, float y) {
    const int32_t displayId = mCursorController.getDisplayId();
    vec2 transformed;
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        transformed = transform.transform(x, y);
    }
    mCursorController.setPosition(transformed.x, transformed.y);
}

void PointerController::getPosition(float* outX, float* outY) const {
    const int32_t displayId = mCursorController.getDisplayId();
    mCursorController.getPosition(outX, outY);
    {
        std::scoped_lock lock(getLock());
        const auto& transform = getTransformForDisplayLocked(displayId);
        const auto xy = transform.inverse().transform(*outX, *outY);
        *outX = xy.x;
        *outY = xy.y;
    }
}

int32_t PointerController::getDisplayId() const {
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

    if (!mCursorController.isViewportValid()) {
        return;
    }

    if (presentation == Presentation::POINTER) {
        mCursorController.getAdditionalMouseResources();
        clearSpotsLocked();
    }
}

void PointerController::setSpots(const PointerCoords* spotCoords, const uint32_t* spotIdToIndex,
                                 BitSet32 spotIdBits, int32_t displayId) {
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
    mLocked.spotControllers.at(displayId).setSpots(outSpotCoords.data(), spotIdToIndex, spotIdBits);
}

void PointerController::clearSpots() {
    std::scoped_lock lock(getLock());
    clearSpotsLocked();
}

void PointerController::clearSpotsLocked() {
    for (auto& [displayID, spotController] : mLocked.spotControllers) {
        spotController.clearSpots();
    }
}

void PointerController::setInactivityTimeout(InactivityTimeout inactivityTimeout) {
    mContext.setInactivityTimeout(inactivityTimeout);
}

void PointerController::reloadPointerResources() {
    std::scoped_lock lock(getLock());

    for (auto& [displayID, spotController] : mLocked.spotControllers) {
        spotController.reloadSpotResources();
    }

    if (mCursorController.resourcesLoaded()) {
        bool getAdditionalMouseResources = false;
        if (mLocked.presentation == PointerController::Presentation::POINTER) {
            getAdditionalMouseResources = true;
        }
        mCursorController.reloadPointerResources(getAdditionalMouseResources);
    }
}

void PointerController::setDisplayViewport(const DisplayViewport& viewport) {
    std::scoped_lock lock(getLock());

    bool getAdditionalMouseResources = false;
    if (mLocked.presentation == PointerController::Presentation::POINTER) {
        getAdditionalMouseResources = true;
    }
    mCursorController.setDisplayViewport(viewport, getAdditionalMouseResources);
    if (viewport.displayId != mLocked.pointerDisplayId) {
        float xPos, yPos;
        mCursorController.getPosition(&xPos, &yPos);
        mContext.getPolicy()->onPointerDisplayIdChanged(viewport.displayId, xPos, yPos);
        mLocked.pointerDisplayId = viewport.displayId;
    }
}

void PointerController::updatePointerIcon(int32_t iconId) {
    std::scoped_lock lock(getLock());
    mCursorController.updatePointerIcon(iconId);
}

void PointerController::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock lock(getLock());
    mCursorController.setCustomPointerIcon(icon);
}

void PointerController::doInactivityTimeout() {
    fade(Transition::GRADUAL);
}

void PointerController::onDisplayViewportsUpdated(std::vector<DisplayViewport>& viewports) {
    std::unordered_set<int32_t> displayIdSet;
    for (const DisplayViewport& viewport : viewports) {
        displayIdSet.insert(viewport.displayId);
    }

    std::scoped_lock lock(getLock());
    for (auto it = mLocked.spotControllers.begin(); it != mLocked.spotControllers.end();) {
        int32_t displayID = it->first;
        if (!displayIdSet.count(displayID)) {
            /*
             * Ensures that an in-progress animation won't dereference
             * a null pointer to TouchSpotController.
             */
            mContext.removeAnimationCallback(displayID);
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

const ui::Transform& PointerController::getTransformForDisplayLocked(int displayId) const {
    const auto& di = mLocked.mDisplayInfos;
    auto it = std::find_if(di.begin(), di.end(), [displayId](const gui::DisplayInfo& info) {
        return info.displayId == displayId;
    });
    return it != di.end() ? it->transform : kIdentityTransform;
}

} // namespace android
