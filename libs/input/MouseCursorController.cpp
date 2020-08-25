/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "MouseCursorController"
//#define LOG_NDEBUG 0

// Log debug messages about pointer updates
#define DEBUG_MOUSE_CURSOR_UPDATES 0

#include "MouseCursorController.h"

#include <log/log.h>

#include <SkBitmap.h>
#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>

namespace {
// Time to spend fading out the pointer completely.
const nsecs_t POINTER_FADE_DURATION = 500 * 1000000LL; // 500 ms
} // namespace

namespace android {

// --- MouseCursorController ---

MouseCursorController::MouseCursorController(PointerControllerContext& context)
      : mContext(context) {
    std::scoped_lock lock(mLock);

    mLocked.animationFrameIndex = 0;
    mLocked.lastFrameUpdatedTime = 0;

    mLocked.pointerFadeDirection = 0;
    mLocked.pointerX = 0;
    mLocked.pointerY = 0;
    mLocked.pointerAlpha = 0.0f; // pointer is initially faded
    mLocked.pointerSprite = mContext.getSpriteController()->createSprite();
    mLocked.updatePointerIcon = false;
    mLocked.requestedPointerType = mContext.getPolicy()->getDefaultPointerIconId();

    mLocked.resourcesLoaded = false;

    mLocked.buttonState = 0;
}

MouseCursorController::~MouseCursorController() {
    std::scoped_lock lock(mLock);

    mLocked.pointerSprite.clear();
}

bool MouseCursorController::getBounds(float* outMinX, float* outMinY, float* outMaxX,
                                      float* outMaxY) const {
    std::scoped_lock lock(mLock);

    return getBoundsLocked(outMinX, outMinY, outMaxX, outMaxY);
}

bool MouseCursorController::getBoundsLocked(float* outMinX, float* outMinY, float* outMaxX,
                                            float* outMaxY) const REQUIRES(mLock) {
    if (!mLocked.viewport.isValid()) {
        return false;
    }

    *outMinX = mLocked.viewport.logicalLeft;
    *outMinY = mLocked.viewport.logicalTop;
    *outMaxX = mLocked.viewport.logicalRight - 1;
    *outMaxY = mLocked.viewport.logicalBottom - 1;
    return true;
}

void MouseCursorController::move(float deltaX, float deltaY) {
#if DEBUG_MOUSE_CURSOR_UPDATES
    ALOGD("Move pointer by deltaX=%0.3f, deltaY=%0.3f", deltaX, deltaY);
#endif
    if (deltaX == 0.0f && deltaY == 0.0f) {
        return;
    }

    std::scoped_lock lock(mLock);

    setPositionLocked(mLocked.pointerX + deltaX, mLocked.pointerY + deltaY);
}

void MouseCursorController::setButtonState(int32_t buttonState) {
#if DEBUG_MOUSE_CURSOR_UPDATES
    ALOGD("Set button state 0x%08x", buttonState);
#endif
    std::scoped_lock lock(mLock);

    if (mLocked.buttonState != buttonState) {
        mLocked.buttonState = buttonState;
    }
}

int32_t MouseCursorController::getButtonState() const {
    std::scoped_lock lock(mLock);
    return mLocked.buttonState;
}

void MouseCursorController::setPosition(float x, float y) {
#if DEBUG_MOUSE_CURSOR_UPDATES
    ALOGD("Set pointer position to x=%0.3f, y=%0.3f", x, y);
#endif
    std::scoped_lock lock(mLock);
    setPositionLocked(x, y);
}

void MouseCursorController::setPositionLocked(float x, float y) REQUIRES(mLock) {
    float minX, minY, maxX, maxY;
    if (getBoundsLocked(&minX, &minY, &maxX, &maxY)) {
        if (x <= minX) {
            mLocked.pointerX = minX;
        } else if (x >= maxX) {
            mLocked.pointerX = maxX;
        } else {
            mLocked.pointerX = x;
        }
        if (y <= minY) {
            mLocked.pointerY = minY;
        } else if (y >= maxY) {
            mLocked.pointerY = maxY;
        } else {
            mLocked.pointerY = y;
        }
        updatePointerLocked();
    }
}

void MouseCursorController::getPosition(float* outX, float* outY) const {
    std::scoped_lock lock(mLock);

    *outX = mLocked.pointerX;
    *outY = mLocked.pointerY;
}

int32_t MouseCursorController::getDisplayId() const {
    std::scoped_lock lock(mLock);
    return mLocked.viewport.displayId;
}

void MouseCursorController::fade(PointerControllerInterface::Transition transition) {
    std::scoped_lock lock(mLock);

    // Remove the inactivity timeout, since we are fading now.
    mContext.removeInactivityTimeout();

    // Start fading.
    if (transition == PointerControllerInterface::Transition::IMMEDIATE) {
        mLocked.pointerFadeDirection = 0;
        mLocked.pointerAlpha = 0.0f;
        updatePointerLocked();
    } else {
        mLocked.pointerFadeDirection = -1;
        startAnimationLocked();
    }
}

void MouseCursorController::unfade(PointerControllerInterface::Transition transition) {
    std::scoped_lock lock(mLock);

    // Always reset the inactivity timer.
    mContext.resetInactivityTimeout();

    // Start unfading.
    if (transition == PointerControllerInterface::Transition::IMMEDIATE) {
        mLocked.pointerFadeDirection = 0;
        mLocked.pointerAlpha = 1.0f;
        updatePointerLocked();
    } else {
        mLocked.pointerFadeDirection = 1;
        startAnimationLocked();
    }
}

void MouseCursorController::reloadPointerResources(bool getAdditionalMouseResources) {
    std::scoped_lock lock(mLock);

    loadResourcesLocked(getAdditionalMouseResources);
    updatePointerLocked();
}

/**
 * The viewport values for deviceHeight and deviceWidth have already been adjusted for rotation,
 * so here we are getting the dimensions in the original, unrotated orientation (orientation 0).
 */
static void getNonRotatedSize(const DisplayViewport& viewport, int32_t& width, int32_t& height) {
    width = viewport.deviceWidth;
    height = viewport.deviceHeight;

    if (viewport.orientation == DISPLAY_ORIENTATION_90 ||
        viewport.orientation == DISPLAY_ORIENTATION_270) {
        std::swap(width, height);
    }
}

void MouseCursorController::setDisplayViewport(const DisplayViewport& viewport,
                                               bool getAdditionalMouseResources) {
    std::scoped_lock lock(mLock);

    if (viewport == mLocked.viewport) {
        return;
    }

    const DisplayViewport oldViewport = mLocked.viewport;
    mLocked.viewport = viewport;

    int32_t oldDisplayWidth, oldDisplayHeight;
    getNonRotatedSize(oldViewport, oldDisplayWidth, oldDisplayHeight);
    int32_t newDisplayWidth, newDisplayHeight;
    getNonRotatedSize(viewport, newDisplayWidth, newDisplayHeight);

    // Reset cursor position to center if size or display changed.
    if (oldViewport.displayId != viewport.displayId || oldDisplayWidth != newDisplayWidth ||
        oldDisplayHeight != newDisplayHeight) {
        float minX, minY, maxX, maxY;
        if (getBoundsLocked(&minX, &minY, &maxX, &maxY)) {
            mLocked.pointerX = (minX + maxX) * 0.5f;
            mLocked.pointerY = (minY + maxY) * 0.5f;
            // Reload icon resources for density may be changed.
            loadResourcesLocked(getAdditionalMouseResources);
        } else {
            mLocked.pointerX = 0;
            mLocked.pointerY = 0;
        }
    } else if (oldViewport.orientation != viewport.orientation) {
        // Apply offsets to convert from the pixel top-left corner position to the pixel center.
        // This creates an invariant frame of reference that we can easily rotate when
        // taking into account that the pointer may be located at fractional pixel offsets.
        float x = mLocked.pointerX + 0.5f;
        float y = mLocked.pointerY + 0.5f;
        float temp;

        // Undo the previous rotation.
        switch (oldViewport.orientation) {
            case DISPLAY_ORIENTATION_90:
                temp = x;
                x = oldViewport.deviceHeight - y;
                y = temp;
                break;
            case DISPLAY_ORIENTATION_180:
                x = oldViewport.deviceWidth - x;
                y = oldViewport.deviceHeight - y;
                break;
            case DISPLAY_ORIENTATION_270:
                temp = x;
                x = y;
                y = oldViewport.deviceWidth - temp;
                break;
        }

        // Perform the new rotation.
        switch (viewport.orientation) {
            case DISPLAY_ORIENTATION_90:
                temp = x;
                x = y;
                y = viewport.deviceHeight - temp;
                break;
            case DISPLAY_ORIENTATION_180:
                x = viewport.deviceWidth - x;
                y = viewport.deviceHeight - y;
                break;
            case DISPLAY_ORIENTATION_270:
                temp = x;
                x = viewport.deviceWidth - y;
                y = temp;
                break;
        }

        // Apply offsets to convert from the pixel center to the pixel top-left corner position
        // and save the results.
        mLocked.pointerX = x - 0.5f;
        mLocked.pointerY = y - 0.5f;
    }

    updatePointerLocked();
}

void MouseCursorController::updatePointerIcon(int32_t iconId) {
    std::scoped_lock lock(mLock);

    if (mLocked.requestedPointerType != iconId) {
        mLocked.requestedPointerType = iconId;
        mLocked.updatePointerIcon = true;
        updatePointerLocked();
    }
}

void MouseCursorController::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock lock(mLock);

