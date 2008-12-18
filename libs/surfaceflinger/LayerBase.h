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

#include <private/ui/LayerState.h>

#include <ui/Region.h>
#include <ui/Overlay.h>

#include <pixelflinger/pixelflinger.h>

#include "Transform.h"

namespace android {

// ---------------------------------------------------------------------------

class SurfaceFlinger;
class DisplayHardware;
class GraphicPlane;
class Client;

// ---------------------------------------------------------------------------

class LayerBase
{
    // poor man's dynamic_cast below
    template<typename T>
    struct getTypeInfoOfAnyType {
        static uint32_t get() { return T::typeInfo; }
    };

    template<typename T>
    struct getTypeInfoOfAnyType<T*> {
        static uint32_t get() { return getTypeInfoOfAnyType<T>::get(); }
    };

public:
    static const uint32_t typeInfo;
    static const char* const typeID;
    virtual char const* getTypeID() const { return typeID; }
    virtual uint32_t getTypeInfo() const { return typeInfo; }
    
    template<typename T>
    static T dynamicCast(LayerBase* base) {
        uint32_t mostDerivedInfo = base->getTypeInfo();
        uint32_t castToInfo = getTypeInfoOfAnyType<T>::get();
        if ((mostDerivedInfo & castToInfo) == castToInfo)
            return static_cast<T>(base);
        return 0;
    }

    
    static Vector<GLuint> deletedTextures; 

    LayerBase(SurfaceFlinger* flinger, DisplayID display);
    virtual ~LayerBase();
    
    DisplayID           dpy;
    mutable bool        invalidate;
            Region      visibleRegionScreen;
            Region      transparentRegionScreen;
            Region      coveredRegionScreen;
            
            struct State {
                uint32_t        w;
                uint32_t        h;
                uint32_t        z;
                uint8_t         alpha;
                uint8_t         flags;
                uint8_t         sequence;   // changes when visible regions can change
                uint8_t         reserved;
                uint32_t        tint;
                Transform       transform;
                Region          transparentRegion;
            };

            // modify current state
            bool setPosition(int32_t x, int32_t y);
            bool setLayer(uint32_t z);
            bool setSize(uint32_t w, uint32_t h);
            bool setAlpha(uint8_t alpha);
            bool setMatrix(const layer_state_t::matrix22_t& matrix);
            bool setTransparentRegionHint(const Region& opaque);
            bool setFlags(uint8_t flags, uint8_t mask);
            
            void commitTransaction(bool skipSize);
            bool requestTransaction();

            uint32_t getTransactionFlags(uint32_t flags);
            uint32_t setTransactionFlags(uint32_t flags);
            
            void validateVisibility(const Transform& globalTransform);
            Rect visibleBounds() const;
            void drawRegion(const Region& reg) const;

    virtual void draw(const Region& clip) const;
    virtual void onDraw(const Region& clip) const = 0;
    virtual void initStates(uint32_t w, uint32_t h, uint32_t flags);
    virtual void setSizeChanged(uint32_t w, uint32_t h);
    virtual uint32_t doTransaction(uint32_t transactionFlags);
    virtual void setVisibleRegion(const Region& visibleRegion);
    virtual void setCoveredRegion(const Region& coveredRegion);
    virtual Point getPhysicalSize() const;
    virtual void lockPageFlip(bool& recomputeVisibleRegions);
    virtual void unlockPageFlip(const Transform& planeTransform, Region& outDirtyRegion);
    virtual void finishPageFlip();
    virtual bool needsBlending() const  { return false; }
    virtual bool isSecure() const       { return false; }

            enum { // flags for doTransaction()
                eVisibleRegion      = 0x00000002,
                eRestartTransaction = 0x00000008
            };


    inline  const State&    drawingState() const    { return mDrawingState; }
    inline  const State&    currentState() const    { return mCurrentState; }
    inline  State&          currentState()          { return mCurrentState; }

    static int compareCurrentStateZ(LayerBase*const* layerA, LayerBase*const* layerB) {
        return layerA[0]->currentState().z - layerB[0]->currentState().z;
    }

    int32_t  getOrientation() const { return mOrientation; }
    bool transformed() const    { return mTransformed; }
    int  tx() const             { return mLeft; }
    int  ty() const             { return mTop; }
    
protected:
    const GraphicPlane& graphicPlane(int dpy) const;
          GraphicPlane& graphicPlane(int dpy);

          GLuint createTexture() const;
    
          void drawWithOpenGL(const Region& clip,
                  GLint textureName, const GGLSurface& surface) const;

          void clearWithOpenGL(const Region& clip) const;

          void loadTexture(const Region& dirty,
                  GLint textureName, const GGLSurface& t,
                  GLuint& textureWidth, GLuint& textureHeight) const;

          bool canUseCopybit() const;
          
          
                SurfaceFlinger* mFlinger;
                uint32_t        mFlags;

                // cached during validateVisibility()
                bool            mTransformed;
                int32_t         mOrientation;
                GLfixed         mVertices[4][2];
                Rect            mTransformedBounds;
                bool            mCanUseCopyBit;
                int             mLeft;
                int             mTop;
            
                // these are protected by an external lock
                State           mCurrentState;
                State           mDrawingState;
    volatile    int32_t         mTransactionFlags;

                // don't change, don't need a lock
                bool            mPremultipliedAlpha;

                // only read
     const      uint32_t        mIdentity;
                

private:
                void validateTexture(GLint textureName) const;
    static      int32_t         sIdentity;
};


// ---------------------------------------------------------------------------

class LayerBaseClient : public LayerBase
{
public:
    class Surface;
   static const uint32_t typeInfo;
    static const char* const typeID;
    virtual char const* getTypeID() const { return typeID; }
    virtual uint32_t getTypeInfo() const { return typeInfo; }

    LayerBaseClient(SurfaceFlinger* flinger, DisplayID display, 
            Client* client, int32_t i);
    virtual ~LayerBaseClient();


    Client*             const client;
    layer_cblk_t*       const lcblk;

    inline  int32_t     clientIndex() const { return mIndex; }
            int32_t     serverIndex() const;

    virtual sp<Surface> getSurface() const;
   
            uint32_t    getIdentity() const { return mIdentity; }

    class Surface : public BnSurface 
    {
    public:
        Surface(SurfaceID id, int identity) { 
            mParams.token = id;
            mParams.identity = identity;
        }
        Surface(SurfaceID id, 
                const sp<IMemoryHeap>& heap0,
                const sp<IMemoryHeap>& heap1,
                int identity)
        {
            mParams.token = id;
            mParams.identity = identity;
            mParams.heap[0] = heap0;
            mParams.heap[1] = heap1;
        }
        virtual ~Surface() {
            // TODO: We now have a point here were we can clean-up the
            // client's mess.
            // This is also where surface id should be recycled.
            //LOGD("Surface %d, heaps={%p, %p} destroyed",
            //        mId, mHeap[0].get(), mHeap[1].get());
        }

        virtual void getSurfaceData(
                ISurfaceFlingerClient::surface_data_t* params) const {
            *params = mParams;
        }

        virtual status_t registerBuffers(int w, int h, int hstride, int vstride,
                PixelFormat format, const sp<IMemoryHeap>& heap) 
                { return INVALID_OPERATION; }
        virtual void postBuffer(ssize_t offset) { }
        virtual void unregisterBuffers() { };
        virtual sp<Overlay> createOverlay(
                uint32_t w, uint32_t h, int32_t format) {
            return NULL;
        };

    private:
        ISurfaceFlingerClient::surface_data_t mParams;
    };

private:
    int32_t mIndex;

};

// ---------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_LAYER_BASE_H
