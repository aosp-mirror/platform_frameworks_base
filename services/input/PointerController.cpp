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


PointerController::PointerController(const sp<Looper>& looper, int32_t pointerLayer) :
        mLooper(looper), mPointerLayer(pointerLayer) {
    AutoMutex _l(mLock);

    mLocked.displayWidth = -1;
    mLocked.displayHeight = -1;
    mLocked.displayOrientation = DISPLAY_ORIENTATION_0;

    mLocked.pointerX = 0;
    mLocked.pointerY = 0;
    mLocked.buttonState = 0;

    mLocked.iconBitmap = NULL;
    mLocked.iconHotSpotX = 0;
    mLocked.iconHotSpotY = 0;

    mLocked.fadeAlpha = 1;
    mLocked.inactivityFadeDelay = INACTIVITY_FADE_DELAY_NORMAL;

    mLocked.wantVisible = false;
    mLocked.visible = false;
    mLocked.drawn = false;

    mHandler = new WeakMessageHandler(this);
}

PointerController::~PointerController() {
    mLooper->removeMessages(mHandler);

    if (mSurfaceControl != NULL) {
        mSurfaceControl->clear();
        mSurfaceControl.clear();
    }

    if (mSurfaceComposerClient != NULL) {
        mSurfaceComposerClient->dispose();
        mSurfaceComposerClient.clear();
    }

    delete mLocked.iconBitmap;
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
        *outMaxX = mLocked.displayHeight;
        *outMaxY = mLocked.displayWidth;
        break;
    default:
        *outMaxX = mLocked.displayWidth;
        *outMaxY = mLocked.displayHeight;
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
    bool wantVisibleAndHavePointerIcon = mLocked.wantVisible && mLocked.iconBitmap;

    if (wantVisibleAndHavePointerIcon) {
        // Want the pointer to be visible.
        // Ensure the surface is created and drawn.
        if (!createSurfaceIfNeededLocked() || !drawPointerIfNeededLocked()) {
            return;
        }
    } else {
        // Don't want the pointer to be visible.
        // If it is not visible then we are done.
        if (mSurfaceControl == NULL || !mLocked.visible) {
            return;
        }
    }

    status_t status = mSurfaceComposerClient->openTransaction();
    if (status) {
        LOGE("Error opening surface transaction to update pointer surface.");
        return;
    }

    if (wantVisibleAndHavePointerIcon) {
        status = mSurfaceControl->setPosition(
                mLocked.pointerX - mLocked.iconHotSpotX,
                mLocked.pointerY - mLocked.iconHotSpotY);
        if (status) {
            LOGE("Error %d moving pointer surface.", status);
            goto CloseTransaction;
        }

        status = mSurfaceControl->setAlpha(mLocked.fadeAlpha);
        if (status) {
            LOGE("Error %d setting pointer surface alpha.", status);
            goto CloseTransaction;
        }

        if (!mLocked.visible) {
            status = mSurfaceControl->setLayer(mPointerLayer);
            if (status) {
                LOGE("Error %d setting pointer surface layer.", status);
                goto CloseTransaction;
            }

            status = mSurfaceControl->show(mPointerLayer);
            if (status) {
                LOGE("Error %d showing pointer surface.", status);
                goto CloseTransaction;
            }

            mLocked.visible = true;
        }
    } else {
        if (mLocked.visible) {
            status = mSurfaceControl->hide();
            if (status) {
                LOGE("Error %d hiding pointer surface.", status);
                goto CloseTransaction;
            }

            mLocked.visible = false;
        }
    }

CloseTransaction:
    status = mSurfaceComposerClient->closeTransaction();
    if (status) {
        LOGE("Error closing surface transaction to update pointer surface.");
    }
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
        float absoluteX, absoluteY;

        // Map from oriented display coordinates to absolute display coordinates.
        switch (mLocked.displayOrientation) {
        case DISPLAY_ORIENTATION_90:
            absoluteX = mLocked.displayWidth - mLocked.pointerY;
            absoluteY = mLocked.pointerX;
            break;
        case DISPLAY_ORIENTATION_180:
            absoluteX = mLocked.displayWidth - mLocked.pointerX;
            absoluteY = mLocked.displayHeight - mLocked.pointerY;
            break;
        case DISPLAY_ORIENTATION_270:
            absoluteX = mLocked.pointerY;
            absoluteY = mLocked.displayHeight - mLocked.pointerX;
            break;
        default:
            absoluteX = mLocked.pointerX;
            absoluteY = mLocked.pointerY;
            break;
        }

        // Map from absolute display coordinates to oriented display coordinates.
        switch (orientation) {
        case DISPLAY_ORIENTATION_90:
            mLocked.pointerX = absoluteY;
            mLocked.pointerY = mLocked.displayWidth - absoluteX;
            break;
        case DISPLAY_ORIENTATION_180:
            mLocked.pointerX = mLocked.displayWidth - absoluteX;
            mLocked.pointerY = mLocked.displayHeight - absoluteY;
            break;
        case DISPLAY_ORIENTATION_270:
            mLocked.pointerX = mLocked.displayHeight - absoluteY;
            mLocked.pointerY = absoluteX;
            break;
        default:
            mLocked.pointerX = absoluteX;
            mLocked.pointerY = absoluteY;
            break;
        }

        mLocked.displayOrientation = orientation;

        updateLocked();
    }
}

