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

#ifndef AAPT2_APKSPLITTER_H
#define AAPT2_APKSPLITTER_H

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "configuration/ConfigurationParser.h"

namespace aapt {

/**
 * Generates a set of APKs that are a subset of the original base APKs. Each of the new APKs contain
 * only the resources and assets for an artifact in the configuration file.
 */
class MultiApkGenerator {
 public:
  MultiApkGenerator(LoadedApk* apk, IAaptContext* context);

  /**
   * Writes a set of APKs to the provided output directory. Each APK is a subset fo the base APK and
   * represents an artifact in the post processing configuration.
   */
  bool FromBaseApk(const std::string& out_dir,
                   const configuration::PostProcessingConfiguration& config,
                   const TableFlattenerOptions& table_flattener_options);

 private:
  IDiagnostics* GetDiagnostics() {
    return context_->GetDiagnostics();
  }

  LoadedApk* apk_;
  IAaptContext* context_;
};

}  // namespace aapt

#endif  // AAPT2_APKSPLITTER_H
