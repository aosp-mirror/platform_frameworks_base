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

#ifndef ANDROID_RFB_SERVER_H
#define ANDROID_RFB_SERVER_H

#include <stdint.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>
#include <arpa/inet.h>

#include <utils/Errors.h>
#include <utils/threads.h>
#include <ui/Region.h>
#include <ui/PixelFormat.h>

#include <pixelflinger/pixelflinger.h>

#include "Barrier.h"

namespace android {

class SurfaceFlinger;

class RFBServer : public Thread
{
public:
                        RFBServer(uint32_t w, uint32_t h, android::PixelFormat format);
    virtual             ~RFBServer();

    void    frameBufferUpdated(const GGLSurface& front, const Region& reg);
    bool    isConnected() const;

private:
            typedef uint8_t     card8;
            typedef uint16_t    card16;
            typedef uint32_t    card32;

            struct Message {
                                Message(size_t size);
                virtual         ~Message();
                void*           payload(int offset=0) {
                    return static_cast<char*>(mPayload)+offset;
                }
                void const *    payload(int offset=0) const {
                    return static_cast<char const *>(mPayload)+offset;
                }
                size_t          size() const { return mSize; }
            protected:
                                Message(void* payload, size_t size);
                status_t        resize(size_t size);
            private:
                void*       mPayload;
                size_t      mSize;
                size_t      mAllocatedSize;
            };

            struct ProtocolVersion : public Message {
                ProtocolVersion(uint8_t major, uint8_t minor)
                    : Message(&messageData, 12) {
                    char* p = static_cast<char*>(payload());
                    snprintf(p, 13, "RFB %03u.%03u%c", major, minor, 0xA);
                }
                status_t decode(int& maj, int& min) {
                    char* p = static_cast<char*>(payload());
                    int n = sscanf(p, "RFB %03u.%03u", &maj, &min);
                    return (n == 2) ? NO_ERROR : NOT_ENOUGH_DATA;
                }
            private:
                char messageData[12+1];
            };
            
            struct Authentication : public Message {
                enum { Failed=0, None=1, Vnc=2 };
                Authentication(int auth) : Message(&messageData, 4) {
                    *static_cast<card32*>(payload()) = htonl(auth);
                }
            private:
                card32 messageData;
            };
            
            struct ClientInitialization : public Message {
                ClientInitialization() : Message(&messageData, 1) { }
                int sharedFlags() {
                    return messageData;
                }
            private:
                card8 messageData;
            };

            struct PixelFormat {
                card8   bitsPerPixel;
                card8   depth;
                card8   bigEndianFlag;
                card8   trueColorFlag;
                card16  redMax;
                card16  greenMax;
                card16  blueMax;
                card8   redShift;
                card8   greenShift;
                card8   blueShift;
                uint8_t padding[3];
            } __attribute__((packed));
            
            struct ServerInitialization : public Message {
                ServerInitialization(char const * name)
                    : Message(sizeof(Payload) + strlen(name))
                {
                    const size_t nameLength = size() - sizeof(Payload);
                    message().nameLength = htonl(nameLength); 
                    memcpy((char*)message().nameString, name,nameLength);
                }
                struct Payload {
                    card16      framebufferWidth;
                    card16      framebufferHeight;
                    PixelFormat serverPixelFormat;
                    card32      nameLength;
                    card8       nameString[0];
                } __attribute__((packed));
                Payload& message() {
                    return *static_cast<Payload*>(payload());
                }
            };

            // client messages...
            
            struct SetPixelFormat {
                card8           type;
                uint8_t         padding[3];
                PixelFormat     pixelFormat;
            } __attribute__((packed));

            struct SetEncodings {
                enum { Raw=0, CoR=1, RRE=2, CoRRE=4, Hextile=5 };
                card8           type;
                uint8_t         padding;
                card16          numberOfEncodings;
                card32          encodings[0];
            } __attribute__((packed));

            struct FrameBufferUpdateRequest {
                card8           type;
                card8           incremental;
                card16          x;
                card16          y;
                card16          width;
                card16          height;
            } __attribute__((packed));
            
            struct KeyEvent {
                card8           type;
                card8           downFlag;
                uint8_t         padding[2];
                card32          key;
            } __attribute__((packed));

