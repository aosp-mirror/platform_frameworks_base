/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Convert.h"

#include <vector>

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "ValueVisitor.h"
#include "android-base/file.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/BigBufferStream.h"
#include "androidfw/StringPiece.h"
#include "cmd/Util.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "format/proto/ProtoDeserialize.h"
#include "format/proto/ProtoSerialize.h"
#include "io/Util.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "util/Util.h"

using ::android::StringPiece;
using ::android::base::StringPrintf;
using ::std::unique_ptr;
using ::std::vector;

namespace aapt {

class IApkSerializer {
 public:
  IApkSerializer(IAaptContext* context, const android::Source& source)
      : context_(context), source_(source) {
  }

  virtual bool SerializeXml(const xml::XmlResource* xml, const std::string& path, bool utf16,
                            IArchiveWriter* writer, uint32_t compression_flags) = 0;
  virtual bool SerializeTable(ResourceTable* table, IArchiveWriter* writer) = 0;
  virtual bool SerializeFile(FileReference* file, IArchiveWriter* writer) = 0;

  virtual ~IApkSerializer() = default;

 protected:
  IAaptContext* context_;
  android::Source source_;
};

class BinaryApkSerializer : public IApkSerializer {
 public:
  BinaryApkSerializer(IAaptContext* context, const android::Source& source,
                      const TableFlattenerOptions& table_flattener_options,
                      const XmlFlattenerOptions& xml_flattener_options)
      : IApkSerializer(context, source),
        table_flattener_options_(table_flattener_options),
        xml_flattener_options_(xml_flattener_options) {
  }

  bool SerializeXml(const xml::XmlResource* xml, const std::string& path, bool utf16,
                    IArchiveWriter* writer, uint32_t compression_flags) override {
    android::BigBuffer buffer(4096);
    xml_flattener_options_.use_utf16 = utf16;
    XmlFlattener flattener(&buffer, xml_flattener_options_);
    if (!flattener.Consume(context_, xml)) {
      return false;
    }

    android::BigBufferInputStream input_stream(&buffer);
    return io::CopyInputStreamToArchive(context_, &input_stream, path, compression_flags, writer);
  }

  bool SerializeTable(ResourceTable* table, IArchiveWriter* writer) override {
    android::BigBuffer buffer(4096);
    TableFlattener table_flattener(table_flattener_options_, &buffer);
    if (!table_flattener.Consume(context_, table)) {
      return false;
    }

    android::BigBufferInputStream input_stream(&buffer);
    return io::CopyInputStreamToArchive(context_, &input_stream, kApkResourceTablePath,
                                        ArchiveEntry::kAlign, writer);
  }

  bool SerializeFile(FileReference* file, IArchiveWriter* writer) override {
    if (file->type == ResourceFile::Type::kProtoXml) {
      unique_ptr<android::InputStream> in = file->file->OpenInputStream();
      if (in == nullptr) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to open file " << *file->path);
        return false;
      }

      pb::XmlNode pb_node;
      io::ProtoInputStreamReader proto_reader(in.get());
      if (!proto_reader.ReadMessage(&pb_node)) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to parse proto XML " << *file->path);
        return false;
      }

      std::string error;
      unique_ptr<xml::XmlResource> xml = DeserializeXmlResourceFromPb(pb_node, &error);
      if (xml == nullptr) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to deserialize proto XML " << *file->path
                                          << ": " << error);
        return false;
      }

      if (!SerializeXml(xml.get(), *file->path, false /*utf16*/, writer,
                        file->file->WasCompressed() ? ArchiveEntry::kCompress : 0u)) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to serialize to binary XML: " << *file->path);
        return false;
      }

      file->type = ResourceFile::Type::kBinaryXml;
    } else {
      if (!io::CopyFileToArchivePreserveCompression(context_, file->file, *file->path, writer)) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to copy file " << *file->path);
        return false;
      }
    }

    return true;
  }

 private:
  TableFlattenerOptions table_flattener_options_;
  XmlFlattenerOptions xml_flattener_options_;

  DISALLOW_COPY_AND_ASSIGN(BinaryApkSerializer);
};

class ProtoApkSerializer : public IApkSerializer {
 public:
  ProtoApkSerializer(IAaptContext* context, const android::Source& source)
      : IApkSerializer(context, source) {
  }

