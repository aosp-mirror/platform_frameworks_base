/*
 * Copyright (C) 2021 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_FABRICATEDOVERLAY_H_
#define IDMAP2_INCLUDE_IDMAP2_FABRICATEDOVERLAY_H_

#include <libidmap2/proto/fabricated_v1.pb.h>

#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "idmap2/ResourceContainer.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

struct FabricatedOverlay {
  struct Builder {
    Builder(const std::string& package_name, const std::string& name,
            const std::string& target_package_name);

    Builder& SetOverlayable(const std::string& name);

    Builder& SetResourceValue(const std::string& resource_name, uint8_t data_type,
                              uint32_t data_value);

    WARN_UNUSED Result<FabricatedOverlay> Build();

   private:
    struct Entry {
      std::string resource_name;
      DataType data_type;
      DataValue data_value;
    };

    std::string package_name_;
    std::string name_;
    std::string target_package_name_;
    std::string target_overlayable_;
    std::vector<Entry> entries_;
  };

  Result<Unit> ToBinaryStream(std::ostream& stream) const;
  static Result<FabricatedOverlay> FromBinaryStream(std::istream& stream);

 private:
  struct SerializedData {
    std::unique_ptr<uint8_t[]> data;
    size_t data_size;
    uint32_t crc;
  };

  Result<SerializedData*> InitializeData() const;
  Result<uint32_t> GetCrc() const;

  explicit FabricatedOverlay(pb::FabricatedOverlay&& overlay,
                             std::optional<uint32_t> crc_from_disk = {});

  pb::FabricatedOverlay overlay_pb_;
  std::optional<uint32_t> crc_from_disk_;
  mutable std::optional<SerializedData> data_;

  friend struct FabricatedOverlayContainer;
};

struct FabricatedOverlayContainer : public OverlayResourceContainer {
  static Result<std::unique_ptr<FabricatedOverlayContainer>> FromPath(std::string path);
  static std::unique_ptr<FabricatedOverlayContainer> FromOverlay(FabricatedOverlay&& overlay);

  WARN_UNUSED OverlayManifestInfo GetManifestInfo() const;

  // inherited from OverlayResourceContainer
  WARN_UNUSED Result<OverlayManifestInfo> FindOverlayInfo(const std::string& name) const override;
  WARN_UNUSED Result<OverlayData> GetOverlayData(const OverlayManifestInfo& info) const override;

  // inherited from ResourceContainer
  WARN_UNUSED Result<uint32_t> GetCrc() const override;
  WARN_UNUSED const std::string& GetPath() const override;
  WARN_UNUSED Result<std::string> GetResourceName(ResourceId id) const override;

  ~FabricatedOverlayContainer() override;

 private:
  FabricatedOverlayContainer(FabricatedOverlay&& overlay, std::string&& path);
  FabricatedOverlay overlay_;
  std::string path_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_FABRICATEDOVERLAY_H_
