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

#include "xml/XmlActionExecutor.h"

using ::android::StringPiece;

namespace aapt {
namespace xml {

static bool wrapper_one(const XmlNodeAction::ActionFunc& f, Element* el,
                        const XmlActionExecutorPolicy& policy, SourcePathDiagnostics*) {
  return f(el);
}

static bool wrapper_two(const XmlNodeAction::ActionFuncWithDiag& f, Element* el,
                        const XmlActionExecutorPolicy& policy, SourcePathDiagnostics* diag) {
  return f(el, diag);
}

static bool wrapper_three(const XmlNodeAction::ActionFuncWithPolicyAndDiag& f, Element* el,
                          const XmlActionExecutorPolicy& policy, SourcePathDiagnostics* diag) {
  return f(el, policy, diag);
}

void XmlNodeAction::Action(XmlNodeAction::ActionFunc f) {
  actions_.emplace_back(std::bind(wrapper_one, std::move(f), std::placeholders::_1,
                                  std::placeholders::_2, std::placeholders::_3));
}

void XmlNodeAction::Action(XmlNodeAction::ActionFuncWithDiag f) {
  actions_.emplace_back(std::bind(wrapper_two, std::move(f), std::placeholders::_1,
                                  std::placeholders::_2, std::placeholders::_3));
}

void XmlNodeAction::Action(XmlNodeAction::ActionFuncWithPolicyAndDiag f) {
  actions_.emplace_back(std::bind(wrapper_three, std::move(f), std::placeholders::_1,
                                  std::placeholders::_2, std::placeholders::_3));
}

static void PrintElementToDiagMessage(const Element* el, DiagMessage* msg) {
  *msg << "<";
  if (!el->namespace_uri.empty()) {
    *msg << el->namespace_uri << ":";
  }
  *msg << el->name << ">";
}

bool XmlNodeAction::Execute(XmlActionExecutorPolicy policy, std::vector<StringPiece>* bread_crumb,
                            SourcePathDiagnostics* diag, Element* el) const {
  bool error = false;
  for (const ActionFuncWithPolicyAndDiag& action : actions_) {
    error |= !action(el, policy, diag);
  }

  for (Element* child_el : el->GetChildElements()) {
    if (child_el->namespace_uri.empty()) {
      std::map<std::string, XmlNodeAction>::const_iterator iter = map_.find(child_el->name);
      if (iter != map_.end()) {
        // Use the iterator's copy of the element name, because the element may be modified.
        bread_crumb->push_back(iter->first);
        error |= !iter->second.Execute(policy, bread_crumb, diag, child_el);
        bread_crumb->pop_back();
        continue;
      }

      if (policy != XmlActionExecutorPolicy::kNone) {
        DiagMessage error_msg(child_el->line_number);
        error_msg << "unexpected element ";
        PrintElementToDiagMessage(child_el, &error_msg);
        error_msg << " found in ";
        for (const StringPiece& element : *bread_crumb) {
          error_msg << "<" << element << ">";
        }
        if (policy == XmlActionExecutorPolicy::kAllowListWarning) {
          // Treat the error only as a warning.
          diag->Warn(error_msg);
        } else {
          // Policy is XmlActionExecutorPolicy::kAllowList, we should fail.
          diag->Error(error_msg);
          error = true;
        }
      }
    }
  }
  return !error;
}

bool XmlActionExecutor::Execute(XmlActionExecutorPolicy policy, IDiagnostics* diag,
                                XmlResource* doc) const {
  SourcePathDiagnostics source_diag(doc->file.source, diag);

  Element* el = doc->root.get();
  if (!el) {
    if (policy == XmlActionExecutorPolicy::kAllowList) {
      source_diag.Error(DiagMessage() << "no root XML tag found");
      return false;
    }
    return true;
  }

  if (el->namespace_uri.empty()) {
    std::map<std::string, XmlNodeAction>::const_iterator iter = map_.find(el->name);
    if (iter != map_.end()) {
      std::vector<StringPiece> bread_crumb;
      bread_crumb.push_back(iter->first);
      return iter->second.Execute(policy, &bread_crumb, &source_diag, el);
    }

    if (policy == XmlActionExecutorPolicy::kAllowList) {
      DiagMessage error_msg(el->line_number);
      error_msg << "unexpected root element ";
      PrintElementToDiagMessage(el, &error_msg);
      source_diag.Error(error_msg);
      return false;
    }
  }
  return true;
}

}  // namespace xml
}  // namespace aapt
