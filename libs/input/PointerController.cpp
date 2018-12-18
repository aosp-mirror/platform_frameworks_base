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

#include <log/log.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>
#include <SkBlendMode.h>

namespace android {

// --- WeakLooperCallback ---

class WeakLooperCallback: public LooperCallback {
protected:
    virtual ~WeakLooperCallback() { }

public:
    WeakLooperCallback(const wp<LooperCallback>& callback) :
        mCallback(callback) {
    }

    virtual int handleEvent(int fd, int events, void* data) {
        sp<LooperCallback> callback = mCallback.promote();
        if (callback != NULL) {
            return callback->handleEvent(fd, events, data);
        }
        return 0; // the client is gone, remove the callback
    }

private:
    wp<LooperCallback> mCallback;
};

// --- PointerController ---

// Time to wait before starting the fade when the pointer is inactive.
static const nsecs_t INACTIVITY_TIMEOUT_DELAY_TIME_NORMAL = 15 * 1000 * 1000000LL; // 15 seconds
static const nsecs_t INACTIVITY_TIMEOUT_DELAY_TIME_SHORT = 3 * 1000 * 1000000LL; // 3 seconds

// Time to spend fading out the spot completely.
static const nsecs_t SPOT_FADE_DURATION = 200 * 1000000LL; // 200 ms

// Time to spend fading out the pointer completely.
static const nsecs_t POINTER_FADE_DURATION = 500 * 1000000LL; // 500 ms

// The number of events to be read at once for DisplayEventReceiver.
static const int EVENT_BUFFER_SIZE = 100;

// --- PointerController ---

PointerController::PointerController(const sp<PointerControllerPolicyInterface>& policy,
        const sp<Looper>& looper, const sp<SpriteController>& spriteController) :
        mPolicy(policy), mLooper(looper), mSpriteController(spriteController) {
    mHandler = new WeakMessageHandler(this);
    mCallback = new WeakLooperCallback(this);

    if (mDisplayEventReceiver.initCheck() == NO_ERROR) {
        mLooper->addFd(mDisplayEventReceiver.getFd(), Looper::POLL_CALLBACK,
                       Looper::EVENT_INPUT, mCallback, nullptr);
    } else {
        ALOGE("Failed to initialize DisplayEventReceiver.");
    }

    AutoMutex _l(mLock);

    mLocked.animationPending = false;

    mLocked.displayWidth = -1;
    mLocked.displayHeight = -1;
    mLocked.displayOrientation = DISPLAY_ORIENTATION_0;

    mLocked.presentation = PRESENTATION_POINTER;
    mLocked.presentationChanged = false;

    mLocked.inactivityTimeout = INACTIVITY_TIMEOUT_NORMAL;

    mLocked.pointerFadeDirection = 0;
    mLocked.pointerX = 0;
    mLocked.pointerY = 0;
    mLocked.pointerAlpha = 0.0f; // pointer is initially faded
    mLocked.pointerSprite = mSpriteController->createSprite();
    mLocked.pointerIconChanged = false;
    mLocked.requestedPointerType = mPolicy->getDefaultPointerIconId();

    mLocked.animationFrameIndex = 0;
    mLocked.lastFrameUpdatedTime = 0;

    mLocked.buttonState = 0;

    mPolicy->loadPointerIcon(&mLocked.pointerIcon);

    loadResources();

    if (mLocked.pointerIcon.isValid()) {
        mLocked.pointerIconChanged = true;
        updatePointerLocked();
    }
}

PointerController::~PointerController() {
    mLooper->removeMessages(mHandler);

    AutoMutex _l(mLock);

    mLocked.pointerSprite.clear();

    for (size_t i = 0; i < mLocked.spots.size(); i++) {
        delete mLocked.spots.itemAt(i);
    }
    mLocked.spots.clear();
    mLocked.recycledSprites.clear();
}

bool PointerController::getBounds(float* outMinX, float* outMinY,
        float* outMaxX, float* outMaxY) const {
    AutoMutex _l(mLock);

    return getBoundsLocked(outMinX, outMinY, outMaxX, outMaxY);
}

bool PointerController::getBoundsLocked(float* outMinX, float* outMinY,
        float* outMaxX, float* outMaxY) const {
    if (mLocked.displayWidth <= 0 || mLocked.displayHeight <= 0) {
        return false;
    }

    *outMinX = 0;
    *outMinY = 0;
    switch (mLocked.displayOrientation) {
    case DISPLAY_ORIENTATION_90:
    case DISPLAY_ORIENTATION_270:
        *outMaxX = mLocked.displayHeight - 1;
        *outMaxY = mLocked.displayWidth - 1;
        break;
    default:
        *outMaxX = mLocked.displayWidth - 1;
        *outMaxY = mLocked.displayHeight - 1;
        break;
    }
    return true;
}

void PointerController::move(float deltaX, float deltaY) {
#if DEBUG_POINTER_UPDATES
    ALOGD("Move pointer by deltaX=%0.3f, deltaY=%0.3f", deltaX, deltaY);
#endif
    if (deltaX == 0.0f && deltaY == 0.0f) {
        return;
    }

    AutoMutex _l(mLock);

    setPositionLocked(mLocked.pointerX + deltaX, mLocked.pointerY + deltaY);
}

void PointerController::setButtonState(int32_t buttonState) {
#if DEBUG_POINTER_UPDATES
    ALOGD("Set button state 0x%08x", buttonState);
#endif
    AutoMutex _l(mLock);

    if (mLocked.buttonState != buttonState) {
        mLocked.buttonState = buttonState;
    }
}

int32_t PointerController::getButtonState() const {
    AutoMutex _l(mLock);

    return mLocked.buttonState;
}

void PointerController::setPosition(float x, float y) {
#if DEBUG_POINTER_UPDATES
    ALOGD("Set pointer position to x=%0.3f, y=%0.3f", x, y);
#endif
    AutoMutex _l(mLock);

    setPositionLocked(x, y);
}

void PointerController::setPositionLocked(float x, float y) {
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

void PointerController::getPosition(float* outX, float* outY) const {
    AutoMutex _l(mLock);

    *outX = mLocked.pointerX;
    *outY = mLocked.pointerY;
}

void PointerController::fade(Transition transition) {
    AutoMutex _l(mLock);

    // Remove the inactivity timeout, since we are fading now.
    removeInactivityTimeoutLocked();

    // Start fading.
    if (transition == TRANSITION_IMMEDIATE) {
        mLocked.pointerFadeDirection = 0;
        mLocked.pointerAlpha = 0.0f;
        updatePointerLocked();
    } else {
        mLocked.pointerFadeDirection = -1;
        startAnimationLocked();
    }
}

void PointerController::unfade(Transition transition) {
    AutoMutex _l(mLock);

    // Always reset the inactivity timer.
    resetInactivityTimeoutLocked();

    // Start unfading.
    if (transition == TRANSITION_IMMEDIATE) {
        mLocked.pointerFadeDirection = 0;
        mLocked.pointerAlpha = 1.0f;
        updatePointerLocked();
    } else {
        mLocked.pointerFadeDirection = 1;
        startAnimationLocked();
    }
}

void PointerController::setPresentation(Presentation presentation) {
    AutoMutex _l(mLock);

    if (presentation == PRESENTATION_POINTER && mLocked.additionalMouseResources.empty()) {
        mPolicy->loadAdditionalMouseResources(&mLocked.additionalMouseResources,
                                              &mLocked.animationResources);
    }

    if (mLocked.presentation != presentation) {
        mLocked.presentation = presentation;
        mLocked.presentationChanged = true;

        if (presentation != PRESENTATION_SPOT) {
            fadeOutAndReleaseAllSpotsLocked();
        }

        updatePointerLocked();
    }
}

void PointerController::setSpots(const PointerCoords* spotCoords,
        const uint32_t* spotIdToIndex, BitSet32 spotIdBits) {
#if DEBUG_POINTER_UPDATES
    ALOGD("setSpots: idBits=%08x", spotIdBits.value);
    for (BitSet32 idBits(spotIdBits); !idBits.isEmpty(); ) {
        uint32_t id = idBits.firstMarkedBit();
        idBits.clearBit(id);
        const PointerCoords& c = spotCoords[spotIdToIndex[id]];
        ALOGD(" spot %d: position=(%0.3f, %0.3f), pressure=%0.3f", id,
                c.getAxisValue(AMOTION_EVENT_AXIS_X),
                c.getAxisValue(AMOTION_EVENT_AXIS_Y),
                c.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE));
    }
#endif

    AutoMutex _l(mLock);

    mSpriteController->openTransaction();

    // Add or move spots for fingers that are down.
    for (BitSet32 idBits(spotIdBits); !idBits.isEmpty(); ) {
        uint32_t id = idBits.clearFirstMarkedBit();
        const PointerCoords& c = spotCoords[spotIdToIndex[id]];
        const SpriteIcon& icon = c.getAxisValue(AMOTION_EVENT_AXIS_PRESSURE) > 0
                ? mResources.spotTouch : mResources.spotHover;
        float x = c.getAxisValue(AMOTION_EVENT_AXIS_X);
        float y = c.getAxisValue(AMOTION_EVENT_AXIS_Y);

        Spot* spot = getSpotLocked(id);
        if (!spot) {
            spot = createAndAddSpotLocked(id);
        }

        spot->updateSprite(&icon, x, y);
    }

    // Remove spots for fingers that went up.
    for (size_t i = 0; i < mLocked.spots.size(); i++) {
        Spot* spot = mLocked.spots.itemAt(i);
        if (spot->id != Spot::INVALID_ID
                && !spotIdBits.hasBit(spot->id)) {
            fadeOutAndReleaseSpotLocked(spot);
        }
    }

    mSpriteController->closeTransaction();
}

void PointerController::clearSpots() {
#if DEBUG_POINTER_UPDATES
    ALOGD("clearSpots");
#endif

    AutoMutex _l(mLock);

    fadeOutAndReleaseAllSpotsLocked();
}

void PointerController::setInactivityTimeout(InactivityTimeout inactivityTimeout) {
    AutoMutex _l(mLock);

    if (mLocked.inactivityTimeout != inactivityTimeout) {
        mLocked.inactivityTimeout = inactivityTimeout;
        resetInactivityTimeoutLocked();
    }
}

void PointerController::reloadPointerResources() {
    AutoMutex _l(mLock);

    loadResources();

    if (mLocked.presentation == PRESENTATION_POINTER) {
        mLocked.additionalMouseResources.clear();
        mLocked.animationResources.clear();
        mPolicy->loadPointerIcon(&mLocked.pointerIcon);
        mPolicy->loadAdditionalMouseResources(&mLocked.additionalMouseResources,
                                              &mLocked.animationResources);
    }

    mLocked.presentationChanged = true;
    updatePointerLocked();
}

void PointerController::setDisplayViewport(int32_t width, int32_t height, int32_t orientation) {
    AutoMutex _l(mLock);

    // Adjust to use the display's unrotated coordinate frame.
    if (orientation == DISPLAY_ORIENTATION_90
            || orientation == DISPLAY_ORIENTATION_270) {
        int32_t temp = height;
        height = width;
        width = temp;
    }

    if (mLocked.displayWidth != width || mLocked.displayHeight != height) {
        mLocked.displayWidth = width;
        mLocked.displayHeight = height;

        float minX, minY, maxX, maxY;
        if (getBoundsLocked(&minX, &minY, &maxX, &maxY)) {
            mLocked.pointerX = (minX + maxX) * 0.5f;
            mLocked.pointerY = (minY + maxY) * 0.5f;
        } else {
            mLocked.pointerX = 0;
            mLocked.pointerY = 0;
        }

        fadeOutAndReleaseAllSpotsLocked();
    }

    if (mLocked.displayOrientation != orientation) {
        // Apply offsets to convert from the pixel top-left corner position to the pixel center.
        // This creates an invariant frame of reference that we can easily rotate when
        // taking into account that the pointer may be located at fractional pixel offsets.
        float x = mLocked.pointerX + 0.5f;
        float y = mLocked.pointerY + 0.5f;
        float temp;

        // Undo the previous rotation.
        switch (mLocked.displayOrientation) {
        case DISPLAY_ORIENTATION_90:
            temp = x;
            x = mLocked.displayWidth - y;
            y = temp;
            break;
        case DISPLAY_ORIENTATION_180:
            x = mLocked.displayWidth - x;
            y = mLocked.displayHeight - y;
            break;
        case DISPLAY_ORIENTATION_270:
            temp = x;
            x = y;
            y = mLocked.displayHeight - temp;
            break;
        }

        // Perform the new rotation.
        switch (orientation) {
        case DISPLAY_ORIENTATION_90:
            temp = x;
            x = y;
            y = mLocked.displayWidth - temp;
            break;
        case DISPLAY_ORIENTATION_180:
            x = mLocked.displayWidth - x;
            y = mLocked.displayHeight - y;
            break;
        case DISPLAY_ORIENTATION_270:
            temp = x;
            x = mLocked.displayHeight - y;
            y = temp;
            break;
        }

        // Apply offsets to convert from the pixel center to the pixel top-left corner position
        // and save the results.
        mLocked.pointerX = x - 0.5f;
        mLocked.pointerY = y - 0.5f;
        mLocked.displayOrientation = orientation;
    }

    updatePointerLocked();
}

void PointerController::updatePointerIcon(int32_t iconId) {
    AutoMutex _l(mLock);
    if (mLocked.requestedPointerType != iconId) {
        mLocked.requestedPointerType = iconId;
        mLocked.presentationChanged = true;
        updatePointerLocked();
    }
}

void PointerController::setCustomPointerIcon(const SpriteIcon& icon) {
    AutoMutex _l(mLock);

    const int32_t iconId = mPolicy->getCustomPointerIconId();
    mLocked.additionalMouseResources[iconId] = icon;
    mLocked.requestedPointerType = iconId;
    mLocked.presentationChanged = true;

    updatePointerLocked();
}

void PointerController::handleMessage(const Message& message) {
    switch (message.what) {
    case MSG_INACTIVITY_TIMEOUT:
        doInactivityTimeout();
        break;
    }
}

int PointerController::handleEvent(int /* fd */, int events, void* /* data */) {
    if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
        ALOGE("Display event receiver pipe was closed or an error occurred.  "
              "events=0x%x", events);
        return 0; // remove the callback
    }

