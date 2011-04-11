/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "Sprites"

//#define LOG_NDEBUG 0

#include "SpriteController.h"

#include <cutils/log.h>
#include <utils/String8.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>
#include <SkXfermode.h>

namespace android {

// --- SpriteController ---

SpriteController::SpriteController(const sp<Looper>& looper, int32_t overlayLayer) :
        mLooper(looper), mOverlayLayer(overlayLayer) {
    mHandler = new WeakMessageHandler(this);
}

SpriteController::~SpriteController() {
    mLooper->removeMessages(mHandler);

    if (mSurfaceComposerClient != NULL) {
        mSurfaceComposerClient->dispose();
        mSurfaceComposerClient.clear();
    }
}

sp<Sprite> SpriteController::createSprite() {
    return new SpriteImpl(this);
}

void SpriteController::invalidateSpriteLocked(const sp<SpriteImpl>& sprite) {
    bool wasEmpty = mInvalidatedSprites.isEmpty();
    mInvalidatedSprites.push(sprite);
    if (wasEmpty) {
        mLooper->sendMessage(mHandler, Message(MSG_UPDATE_SPRITES));
    }
}

void SpriteController::disposeSurfaceLocked(const sp<SurfaceControl>& surfaceControl) {
    bool wasEmpty = mDisposedSurfaces.isEmpty();
    mDisposedSurfaces.push(surfaceControl);
    if (wasEmpty) {
        mLooper->sendMessage(mHandler, Message(MSG_DISPOSE_SURFACES));
    }
}

void SpriteController::handleMessage(const Message& message) {
    switch (message.what) {
    case MSG_UPDATE_SPRITES:
        doUpdateSprites();
        break;
    case MSG_DISPOSE_SURFACES:
        doDisposeSurfaces();
        break;
    }
}

void SpriteController::doUpdateSprites() {
    // Collect information about sprite updates.
    // Each sprite update record includes a reference to its associated sprite so we can
    // be certain the sprites will not be deleted while this function runs.  Sprites
    // may invalidate themselves again during this time but we will handle those changes
    // in the next iteration.
    Vector<SpriteUpdate> updates;
    size_t numSprites;
    { // acquire lock
        AutoMutex _l(mLock);

        numSprites = mInvalidatedSprites.size();
        for (size_t i = 0; i < numSprites; i++) {
            const sp<SpriteImpl>& sprite = mInvalidatedSprites.itemAt(i);

            updates.push(SpriteUpdate(sprite, sprite->getStateLocked()));
            sprite->resetDirtyLocked();
        }
        mInvalidatedSprites.clear();
    } // release lock

    // Create missing surfaces.
    bool surfaceChanged = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        if (update.state.surfaceControl == NULL && update.state.wantSurfaceVisible()) {
            update.state.surfaceWidth = update.state.bitmap.width();
            update.state.surfaceHeight = update.state.bitmap.height();
            update.state.surfaceDrawn = false;
            update.state.surfaceVisible = false;
            update.state.surfaceControl = obtainSurface(
                    update.state.surfaceWidth, update.state.surfaceHeight);
            if (update.state.surfaceControl != NULL) {
                update.surfaceChanged = surfaceChanged = true;
            }
        }
    }

