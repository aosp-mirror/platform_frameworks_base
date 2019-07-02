/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <utils/String16.h>
#include <unordered_map>
#include <utils/RefBase.h>
#include "StatsPuller.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

class PullDataReceiver : virtual public RefBase{
 public:
  virtual ~PullDataReceiver() {}
  /**
   * @param data The pulled data.
   * @param pullSuccess Whether the pull succeeded. If the pull does not succeed, the data for the
   * bucket should be invalidated.
   * @param originalPullTimeNs This is when all the pulls have been initiated (elapsed time).
   */
  virtual void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data, 
                            bool pullSuccess, int64_t originalPullTimeNs) = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
