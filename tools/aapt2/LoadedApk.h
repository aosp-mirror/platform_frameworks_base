/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_LOADEDAPK_H
#define AAPT_LOADEDAPK_H

#include "androidfw/StringPiece.h"

#include "ResourceTable.h"
#include "filter/Filter.h"
#include "format/Archive.h"
#include "format/binary/BinaryResourceParser.h"
#include "format/binary/TableFlattener.h"
#include "io/ZipArchive.h"
#include "xml/XmlDom.h"

namespace aapt {

constexpr static const char kApkResourceTablePath[] = "resources.arsc";
constexpr static const char kProtoResourceTablePath[] = "resources.pb";
constexpr static const char kAndroidManifestPath[] = "AndroidManifest.xml";

enum ApkFormat {
  kUnknown,
  kBinary,
  kProto,
};

// Info about an APK loaded in memory.
class LoadedApk {
 public:
  virtual ~LoadedApk() = default;

  // Loads both binary and proto APKs from disk.
  static std::unique_ptr<LoadedApk> LoadApkFromPath(const ::android::StringPiece& path,
                                                    IDiagnostics* diag);

  // Loads a proto APK from the given file collection.
  static std::unique_ptr<LoadedApk> LoadProtoApkFromFileCollection(
      const Source& source, std::unique_ptr<io::IFileCollection> collection, IDiagnostics* diag);

  // Loads a binary APK from the given file collection.
  static std::unique_ptr<LoadedApk> LoadBinaryApkFromFileCollection(
      const Source& source, std::unique_ptr<io::IFileCollection> collection, IDiagnostics* diag);

  LoadedApk(const Source& source, std::unique_ptr<io::IFileCollection> apk,
            std::unique_ptr<ResourceTable> table, std::unique_ptr<xml::XmlResource> manifest,
            const ApkFormat& format)
      : source_(source),
        apk_(std::move(apk)),
        table_(std::move(table)),
        manifest_(std::move(manifest)),
        format_(format) {
  }

  io::IFileCollection* GetFileCollection() {
    return apk_.get();
  }

  const ResourceTable* GetResourceTable() const {
    return table_.get();
  }

  ResourceTable* GetResourceTable() {
    return table_.get();
  }

  const Source& GetSource() {
    return source_;
  }

  const xml::XmlResource* GetManifest() const {
    return manifest_.get();
  }

  /**
   * Writes the APK on disk at the given path, while also removing the resource
   * files that are not referenced in the resource table.
   */
  virtual bool WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                              IArchiveWriter* writer);

  /**
   * Writes the APK on disk at the given path, while also removing the resource files that are not
   * referenced in the resource table. The provided filter chain is applied to each entry in the APK
   * file.
   *
   * If the manifest is also provided, it will be written to the new APK file, otherwise the
   * original manifest will be written. The manifest is only required if the contents of the new APK
   * have been modified in a way that require the AndroidManifest.xml to also be modified.
   */
  virtual bool WriteToArchive(IAaptContext* context, ResourceTable* split_table,
                              const TableFlattenerOptions& options, FilterChain* filters,
                              IArchiveWriter* writer, xml::XmlResource* manifest = nullptr);


 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedApk);

  Source source_;
  std::unique_ptr<io::IFileCollection> apk_;
  std::unique_ptr<ResourceTable> table_;
  std::unique_ptr<xml::XmlResource> manifest_;
  ApkFormat format_;

  static ApkFormat DetermineApkFormat(io::IFileCollection* apk);
};

}  // namespace aapt

#endif /* AAPT_LOADEDAPK_H */