    const int32_t iconId = mContext.getPolicy()->getCustomPointerIconId();
    mLocked.additionalMouseResources[iconId] = icon;
    mLocked.requestedPointerType = iconId;
    mLocked.updatePointerIcon = true;
    updatePointerLocked();
}

bool MouseCursorController::doFadingAnimationLocked(nsecs_t timestamp) REQUIRES(mLock) {
    nsecs_t frameDelay = timestamp - mContext.getAnimationTime();
    bool keepAnimating = false;

    // Animate pointer fade.
    if (mLocked.pointerFadeDirection < 0) {
        mLocked.pointerAlpha -= float(frameDelay) / POINTER_FADE_DURATION;
        if (mLocked.pointerAlpha <= 0.0f) {
            mLocked.pointerAlpha = 0.0f;
            mLocked.pointerFadeDirection = 0;
        } else {
            keepAnimating = true;
        }
        updatePointerLocked();
    } else if (mLocked.pointerFadeDirection > 0) {
        mLocked.pointerAlpha += float(frameDelay) / POINTER_FADE_DURATION;
        if (mLocked.pointerAlpha >= 1.0f) {
            mLocked.pointerAlpha = 1.0f;
            mLocked.pointerFadeDirection = 0;
        } else {
            keepAnimating = true;
        }
        updatePointerLocked();
    }
    return keepAnimating;
}

