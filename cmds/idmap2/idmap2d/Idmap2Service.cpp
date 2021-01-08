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

#include "idmap2d/Idmap2Service.h"

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <fstream>
#include <memory>
#include <ostream>
#include <string>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "binder/IPCThreadState.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"
#include "utils/String8.h"

using android::IPCThreadState;
using android::base::StringPrintf;
using android::binder::Status;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::Idmap;
using android::idmap2::IdmapHeader;
using android::idmap2::OverlayResourceContainer;
using android::idmap2::TargetResourceContainer;
using android::idmap2::utils::kIdmapCacheDir;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::UidHasWriteAccessToPath;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace {

constexpr const char* kFrameworkPath = "/system/framework/framework-res.apk";

Status ok() {
  return Status::ok();
}

Status error(const std::string& msg) {
  LOG(ERROR) << msg;
  return Status::fromExceptionCode(Status::EX_NONE, msg.c_str());
}

PolicyBitmask ConvertAidlArgToPolicyBitmask(int32_t arg) {
  return static_cast<PolicyBitmask>(arg);
}
}  // namespace

namespace android::os {

Status Idmap2Service::getIdmapPath(const std::string& overlay_path,
                                   int32_t user_id ATTRIBUTE_UNUSED, std::string* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::getIdmapPath " << overlay_path;
  *_aidl_return = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  return ok();
}

Status Idmap2Service::removeIdmap(const std::string& overlay_path, int32_t user_id ATTRIBUTE_UNUSED,
                                  bool* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::removeIdmap " << overlay_path;
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    idmap_path.c_str(), uid));
  }
  if (unlink(idmap_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + idmap_path + ": " + strerror(errno));
  }
  *_aidl_return = true;
  return ok();
}

Status Idmap2Service::verifyIdmap(const std::string& target_path, const std::string& overlay_path,
                                  int32_t fulfilled_policies, bool enforce_overlayable,
                                  int32_t user_id ATTRIBUTE_UNUSED, bool* _aidl_return) {
  SYSTRACE << "Idmap2Service::verifyIdmap " << overlay_path;
  assert(_aidl_return);

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  std::ifstream fin(idmap_path);
  const std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(fin);
  fin.close();
  if (!header) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to parse idmap header of '" << idmap_path << "'";
    return ok();
  }

  const auto target = GetTargetContainer(target_path);
  if (!target) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to load target '" << target_path << "'";
    return ok();
  }

  const auto overlay = OverlayResourceContainer::FromPath(overlay_path);
  if (!overlay) {
    *_aidl_return = false;
    LOG(WARNING) << "failed to load overlay '" << overlay_path << "'";
    return ok();
  }

  // TODO(162841629): Support passing overlay name to idmap2d verify
  auto up_to_date =
      header->IsUpToDate(*GetPointer(*target), **overlay, "",
                         ConvertAidlArgToPolicyBitmask(fulfilled_policies), enforce_overlayable);

  *_aidl_return = static_cast<bool>(up_to_date);
  if (!up_to_date) {
    LOG(WARNING) << "idmap '" << idmap_path
                 << "' not up to date : " << up_to_date.GetErrorMessage();
  }
  return ok();
}

Status Idmap2Service::createIdmap(const std::string& target_path, const std::string& overlay_path,
                                  int32_t fulfilled_policies, bool enforce_overlayable,
                                  int32_t user_id ATTRIBUTE_UNUSED,
                                  std::optional<std::string>* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::createIdmap " << target_path << " " << overlay_path;
  _aidl_return->reset();

  const PolicyBitmask policy_bitmask = ConvertAidlArgToPolicyBitmask(fulfilled_policies);

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    return error(base::StringPrintf("will not write to %s: calling uid %d lacks write accesss",
                                    idmap_path.c_str(), uid));
  }

  const auto target = GetTargetContainer(target_path);
  if (!target) {
    return error("failed to load target '%s'" + target_path);
  }

  const auto overlay = OverlayResourceContainer::FromPath(overlay_path);
  if (!overlay) {
    return error("failed to load apk overlay '%s'" + overlay_path);
  }

  // TODO(162841629): Support passing overlay name to idmap2d create
  const auto idmap = Idmap::FromContainers(*GetPointer(*target), **overlay, "", policy_bitmask,
                                           enforce_overlayable);
  if (!idmap) {
    return error(idmap.GetErrorMessage());
  }

  // idmap files are mapped with mmap in libandroidfw. Deleting and recreating the idmap guarantees
  // that existing memory maps will continue to be valid and unaffected.
  unlink(idmap_path.c_str());

  umask(kIdmapFilePermissionMask);
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    return error("failed to open idmap path " + idmap_path);
  }

  BinaryStreamVisitor visitor(fout);
  (*idmap)->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    unlink(idmap_path.c_str());
    return error("failed to write to idmap path " + idmap_path);
  }

  *_aidl_return = idmap_path;
  return ok();
}

idmap2::Result<Idmap2Service::TargetResourceContainerPtr> Idmap2Service::GetTargetContainer(
    const std::string& target_path) {
  if (target_path == kFrameworkPath) {
    if (framework_apk_cache_ == nullptr) {
      // Initialize the framework APK cache.
      auto target = TargetResourceContainer::FromPath(target_path);
      if (!target) {
        return target.GetError();
      }
      framework_apk_cache_ = std::move(*target);
    }
    return {framework_apk_cache_.get()};
  }

  auto target = TargetResourceContainer::FromPath(target_path);
  if (!target) {
    return target.GetError();
  }
  return {std::move(*target)};
}

}  // namespace android::os
