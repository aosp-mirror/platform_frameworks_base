/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_LAYER_BASE_H
#define ANDROID_LAYER_BASE_H

#include <stdint.h>
#include <sys/types.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>

#include <utils/RefBase.h>

#include <ui/Region.h>
#include <ui/Overlay.h>

#include <surfaceflinger/ISurfaceComposerClient.h>
#include <private/surfaceflinger/SharedBufferStack.h>
#include <private/surfaceflinger/LayerState.h>

#include <pixelflinger/pixelflinger.h>

#include "Transform.h"

namespace android {

// ---------------------------------------------------------------------------

class DisplayHardware;
class Client;
class GraphicBuffer;
class GraphicPlane;
class LayerBaseClient;
class SurfaceFlinger;
class Texture;

// ---------------------------------------------------------------------------

class LayerBase : public RefBase
{
public:
            LayerBase(SurfaceFlinger* flinger, DisplayID display);

    DisplayID           dpy;
    mutable bool        contentDirty;
            Region      visibleRegionScreen;
            Region      transparentRegionScreen;
            Region      coveredRegionScreen;
            
            struct State {
                uint32_t        w;
                uint32_t        h;
                uint32_t        requested_w;
                uint32_t        requested_h;
                uint32_t        z;
                uint8_t         alpha;
                uint8_t         flags;
                uint8_t         reserved[2];
                int32_t         sequence;   // changes when visible regions can change
                uint32_t        tint;
                Transform       transform;
                Region          transparentRegion;
            };

            void setName(const String8& name);
            String8 getName() const;

            // modify current state
            bool setPosition(int32_t x, int32_t y);
            bool setLayer(uint32_t z);
            bool setSize(uint32_t w, uint32_t h);
            bool setAlpha(uint8_t alpha);
            bool setMatrix(const layer_state_t::matrix22_t& matrix);
            bool setTransparentRegionHint(const Region& opaque);
            bool setFlags(uint8_t flags, uint8_t mask);
            
            void commitTransaction();
            bool requestTransaction();
            void forceVisibilityTransaction();
            
            uint32_t getTransactionFlags(uint32_t flags);
            uint32_t setTransactionFlags(uint32_t flags);
            
            Rect visibleBounds() const;
            void drawRegion(const Region& reg) const;

            void invalidate();

    virtual sp<LayerBaseClient> getLayerBaseClient() const { return 0; }

    virtual const char* getTypeId() const { return "LayerBase"; }

    /**
     * draw - performs some global clipping optimizations
     * and calls onDraw().
     * Typically this method is not overridden, instead implement onDraw()
     * to perform the actual drawing.  
     */
    virtual void draw(const Region& clip) const;
    
    /**
     * onDraw - draws the surface.
     */
    virtual void onDraw(const Region& clip) const = 0;
    
    /**
     * initStates - called just after construction
     */
    virtual void initStates(uint32_t w, uint32_t h, uint32_t flags);
    
    /**
     * doTransaction - process the transaction. This is a good place to figure
     * out which attributes of the surface have changed.
     */
    virtual uint32_t doTransaction(uint32_t transactionFlags);
    
    /**
     * setVisibleRegion - called to set the new visible region. This gives
     * a chance to update the new visible region or record the fact it changed.
     */
    virtual void setVisibleRegion(const Region& visibleRegion);
    
    /**
     * setCoveredRegion - called when the covered region changes. The covered
     * region corresponds to any area of the surface that is covered
     * (transparently or not) by another surface.
     */
    virtual void setCoveredRegion(const Region& coveredRegion);

    /**
     * validateVisibility - cache a bunch of things
     */
    virtual void validateVisibility(const Transform& globalTransform);

    /**
     * lockPageFlip - called each time the screen is redrawn and returns whether
     * the visible regions need to be recomputed (this is a fairly heavy
     * operation, so this should be set only if needed). Typically this is used
     * to figure out if the content or size of a surface has changed.
     */
    virtual void lockPageFlip(bool& recomputeVisibleRegions);
    
    /**
     * unlockPageFlip - called each time the screen is redrawn. updates the
     * final dirty region wrt the planeTransform.
     * At this point, all visible regions, surface position and size, etc... are
     * correct.
     */
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);
    
    /**
     * finishPageFlip - called after all surfaces have drawn.
     */
    virtual void finishPageFlip();
    
    /**
     * needsBlending - true if this surface needs blending
     */
    virtual bool needsBlending() const  { return false; }

    /**
     * needsDithering - true if this surface needs dithering
     */
    virtual bool needsDithering() const { return false; }

