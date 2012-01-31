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
#define LOG_TAG "OMX"
#include <utils/Log.h>

#include <dlfcn.h>

#include "../include/OMX.h"

#include "../include/OMXNodeInstance.h"

#include <binder/IMemory.h>
#include <media/stagefright/MediaDebug.h>
#include <utils/threads.h>

#include "OMXMaster.h"

#include <OMX_Component.h>

namespace android {

////////////////////////////////////////////////////////////////////////////////

// This provides the underlying Thread used by CallbackDispatcher.
// Note that deriving CallbackDispatcher from Thread does not work.

struct OMX::CallbackDispatcherThread : public Thread {
    CallbackDispatcherThread(CallbackDispatcher *dispatcher)
        : mDispatcher(dispatcher) {
    }

private:
    CallbackDispatcher *mDispatcher;

    bool threadLoop();

    CallbackDispatcherThread(const CallbackDispatcherThread &);
    CallbackDispatcherThread &operator=(const CallbackDispatcherThread &);
};

////////////////////////////////////////////////////////////////////////////////

struct OMX::CallbackDispatcher : public RefBase {
    CallbackDispatcher(OMXNodeInstance *owner);

    void post(const omx_message &msg);

    bool loop();

protected:
    virtual ~CallbackDispatcher();

private:
    Mutex mLock;

    OMXNodeInstance *mOwner;
    bool mDone;
    Condition mQueueChanged;
    List<omx_message> mQueue;

    sp<CallbackDispatcherThread> mThread;

    void dispatch(const omx_message &msg);

    CallbackDispatcher(const CallbackDispatcher &);
    CallbackDispatcher &operator=(const CallbackDispatcher &);
};

OMX::CallbackDispatcher::CallbackDispatcher(OMXNodeInstance *owner)
    : mOwner(owner),
      mDone(false) {
    mThread = new CallbackDispatcherThread(this);
    mThread->run("OMXCallbackDisp", ANDROID_PRIORITY_FOREGROUND);
}

OMX::CallbackDispatcher::~CallbackDispatcher() {
    {
        Mutex::Autolock autoLock(mLock);

        mDone = true;
        mQueueChanged.signal();
    }

    // A join on self can happen if the last ref to CallbackDispatcher
    // is released within the CallbackDispatcherThread loop
    status_t status = mThread->join();
    if (status != WOULD_BLOCK) {
        // Other than join to self, the only other error return codes are
        // whatever readyToRun() returns, and we don't override that
        CHECK_EQ(status, NO_ERROR);
    }
}

void OMX::CallbackDispatcher::post(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);

    mQueue.push_back(msg);
    mQueueChanged.signal();
}

void OMX::CallbackDispatcher::dispatch(const omx_message &msg) {
    if (mOwner == NULL) {
        ALOGV("Would have dispatched a message to a node that's already gone.");
        return;
    }
    mOwner->onMessage(msg);
}

bool OMX::CallbackDispatcher::loop() {
    for (;;) {
        omx_message msg;

        {
            Mutex::Autolock autoLock(mLock);
            while (!mDone && mQueue.empty()) {
                mQueueChanged.wait(mLock);
            }

            if (mDone) {
                break;
            }

            msg = *mQueue.begin();
            mQueue.erase(mQueue.begin());
        }

        dispatch(msg);
    }

    return false;
}

////////////////////////////////////////////////////////////////////////////////

bool OMX::CallbackDispatcherThread::threadLoop() {
    return mDispatcher->loop();
}

////////////////////////////////////////////////////////////////////////////////

OMX::OMX()
    : mMaster(new OMXMaster),
      mNodeCounter(0) {
}

OMX::~OMX() {
    delete mMaster;
    mMaster = NULL;
}

void OMX::binderDied(const wp<IBinder> &the_late_who) {
    OMXNodeInstance *instance;

    {
        Mutex::Autolock autoLock(mLock);

        ssize_t index = mLiveNodes.indexOfKey(the_late_who);
        CHECK(index >= 0);

        instance = mLiveNodes.editValueAt(index);
        mLiveNodes.removeItemsAt(index);

        index = mDispatchers.indexOfKey(instance->nodeID());
        CHECK(index >= 0);
        mDispatchers.removeItemsAt(index);

        invalidateNodeID_l(instance->nodeID());
    }

    instance->onObserverDied(mMaster);
}

