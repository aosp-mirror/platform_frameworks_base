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

#ifndef OMX_CLIENT_H_

#define OMX_CLIENT_H_

#include <media/IOMX.h>

#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/threads.h>

namespace android {

class OMXObserver {
public:
    OMXObserver();
    virtual ~OMXObserver();

    void postMessage(const omx_message &msg);

protected:
    virtual void onOMXMessage(const omx_message &msg) = 0;

private:
    friend class OMXClient;

    pthread_t mThread;
    Mutex mLock;
    Condition mQueueNotEmpty;
    List<omx_message> mQueue;

    void start();
    void stop();

    static void *ThreadWrapper(void *me);
    void threadEntry();

    OMXObserver(const OMXObserver &);
    OMXObserver &operator=(const OMXObserver &);
};

class OMXClient;

class OMXClientReflector : public BnOMXObserver {
public:
    OMXClientReflector(OMXClient *client);

    virtual void on_message(const omx_message &msg);
    void reset();

private:
    OMXClient *mClient;

    OMXClientReflector(const OMXClientReflector &);
    OMXClientReflector &operator=(const OMXClientReflector &);
};

class OMXClient {
public:
    friend class OMXClientReflector;

    OMXClient();
    ~OMXClient();

    status_t connect();
    void disconnect();

    sp<IOMX> interface() {
        return mOMX;
    }

    status_t registerObserver(IOMX::node_id node, OMXObserver *observer);
    void unregisterObserver(IOMX::node_id node);

    status_t fillBuffer(IOMX::node_id node, IOMX::buffer_id buffer);

    status_t emptyBuffer(
            IOMX::node_id node, IOMX::buffer_id buffer,
            OMX_U32 range_offset, OMX_U32 range_length,
            OMX_U32 flags, OMX_TICKS timestamp);

    status_t send_command(
            IOMX::node_id node, OMX_COMMANDTYPE cmd, OMX_S32 param);

private:
    sp<IOMX> mOMX;

    int mSock;
    Mutex mLock;
    pthread_t mThread;

    KeyedVector<IOMX::node_id, OMXObserver *> mObservers;

    sp<OMXClientReflector> mReflector;

#if IOMX_USES_SOCKETS
    static void *ThreadWrapper(void *me);
    void threadEntry();
#endif

    bool onOMXMessage(const omx_message &msg);

    OMXClient(const OMXClient &);
    OMXClient &operator=(const OMXClient &);
};

}  // namespace android

#endif  // OMX_CLIENT_H_
