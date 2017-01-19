/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_TABLEMERGER_H
#define AAPT_TABLEMERGER_H

#include <functional>
#include <map>

#include "android-base/macros.h"

#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "filter/ConfigFilter.h"
#include "io/File.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"

namespace aapt {

struct TableMergerOptions {
  /**
   * If true, resources in overlays can be added without previously having
   * existed.
   */
  bool auto_add_overlay = false;
};

/**
 * TableMerger takes resource tables and merges all packages within the tables
 * that have the same
 * package ID.
 *
 * If a package has a different name, all the entries in that table have their
 * names mangled
 * to include the package name. This way there are no collisions. In order to do
 * this correctly,
 * the TableMerger needs to also mangle any FileReference paths. Once these are
 * mangled,
 * the original source path of the file, along with the new destination path is
 * recorded in the
 * queue returned from getFileMergeQueue().
 *
 * Once the merging is complete, a separate process can go collect the files
 * from the various
 * source APKs and either copy or process their XML and put them in the correct
 * location in
 * the final APK.
 */
class TableMerger {
 public:
  /**
   * Note: The out_table ResourceTable must live longer than this TableMerger.
   * References are made to this ResourceTable for efficiency reasons.
   */
  TableMerger(IAaptContext* context, ResourceTable* out_table,
              const TableMergerOptions& options);

  const std::set<std::string>& merged_packages() const {
    return merged_packages_;
  }

  /**
   * Merges resources from the same or empty package. This is for local sources.
   * An io::IFileCollection is optional and used to find the referenced Files
   * and process them.
   */
  bool Merge(const Source& src, ResourceTable* table,
             io::IFileCollection* collection = nullptr);

  /**
   * Merges resources from an overlay ResourceTable.
   * An io::IFileCollection is optional and used to find the referenced Files
   * and process them.
   */
  bool MergeOverlay(const Source& src, ResourceTable* table,
                    io::IFileCollection* collection = nullptr);

  /**
   * Merges resources from the given package, mangling the name. This is for
   * static libraries.
   * An io::IFileCollection is needed in order to find the referenced Files and
   * process them.
   */
  bool MergeAndMangle(const Source& src, const android::StringPiece& package, ResourceTable* table,
                      io::IFileCollection* collection);

  /**
   * Merges a compiled file that belongs to this same or empty package. This is
   * for local sources.
   */
  bool MergeFile(const ResourceFile& fileDesc, io::IFile* file);

  /**
   * Merges a compiled file from an overlay, overriding an existing definition.
   */
  bool MergeFileOverlay(const ResourceFile& fileDesc, io::IFile* file);

 private:
  DISALLOW_COPY_AND_ASSIGN(TableMerger);

  using FileMergeCallback = std::function<bool(const ResourceNameRef&,
                                               const ConfigDescription& config,
                                               FileReference*, FileReference*)>;

  IAaptContext* context_;
  ResourceTable* master_table_;
  TableMergerOptions options_;
  ResourceTablePackage* master_package_;
  std::set<std::string> merged_packages_;

  bool MergeFileImpl(const ResourceFile& file_desc, io::IFile* file,
                     bool overlay);

  bool MergeImpl(const Source& src, ResourceTable* src_table,
                 io::IFileCollection* collection, bool overlay, bool allow_new);

  bool DoMerge(const Source& src, ResourceTable* src_table,
               ResourceTablePackage* src_package, const bool mangle_package,
               const bool overlay, const bool allow_new_resources,
               const FileMergeCallback& callback);

  std::unique_ptr<FileReference> CloneAndMangleFile(const std::string& package,
                                                    const FileReference& value);
};

}  // namespace aapt

#endif /* AAPT_TABLEMERGER_H */
