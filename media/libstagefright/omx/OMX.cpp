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

#include <sys/prctl.h>
#include <sys/resource.h>

#include "../include/OMX.h"
#include "OMXRenderer.h"

#include "../include/OMXNodeInstance.h"
#include "../include/SoftwareRenderer.h"

#include <binder/IMemory.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/VideoRenderer.h>
#include <utils/threads.h>

#include "OMXMaster.h"

#include <OMX_Component.h>

namespace android {

////////////////////////////////////////////////////////////////////////////////

struct OMX::CallbackDispatcher : public RefBase {
    CallbackDispatcher(OMXNodeInstance *owner);

    void post(const omx_message &msg);

protected:
    virtual ~CallbackDispatcher();

private:
    Mutex mLock;

    OMXNodeInstance *mOwner;
    bool mDone;
    Condition mQueueChanged;
    List<omx_message> mQueue;

    pthread_t mThread;

    void dispatch(const omx_message &msg);

    static void *ThreadWrapper(void *me);
    void threadEntry();

    CallbackDispatcher(const CallbackDispatcher &);
    CallbackDispatcher &operator=(const CallbackDispatcher &);
};

OMX::CallbackDispatcher::CallbackDispatcher(OMXNodeInstance *owner)
    : mOwner(owner),
      mDone(false) {
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    pthread_create(&mThread, &attr, ThreadWrapper, this);

    pthread_attr_destroy(&attr);
}

OMX::CallbackDispatcher::~CallbackDispatcher() {
    {
        Mutex::Autolock autoLock(mLock);

        mDone = true;
        mQueueChanged.signal();
    }

    void *dummy;
    pthread_join(mThread, &dummy);
}

void OMX::CallbackDispatcher::post(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);

    mQueue.push_back(msg);
    mQueueChanged.signal();
}

void OMX::CallbackDispatcher::dispatch(const omx_message &msg) {
    if (mOwner == NULL) {
        LOGV("Would have dispatched a message to a node that's already gone.");
        return;
    }
    mOwner->onMessage(msg);
}

// static
void *OMX::CallbackDispatcher::ThreadWrapper(void *me) {
    static_cast<CallbackDispatcher *>(me)->threadEntry();

    return NULL;
}

void OMX::CallbackDispatcher::threadEntry() {
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_AUDIO);
    prctl(PR_SET_NAME, (unsigned long)"OMXCallbackDisp", 0, 0, 0);

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

bool OMX::livesLocally(pid_t pid) {
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
        LOGV("FAILED to allocate omx component '%s'", name);

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

    index = mDispatchers.indexOfKey(node);
    CHECK(index >= 0);
    mDispatchers.removeItemsAt(index);

    instance->observer()->asBinder()->unlinkToDeath(this);

    return instance->freeNode(mMaster);
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

status_t OMX::useBuffer(
        node_id node, OMX_U32 port_index, const sp<IMemory> &params,
        buffer_id *buffer) {
    return findInstance(node)->useBuffer(
            port_index, params, buffer);
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
    LOGV("OnEvent(%d, %ld, %ld)", eEvent, nData1, nData2);

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
    LOGV("OnEmptyBufferDone buffer=%p", pBuffer);

    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER_DONE;
    msg.node = node;
    msg.u.buffer_data.buffer = pBuffer;

    findDispatcher(node)->post(msg);

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMX::OnFillBufferDone(
        node_id node, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    LOGV("OnFillBufferDone buffer=%p", pBuffer);

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

////////////////////////////////////////////////////////////////////////////////

struct SharedVideoRenderer : public VideoRenderer {
    SharedVideoRenderer(void *libHandle, VideoRenderer *obj)
        : mLibHandle(libHandle),
          mObj(obj) {
    }

    virtual ~SharedVideoRenderer() {
        delete mObj;
        mObj = NULL;

        dlclose(mLibHandle);
        mLibHandle = NULL;
    }

    virtual void render(
            const void *data, size_t size, void *platformPrivate) {
        return mObj->render(data, size, platformPrivate);
    }

private:
    void *mLibHandle;
    VideoRenderer *mObj;

    SharedVideoRenderer(const SharedVideoRenderer &);
    SharedVideoRenderer &operator=(const SharedVideoRenderer &);
};

sp<IOMXRenderer> OMX::createRenderer(
        const sp<ISurface> &surface,
        const char *componentName,
        OMX_COLOR_FORMATTYPE colorFormat,
        size_t encodedWidth, size_t encodedHeight,
        size_t displayWidth, size_t displayHeight) {
    Mutex::Autolock autoLock(mLock);

    VideoRenderer *impl = NULL;

    void *libHandle = dlopen("libstagefrighthw.so", RTLD_NOW);

    if (libHandle) {
        typedef VideoRenderer *(*CreateRendererFunc)(
                const sp<ISurface> &surface,
                const char *componentName,
                OMX_COLOR_FORMATTYPE colorFormat,
                size_t displayWidth, size_t displayHeight,
                size_t decodedWidth, size_t decodedHeight);

        CreateRendererFunc func =
            (CreateRendererFunc)dlsym(
                    libHandle,
                    "_Z14createRendererRKN7android2spINS_8ISurfaceEEEPKc20"
                    "OMX_COLOR_FORMATTYPEjjjj");

        if (func) {
            impl = (*func)(surface, componentName, colorFormat,
                    displayWidth, displayHeight, encodedWidth, encodedHeight);

            if (impl) {
                impl = new SharedVideoRenderer(libHandle, impl);
                libHandle = NULL;
            }
        }

        if (libHandle) {
            dlclose(libHandle);
            libHandle = NULL;
        }
    }

    if (!impl) {
        LOGW("Using software renderer.");
        impl = new SoftwareRenderer(
                colorFormat,
                surface,
                displayWidth, displayHeight,
                encodedWidth, encodedHeight);
    }

    return new OMXRenderer(impl);
}

OMXRenderer::OMXRenderer(VideoRenderer *impl)
    : mImpl(impl) {
}

OMXRenderer::~OMXRenderer() {
    delete mImpl;
    mImpl = NULL;
}

void OMXRenderer::render(IOMX::buffer_id buffer) {
    OMX_BUFFERHEADERTYPE *header = (OMX_BUFFERHEADERTYPE *)buffer;

    mImpl->render(
            header->pBuffer + header->nOffset,
            header->nFilledLen,
            header->pPlatformPrivate);
}

}  // namespace android

