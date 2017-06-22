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

#include "LoadedApk.h"

#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "io/BigBufferInputStream.h"
#include "io/Util.h"

namespace aapt {

std::unique_ptr<LoadedApk> LoadedApk::LoadApkFromPath(IAaptContext* context,
                                                      const android::StringPiece& path) {
  Source source(path);
  std::string error;
  std::unique_ptr<io::ZipFileCollection> apk = io::ZipFileCollection::Create(path, &error);
  if (!apk) {
    context->GetDiagnostics()->Error(DiagMessage(source) << error);
    return {};
  }

  io::IFile* file = apk->FindFile("resources.arsc");
  if (!file) {
    context->GetDiagnostics()->Error(DiagMessage(source) << "no resources.arsc found");
    return {};
  }

  std::unique_ptr<io::IData> data = file->OpenAsData();
  if (!data) {
    context->GetDiagnostics()->Error(DiagMessage(source) << "could not open resources.arsc");
    return {};
  }

  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
  BinaryResourceParser parser(context, table.get(), source, data->data(), data->size(), apk.get());
  if (!parser.Parse()) {
    return {};
  }
  return util::make_unique<LoadedApk>(source, std::move(apk), std::move(table));
}

bool LoadedApk::WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                               IArchiveWriter* writer) {
  FilterChain empty;
  return WriteToArchive(context, options, &empty, writer);
}

bool LoadedApk::WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                               FilterChain* filters, IArchiveWriter* writer) {
  std::set<std::string> referenced_resources;
  // List the files being referenced in the resource table.
  for (auto& pkg : table_->packages) {
    for (auto& type : pkg->types) {
      for (auto& entry : type->entries) {
        for (auto& config_value : entry->values) {
          FileReference* file_ref = ValueCast<FileReference>(config_value->value.get());
          if (file_ref) {
            referenced_resources.insert(*file_ref->path);
          }
        }
      }
    }
  }

  std::unique_ptr<io::IFileCollectionIterator> iterator = apk_->Iterator();
  while (iterator->HasNext()) {
    io::IFile* file = iterator->Next();

    std::string path = file->GetSource().path;
    // The name of the path has the format "<zip-file-name>@<path-to-file>".
    path = path.substr(path.find("@") + 1);

    // Skip resources that are not referenced if requested.
    if (path.find("res/") == 0 && referenced_resources.find(path) == referenced_resources.end()) {
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage()
                                        << "Removing resource '" << path << "' from APK.");
      }
      continue;
    }

    if (!filters->Keep(path)) {
      if (context->IsVerbose()) {
        context->GetDiagnostics()->Note(DiagMessage() << "Filtered '" << path << "' from APK.");
      }
      continue;
    }

    // The resource table needs to be re-serialized since it might have changed.
    if (path == "resources.arsc") {
      BigBuffer buffer(4096);
      // TODO(adamlesinski): How to determine if there were sparse entries (and if to encode
      // with sparse entries) b/35389232.
      TableFlattener flattener(options, &buffer);
      if (!flattener.Consume(context, table_.get())) {
        return false;
      }

      io::BigBufferInputStream input_stream(&buffer);
      if (!io::CopyInputStreamToArchive(context, &input_stream, path, ArchiveEntry::kAlign,
                                        writer)) {
        return false;
      }

    } else {
      uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
      if (!io::CopyFileToArchive(context, file, path, compression_flags, writer)) {
        return false;
      }
    }
  }
  return true;
}

}  // namespace aapt
