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

#ifndef IDMAP2_IDMAP2D_IDMAP2SERVICE_H_
#define IDMAP2_IDMAP2D_IDMAP2SERVICE_H_

#include <android-base/unique_fd.h>
#include <binder/BinderService.h>
#include <binder/Nullable.h>

#include "android/os/BnIdmap2.h"

namespace android::os {

class Idmap2Service : public BinderService<Idmap2Service>, public BnIdmap2 {
 public:
  static char const* getServiceName() {
    return "idmap";
  }

  binder::Status getIdmapPath(const std::string& overlay_apk_path, int32_t user_id,
                              std::string* _aidl_return) override;

  binder::Status removeIdmap(const std::string& overlay_apk_path, int32_t user_id,
                             bool* _aidl_return) override;

  binder::Status verifyIdmap(const std::string& overlay_apk_path, int32_t fulfilled_policies,
                             bool enforce_overlayable, int32_t user_id,
                             bool* _aidl_return) override;

  binder::Status createIdmap(const std::string& target_apk_path,
                             const std::string& overlay_apk_path, int32_t fulfilled_policies,
                             bool enforce_overlayable, int32_t user_id,
                             aidl::nullable<std::string>* _aidl_return) override;
};

}  // namespace android::os

#endif  // IDMAP2_IDMAP2D_IDMAP2SERVICE_H_
