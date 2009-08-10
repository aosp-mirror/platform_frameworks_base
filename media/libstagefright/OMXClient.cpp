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

OMXClient::OMXClient() {
}

OMXClient::~OMXClient() {
    disconnect();
}

status_t OMXClient::connect() {
    Mutex::Autolock autoLock(mLock);

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

    assert(service.get() != NULL);

    mOMX = service->createOMX();
    assert(mOMX.get() != NULL);

    mReflector = new OMXClientReflector(this);

    return OK;
}

void OMXClient::disconnect() {
    Mutex::Autolock autoLock(mLock);

    if (mReflector.get() != NULL) {
        return;
    }

    assert(mObservers.isEmpty());

    mReflector->reset();
    mReflector.clear();
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

    mOMX->observe_node(node, mReflector);

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