    /**
     * needsLinearFiltering - true if this surface needs filtering
     */
    virtual bool needsFiltering() const { return mNeedsFiltering; }

    /**
     * isSecure - true if this surface is secure, that is if it prevents
     * screenshots or VNC servers.
     */
    virtual bool isSecure() const       { return false; }

    /** Called from the main thread, when the surface is removed from the
     * draw list */
    virtual status_t ditch() { return NO_ERROR; }

    /** called with the state lock when the surface is removed from the
     *  current list */
    virtual void onRemoved() { };
    
    /** always call base class first */
    virtual void dump(String8& result, char* scratch, size_t size) const;


    enum { // flags for doTransaction()
        eVisibleRegion      = 0x00000002,
    };


    inline  const State&    drawingState() const    { return mDrawingState; }
    inline  const State&    currentState() const    { return mCurrentState; }
    inline  State&          currentState()          { return mCurrentState; }

    static int compareCurrentStateZ(
            sp<LayerBase> const * layerA,
            sp<LayerBase> const * layerB) {
        return layerA[0]->currentState().z - layerB[0]->currentState().z;
    }

    int32_t  getOrientation() const { return mOrientation; }
    int  tx() const             { return mLeft; }
    int  ty() const             { return mTop; }
    
protected:
    const GraphicPlane& graphicPlane(int dpy) const;
          GraphicPlane& graphicPlane(int dpy);

          void clearWithOpenGL(const Region& clip, GLclampf r, GLclampf g,
                               GLclampf b, GLclampf alpha) const;
          void clearWithOpenGL(const Region& clip) const;
          void drawWithOpenGL(const Region& clip, const Texture& texture) const;
          
                sp<SurfaceFlinger> mFlinger;
                uint32_t        mFlags;

                // cached during validateVisibility()
                bool            mNeedsFiltering;
                int32_t         mOrientation;
                GLfloat         mVertices[4][2];
                Rect            mTransformedBounds;
                int             mLeft;
                int             mTop;
            
                // these are protected by an external lock
                State           mCurrentState;
                State           mDrawingState;
    volatile    int32_t         mTransactionFlags;

                // don't change, don't need a lock
                bool            mPremultipliedAlpha;
                String8         mName;
    mutable     bool            mDebug;


                // atomic
    volatile    int32_t         mInvalidate;
                

protected:
    virtual ~LayerBase();

private:
    LayerBase(const LayerBase& rhs);
    void validateTexture(GLint textureName) const;
};


// ---------------------------------------------------------------------------

class LayerBaseClient : public LayerBase
{
public:
    class Surface;

            LayerBaseClient(SurfaceFlinger* flinger, DisplayID display,
                        const sp<Client>& client);
    virtual ~LayerBaseClient();

            sp<Surface> getSurface();
    virtual sp<Surface> createSurface() const;
    virtual sp<LayerBaseClient> getLayerBaseClient() const {
        return const_cast<LayerBaseClient*>(this); }
    virtual const char* getTypeId() const { return "LayerBaseClient"; }

    uint32_t getIdentity() const { return mIdentity; }

    class Surface : public BnSurface  {
    public:
        int32_t getIdentity() const { return mIdentity; }
        
    protected:
        Surface(const sp<SurfaceFlinger>& flinger, int identity,
                const sp<LayerBaseClient>& owner);
        virtual ~Surface();
        virtual status_t onTransact(uint32_t code, const Parcel& data,
                Parcel* reply, uint32_t flags);
        sp<LayerBaseClient> getOwner() const;

    private:
        virtual sp<GraphicBuffer> requestBuffer(int bufferIdx,
                uint32_t w, uint32_t h, uint32_t format, uint32_t usage);
        virtual status_t setBufferCount(int bufferCount);

        virtual status_t registerBuffers(const ISurface::BufferHeap& buffers); 
        virtual void postBuffer(ssize_t offset);
        virtual void unregisterBuffers();
        virtual sp<OverlayRef> createOverlay(uint32_t w, uint32_t h,
                int32_t format, int32_t orientation);

    protected:
        friend class LayerBaseClient;
        sp<SurfaceFlinger>  mFlinger;
        int32_t             mIdentity;
        wp<LayerBaseClient> mOwner;
    };

    friend class Surface;

protected:
    virtual void dump(String8& result, char* scratch, size_t size) const;

private:
    mutable Mutex mLock;
    mutable wp<Surface> mClientSurface;
    const wp<Client> mClientRef;
    // only read
    const uint32_t mIdentity;
    static int32_t sIdentity;
};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BASE_H