bool MouseCursorController::doBitmapAnimationLocked(nsecs_t timestamp) REQUIRES(mLock) {
    std::map<int32_t, PointerAnimation>::const_iterator iter =
            mLocked.animationResources.find(mLocked.requestedPointerType);
    if (iter == mLocked.animationResources.end()) {
        return false;
    }

    if (timestamp - mLocked.lastFrameUpdatedTime > iter->second.durationPerFrame) {
        sp<SpriteController> spriteController = mContext.getSpriteController();
        spriteController->openTransaction();

        int incr = (timestamp - mLocked.lastFrameUpdatedTime) / iter->second.durationPerFrame;
        mLocked.animationFrameIndex += incr;
        mLocked.lastFrameUpdatedTime += iter->second.durationPerFrame * incr;
        while (mLocked.animationFrameIndex >= iter->second.animationFrames.size()) {
            mLocked.animationFrameIndex -= iter->second.animationFrames.size();
        }
        mLocked.pointerSprite->setIcon(iter->second.animationFrames[mLocked.animationFrameIndex]);

        spriteController->closeTransaction();
    }
    // Keep animating.
    return true;
}

void MouseCursorController::updatePointerLocked() REQUIRES(mLock) {
    if (!mLocked.viewport.isValid()) {
        return;
    }
    sp<SpriteController> spriteController = mContext.getSpriteController();
    spriteController->openTransaction();

    mLocked.pointerSprite->setLayer(Sprite::BASE_LAYER_POINTER);
    mLocked.pointerSprite->setPosition(mLocked.pointerX, mLocked.pointerY);
    mLocked.pointerSprite->setDisplayId(mLocked.viewport.displayId);

    if (mLocked.pointerAlpha > 0) {
        mLocked.pointerSprite->setAlpha(mLocked.pointerAlpha);
        mLocked.pointerSprite->setVisible(true);
    } else {
        mLocked.pointerSprite->setVisible(false);
    }

    if (mLocked.updatePointerIcon) {
        if (mLocked.requestedPointerType == mContext.getPolicy()->getDefaultPointerIconId()) {
            mLocked.pointerSprite->setIcon(mLocked.pointerIcon);
        } else {
            std::map<int32_t, SpriteIcon>::const_iterator iter =
                    mLocked.additionalMouseResources.find(mLocked.requestedPointerType);
            if (iter != mLocked.additionalMouseResources.end()) {
                std::map<int32_t, PointerAnimation>::const_iterator anim_iter =
                        mLocked.animationResources.find(mLocked.requestedPointerType);
                if (anim_iter != mLocked.animationResources.end()) {
                    mLocked.animationFrameIndex = 0;
                    mLocked.lastFrameUpdatedTime = systemTime(SYSTEM_TIME_MONOTONIC);
                    startAnimationLocked();
                }
                mLocked.pointerSprite->setIcon(iter->second);
            } else {
                ALOGW("Can't find the resource for icon id %d", mLocked.requestedPointerType);
                mLocked.pointerSprite->setIcon(mLocked.pointerIcon);
            }
        }
        mLocked.updatePointerIcon = false;
    }

    spriteController->closeTransaction();
}

