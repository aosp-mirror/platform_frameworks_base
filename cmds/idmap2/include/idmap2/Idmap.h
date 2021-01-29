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

/*
 * # idmap file format (current version)
 *
 * idmap                      := header data*
 * header                     := magic version target_crc overlay_crc fulfilled_policies
 *                               enforce_overlayable target_path overlay_path debug_info
 * data                       := data_header target_entry* target_inline_entry* overlay_entry*
 *                               string_pool
 * data_header                := target_package_id overlay_package_id padding(2) target_entry_count
 *                               target_inline_entry_count overlay_entry_count string_pool_index
 * target_entry               := target_id overlay_id
 * target_inline_entry        := target_id Res_value::size padding(1) Res_value::type
 *                               Res_value::value
 * overlay_entry              := overlay_id target_id
 *
 * debug_info                 := string
 * enforce_overlayable        := <uint32_t>
 * fulfilled_policies         := <uint32_t>
 * magic                      := <uint32_t>
 * overlay_crc                := <uint32_t>
 * overlay_entry_count        := <uint32_t>
 * overlay_id                 := <uint32_t>
 * overlay_package_id         := <uint8_t>
 * overlay_path               := string256
 * padding(n)                 := <uint8_t>[n]
 * Res_value::size            := <uint16_t>
 * Res_value::type            := <uint8_t>
 * Res_value::value           := <uint32_t>
 * string                     := <uint32_t> <uint8_t>+ padding(n)
 * string256                  := <uint8_t>[256]
 * string_pool                := string
 * string_pool_index          := <uint32_t>
 * string_pool_length         := <uint32_t>
 * target_crc                 := <uint32_t>
 * target_entry_count         := <uint32_t>
 * target_inline_entry_count  := <uint32_t>
 * target_id                  := <uint32_t>
 * target_package_id          := <uint8_t>
 * target_path                := string256
 * value_type                 := <uint8_t>
 * value_data                 := <uint32_t>
 * version                    := <uint32_t>
 */

#ifndef IDMAP2_INCLUDE_IDMAP2_IDMAP_H_
#define IDMAP2_INCLUDE_IDMAP2_IDMAP_H_

#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"
#include "idmap2/ResourceMapping.h"
#include "idmap2/ZipFile.h"

namespace android::idmap2 {

class Idmap;
class Visitor;

static constexpr const ResourceId kPadding = 0xffffffffu;
static constexpr const EntryId kNoEntry = 0xffffu;

// magic number: all idmap files start with this
static constexpr const uint32_t kIdmapMagic = android::kIdmapMagic;

// current version of the idmap binary format; must be incremented when the format is changed
static constexpr const uint32_t kIdmapCurrentVersion = android::kIdmapCurrentVersion;

// strings in the idmap are encoded char arrays of length 'kIdmapStringLength' (including mandatory
// terminating null)
static constexpr const size_t kIdmapStringLength = 256;

// Retrieves a crc generated using all of the files within the zip that can affect idmap generation.
Result<uint32_t> GetPackageCrc(const ZipFile& zip_info);

class IdmapHeader {
 public:
  static std::unique_ptr<const IdmapHeader> FromBinaryStream(std::istream& stream);

  inline uint32_t GetMagic() const {
    return magic_;
  }

  inline uint32_t GetVersion() const {
    return version_;
  }

  inline uint32_t GetTargetCrc() const {
    return target_crc_;
  }

  inline uint32_t GetOverlayCrc() const {
    return overlay_crc_;
  }

  inline uint32_t GetFulfilledPolicies() const {
    return fulfilled_policies_;
  }

  bool GetEnforceOverlayable() const {
    return enforce_overlayable_;
  }

  inline StringPiece GetTargetPath() const {
    return StringPiece(target_path_);
  }

  inline StringPiece GetOverlayPath() const {
    return StringPiece(overlay_path_);
  }

  inline const std::string& GetDebugInfo() const {
    return debug_info_;
  }

  // Invariant: anytime the idmap data encoding is changed, the idmap version
  // field *must* be incremented. Because of this, we know that if the idmap
  // header is up-to-date the entire file is up-to-date.
  Result<Unit> IsUpToDate(const char* target_path, const char* overlay_path,
                          PolicyBitmask fulfilled_policies, bool enforce_overlayable) const;
  Result<Unit> IsUpToDate(const char* target_path, const char* overlay_path, uint32_t target_crc,
                          uint32_t overlay_crc, PolicyBitmask fulfilled_policies,
                          bool enforce_overlayable) const;

  void accept(Visitor* v) const;

 private:
  IdmapHeader() {
  }

