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

#ifndef OMX_HARNESS_H_

#define OMX_HARNESS_H_

#include <media/IOMX.h>
#include <utils/List.h>
#include <utils/Vector.h>
#include <utils/threads.h>

#include <OMX_Component.h>

namespace android {

class MemoryDealer;

struct Harness : public BnOMXObserver {
    enum BufferFlags {
        kBufferBusy = 1
    };
    struct Buffer {
        IOMX::buffer_id mID;
        sp<IMemory> mMemory;
        uint32_t mFlags;
    };

    Harness();

    status_t initCheck() const;

    status_t dequeueMessageForNode(
            IOMX::node_id node, omx_message *msg, int64_t timeoutUs = -1);

    status_t dequeueMessageForNodeIgnoringBuffers(
            IOMX::node_id node,
            Vector<Buffer> *inputBuffers,
            Vector<Buffer> *outputBuffers,
            omx_message *msg, int64_t timeoutUs = -1);

    status_t getPortDefinition(
            IOMX::node_id node, OMX_U32 portIndex,
            OMX_PARAM_PORTDEFINITIONTYPE *def);

    status_t allocatePortBuffers(
            const sp<MemoryDealer> &dealer,
            IOMX::node_id node, OMX_U32 portIndex,
            Vector<Buffer> *buffers);

    status_t setRole(IOMX::node_id node, const char *role);

    status_t testStateTransitions(
            const char *componentName, const char *componentRole);

    status_t testSeek(
            const char *componentName, const char *componentRole);

    status_t test(
            const char *componentName, const char *componentRole);

    status_t testAll();

    virtual void onMessage(const omx_message &msg);

protected:
    virtual ~Harness();

private:
    friend struct NodeReaper;

    Mutex mLock;

    status_t mInitCheck;
    sp<IOMX> mOMX;
    List<omx_message> mMessageQueue;
    Condition mMessageAddedCondition;

    status_t initOMX();

    bool handleBufferMessage(
            const omx_message &msg,
            Vector<Buffer> *inputBuffers,
            Vector<Buffer> *outputBuffers);

    Harness(const Harness &);
    Harness &operator=(const Harness &);
};

}  // namespace android

#endif  // OMX_HARNESS_H_
