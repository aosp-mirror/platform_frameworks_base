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

#define LOG_TAG "RFBServer"

#include <stdlib.h>
#include <stdio.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/ioctl.h>

#include <netinet/in.h>

#include <cutils/sockets.h>

#include <utils/Log.h>
#include <ui/Rect.h>

#ifdef HAVE_ANDROID_OS
#include <linux/input.h>
#endif

#include "RFBServer.h"
#include "SurfaceFlinger.h"

/* BUG=773511: this is a temporary hack required while developing the new
   set of "clean kernel headers" for the Bionic C library. */
#ifndef KEY_STAR
#define KEY_STAR    227
#endif
#ifndef KEY_SHARP
#define KEY_SHARP   228
#endif
#ifndef KEY_SOFT1
#define KEY_SOFT1   229
#endif
#ifndef KEY_SOFT2
#define KEY_SOFT2   230
#endif
#ifndef KEY_CENTER
#define KEY_CENTER  232
#endif

// ----------------------------------------------------------------------------

#define DEBUG_MSG   0

// ----------------------------------------------------------------------------

namespace android {

const int VNC_PORT = 5900;

RFBServer::RFBServer(uint32_t w, uint32_t h, android::PixelFormat format)
    : Thread(false), mFD(-1), mStatus(NO_INIT), mIoVec(0)
{
    mFrameBuffer.version = sizeof(mFrameBuffer);
    mFrameBuffer.width = w;
    mFrameBuffer.height = h;
    mFrameBuffer.stride = w;
    mFrameBuffer.format = format;
    mFrameBuffer.data = 0;
}

RFBServer::~RFBServer()
{
    if (mRobinThread != 0) {
        // ask the thread to exit first
        mRobinThread->exitAndWait();
    }

    free(mFrameBuffer.data);

    delete [] mIoVec;
}

void RFBServer::onFirstRef()
{
    run("Batman");
}

status_t RFBServer::readyToRun()
{
    LOGI("RFB server ready to run");
    return NO_ERROR;
}

bool RFBServer::threadLoop()
{
    struct sockaddr addr;
    socklen_t alen;
    int serverfd = -1;
    int port = VNC_PORT;

    do {
        retry:
        if (serverfd < 0) {
            serverfd = socket_loopback_server(port, SOCK_STREAM);
            if (serverfd < 0) {
                if ((errno == EADDRINUSE) && (port < (VNC_PORT+10))) {
                    LOGW("port %d already in use, trying %d", port, port+1);
                    port++;
                    goto retry;
                }
                LOGE("couldn't create socket, port=%d, error %d (%s)",
                        port, errno, strerror(errno));
                sleep(1);
                break;
            }
            fcntl(serverfd, F_SETFD, FD_CLOEXEC);
        }

        alen = sizeof(addr);
        mFD = accept(serverfd, &addr, &alen);

        if (mFD < 0) {
            LOGE("couldn't accept(), error %d (%s)", errno, strerror(errno));
            // we could have run out of file descriptors, wait a bit and
            // try again.
            sleep(1);
            goto retry;
        }
        fcntl(mFD, F_SETFD, FD_CLOEXEC);

        // send protocol version and Authentication method
        mStatus = NO_ERROR;
        handshake(3, 3, Authentication::None);

        if (alive()) {
            // create the thread we use to send data to the client
            mRobinThread = new ServerThread(this);
        }

        while( alive() ) {
            // client message must be destroyed at each iteration
            // (most of the time this is a no-op)
            ClientMessage msg;
            waitForClientMessage(msg);
            if (alive()) {
                handleClientMessage(msg);
            }
        }

    } while( alive() );

    // free-up some resources
    if (mRobinThread != 0) {
        mRobinThread->exitAndWait();
        mRobinThread.clear();
    }

    free(mFrameBuffer.data);
    mFrameBuffer.data = 0;

    close(mFD);
    close(serverfd);
    mFD = -1;

    // we'll try again
    return true;
}

// ----------------------------------------------------------------------------

RFBServer::ServerThread::ServerThread(const sp<RFBServer>& receiver)
            : Thread(false), mReceiver(receiver)
{
    LOGD("RFB Server Thread created");
}

RFBServer::ServerThread::~ServerThread()
{
    LOGD("RFB Server Thread destroyed");
}

void RFBServer::ServerThread::onFirstRef()
{
    mUpdateBarrier.close();
    run("Robin");
}

status_t RFBServer::ServerThread::readyToRun()
{
    return NO_ERROR;
}

void RFBServer::ServerThread::wake()
{
    mUpdateBarrier.open();
}

void RFBServer::ServerThread::exitAndWait()
{
    requestExit();
    mUpdateBarrier.open();
    requestExitAndWait();
}

bool RFBServer::ServerThread::threadLoop()
{
    sp<RFBServer> receiver(mReceiver.promote());
    if (receiver == 0)
        return false;

    // wait for something to do
    mUpdateBarrier.wait();

    // we're asked to quit, abort everything
    if (exitPending())
        return false;

    mUpdateBarrier.close();

    // process updates
    receiver->sendFrameBufferUpdates();
    return !exitPending();
}

// ----------------------------------------------------------------------------

void RFBServer::handshake(uint8_t major, uint8_t minor, uint32_t auth)
{
    ProtocolVersion protocolVersion(major, minor);
    if( !write(protocolVersion) )
        return;

    if ( !read(protocolVersion) )
        return;

    int maj, min;
    if ( protocolVersion.decode(maj, min) != NO_ERROR ) {
        mStatus = -1;
        return;
    }

#if DEBUG_MSG
    LOGD("client protocol string: <%s>", (char*)protocolVersion.payload());
    LOGD("client wants protocol version %d.%d\n", maj, min);
#endif

    Authentication authentication(auth);
    if( !write(authentication) )
        return;

    ClientInitialization clientInit;
    if ( !read(clientInit) )
        return;

#if DEBUG_MSG
    LOGD("client initialization: sharedFlags = %d\n", clientInit.sharedFlags());
#endif

    ServerInitialization serverInit("Android RFB");
    ServerInitialization::Payload& message(serverInit.message());
        message.framebufferWidth = htons(mFrameBuffer.width);
        message.framebufferHeight = htons(mFrameBuffer.height);
        message.serverPixelFormat.bitsPerPixel = 16;
        message.serverPixelFormat.depth = 16;
        message.serverPixelFormat.bigEndianFlag = 0;
        message.serverPixelFormat.trueColorFlag = 1;
        message.serverPixelFormat.redMax   = htons((1<<5)-1);
        message.serverPixelFormat.greenMax = htons((1<<6)-1);
        message.serverPixelFormat.blueMax  = htons((1<<5)-1);
        message.serverPixelFormat.redShift     = 11;
        message.serverPixelFormat.greenShift   = 5;
        message.serverPixelFormat.blueShift    = 0;

    mIoVec = new iovec[mFrameBuffer.height];

    write(serverInit);
}

void RFBServer::handleClientMessage(const ClientMessage& msg)
{
    switch(msg.type()) {
    case SET_PIXEL_FORMAT:
        handleSetPixelFormat(msg.messages().setPixelFormat);
        break;
    case SET_ENCODINGS:
        handleSetEncodings(msg.messages().setEncodings);
        break;
    case FRAME_BUFFER_UPDATE_REQ:
        handleFrameBufferUpdateReq(msg.messages().frameBufferUpdateRequest);
        break;
    case KEY_EVENT:
        handleKeyEvent(msg.messages().keyEvent);
        break;
    }
}

void RFBServer::handleSetPixelFormat(const SetPixelFormat& msg)
{
    if (!validatePixelFormat(msg.pixelFormat)) {
        LOGE("The builtin VNC server only supports the RGB 565 pixel format");
        LOGD("requested pixel format:");
        LOGD("bitsPerPixel:     %d", msg.pixelFormat.bitsPerPixel);
        LOGD("depth:            %d", msg.pixelFormat.depth);
        LOGD("bigEndianFlag:    %d", msg.pixelFormat.bigEndianFlag);
        LOGD("trueColorFlag:    %d", msg.pixelFormat.trueColorFlag);
        LOGD("redmax:           %d", ntohs(msg.pixelFormat.redMax));
        LOGD("bluemax:          %d", ntohs(msg.pixelFormat.greenMax));
        LOGD("greenmax:         %d", ntohs(msg.pixelFormat.blueMax));
        LOGD("redshift:         %d", msg.pixelFormat.redShift);
        LOGD("greenshift:       %d", msg.pixelFormat.greenShift);
        LOGD("blueshift:        %d", msg.pixelFormat.blueShift);
        mStatus = -1;
    }
}

bool RFBServer::validatePixelFormat(const PixelFormat& pf)
{
    if ((pf.bitsPerPixel != 16) || (pf.depth != 16))
        return false;

    if (pf.bigEndianFlag || !pf.trueColorFlag)
        return false;

    if (ntohs(pf.redMax)!=0x1F ||
        ntohs(pf.greenMax)!=0x3F ||
        ntohs(pf.blueMax)!=0x1F) {
        return false;
    }

    if (pf.redShift!=11 || pf.greenShift!=5 || pf.blueShift!=0)
        return false;

    return true;
}

void RFBServer::handleSetEncodings(const SetEncodings& msg)
{
    /* From the RFB specification:
        Sets the encoding types in which pixel data can be sent by the server.
        The order of the encoding types given in this message is a hint by the
        client as to its preference (the first encoding specified being most
        preferred). The server may or may not choose to make use of this hint.
        Pixel data may always be sent in raw encoding even if not specified
        explicitly here.
    */

    LOGW("SetEncodings received. Only RAW is supported.");
}

void RFBServer::handleFrameBufferUpdateReq(const FrameBufferUpdateRequest& msg)
{
#if DEBUG_MSG
    LOGD("handle FrameBufferUpdateRequest");
#endif

    Rect r;
    r.left = ntohs(msg.x);
    r.top = ntohs(msg.y);
    r.right = r.left + ntohs(msg.width);
    r.bottom = r.top + ntohs(msg.height);

    Mutex::Autolock _l(mRegionLock);
    mClientRegionRequest.set(r);
    if (!msg.incremental)
        mDirtyRegion.orSelf(r);

    mRobinThread->wake();
}

void RFBServer::handleKeyEvent(const KeyEvent& msg)
{
#ifdef HAVE_ANDROID_OS

    int scancode = 0;
    int code = ntohl(msg.key);

    if (code>='0' && code<='9') {
        scancode = (code & 0xF) - 1;
        if (scancode<0) scancode += 10;
        scancode += KEY_1;
    } else if (code>=0xFF50 && code<=0xFF58) {
        static const uint16_t map[] =
             {  KEY_HOME, KEY_LEFT, KEY_UP, KEY_RIGHT, KEY_DOWN,
                KEY_SOFT1, KEY_SOFT2, KEY_END, 0 };
        scancode = map[code & 0xF];
    } else if (code>=0xFFE1 && code<=0xFFEE) {
        static const uint16_t map[] =
             {  KEY_LEFTSHIFT, KEY_LEFTSHIFT,
                KEY_COMPOSE, KEY_COMPOSE,
                KEY_LEFTSHIFT, KEY_LEFTSHIFT,
                0,0,
                KEY_LEFTALT, KEY_RIGHTALT,
                0, 0, 0, 0 };
        scancode = map[code & 0xF];
    } else if ((code>='A' && code<='Z') || (code>='a' && code<='z')) {
        static const uint16_t map[] = {
                KEY_A, KEY_B, KEY_C, KEY_D, KEY_E,
                KEY_F, KEY_G, KEY_H, KEY_I, KEY_J,
                KEY_K, KEY_L, KEY_M, KEY_N, KEY_O,
                KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T,
                KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z };
        scancode = map[(code & 0x5F) - 'A'];
    } else {
        switch (code) {
            case 0x0003:    scancode = KEY_CENTER;      break;
            case 0x0020:    scancode = KEY_SPACE;       break;
            case 0x0023:    scancode = KEY_SHARP;       break;
            case 0x0033:    scancode = KEY_SHARP;       break;
            case 0x002C:    scancode = KEY_COMMA;       break;
            case 0x003C:    scancode = KEY_COMMA;       break;
            case 0x002E:    scancode = KEY_DOT;         break;
            case 0x003E:    scancode = KEY_DOT;         break;
            case 0x002F:    scancode = KEY_SLASH;       break;
            case 0x003F:    scancode = KEY_SLASH;       break;
            case 0x0032:    scancode = KEY_EMAIL;       break;
            case 0x0040:    scancode = KEY_EMAIL;       break;
            case 0xFF08:    scancode = KEY_BACKSPACE;   break;
            case 0xFF1B:    scancode = KEY_BACK;        break;
            case 0xFF09:    scancode = KEY_TAB;         break;
            case 0xFF0D:    scancode = KEY_ENTER;       break;
            case 0x002A:    scancode = KEY_STAR;        break;
            case 0xFFBE:    scancode = KEY_SEND;        break; // F1
            case 0xFFBF:    scancode = KEY_END;         break; // F2
            case 0xFFC0:    scancode = KEY_HOME;        break; // F3
            case 0xFFC5:    scancode = KEY_POWER;       break; // F8
        }
    }

#if DEBUG_MSG
   LOGD("handle KeyEvent 0x%08x, %d, scancode=%d\n", code, msg.downFlag, scancode);
#endif

    if (scancode) {
        mEventInjector.injectKey(uint16_t(scancode),
             msg.downFlag ? EventInjector::DOWN : EventInjector::UP);
    }
#endif
}

void RFBServer::waitForClientMessage(ClientMessage& msg)
{
    if ( !read(msg.payload(), 1) )
        return;

    switch(msg.type()) {

    case SET_PIXEL_FORMAT:
        read(msg.payload(1), sizeof(SetPixelFormat)-1);
        break;

    case FIX_COLOUR_MAP_ENTRIES:
        mStatus = UNKNOWN_ERROR;
        return;

    case SET_ENCODINGS:
    {
        if ( !read(msg.payload(1), sizeof(SetEncodings)-1) )
            return;

        size_t size = ntohs( msg.messages().setEncodings.numberOfEncodings ) * 4;
        if (msg.resize(sizeof(SetEncodings) + size) != NO_ERROR) {
            mStatus = NO_MEMORY;
            return;
        }

        if ( !read(msg.payload(sizeof(SetEncodings)), size) )
            return;

        break;
    }

    case FRAME_BUFFER_UPDATE_REQ:
        read(msg.payload(1), sizeof(FrameBufferUpdateRequest)-1);
        break;

    case KEY_EVENT:
        read(msg.payload(1), sizeof(KeyEvent)-1);
        break;

    case POINTER_EVENT:
        read(msg.payload(1), sizeof(PointerEvent)-1);
        break;

    case CLIENT_CUT_TEXT:
    {
        if ( !read(msg.payload(1), sizeof(ClientCutText)-1) )
            return;

        size_t size = ntohl( msg.messages().clientCutText.length );
        if (msg.resize(sizeof(ClientCutText) + size) != NO_ERROR) {
            mStatus = NO_MEMORY;
            return;
        }

        if ( !read(msg.payload(sizeof(SetEncodings)), size) )
            return;

        break;
    }

    default:
        LOGE("Unknown Message %d", msg.type());
        mStatus = UNKNOWN_ERROR;
        return;
    }
}

// ----------------------------------------------------------------------------

bool RFBServer::write(const Message& msg)
{
    write(msg.payload(), msg.size());
    return alive();
}

bool RFBServer::read(Message& msg)
{
    read(msg.payload(), msg.size());
    return alive();
}

bool RFBServer::write(const void* buffer, int size)
{
    int wr = ::write(mFD, buffer, size);
    if (wr != size) {
        //LOGE("write(%d) error %d (%s)", size, wr, strerror(errno));
        mStatus = (wr == -1) ? errno : -1;
    }
    return alive();
}

bool RFBServer::read(void* buffer, int size)
{
    int rd = ::read(mFD, buffer, size);
    if (rd != size) {
        //LOGE("read(%d) error %d (%s)", size, rd, strerror(errno));
        mStatus = (rd == -1) ? errno : -1;
    }
    return alive();
}

bool RFBServer::alive() const
{
    return  mStatus == 0;
}

bool RFBServer::isConnected() const
{
    return alive();
}

// ----------------------------------------------------------------------------

void RFBServer::frameBufferUpdated(const GGLSurface& front, const Region& reg)
{
    Mutex::Autolock _l(mRegionLock);

    // update dirty region
    mDirtyRegion.orSelf(reg);

    // remember the front-buffer
    mFrontBuffer = front;

    // The client has not requested anything, don't do anything more
    if (mClientRegionRequest.isEmpty())
        return;

    // wake the sending thread up
    mRobinThread->wake();
}

void RFBServer::sendFrameBufferUpdates()
{
    Vector<Rect> rects;
    size_t countRects;
    GGLSurface fb;

    { // Scope for the lock
        Mutex::Autolock _l(mRegionLock);
        if (mFrontBuffer.data == 0)
            return;

        const Region reg( mDirtyRegion.intersect(mClientRegionRequest) );
        if (reg.isEmpty())
            return;

        mDirtyRegion.subtractSelf(reg);
        countRects = reg.rects(rects);

        // copy the frame-buffer so we can stay responsive
        size_t bytesPerPix = bytesPerPixel(mFrameBuffer.format);
        size_t bpr = mFrameBuffer.stride * bytesPerPix;
        if (mFrameBuffer.data == 0) {
            mFrameBuffer.data = (GGLubyte*)malloc(bpr * mFrameBuffer.height);
            if (mFrameBuffer.data == 0)
            	return;
        }

        memcpy(mFrameBuffer.data, mFrontBuffer.data, bpr*mFrameBuffer.height);
        fb = mFrameBuffer;
    }

    FrameBufferUpdate msgHeader;
    msgHeader.type = 0;
    msgHeader.numberOfRectangles = htons(countRects);
    write(&msgHeader, sizeof(msgHeader));

    Rectangle rectangle;
    for (size_t i=0 ; i<countRects ; i++) {
        const Rect& r = rects[i];
        rectangle.x = htons( r.left );
        rectangle.y = htons( r.top );
        rectangle.w = htons( r.width() );
        rectangle.h = htons( r.height() );
        rectangle.encoding = htons( SetEncodings::Raw );
        write(&rectangle, sizeof(rectangle));
        size_t h = r.height();
        size_t w = r.width();
        size_t bytesPerPix = bytesPerPixel(fb.format);
        size_t bpr = fb.stride * bytesPerPix;
        size_t bytes = w * bytesPerPix;
        size_t offset = (r.top * bpr) + (r.left * bytesPerPix);
        uint8_t* src = static_cast<uint8_t*>(fb.data) + offset;
        iovec* iov = mIoVec;
        while (h--) {
            iov->iov_base = src;
            iov->iov_len = bytes;
            src += bpr;
            iov++;
        }
        size_t iovcnt = iov - mIoVec;
        int wr = ::writev(mFD, mIoVec, iovcnt);
        if (wr < 0) {
            //LOGE("write(%d) error %d (%s)", size, wr, strerror(errno));
            mStatus =  errno;
        }
    }
}

// ----------------------------------------------------------------------------

RFBServer::Message::Message(size_t size)
    : mSize(size), mAllocatedSize(size)
{
    mPayload = malloc(size);
}

RFBServer::Message::Message(void* payload, size_t size)
    : mPayload(payload), mSize(size), mAllocatedSize(0)
{
}

RFBServer::Message::~Message()
{
    if (mAllocatedSize)
        free(mPayload);
}

status_t RFBServer::Message::resize(size_t size)
{
    if (size > mAllocatedSize) {
        void* newp;
        if (mAllocatedSize) {
            newp = realloc(mPayload, size);
            if (!newp) return NO_MEMORY;
        } else {
            newp = malloc(size);
            if (!newp) return NO_MEMORY;
            memcpy(newp, mPayload, mSize);
            mAllocatedSize = size;
        }
        mPayload = newp;
    }
    mSize = size;
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

RFBServer::EventInjector::EventInjector()
    : mFD(-1)
{
}

RFBServer::EventInjector::~EventInjector()
{
}

void RFBServer::EventInjector::injectKey(uint16_t code, uint16_t value)
{
#ifdef HAVE_ANDROID_OS
    // XXX: we need to open the right event device
    int version;
    mFD = open("/dev/input/event0", O_RDWR);
    ioctl(mFD, EVIOCGVERSION, &version);

    input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_KEY;
    ev.code = code;
    ev.value = value;
    ::write(mFD, &ev, sizeof(ev));

    close(mFD);
    mFD = -1;
#endif
}


}; // namespace android