  uint32_t magic_;
  uint32_t version_;
  uint32_t target_crc_;
  uint32_t overlay_crc_;
  uint32_t fulfilled_policies_;
  bool enforce_overlayable_;
  char target_path_[kIdmapStringLength];
  char overlay_path_[kIdmapStringLength];
  std::string debug_info_;

  friend Idmap;
  DISALLOW_COPY_AND_ASSIGN(IdmapHeader);
};
class IdmapData {
 public:
  class Header {
   public:
    static std::unique_ptr<const Header> FromBinaryStream(std::istream& stream);

    inline PackageId GetTargetPackageId() const {
      return target_package_id_;
    }

    inline PackageId GetOverlayPackageId() const {
      return overlay_package_id_;
    }

    inline uint32_t GetTargetEntryCount() const {
      return target_entry_count;
    }

    inline uint32_t GetTargetInlineEntryCount() const {
      return target_entry_inline_count;
    }

    inline uint32_t GetOverlayEntryCount() const {
      return overlay_entry_count;
    }

    inline uint32_t GetStringPoolIndexOffset() const {
      return string_pool_index_offset;
    }

    void accept(Visitor* v) const;

   private:
    PackageId target_package_id_;
    PackageId overlay_package_id_;
    uint32_t target_entry_count;
    uint32_t target_entry_inline_count;
    uint32_t overlay_entry_count;
    uint32_t string_pool_index_offset;
    Header() = default;

    friend Idmap;
    friend IdmapData;
    DISALLOW_COPY_AND_ASSIGN(Header);
  };

  struct TargetEntry {
    ResourceId target_id;
    ResourceId overlay_id;
  };

  struct TargetInlineEntry {
    ResourceId target_id;
    TargetValue value;
  };

  struct OverlayEntry {
    ResourceId overlay_id;
    ResourceId target_id;
  };

  static std::unique_ptr<const IdmapData> FromBinaryStream(std::istream& stream);

  static Result<std::unique_ptr<const IdmapData>> FromResourceMapping(
      const ResourceMapping& resource_mapping);

  const std::unique_ptr<const Header>& GetHeader() const {
    return header_;
  }

  const std::vector<TargetEntry>& GetTargetEntries() const {
    return target_entries_;
  }

  const std::vector<TargetInlineEntry>& GetTargetInlineEntries() const {
    return target_inline_entries_;
  }

  const std::vector<OverlayEntry>& GetOverlayEntries() const {
    return overlay_entries_;
  }

  const std::string& GetStringPoolData() const {
    return string_pool_data_;
  }

  void accept(Visitor* v) const;

 private:
  IdmapData() {
  }

  std::unique_ptr<const Header> header_;
  std::vector<TargetEntry> target_entries_;
  std::vector<TargetInlineEntry> target_inline_entries_;
  std::vector<OverlayEntry> overlay_entries_;
  std::string string_pool_data_;

  friend Idmap;
  DISALLOW_COPY_AND_ASSIGN(IdmapData);
};

class Idmap {
 public:
  static std::string CanonicalIdmapPathFor(const std::string& absolute_dir,
                                           const std::string& absolute_apk_path);

  static Result<std::unique_ptr<const Idmap>> FromBinaryStream(std::istream& stream);

  // In the current version of idmap, the first package in each resources.arsc
  // file is used; change this in the next version of idmap to use a named
  // package instead; also update FromApkAssets to take additional parameters:
  // the target and overlay package names
  static Result<std::unique_ptr<const Idmap>> FromApkAssets(const ApkAssets& target_apk_assets,
                                                            const ApkAssets& overlay_apk_assets,
                                                            const PolicyBitmask& fulfilled_policies,
                                                            bool enforce_overlayable);

  inline const std::unique_ptr<const IdmapHeader>& GetHeader() const {
    return header_;
  }

  inline const std::vector<std::unique_ptr<const IdmapData>>& GetData() const {
    return data_;
  }

  void accept(Visitor* v) const;

 private:
  Idmap() {
  }

  std::unique_ptr<const IdmapHeader> header_;
  std::vector<std::unique_ptr<const IdmapData>> data_;

  DISALLOW_COPY_AND_ASSIGN(Idmap);
};

class Visitor {
 public:
  virtual ~Visitor() {
  }
  virtual void visit(const Idmap& idmap) = 0;
  virtual void visit(const IdmapHeader& header) = 0;
  virtual void visit(const IdmapData& data) = 0;
  virtual void visit(const IdmapData::Header& header) = 0;
};

inline size_t CalculatePadding(size_t data_length) {
  return (4 - (data_length % 4)) % 4;
}

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_IDMAP_H_
