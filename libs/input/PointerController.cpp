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

// Log debug messages about pointer updates
#define DEBUG_POINTER_UPDATES 0

#include "PointerController.h"
#include "MouseCursorController.h"
#include "PointerControllerContext.h"
#include "TouchSpotController.h"

#include <log/log.h>

#include <SkBitmap.h>
#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>

namespace android {

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
      : mContext(policy, looper, spriteController, *this), mCursorController(mContext) {
    std::scoped_lock lock(mLock);
    mLocked.presentation = Presentation::SPOT;
}

bool PointerController::getBounds(float* outMinX, float* outMinY, float* outMaxX,
                                  float* outMaxY) const {
    return mCursorController.getBounds(outMinX, outMinY, outMaxX, outMaxY);
}

void PointerController::move(float deltaX, float deltaY) {
    mCursorController.move(deltaX, deltaY);
}

void PointerController::setButtonState(int32_t buttonState) {
    mCursorController.setButtonState(buttonState);
}

int32_t PointerController::getButtonState() const {
    return mCursorController.getButtonState();
}

void PointerController::setPosition(float x, float y) {
    std::scoped_lock lock(mLock);
    mCursorController.setPosition(x, y);
}

void PointerController::getPosition(float* outX, float* outY) const {
    mCursorController.getPosition(outX, outY);
}

int32_t PointerController::getDisplayId() const {
    return mCursorController.getDisplayId();
}

void PointerController::fade(Transition transition) {
    std::scoped_lock lock(mLock);
    mCursorController.fade(transition);
}

void PointerController::unfade(Transition transition) {
    std::scoped_lock lock(mLock);
    mCursorController.unfade(transition);
}

void PointerController::setPresentation(Presentation presentation) {
    std::scoped_lock lock(mLock);

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
    std::scoped_lock lock(mLock);
    auto it = mLocked.spotControllers.find(displayId);
    if (it == mLocked.spotControllers.end()) {
        mLocked.spotControllers.try_emplace(displayId, displayId, mContext);
    }
    mLocked.spotControllers.at(displayId).setSpots(spotCoords, spotIdToIndex, spotIdBits);
}

void PointerController::clearSpots() {
    std::scoped_lock lock(mLock);
    clearSpotsLocked();
}

void PointerController::clearSpotsLocked() REQUIRES(mLock) {
    for (auto& [displayID, spotController] : mLocked.spotControllers) {
        spotController.clearSpots();
    }
}

void PointerController::setInactivityTimeout(InactivityTimeout inactivityTimeout) {
    mContext.setInactivityTimeout(inactivityTimeout);
}

void PointerController::reloadPointerResources() {
    std::scoped_lock lock(mLock);

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
    std::scoped_lock lock(mLock);

    bool getAdditionalMouseResources = false;
    if (mLocked.presentation == PointerController::Presentation::POINTER) {
        getAdditionalMouseResources = true;
    }
    mCursorController.setDisplayViewport(viewport, getAdditionalMouseResources);
}

void PointerController::updatePointerIcon(int32_t iconId) {
    std::scoped_lock lock(mLock);
    mCursorController.updatePointerIcon(iconId);
}

void PointerController::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock lock(mLock);
    mCursorController.setCustomPointerIcon(icon);
}

void PointerController::doInactivityTimeout() {
    fade(Transition::GRADUAL);
}

void PointerController::onDisplayViewportsUpdated(std::vector<DisplayViewport>& viewports) {
    std::unordered_set<int32_t> displayIdSet;
    for (DisplayViewport viewport : viewports) {
        displayIdSet.insert(viewport.displayId);
    }

    std::scoped_lock lock(mLock);
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

} // namespace android
