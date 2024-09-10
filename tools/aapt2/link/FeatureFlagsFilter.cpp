/*
 * Copyright 2023 The Android Open Source Project
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

#include "link/FeatureFlagsFilter.h"

#include <string_view>

#include "androidfw/IDiagnostics.h"
#include "androidfw/Source.h"
#include "util/Util.h"
#include "xml/XmlDom.h"
#include "xml/XmlUtil.h"

using ::aapt::xml::Element;
using ::aapt::xml::Node;
using ::aapt::xml::NodeCast;

namespace aapt {

class FlagsVisitor : public xml::Visitor {
 public:
  explicit FlagsVisitor(android::IDiagnostics* diagnostics,
                        const FeatureFlagValues& feature_flag_values,
                        const FeatureFlagsFilterOptions& options)
      : diagnostics_(diagnostics), feature_flag_values_(feature_flag_values), options_(options) {
  }

  void Visit(xml::Element* node) override {
    std::erase_if(node->children,
                  [this](std::unique_ptr<xml::Node>& node) { return ShouldRemove(node); });
    VisitChildren(node);
  }

  bool HasError() const {
    return has_error_;
  }

 private:
  bool ShouldRemove(std::unique_ptr<xml::Node>& node) {
    if (const auto* el = NodeCast<Element>(node.get())) {
      auto* attr = el->FindAttribute(xml::kSchemaAndroid, "featureFlag");
      if (attr == nullptr) {
        return false;
      }

      bool negated = false;
      std::string_view flag_name = util::TrimWhitespace(attr->value);
      if (flag_name.starts_with('!')) {
        negated = true;
        flag_name = flag_name.substr(1);
      }

      if (auto it = feature_flag_values_.find(flag_name); it != feature_flag_values_.end()) {
        if (it->second.enabled.has_value()) {
          if (options_.flags_must_be_readonly && !it->second.read_only) {
            diagnostics_->Error(android::DiagMessage(node->line_number)
                                << "attribute 'android:featureFlag' has flag '" << flag_name
                                << "' which must be readonly but is not");
            has_error_ = true;
            return false;
          }
          if (options_.remove_disabled_elements) {
            // Remove if flag==true && attr=="!flag" (negated) OR flag==false && attr=="flag"
            return *it->second.enabled == negated;
          }
        } else if (options_.flags_must_have_value) {
          diagnostics_->Error(android::DiagMessage(node->line_number)
                              << "attribute 'android:featureFlag' has flag '" << flag_name
                              << "' without a true/false value from --feature_flags parameter");
          has_error_ = true;
          return false;
        }
      } else if (options_.fail_on_unrecognized_flags) {
        diagnostics_->Error(android::DiagMessage(node->line_number)
                            << "attribute 'android:featureFlag' has flag '" << flag_name
                            << "' not found in flags from --feature_flags parameter");
        has_error_ = true;
        return false;
      }
    }

    return false;
  }

  android::IDiagnostics* diagnostics_;
  const FeatureFlagValues& feature_flag_values_;
  const FeatureFlagsFilterOptions& options_;
  bool has_error_ = false;
};

bool FeatureFlagsFilter::Consume(IAaptContext* context, xml::XmlResource* doc) {
  FlagsVisitor visitor(context->GetDiagnostics(), feature_flag_values_, options_);
  doc->root->Accept(&visitor);
  return !visitor.HasError();
}

}  // namespace aapt
