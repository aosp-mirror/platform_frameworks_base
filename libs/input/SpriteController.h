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

#ifndef _UI_SPRITES_H
#define _UI_SPRITES_H

#include <utils/RefBase.h>
#include <utils/Looper.h>

#include <gui/SurfaceComposerClient.h>

#include <SkBitmap.h>

namespace android {

/*
 * Transformation matrix for a sprite.
 */
struct SpriteTransformationMatrix {
    inline SpriteTransformationMatrix() : dsdx(1.0f), dtdx(0.0f), dsdy(0.0f), dtdy(1.0f) { }
    inline SpriteTransformationMatrix(float dsdx, float dtdx, float dsdy, float dtdy) :
            dsdx(dsdx), dtdx(dtdx), dsdy(dsdy), dtdy(dtdy) { }

    float dsdx;
    float dtdx;
    float dsdy;
    float dtdy;

    inline bool operator== (const SpriteTransformationMatrix& other) {
        return dsdx == other.dsdx
                && dtdx == other.dtdx
                && dsdy == other.dsdy
                && dtdy == other.dtdy;
    }

    inline bool operator!= (const SpriteTransformationMatrix& other) {
        return !(*this == other);
    }
};

/*
 * Icon that a sprite displays, including its hotspot.
 */
struct SpriteIcon {
    inline SpriteIcon() : hotSpotX(0), hotSpotY(0) { }
    inline SpriteIcon(const SkBitmap& bitmap, float hotSpotX, float hotSpotY) :
            bitmap(bitmap), hotSpotX(hotSpotX), hotSpotY(hotSpotY) { }

    SkBitmap bitmap;
    float hotSpotX;
    float hotSpotY;

    inline SpriteIcon copy() const {
        SkBitmap bitmapCopy;
        if (bitmapCopy.tryAllocPixels(bitmap.info().makeColorType(kN32_SkColorType))) {
            bitmap.readPixels(bitmapCopy.info(), bitmapCopy.getPixels(), bitmapCopy.rowBytes(),
                    0, 0);
        }
        return SpriteIcon(bitmapCopy, hotSpotX, hotSpotY);
    }

    inline void reset() {
        bitmap.reset();
        hotSpotX = 0;
        hotSpotY = 0;
    }

    inline bool isValid() const {
        return !bitmap.isNull() && !bitmap.empty();
    }
};

/*
 * A sprite is a simple graphical object that is displayed on-screen above other layers.
 * The basic sprite class is an interface.
 * The implementation is provided by the sprite controller.
 */
class Sprite : public RefBase {
protected:
    Sprite() { }
    virtual ~Sprite() { }

public:
    enum {
        // The base layer for pointer sprites.
        BASE_LAYER_POINTER = 0, // reserve space for 1 pointer

        // The base layer for spot sprites.
        BASE_LAYER_SPOT = 1, // reserve space for MAX_POINTER_ID spots
    };

    /* Sets the bitmap that is drawn by the sprite.
     * The sprite retains a copy of the bitmap for subsequent rendering. */
    virtual void setIcon(const SpriteIcon& icon) = 0;

    inline void clearIcon() {
        setIcon(SpriteIcon());
    }

    /* Sets whether the sprite is visible. */
    virtual void setVisible(bool visible) = 0;

    /* Sets the sprite position on screen, relative to the sprite's hot spot. */
    virtual void setPosition(float x, float y) = 0;

    /* Sets the layer of the sprite, relative to the system sprite overlay layer.
     * Layer 0 is the overlay layer, > 0 appear above this layer. */
    virtual void setLayer(int32_t layer) = 0;

    /* Sets the sprite alpha blend ratio between 0.0 and 1.0. */
    virtual void setAlpha(float alpha) = 0;

    /* Sets the sprite transformation matrix. */
    virtual void setTransformationMatrix(const SpriteTransformationMatrix& matrix) = 0;
};

/*
 * Displays sprites on the screen.
 *
 * This interface is used by PointerController and SpotController to draw pointers or
 * spot representations of fingers.  It is not intended for general purpose use
 * by other components.
 *
 * All sprite position updates and rendering is performed asynchronously.
 *
 * Clients are responsible for animating sprites by periodically updating their properties.
 */
class SpriteController : public MessageHandler {
protected:
    virtual ~SpriteController();

public:
    SpriteController(const sp<Looper>& looper, int32_t overlayLayer);

    /* Creates a new sprite, initially invisible. */
    sp<Sprite> createSprite();

    /* Opens or closes a transaction to perform a batch of sprite updates as part of
     * a single operation such as setPosition and setAlpha.  It is not necessary to
     * open a transaction when updating a single property.
     * Calls to openTransaction() nest and must be matched by an equal number
     * of calls to closeTransaction(). */
    void openTransaction();
    void closeTransaction();

private:
    enum {
        MSG_UPDATE_SPRITES,
        MSG_DISPOSE_SURFACES,
    };

