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

#ifndef AAPT_FORMAT_BINARY_XMLFLATTENER_H
#define AAPT_FORMAT_BINARY_XMLFLATTENER_H

#include "android-base/macros.h"

#include "process/IResourceTableConsumer.h"
#include "util/BigBuffer.h"
#include "xml/XmlDom.h"

namespace aapt {

struct XmlFlattenerOptions {
  // Keep attribute raw string values along with typed values.
  bool keep_raw_values = false;

  // Encode the strings in UTF-16. Only needed for AndroidManifest.xml to avoid a bug in
  // certain non-AOSP platforms: https://issuetracker.google.com/64434571
  bool use_utf16 = false;
};

class XmlFlattener {
 public:
  XmlFlattener(BigBuffer* buffer, XmlFlattenerOptions options)
      : buffer_(buffer), options_(options) {
  }

  bool Consume(IAaptContext* context, const xml::XmlResource* resource);

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlFlattener);

  bool Flatten(IAaptContext* context, const xml::Node* node);

  BigBuffer* buffer_;
  XmlFlattenerOptions options_;
};

}  // namespace aapt

#endif /* AAPT_FORMAT_BINARY_XMLFLATTENER_H */
