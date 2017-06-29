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

#include "java/ProguardRules.h"

#include <memory>
#include <string>

#include "android-base/macros.h"

#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace proguard {

class BaseVisitor : public xml::Visitor {
 public:
  BaseVisitor(const Source& source, KeepSet* keep_set)
      : source_(source), keep_set_(keep_set) {}

  virtual void Visit(xml::Text*) override{};

  virtual void Visit(xml::Namespace* node) override {
    for (const auto& child : node->children) {
      child->Accept(this);
    }
  }

  virtual void Visit(xml::Element* node) override {
    if (!node->namespace_uri.empty()) {
      Maybe<xml::ExtractedPackage> maybe_package =
          xml::ExtractPackageFromNamespace(node->namespace_uri);
      if (maybe_package) {
        // This is a custom view, let's figure out the class name from this.
        std::string package = maybe_package.value().package + "." + node->name;
        if (util::IsJavaClassName(package)) {
          AddClass(node->line_number, package);
        }
      }
    } else if (util::IsJavaClassName(node->name)) {
      AddClass(node->line_number, node->name);
    }

    for (const auto& child : node->children) {
      child->Accept(this);
    }
  }

 protected:
  void AddClass(size_t line_number, const std::string& class_name) {
    keep_set_->AddClass(Source(source_.path, line_number), class_name);
  }

  void AddMethod(size_t line_number, const std::string& method_name) {
    keep_set_->AddMethod(Source(source_.path, line_number), method_name);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(BaseVisitor);

  Source source_;
  KeepSet* keep_set_;
};

class LayoutVisitor : public BaseVisitor {
 public:
  LayoutVisitor(const Source& source, KeepSet* keep_set)
      : BaseVisitor(source, keep_set) {}