void PointerController::setPointerIcon(const SkBitmap* bitmap, float hotSpotX, float hotSpotY) {
    AutoMutex _l(mLock);

    if (mLocked.iconBitmap) {
        delete mLocked.iconBitmap;
        mLocked.iconBitmap = NULL;
    }

    if (bitmap) {
        mLocked.iconBitmap = new SkBitmap();
        bitmap->copyTo(mLocked.iconBitmap, SkBitmap::kARGB_8888_Config);
    }

    mLocked.iconHotSpotX = hotSpotX;
    mLocked.iconHotSpotY = hotSpotY;
    mLocked.drawn = false;
}

bool PointerController::createSurfaceIfNeededLocked() {
    if (!mLocked.iconBitmap) {
        // If we don't have a pointer icon, then no point allocating a surface now.
        return false;
    }

    if (mSurfaceComposerClient == NULL) {
        mSurfaceComposerClient = new SurfaceComposerClient();
    }

    if (mSurfaceControl == NULL) {
        mSurfaceControl = mSurfaceComposerClient->createSurface(getpid(),
                String8("Pointer Icon"), 0,
                mLocked.iconBitmap->width(), mLocked.iconBitmap->height(),
                PIXEL_FORMAT_RGBA_8888);
        if (mSurfaceControl == NULL) {
            LOGE("Error creating pointer surface.");
            return false;
        }
    }
    return true;
}

bool PointerController::drawPointerIfNeededLocked() {
    if (!mLocked.drawn) {
        if (!mLocked.iconBitmap) {
            return false;
        }

        if (!resizeSurfaceLocked(mLocked.iconBitmap->width(), mLocked.iconBitmap->height())) {
            return false;
        }

        sp<Surface> surface = mSurfaceControl->getSurface();

        Surface::SurfaceInfo surfaceInfo;
        status_t status = surface->lock(&surfaceInfo);
        if (status) {
            LOGE("Error %d locking pointer surface before drawing.", status);
            return false;
        }

        SkBitmap surfaceBitmap;
        ssize_t bpr = surfaceInfo.s * bytesPerPixel(surfaceInfo.format);
        surfaceBitmap.setConfig(SkBitmap::kARGB_8888_Config, surfaceInfo.w, surfaceInfo.h, bpr);
        surfaceBitmap.setPixels(surfaceInfo.bits);

        SkCanvas surfaceCanvas;
        surfaceCanvas.setBitmapDevice(surfaceBitmap);

        SkPaint paint;
        paint.setXfermodeMode(SkXfermode::kSrc_Mode);
        surfaceCanvas.drawBitmap(*mLocked.iconBitmap, 0, 0, &paint);

        status = surface->unlockAndPost();
        if (status) {
            LOGE("Error %d unlocking pointer surface after drawing.", status);
            return false;
        }
    }

    mLocked.drawn = true;
    return true;
}

bool PointerController::resizeSurfaceLocked(int32_t width, int32_t height) {
    status_t status = mSurfaceComposerClient->openTransaction();
    if (status) {
        LOGE("Error opening surface transaction to resize pointer surface.");
        return false;
    }

    status = mSurfaceControl->setSize(width, height);
    if (status) {
        LOGE("Error %d setting pointer surface size.", status);
        return false;
    }

    status = mSurfaceComposerClient->closeTransaction();
    if (status) {
        LOGE("Error closing surface transaction to resize pointer surface.");
        return false;
    }

    return true;
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
        mLocked.wantVisible = true;
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
    if (mLocked.wantVisible) {
        mLocked.fadeAlpha -= FADE_DECAY_PER_FRAME;
        if (mLocked.fadeAlpha < 0) {
            mLocked.fadeAlpha = 0;
            mLocked.wantVisible = false;
        } else {
            sendFadeStepMessageDelayedLocked(FADE_FRAME_INTERVAL);
        }
        updateLocked();
    }
}

bool PointerController::isFadingLocked() {
    return !mLocked.wantVisible || mLocked.fadeAlpha != 1;
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
