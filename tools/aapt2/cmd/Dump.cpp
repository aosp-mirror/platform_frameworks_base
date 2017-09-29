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

#include <vector>

#include "androidfw/StringPiece.h"

#include "Debug.h"
#include "Diagnostics.h"
#include "Flags.h"
#include "format/binary/BinaryResourceParser.h"
#include "format/proto/ProtoDeserialize.h"
#include "io/ZipArchive.h"
#include "process/IResourceTableConsumer.h"
#include "util/Files.h"

using ::android::StringPiece;

namespace aapt {

bool DumpCompiledFile(const pb::internal::CompiledFile& pb_file, const void* data, size_t len,
                      const Source& source, IAaptContext* context) {
  ResourceFile file;
  std::string error;
  if (!DeserializeCompiledFileFromPb(pb_file, &file, &error)) {
    context->GetDiagnostics()->Warn(DiagMessage(source)
                                    << "failed to read compiled file: " << error);
    return false;
  }

  std::cout << "Resource: " << file.name << "\n"
            << "Config:   " << file.config << "\n"
            << "Source:   " << file.source << "\n";
  return true;
}

bool TryDumpFile(IAaptContext* context, const std::string& file_path) {
  std::string err;
  std::unique_ptr<io::ZipFileCollection> zip = io::ZipFileCollection::Create(file_path, &err);
  if (zip) {
    ResourceTable table;
    if (io::IFile* file = zip->FindFile("resources.arsc.flat")) {
      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (data == nullptr) {
        context->GetDiagnostics()->Error(DiagMessage(file_path)
                                         << "failed to open resources.arsc.flat");
        return false;
      }

      pb::ResourceTable pb_table;
      if (!pb_table.ParseFromArray(data->data(), data->size())) {
        context->GetDiagnostics()->Error(DiagMessage(file_path) << "invalid resources.arsc.flat");
        return false;
      }

      ResourceTable table;
      if (!DeserializeTableFromPb(pb_table, &table, &err)) {
        context->GetDiagnostics()->Error(DiagMessage(file_path)
                                         << "failed to parse table: " << err);
        return false;
      }
    } else if (io::IFile* file = zip->FindFile("resources.arsc")) {
      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (!data) {
        context->GetDiagnostics()->Error(DiagMessage(file_path) << "failed to open resources.arsc");
        return false;
      }

      BinaryResourceParser parser(context, &table, Source(file_path), data->data(), data->size());
      if (!parser.Parse()) {
        return false;
      }
    }

    DebugPrintTableOptions options;
    options.show_sources = true;
    Debug::PrintTable(&table, options);
    return true;
  }

  err.clear();

  Maybe<android::FileMap> file = file::MmapPath(file_path, &err);
  if (!file) {
    context->GetDiagnostics()->Error(DiagMessage(file_path) << err);
    return false;
  }

  android::FileMap* file_map = &file.value();

  // Check to see if this is a loose ResourceTable.
  pb::ResourceTable pb_table;
  if (pb_table.ParseFromArray(file_map->getDataPtr(), file_map->getDataLength())) {
    ResourceTable table;
    if (DeserializeTableFromPb(pb_table, &table, &err)) {
      DebugPrintTableOptions options;
      options.show_sources = true;
      Debug::PrintTable(&table, options);
      return true;
    }
  }

  // Try as a compiled file.
  CompiledFileInputStream input(file_map->getDataPtr(), file_map->getDataLength());
  uint32_t num_files = 0;
  if (!input.ReadLittleEndian32(&num_files)) {
    return false;
  }

  for (uint32_t i = 0; i < num_files; i++) {
    pb::internal::CompiledFile compiled_file;
    if (!input.ReadCompiledFile(&compiled_file)) {
      context->GetDiagnostics()->Warn(DiagMessage() << "failed to read compiled file");
      return false;
    }

    uint64_t offset, len;
    if (!input.ReadDataMetaData(&offset, &len)) {
      context->GetDiagnostics()->Warn(DiagMessage() << "failed to read meta data");
      return false;
    }

    const void* data = static_cast<const uint8_t*>(file_map->getDataPtr()) + offset;
    if (!DumpCompiledFile(compiled_file, data, len, Source(file_path), context)) {
      return false;
    }
  }
  return true;
}

class DumpContext : public IAaptContext {
 public:
  PackageType GetPackageType() override {
    // Doesn't matter.
    return PackageType::kApp;
  }

  IDiagnostics* GetDiagnostics() override {
    return &diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    abort();
    return nullptr;
  }

  const std::string& GetCompilationPackage() override {
    static std::string empty;
    return empty;
  }

  uint8_t GetPackageId() override {
    return 0;
  }

  SymbolTable* GetExternalSymbols() override {
    abort();
    return nullptr;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  void SetVerbose(bool val) {
    verbose_ = val;
  }

  int GetMinSdkVersion() override {
    return 0;
  }

 private:
  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
};

/**
 * Entry point for dump command.
 */
int Dump(const std::vector<StringPiece>& args) {
  bool verbose = false;
  Flags flags = Flags().OptionalSwitch("-v", "increase verbosity of output", &verbose);
  if (!flags.Parse("aapt2 dump", args, &std::cerr)) {
    return 1;
  }

  DumpContext context;
  context.SetVerbose(verbose);

  for (const std::string& arg : flags.GetArgs()) {
    if (!TryDumpFile(&context, arg)) {
      return 1;
    }
  }

  return 0;
}

}  // namespace aapt
