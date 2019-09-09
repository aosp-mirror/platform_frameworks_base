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
#include "androidfw/StringPiece.h"

#include "JavaClassGenerator.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "text/Printer.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

using ::aapt::io::OutputStream;
using ::aapt::text::Printer;

namespace aapt {
namespace proguard {

class BaseVisitor : public xml::Visitor {
 public:
  using xml::Visitor::Visit;

  BaseVisitor(const ResourceFile& file, KeepSet* keep_set) : BaseVisitor(file, keep_set, "...") {
  }

  BaseVisitor(const ResourceFile& file, KeepSet* keep_set, const std::string& ctor_signature)
      : file_(file), keep_set_(keep_set), ctor_signature_(ctor_signature) {
  }

  void Visit(xml::Element* node) override {
    if (!node->namespace_uri.empty()) {
      Maybe<xml::ExtractedPackage> maybe_package =
          xml::ExtractPackageFromNamespace(node->namespace_uri);
      if (maybe_package) {
        // This is a custom view, let's figure out the class name from this.
        std::string package = maybe_package.value().package + "." + node->name;
        if (util::IsJavaClassName(package)) {
          AddClass(node->line_number, package, ctor_signature_);
        }
      }
    } else if (util::IsJavaClassName(node->name)) {
      AddClass(node->line_number, node->name, ctor_signature_);
    }

    for (const auto& child : node->children) {
      child->Accept(this);
    }

    for (const auto& attr : node->attributes) {
      if (attr.compiled_value) {
        auto ref = ValueCast<Reference>(attr.compiled_value.get());
        if (ref) {
          AddReference(node->line_number, ref);
        }
      }
    }
  }

 protected:
  ResourceFile file_;
  KeepSet* keep_set_;
  std::string ctor_signature_;

  virtual void AddClass(size_t line_number, const std::string& class_name,
                        const std::string& ctor_signature) {
    keep_set_->AddConditionalClass({file_.name, file_.source.WithLine(line_number)},
        {class_name, ctor_signature});
  }

  void AddMethod(size_t line_number, const std::string& method_name,
                 const std::string& method_signature) {
    keep_set_->AddMethod({file_.name, file_.source.WithLine(line_number)},
        {method_name, method_signature});
  }