    // Resize sprites if needed, inside a global transaction.
    bool haveGlobalTransaction = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        if (update.state.surfaceControl != NULL && update.state.wantSurfaceVisible()) {
            int32_t desiredWidth = update.state.bitmap.width();
            int32_t desiredHeight = update.state.bitmap.height();
            if (update.state.surfaceWidth < desiredWidth
                    || update.state.surfaceHeight < desiredHeight) {
                if (!haveGlobalTransaction) {
                    SurfaceComposerClient::openGlobalTransaction();
                    haveGlobalTransaction = true;
                }

                status_t status = update.state.surfaceControl->setSize(desiredWidth, desiredHeight);
                if (status) {
                    LOGE("Error %d resizing sprite surface from %dx%d to %dx%d",
                            status, update.state.surfaceWidth, update.state.surfaceHeight,
                            desiredWidth, desiredHeight);
                } else {
                    update.state.surfaceWidth = desiredWidth;
                    update.state.surfaceHeight = desiredHeight;
                    update.state.surfaceDrawn = false;
                    update.surfaceChanged = surfaceChanged = true;

                    if (update.state.surfaceVisible) {
                        status = update.state.surfaceControl->hide();
                        if (status) {
                            LOGE("Error %d hiding sprite surface after resize.", status);
                        } else {
                            update.state.surfaceVisible = false;
                        }
                    }
                }
            }
        }
    }
    if (haveGlobalTransaction) {
        SurfaceComposerClient::closeGlobalTransaction();
    }

    // Redraw sprites if needed.
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        if ((update.state.dirty & DIRTY_BITMAP) && update.state.surfaceDrawn) {
            update.state.surfaceDrawn = false;
            update.surfaceChanged = surfaceChanged = true;
        }

        if (update.state.surfaceControl != NULL && !update.state.surfaceDrawn
                && update.state.wantSurfaceVisible()) {
            sp<Surface> surface = update.state.surfaceControl->getSurface();
            Surface::SurfaceInfo surfaceInfo;
            status_t status = surface->lock(&surfaceInfo);
            if (status) {
                LOGE("Error %d locking sprite surface before drawing.", status);
            } else {
                SkBitmap surfaceBitmap;
                ssize_t bpr = surfaceInfo.s * bytesPerPixel(surfaceInfo.format);
                surfaceBitmap.setConfig(SkBitmap::kARGB_8888_Config,
                        surfaceInfo.w, surfaceInfo.h, bpr);
                surfaceBitmap.setPixels(surfaceInfo.bits);

                SkCanvas surfaceCanvas;
                surfaceCanvas.setBitmapDevice(surfaceBitmap);

                SkPaint paint;
                paint.setXfermodeMode(SkXfermode::kSrc_Mode);
                surfaceCanvas.drawBitmap(update.state.bitmap, 0, 0, &paint);

                if (surfaceInfo.w > uint32_t(update.state.bitmap.width())) {
                    paint.setColor(0); // transparent fill color
                    surfaceCanvas.drawRectCoords(update.state.bitmap.width(), 0,
                            surfaceInfo.w, update.state.bitmap.height(), paint);
                }
                if (surfaceInfo.h > uint32_t(update.state.bitmap.height())) {
                    paint.setColor(0); // transparent fill color
                    surfaceCanvas.drawRectCoords(0, update.state.bitmap.height(),
                            surfaceInfo.w, surfaceInfo.h, paint);
                }

                status = surface->unlockAndPost();
                if (status) {
                    LOGE("Error %d unlocking and posting sprite surface after drawing.", status);
                } else {
                    update.state.surfaceDrawn = true;
                    update.surfaceChanged = surfaceChanged = true;
                }
            }
        }
    }

    // Set sprite surface properties and make them visible.
    bool haveTransaction = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        bool wantSurfaceVisibleAndDrawn = update.state.wantSurfaceVisible()
                && update.state.surfaceDrawn;
        bool becomingVisible = wantSurfaceVisibleAndDrawn && !update.state.surfaceVisible;
        bool becomingHidden = !wantSurfaceVisibleAndDrawn && update.state.surfaceVisible;
        if (update.state.surfaceControl != NULL && (becomingVisible || becomingHidden
                || (wantSurfaceVisibleAndDrawn && (update.state.dirty & (DIRTY_ALPHA
                        | DIRTY_POSITION | DIRTY_TRANSFORMATION_MATRIX | DIRTY_LAYER
                        | DIRTY_VISIBILITY | DIRTY_HOTSPOT))))) {
            status_t status;
            if (!haveTransaction) {
                status = mSurfaceComposerClient->openTransaction();
                if (status) {
                    LOGE("Error %d opening transation to update sprite surface.", status);
                    break;
                }
                haveTransaction = true;
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & DIRTY_ALPHA))) {
                status = update.state.surfaceControl->setAlpha(update.state.alpha);
                if (status) {
                    LOGE("Error %d setting sprite surface alpha.", status);
                }
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & (DIRTY_POSITION
                            | DIRTY_HOTSPOT)))) {
                status = update.state.surfaceControl->setPosition(
                        update.state.positionX - update.state.hotSpotX,
                        update.state.positionY - update.state.hotSpotY);
                if (status) {
                    LOGE("Error %d setting sprite surface position.", status);
                }
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible
                            || (update.state.dirty & DIRTY_TRANSFORMATION_MATRIX))) {
                status = update.state.surfaceControl->setMatrix(
                        update.state.transformationMatrix.dsdx,
                        update.state.transformationMatrix.dtdx,
                        update.state.transformationMatrix.dsdy,
                        update.state.transformationMatrix.dtdy);
                if (status) {
                    LOGE("Error %d setting sprite surface transformation matrix.", status);
                }
            }

            int32_t surfaceLayer = mOverlayLayer + update.state.layer;
            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & DIRTY_LAYER))) {
                status = update.state.surfaceControl->setLayer(surfaceLayer);
                if (status) {
                    LOGE("Error %d setting sprite surface layer.", status);
                }
            }

            if (becomingVisible) {
                status = update.state.surfaceControl->show(surfaceLayer);
                if (status) {
                    LOGE("Error %d showing sprite surface.", status);
                } else {
                    update.state.surfaceVisible = true;
                    update.surfaceChanged = surfaceChanged = true;
                }
            } else if (becomingHidden) {
                status = update.state.surfaceControl->hide();
                if (status) {
                    LOGE("Error %d hiding sprite surface.", status);
                } else {
                    update.state.surfaceVisible = false;
                    update.surfaceChanged = surfaceChanged = true;
                }
            }
        }
    }

    if (haveTransaction) {
        status_t status = mSurfaceComposerClient->closeTransaction();
        if (status) {
            LOGE("Error %d closing transaction to update sprite surface.", status);
        }
    }

    // If any surfaces were changed, write back the new surface properties to the sprites.
    if (surfaceChanged) { // acquire lock
        AutoMutex _l(mLock);

        for (size_t i = 0; i < numSprites; i++) {
            const SpriteUpdate& update = updates.itemAt(i);

            if (update.surfaceChanged) {
                update.sprite->setSurfaceLocked(update.state.surfaceControl,
                        update.state.surfaceWidth, update.state.surfaceHeight,
                        update.state.surfaceDrawn, update.state.surfaceVisible);
            }
        }
    } // release lock

    // Clear the sprite update vector outside the lock.  It is very important that
    // we do not clear sprite references inside the lock since we could be releasing
    // the last remaining reference to the sprite here which would result in the
    // sprite being deleted and the lock being reacquired by the sprite destructor
    // while already held.
    updates.clear();
}

