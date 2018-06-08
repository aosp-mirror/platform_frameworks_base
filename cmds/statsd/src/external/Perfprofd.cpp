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

#include "Perfprofd.h"

#define DEBUG false  // STOPSHIP if true
#include "config/ConfigKey.h"
#include "Log.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

#include <binder/IServiceManager.h>

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // Alert

#include "android/os/IPerfProfd.h"

namespace android {
namespace os {
namespace statsd {

bool CollectPerfprofdTraceAndUploadToDropbox(const PerfprofdDetails& config,
                                             int64_t alert_id,
                                             const ConfigKey& configKey) {
    VLOG("Starting trace collection through perfprofd");

    if (!config.has_perfprofd_config()) {
      ALOGE("The perfprofd trace config is empty, aborting");
      return false;
    }

    sp<IPerfProfd> service = interface_cast<IPerfProfd>(
        defaultServiceManager()->getService(android::String16("perfprofd")));
    if (service == NULL) {
      ALOGE("Could not find perfprofd service");
      return false;
    }

    // Add protobufs can't be described in AIDL, we need to re-serialize
    // the config proto to send it.
    std::vector<uint8_t> proto_serialized;
    {
      const auto& config_proto = config.perfprofd_config();
      int size = config_proto.ByteSize();
      proto_serialized.resize(size);
      ::google::protobuf::uint8* target_ptr =
          reinterpret_cast<::google::protobuf::uint8*>(proto_serialized.data());
      config_proto.SerializeWithCachedSizesToArray(target_ptr);
    }

    // TODO: alert-id etc?

    binder::Status status = service->startProfilingProtobuf(proto_serialized);
    if (status.isOk()) {
      return true;
    }

    ALOGE("Error starting perfprofd profiling: %s", status.toString8().c_str());
    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