  bool SerializeXml(const xml::XmlResource* xml, const std::string& path, bool utf16,
                    IArchiveWriter* writer, uint32_t compression_flags) override {
    pb::XmlNode pb_node;
    SerializeXmlResourceToPb(*xml, &pb_node);
    return io::CopyProtoToArchive(context_, &pb_node, path, compression_flags, writer);
  }

  bool SerializeTable(ResourceTable* table, IArchiveWriter* writer) override {
    pb::ResourceTable pb_table;
    SerializeTableToPb(*table, &pb_table, context_->GetDiagnostics());
    return io::CopyProtoToArchive(context_, &pb_table, kProtoResourceTablePath,
                                  ArchiveEntry::kCompress, writer);
  }

  bool SerializeFile(FileReference* file, IArchiveWriter* writer) override {
    if (file->type == ResourceFile::Type::kBinaryXml) {
      std::unique_ptr<io::IData> data = file->file->OpenAsData();
      if (!data) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to open file " << *file->path);
        return false;
      }

      std::string error;
      std::unique_ptr<xml::XmlResource> xml = xml::Inflate(data->data(), data->size(), &error);
      if (xml == nullptr) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to parse binary XML: " << error);
        return false;
      }

      if (!SerializeXml(xml.get(), *file->path, false /*utf16*/, writer,
                        file->file->WasCompressed() ? ArchiveEntry::kCompress : 0u)) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to serialize to proto XML: " << *file->path);
        return false;
      }

      file->type = ResourceFile::Type::kProtoXml;
    } else {
      if (!io::CopyFileToArchivePreserveCompression(context_, file->file, *file->path, writer)) {
        context_->GetDiagnostics()->Error(android::DiagMessage(source_)
                                          << "failed to copy file " << *file->path);
        return false;
      }
    }

    return true;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ProtoApkSerializer);
};

class Context : public IAaptContext {
 public:
  Context() : mangler_({}), symbols_(&mangler_) {
  }

  PackageType GetPackageType() override {
    return PackageType::kApp;
  }

  SymbolTable* GetExternalSymbols() override {
    return &symbols_;
  }

  android::IDiagnostics* GetDiagnostics() override {
    return &diag_;
  }

  const std::string& GetCompilationPackage() override {
    return package_;
  }

  uint8_t GetPackageId() override {
    // Nothing should call this.
    UNIMPLEMENTED(FATAL) << "PackageID should not be necessary";
    return 0;
  }

  NameMangler* GetNameMangler() override {
    UNIMPLEMENTED(FATAL);
    return nullptr;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  int GetMinSdkVersion() override {
    return min_sdk_;
  }

  const std::set<std::string>& GetSplitNameDependencies() override {
    UNIMPLEMENTED(FATAL) << "Split Name Dependencies should not be necessary";
    static std::set<std::string> empty;
    return empty;
  }

  bool verbose_ = false;
  std::string package_;
  int32_t min_sdk_ = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(Context);

  NameMangler mangler_;
  SymbolTable symbols_;
  StdErrDiagnostics diag_;
};

int Convert(IAaptContext* context, LoadedApk* apk, IArchiveWriter* output_writer,
            ApkFormat output_format, TableFlattenerOptions table_flattener_options,
            XmlFlattenerOptions xml_flattener_options) {
  unique_ptr<IApkSerializer> serializer;
  if (output_format == ApkFormat::kBinary) {
    serializer.reset(new BinaryApkSerializer(context, apk->GetSource(), table_flattener_options,
                                             xml_flattener_options));
  } else if (output_format == ApkFormat::kProto) {
    serializer.reset(new ProtoApkSerializer(context, apk->GetSource()));
  } else {
    context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                     << "Cannot convert APK to unknown format");
    return 1;
  }