bool OMX::livesLocally(node_id node, pid_t pid) {
    return pid == getpid();
}

status_t OMX::listNodes(List<ComponentInfo> *list) {
    list->clear();

    OMX_U32 index = 0;
    char componentName[256];
    while (mMaster->enumerateComponents(
                componentName, sizeof(componentName), index) == OMX_ErrorNone) {
        list->push_back(ComponentInfo());
        ComponentInfo &info = *--list->end();

        info.mName = componentName;

        Vector<String8> roles;
        OMX_ERRORTYPE err =
            mMaster->getRolesOfComponent(componentName, &roles);

        if (err == OMX_ErrorNone) {
            for (OMX_U32 i = 0; i < roles.size(); ++i) {
                info.mRoles.push_back(roles[i]);
            }
        }

        ++index;
    }

    return OK;
}

status_t OMX::allocateNode(
        const char *name, const sp<IOMXObserver> &observer, node_id *node) {
    Mutex::Autolock autoLock(mLock);

    *node = 0;

    OMXNodeInstance *instance = new OMXNodeInstance(this, observer);

    OMX_COMPONENTTYPE *handle;
    OMX_ERRORTYPE err = mMaster->makeComponentInstance(
            name, &OMXNodeInstance::kCallbacks,
            instance, &handle);

    if (err != OMX_ErrorNone) {
        ALOGV("FAILED to allocate omx component '%s'", name);

        instance->onGetHandleFailed();

        return UNKNOWN_ERROR;
    }

    *node = makeNodeID(instance);
    mDispatchers.add(*node, new CallbackDispatcher(instance));

    instance->setHandle(*node, handle);

    mLiveNodes.add(observer->asBinder(), instance);
    observer->asBinder()->linkToDeath(this);

    return OK;
}

status_t OMX::freeNode(node_id node) {
    OMXNodeInstance *instance = findInstance(node);

    ssize_t index = mLiveNodes.indexOfKey(instance->observer()->asBinder());
    CHECK(index >= 0);
    mLiveNodes.removeItemsAt(index);

    instance->observer()->asBinder()->unlinkToDeath(this);

    status_t err = instance->freeNode(mMaster);

    {
        Mutex::Autolock autoLock(mLock);
        index = mDispatchers.indexOfKey(node);
        CHECK(index >= 0);
        mDispatchers.removeItemsAt(index);
    }

    return err;
}

status_t OMX::sendCommand(
        node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param) {
    return findInstance(node)->sendCommand(cmd, param);
}

status_t OMX::getParameter(
        node_id node, OMX_INDEXTYPE index,
        void *params, size_t size) {
    return findInstance(node)->getParameter(
            index, params, size);
}

status_t OMX::setParameter(
        node_id node, OMX_INDEXTYPE index,
        const void *params, size_t size) {
    return findInstance(node)->setParameter(
            index, params, size);
}

status_t OMX::getConfig(
        node_id node, OMX_INDEXTYPE index,
        void *params, size_t size) {
    return findInstance(node)->getConfig(
            index, params, size);
}

status_t OMX::setConfig(
        node_id node, OMX_INDEXTYPE index,
        const void *params, size_t size) {
    return findInstance(node)->setConfig(
            index, params, size);
}

status_t OMX::getState(
        node_id node, OMX_STATETYPE* state) {
    return findInstance(node)->getState(
            state);
}

status_t OMX::enableGraphicBuffers(
        node_id node, OMX_U32 port_index, OMX_BOOL enable) {
    return findInstance(node)->enableGraphicBuffers(port_index, enable);
}

status_t OMX::getGraphicBufferUsage(
        node_id node, OMX_U32 port_index, OMX_U32* usage) {
    return findInstance(node)->getGraphicBufferUsage(port_index, usage);
}

status_t OMX::storeMetaDataInBuffers(
        node_id node, OMX_U32 port_index, OMX_BOOL enable) {
    return findInstance(node)->storeMetaDataInBuffers(port_index, enable);
}

status_t OMX::useBuffer(
        node_id node, OMX_U32 port_index, const sp<IMemory> &params,
        buffer_id *buffer) {
    return findInstance(node)->useBuffer(
            port_index, params, buffer);
}

status_t OMX::useGraphicBuffer(
        node_id node, OMX_U32 port_index,
        const sp<GraphicBuffer> &graphicBuffer, buffer_id *buffer) {
    return findInstance(node)->useGraphicBuffer(
            port_index, graphicBuffer, buffer);
}

