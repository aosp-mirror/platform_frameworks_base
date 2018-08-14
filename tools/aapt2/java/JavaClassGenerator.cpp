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

#include "java/JavaClassGenerator.h"

#include <algorithm>
#include <ostream>
#include <set>
#include <sstream>
#include <tuple>

#include "android-base/errors.h"
#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "process/SymbolTable.h"

using ::aapt::io::OutputStream;
using ::aapt::text::Printer;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

static const std::set<StringPiece> sJavaIdentifiers = {
    "abstract",   "assert",       "boolean",   "break",      "byte",
    "case",       "catch",        "char",      "class",      "const",
    "continue",   "default",      "do",        "double",     "else",
    "enum",       "extends",      "final",     "finally",    "float",
    "for",        "goto",         "if",        "implements", "import",
    "instanceof", "int",          "interface", "long",       "native",
    "new",        "package",      "private",   "protected",  "public",
    "return",     "short",        "static",    "strictfp",   "super",
    "switch",     "synchronized", "this",      "throw",      "throws",
    "transient",  "try",          "void",      "volatile",   "while",
    "true",       "false",        "null"};

static bool IsValidSymbol(const StringPiece& symbol) {
  return sJavaIdentifiers.find(symbol) == sJavaIdentifiers.end();
}

// Java symbols can not contain . or -, but those are valid in a resource name.
// Replace those with '_'.
std::string JavaClassGenerator::TransformToFieldName(const StringPiece& symbol) {
  std::string output = symbol.to_string();
  for (char& c : output) {
    if (c == '.' || c == '-') {
      c = '_';
    }
  }
  return output;
}

// Transforms an attribute in a styleable to the Java field name:
//
// <declare-styleable name="Foo">
//   <attr name="android:bar" />
//   <attr name="bar" />
// </declare-styleable>
//
// Foo_android_bar
// Foo_bar
static std::string TransformNestedAttr(const ResourceNameRef& attr_name,
                                       const std::string& styleable_class_name,
                                       const StringPiece& package_name_to_generate) {
  std::string output = styleable_class_name;

  // We may reference IDs from other packages, so prefix the entry name with
  // the package.
  if (!attr_name.package.empty() &&
      package_name_to_generate != attr_name.package) {
    output += "_" + JavaClassGenerator::TransformToFieldName(attr_name.package);
  }
  output += "_" + JavaClassGenerator::TransformToFieldName(attr_name.entry);
  return output;
}

