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
#include "idmap2/SysTrace.h"
#include "utils/String8.h"

using android::IPCThreadState;
using android::binder::Status;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::Idmap;
using android::idmap2::IdmapHeader;
using android::idmap2::utils::kIdmapCacheDir;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::UidHasWriteAccessToPath;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace {

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

Status Idmap2Service::getIdmapPath(const std::string& overlay_apk_path,
                                   int32_t user_id ATTRIBUTE_UNUSED, std::string* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::getIdmapPath " << overlay_apk_path;
  *_aidl_return = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  return ok();
}

Status Idmap2Service::removeIdmap(const std::string& overlay_apk_path,
                                  int32_t user_id ATTRIBUTE_UNUSED, bool* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::removeIdmap " << overlay_apk_path;
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
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

Status Idmap2Service::verifyIdmap(const std::string& overlay_apk_path,
                                  int32_t fulfilled_policies ATTRIBUTE_UNUSED,
                                  bool enforce_overlayable ATTRIBUTE_UNUSED,
                                  int32_t user_id ATTRIBUTE_UNUSED, bool* _aidl_return) {
  SYSTRACE << "Idmap2Service::verifyIdmap " << overlay_apk_path;
  assert(_aidl_return);
  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  std::ifstream fin(idmap_path);
  const std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(fin);
  fin.close();
  *_aidl_return = header && header->IsUpToDate();

  // TODO(b/119328308): Check that the set of fulfilled policies of the overlay has not changed

  return ok();
}

Status Idmap2Service::createIdmap(const std::string& target_apk_path,
                                  const std::string& overlay_apk_path, int32_t fulfilled_policies,
                                  bool enforce_overlayable, int32_t user_id ATTRIBUTE_UNUSED,
                                  std::optional<std::string>* _aidl_return) {
  assert(_aidl_return);
  SYSTRACE << "Idmap2Service::createIdmap " << target_apk_path << " " << overlay_apk_path;
  _aidl_return->reset();

  const PolicyBitmask policy_bitmask = ConvertAidlArgToPolicyBitmask(fulfilled_policies);

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_apk_path);
  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    return error(base::StringPrintf("will not write to %s: calling uid %d lacks write accesss",
                                    idmap_path.c_str(), uid));
  }

  const std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return error("failed to load apk " + target_apk_path);
  }

  const std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return error("failed to load apk " + overlay_apk_path);
  }

  const auto idmap =
      Idmap::FromApkAssets(*target_apk, *overlay_apk, policy_bitmask, enforce_overlayable);
  if (!idmap) {
    return error(idmap.GetErrorMessage());
  }

  umask(kIdmapFilePermissionMask);
  std::ofstream fout(idmap_path);
  if (fout.fail()) {
    return error("failed to open idmap path " + idmap_path);
  }
  BinaryStreamVisitor visitor(fout);
  (*idmap)->accept(&visitor);
  fout.close();
  if (fout.fail()) {
    return error("failed to write to idmap path " + idmap_path);
  }

  *_aidl_return = idmap_path;
  return ok();
}

}  // namespace android::os