status_t OMX::allocateBuffer(
        node_id node, OMX_U32 port_index, size_t size,
        buffer_id *buffer, void **buffer_data) {
    return findInstance(node)->allocateBuffer(
            port_index, size, buffer, buffer_data);
}

status_t OMX::allocateBufferWithBackup(
        node_id node, OMX_U32 port_index, const sp<IMemory> &params,
        buffer_id *buffer) {
    return findInstance(node)->allocateBufferWithBackup(
            port_index, params, buffer);
}

status_t OMX::freeBuffer(node_id node, OMX_U32 port_index, buffer_id buffer) {
    return findInstance(node)->freeBuffer(
            port_index, buffer);
}

status_t OMX::fillBuffer(node_id node, buffer_id buffer) {
    return findInstance(node)->fillBuffer(buffer);
}

status_t OMX::emptyBuffer(
        node_id node,
        buffer_id buffer,
        OMX_U32 range_offset, OMX_U32 range_length,
        OMX_U32 flags, OMX_TICKS timestamp) {
    return findInstance(node)->emptyBuffer(
            buffer, range_offset, range_length, flags, timestamp);
}

status_t OMX::getExtensionIndex(
        node_id node,
        const char *parameter_name,
        OMX_INDEXTYPE *index) {
    return findInstance(node)->getExtensionIndex(
            parameter_name, index);
}

OMX_ERRORTYPE OMX::OnEvent(
        node_id node,
        OMX_IN OMX_EVENTTYPE eEvent,
        OMX_IN OMX_U32 nData1,
        OMX_IN OMX_U32 nData2,
        OMX_IN OMX_PTR pEventData) {
    ALOGV("OnEvent(%d, %ld, %ld)", eEvent, nData1, nData2);

    omx_message msg;
    msg.type = omx_message::EVENT;
    msg.node = node;
    msg.u.event_data.event = eEvent;
    msg.u.event_data.data1 = nData1;
    msg.u.event_data.data2 = nData2;

    findDispatcher(node)->post(msg);

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMX::OnEmptyBufferDone(
        node_id node, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    ALOGV("OnEmptyBufferDone buffer=%p", pBuffer);

    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER_DONE;
    msg.node = node;
    msg.u.buffer_data.buffer = pBuffer;

    findDispatcher(node)->post(msg);

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMX::OnFillBufferDone(
        node_id node, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    ALOGV("OnFillBufferDone buffer=%p", pBuffer);

    omx_message msg;
    msg.type = omx_message::FILL_BUFFER_DONE;
    msg.node = node;
    msg.u.extended_buffer_data.buffer = pBuffer;
    msg.u.extended_buffer_data.range_offset = pBuffer->nOffset;
    msg.u.extended_buffer_data.range_length = pBuffer->nFilledLen;
    msg.u.extended_buffer_data.flags = pBuffer->nFlags;
    msg.u.extended_buffer_data.timestamp = pBuffer->nTimeStamp;
    msg.u.extended_buffer_data.platform_private = pBuffer->pPlatformPrivate;
    msg.u.extended_buffer_data.data_ptr = pBuffer->pBuffer;

    findDispatcher(node)->post(msg);

    return OMX_ErrorNone;
}

OMX::node_id OMX::makeNodeID(OMXNodeInstance *instance) {
    // mLock is already held.

    node_id node = (node_id)++mNodeCounter;
    mNodeIDToInstance.add(node, instance);

    return node;
}

OMXNodeInstance *OMX::findInstance(node_id node) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mNodeIDToInstance.indexOfKey(node);

    return index < 0 ? NULL : mNodeIDToInstance.valueAt(index);
}

sp<OMX::CallbackDispatcher> OMX::findDispatcher(node_id node) {
    Mutex::Autolock autoLock(mLock);

    ssize_t index = mDispatchers.indexOfKey(node);

    return index < 0 ? NULL : mDispatchers.valueAt(index);
}

void OMX::invalidateNodeID(node_id node) {
    Mutex::Autolock autoLock(mLock);
    invalidateNodeID_l(node);
}

void OMX::invalidateNodeID_l(node_id node) {
    // mLock is held.
    mNodeIDToInstance.removeItem(node);
}

}  // namespace android
