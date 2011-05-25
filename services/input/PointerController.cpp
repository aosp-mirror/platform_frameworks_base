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

#include <cutils/log.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>
#include <SkXfermode.h>

namespace android {

// --- PointerController ---

// Time to wait before starting the fade when the pointer is inactive.
static const nsecs_t INACTIVITY_FADE_DELAY_TIME_NORMAL = 15 * 1000 * 1000000LL; // 15 seconds
static const nsecs_t INACTIVITY_FADE_DELAY_TIME_SHORT = 3 * 1000 * 1000000LL; // 3 seconds

// Time to spend fading out the pointer completely.
static const nsecs_t FADE_DURATION = 500 * 1000000LL; // 500 ms

// Time to wait between frames.
static const nsecs_t FADE_FRAME_INTERVAL = 1000000000LL / 60;

// Amount to subtract from alpha per frame.
static const float FADE_DECAY_PER_FRAME = float(FADE_FRAME_INTERVAL) / FADE_DURATION;


PointerController::PointerController(const sp<Looper>& looper,
        const sp<SpriteController>& spriteController) :
        mLooper(looper), mSpriteController(spriteController) {
    mHandler = new WeakMessageHandler(this);

    AutoMutex _l(mLock);

    mLocked.displayWidth = -1;
    mLocked.displayHeight = -1;
    mLocked.displayOrientation = DISPLAY_ORIENTATION_0;

    mLocked.pointerX = 0;
    mLocked.pointerY = 0;
    mLocked.buttonState = 0;

    mLocked.fadeAlpha = 1;
    mLocked.inactivityFadeDelay = INACTIVITY_FADE_DELAY_NORMAL;

    mLocked.visible = false;

    mLocked.sprite = mSpriteController->createSprite();
}

PointerController::~PointerController() {
    mLooper->removeMessages(mHandler);

    AutoMutex _l(mLock);

    mLocked.sprite.clear();
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
    LOGD("Move pointer by deltaX=%0.3f, deltaY=%0.3f", deltaX, deltaY);
#endif
    if (deltaX == 0.0f && deltaY == 0.0f) {
        return;
    }

    AutoMutex _l(mLock);

    setPositionLocked(mLocked.pointerX + deltaX, mLocked.pointerY + deltaY);
}

void PointerController::setButtonState(uint32_t buttonState) {
#if DEBUG_POINTER_UPDATES
    LOGD("Set button state 0x%08x", buttonState);
#endif
    AutoMutex _l(mLock);

    if (mLocked.buttonState != buttonState) {
        mLocked.buttonState = buttonState;
        unfadeBeforeUpdateLocked();
        updateLocked();
    }
}

uint32_t PointerController::getButtonState() const {
    AutoMutex _l(mLock);

    return mLocked.buttonState;
}

void PointerController::setPosition(float x, float y) {
#if DEBUG_POINTER_UPDATES
    LOGD("Set pointer position to x=%0.3f, y=%0.3f", x, y);
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
        unfadeBeforeUpdateLocked();
        updateLocked();
    }
}

void PointerController::getPosition(float* outX, float* outY) const {
    AutoMutex _l(mLock);

    *outX = mLocked.pointerX;
    *outY = mLocked.pointerY;
}

void PointerController::fade() {
    AutoMutex _l(mLock);

    startFadeLocked();
}

void PointerController::unfade() {
    AutoMutex _l(mLock);

    if (unfadeBeforeUpdateLocked()) {
        updateLocked();
    }
}

void PointerController::setInactivityFadeDelay(InactivityFadeDelay inactivityFadeDelay) {
    AutoMutex _l(mLock);

    if (mLocked.inactivityFadeDelay != inactivityFadeDelay) {
        mLocked.inactivityFadeDelay = inactivityFadeDelay;
        startInactivityFadeDelayLocked();
    }
}

void PointerController::updateLocked() {
    mLocked.sprite->openTransaction();
    mLocked.sprite->setPosition(mLocked.pointerX, mLocked.pointerY);
    mLocked.sprite->setAlpha(mLocked.fadeAlpha);
    mLocked.sprite->setVisible(mLocked.visible);
    mLocked.sprite->closeTransaction();
}

void PointerController::setDisplaySize(int32_t width, int32_t height) {
    AutoMutex _l(mLock);

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

        updateLocked();
    }
}

void PointerController::setDisplayOrientation(int32_t orientation) {
    AutoMutex _l(mLock);

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

        updateLocked();
    }
}

void PointerController::setPointerIcon(const SkBitmap* bitmap, float hotSpotX, float hotSpotY) {
    AutoMutex _l(mLock);

    mLocked.sprite->setBitmap(bitmap, hotSpotX, hotSpotY);
}

void PointerController::handleMessage(const Message& message) {
    switch (message.what) {
    case MSG_FADE_STEP: {
        AutoMutex _l(mLock);
        fadeStepLocked();
        break;
    }
    }
}

bool PointerController::unfadeBeforeUpdateLocked() {
    sendFadeStepMessageDelayedLocked(getInactivityFadeDelayTimeLocked());

    if (isFadingLocked()) {
        mLocked.visible = true;
        mLocked.fadeAlpha = 1;
        return true; // update required to effect the unfade
    }
    return false; // update not required
}

void PointerController::startFadeLocked() {
    if (!isFadingLocked()) {
        sendFadeStepMessageDelayedLocked(0);
    }
}

void PointerController::startInactivityFadeDelayLocked() {
    if (!isFadingLocked()) {
        sendFadeStepMessageDelayedLocked(getInactivityFadeDelayTimeLocked());
    }
}

void PointerController::fadeStepLocked() {
    if (mLocked.visible) {
        mLocked.fadeAlpha -= FADE_DECAY_PER_FRAME;
        if (mLocked.fadeAlpha < 0) {
            mLocked.fadeAlpha = 0;
            mLocked.visible = false;
        } else {
            sendFadeStepMessageDelayedLocked(FADE_FRAME_INTERVAL);
        }
        updateLocked();
    }
}

bool PointerController::isFadingLocked() {
    return !mLocked.visible || mLocked.fadeAlpha != 1;
}

nsecs_t PointerController::getInactivityFadeDelayTimeLocked() {
    return mLocked.inactivityFadeDelay == INACTIVITY_FADE_DELAY_SHORT
            ? INACTIVITY_FADE_DELAY_TIME_SHORT : INACTIVITY_FADE_DELAY_TIME_NORMAL;
}

void PointerController::sendFadeStepMessageDelayedLocked(nsecs_t delayTime) {
    mLooper->removeMessages(mHandler, MSG_FADE_STEP);
    mLooper->sendMessageDelayed(delayTime, mHandler, Message(MSG_FADE_STEP));
}

} // namespace android
