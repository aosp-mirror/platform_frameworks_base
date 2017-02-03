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

namespace aapt {

std::unique_ptr<LoadedApk> LoadedApk::LoadApkFromPath(IAaptContext* context,
                                                      const android::StringPiece& path) {
  Source source(path);
  std::string error;
  std::unique_ptr<io::ZipFileCollection> apk =
      io::ZipFileCollection::Create(path, &error);
  if (!apk) {
    context->GetDiagnostics()->Error(DiagMessage(source) << error);
    return {};
  }

  io::IFile* file = apk->FindFile("resources.arsc");
  if (!file) {
    context->GetDiagnostics()->Error(DiagMessage(source)
                                     << "no resources.arsc found");
    return {};
  }

  std::unique_ptr<io::IData> data = file->OpenAsData();
  if (!data) {
    context->GetDiagnostics()->Error(DiagMessage(source)
                                     << "could not open resources.arsc");
    return {};
  }

  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();
  BinaryResourceParser parser(context, table.get(), source, data->data(),
                              data->size());
  if (!parser.Parse()) {
    return {};
  }

  return util::make_unique<LoadedApk>(source, std::move(apk), std::move(table));
}

bool LoadedApk::WriteToArchive(IAaptContext* context, IArchiveWriter* writer) {
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

    // The resource table needs to be reserialized since it might have changed.
    if (path == "resources.arsc") {
      BigBuffer buffer = BigBuffer(1024);
      TableFlattener flattener(&buffer);
      if (!flattener.Consume(context, table_.get())) {
        return false;
      }

      if (!writer->StartEntry(path, ArchiveEntry::kAlign) || !writer->WriteEntry(buffer) ||
          !writer->FinishEntry()) {
        context->GetDiagnostics()->Error(DiagMessage()
                                         << "Error when writing file '" << path << "' in APK.");
        return false;
      }
      continue;
    }

    std::unique_ptr<io::IData> data = file->OpenAsData();
    uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
    if (!writer->StartEntry(path, compression_flags) ||
        !writer->WriteEntry(data->data(), data->size()) || !writer->FinishEntry()) {
      context->GetDiagnostics()->Error(DiagMessage()
                                       << "Error when writing file '" << path << "' in APK.");
      return false;
    }
  }

  return true;
}

}  // namespace aapt