            struct PointerEvent {
                card8           type;
                card8           buttonMask;
                card16          x;
                card16          y;
            } __attribute__((packed));

            struct ClientCutText {
                card8           type;
                uint8_t         padding[3];
                card32          length;
                card8           text[0];
            } __attribute__((packed));
            
            union ClientMessages {
                card8                       type;
                SetPixelFormat              setPixelFormat;
                SetEncodings                setEncodings;
                FrameBufferUpdateRequest    frameBufferUpdateRequest;
                KeyEvent                    keyEvent;
                PointerEvent                pointerEvent;
                ClientCutText               clientCutText;
            };

            struct Rectangle {
                card16      x;
                card16      y;
                card16      w;
                card16      h;
                card32      encoding;
            } __attribute__((packed));

            struct FrameBufferUpdate {
                card8       type;
                uint8_t     padding;
                card16      numberOfRectangles;
                Rectangle   rectangles[0];            
            } __attribute__((packed));

            enum {
                SET_PIXEL_FORMAT        = 0,
                FIX_COLOUR_MAP_ENTRIES  = 1,
                SET_ENCODINGS           = 2,
                FRAME_BUFFER_UPDATE_REQ = 3,
                KEY_EVENT               = 4,
                POINTER_EVENT           = 5,
                CLIENT_CUT_TEXT         = 6,
            };

            struct ClientMessage : public Message {
                ClientMessage()
                    : Message(&messageData, sizeof(messageData)) {
                }
                const ClientMessages& messages() const {
                    return *static_cast<ClientMessages const *>(payload());
                }
                const int type() const {
                    return messages().type;
                }
                status_t resize(size_t size) {
                    return Message::resize(size);
                }

                ClientMessages messageData;
            };

            
            class ServerThread : public Thread
            {
                friend class RFBServer;
            public:
                        ServerThread(const sp<RFBServer>& receiver);
                virtual ~ServerThread();
                void wake();
                void exitAndWait();
            private:
                virtual bool threadLoop();
                virtual status_t readyToRun();
                virtual void onFirstRef();
                wp<RFBServer> mReceiver;
                bool (RFBServer::*mAction)();
                Barrier mUpdateBarrier;
            };
            
            class EventInjector {
            public:
                enum { UP=0, DOWN=1 };
                EventInjector();
                ~EventInjector();
                void injectKey(uint16_t code, uint16_t value);
            private:
                struct input_event {
                    struct timeval time;
                    uint16_t type;
                    uint16_t code;
                    uint32_t value;
                };
                int mFD;
            };
            
            void        handshake(uint8_t major, uint8_t minor, uint32_t auth);
            void        waitForClientMessage(ClientMessage& msg);
            void        handleClientMessage(const ClientMessage& msg);
            void        handleSetPixelFormat(const SetPixelFormat& msg);
            void        handleSetEncodings(const SetEncodings& msg);
            void        handleFrameBufferUpdateReq(const FrameBufferUpdateRequest& msg);
            void        handleKeyEvent(const KeyEvent& msg);
            void        sendFrameBufferUpdates();

            bool        validatePixelFormat(const PixelFormat& pf);
            bool        alive() const;
            bool        write(const Message& msg);
            bool        read(Message& msg);

            bool        write(const void* buffer, int size);
            bool        read(void* buffer, int size);

    virtual bool        threadLoop();
    virtual status_t    readyToRun();
    virtual void        onFirstRef();

            sp<ServerThread>    mRobinThread;

            int         mFD;
            int         mStatus;
            iovec*      mIoVec;
    
            EventInjector   mEventInjector;

            Mutex       mRegionLock;
            // This is the region requested by the client since the last
            // time we updated it
            Region      mClientRegionRequest;
            // This is the region of the screen that needs to be sent to the
            // client since the last time we updated it.
            // Typically this is the dirty region, but not necessarily, for
            // instance if the client asked for a non incremental update.
            Region      mDirtyRegion;
            
            GGLSurface  mFrameBuffer;
            GGLSurface  mFrontBuffer;
};

}; // namespace android

#endif // ANDROID_RFB_SERVER_H