void SpriteController::doDisposeSurfaces() {
    // Collect disposed surfaces.
    Vector<sp<SurfaceControl> > disposedSurfaces;
    { // acquire lock
        disposedSurfaces = mDisposedSurfaces;
        mDisposedSurfaces.clear();
    } // release lock

    // Release the last reference to each surface outside of the lock.
    // We don't want the surfaces to be deleted while we are holding our lock.
    disposedSurfaces.clear();
}

void SpriteController::ensureSurfaceComposerClient() {
    if (mSurfaceComposerClient == NULL) {
        mSurfaceComposerClient = new SurfaceComposerClient();
    }
}

sp<SurfaceControl> SpriteController::obtainSurface(int32_t width, int32_t height) {
    ensureSurfaceComposerClient();

    sp<SurfaceControl> surfaceControl = mSurfaceComposerClient->createSurface(
            getpid(), String8("Sprite"), 0, width, height, PIXEL_FORMAT_RGBA_8888);
    if (surfaceControl == NULL) {
        LOGE("Error creating sprite surface.");
        return NULL;
    }
    return surfaceControl;
}


// --- SpriteController::SpriteImpl ---

SpriteController::SpriteImpl::SpriteImpl(const sp<SpriteController> controller) :
        mController(controller), mTransactionNestingCount(0) {
}