  virtual void Visit(xml::Element* node) override {
    bool check_class = false;
    bool check_name = false;
    if (node->namespace_uri.empty()) {
      if (node->name == "view") {
        check_class = true;
      } else if (node->name == "fragment") {
        check_class = check_name = true;
      }
    } else if (node->namespace_uri == xml::kSchemaAndroid) {
      check_name = node->name == "fragment";
    }

    for (const auto& attr : node->attributes) {
      if (check_class && attr.namespace_uri.empty() && attr.name == "class" &&
          util::IsJavaClassName(attr.value)) {
        AddClass(node->line_number, attr.value);
      } else if (check_name && attr.namespace_uri == xml::kSchemaAndroid &&
                 attr.name == "name" && util::IsJavaClassName(attr.value)) {
        AddClass(node->line_number, attr.value);
      } else if (attr.namespace_uri == xml::kSchemaAndroid &&
                 attr.name == "onClick") {
        AddMethod(node->line_number, attr.value);
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LayoutVisitor);
};

class MenuVisitor : public BaseVisitor {
 public:
  MenuVisitor(const Source& source, KeepSet* keep_set) : BaseVisitor(source, keep_set) {
  }

  virtual void Visit(xml::Element* node) override {
    if (node->namespace_uri.empty() && node->name == "item") {
      for (const auto& attr : node->attributes) {
        if (attr.namespace_uri == xml::kSchemaAndroid) {
          if ((attr.name == "actionViewClass" || attr.name == "actionProviderClass") &&
              util::IsJavaClassName(attr.value)) {
            AddClass(node->line_number, attr.value);
          } else if (attr.name == "onClick") {
            AddMethod(node->line_number, attr.value);
          }
        }
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(MenuVisitor);
};

class XmlResourceVisitor : public BaseVisitor {
 public:
  XmlResourceVisitor(const Source& source, KeepSet* keep_set)
      : BaseVisitor(source, keep_set) {}

  virtual void Visit(xml::Element* node) override {
    bool check_fragment = false;
    if (node->namespace_uri.empty()) {
      check_fragment =
          node->name == "PreferenceScreen" || node->name == "header";
    }

    if (check_fragment) {
      xml::Attribute* attr =
          node->FindAttribute(xml::kSchemaAndroid, "fragment");
      if (attr && util::IsJavaClassName(attr->value)) {
        AddClass(node->line_number, attr->value);
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlResourceVisitor);
};

class TransitionVisitor : public BaseVisitor {
 public:
  TransitionVisitor(const Source& source, KeepSet* keep_set)
      : BaseVisitor(source, keep_set) {}

  virtual void Visit(xml::Element* node) override {
    bool check_class =
        node->namespace_uri.empty() &&
        (node->name == "transition" || node->name == "pathMotion");
    if (check_class) {
      xml::Attribute* attr = node->FindAttribute({}, "class");
      if (attr && util::IsJavaClassName(attr->value)) {
        AddClass(node->line_number, attr->value);
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(TransitionVisitor);
};

class ManifestVisitor : public BaseVisitor {
 public:
  ManifestVisitor(const Source& source, KeepSet* keep_set, bool main_dex_only)
      : BaseVisitor(source, keep_set), main_dex_only_(main_dex_only) {}

  virtual void Visit(xml::Element* node) override {
    if (node->namespace_uri.empty()) {
      bool get_name = false;
      if (node->name == "manifest") {
        xml::Attribute* attr = node->FindAttribute({}, "package");
        if (attr) {
          package_ = attr->value;
        }
      } else if (node->name == "application") {
        get_name = true;
        xml::Attribute* attr =
            node->FindAttribute(xml::kSchemaAndroid, "backupAgent");
        if (attr) {
          Maybe<std::string> result =
              util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value());
          }
        }
        if (main_dex_only_) {
          xml::Attribute* default_process =
              node->FindAttribute(xml::kSchemaAndroid, "process");
          if (default_process) {
            default_process_ = default_process->value;
          }
        }
      } else if (node->name == "activity" || node->name == "service" ||
                 node->name == "receiver" || node->name == "provider") {
        get_name = true;

        if (main_dex_only_) {
          xml::Attribute* component_process =
              node->FindAttribute(xml::kSchemaAndroid, "process");

          const std::string& process =
              component_process ? component_process->value : default_process_;
          get_name = !process.empty() && process[0] != ':';
        }
      } else if (node->name == "instrumentation") {
        get_name = true;
      }

      if (get_name) {
        xml::Attribute* attr = node->FindAttribute(xml::kSchemaAndroid, "name");
        get_name = attr != nullptr;

        if (get_name) {
          Maybe<std::string> result =
              util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value());
          }
        }
      }
    }
    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ManifestVisitor);

  std::string package_;
  const bool main_dex_only_;
  std::string default_process_;
};

bool CollectProguardRulesForManifest(const Source& source,
                                     xml::XmlResource* res, KeepSet* keep_set,
                                     bool main_dex_only) {
  ManifestVisitor visitor(source, keep_set, main_dex_only);
  if (res->root) {
    res->root->Accept(&visitor);
    return true;
  }
  return false;
}

bool CollectProguardRules(const Source& source, xml::XmlResource* res,
                          KeepSet* keep_set) {
  if (!res->root) {
    return false;
  }

  switch (res->file.name.type) {
    case ResourceType::kLayout: {
      LayoutVisitor visitor(source, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kXml: {
      XmlResourceVisitor visitor(source, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kTransition: {
      TransitionVisitor visitor(source, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kMenu: {
      MenuVisitor visitor(source, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    default:
      break;
  }
  return true;
}

bool WriteKeepSet(std::ostream* out, const KeepSet& keep_set) {
  for (const auto& entry : keep_set.keep_set_) {
    for (const Source& source : entry.second) {
      *out << "# Referenced at " << source << "\n";
    }
    *out << "-keep class " << entry.first << " { <init>(...); }\n" << std::endl;
  }

  for (const auto& entry : keep_set.keep_method_set_) {
    for (const Source& source : entry.second) {
      *out << "# Referenced at " << source << "\n";
    }
    *out << "-keepclassmembers class * { *** " << entry.first << "(...); }\n"
         << std::endl;
  }
  return true;
}

}  // namespace proguard
}  // namespace aapt
