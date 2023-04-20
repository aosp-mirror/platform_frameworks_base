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

#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <ostream>
#include <string>
#include <utility>
#include <vector>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "binder/IPCThreadState.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "idmap2/PrettyPrintVisitor.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"

using android::base::StringPrintf;
using android::binder::Status;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::FabricatedOverlayContainer;
using android::idmap2::Idmap;
using android::idmap2::IdmapHeader;
using android::idmap2::OverlayResourceContainer;
using android::idmap2::PrettyPrintVisitor;
using android::idmap2::TargetResourceContainer;
using android::idmap2::utils::kIdmapCacheDir;
using android::idmap2::utils::kIdmapFilePermissionMask;
using android::idmap2::utils::RandomStringForPath;
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
                                  const std::string& overlay_name, int32_t fulfilled_policies,
                                  bool enforce_overlayable, int32_t user_id ATTRIBUTE_UNUSED,
                                  bool* _aidl_return) {
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

  auto up_to_date =
      header->IsUpToDate(*GetPointer(*target), **overlay, overlay_name,
                         ConvertAidlArgToPolicyBitmask(fulfilled_policies), enforce_overlayable);

  *_aidl_return = static_cast<bool>(up_to_date);
  if (!up_to_date) {
    LOG(WARNING) << "idmap '" << idmap_path
                 << "' not up to date : " << up_to_date.GetErrorMessage();
  }
  return ok();
}

