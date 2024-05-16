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

#include <input/Input.h>
#include <log/log.h>

namespace {
// Time to spend fading out the pointer completely.
const nsecs_t POINTER_FADE_DURATION = 500 * 1000000LL; // 500 ms
} // namespace

namespace android {

// --- MouseCursorController ---

MouseCursorController::MouseCursorController(PointerControllerContext& context)
      : mContext(context) {
    std::scoped_lock lock(mLock);

    mLocked.stylusHoverMode = false;

    mLocked.animationFrameIndex = 0;
    mLocked.lastFrameUpdatedTime = 0;

    mLocked.pointerFadeDirection = 0;
    mLocked.pointerX = 0;
    mLocked.pointerY = 0;
    mLocked.pointerAlpha = 0.0f; // pointer is initially faded
    mLocked.pointerSprite = mContext.getSpriteController().createSprite();
    mLocked.updatePointerIcon = false;
    mLocked.requestedPointerType = PointerIconStyle::TYPE_NOT_SPECIFIED;
    mLocked.resolvedPointerType = PointerIconStyle::TYPE_NOT_SPECIFIED;

    mLocked.resourcesLoaded = false;
}

MouseCursorController::~MouseCursorController() {
    std::scoped_lock lock(mLock);

    mLocked.pointerSprite.clear();
}

std::optional<FloatRect> MouseCursorController::getBounds() const {
    std::scoped_lock lock(mLock);

    return getBoundsLocked();
}

std::optional<FloatRect> MouseCursorController::getBoundsLocked() const REQUIRES(mLock) {
    if (!mLocked.viewport.isValid()) {
        return {};
    }

    return FloatRect{
            static_cast<float>(mLocked.viewport.logicalLeft),
            static_cast<float>(mLocked.viewport.logicalTop),
            static_cast<float>(mLocked.viewport.logicalRight - 1),
            static_cast<float>(mLocked.viewport.logicalBottom - 1),
    };
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

void MouseCursorController::setPosition(float x, float y) {
#if DEBUG_MOUSE_CURSOR_UPDATES
    ALOGD("Set pointer position to x=%0.3f, y=%0.3f", x, y);
#endif
    std::scoped_lock lock(mLock);
    setPositionLocked(x, y);
}

void MouseCursorController::setPositionLocked(float x, float y) REQUIRES(mLock) {
    const auto bounds = getBoundsLocked();
    if (!bounds) return;

    mLocked.pointerX = std::max(bounds->left, std::min(bounds->right, x));
    mLocked.pointerY = std::max(bounds->top, std::min(bounds->bottom, y));

    updatePointerLocked();
}

FloatPoint MouseCursorController::getPosition() const {
    std::scoped_lock lock(mLock);

    return {mLocked.pointerX, mLocked.pointerY};
}

ui::LogicalDisplayId MouseCursorController::getDisplayId() const {
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

void MouseCursorController::setStylusHoverMode(bool stylusHoverMode) {
    std::scoped_lock lock(mLock);

    if (mLocked.stylusHoverMode != stylusHoverMode) {
        mLocked.stylusHoverMode = stylusHoverMode;
        mLocked.updatePointerIcon = true;
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

    if (viewport.orientation == ui::ROTATION_90 || viewport.orientation == ui::ROTATION_270) {
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
        if (const auto bounds = getBoundsLocked(); bounds) {
            mLocked.pointerX = (bounds->left + bounds->right) * 0.5f;
            mLocked.pointerY = (bounds->top + bounds->bottom) * 0.5f;
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
            case ui::ROTATION_90:
                temp = x;
                x = oldViewport.deviceHeight - y;
                y = temp;
                break;
            case ui::ROTATION_180:
                x = oldViewport.deviceWidth - x;
                y = oldViewport.deviceHeight - y;
                break;
            case ui::ROTATION_270:
                temp = x;
                x = y;
                y = oldViewport.deviceWidth - temp;
                break;
            case ui::ROTATION_0:
                break;
        }

        // Perform the new rotation.
        switch (viewport.orientation) {
            case ui::ROTATION_90:
                temp = x;
                x = y;
                y = viewport.deviceHeight - temp;
                break;
            case ui::ROTATION_180:
                x = viewport.deviceWidth - x;
                y = viewport.deviceHeight - y;
                break;
            case ui::ROTATION_270:
                temp = x;
                x = viewport.deviceWidth - y;
                y = temp;
                break;
            case ui::ROTATION_0:
                break;
        }

        // Apply offsets to convert from the pixel center to the pixel top-left corner position
        // and save the results.
        mLocked.pointerX = x - 0.5f;
        mLocked.pointerY = y - 0.5f;
    }

    updatePointerLocked();
}

void MouseCursorController::updatePointerIcon(PointerIconStyle iconId) {
    std::scoped_lock lock(mLock);

    if (mLocked.requestedPointerType != iconId) {
        mLocked.requestedPointerType = iconId;
        mLocked.updatePointerIcon = true;
        updatePointerLocked();
    }
}

void MouseCursorController::setCustomPointerIcon(const SpriteIcon& icon) {
    std::scoped_lock lock(mLock);

    const PointerIconStyle iconId = mContext.getPolicy()->getCustomPointerIconId();
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
    std::map<PointerIconStyle, PointerAnimation>::const_iterator iter =
            mLocked.animationResources.find(mLocked.resolvedPointerType);
    if (iter == mLocked.animationResources.end()) {
        return false;
    }

    if (timestamp - mLocked.lastFrameUpdatedTime > iter->second.durationPerFrame) {
        auto& spriteController = mContext.getSpriteController();
        spriteController.openTransaction();

        int incr = (timestamp - mLocked.lastFrameUpdatedTime) / iter->second.durationPerFrame;
        mLocked.animationFrameIndex += incr;
        mLocked.lastFrameUpdatedTime += iter->second.durationPerFrame * incr;
        while (mLocked.animationFrameIndex >= iter->second.animationFrames.size()) {
            mLocked.animationFrameIndex -= iter->second.animationFrames.size();
        }
        mLocked.pointerSprite->setIcon(iter->second.animationFrames[mLocked.animationFrameIndex]);

        spriteController.closeTransaction();
    }
    // Keep animating.
    return true;
}

void MouseCursorController::updatePointerLocked() REQUIRES(mLock) {
    if (!mLocked.viewport.isValid()) {
        return;
    }
    auto& spriteController = mContext.getSpriteController();
    spriteController.openTransaction();

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
        mLocked.resolvedPointerType = mLocked.requestedPointerType;
        const PointerIconStyle defaultPointerIconId =
                mContext.getPolicy()->getDefaultPointerIconId();
        if (mLocked.resolvedPointerType == PointerIconStyle::TYPE_NOT_SPECIFIED) {
            mLocked.resolvedPointerType = mLocked.stylusHoverMode
                    ? mContext.getPolicy()->getDefaultStylusIconId()
                    : defaultPointerIconId;
        }

        if (mLocked.resolvedPointerType == defaultPointerIconId) {
            mLocked.pointerSprite->setIcon(mLocked.pointerIcon);
        } else {
            std::map<PointerIconStyle, SpriteIcon>::const_iterator iter =
                    mLocked.additionalMouseResources.find(mLocked.resolvedPointerType);
            if (iter != mLocked.additionalMouseResources.end()) {
                std::map<PointerIconStyle, PointerAnimation>::const_iterator anim_iter =
                        mLocked.animationResources.find(mLocked.resolvedPointerType);
                if (anim_iter != mLocked.animationResources.end()) {
                    mLocked.animationFrameIndex = 0;
                    mLocked.lastFrameUpdatedTime = systemTime(SYSTEM_TIME_MONOTONIC);
                    startAnimationLocked();
                }
                mLocked.pointerSprite->setIcon(iter->second);
            } else {
                ALOGW("Can't find the resource for icon id %d", mLocked.resolvedPointerType);
                mLocked.pointerSprite->setIcon(mLocked.pointerIcon);
            }
        }
        mLocked.updatePointerIcon = false;
    }

    spriteController.closeTransaction();
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
     * Using ui::LogicalDisplayId::INVALID for displayId here to avoid removing the callback
     * if a TouchSpotController with the same display is removed.
     */
    mContext.addAnimationCallback(ui::LogicalDisplayId::INVALID, func);
}

} // namespace android
