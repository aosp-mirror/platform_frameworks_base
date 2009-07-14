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

#undef NDEBUG
#include <assert.h>

#include "OMX.h"
#include "pv_omxcore.h"

#include <binder/IMemory.h>

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
        assert(mHandle == NULL);
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
#if IOMX_USES_SOCKETS
    : mSock(-1)
#endif
{
}

OMX::~OMX() {
#if IOMX_USES_SOCKETS
    assert(mSock < 0);
#endif
}

#if IOMX_USES_SOCKETS
status_t OMX::connect(int *sd) {
    Mutex::Autolock autoLock(mLock);

    if (mSock >= 0) {
        return UNKNOWN_ERROR;
    }

    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) < 0) {
        return UNKNOWN_ERROR;
    }

    mSock = sockets[0];
    *sd = sockets[1];

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    int err = pthread_create(&mThread, &attr, ThreadWrapper, this);
    assert(err == 0);

    pthread_attr_destroy(&attr);

    return OK;
}

// static
void *OMX::ThreadWrapper(void *me) {
    ((OMX *)me)->threadEntry();

    return NULL;
}

void OMX::threadEntry() {
    bool done = false;
    while (!done) {
        omx_message msg;
        ssize_t n = recv(mSock, &msg, sizeof(msg), 0);

        if (n <= 0) {
            break;
        }

        Mutex::Autolock autoLock(mLock);

        switch (msg.type) {
            case omx_message::FILL_BUFFER:
            {
                OMX_BUFFERHEADERTYPE *header =
                    static_cast<OMX_BUFFERHEADERTYPE *>(
                            msg.u.buffer_data.buffer);

                header->nFilledLen = 0;
                header->nOffset = 0;
                header->nFlags = 0;

                NodeMeta *node_meta = static_cast<NodeMeta *>(
                        msg.u.buffer_data.node);
                
                LOGV("FillThisBuffer buffer=%p", header);

                OMX_ERRORTYPE err =
                    OMX_FillThisBuffer(node_meta->handle(), header);
                assert(err == OMX_ErrorNone);
                break;
            }

            case omx_message::EMPTY_BUFFER:
            {
                OMX_BUFFERHEADERTYPE *header =
                    static_cast<OMX_BUFFERHEADERTYPE *>(
                            msg.u.extended_buffer_data.buffer);

                header->nFilledLen = msg.u.extended_buffer_data.range_length;
                header->nOffset = msg.u.extended_buffer_data.range_offset;
                header->nFlags = msg.u.extended_buffer_data.flags;
                header->nTimeStamp = msg.u.extended_buffer_data.timestamp;

                BufferMeta *buffer_meta =
                    static_cast<BufferMeta *>(header->pAppPrivate);
                buffer_meta->CopyToOMX(header);

                NodeMeta *node_meta = static_cast<NodeMeta *>(
                        msg.u.extended_buffer_data.node);

                LOGV("EmptyThisBuffer buffer=%p", header);

                OMX_ERRORTYPE err =
                    OMX_EmptyThisBuffer(node_meta->handle(), header);
                assert(err == OMX_ErrorNone);
                break;
            }

            case omx_message::SEND_COMMAND:
            {
                NodeMeta *node_meta = static_cast<NodeMeta *>(
                        msg.u.send_command_data.node);

                OMX_ERRORTYPE err =
                    OMX_SendCommand(
                            node_meta->handle(), msg.u.send_command_data.cmd,
                            msg.u.send_command_data.param, NULL);
                assert(err == OMX_ErrorNone);
                break;
            }

            case omx_message::DISCONNECT:
            {
                omx_message msg;
                msg.type = omx_message::DISCONNECTED;
                ssize_t n = send(mSock, &msg, sizeof(msg), 0);
                assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
                done = true;
                break;
            }

            default:
                LOGE("received unknown omx_message type %d", msg.type);
                break;
        }
    }

    Mutex::Autolock autoLock(mLock);
    close(mSock);
    mSock = -1;
}
#endif

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

#if IOMX_USES_SOCKETS
    if (mSock < 0) {
        return UNKNOWN_ERROR;
    }
#endif

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
    msg.u.event_data.node = meta;
    msg.u.event_data.event = eEvent;
    msg.u.event_data.data1 = nData1;
    msg.u.event_data.data2 = nData2;

#if !IOMX_USES_SOCKETS
    sp<IOMXObserver> observer = meta->observer();
    if (observer.get() != NULL) {
        observer->on_message(msg);
    }
#else
    assert(mSock >= 0);

    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OMX_ErrorNone;
}
    
OMX_ERRORTYPE OMX::OnEmptyBufferDone(
        NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    LOGV("OnEmptyBufferDone buffer=%p", pBuffer);

    omx_message msg;
    msg.type = omx_message::EMPTY_BUFFER_DONE;
    msg.u.buffer_data.node = meta;
    msg.u.buffer_data.buffer = pBuffer;

#if !IOMX_USES_SOCKETS
    sp<IOMXObserver> observer = meta->observer();
    if (observer.get() != NULL) {
        observer->on_message(msg);
    }
#else
    assert(mSock >= 0);
    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OMX_ErrorNone;
}

OMX_ERRORTYPE OMX::OnFillBufferDone(
        NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer) {
    LOGV("OnFillBufferDone buffer=%p", pBuffer);
    BufferMeta *buffer_meta = static_cast<BufferMeta *>(pBuffer->pAppPrivate);
    buffer_meta->CopyFromOMX(pBuffer);

    omx_message msg;
    msg.type = omx_message::FILL_BUFFER_DONE;
    msg.u.extended_buffer_data.node = meta;
    msg.u.extended_buffer_data.buffer = pBuffer;
    msg.u.extended_buffer_data.range_offset = pBuffer->nOffset;
    msg.u.extended_buffer_data.range_length = pBuffer->nFilledLen;
    msg.u.extended_buffer_data.flags = pBuffer->nFlags;
    msg.u.extended_buffer_data.timestamp = pBuffer->nTimeStamp;
    msg.u.extended_buffer_data.platform_private = pBuffer->pPlatformPrivate;

#if !IOMX_USES_SOCKETS
    sp<IOMXObserver> observer = meta->observer();
    if (observer.get() != NULL) {
        observer->on_message(msg);
    }
#else
    assert(mSock >= 0);

    ssize_t n = send(mSock, &msg, sizeof(msg), 0);
    assert(n > 0 && static_cast<size_t>(n) == sizeof(msg));
#endif

    return OMX_ErrorNone;
}

#if !IOMX_USES_SOCKETS
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
    assert(err == OMX_ErrorNone);
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
    assert(err == OMX_ErrorNone);
}
#endif

}  // namespace android

