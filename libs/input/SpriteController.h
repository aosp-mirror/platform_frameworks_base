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

#include "SpriteIcon.h"

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

    /* Sets the id of the display where the sprite should be shown. */
    virtual void setDisplayId(ui::LogicalDisplayId displayId) = 0;

    /* Sets the flag to hide sprite on mirrored displays.
     * This will add ISurfaceComposerClient::eSkipScreenshot flag to the sprite. */
    virtual void setSkipScreenshot(bool skip) = 0;
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
class SpriteController {
public:
    using ParentSurfaceProvider = std::function<sp<SurfaceControl>(ui::LogicalDisplayId)>;
    SpriteController(const sp<Looper>& looper, int32_t overlayLayer, ParentSurfaceProvider parent);
    SpriteController(const SpriteController&) = delete;
    SpriteController& operator=(const SpriteController&) = delete;
    virtual ~SpriteController();

    /* Initialize the callback for the message handler. */
    void setHandlerController(const std::shared_ptr<SpriteController>& controller);

    /* Creates a new sprite, initially invisible. The lifecycle of the sprite must not extend beyond
     * the lifecycle of this SpriteController. */
    virtual sp<Sprite> createSprite();

    /* Opens or closes a transaction to perform a batch of sprite updates as part of
     * a single operation such as setPosition and setAlpha.  It is not necessary to
     * open a transaction when updating a single property.
     * Calls to openTransaction() nest and must be matched by an equal number
     * of calls to closeTransaction(). */
    virtual void openTransaction();
    virtual void closeTransaction();

private:
    class Handler : public virtual android::MessageHandler {
    public:
        enum { MSG_UPDATE_SPRITES, MSG_DISPOSE_SURFACES };

        void handleMessage(const Message& message) override;
        std::weak_ptr<SpriteController> spriteController;
    };

    enum {
        DIRTY_BITMAP = 1 << 0,
        DIRTY_ALPHA = 1 << 1,
        DIRTY_POSITION = 1 << 2,
        DIRTY_TRANSFORMATION_MATRIX = 1 << 3,
        DIRTY_LAYER = 1 << 4,
        DIRTY_VISIBILITY = 1 << 5,
        DIRTY_HOTSPOT = 1 << 6,
        DIRTY_DISPLAY_ID = 1 << 7,
        DIRTY_ICON_STYLE = 1 << 8,
        DIRTY_DRAW_DROP_SHADOW = 1 << 9,
        DIRTY_SKIP_SCREENSHOT = 1 << 10,
    };

    /* Describes the state of a sprite.
     * This structure is designed so that it can be copied during updates so that
     * surfaces can be resized and redrawn without blocking the client by holding a lock
     * on the sprites for a long time.
     * Note that the SpriteIcon holds a reference to a shared (and immutable) bitmap. */
    struct SpriteState {
        uint32_t dirty{0};

        SpriteIcon icon;
        bool visible{false};
        float positionX{0};
        float positionY{0};
        int32_t layer{0};
        float alpha{1.0f};
        SpriteTransformationMatrix transformationMatrix;
        ui::LogicalDisplayId displayId{ui::LogicalDisplayId::DEFAULT};

        sp<SurfaceControl> surfaceControl;
        int32_t surfaceWidth{0};
        int32_t surfaceHeight{0};
        bool surfaceDrawn{false};
        bool surfaceVisible{false};
        bool skipScreenshot{false};

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
        explicit SpriteImpl(SpriteController& controller);

        virtual void setIcon(const SpriteIcon& icon);
        virtual void setVisible(bool visible);
        virtual void setPosition(float x, float y);
        virtual void setLayer(int32_t layer);
        virtual void setAlpha(float alpha);
        virtual void setTransformationMatrix(const SpriteTransformationMatrix& matrix);
        virtual void setDisplayId(ui::LogicalDisplayId displayId);
        virtual void setSkipScreenshot(bool skip);

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
        SpriteController& mController;

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
    sp<Handler> mHandler;
    ParentSurfaceProvider mParentSurfaceProvider;

    sp<SurfaceComposerClient> mSurfaceComposerClient;

    struct Locked {
        std::vector<sp<SpriteImpl>> invalidatedSprites;
        std::vector<sp<SurfaceControl>> disposedSurfaces;
        uint32_t transactionNestingCount;
        bool deferredSpriteUpdate;
    } mLocked; // guarded by mLock

    void invalidateSpriteLocked(const sp<SpriteImpl>& sprite);
    void disposeSurfaceLocked(const sp<SurfaceControl>& surfaceControl);

    void doUpdateSprites();
    void doDisposeSurfaces();

    void ensureSurfaceComposerClient();
    sp<SurfaceControl> obtainSurface(int32_t width, int32_t height, ui::LogicalDisplayId displayId,
                                     bool hideOnMirrored);
};

} // namespace android

#endif // _UI_SPRITES_H