SpriteController::SpriteImpl::~SpriteImpl() {
    AutoMutex _m(mController->mLock);

    // Let the controller take care of deleting the last reference to sprite
    // surfaces so that we do not block the caller on an IPC here.
    if (mState.surfaceControl != NULL) {
        mController->disposeSurfaceLocked(mState.surfaceControl);
        mState.surfaceControl.clear();
    }
}

void SpriteController::SpriteImpl::setBitmap(const SkBitmap* bitmap,
        float hotSpotX, float hotSpotY) {
    AutoMutex _l(mController->mLock);

    if (bitmap) {
        bitmap->copyTo(&mState.bitmap, SkBitmap::kARGB_8888_Config);
    } else {
        mState.bitmap.reset();
    }

    uint32_t dirty = DIRTY_BITMAP;
    if (mState.hotSpotX != hotSpotX || mState.hotSpotY != hotSpotY) {
        mState.hotSpotX = hotSpotX;
        mState.hotSpotY = hotSpotY;
        dirty |= DIRTY_HOTSPOT;
    }

    invalidateLocked(dirty);
}

void SpriteController::SpriteImpl::setVisible(bool visible) {
    AutoMutex _l(mController->mLock);

    if (mState.visible != visible) {
        mState.visible = visible;
        invalidateLocked(DIRTY_VISIBILITY);
    }
}

void SpriteController::SpriteImpl::setPosition(float x, float y) {
    AutoMutex _l(mController->mLock);

    if (mState.positionX != x || mState.positionY != y) {
        mState.positionX = x;
        mState.positionY = y;
        invalidateLocked(DIRTY_POSITION);
    }
}

void SpriteController::SpriteImpl::setLayer(int32_t layer) {
    AutoMutex _l(mController->mLock);

    if (mState.layer != layer) {
        mState.layer = layer;
        invalidateLocked(DIRTY_LAYER);
    }
}

void SpriteController::SpriteImpl::setAlpha(float alpha) {
    AutoMutex _l(mController->mLock);

    if (mState.alpha != alpha) {
        mState.alpha = alpha;
        invalidateLocked(DIRTY_ALPHA);
    }
}

void SpriteController::SpriteImpl::setTransformationMatrix(
        const SpriteTransformationMatrix& matrix) {
    AutoMutex _l(mController->mLock);

    if (mState.transformationMatrix != matrix) {
        mState.transformationMatrix = matrix;
        invalidateLocked(DIRTY_TRANSFORMATION_MATRIX);
    }
}

void SpriteController::SpriteImpl::openTransaction() {
    AutoMutex _l(mController->mLock);

    mTransactionNestingCount += 1;
}

void SpriteController::SpriteImpl::closeTransaction() {
    AutoMutex _l(mController->mLock);

    LOG_ALWAYS_FATAL_IF(mTransactionNestingCount == 0,
            "Sprite closeTransaction() called but there is no open sprite transaction");

    mTransactionNestingCount -= 1;
    if (mTransactionNestingCount == 0 && mState.dirty) {
        mController->invalidateSpriteLocked(this);
    }
}

void SpriteController::SpriteImpl::invalidateLocked(uint32_t dirty) {
    if (mTransactionNestingCount > 0) {
        bool wasDirty = mState.dirty;
        mState.dirty |= dirty;
        if (!wasDirty) {
            mController->invalidateSpriteLocked(this);
        }
    }
}

} // namespace android
