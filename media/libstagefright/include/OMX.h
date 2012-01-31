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

#ifndef ANDROID_OMX_H_
#define ANDROID_OMX_H_

#include <media/IOMX.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>

namespace android {

struct OMXMaster;
class OMXNodeInstance;

class OMX : public BnOMX,
            public IBinder::DeathRecipient {
public:
    OMX();

    virtual bool livesLocally(node_id node, pid_t pid);

    virtual status_t listNodes(List<ComponentInfo> *list);

    virtual status_t allocateNode(
            const char *name, const sp<IOMXObserver> &observer, node_id *node);

    virtual status_t freeNode(node_id node);

    virtual status_t sendCommand(
            node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param);

    virtual status_t getParameter(
            node_id node, OMX_INDEXTYPE index,
            void *params, size_t size);

    virtual status_t setParameter(
            node_id node, OMX_INDEXTYPE index,
            const void *params, size_t size);

    virtual status_t getConfig(
            node_id node, OMX_INDEXTYPE index,
            void *params, size_t size);

    virtual status_t setConfig(
            node_id node, OMX_INDEXTYPE index,
            const void *params, size_t size);

    virtual status_t getState(
            node_id node, OMX_STATETYPE* state);

    virtual status_t enableGraphicBuffers(
            node_id node, OMX_U32 port_index, OMX_BOOL enable);

    virtual status_t getGraphicBufferUsage(
            node_id node, OMX_U32 port_index, OMX_U32* usage);

    virtual status_t storeMetaDataInBuffers(
            node_id node, OMX_U32 port_index, OMX_BOOL enable);

    virtual status_t useBuffer(
            node_id node, OMX_U32 port_index, const sp<IMemory> &params,
            buffer_id *buffer);

    virtual status_t useGraphicBuffer(
            node_id node, OMX_U32 port_index,
            const sp<GraphicBuffer> &graphicBuffer, buffer_id *buffer);

    virtual status_t allocateBuffer(
            node_id node, OMX_U32 port_index, size_t size,
            buffer_id *buffer, void **buffer_data);

    virtual status_t allocateBufferWithBackup(
            node_id node, OMX_U32 port_index, const sp<IMemory> &params,
            buffer_id *buffer);

    virtual status_t freeBuffer(
            node_id node, OMX_U32 port_index, buffer_id buffer);

    virtual status_t fillBuffer(node_id node, buffer_id buffer);

    virtual status_t emptyBuffer(
            node_id node,
            buffer_id buffer,
            OMX_U32 range_offset, OMX_U32 range_length,
            OMX_U32 flags, OMX_TICKS timestamp);

    virtual status_t getExtensionIndex(
            node_id node,
            const char *parameter_name,
            OMX_INDEXTYPE *index);

    virtual void binderDied(const wp<IBinder> &the_late_who);

    OMX_ERRORTYPE OnEvent(
            node_id node,
            OMX_IN OMX_EVENTTYPE eEvent,
            OMX_IN OMX_U32 nData1,
            OMX_IN OMX_U32 nData2,
            OMX_IN OMX_PTR pEventData);

    OMX_ERRORTYPE OnEmptyBufferDone(
            node_id node, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer);

    OMX_ERRORTYPE OnFillBufferDone(
            node_id node, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer);

    void invalidateNodeID(node_id node);

protected:
    virtual ~OMX();

private:
    struct CallbackDispatcherThread;
    struct CallbackDispatcher;

    Mutex mLock;
    OMXMaster *mMaster;
    int32_t mNodeCounter;

    KeyedVector<wp<IBinder>, OMXNodeInstance *> mLiveNodes;
    KeyedVector<node_id, OMXNodeInstance *> mNodeIDToInstance;
    KeyedVector<node_id, sp<CallbackDispatcher> > mDispatchers;

    node_id makeNodeID(OMXNodeInstance *instance);
    OMXNodeInstance *findInstance(node_id node);
    sp<CallbackDispatcher> findDispatcher(node_id node);

    void invalidateNodeID_l(node_id node);

    OMX(const OMX &);
    OMX &operator=(const OMX &);
};

}  // namespace android

#endif  // ANDROID_OMX_H_