void MouseCursorController::loadResourcesLocked(bool getAdditionalMouseResources) REQUIRES(mLock) {
    if (!mLocked.viewport.isValid()) {
        return;
    }

    if (!mLocked.resourcesLoaded) mLocked.resourcesLoaded = true;

    sp<PointerControllerPolicyInterface> policy = mContext.getPolicy();
    policy->loadPointerResources(&mResources, mLocked.viewport.displayId);
    policy->loadPointerIcon(&mLocked.pointerIcon, mLocked.viewport.displayId);

    mLocked.additionalMouseResources.clear();
    mLocked.animationResources.clear();
    if (getAdditionalMouseResources) {
        policy->loadAdditionalMouseResources(&mLocked.additionalMouseResources,
                                             &mLocked.animationResources,
                                             mLocked.viewport.displayId);
    }

    mLocked.updatePointerIcon = true;
}

bool MouseCursorController::isViewportValid() {
    std::scoped_lock lock(mLock);
    return mLocked.viewport.isValid();
}

void MouseCursorController::getAdditionalMouseResources() {
    std::scoped_lock lock(mLock);

    if (mLocked.additionalMouseResources.empty()) {
        mContext.getPolicy()->loadAdditionalMouseResources(&mLocked.additionalMouseResources,
                                                           &mLocked.animationResources,
                                                           mLocked.viewport.displayId);
    }
    mLocked.updatePointerIcon = true;
    updatePointerLocked();
}

bool MouseCursorController::resourcesLoaded() {
    std::scoped_lock lock(mLock);
    return mLocked.resourcesLoaded;
}

bool MouseCursorController::doAnimations(nsecs_t timestamp) {
    std::scoped_lock lock(mLock);
    bool keepFading = doFadingAnimationLocked(timestamp);
    bool keepBitmap = doBitmapAnimationLocked(timestamp);
    bool keepAnimating = keepFading || keepBitmap;
    if (!keepAnimating) {
        /*
         * We know that this callback will be removed before another
         * is added. mLock in PointerAnimator will not be released
         * until after this is removed, and adding another callback
         * requires that lock. Thus it's safe to set mLocked.animating
         * here.
         */
        mLocked.animating = false;
    }
    return keepAnimating;
}

void MouseCursorController::startAnimationLocked() REQUIRES(mLock) {
    using namespace std::placeholders;

    if (mLocked.animating) {
        return;
    }
    mLocked.animating = true;

    std::function<bool(nsecs_t)> func = std::bind(&MouseCursorController::doAnimations, this, _1);
    /*
     * Using -1 for displayId here to avoid removing the callback
     * if a TouchSpotController with the same display is removed.
     */
    mContext.addAnimationCallback(-1, func);
}

} // namespace android
