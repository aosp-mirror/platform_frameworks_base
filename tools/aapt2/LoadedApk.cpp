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
#include "format/Archive.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "format/proto/ProtoDeserialize.h"
#include "format/proto/ProtoSerialize.h"
#include "io/BigBufferStream.h"
#include "io/Util.h"
#include "xml/XmlDom.h"

using ::aapt::io::IFile;
using ::aapt::io::IFileCollection;
using ::aapt::xml::XmlResource;
using ::android::StringPiece;
using ::std::unique_ptr;

namespace aapt {

std::unique_ptr<LoadedApk> LoadedApk::LoadApkFromPath(const StringPiece& path, IDiagnostics* diag) {
  Source source(path);
  std::string error;
  std::unique_ptr<io::ZipFileCollection> apk = io::ZipFileCollection::Create(path, &error);
  if (apk == nullptr) {
    diag->Error(DiagMessage(path) << "failed opening zip: " << error);
    return {};
  }

  ApkFormat apkFormat = DetermineApkFormat(apk.get());
  switch (apkFormat) {
    case ApkFormat::kBinary:
      return LoadBinaryApkFromFileCollection(source, std::move(apk), diag);
    case ApkFormat::kProto:
      return LoadProtoApkFromFileCollection(source, std::move(apk), diag);
    default:
      diag->Error(DiagMessage(path) << "could not identify format of APK");
      return {};
  }
}

std::unique_ptr<LoadedApk> LoadedApk::LoadProtoApkFromFileCollection(
    const Source& source, unique_ptr<io::IFileCollection> collection, IDiagnostics* diag) {
  std::unique_ptr<ResourceTable> table;

  io::IFile* table_file = collection->FindFile(kProtoResourceTablePath);
  if (table_file != nullptr) {
    pb::ResourceTable pb_table;
    std::unique_ptr<io::InputStream> in = table_file->OpenInputStream();
    if (in == nullptr) {
      diag->Error(DiagMessage(source) << "failed to open " << kProtoResourceTablePath);
      return {};
    }

    io::ZeroCopyInputAdaptor adaptor(in.get());
    if (!pb_table.ParseFromZeroCopyStream(&adaptor)) {
      diag->Error(DiagMessage(source) << "failed to read " << kProtoResourceTablePath);
      return {};
    }

    std::string error;
    table = util::make_unique<ResourceTable>();
    if (!DeserializeTableFromPb(pb_table, collection.get(), table.get(), &error)) {
      diag->Error(DiagMessage(source)
                  << "failed to deserialize " << kProtoResourceTablePath << ": " << error);
      return {};
    }
  }

  io::IFile* manifest_file = collection->FindFile(kAndroidManifestPath);
  if (manifest_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kAndroidManifestPath);
    return {};
  }

  std::unique_ptr<io::InputStream> manifest_in = manifest_file->OpenInputStream();
  if (manifest_in == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kAndroidManifestPath);
    return {};
  }

  pb::XmlNode pb_node;
  io::ZeroCopyInputAdaptor manifest_adaptor(manifest_in.get());
  if (!pb_node.ParseFromZeroCopyStream(&manifest_adaptor)) {
    diag->Error(DiagMessage(source) << "failed to read proto " << kAndroidManifestPath);
    return {};
  }

  std::string error;
  std::unique_ptr<xml::XmlResource> manifest = DeserializeXmlResourceFromPb(pb_node, &error);
  if (manifest == nullptr) {
    diag->Error(DiagMessage(source)
                << "failed to deserialize proto " << kAndroidManifestPath << ": " << error);
    return {};
  }
  return util::make_unique<LoadedApk>(source, std::move(collection), std::move(table),
                                      std::move(manifest), ApkFormat::kProto);
}

std::unique_ptr<LoadedApk> LoadedApk::LoadBinaryApkFromFileCollection(
    const Source& source, unique_ptr<io::IFileCollection> collection, IDiagnostics* diag) {
  std::unique_ptr<ResourceTable> table;

  io::IFile* table_file = collection->FindFile(kApkResourceTablePath);
  if (table_file != nullptr) {
    table = util::make_unique<ResourceTable>();
    std::unique_ptr<io::IData> data = table_file->OpenAsData();
    if (data == nullptr) {
      diag->Error(DiagMessage(source) << "failed to open " << kApkResourceTablePath);
      return {};
    }
    BinaryResourceParser parser(diag, table.get(), source, data->data(), data->size(),
                                collection.get());
    if (!parser.Parse()) {
      return {};
    }
  }

  io::IFile* manifest_file = collection->FindFile(kAndroidManifestPath);
  if (manifest_file == nullptr) {
    diag->Error(DiagMessage(source) << "failed to find " << kAndroidManifestPath);
    return {};
  }

  std::unique_ptr<io::IData> manifest_data = manifest_file->OpenAsData();
  if (manifest_data == nullptr) {
    diag->Error(DiagMessage(source) << "failed to open " << kAndroidManifestPath);
    return {};
  }

  std::string error;
  std::unique_ptr<xml::XmlResource> manifest =
      xml::Inflate(manifest_data->data(), manifest_data->size(), &error);
  if (manifest == nullptr) {
    diag->Error(DiagMessage(source)
                << "failed to parse binary " << kAndroidManifestPath << ": " << error);
    return {};
  }
  return util::make_unique<LoadedApk>(source, std::move(collection), std::move(table),
                                      std::move(manifest), ApkFormat::kBinary);
}