static void AddAttributeFormatDoc(AnnotationProcessor* processor, Attribute* attr) {
  const uint32_t type_mask = attr->type_mask;
  if (type_mask & android::ResTable_map::TYPE_REFERENCE) {
    processor->AppendComment(
        "<p>May be a reference to another resource, in the form\n"
        "\"<code>@[+][<i>package</i>:]<i>type</i>/<i>name</i></code>\" or a "
        "theme\n"
        "attribute in the form\n"
        "\"<code>?[<i>package</i>:]<i>type</i>/<i>name</i></code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_STRING) {
    processor->AppendComment(
        "<p>May be a string value, using '\\\\;' to escape characters such as\n"
        "'\\\\n' or '\\\\uxxxx' for a unicode character;");
  }

  if (type_mask & android::ResTable_map::TYPE_INTEGER) {
    processor->AppendComment(
        "<p>May be an integer value, such as \"<code>100</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_BOOLEAN) {
    processor->AppendComment(
        "<p>May be a boolean value, such as \"<code>true</code>\" or\n"
        "\"<code>false</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_COLOR) {
    processor->AppendComment(
        "<p>May be a color value, in the form of "
        "\"<code>#<i>rgb</i></code>\",\n"
        "\"<code>#<i>argb</i></code>\", \"<code>#<i>rrggbb</i></code>\", or \n"
        "\"<code>#<i>aarrggbb</i></code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_FLOAT) {
    processor->AppendComment(
        "<p>May be a floating point value, such as \"<code>1.2</code>\".");
  }

  if (type_mask & android::ResTable_map::TYPE_DIMENSION) {
    processor->AppendComment(
        "<p>May be a dimension value, which is a floating point number "
        "appended with a\n"
        "unit such as \"<code>14.5sp</code>\".\n"
        "Available units are: px (pixels), dp (density-independent pixels),\n"
        "sp (scaled pixels based on preferred font size), in (inches), and\n"
        "mm (millimeters).");
  }

  if (type_mask & android::ResTable_map::TYPE_FRACTION) {
    processor->AppendComment(
        "<p>May be a fractional value, which is a floating point number "
        "appended with\n"
        "either % or %p, such as \"<code>14.5%</code>\".\n"
        "The % suffix always means a percentage of the base size;\n"
        "the optional %p suffix provides a size relative to some parent "
        "container.");
  }

  if (type_mask &
      (android::ResTable_map::TYPE_FLAGS | android::ResTable_map::TYPE_ENUM)) {
    if (type_mask & android::ResTable_map::TYPE_FLAGS) {
      processor->AppendComment(
          "<p>Must be one or more (separated by '|') of the following "
          "constant values.</p>");
    } else {
      processor->AppendComment(
          "<p>Must be one of the following constant values.</p>");
    }

    processor->AppendComment(
        "<table>\n<colgroup align=\"left\" />\n"
        "<colgroup align=\"left\" />\n"
        "<colgroup align=\"left\" />\n"
        "<tr><th>Constant</th><th>Value</th><th>Description</th></tr>\n");
    for (const Attribute::Symbol& symbol : attr->symbols) {
      std::stringstream line;
      line << "<tr><td>" << symbol.symbol.name.value().entry << "</td>"
           << "<td>" << std::hex << symbol.value << std::dec << "</td>"
           << "<td>" << util::TrimWhitespace(symbol.symbol.GetComment())
           << "</td></tr>";
      processor->AppendComment(line.str());
    }
    processor->AppendComment("</table>");
  }
}

JavaClassGenerator::JavaClassGenerator(IAaptContext* context,
                                       ResourceTable* table,
                                       const JavaClassGeneratorOptions& options)
    : context_(context), table_(table), options_(options) {}

bool JavaClassGenerator::SkipSymbol(Visibility::Level level) {
  switch (options_.types) {
    case JavaClassGeneratorOptions::SymbolTypes::kAll:
      return false;
    case JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate:
      return level == Visibility::Level::kUndefined;
    case JavaClassGeneratorOptions::SymbolTypes::kPublic:
      return level != Visibility::Level::kPublic;
  }
  return true;
}

// Whether or not to skip writing this symbol.
bool JavaClassGenerator::SkipSymbol(const Maybe<SymbolTable::Symbol>& symbol) {
  return !symbol || (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
                     !symbol.value().is_public);
}

struct StyleableAttr {
  const Reference* attr_ref = nullptr;
  std::string field_name;
  Maybe<SymbolTable::Symbol> symbol;
};

static bool operator<(const StyleableAttr& lhs, const StyleableAttr& rhs) {
  const ResourceId lhs_id = lhs.attr_ref->id.value_or_default(ResourceId(0));
  const ResourceId rhs_id = rhs.attr_ref->id.value_or_default(ResourceId(0));
  if (lhs_id < rhs_id) {
    return true;
  } else if (lhs_id > rhs_id) {
    return false;
  } else {
    return lhs.attr_ref->name.value() < rhs.attr_ref->name.value();
  }
}

void JavaClassGenerator::ProcessStyleable(const ResourceNameRef& name, const ResourceId& id,
                                          const Styleable& styleable,
                                          const StringPiece& package_name_to_generate,
                                          ClassDefinition* out_class_def,
                                          MethodDefinition* out_rewrite_method,
                                          Printer* r_txt_printer) {
  const std::string array_field_name = TransformToFieldName(name.entry);
  std::unique_ptr<ResourceArrayMember> array_def =
      util::make_unique<ResourceArrayMember>(array_field_name);

  // The array must be sorted by resource ID.
  std::vector<StyleableAttr> sorted_attributes;
  sorted_attributes.reserve(styleable.entries.size());
  for (const auto& attr : styleable.entries) {
    // If we are not encoding final attributes, the styleable entry may have no
    // ID if we are building a static library.
    CHECK(!options_.use_final || attr.id) << "no ID set for Styleable entry";
    CHECK(bool(attr.name)) << "no name set for Styleable entry";

    // We will need the unmangled, transformed name in the comments and the field,
    // so create it once and cache it in this StyleableAttr data structure.
    StyleableAttr styleable_attr;
    styleable_attr.attr_ref = &attr;

    // The field name for this attribute is prefixed by the name of this styleable and
    // the package it comes from.
    styleable_attr.field_name =
        TransformNestedAttr(attr.name.value(), array_field_name, package_name_to_generate);

    // Look up the symbol so that we can write out in the comments what are possible legal values
    // for this attribute.
    const SymbolTable::Symbol* symbol = context_->GetExternalSymbols()->FindByReference(attr);
    if (symbol && symbol->attribute) {
      // Copy the symbol data structure because the returned instance can be destroyed.
      styleable_attr.symbol = *symbol;
    }
    sorted_attributes.push_back(std::move(styleable_attr));
  }

  // Sort the attributes by ID.
  std::sort(sorted_attributes.begin(), sorted_attributes.end());

  // Build the JavaDoc comment for the Styleable array. This has references to child attributes
  // and what possible values can be used for them.
  const size_t attr_count = sorted_attributes.size();
  if (out_class_def != nullptr && attr_count > 0) {
    std::stringstream styleable_comment;
    if (!styleable.GetComment().empty()) {
      styleable_comment << styleable.GetComment() << "\n";
    } else {
      // Apply a default intro comment if the styleable has no comments of its own.
      styleable_comment << "Attributes that can be used with a " << array_field_name << ".\n";
    }

    styleable_comment << "<p>Includes the following attributes:</p>\n"
                         "<table>\n"
                         "<colgroup align=\"left\" />\n"
                         "<colgroup align=\"left\" />\n"
                         "<tr><th>Attribute</th><th>Description</th></tr>\n";

    // Build the table of attributes with their links and names.
    for (const StyleableAttr& entry : sorted_attributes) {
      if (SkipSymbol(entry.symbol)) {
        continue;
      }

      StringPiece attr_comment_line = entry.symbol.value().attribute->GetComment();
      if (attr_comment_line.contains("@removed")) {
        // Removed attributes are public but hidden from the documentation, so
        // don't emit them as part of the class documentation.
        continue;
      }

      const ResourceName& attr_name = entry.attr_ref->name.value();
      styleable_comment << "<tr><td><code>{@link #" << entry.field_name << " "
                        << (!attr_name.package.empty() ? attr_name.package
                                                       : context_->GetCompilationPackage())
                        << ":" << attr_name.entry << "}</code></td>";

      // Only use the comment up until the first '.'. This is to stay compatible with
      // the way old AAPT did it (presumably to keep it short and to avoid including
      // annotations like @hide which would affect this Styleable).
      styleable_comment << "<td>" << AnnotationProcessor::ExtractFirstSentence(attr_comment_line)
                        << "</td></tr>\n";
    }
    styleable_comment << "</table>\n";

    // Generate the @see lines for each attribute.
    for (const StyleableAttr& entry : sorted_attributes) {
      if (SkipSymbol(entry.symbol)) {
        continue;
      }
      styleable_comment << "@see #" << entry.field_name << "\n";
    }

    array_def->GetCommentBuilder()->AppendComment(styleable_comment.str());
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Print("int[] styleable ").Print(array_field_name).Print(" {");
  }

  // Add the ResourceIds to the array member.
  for (size_t i = 0; i < attr_count; i++) {
    const ResourceId id = sorted_attributes[i].attr_ref->id.value_or_default(ResourceId(0));
    array_def->AddElement(id);

    if (r_txt_printer != nullptr) {
      if (i != 0) {
        r_txt_printer->Print(",");
      }
      r_txt_printer->Print(" ").Print(id.to_string());
    }
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Println(" }");
  }

  // Add the Styleable array to the Styleable class.
  out_class_def->AddMember(std::move(array_def));

  // Now we emit the indices into the array.
  for (size_t i = 0; i < attr_count; i++) {
    const StyleableAttr& styleable_attr = sorted_attributes[i];
    if (SkipSymbol(styleable_attr.symbol)) {
      continue;
    }

    if (out_class_def != nullptr) {
      StringPiece comment = styleable_attr.attr_ref->GetComment();
      if (styleable_attr.symbol.value().attribute && comment.empty()) {
        comment = styleable_attr.symbol.value().attribute->GetComment();
      }

      if (comment.contains("@removed")) {
        // Removed attributes are public but hidden from the documentation, so
        // don't emit them as part of the class documentation.
        continue;
      }

      const ResourceName& attr_name = styleable_attr.attr_ref->name.value();

      StringPiece package_name = attr_name.package;
      if (package_name.empty()) {
        package_name = context_->GetCompilationPackage();
      }

      std::unique_ptr<IntMember> index_member =
          util::make_unique<IntMember>(sorted_attributes[i].field_name, static_cast<uint32_t>(i));

      AnnotationProcessor* attr_processor = index_member->GetCommentBuilder();

      if (!comment.empty()) {
        attr_processor->AppendComment("<p>\n@attr description");
        attr_processor->AppendComment(comment);
      } else {
        std::stringstream default_comment;
        default_comment << "<p>This symbol is the offset where the "
                        << "{@link " << package_name << ".R.attr#"
                        << TransformToFieldName(attr_name.entry) << "}\n"
                        << "attribute's value can be found in the "
                        << "{@link #" << array_field_name << "} array.";
        attr_processor->AppendComment(default_comment.str());
      }

      attr_processor->AppendNewLine();
      AddAttributeFormatDoc(attr_processor, styleable_attr.symbol.value().attribute.get());
      attr_processor->AppendNewLine();
      attr_processor->AppendComment(
          StringPrintf("@attr name %s:%s", package_name.data(), attr_name.entry.data()));

      out_class_def->AddMember(std::move(index_member));
    }

    if (r_txt_printer != nullptr) {
      r_txt_printer->Println(
          StringPrintf("int styleable %s %zd", sorted_attributes[i].field_name.c_str(), i));
    }
  }

  // If there is a rewrite method to generate, add the statements that rewrite package IDs
  // for this styleable.
  if (out_rewrite_method != nullptr) {
    out_rewrite_method->AppendStatement(
        StringPrintf("for (int i = 0; i < styleable.%s.length; i++) {", array_field_name.data()));
    out_rewrite_method->AppendStatement(
        StringPrintf("  if ((styleable.%s[i] & 0xff000000) == 0) {", array_field_name.data()));
    out_rewrite_method->AppendStatement(
        StringPrintf("    styleable.%s[i] = (styleable.%s[i] & 0x00ffffff) | (p << 24);",
                     array_field_name.data(), array_field_name.data()));
    out_rewrite_method->AppendStatement("  }");
    out_rewrite_method->AppendStatement("}");
  }
}

void JavaClassGenerator::ProcessResource(const ResourceNameRef& name, const ResourceId& id,
                                         const ResourceEntry& entry, ClassDefinition* out_class_def,
                                         MethodDefinition* out_rewrite_method,
                                         text::Printer* r_txt_printer) {
  ResourceId real_id = id;
  if (context_->GetMinSdkVersion() < SDK_O && name.type == ResourceType::kId &&
      id.package_id() > kAppPackageId) {
    // Workaround for feature splits using package IDs > 0x7F.
    // See b/37498913.
    real_id = ResourceId(kAppPackageId, id.package_id(), id.entry_id());
  }

  const std::string field_name = TransformToFieldName(name.entry);
  if (out_class_def != nullptr) {
    std::unique_ptr<ResourceMember> resource_member =
        util::make_unique<ResourceMember>(field_name, real_id);

    // Build the comments and annotations for this entry.
    AnnotationProcessor* processor = resource_member->GetCommentBuilder();

    // Add the comments from any <public> tags.
    if (entry.visibility.level != Visibility::Level::kUndefined) {
      processor->AppendComment(entry.visibility.comment);
    }

    // Add the comments from all configurations of this entry.
    for (const auto& config_value : entry.values) {
      processor->AppendComment(config_value->value->GetComment());
    }

    // If this is an Attribute, append the format Javadoc.
    if (!entry.values.empty()) {
      if (Attribute* attr = ValueCast<Attribute>(entry.values.front()->value.get())) {
        // We list out the available values for the given attribute.
        AddAttributeFormatDoc(processor, attr);
      }
    }

    out_class_def->AddMember(std::move(resource_member));
  }

  if (r_txt_printer != nullptr) {
    r_txt_printer->Print("int ")
        .Print(to_string(name.type))
        .Print(" ")
        .Print(field_name)
        .Print(" ")
        .Println(real_id.to_string());
  }

  if (out_rewrite_method != nullptr) {
    const StringPiece& type_str = to_string(name.type);
    out_rewrite_method->AppendStatement(StringPrintf("%s.%s = (%s.%s & 0x00ffffff) | (p << 24);",
                                                     type_str.data(), field_name.data(),
                                                     type_str.data(), field_name.data()));
  }
}

Maybe<std::string> JavaClassGenerator::UnmangleResource(const StringPiece& package_name,
                                                        const StringPiece& package_name_to_generate,
                                                        const ResourceEntry& entry) {
  if (SkipSymbol(entry.visibility.level)) {
    return {};
  }

  std::string unmangled_package;
  std::string unmangled_name = entry.name;
  if (NameMangler::Unmangle(&unmangled_name, &unmangled_package)) {
    // The entry name was mangled, and we successfully unmangled it.
    // Check that we want to emit this symbol.
    if (package_name_to_generate != unmangled_package) {
      // Skip the entry if it doesn't belong to the package we're writing.
      return {};
    }
  } else if (package_name_to_generate != package_name) {
    // We are processing a mangled package name,
    // but this is a non-mangled resource.
    return {};
  }
  return {std::move(unmangled_name)};
}

bool JavaClassGenerator::ProcessType(const StringPiece& package_name_to_generate,
                                     const ResourceTablePackage& package,
                                     const ResourceTableType& type,
                                     ClassDefinition* out_type_class_def,
                                     MethodDefinition* out_rewrite_method_def,
                                     Printer* r_txt_printer) {
  for (const auto& entry : type.entries) {
    const Maybe<std::string> unmangled_name =
        UnmangleResource(package.name, package_name_to_generate, *entry);
    if (!unmangled_name) {
      continue;
    }

    // Create an ID if there is one (static libraries don't need one).
    ResourceId id;
    if (package.id && type.id && entry->id) {
      id = ResourceId(package.id.value(), type.id.value(), entry->id.value());
    }

    // We need to make sure we hide the fact that we are generating kAttrPrivate attributes.
    const ResourceNameRef resource_name(
        package_name_to_generate,
        type.type == ResourceType::kAttrPrivate ? ResourceType::kAttr : type.type,
        unmangled_name.value());

    // Check to see if the unmangled name is a valid Java name (not a keyword).
    if (!IsValidSymbol(unmangled_name.value())) {
      std::stringstream err;
      err << "invalid symbol name '" << resource_name << "'";
      error_ = err.str();
      return false;
    }

    if (resource_name.type == ResourceType::kStyleable) {
      CHECK(!entry->values.empty());

      const Styleable* styleable =
          static_cast<const Styleable*>(entry->values.front()->value.get());

      ProcessStyleable(resource_name, id, *styleable, package_name_to_generate, out_type_class_def,
                       out_rewrite_method_def, r_txt_printer);
    } else {
      ProcessResource(resource_name, id, *entry, out_type_class_def, out_rewrite_method_def,
                      r_txt_printer);
    }
  }
  return true;
}

bool JavaClassGenerator::Generate(const StringPiece& package_name_to_generate, OutputStream* out,
                                  OutputStream* out_r_txt) {
  return Generate(package_name_to_generate, package_name_to_generate, out, out_r_txt);
}

static void AppendJavaDocAnnotations(const std::vector<std::string>& annotations,
                                     AnnotationProcessor* processor) {
  for (const std::string& annotation : annotations) {
    std::string proper_annotation = "@";
    proper_annotation += annotation;
    processor->AppendComment(proper_annotation);
  }
}

bool JavaClassGenerator::Generate(const StringPiece& package_name_to_generate,
                                  const StringPiece& out_package_name, OutputStream* out,
                                  OutputStream* out_r_txt) {
  ClassDefinition r_class("R", ClassQualifier::kNone, true);
  std::unique_ptr<MethodDefinition> rewrite_method;

  std::unique_ptr<Printer> r_txt_printer;
  if (out_r_txt != nullptr) {
    r_txt_printer = util::make_unique<Printer>(out_r_txt);
  }

  // Generate an onResourcesLoaded() callback if requested.
  if (out != nullptr && options_.rewrite_callback_options) {
    rewrite_method =
        util::make_unique<MethodDefinition>("public static void onResourcesLoaded(int p)");
    for (const std::string& package_to_callback :
         options_.rewrite_callback_options.value().packages_to_callback) {
      rewrite_method->AppendStatement(
          StringPrintf("%s.R.onResourcesLoaded(p);", package_to_callback.data()));
    }
  }

  for (const auto& package : table_->packages) {
    for (const auto& type : package->types) {
      if (type->type == ResourceType::kAttrPrivate) {
        // We generate these as part of the kAttr type, so skip them here.
        continue;
      }

      // Stay consistent with AAPT and generate an empty type class if the R class is public.
      const bool force_creation_if_empty =
          (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic);

      std::unique_ptr<ClassDefinition> class_def;
      if (out != nullptr) {
        class_def = util::make_unique<ClassDefinition>(
            to_string(type->type), ClassQualifier::kStatic, force_creation_if_empty);
      }

      if (!ProcessType(package_name_to_generate, *package, *type, class_def.get(),
                       rewrite_method.get(), r_txt_printer.get())) {
        return false;
      }

      if (type->type == ResourceType::kAttr) {
        // Also include private attributes in this same class.
        const ResourceTableType* priv_type = package->FindType(ResourceType::kAttrPrivate);
        if (priv_type) {
          if (!ProcessType(package_name_to_generate, *package, *priv_type, class_def.get(),
                           rewrite_method.get(), r_txt_printer.get())) {
            return false;
          }
        }
      }

      if (out != nullptr && type->type == ResourceType::kStyleable &&
          options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic) {
        // When generating a public R class, we don't want Styleable to be part
        // of the API. It is only emitted for documentation purposes.
        class_def->GetCommentBuilder()->AppendComment("@doconly");
      }

      if (out != nullptr) {
        AppendJavaDocAnnotations(options_.javadoc_annotations, class_def->GetCommentBuilder());
        r_class.AddMember(std::move(class_def));
      }
    }
  }

  if (rewrite_method != nullptr) {
    r_class.AddMember(std::move(rewrite_method));
  }

  if (out != nullptr) {
    AppendJavaDocAnnotations(options_.javadoc_annotations, r_class.GetCommentBuilder());
    ClassDefinition::WriteJavaFile(&r_class, out_package_name, options_.use_final, out);
  }
  return true;
}

}  // namespace aapt