Status Idmap2Service::createIdmap(const std::string& target_path, const std::string& overlay_path,
                                  const std::string& overlay_name, int32_t fulfilled_policies,
                                  bool enforce_overlayable, int32_t user_id ATTRIBUTE_UNUSED,
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

  // idmap files are mapped with mmap in libandroidfw. Deleting and recreating the idmap guarantees
  // that existing memory maps will continue to be valid and unaffected. The file must be deleted
  // before attempting to create the idmap, so that if idmap  creation fails, the overlay will no
  // longer be usable.
  unlink(idmap_path.c_str());

  const auto target = GetTargetContainer(target_path);
  if (!target) {
    return error("failed to load target '%s'" + target_path);
  }

  const auto overlay = OverlayResourceContainer::FromPath(overlay_path);
  if (!overlay) {
    return error("failed to load apk overlay '%s'" + overlay_path);
  }

  const auto idmap = Idmap::FromContainers(*GetPointer(*target), **overlay, overlay_name,
                                           policy_bitmask, enforce_overlayable);
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

Status Idmap2Service::createFabricatedOverlay(
    const os::FabricatedOverlayInternal& overlay,
    std::optional<os::FabricatedOverlayInfo>* _aidl_return) {
  idmap2::FabricatedOverlay::Builder builder(overlay.packageName, overlay.overlayName,
                                             overlay.targetPackageName);
  if (!overlay.targetOverlayable.empty()) {
    builder.SetOverlayable(overlay.targetOverlayable);
  }

  for (const auto& res : overlay.entries) {
    builder.SetResourceValue(res.resourceName, res.dataType, res.data);
  }

  // Generate the file path of the fabricated overlay and ensure it does not collide with an
  // existing path. Re-registering a fabricated overlay will always result in an updated path.
  std::string path;
  std::string file_name;
  do {
    constexpr size_t kSuffixLength = 4;
    const std::string random_suffix = RandomStringForPath(kSuffixLength);
    file_name = StringPrintf("%s-%s-%s.frro", overlay.packageName.c_str(),
                             overlay.overlayName.c_str(), random_suffix.c_str());
    path = StringPrintf("%s/%s", kIdmapCacheDir, file_name.c_str());

    // Invoking std::filesystem::exists with a file name greater than 255 characters will cause this
    // process to abort since the name exceeds the maximum file name size.
    const size_t kMaxFileNameLength = 255;
    if (file_name.size() > kMaxFileNameLength) {
      return error(
          base::StringPrintf("fabricated overlay file name '%s' longer than %zu characters",
                             file_name.c_str(), kMaxFileNameLength));
    }
  } while (std::filesystem::exists(path));

  const uid_t uid = IPCThreadState::self()->getCallingUid();
  if (!UidHasWriteAccessToPath(uid, path)) {
    return error(base::StringPrintf("will not write to %s: calling uid %d lacks write access",
                                    path.c_str(), uid));
  }

  const auto frro = builder.Build();
  if (!frro) {
    return error(StringPrintf("failed to serialize '%s:%s': %s", overlay.packageName.c_str(),
                              overlay.overlayName.c_str(), frro.GetErrorMessage().c_str()));
  }
  // Persist the fabricated overlay.
  umask(kIdmapFilePermissionMask);
  std::ofstream fout(path);
  if (fout.fail()) {
    return error("failed to open frro path " + path);
  }
  auto result = frro->ToBinaryStream(fout);
  if (!result) {
    unlink(path.c_str());
    return error("failed to write to frro path " + path + ": " + result.GetErrorMessage());
  }
  if (fout.fail()) {
    unlink(path.c_str());
    return error("failed to write to frro path " + path);
  }

  os::FabricatedOverlayInfo out_info;
  out_info.packageName = overlay.packageName;
  out_info.overlayName = overlay.overlayName;
  out_info.targetPackageName = overlay.targetPackageName;
  out_info.targetOverlayable = overlay.targetOverlayable;
  out_info.path = path;
  *_aidl_return = out_info;
  return ok();
}

Status Idmap2Service::acquireFabricatedOverlayIterator(int32_t* _aidl_return) {
  std::lock_guard l(frro_iter_mutex_);
  if (frro_iter_.has_value()) {
    LOG(WARNING) << "active ffro iterator was not previously released";
  }
  frro_iter_ = std::filesystem::directory_iterator(kIdmapCacheDir);
  if (frro_iter_id_ == std::numeric_limits<int32_t>::max()) {
    frro_iter_id_ = 0;
  } else {
    ++frro_iter_id_;
  }
  *_aidl_return = frro_iter_id_;
  return ok();
}

Status Idmap2Service::releaseFabricatedOverlayIterator(int32_t iteratorId) {
  std::lock_guard l(frro_iter_mutex_);
  if (!frro_iter_.has_value()) {
    LOG(WARNING) << "no active ffro iterator to release";
  } else if (frro_iter_id_ != iteratorId) {
    LOG(WARNING) << "incorrect iterator id in a call to release";
  } else {
    frro_iter_.reset();
  }
  return ok();
}

Status Idmap2Service::nextFabricatedOverlayInfos(int32_t iteratorId,
    std::vector<os::FabricatedOverlayInfo>* _aidl_return) {
  std::lock_guard l(frro_iter_mutex_);

  constexpr size_t kMaxEntryCount = 100;
  if (!frro_iter_.has_value()) {
    return error("no active frro iterator");
  } else if (frro_iter_id_ != iteratorId) {
    return error("incorrect iterator id in a call to next");
  }

  size_t count = 0;
  auto& entry_iter = *frro_iter_;
  auto entry_iter_end = end(*frro_iter_);
  for (; entry_iter != entry_iter_end && count < kMaxEntryCount; ++entry_iter) {
    auto& entry = *entry_iter;
    if (!entry.is_regular_file() || !android::IsFabricatedOverlay(entry.path().native())) {
      continue;
    }

    const auto overlay = FabricatedOverlayContainer::FromPath(entry.path().native());
    if (!overlay) {
      LOG(WARNING) << "Failed to open '" << entry.path() << "': " << overlay.GetErrorMessage();
      continue;
    }

    auto info = (*overlay)->GetManifestInfo();
    os::FabricatedOverlayInfo out_info;
    out_info.packageName = std::move(info.package_name);
    out_info.overlayName = std::move(info.name);
    out_info.targetPackageName = std::move(info.target_package);
    out_info.targetOverlayable = std::move(info.target_name);
    out_info.path = entry.path();
    _aidl_return->emplace_back(std::move(out_info));
    count++;
  }
  return ok();
}

binder::Status Idmap2Service::deleteFabricatedOverlay(const std::string& overlay_path,
                                                      bool* _aidl_return) {
  SYSTRACE << "Idmap2Service::deleteFabricatedOverlay " << overlay_path;
  const uid_t uid = IPCThreadState::self()->getCallingUid();

  if (!UidHasWriteAccessToPath(uid, overlay_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    overlay_path.c_str(), uid));
  }

  const std::string idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  if (!UidHasWriteAccessToPath(uid, idmap_path)) {
    *_aidl_return = false;
    return error(base::StringPrintf("failed to unlink %s: calling uid %d lacks write access",
                                    idmap_path.c_str(), uid));
  }

  if (unlink(overlay_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + overlay_path + ": " + strerror(errno));
  }

  if (unlink(idmap_path.c_str()) != 0) {
    *_aidl_return = false;
    return error("failed to unlink " + idmap_path + ": " + strerror(errno));
  }

  *_aidl_return = true;
  return ok();
}

binder::Status Idmap2Service::dumpIdmap(const std::string& overlay_path,
                                        std::string* _aidl_return) {
  assert(_aidl_return);

  const auto idmap_path = Idmap::CanonicalIdmapPathFor(kIdmapCacheDir, overlay_path);
  std::ifstream fin(idmap_path);
  const auto idmap = Idmap::FromBinaryStream(fin);
  fin.close();
  if (!idmap) {
    return error(idmap.GetErrorMessage());
  }

  std::stringstream stream;
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);
  *_aidl_return = stream.str();

  return ok();
}

}  // namespace android::os