bool LoadedApk::WriteToArchive(IAaptContext* context, const TableFlattenerOptions& options,
                               IArchiveWriter* writer) {
  FilterChain empty;
  return WriteToArchive(context, table_.get(), options, &empty, writer);
}

bool LoadedApk::WriteToArchive(IAaptContext* context, ResourceTable* split_table,
                               const TableFlattenerOptions& options, FilterChain* filters,
                               IArchiveWriter* writer, XmlResource* manifest) {
  std::set<std::string> referenced_resources;
  // List the files being referenced in the resource table.
  for (auto& pkg : split_table->packages) {
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
    path = path.substr(path.find('@') + 1);

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
    if (format_ == ApkFormat::kBinary && path == kApkResourceTablePath) {
      BigBuffer buffer(4096);
      // TODO(adamlesinski): How to determine if there were sparse entries (and if to encode
      // with sparse entries) b/35389232.
      TableFlattener flattener(options, &buffer);
      if (!flattener.Consume(context, split_table)) {
        return false;
      }

      io::BigBufferInputStream input_stream(&buffer);
      if (!io::CopyInputStreamToArchive(context,
                                        &input_stream,
                                        path,
                                        ArchiveEntry::kAlign,
                                        writer)) {
        return false;
      }
    } else if (format_ == ApkFormat::kProto && path == kProtoResourceTablePath) {
      pb::ResourceTable pb_table;
      SerializeTableToPb(*split_table, &pb_table, context->GetDiagnostics());
      if (!io::CopyProtoToArchive(context,
                                  &pb_table,
                                  path,
                                  ArchiveEntry::kAlign, writer)) {
        return false;
      }
    } else if (manifest != nullptr && path == "AndroidManifest.xml") {
      BigBuffer buffer(8192);
      XmlFlattenerOptions xml_flattener_options;
      xml_flattener_options.use_utf16 = true;
      XmlFlattener xml_flattener(&buffer, xml_flattener_options);
      if (!xml_flattener.Consume(context, manifest)) {
        context->GetDiagnostics()->Error(DiagMessage(path) << "flattening failed");
        return false;
      }

      uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
      io::BigBufferInputStream manifest_buffer_in(&buffer);
      if (!io::CopyInputStreamToArchive(context, &manifest_buffer_in, path, compression_flags,
                                        writer)) {
        return false;
      }
    } else {
      if (!io::CopyFileToArchivePreserveCompression(context, file, path, writer)) {
        return false;
      }
    }
  }
  return true;
}

ApkFormat LoadedApk::DetermineApkFormat(io::IFileCollection* apk) {
  if (apk->FindFile(kApkResourceTablePath) != nullptr) {
    return ApkFormat::kBinary;
  } else if (apk->FindFile(kProtoResourceTablePath) != nullptr) {
    return ApkFormat::kProto;
  } else {
    // If the resource table is not present, attempt to read the manifest.
    io::IFile* manifest_file = apk->FindFile(kAndroidManifestPath);
    if (manifest_file == nullptr) {
      return ApkFormat::kUnknown;
    }

    // First try in proto format.
    std::unique_ptr<io::InputStream> manifest_in = manifest_file->OpenInputStream();
    if (manifest_in != nullptr) {
      pb::XmlNode pb_node;
      io::ZeroCopyInputAdaptor manifest_adaptor(manifest_in.get());
      if (pb_node.ParseFromZeroCopyStream(&manifest_adaptor)) {
        return ApkFormat::kProto;
      }
    }

    // If it didn't work, try in binary format.
    std::unique_ptr<io::IData> manifest_data = manifest_file->OpenAsData();
    if (manifest_data != nullptr) {
      std::string error;
      std::unique_ptr<xml::XmlResource> manifest =
          xml::Inflate(manifest_data->data(), manifest_data->size(), &error);
      if (manifest != nullptr) {
        return ApkFormat::kBinary;
      }
    }

    return ApkFormat::kUnknown;
  }
}

}  // namespace aapt
