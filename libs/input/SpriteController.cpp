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

#include <log/log.h>
#include <utils/String8.h>
#include <gui/Surface.h>

namespace android {

// --- SpriteController ---

SpriteController::SpriteController(const sp<Looper>& looper, int32_t overlayLayer,
                                   ParentSurfaceProvider parentSurfaceProvider)
      : mLooper(looper),
        mOverlayLayer(overlayLayer),
        mParentSurfaceProvider(std::move(parentSurfaceProvider)) {
    mHandler = new WeakMessageHandler(this);
    mLocked.transactionNestingCount = 0;
    mLocked.deferredSpriteUpdate = false;
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

void SpriteController::openTransaction() {
    AutoMutex _l(mLock);

    mLocked.transactionNestingCount += 1;
}

void SpriteController::closeTransaction() {
    AutoMutex _l(mLock);

    LOG_ALWAYS_FATAL_IF(mLocked.transactionNestingCount == 0,
            "Sprite closeTransaction() called but there is no open sprite transaction");

    mLocked.transactionNestingCount -= 1;
    if (mLocked.transactionNestingCount == 0 && mLocked.deferredSpriteUpdate) {
        mLocked.deferredSpriteUpdate = false;
        mLooper->sendMessage(mHandler, Message(MSG_UPDATE_SPRITES));
    }
}

void SpriteController::invalidateSpriteLocked(const sp<SpriteImpl>& sprite) {
    bool wasEmpty = mLocked.invalidatedSprites.empty();
    mLocked.invalidatedSprites.push_back(sprite);
    if (wasEmpty) {
        if (mLocked.transactionNestingCount != 0) {
            mLocked.deferredSpriteUpdate = true;
        } else {
            mLooper->sendMessage(mHandler, Message(MSG_UPDATE_SPRITES));
        }
    }
}

void SpriteController::disposeSurfaceLocked(const sp<SurfaceControl>& surfaceControl) {
    bool wasEmpty = mLocked.disposedSurfaces.empty();
    mLocked.disposedSurfaces.push_back(surfaceControl);
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

        numSprites = mLocked.invalidatedSprites.size();
        for (size_t i = 0; i < numSprites; i++) {
            const sp<SpriteImpl>& sprite = mLocked.invalidatedSprites[i];

            updates.push(SpriteUpdate(sprite, sprite->getStateLocked()));
            sprite->resetDirtyLocked();
        }
        mLocked.invalidatedSprites.clear();
    } // release lock

    // Create missing surfaces.
    bool surfaceChanged = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        if (update.state.surfaceControl == NULL && update.state.wantSurfaceVisible()) {
            update.state.surfaceWidth = update.state.icon.width();
            update.state.surfaceHeight = update.state.icon.height();
            update.state.surfaceDrawn = false;
            update.state.surfaceVisible = false;
            update.state.surfaceControl = obtainSurface(
                    update.state.surfaceWidth, update.state.surfaceHeight);
            if (update.state.surfaceControl != NULL) {
                update.surfaceChanged = surfaceChanged = true;
            }
        }
    }

