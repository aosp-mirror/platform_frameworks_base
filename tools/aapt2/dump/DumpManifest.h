/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef AAPT2_DUMP_MANIFEST_H
#define AAPT2_DUMP_MANIFEST_H

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "text/Printer.h"

namespace aapt {

struct DumpManifestOptions {
  /** Include meta information from <meta-data> elements in the output. */
  bool include_meta_data = false;
  /** Only output permission information. */
  bool only_permissions = false;
};

/** Print information extracted from the manifest of the APK. */
int DumpManifest(LoadedApk* apk, DumpManifestOptions& options, text::Printer* printer,
                 IDiagnostics* diag);

}  // namespace aapt

#endif  // AAPT2_DUMP_MANIFEST_H