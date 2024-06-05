/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <sys/stat.h>

#include <fstream>
#include <optional>

#define LOG_TAG "SelfTargeting"

#include "androidfw/ResourceTypes.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/FabricatedOverlay.h"
#include "idmap2/Idmap.h"
#include "idmap2/Result.h"

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;
using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;
using android::idmap2::BinaryStreamVisitor;
using android::idmap2::Idmap;
using android::idmap2::OverlayResourceContainer;

namespace android::self_targeting {

constexpr const mode_t kIdmapFilePermission = S_IRUSR | S_IWUSR;  // u=rw-, g=---, o=---

extern "C" bool
CreateFrroFile(std::string& out_err_result, const std::string& packageName,
               const std::string& overlayName, const std::string& targetPackageName,
               const std::optional<std::string>& targetOverlayable,
               const std::vector<FabricatedOverlayEntryParameters>& entries_params,
               const std::string& frro_file_path) {
    android::idmap2::FabricatedOverlay::Builder builder(packageName, overlayName,
                                                        targetPackageName);
    if (targetOverlayable.has_value()) {
        builder.SetOverlayable(targetOverlayable.value_or(std::string()));
    }
    for (const auto& entry_params : entries_params) {
        const auto dataType = entry_params.data_type;
        if (entry_params.data_binary_value.has_value()) {
            builder.SetResourceValue(entry_params.resource_name, *entry_params.data_binary_value,
                                     entry_params.binary_data_offset, entry_params.binary_data_size,
                                     entry_params.configuration, entry_params.nine_patch);
        } else  if (dataType >= Res_value::TYPE_FIRST_INT && dataType <= Res_value::TYPE_LAST_INT) {
           builder.SetResourceValue(entry_params.resource_name, dataType,
                                    entry_params.data_value, entry_params.configuration);
        } else if (dataType == Res_value::TYPE_STRING) {
           builder.SetResourceValue(entry_params.resource_name, dataType,
                                    entry_params.data_string_value , entry_params.configuration);
        } else {
            out_err_result = base::StringPrintf("Unsupported data type %d", dataType);
            return false;
        }
    }

    const auto frro = builder.Build();
    std::ofstream fout(frro_file_path);
    if (fout.fail()) {
        out_err_result = base::StringPrintf("open output stream fail %s", std::strerror(errno));
        return false;
    }
    auto result = frro->ToBinaryStream(fout);
    if (!result) {
        unlink(frro_file_path.c_str());
        out_err_result = base::StringPrintf("to stream fail %s", result.GetErrorMessage().c_str());
        return false;
    }
    fout.close();
    if (fout.fail()) {
        unlink(frro_file_path.c_str());
        out_err_result = base::StringPrintf("output stream fail %s", std::strerror(errno));
        return false;
    }
    if (chmod(frro_file_path.c_str(), kIdmapFilePermission) == -1) {
        out_err_result = base::StringPrintf("Failed to change the file permission %s",
                                            frro_file_path.c_str());
        return false;
    }
    return true;
}

static PolicyBitmask GetFulfilledPolicy(const bool isSystem, const bool isVendor,
                                        const bool isProduct, const bool isTargetSignature,
                                        const bool isOdm, const bool isOem) {
    auto fulfilled_policy = static_cast<PolicyBitmask>(PolicyFlags::PUBLIC);

    if (isSystem) {
        fulfilled_policy |= PolicyFlags::SYSTEM_PARTITION;
    }
    if (isVendor) {
        fulfilled_policy |= PolicyFlags::VENDOR_PARTITION;
    }
    if (isProduct) {
        fulfilled_policy |= PolicyFlags::PRODUCT_PARTITION;
    }
    if (isOdm) {
        fulfilled_policy |= PolicyFlags::ODM_PARTITION;
    }
    if (isOem) {
        fulfilled_policy |= PolicyFlags::OEM_PARTITION;
    }
    if (isTargetSignature) {
        fulfilled_policy |= PolicyFlags::SIGNATURE;
    }

    // Not support actor_signature and config_overlay_signature
    fulfilled_policy &=
            ~(PolicyFlags::ACTOR_SIGNATURE | PolicyFlags::CONFIG_SIGNATURE);

    ALOGV(
            "fulfilled_policy = 0x%08x, isSystem = %d, isVendor = %d, isProduct = %d,"
            " isTargetSignature = %d, isOdm = %d, isOem = %d,",
            fulfilled_policy, isSystem, isVendor, isProduct, isTargetSignature, isOdm, isOem);
    return fulfilled_policy;
}

extern "C" bool
CreateIdmapFile(std::string& out_err, const std::string& targetPath, const std::string& overlayPath,
                const std::string& idmapPath, const std::string& overlayName,
                const bool isSystem, const bool isVendor, const bool isProduct,
                const bool isTargetSignature, const bool isOdm, const bool isOem) {
    // idmap files are mapped with mmap in libandroidfw. Deleting and recreating the idmap
    // guarantees that existing memory maps will continue to be valid and unaffected. The file must
    // be deleted before attempting to create the idmap, so that if idmap  creation fails, the
    // overlay will no longer be usable.
    unlink(idmapPath.c_str());

    const auto target = idmap2::TargetResourceContainer::FromPath(targetPath);
    if (!target) {
        out_err = base::StringPrintf("Failed to load target %s because of %s", targetPath.c_str(),
                                     target.GetErrorMessage().c_str());
        return false;
    }

    const auto overlay = OverlayResourceContainer::FromPath(overlayPath);
    if (!overlay) {
        out_err = base::StringPrintf("Failed to load overlay %s because of %s", overlayPath.c_str(),
                                     overlay.GetErrorMessage().c_str());
        return false;
    }

    // Overlay self target process. Only allow self-targeting types.
    const auto fulfilled_policies = GetFulfilledPolicy(isSystem, isVendor, isProduct,
                                                       isTargetSignature, isOdm, isOem);

    const auto idmap = Idmap::FromContainers(**target, **overlay, overlayName,
                                             fulfilled_policies, true /* enforce_overlayable */);
    if (!idmap) {
        out_err = base::StringPrintf("Failed to create idmap because of %s",
                                     idmap.GetErrorMessage().c_str());
        return false;
    }

    std::ofstream fout(idmapPath.c_str());
    if (fout.fail()) {
        out_err = base::StringPrintf("Failed to create idmap %s because of %s", idmapPath.c_str(),
                                     strerror(errno));
        return false;
    }

    BinaryStreamVisitor visitor(fout);
    (*idmap)->accept(&visitor);
    fout.close();
    if (fout.fail()) {
        unlink(idmapPath.c_str());
        out_err = base::StringPrintf("Failed to write idmap %s because of %s", idmapPath.c_str(),
                                     strerror(errno));
        return false;
    }
    if (chmod(idmapPath.c_str(), kIdmapFilePermission) == -1) {
        out_err = base::StringPrintf("Failed to change the file permission %s", idmapPath.c_str());
        return false;
    }
    return true;
}

extern "C" bool
GetFabricatedOverlayInfo(std::string& out_err, const std::string& overlay_path,
                         OverlayManifestInfo& out_info) {
    const auto overlay = idmap2::FabricatedOverlayContainer::FromPath(overlay_path);
    if (!overlay) {
        out_err = base::StringPrintf("Failed to write idmap %s because of %s",
                                     overlay_path.c_str(), strerror(errno));
        return false;
    }

    out_info = (*overlay)->GetManifestInfo();

    return true;
}

}  // namespace android::self_targeting

