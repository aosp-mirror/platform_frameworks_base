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

#ifndef AAPT_DEBUG_H
#define AAPT_DEBUG_H

// Include for printf-like debugging.
#include <iostream>

#include "Resource.h"
#include "ResourceTable.h"
#include "text/Printer.h"
#include "xml/XmlDom.h"

namespace aapt {

struct DebugPrintTableOptions {
  bool show_sources = false;
  bool show_values = true;
};

struct Debug {
  static void PrintTable(const ResourceTable& table, const DebugPrintTableOptions& options,
                         text::Printer* printer);
  static void PrintStyleGraph(ResourceTable* table, const ResourceName& target_style);
  static void DumpHex(const void* data, size_t len);
  static void DumpXml(const xml::XmlResource& doc, text::Printer* printer);
  static void DumpResStringPool(const android::ResStringPool* pool, text::Printer* printer);
  static void DumpOverlayable(const ResourceTable& table, text::Printer* printer);
  static void DumpChunks(const void* data, size_t len, text::Printer* printer, IDiagnostics* diag);
};

}  // namespace aapt

#endif  // AAPT_DEBUG_H
