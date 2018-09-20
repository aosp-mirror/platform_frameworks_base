/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include "logd/LogEvent.h"

#include <android/util/ProtoOutputStream.h>
#include <binder/IResultReceiver.h>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include "frameworks/base/cmds/statsd/src/shell/shell_config.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "packages/UidMap.h"

namespace android {
namespace os {
namespace statsd {

/**
 * Handles atoms subscription via shell cmd.
 *
 * A shell subscription lasts *until shell exits*. Unlike config based clients, a shell client
 * communicates with statsd via file descriptors. They can subscribe pushed and pulled atoms.
 * The atoms are sent back to the client in real time, as opposed to
 * keeping the data in memory. Shell clients do not subscribe aggregated metrics, as they are
 * responsible for doing the aggregation after receiving the atom events.
 *
 * Shell client pass ShellSubscription in the proto binary format. Client can update the
 * subscription by sending a new subscription. The new subscription would replace the old one.
 * Input data stream format is:
 *
 * |size_t|subscription proto|size_t|subscription proto|....
 *
 * statsd sends the events back in Atom proto binary format. Each Atom message is preceded
 * with sizeof(size_t) bytes indicating the size of the proto message payload.
 *
 * The stream would be in the following format:
 * |size_t|atom1 proto|size_t|atom2 proto|....
 *
 * Only one shell subscriber allowed at a time, because each shell subscriber blocks one thread
 * until it exits.
 */
class ShellSubscriber : public virtual IBinder::DeathRecipient {
public:
    ShellSubscriber(sp<UidMap> uidMap) : mUidMap(uidMap){};

    /**
     * Start a new subscription.
     */
    void startNewSubscription(int inFd, int outFd, sp<IResultReceiver> resultReceiver);

    void binderDied(const wp<IBinder>& who);

    void onLogEvent(const LogEvent& event);

private:
    void readConfig(int in);

    void updateConfig(const ShellSubscription& config);

    void cleanUpLocked();

    sp<UidMap> mUidMap;

    // bool mWritten = false;

    android::util::ProtoOutputStream mProto;

    mutable std::mutex mMutex;

    std::condition_variable mShellDied;  // semaphore for waiting until shell exits.

    int mInput;  // The input file descriptor

    int mOutput;  // The output file descriptor

    sp<IResultReceiver> mResultReceiver;

    std::vector<SimpleAtomMatcher> mPushedMatchers;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
