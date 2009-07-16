/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "OMXClient"
#include <utils/Log.h>

#include <sys/socket.h>

#undef NDEBUG
#include <assert.h>

#include <binder/IServiceManager.h>
#include <media/IMediaPlayerService.h>
#include <media/IOMX.h>
#include <media/stagefright/OMXClient.h>

namespace android {

OMXClient::OMXClient()
    : mSock(-1) {
}

OMXClient::~OMXClient() {
    disconnect();
}

status_t OMXClient::connect() {
    Mutex::Autolock autoLock(mLock);

    if (mSock >= 0) {
        return UNKNOWN_ERROR;
    }

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

    assert(service.get() != NULL);

    mOMX = service->createOMX();
    assert(mOMX.get() != NULL);

#if IOMX_USES_SOCKETS
    status_t result = mOMX->connect(&mSock);
    if (result != OK) {
        mSock = -1;

        mOMX = NULL;
        return result;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    int err = pthread_create(&mThread, &attr, ThreadWrapper, this);
    assert(err == 0);

    pthread_attr_destroy(&attr);
#else
    mReflector = new OMXClientReflector(this);
#endif

    return OK;
}

void OMXClient::disconnect() {
    {
        Mutex::Autolock autoLock(mLock);

        if (mSock < 0) {
            return;
        }

        assert(mObservers.isEmpty());
    }

#if IOMX_USES_SOCKETS
    omx_message msg;
    msg.type = omx_message::DISCONNECT;
    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));

    void *dummy;
    pthread_join(mThread, &dummy);
#else
    mReflector->reset();
    mReflector.clear();
#endif
}

#if IOMX_USES_SOCKETS
// static
void *OMXClient::ThreadWrapper(void *me) {
    ((OMXClient *)me)->threadEntry();

    return NULL;
}

void OMXClient::threadEntry() {
    bool done = false;
    while (!done) {
        omx_message msg;
        ssize_t n = recv(mSock, &msg, sizeof(msg), 0);

        if (n <= 0) {
            break;
        }

        done = onOMXMessage(msg);
    }

    Mutex::Autolock autoLock(mLock);
    close(mSock);
    mSock = -1;
}
#endif

status_t OMXClient::fillBuffer(IOMX::node_id node, IOMX::buffer_id buffer) {
#if !IOMX_USES_SOCKETS
    mOMX->fill_buffer(node, buffer);
#else
    if (mSock < 0) {
        return UNKNOWN_ERROR;
    }

    omx_message msg;
    msg.type = omx_message::FILL_BUFFER;
    msg.u.buffer_data.node = node;
    msg.u.buffer_data.buffer = buffer;

    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OK;
}

status_t OMXClient::emptyBuffer(
        IOMX::node_id node, IOMX::buffer_id buffer,
        OMX_U32 range_offset, OMX_U32 range_length,
        OMX_U32 flags, OMX_TICKS timestamp) {
#if !IOMX_USES_SOCKETS
    mOMX->empty_buffer(
            node, buffer, range_offset, range_length, flags, timestamp);
#else
    if (mSock < 0) {
        return UNKNOWN_ERROR;
    }

    // XXX I don't like all this copying...

    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER;
    msg.u.extended_buffer_data.node = node;
    msg.u.extended_buffer_data.buffer = buffer;
    msg.u.extended_buffer_data.range_offset = range_offset;
    msg.u.extended_buffer_data.range_length = range_length;
    msg.u.extended_buffer_data.flags = flags;
    msg.u.extended_buffer_data.timestamp = timestamp;

    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OK;
}

status_t OMXClient::send_command(
        IOMX::node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param) {
#if !IOMX_USES_SOCKETS
    return mOMX->send_command(node, cmd, param);
#else
    if (mSock < 0) {
        return UNKNOWN_ERROR;
    }

    omx_message msg;
    msg.type = omx_message::SEND_COMMAND;
    msg.u.send_command_data.node = node;
    msg.u.send_command_data.cmd = cmd;
    msg.u.send_command_data.param = param;

    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OK;
}

status_t OMXClient::registerObserver(
        IOMX::node_id node, OMXObserver *observer) {
    Mutex::Autolock autoLock(&mLock);

    ssize_t index = mObservers.indexOfKey(node);
    if (index >= 0) {
        return UNKNOWN_ERROR;
    }

    mObservers.add(node, observer);
    observer->start();

#if !IOMX_USES_SOCKETS
    mOMX->observe_node(node, mReflector);
#endif

    return OK;
}

void OMXClient::unregisterObserver(IOMX::node_id node) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mObservers.indexOfKey(node);
    assert(index >= 0);

    if (index < 0) {
        return;
    }

    OMXObserver *observer = mObservers.valueAt(index);
    observer->stop();
    mObservers.removeItemsAt(index);
}

bool OMXClient::onOMXMessage(const omx_message &msg) {
    bool done = false;

    switch (msg.type) {
        case omx_message::EVENT:
        {
            LOGV("OnEvent node:%p event:%d data1:%ld data2:%ld",
                 msg.u.event_data.node,
                 msg.u.event_data.event,
                 msg.u.event_data.data1,
                 msg.u.event_data.data2);

            break;
        }

        case omx_message::FILL_BUFFER_DONE:
        {
            LOGV("FillBufferDone %p", msg.u.extended_buffer_data.buffer);
            break;
        }

        case omx_message::EMPTY_BUFFER_DONE:
        {
            LOGV("EmptyBufferDone %p", msg.u.buffer_data.buffer);
            break;
        }

#if IOMX_USES_SOCKETS
        case omx_message::DISCONNECTED:
        {
            LOGV("Disconnected");
            done = true;
            break;
        }
#endif

        default:
            LOGE("received unknown omx_message type %d", msg.type);
            break;
    }

    Mutex::Autolock autoLock(mLock);
    ssize_t index = mObservers.indexOfKey(msg.u.buffer_data.node);

    if (index >= 0) {
        mObservers.editValueAt(index)->postMessage(msg);
    }

    return done;
}

////////////////////////////////////////////////////////////////////////////////

OMXObserver::OMXObserver() {
}

OMXObserver::~OMXObserver() {
}

void OMXObserver::start() {
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    int err = pthread_create(&mThread, &attr, ThreadWrapper, this);
    assert(err == 0);

    pthread_attr_destroy(&attr);
}

void OMXObserver::stop() {
    omx_message msg;
    msg.type = omx_message::QUIT_OBSERVER;
    postMessage(msg);

    void *dummy;
    pthread_join(mThread, &dummy);
}

void OMXObserver::postMessage(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);
    mQueue.push_back(msg);
    mQueueNotEmpty.signal();
}

// static
void *OMXObserver::ThreadWrapper(void *me) {
    static_cast<OMXObserver *>(me)->threadEntry();

    return NULL;
}

void OMXObserver::threadEntry() {
    for (;;) {
        omx_message msg;

        {
            Mutex::Autolock autoLock(mLock);
            while (mQueue.empty()) {
                mQueueNotEmpty.wait(mLock);
            }

            msg = *mQueue.begin();
            mQueue.erase(mQueue.begin());
        }

        if (msg.type == omx_message::QUIT_OBSERVER) {
            break;
        }

        onOMXMessage(msg);
    }
}

////////////////////////////////////////////////////////////////////////////////

OMXClientReflector::OMXClientReflector(OMXClient *client)
    : mClient(client) {
}

void OMXClientReflector::on_message(const omx_message &msg) {
    if (mClient != NULL) {
        mClient->onOMXMessage(msg);
    }
}

void OMXClientReflector::reset() {
    mClient = NULL;
}

}  // namespace android
