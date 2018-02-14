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

#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "configuration/ConfigurationParser.h"

namespace aapt {

struct MultiApkGeneratorOptions {
  std::string out_dir;
  std::vector<configuration::OutputArtifact> apk_artifacts;
  TableFlattenerOptions table_flattener_options;
  std::unordered_set<std::string> kept_artifacts;
};

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
  bool FromBaseApk(const MultiApkGeneratorOptions& options);

 protected:
  virtual std::unique_ptr<ResourceTable> FilterTable(IAaptContext* context,
                                                     const configuration::OutputArtifact& artifact,
                                                     const ResourceTable& old_table,
                                                     FilterChain* chain);

 private:
  IDiagnostics* GetDiagnostics() {
    return context_->GetDiagnostics();
  }

  bool UpdateManifest(const configuration::OutputArtifact& artifact,
                      std::unique_ptr<xml::XmlResource>* updated_manifest, IDiagnostics* diag);

  /**
   * Adds the <screen> elements to the parent node for the provided density configuration.
   */
  void AddScreens(const ConfigDescription& config, xml::Element* parent);

  LoadedApk* apk_;
  IAaptContext* context_;
};

}  // namespace aapt

#endif  // AAPT2_APKSPLITTER_H