    if (!(events & Looper::EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  "
              "events=0x%x", events);
        return 1; // keep the callback
    }

    bool gotVsync = false;
    ssize_t n;
    nsecs_t timestamp;
    DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
    while ((n = mDisplayEventReceiver.getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
        for (size_t i = 0; i < static_cast<size_t>(n); ++i) {
            if (buf[i].header.type == DisplayEventReceiver::DISPLAY_EVENT_VSYNC) {
                timestamp = buf[i].header.timestamp;
                gotVsync = true;
            }
        }
    }
    if (gotVsync) {
        doAnimate(timestamp);
    }
    return 1;  // keep the callback
}

void PointerController::doAnimate(nsecs_t timestamp) {
    AutoMutex _l(mLock);

    mLocked.animationPending = false;

    bool keepFading = doFadingAnimationLocked(timestamp);
    bool keepBitmapFlipping = doBitmapAnimationLocked(timestamp);
    if (keepFading || keepBitmapFlipping) {
        startAnimationLocked();
    }
}

bool PointerController::doFadingAnimationLocked(nsecs_t timestamp) {
    bool keepAnimating = false;
    nsecs_t frameDelay = timestamp - mLocked.animationTime;

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

    // Animate spots that are fading out and being removed.
    for (size_t i = 0; i < mLocked.spots.size();) {
        Spot* spot = mLocked.spots.itemAt(i);
        if (spot->id == Spot::INVALID_ID) {
            spot->alpha -= float(frameDelay) / SPOT_FADE_DURATION;
            if (spot->alpha <= 0) {
                mLocked.spots.removeAt(i);
                releaseSpotLocked(spot);
                continue;
            } else {
                spot->sprite->setAlpha(spot->alpha);
                keepAnimating = true;
            }
        }
        ++i;
    }
    return keepAnimating;
}

bool PointerController::doBitmapAnimationLocked(nsecs_t timestamp) {
    std::map<int32_t, PointerAnimation>::const_iterator iter = mLocked.animationResources.find(
            mLocked.requestedPointerType);
    if (iter == mLocked.animationResources.end()) {
        return false;
    }

    if (timestamp - mLocked.lastFrameUpdatedTime > iter->second.durationPerFrame) {
        mSpriteController->openTransaction();

        int incr = (timestamp - mLocked.lastFrameUpdatedTime) / iter->second.durationPerFrame;
        mLocked.animationFrameIndex += incr;
        mLocked.lastFrameUpdatedTime += iter->second.durationPerFrame * incr;
        while (mLocked.animationFrameIndex >= iter->second.animationFrames.size()) {
            mLocked.animationFrameIndex -= iter->second.animationFrames.size();
        }
        mLocked.pointerSprite->setIcon(iter->second.animationFrames[mLocked.animationFrameIndex]);

        mSpriteController->closeTransaction();
    }

    // Keep animating.
    return true;
}

void PointerController::doInactivityTimeout() {
    fade(TRANSITION_GRADUAL);
}

void PointerController::startAnimationLocked() {
    if (!mLocked.animationPending) {
        mLocked.animationPending = true;
        mLocked.animationTime = systemTime(SYSTEM_TIME_MONOTONIC);
        mDisplayEventReceiver.requestNextVsync();
    }
}

void PointerController::resetInactivityTimeoutLocked() {
    mLooper->removeMessages(mHandler, MSG_INACTIVITY_TIMEOUT);

    nsecs_t timeout = mLocked.inactivityTimeout == INACTIVITY_TIMEOUT_SHORT
            ? INACTIVITY_TIMEOUT_DELAY_TIME_SHORT : INACTIVITY_TIMEOUT_DELAY_TIME_NORMAL;
    mLooper->sendMessageDelayed(timeout, mHandler, MSG_INACTIVITY_TIMEOUT);
}

void PointerController::removeInactivityTimeoutLocked() {
    mLooper->removeMessages(mHandler, MSG_INACTIVITY_TIMEOUT);
}

void PointerController::updatePointerLocked() {
    mSpriteController->openTransaction();

    mLocked.pointerSprite->setLayer(Sprite::BASE_LAYER_POINTER);
    mLocked.pointerSprite->setPosition(mLocked.pointerX, mLocked.pointerY);

    if (mLocked.pointerAlpha > 0) {
        mLocked.pointerSprite->setAlpha(mLocked.pointerAlpha);
        mLocked.pointerSprite->setVisible(true);
    } else {
        mLocked.pointerSprite->setVisible(false);
    }

    if (mLocked.pointerIconChanged || mLocked.presentationChanged) {
        if (mLocked.presentation == PRESENTATION_POINTER) {
            if (mLocked.requestedPointerType == mPolicy->getDefaultPointerIconId()) {
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
        } else {
            mLocked.pointerSprite->setIcon(mResources.spotAnchor);
        }
        mLocked.pointerIconChanged = false;
        mLocked.presentationChanged = false;
    }

    mSpriteController->closeTransaction();
}

PointerController::Spot* PointerController::getSpotLocked(uint32_t id) {
    for (size_t i = 0; i < mLocked.spots.size(); i++) {
        Spot* spot = mLocked.spots.itemAt(i);
        if (spot->id == id) {
            return spot;
        }
    }
    return NULL;
}

PointerController::Spot* PointerController::createAndAddSpotLocked(uint32_t id) {
    // Remove spots until we have fewer than MAX_SPOTS remaining.
    while (mLocked.spots.size() >= MAX_SPOTS) {
        Spot* spot = removeFirstFadingSpotLocked();
        if (!spot) {
            spot = mLocked.spots.itemAt(0);
            mLocked.spots.removeAt(0);
        }
        releaseSpotLocked(spot);
    }

    // Obtain a sprite from the recycled pool.
    sp<Sprite> sprite;
    if (! mLocked.recycledSprites.isEmpty()) {
        sprite = mLocked.recycledSprites.top();
        mLocked.recycledSprites.pop();
    } else {
        sprite = mSpriteController->createSprite();
    }

    // Return the new spot.
    Spot* spot = new Spot(id, sprite);
    mLocked.spots.push(spot);
    return spot;
}

PointerController::Spot* PointerController::removeFirstFadingSpotLocked() {
    for (size_t i = 0; i < mLocked.spots.size(); i++) {
        Spot* spot = mLocked.spots.itemAt(i);
        if (spot->id == Spot::INVALID_ID) {
            mLocked.spots.removeAt(i);
            return spot;
        }
    }
    return NULL;
}

void PointerController::releaseSpotLocked(Spot* spot) {
    spot->sprite->clearIcon();

    if (mLocked.recycledSprites.size() < MAX_RECYCLED_SPRITES) {
        mLocked.recycledSprites.push(spot->sprite);
    }

    delete spot;
}

void PointerController::fadeOutAndReleaseSpotLocked(Spot* spot) {
    if (spot->id != Spot::INVALID_ID) {
        spot->id = Spot::INVALID_ID;
        startAnimationLocked();
    }
}

void PointerController::fadeOutAndReleaseAllSpotsLocked() {
    for (size_t i = 0; i < mLocked.spots.size(); i++) {
        Spot* spot = mLocked.spots.itemAt(i);
        fadeOutAndReleaseSpotLocked(spot);
    }
}

void PointerController::loadResources() {
    mPolicy->loadPointerResources(&mResources);
}


// --- PointerController::Spot ---

void PointerController::Spot::updateSprite(const SpriteIcon* icon, float x, float y) {
    sprite->setLayer(Sprite::BASE_LAYER_SPOT + id);
    sprite->setAlpha(alpha);
    sprite->setTransformationMatrix(SpriteTransformationMatrix(scale, 0.0f, 0.0f, scale));
    sprite->setPosition(x, y);

    this->x = x;
    this->y = y;

    if (icon != lastIcon) {
        lastIcon = icon;
        if (icon) {
            sprite->setIcon(*icon);
            sprite->setVisible(true);
        } else {
            sprite->setVisible(false);
        }
    }
}

} // namespace android
