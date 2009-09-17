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

#include <sys/socket.h>

#include "OMX.h"
#include "OMXRenderer.h"

#include "pv_omxcore.h"

#include <binder/IMemory.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/QComHardwareRenderer.h>
#include <media/stagefright/SoftwareRenderer.h>
#include <media/stagefright/TIHardwareRenderer.h>
#include <media/stagefright/VideoRenderer.h>

#include <OMX_Component.h>

namespace android {

class NodeMeta {
public:
    NodeMeta(OMX *owner)
        : mOwner(owner),
          mHandle(NULL) {
    }

    OMX *owner() const {
        return mOwner;
    }

    void setHandle(OMX_HANDLETYPE handle) {
        CHECK_EQ(mHandle, NULL);
        mHandle = handle;
    }

    OMX_HANDLETYPE handle() const {
        return mHandle;
    }

    void setObserver(const sp<IOMXObserver> &observer) {
        mObserver = observer;
    }

    sp<IOMXObserver> observer() {
        return mObserver;
    }

private:
    OMX *mOwner;
    OMX_HANDLETYPE mHandle;
    sp<IOMXObserver> mObserver;

    NodeMeta(const NodeMeta &);
    NodeMeta &operator=(const NodeMeta &);
};

////////////////////////////////////////////////////////////////////////////////

struct OMX::CallbackDispatcher : public RefBase {
    CallbackDispatcher();

    void post(const omx_message &msg);

protected:
    virtual ~CallbackDispatcher();

private:
    Mutex mLock;
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

OMX::CallbackDispatcher::CallbackDispatcher()
    : mDone(false) {
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
    NodeMeta *meta = static_cast<NodeMeta *>(msg.node);

    sp<IOMXObserver> observer = meta->observer();
    if (observer.get() != NULL) {
        observer->on_message(msg);
    }
}

// static
void *OMX::CallbackDispatcher::ThreadWrapper(void *me) {
    static_cast<CallbackDispatcher *>(me)->threadEntry();

    return NULL;
}

void OMX::CallbackDispatcher::threadEntry() {
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

class BufferMeta {
public:
    BufferMeta(OMX *owner, const sp<IMemory> &mem, bool is_backup = false)
        : mOwner(owner),
          mMem(mem),
          mIsBackup(is_backup) {
    }

    BufferMeta(OMX *owner, size_t size)
        : mOwner(owner),
          mSize(size),
          mIsBackup(false) {
    }

    void CopyFromOMX(const OMX_BUFFERHEADERTYPE *header) {
        if (!mIsBackup) {
            return;
        }

        memcpy((OMX_U8 *)mMem->pointer() + header->nOffset,
               header->pBuffer + header->nOffset,
               header->nFilledLen);
    }

    void CopyToOMX(const OMX_BUFFERHEADERTYPE *header) {
        if (!mIsBackup) {
            return;
        }

        memcpy(header->pBuffer + header->nOffset,
               (const OMX_U8 *)mMem->pointer() + header->nOffset,
               header->nFilledLen);
    }

private:
    OMX *mOwner;
    sp<IMemory> mMem;
    size_t mSize;
    bool mIsBackup;

    BufferMeta(const BufferMeta &);
    BufferMeta &operator=(const BufferMeta &);
};

// static
OMX_CALLBACKTYPE OMX::kCallbacks = {
    &OnEvent, &OnEmptyBufferDone, &OnFillBufferDone
};

// static
OMX_ERRORTYPE OMX::OnEvent(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_IN OMX_PTR pAppData,
        OMX_IN OMX_EVENTTYPE eEvent,
        OMX_IN OMX_U32 nData1,
        OMX_IN OMX_U32 nData2,
        OMX_IN OMX_PTR pEventData) {
    NodeMeta *meta = static_cast<NodeMeta *>(pAppData);
    return meta->owner()->OnEvent(meta, eEvent, nData1, nData2, pEventData);
}

// static
OMX_ERRORTYPE OMX::OnEmptyBufferDone(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_IN OMX_PTR pAppData,
        OMX_IN OMX_BUFFERHEADERTYPE* pBuffer) {
    NodeMeta *meta = static_cast<NodeMeta *>(pAppData);
    return meta->owner()->OnEmptyBufferDone(meta, pBuffer);
}

// static
OMX_ERRORTYPE OMX::OnFillBufferDone(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_IN OMX_PTR pAppData,
        OMX_IN OMX_BUFFERHEADERTYPE* pBuffer) {
    NodeMeta *meta = static_cast<NodeMeta *>(pAppData);
    return meta->owner()->OnFillBufferDone(meta, pBuffer);
}

OMX::OMX()
    : mDispatcher(new CallbackDispatcher) {
}

status_t OMX::list_nodes(List<String8> *list) {
    OMX_MasterInit();  // XXX Put this somewhere else.

    list->clear();

    OMX_U32 index = 0;
    char componentName[256];
    while (OMX_MasterComponentNameEnum(componentName, sizeof(componentName), index)
               == OMX_ErrorNone) {
        list->push_back(String8(componentName));

        ++index;
    }

    return OK;
}

status_t OMX::allocate_node(const char *name, node_id *node) {
    Mutex::Autolock autoLock(mLock);

    *node = 0;

    OMX_MasterInit();  // XXX Put this somewhere else.

    NodeMeta *meta = new NodeMeta(this);

    OMX_HANDLETYPE handle;
    OMX_ERRORTYPE err = OMX_MasterGetHandle(
            &handle, const_cast<char *>(name), meta, &kCallbacks);

    if (err != OMX_ErrorNone) {
        LOGE("FAILED to allocate omx component '%s'", name);

        delete meta;
        meta = NULL;

        return UNKNOWN_ERROR;
    }

    meta->setHandle(handle);

    *node = meta;

    return OK;
}

status_t OMX::free_node(node_id node) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);

