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

namespace aapt {

std::unique_ptr<LoadedApk> LoadedApk::LoadApkFromPath(
    IAaptContext* context, const StringPiece& path) {
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

}  // namespace aapt