  void AddReference(size_t line_number, Reference* ref) {
    if (ref && ref->name) {
      ResourceName ref_name = ref->name.value();
      if (ref_name.package.empty()) {
        ref_name = ResourceName(file_.name.package, ref_name.type, ref_name.entry);
      }
      keep_set_->AddReference({file_.name, file_.source.WithLine(line_number)}, ref_name);
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(BaseVisitor);

};

class LayoutVisitor : public BaseVisitor {
 public:
  LayoutVisitor(const ResourceFile& file, KeepSet* keep_set)
      : BaseVisitor(file, keep_set, "android.content.Context, android.util.AttributeSet") {
  }

  void Visit(xml::Element* node) override {
    bool is_view = false;
    bool is_fragment = false;
    if (node->namespace_uri.empty()) {
      if (node->name == "view") {
        is_view = true;
      } else if (node->name == "fragment") {
        is_fragment = true;
      }
    } else if (node->namespace_uri == xml::kSchemaAndroid) {
      is_fragment = node->name == "fragment";
    }

    for (const auto& attr : node->attributes) {
      if (attr.namespace_uri.empty() && attr.name == "class") {
        if (util::IsJavaClassName(attr.value)) {
          if (is_view) {
            AddClass(node->line_number, attr.value,
                "android.content.Context, android.util.AttributeSet");
          } else if (is_fragment) {
            AddClass(node->line_number, attr.value, "");
          }
        }
      } else if (attr.namespace_uri == xml::kSchemaAndroid && attr.name == "name") {
        if (is_fragment && util::IsJavaClassName(attr.value)) {
          AddClass(node->line_number, attr.value, "");
        }
      } else if (attr.namespace_uri == xml::kSchemaAndroid && attr.name == "onClick") {
        AddMethod(node->line_number, attr.value, "android.view.View");
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LayoutVisitor);
};

class MenuVisitor : public BaseVisitor {
 public:
  MenuVisitor(const ResourceFile& file, KeepSet* keep_set) : BaseVisitor(file, keep_set) {
  }

  void Visit(xml::Element* node) override {
    if (node->namespace_uri.empty() && node->name == "item") {
      for (const auto& attr : node->attributes) {
        // AppCompat-v7 defines its own versions of Android attributes if
        // they're defined after SDK 7 (the below are from 11 and 14,
        // respectively), so don't bother checking the XML namespace.
        //
        // Given the names of the containing XML files and the attribute
        // names, it's unlikely that keeping these classes would be wrong.
        if ((attr.name == "actionViewClass" || attr.name == "actionProviderClass") &&
            util::IsJavaClassName(attr.value)) {
          AddClass(node->line_number, attr.value, "android.content.Context");
        }

        if (attr.namespace_uri == xml::kSchemaAndroid && attr.name == "onClick") {
          AddMethod(node->line_number, attr.value, "android.view.MenuItem");
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
  XmlResourceVisitor(const ResourceFile& file, KeepSet* keep_set) : BaseVisitor(file, keep_set) {
  }

  void Visit(xml::Element* node) override {
    bool check_fragment = false;
    if (node->namespace_uri.empty()) {
      check_fragment =
          node->name == "PreferenceScreen" || node->name == "header";
    }

    if (check_fragment) {
      xml::Attribute* attr =
          node->FindAttribute(xml::kSchemaAndroid, "fragment");
      if (attr && util::IsJavaClassName(attr->value)) {
        AddClass(node->line_number, attr->value, "");
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlResourceVisitor);
};

class NavigationVisitor : public BaseVisitor {
 public:
  NavigationVisitor(const ResourceFile& file, KeepSet* keep_set, const std::string& package)
      : BaseVisitor(file, keep_set), package_(package) {
  }

  void Visit(xml::Element* node) override {
    const auto& attr = node->FindAttribute(xml::kSchemaAndroid, "name");
    if (attr != nullptr && !attr->value.empty()) {
      std::string name = (attr->value[0] == '.') ? package_ + attr->value : attr->value;
      if (util::IsJavaClassName(name)) {
        AddClass(node->line_number, name, "...");
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(NavigationVisitor);
  const std::string package_;
};

class TransitionVisitor : public BaseVisitor {
 public:
  TransitionVisitor(const ResourceFile& file, KeepSet* keep_set) : BaseVisitor(file, keep_set) {
  }

  void Visit(xml::Element* node) override {
    bool check_class =
        node->namespace_uri.empty() && (node->name == "transition" || node->name == "pathMotion");
    if (check_class) {
      xml::Attribute* attr = node->FindAttribute({}, "class");
      if (attr && util::IsJavaClassName(attr->value)) {
        AddClass(node->line_number, attr->value,
            "android.content.Context, android.util.AttributeSet");
      }
    }

    BaseVisitor::Visit(node);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(TransitionVisitor);
};

class ManifestVisitor : public BaseVisitor {
 public:
  ManifestVisitor(const ResourceFile& file, KeepSet* keep_set, bool main_dex_only)
      : BaseVisitor(file, keep_set), main_dex_only_(main_dex_only) {
  }

  void Visit(xml::Element* node) override {
    if (node->namespace_uri.empty()) {
      bool get_name = false;
      if (node->name == "manifest") {
        xml::Attribute* attr = node->FindAttribute({}, "package");
        if (attr) {
          package_ = attr->value;
        }
      } else if (node->name == "application") {
        get_name = true;
        xml::Attribute* attr = node->FindAttribute(xml::kSchemaAndroid, "backupAgent");
        if (attr) {
          Maybe<std::string> result = util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value(), "");
          }
        }
        attr = node->FindAttribute(xml::kSchemaAndroid, "appComponentFactory");
        if (attr) {
          Maybe<std::string> result = util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value(), "");
          }
        }

        attr = node->FindAttribute(xml::kSchemaAndroid, "zygotePreloadName");
        if (attr) {
          Maybe<std::string> result = util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value(), "");
          }
        }

        if (main_dex_only_) {
          xml::Attribute* default_process = node->FindAttribute(xml::kSchemaAndroid, "process");
          if (default_process) {
            default_process_ = default_process->value;
          }
        }
      } else if (node->name == "activity" || node->name == "service" ||
                 node->name == "receiver" || node->name == "provider") {
        get_name = true;

        if (main_dex_only_) {
          xml::Attribute* component_process = node->FindAttribute(xml::kSchemaAndroid, "process");

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
          Maybe<std::string> result = util::GetFullyQualifiedClassName(package_, attr->value);
          if (result) {
            AddClass(node->line_number, result.value(), "");
          }
        }
      }
    }
    BaseVisitor::Visit(node);
  }

  virtual void AddClass(size_t line_number, const std::string& class_name,
                        const std::string& ctor_signature) override {
    keep_set_->AddManifestClass({file_.name, file_.source.WithLine(line_number)}, class_name);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ManifestVisitor);

  std::string package_;
  const bool main_dex_only_;
  std::string default_process_;
};

bool CollectProguardRulesForManifest(xml::XmlResource* res, KeepSet* keep_set, bool main_dex_only) {
  ManifestVisitor visitor(res->file, keep_set, main_dex_only);
  if (res->root) {
    res->root->Accept(&visitor);
    return true;
  }
  return false;
}

bool CollectProguardRules(IAaptContext* context_, xml::XmlResource* res, KeepSet* keep_set) {
  if (!res->root) {
    return false;
  }

  switch (res->file.name.type) {
    case ResourceType::kLayout: {
      LayoutVisitor visitor(res->file, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kXml: {
      XmlResourceVisitor visitor(res->file, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kNavigation: {
      NavigationVisitor visitor(res->file, keep_set, context_->GetCompilationPackage());
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kTransition: {
      TransitionVisitor visitor(res->file, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    case ResourceType::kMenu: {
      MenuVisitor visitor(res->file, keep_set);
      res->root->Accept(&visitor);
      break;
    }

    default: {
      BaseVisitor visitor(res->file, keep_set);
      res->root->Accept(&visitor);
      break;
    }
  }
  return true;
}

void WriteKeepSet(const KeepSet& keep_set, OutputStream* out, bool minimal_keep) {
  Printer printer(out);
  for (const auto& entry : keep_set.manifest_class_set_) {
    for (const UsageLocation& location : entry.second) {
      printer.Print("# Referenced at ").Println(location.source.to_string());
    }
    printer.Print("-keep class ").Print(entry.first).Println(" { <init>(); }");
  }

  for (const auto& entry : keep_set.conditional_class_set_) {
    std::set<UsageLocation> locations;
    bool can_be_conditional = true;
    for (const UsageLocation& location : entry.second) {
      can_be_conditional &= CollectLocations(location, keep_set, &locations);
    }

    if (keep_set.conditional_keep_rules_ && can_be_conditional) {
      for (const UsageLocation& location : locations) {
        printer.Print("# Referenced at ").Println(location.source.to_string());
        printer.Print("-if class **.R$layout { int ")
            .Print(JavaClassGenerator::TransformToFieldName(location.name.entry))
            .Println("; }");

        printer.Print("-keep class ").Print(entry.first.name).Print(" { <init>(");
        printer.Print((minimal_keep) ? entry.first.signature : "...");
        printer.Println("); }");
      }
    } else {
      for (const UsageLocation& location : entry.second) {
        printer.Print("# Referenced at ").Println(location.source.to_string());
      }

      printer.Print("-keep class ").Print(entry.first.name).Print(" { <init>(");
      printer.Print((minimal_keep) ? entry.first.signature : "...");
      printer.Println("); }");
    }
    printer.Println();
  }

  for (const auto& entry : keep_set.method_set_) {
    for (const UsageLocation& location : entry.second) {
      printer.Print("# Referenced at ").Println(location.source.to_string());
    }
    printer.Print("-keepclassmembers class * { *** ").Print(entry.first.name)
        .Print("(").Print(entry.first.signature).Println("); }");
    printer.Println();
  }
}

bool CollectLocations(const UsageLocation& location, const KeepSet& keep_set,
                      std::set<UsageLocation>* locations) {
  locations->insert(location);

  // TODO: allow for more reference types if we can determine its safe.
  if (location.name.type != ResourceType::kLayout) {
    return false;
  }

  for (const auto& entry : keep_set.reference_set_) {
    if (entry.first == location.name) {
      for (auto& refLocation : entry.second) {
        // Don't get stuck in loops
        if (locations->find(refLocation) != locations->end()) {
          return false;
        }
        if (!CollectLocations(refLocation, keep_set, locations)) {
          return false;
        }
      }
    }
  }

  return true;
}

class ReferenceVisitor : public ValueVisitor {
 public:
  using ValueVisitor::Visit;

  ReferenceVisitor(aapt::IAaptContext* context, ResourceName from, KeepSet* keep_set)
      : context_(context), from_(from), keep_set_(keep_set) {
  }

  void Visit(Reference* reference) override {
    if (reference->name) {
      ResourceName reference_name = reference->name.value();
      if (reference_name.package.empty()) {
        reference_name = ResourceName(context_->GetCompilationPackage(), reference_name.type,
                                      reference_name.entry);
      }
      keep_set_->AddReference({from_, reference->GetSource()}, reference_name);
    }
  }

 private:
  aapt::IAaptContext* context_;
  ResourceName from_;
  KeepSet* keep_set_;
};

bool CollectResourceReferences(aapt::IAaptContext* context, ResourceTable* table,
                               KeepSet* keep_set) {
  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      for (auto& entry : type->entries) {
        for (auto& config_value : entry->values) {
          ResourceName from(pkg->name, type->type, entry->name);
          ReferenceVisitor visitor(context, from, keep_set);
          config_value->value->Accept(&visitor);
        }
      }
    }
  }
  return true;
}

}  // namespace proguard
}  // namespace aapt