    OMX_ERRORTYPE err = OMX_MasterFreeHandle(meta->handle());

    delete meta;
    meta = NULL;

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::send_command(
        node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err = OMX_SendCommand(meta->handle(), cmd, param, NULL);

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::get_parameter(
        node_id node, OMX_INDEXTYPE index,
        void *params, size_t size) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err = OMX_GetParameter(meta->handle(), index, params);

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::set_parameter(
        node_id node, OMX_INDEXTYPE index,
        const void *params, size_t size) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_SetParameter(meta->handle(), index, const_cast<void *>(params));

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::get_config(
        node_id node, OMX_INDEXTYPE index,
        void *params, size_t size) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err = OMX_GetConfig(meta->handle(), index, params);

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::set_config(
        node_id node, OMX_INDEXTYPE index,
        const void *params, size_t size) {
    Mutex::Autolock autoLock(mLock);

    NodeMeta *meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_SetConfig(meta->handle(), index, const_cast<void *>(params));

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

status_t OMX::use_buffer(
        node_id node, OMX_U32 port_index, const sp<IMemory> &params,
        buffer_id *buffer) {
    Mutex::Autolock autoLock(mLock);

    BufferMeta *buffer_meta = new BufferMeta(this, params);

    OMX_BUFFERHEADERTYPE *header;

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_UseBuffer(node_meta->handle(), &header, port_index, buffer_meta,
                      params->size(), static_cast<OMX_U8 *>(params->pointer()));

    if (err != OMX_ErrorNone) {
        LOGE("OMX_UseBuffer failed with error %d (0x%08x)", err, err);

        delete buffer_meta;
        buffer_meta = NULL;

        *buffer = 0;
        return UNKNOWN_ERROR;
    }

    *buffer = header;

    return OK;
}

status_t OMX::allocate_buffer(
        node_id node, OMX_U32 port_index, size_t size,
        buffer_id *buffer) {
    Mutex::Autolock autoLock(mLock);

    BufferMeta *buffer_meta = new BufferMeta(this, size);

    OMX_BUFFERHEADERTYPE *header;

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_AllocateBuffer(node_meta->handle(), &header, port_index,
                           buffer_meta, size);

    if (err != OMX_ErrorNone) {
        delete buffer_meta;
        buffer_meta = NULL;

        *buffer = 0;
        return UNKNOWN_ERROR;
    }

    *buffer = header;

    return OK;
}

status_t OMX::allocate_buffer_with_backup(
        node_id node, OMX_U32 port_index, const sp<IMemory> &params,
        buffer_id *buffer) {
    Mutex::Autolock autoLock(mLock);

    BufferMeta *buffer_meta = new BufferMeta(this, params, true);

    OMX_BUFFERHEADERTYPE *header;

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_AllocateBuffer(
                node_meta->handle(), &header, port_index, buffer_meta,
                params->size());

    if (err != OMX_ErrorNone) {
        delete buffer_meta;
        buffer_meta = NULL;

        *buffer = 0;
        return UNKNOWN_ERROR;
    }

    *buffer = header;

    return OK;
}

status_t OMX::free_buffer(node_id node, OMX_U32 port_index, buffer_id buffer) {
    OMX_BUFFERHEADERTYPE *header = (OMX_BUFFERHEADERTYPE *)buffer;
    BufferMeta *buffer_meta = static_cast<BufferMeta *>(header->pAppPrivate);

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);
    OMX_ERRORTYPE err =
        OMX_FreeBuffer(node_meta->handle(), port_index, header);

    delete buffer_meta;
    buffer_meta = NULL;

    return (err != OMX_ErrorNone) ? UNKNOWN_ERROR : OK;
}

OMX_ERRORTYPE OMX::OnEvent(
        NodeMeta *meta,
        OMX_IN OMX_EVENTTYPE eEvent,
        OMX_IN OMX_U32 nData1,
        OMX_IN OMX_U32 nData2,
        OMX_IN OMX_PTR pEventData) {
    LOGV("OnEvent(%d, %ld, %ld)", eEvent, nData1, nData2);

    omx_message msg;
    msg.type = omx_message::EVENT;
    msg.node = meta;
    msg.u.event_data.event = eEvent;
    msg.u.event_data.data1 = nData1;
    msg.u.event_data.data2 = nData2;

    mDispatcher->post(msg);

    return OMX_ErrorNone;
}
    
OMX_ERRORTYPE OMX::OnEmptyBufferDone(
        NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    LOGV("OnEmptyBufferDone buffer=%p", pBuffer);

    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER_DONE;
    msg.node = meta;
    msg.u.buffer_data.buffer = pBuffer;

    mDispatcher->post(msg);

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMX::OnFillBufferDone(
        NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    LOGV("OnFillBufferDone buffer=%p", pBuffer);
    BufferMeta *buffer_meta = static_cast<BufferMeta *>(pBuffer->pAppPrivate);
    buffer_meta->CopyFromOMX(pBuffer);

    omx_message msg;
    msg.type = omx_message::FILL_BUFFER_DONE;
    msg.node = meta;
    msg.u.extended_buffer_data.buffer = pBuffer;
    msg.u.extended_buffer_data.range_offset = pBuffer->nOffset;
    msg.u.extended_buffer_data.range_length = pBuffer->nFilledLen;
    msg.u.extended_buffer_data.flags = pBuffer->nFlags;
    msg.u.extended_buffer_data.timestamp = pBuffer->nTimeStamp;
    msg.u.extended_buffer_data.platform_private = pBuffer->pPlatformPrivate;

    mDispatcher->post(msg);

    return OMX_ErrorNone;
}

status_t OMX::observe_node(
        node_id node, const sp<IOMXObserver> &observer) {
    NodeMeta *node_meta = static_cast<NodeMeta *>(node);

    node_meta->setObserver(observer);

    return OK;
}

void OMX::fill_buffer(node_id node, buffer_id buffer) {
    OMX_BUFFERHEADERTYPE *header = (OMX_BUFFERHEADERTYPE *)buffer;
    header->nFilledLen = 0;
    header->nOffset = 0;
    header->nFlags = 0;

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);

    OMX_ERRORTYPE err =
        OMX_FillThisBuffer(node_meta->handle(), header);
    CHECK_EQ(err, OMX_ErrorNone);
}

void OMX::empty_buffer(
        node_id node,
        buffer_id buffer,
        OMX_U32 range_offset, OMX_U32 range_length,
        OMX_U32 flags, OMX_TICKS timestamp) {
    OMX_BUFFERHEADERTYPE *header = (OMX_BUFFERHEADERTYPE *)buffer;
    header->nFilledLen = range_length;
    header->nOffset = range_offset;
    header->nFlags = flags;
    header->nTimeStamp = timestamp;

    BufferMeta *buffer_meta =
        static_cast<BufferMeta *>(header->pAppPrivate);
    buffer_meta->CopyToOMX(header);

    NodeMeta *node_meta = static_cast<NodeMeta *>(node);

    OMX_ERRORTYPE err =
        OMX_EmptyThisBuffer(node_meta->handle(), header);
    CHECK_EQ(err, OMX_ErrorNone);
}

status_t OMX::get_extension_index(
        node_id node,
        const char *parameter_name,
        OMX_INDEXTYPE *index) {
    NodeMeta *node_meta = static_cast<NodeMeta *>(node);

    OMX_ERRORTYPE err =
        OMX_GetExtensionIndex(
                node_meta->handle(),
                const_cast<char *>(parameter_name), index);

    return err == OMX_ErrorNone ? OK : UNKNOWN_ERROR;
}

////////////////////////////////////////////////////////////////////////////////

sp<IOMXRenderer> OMX::createRenderer(
        const sp<ISurface> &surface,
        const char *componentName,
        OMX_COLOR_FORMATTYPE colorFormat,
        size_t encodedWidth, size_t encodedHeight,
        size_t displayWidth, size_t displayHeight) {
    VideoRenderer *impl = NULL;

    static const int OMX_QCOM_COLOR_FormatYVU420SemiPlanar = 0x7FA30C00;

    if (colorFormat == OMX_QCOM_COLOR_FormatYVU420SemiPlanar
        && !strncmp(componentName, "OMX.qcom.video.decoder.", 23)) {
        LOGW("Using QComHardwareRenderer.");
        impl =
            new QComHardwareRenderer(
                    surface,
                    displayWidth, displayHeight,
                    encodedWidth, encodedHeight);
    } else if (colorFormat == OMX_COLOR_FormatCbYCrY
            && !strcmp(componentName, "OMX.TI.Video.Decoder")) {
        LOGW("Using TIHardwareRenderer.");
        impl =
            new TIHardwareRenderer(
                    surface,
                    displayWidth, displayHeight,
                    encodedWidth, encodedHeight);
    } else {
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