  io::IFile* manifest = apk->GetFileCollection()->FindFile(kAndroidManifestPath);
  if (!serializer->SerializeXml(apk->GetManifest(), kAndroidManifestPath, true /*utf16*/,
                                output_writer, (manifest != nullptr && manifest->WasCompressed())
                                               ? ArchiveEntry::kCompress : 0u)) {
    context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                     << "failed to serialize AndroidManifest.xml");
    return 1;
  }

  if (apk->GetResourceTable() != nullptr) {
    // The table might be modified by below code.
    auto converted_table = apk->GetResourceTable();

    std::unordered_set<std::string> files_written;

    // Resources
    for (const auto& package : converted_table->packages) {
      for (const auto& type : package->types) {
        for (const auto& entry : type->entries) {
          for (const auto& config_value : entry->values) {
            FileReference* file = ValueCast<FileReference>(config_value->value.get());
            if (file != nullptr) {
              if (file->file == nullptr) {
                context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                                 << "no file associated with " << *file);
                return 1;
              }

              // Only serialize if we haven't seen this file before
              if (files_written.insert(*file->path).second) {
                if (!serializer->SerializeFile(file, output_writer)) {
                  context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                                   << "failed to serialize file " << *file->path);
                  return 1;
                }
              }
            } // file
          } // config_value
        } // entry
      } // type
    } // package

    // Converted resource table
    if (!serializer->SerializeTable(converted_table, output_writer)) {
      context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                       << "failed to serialize the resource table");
      return 1;
    }
  }

  // Other files
  std::unique_ptr<io::IFileCollectionIterator> iterator = apk->GetFileCollection()->Iterator();
  while (iterator->HasNext()) {
    io::IFile* file = iterator->Next();
    std::string path = file->GetSource().path;

    // Manifest, resource table and resources have already been taken care of.
    if (path == kAndroidManifestPath ||
        path == kApkResourceTablePath ||
        path == kProtoResourceTablePath ||
        path.find("res/") == 0) {
      continue;
    }

    if (!io::CopyFileToArchivePreserveCompression(context, file, path, output_writer)) {
      context->GetDiagnostics()->Error(android::DiagMessage(apk->GetSource())
                                       << "failed to copy file " << path);
      return 1;
    }
  }

  return 0;
}

bool ExtractResourceConfig(const std::string& path, IAaptContext* context,
                           TableFlattenerOptions& out_options) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true /*follow_symlinks*/)) {
    context->GetDiagnostics()->Error(android::DiagMessage(path) << "failed reading config file");
    return false;
  }
  std::unordered_set<ResourceName> resources_exclude_list;
  bool result = ParseResourceConfig(content, context, resources_exclude_list,
                                    out_options.name_collapse_exemptions,
                                    out_options.path_shorten_exemptions);
  if (!result) {
    return false;
  }
  if (!resources_exclude_list.empty()) {
    context->GetDiagnostics()->Error(android::DiagMessage(path)
                                     << "Unsupported '#remove' directive in resource config.");
    return false;
  }
  return true;
}

const char* ConvertCommand::kOutputFormatProto = "proto";
const char* ConvertCommand::kOutputFormatBinary = "binary";

int ConvertCommand::Action(const std::vector<std::string>& args) {
  if (args.size() != 1) {
    std::cerr << "must supply a single APK\n";
    Usage(&std::cerr);
    return 1;
  }

  Context context;
  StringPiece path = args[0];
  unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(path, context.GetDiagnostics());
  if (apk == nullptr) {
    context.GetDiagnostics()->Error(android::DiagMessage(path) << "failed to load APK");
    return 1;
  }

  auto app_info = ExtractAppInfoFromBinaryManifest(*apk->GetManifest(), context.GetDiagnostics());
  if (!app_info) {
    return 1;
  }

  context.package_ = app_info.value().package;
  context.min_sdk_ = app_info.value().min_sdk_version.value_or(0);
  unique_ptr<IArchiveWriter> writer = CreateZipFileArchiveWriter(context.GetDiagnostics(),
                                                                 output_path_);
  if (writer == nullptr) {
    return 1;
  }

  ApkFormat format;
  if (!output_format_ || output_format_.value() == ConvertCommand::kOutputFormatBinary) {
    format = ApkFormat::kBinary;
  } else if (output_format_.value() == ConvertCommand::kOutputFormatProto) {
    format = ApkFormat::kProto;
  } else {
    context.GetDiagnostics()->Error(android::DiagMessage(path)
                                    << "Invalid value for flag --output-format: "
                                    << output_format_.value());
    return 1;
  }
  if (enable_sparse_encoding_) {
    table_flattener_options_.sparse_entries = SparseEntriesMode::Enabled;
  }
  if (force_sparse_encoding_) {
    table_flattener_options_.sparse_entries = SparseEntriesMode::Forced;
  }
  table_flattener_options_.use_compact_entries = enable_compact_entries_;
  if (resources_config_path_) {
    if (!ExtractResourceConfig(*resources_config_path_, &context, table_flattener_options_)) {
      return 1;
    }
  }

  return Convert(&context, apk.get(), writer.get(), format, table_flattener_options_,
                 xml_flattener_options_);
}

}  // namespace aapt