    enum {
        DIRTY_BITMAP = 1 << 0,
        DIRTY_ALPHA = 1 << 1,
        DIRTY_POSITION = 1 << 2,
        DIRTY_TRANSFORMATION_MATRIX = 1 << 3,
        DIRTY_LAYER = 1 << 4,
        DIRTY_VISIBILITY = 1 << 5,
        DIRTY_HOTSPOT = 1 << 6,
    };

    /* Describes the state of a sprite.
     * This structure is designed so that it can be copied during updates so that
     * surfaces can be resized and redrawn without blocking the client by holding a lock
     * on the sprites for a long time.
     * Note that the SkBitmap holds a reference to a shared (and immutable) pixel ref. */
    struct SpriteState {
        inline SpriteState() :
                dirty(0), visible(false),
                positionX(0), positionY(0), layer(0), alpha(1.0f),
                surfaceWidth(0), surfaceHeight(0), surfaceDrawn(false), surfaceVisible(false) {
        }

        uint32_t dirty;

        SpriteIcon icon;
        bool visible;
        float positionX;
        float positionY;
        int32_t layer;
        float alpha;
        SpriteTransformationMatrix transformationMatrix;

        sp<SurfaceControl> surfaceControl;
        int32_t surfaceWidth;
        int32_t surfaceHeight;
        bool surfaceDrawn;
        bool surfaceVisible;

        inline bool wantSurfaceVisible() const {
            return visible && alpha > 0.0f && icon.isValid();
        }
    };

    /* Client interface for a sprite.
     * Requests acquire a lock on the controller, update local state and request the
     * controller to invalidate the sprite.
     * The real heavy lifting of creating, resizing and redrawing surfaces happens
     * asynchronously with no locks held except in short critical section to copy
     * the sprite state before the work and update the sprite surface control afterwards.
     */
    class SpriteImpl : public Sprite {
    protected:
        virtual ~SpriteImpl();

    public:
        explicit SpriteImpl(const sp<SpriteController> controller);

        virtual void setIcon(const SpriteIcon& icon);
        virtual void setVisible(bool visible);
        virtual void setPosition(float x, float y);
        virtual void setLayer(int32_t layer);
        virtual void setAlpha(float alpha);
        virtual void setTransformationMatrix(const SpriteTransformationMatrix& matrix);

        inline const SpriteState& getStateLocked() const {
            return mLocked.state;
        }

        inline void resetDirtyLocked() {
            mLocked.state.dirty = 0;
        }

        inline void setSurfaceLocked(const sp<SurfaceControl>& surfaceControl,
                int32_t width, int32_t height, bool drawn, bool visible) {
            mLocked.state.surfaceControl = surfaceControl;
            mLocked.state.surfaceWidth = width;
            mLocked.state.surfaceHeight = height;
            mLocked.state.surfaceDrawn = drawn;
            mLocked.state.surfaceVisible = visible;
        }

    private:
        sp<SpriteController> mController;

        struct Locked {
            SpriteState state;
        } mLocked; // guarded by mController->mLock

        void invalidateLocked(uint32_t dirty);
    };

    /* Stores temporary information collected during the sprite update cycle. */
    struct SpriteUpdate {
        inline SpriteUpdate() : surfaceChanged(false) { }
        inline SpriteUpdate(const sp<SpriteImpl> sprite, const SpriteState& state) :
                sprite(sprite), state(state), surfaceChanged(false) {
        }

        sp<SpriteImpl> sprite;
        SpriteState state;
        bool surfaceChanged;
    };

    mutable Mutex mLock;

    sp<Looper> mLooper;
    const int32_t mOverlayLayer;
    sp<WeakMessageHandler> mHandler;

    sp<SurfaceComposerClient> mSurfaceComposerClient;

    struct Locked {
        Vector<sp<SpriteImpl> > invalidatedSprites;
        Vector<sp<SurfaceControl> > disposedSurfaces;
        uint32_t transactionNestingCount;
        bool deferredSpriteUpdate;
    } mLocked; // guarded by mLock

    void invalidateSpriteLocked(const sp<SpriteImpl>& sprite);
    void disposeSurfaceLocked(const sp<SurfaceControl>& surfaceControl);

    void handleMessage(const Message& message);
    void doUpdateSprites();
    void doDisposeSurfaces();

    void ensureSurfaceComposerClient();
    sp<SurfaceControl> obtainSurface(int32_t width, int32_t height);
};

} // namespace android

#endif // _UI_SPRITES_H
