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

#include "Log.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // Alert

#include <android-base/unique_fd.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

namespace {
const char kDropboxTag[] = "perfetto";
}

namespace android {
namespace os {
namespace statsd {

bool CollectPerfettoTraceAndUploadToDropbox(const PerfettoDetails& config) {
    ALOGD("Starting trace collection through perfetto");

    if (!config.has_trace_config()) {
        ALOGE("The perfetto trace config is empty, aborting");
        return false;
    }

    android::base::unique_fd readPipe;
    android::base::unique_fd writePipe;
    if (!android::base::Pipe(&readPipe, &writePipe)) {
        ALOGE("pipe() failed while calling the Perfetto client: %s", strerror(errno));
        return false;
    }

    pid_t pid = fork();
    if (pid < 0) {
        ALOGE("fork() failed while calling the Perfetto client: %s", strerror(errno));
        return false;
    }

    if (pid == 0) {
        // Child process.

        // No malloc calls or library calls after this point. Remember that even
        // ALOGx (aka android_printLog()) can use dynamic memory for vsprintf().

        writePipe.reset();  // Close the write end (owned by the main process).

        // Replace stdin with |readPipe| so the main process can write into it.
        if (dup2(readPipe.get(), STDIN_FILENO) < 0) _exit(1);
        execl("/system/bin/perfetto", "perfetto", "--background", "--config", "-", "--dropbox",
              kDropboxTag, nullptr);

        // execl() doesn't return in case of success, if we get here something failed.
        _exit(1);
    }

    // Main process.

    readPipe.reset();  // Close the read end (owned by the child process).

    // Using fopen() because fwrite() has the right logic to chunking write()
    // over a pipe (see __sfvwrite()).
    FILE* writePipeStream = fdopen(writePipe.get(), "wb");
    if (!writePipeStream) {
        ALOGE("fdopen() failed while calling the Perfetto client: %s", strerror(errno));
        return false;
    }

    std::string cfgProto = config.trace_config().SerializeAsString();
    size_t bytesWritten = fwrite(cfgProto.data(), 1, cfgProto.size(), writePipeStream);
    fclose(writePipeStream);
    if (bytesWritten != cfgProto.size() || cfgProto.size() == 0) {
        ALOGE("fwrite() failed (ret: %zd) while calling the Perfetto client: %s", bytesWritten,
              strerror(errno));
        return false;
    }

    // This does NOT wait for the full duration of the trace. It just waits until the process
    // has read the config from stdin and detached.
    int childStatus = 0;
    waitpid(pid, &childStatus, 0);
    if (!WIFEXITED(childStatus) || WEXITSTATUS(childStatus) != 0) {
        ALOGE("Child process failed (0x%x) while calling the Perfetto client", childStatus);
        return false;
    }

    ALOGD("CollectPerfettoTraceAndUploadToDropbox() succeeded");
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