    // Resize and/or reparent sprites if needed.
    SurfaceComposerClient::Transaction t;
    bool needApplyTransaction = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);
        if (update.state.surfaceControl == nullptr) {
            continue;
        }

        if (update.state.wantSurfaceVisible()) {
            int32_t desiredWidth = update.state.icon.width();
            int32_t desiredHeight = update.state.icon.height();
            if (update.state.surfaceWidth < desiredWidth
                    || update.state.surfaceHeight < desiredHeight) {
                needApplyTransaction = true;

                update.state.surfaceControl->updateDefaultBufferSize(desiredWidth, desiredHeight);
                update.state.surfaceWidth = desiredWidth;
                update.state.surfaceHeight = desiredHeight;
                update.state.surfaceDrawn = false;
                update.surfaceChanged = surfaceChanged = true;

                if (update.state.surfaceVisible) {
                    t.hide(update.state.surfaceControl);
                    update.state.surfaceVisible = false;
                }
            }
        }

        // If surface is a new one, we have to set right layer stack.
        if (update.surfaceChanged || update.state.dirty & DIRTY_DISPLAY_ID) {
            t.reparent(update.state.surfaceControl, mParentSurfaceProvider(update.state.displayId));
            needApplyTransaction = true;
        }
    }
    if (needApplyTransaction) {
        t.apply();
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
            if (update.state.icon.draw(surface)) {
                update.state.surfaceDrawn = true;
                update.surfaceChanged = surfaceChanged = true;
            }
        }
    }

    needApplyTransaction = false;
    for (size_t i = 0; i < numSprites; i++) {
        SpriteUpdate& update = updates.editItemAt(i);

        bool wantSurfaceVisibleAndDrawn = update.state.wantSurfaceVisible()
                && update.state.surfaceDrawn;
        bool becomingVisible = wantSurfaceVisibleAndDrawn && !update.state.surfaceVisible;
        bool becomingHidden = !wantSurfaceVisibleAndDrawn && update.state.surfaceVisible;
        if (update.state.surfaceControl != NULL && (becomingVisible || becomingHidden
                || (wantSurfaceVisibleAndDrawn && (update.state.dirty & (DIRTY_ALPHA
                        | DIRTY_POSITION | DIRTY_TRANSFORMATION_MATRIX | DIRTY_LAYER
                        | DIRTY_VISIBILITY | DIRTY_HOTSPOT | DIRTY_DISPLAY_ID
                        | DIRTY_ICON_STYLE))))) {
            needApplyTransaction = true;

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & DIRTY_ALPHA))) {
                t.setAlpha(update.state.surfaceControl,
                        update.state.alpha);
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & (DIRTY_POSITION
                            | DIRTY_HOTSPOT)))) {
                t.setPosition(
                        update.state.surfaceControl,
                        update.state.positionX - update.state.icon.hotSpotX,
                        update.state.positionY - update.state.icon.hotSpotY);
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible
                            || (update.state.dirty & DIRTY_TRANSFORMATION_MATRIX))) {
                t.setMatrix(
                        update.state.surfaceControl,
                        update.state.transformationMatrix.dsdx,
                        update.state.transformationMatrix.dtdx,
                        update.state.transformationMatrix.dsdy,
                        update.state.transformationMatrix.dtdy);
            }

            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible
                            || (update.state.dirty & (DIRTY_HOTSPOT | DIRTY_ICON_STYLE)))) {
                Parcel p;
                p.writeInt32(update.state.icon.style);
                p.writeFloat(update.state.icon.hotSpotX);
                p.writeFloat(update.state.icon.hotSpotY);

                // Pass cursor metadata in the sprite surface so that when Android is running as a
                // client OS (e.g. ARC++) the host OS can get the requested cursor metadata and
                // update mouse cursor in the host OS.
                t.setMetadata(update.state.surfaceControl, gui::METADATA_MOUSE_CURSOR, p);
            }

            int32_t surfaceLayer = mOverlayLayer + update.state.layer;
            if (wantSurfaceVisibleAndDrawn
                    && (becomingVisible || (update.state.dirty & DIRTY_LAYER))) {
                t.setLayer(update.state.surfaceControl, surfaceLayer);
            }

            if (becomingVisible) {
                t.show(update.state.surfaceControl);

                update.state.surfaceVisible = true;
                update.surfaceChanged = surfaceChanged = true;
            } else if (becomingHidden) {
                t.hide(update.state.surfaceControl);

                update.state.surfaceVisible = false;
                update.surfaceChanged = surfaceChanged = true;
            }
        }
    }

    if (needApplyTransaction) {
        status_t status = t.apply();
        if (status) {
            ALOGE("Error applying Surface transaction");
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
    std::vector<sp<SurfaceControl>> disposedSurfaces;
    { // acquire lock
        AutoMutex _l(mLock);

        disposedSurfaces = mLocked.disposedSurfaces;
        mLocked.disposedSurfaces.clear();
    } // release lock

    // Remove the parent from all surfaces.
    SurfaceComposerClient::Transaction t;
    for (const sp<SurfaceControl>& sc : disposedSurfaces) {
        t.reparent(sc, nullptr);
    }
    t.apply();

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
            String8("Sprite"), width, height, PIXEL_FORMAT_RGBA_8888,
            ISurfaceComposerClient::eHidden |
            ISurfaceComposerClient::eCursorWindow);
    if (surfaceControl == NULL || !surfaceControl->isValid()) {
        ALOGE("Error creating sprite surface.");
        return NULL;
    }
    return surfaceControl;
}


