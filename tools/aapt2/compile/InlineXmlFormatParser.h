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

#ifndef AAPT_COMPILE_INLINEXMLFORMATPARSER_H
#define AAPT_COMPILE_INLINEXMLFORMATPARSER_H

#include <memory>
#include <vector>

#include "android-base/macros.h"
#include "process/IResourceTableConsumer.h"
#include "xml/XmlDom.h"

namespace aapt {

// Extracts Inline XML definitions into their own xml::XmlResource objects.
//
// Inline XML looks like:
//
// <animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
//                  xmlns:aapt="http://schemas.android.com/aapt" >
//   <aapt:attr name="android:drawable" >
//     <vector
//       android:height="64dp"
//       android:width="64dp"
//       android:viewportHeight="600"
//       android:viewportWidth="600"/>
//   </aapt:attr>
// </animated-vector>
//
// The <vector> will be extracted into its own XML file and <animated-vector> will
// gain an attribute 'android:drawable' set to a reference to the extracted <vector> resource.
class InlineXmlFormatParser : public IXmlResourceConsumer {
 public:
  explicit InlineXmlFormatParser() = default;

  bool Consume(IAaptContext* context, xml::XmlResource* doc) override;

  std::vector<std::unique_ptr<xml::XmlResource>>& GetExtractedInlineXmlDocuments() {
    return queue_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(InlineXmlFormatParser);

  std::vector<std::unique_ptr<xml::XmlResource>> queue_;
};

}  // namespace aapt

#endif /* AAPT_COMPILE_INLINEXMLFORMATPARSER_H */
