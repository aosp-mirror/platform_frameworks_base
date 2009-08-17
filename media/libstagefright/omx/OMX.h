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

namespace android {

class NodeMeta;

class OMX : public BnOMX {
public:
    OMX();

    virtual status_t list_nodes(List<String8> *list);

    virtual status_t allocate_node(const char *name, node_id *node);
    virtual status_t free_node(node_id node);

    virtual status_t send_command(
            node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param);

    virtual status_t get_parameter(
            node_id node, OMX_INDEXTYPE index,
            void *params, size_t size);

    virtual status_t set_parameter(
            node_id node, OMX_INDEXTYPE index,
            const void *params, size_t size);

    virtual status_t get_config(
            node_id node, OMX_INDEXTYPE index,
            void *params, size_t size);

    virtual status_t set_config(
            node_id node, OMX_INDEXTYPE index,
            const void *params, size_t size);

    virtual status_t use_buffer(
            node_id node, OMX_U32 port_index, const sp<IMemory> &params,
            buffer_id *buffer);

    virtual status_t allocate_buffer(
            node_id node, OMX_U32 port_index, size_t size,
            buffer_id *buffer);

    virtual status_t allocate_buffer_with_backup(
            node_id node, OMX_U32 port_index, const sp<IMemory> &params,
            buffer_id *buffer);

    virtual status_t free_buffer(
            node_id node, OMX_U32 port_index, buffer_id buffer);

    virtual status_t observe_node(
            node_id node, const sp<IOMXObserver> &observer);

    virtual void fill_buffer(node_id node, buffer_id buffer);

    virtual void empty_buffer(
            node_id node,
            buffer_id buffer,
            OMX_U32 range_offset, OMX_U32 range_length,
            OMX_U32 flags, OMX_TICKS timestamp);

    virtual status_t get_extension_index(
            node_id node,
            const char *parameter_name,
            OMX_INDEXTYPE *index);

    virtual sp<IOMXRenderer> createRenderer(
            const sp<ISurface> &surface,
            const char *componentName,
            OMX_COLOR_FORMATTYPE colorFormat,
            size_t encodedWidth, size_t encodedHeight,
            size_t displayWidth, size_t displayHeight);

private:
    static OMX_CALLBACKTYPE kCallbacks;

    Mutex mLock;

    struct CallbackDispatcher;
    sp<CallbackDispatcher> mDispatcher;

    static OMX_ERRORTYPE OnEvent(
            OMX_IN OMX_HANDLETYPE hComponent,
            OMX_IN OMX_PTR pAppData,
            OMX_IN OMX_EVENTTYPE eEvent,
            OMX_IN OMX_U32 nData1,
            OMX_IN OMX_U32 nData2,
            OMX_IN OMX_PTR pEventData);

    static OMX_ERRORTYPE OnEmptyBufferDone(
            OMX_IN OMX_HANDLETYPE hComponent,
            OMX_IN OMX_PTR pAppData,
            OMX_IN OMX_BUFFERHEADERTYPE* pBuffer);

    static OMX_ERRORTYPE OnFillBufferDone(
            OMX_IN OMX_HANDLETYPE hComponent,
            OMX_IN OMX_PTR pAppData,
            OMX_IN OMX_BUFFERHEADERTYPE* pBuffer);

    OMX_ERRORTYPE OnEvent(
            NodeMeta *meta,
            OMX_IN OMX_EVENTTYPE eEvent,
            OMX_IN OMX_U32 nData1,
            OMX_IN OMX_U32 nData2,
            OMX_IN OMX_PTR pEventData);
        
    OMX_ERRORTYPE OnEmptyBufferDone(
            NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer);

    OMX_ERRORTYPE OnFillBufferDone(
            NodeMeta *meta, OMX_IN OMX_BUFFERHEADERTYPE *pBuffer);

    OMX(const OMX &);
    OMX &operator=(const OMX &);
};

}  // namespace android

#endif  // ANDROID_OMX_H_