// --- SpriteController::SpriteImpl ---

SpriteController::SpriteImpl::SpriteImpl(const sp<SpriteController> controller) :
        mController(controller) {
}

SpriteController::SpriteImpl::~SpriteImpl() {
    AutoMutex _m(mController->mLock);

    // Let the controller take care of deleting the last reference to sprite
    // surfaces so that we do not block the caller on an IPC here.
    if (mLocked.state.surfaceControl != NULL) {
        mController->disposeSurfaceLocked(mLocked.state.surfaceControl);
        mLocked.state.surfaceControl.clear();
    }
}

void SpriteController::SpriteImpl::setIcon(const SpriteIcon& icon) {
    AutoMutex _l(mController->mLock);

    uint32_t dirty;
    if (icon.isValid()) {
        mLocked.state.icon.bitmap = icon.bitmap.copy(ANDROID_BITMAP_FORMAT_RGBA_8888);
        if (!mLocked.state.icon.isValid()
                || mLocked.state.icon.hotSpotX != icon.hotSpotX
                || mLocked.state.icon.hotSpotY != icon.hotSpotY) {
            mLocked.state.icon.hotSpotX = icon.hotSpotX;
            mLocked.state.icon.hotSpotY = icon.hotSpotY;
            dirty = DIRTY_BITMAP | DIRTY_HOTSPOT;
        } else {
            dirty = DIRTY_BITMAP;
        }

        if (mLocked.state.icon.style != icon.style) {
            mLocked.state.icon.style = icon.style;
            dirty |= DIRTY_ICON_STYLE;
        }
    } else if (mLocked.state.icon.isValid()) {
        mLocked.state.icon.bitmap.reset();
        dirty = DIRTY_BITMAP | DIRTY_HOTSPOT | DIRTY_ICON_STYLE;
    } else {
        return; // setting to invalid icon and already invalid so nothing to do
    }

    invalidateLocked(dirty);
}

void SpriteController::SpriteImpl::setVisible(bool visible) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.visible != visible) {
        mLocked.state.visible = visible;
        invalidateLocked(DIRTY_VISIBILITY);
    }
}

void SpriteController::SpriteImpl::setPosition(float x, float y) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.positionX != x || mLocked.state.positionY != y) {
        mLocked.state.positionX = x;
        mLocked.state.positionY = y;
        invalidateLocked(DIRTY_POSITION);
    }
}

void SpriteController::SpriteImpl::setLayer(int32_t layer) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.layer != layer) {
        mLocked.state.layer = layer;
        invalidateLocked(DIRTY_LAYER);
    }
}

void SpriteController::SpriteImpl::setAlpha(float alpha) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.alpha != alpha) {
        mLocked.state.alpha = alpha;
        invalidateLocked(DIRTY_ALPHA);
    }
}

void SpriteController::SpriteImpl::setTransformationMatrix(
        const SpriteTransformationMatrix& matrix) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.transformationMatrix != matrix) {
        mLocked.state.transformationMatrix = matrix;
        invalidateLocked(DIRTY_TRANSFORMATION_MATRIX);
    }
}

void SpriteController::SpriteImpl::setDisplayId(int32_t displayId) {
    AutoMutex _l(mController->mLock);

    if (mLocked.state.displayId != displayId) {
        mLocked.state.displayId = displayId;
        invalidateLocked(DIRTY_DISPLAY_ID);
    }
}

void SpriteController::SpriteImpl::invalidateLocked(uint32_t dirty) {
    bool wasDirty = mLocked.state.dirty;
    mLocked.state.dirty |= dirty;

    if (!wasDirty) {
        mController->invalidateSpriteLocked(this);
    }
}

} // namespace android
