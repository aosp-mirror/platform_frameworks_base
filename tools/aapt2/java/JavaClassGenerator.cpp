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

#include "android-base/logging.h"

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "process/SymbolTable.h"
#include "util/StringPiece.h"

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

/*
 * Java symbols can not contain . or -, but those are valid in a resource name.
 * Replace those with '_'.
 */
static std::string Transform(const StringPiece& symbol) {
  std::string output = symbol.ToString();
  for (char& c : output) {
    if (c == '.' || c == '-') {
      c = '_';
    }
  }
  return output;
}

/**
 * Transforms an attribute in a styleable to the Java field name:
 *
 * <declare-styleable name="Foo">
 *   <attr name="android:bar" />
 *   <attr name="bar" />
 * </declare-styleable>
 *
 * Foo_android_bar
 * Foo_bar
 */
static std::string TransformNestedAttr(
    const ResourceNameRef& attr_name, const std::string& styleable_class_name,
    const StringPiece& package_name_to_generate) {
  std::string output = styleable_class_name;

  // We may reference IDs from other packages, so prefix the entry name with
  // the package.
  if (!attr_name.package.empty() &&
      package_name_to_generate != attr_name.package) {
    output += "_" + Transform(attr_name.package);
  }
  output += "_" + Transform(attr_name.entry);
  return output;
}

static void AddAttributeFormatDoc(AnnotationProcessor* processor,
                                  Attribute* attr) {
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
        "\"<code>#<i>argb</i></code>\", \"<code>#<i>rrggbb</i></code\", or \n"
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

bool JavaClassGenerator::SkipSymbol(SymbolState state) {
  switch (options_.types) {
    case JavaClassGeneratorOptions::SymbolTypes::kAll:
      return false;
    case JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate:
      return state == SymbolState::kUndefined;
    case JavaClassGeneratorOptions::SymbolTypes::kPublic:
      return state != SymbolState::kPublic;
  }
  return true;
}

struct StyleableAttr {
  const Reference* attr_ref;
  std::string field_name;
  std::unique_ptr<SymbolTable::Symbol> symbol;
};

static bool less_styleable_attr(const StyleableAttr& lhs,
                                const StyleableAttr& rhs) {
  const ResourceId lhs_id =
      lhs.attr_ref->id ? lhs.attr_ref->id.value() : ResourceId(0);
  const ResourceId rhs_id =
      rhs.attr_ref->id ? rhs.attr_ref->id.value() : ResourceId(0);
  if (lhs_id < rhs_id) {
    return true;
  } else if (lhs_id > rhs_id) {
    return false;
  } else {
    return lhs.attr_ref->name.value() < rhs.attr_ref->name.value();
  }
}

void JavaClassGenerator::AddMembersToStyleableClass(
    const StringPiece& package_name_to_generate, const std::string& entry_name,
    const Styleable* styleable, ClassDefinition* out_styleable_class_def) {
  const std::string class_name = Transform(entry_name);

  std::unique_ptr<ResourceArrayMember> styleable_array_def =
      util::make_unique<ResourceArrayMember>(class_name);

  // This must be sorted by resource ID.
  std::vector<StyleableAttr> sorted_attributes;
  sorted_attributes.reserve(styleable->entries.size());
  for (const auto& attr : styleable->entries) {
    // If we are not encoding final attributes, the styleable entry may have no
    // ID if we are building a static library.
    CHECK(!options_.use_final || attr.id) << "no ID set for Styleable entry";
    CHECK(bool(attr.name)) << "no name set for Styleable entry";

    // We will need the unmangled, transformed name in the comments and the
    // field,
    // so create it once and cache it in this StyleableAttr data structure.
    StyleableAttr styleable_attr = {};
    styleable_attr.attr_ref = &attr;
    styleable_attr.field_name = TransformNestedAttr(
        attr.name.value(), class_name, package_name_to_generate);

    Reference mangled_reference;
    mangled_reference.id = attr.id;
    mangled_reference.name = attr.name;
    if (mangled_reference.name.value().package.empty()) {
      mangled_reference.name.value().package =
          context_->GetCompilationPackage();
    }

    if (Maybe<ResourceName> mangled_name =
            context_->GetNameMangler()->MangleName(
                mangled_reference.name.value())) {
      mangled_reference.name = mangled_name;
    }

    // Look up the symbol so that we can write out in the comments what are
    // possible
    // legal values for this attribute.
    const SymbolTable::Symbol* symbol =
        context_->GetExternalSymbols()->FindByReference(mangled_reference);
    if (symbol && symbol->attribute) {
      // Copy the symbol data structure because the returned instance can be
      // destroyed.
      styleable_attr.symbol = util::make_unique<SymbolTable::Symbol>(*symbol);
    }
    sorted_attributes.push_back(std::move(styleable_attr));
  }

  // Sort the attributes by ID.
  std::sort(sorted_attributes.begin(), sorted_attributes.end(),
            less_styleable_attr);

  const size_t attr_count = sorted_attributes.size();
  if (attr_count > 0) {
    // Build the comment string for the Styleable. It includes details about the
    // child attributes.
    std::stringstream styleable_comment;
    if (!styleable->GetComment().empty()) {
      styleable_comment << styleable->GetComment() << "\n";
    } else {
      styleable_comment << "Attributes that can be used with a " << class_name
                        << ".\n";
    }

    styleable_comment << "<p>Includes the following attributes:</p>\n"
                         "<table>\n"
                         "<colgroup align=\"left\" />\n"
                         "<colgroup align=\"left\" />\n"
                         "<tr><th>Attribute</th><th>Description</th></tr>\n";

    for (const StyleableAttr& entry : sorted_attributes) {
      if (!entry.symbol) {
        continue;
      }

      if (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
          !entry.symbol->is_public) {
        // Don't write entries for non-public attributes.
        continue;
      }

      StringPiece attr_comment_line = entry.symbol->attribute->GetComment();
      if (attr_comment_line.contains("@removed")) {
        // Removed attributes are public but hidden from the documentation, so
        // don't emit
        // them as part of the class documentation.
        continue;
      }

      const ResourceName& attr_name = entry.attr_ref->name.value();
      styleable_comment << "<tr><td>";
      styleable_comment << "<code>{@link #" << entry.field_name << " "
                        << (!attr_name.package.empty()
                                ? attr_name.package
                                : context_->GetCompilationPackage())
                        << ":" << attr_name.entry << "}</code>";
      styleable_comment << "</td>";

      styleable_comment << "<td>";

      // Only use the comment up until the first '.'. This is to stay compatible
      // with
      // the way old AAPT did it (presumably to keep it short and to avoid
      // including
      // annotations like @hide which would affect this Styleable).
      auto iter =
          std::find(attr_comment_line.begin(), attr_comment_line.end(), u'.');
      if (iter != attr_comment_line.end()) {
        attr_comment_line =
            attr_comment_line.substr(0, (iter - attr_comment_line.begin()) + 1);
      }
      styleable_comment << attr_comment_line << "</td></tr>\n";
    }
    styleable_comment << "</table>\n";

    for (const StyleableAttr& entry : sorted_attributes) {
      if (!entry.symbol) {
        continue;
      }

      if (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
          !entry.symbol->is_public) {
        // Don't write entries for non-public attributes.
        continue;
      }
      styleable_comment << "@see #" << entry.field_name << "\n";
    }

    styleable_array_def->GetCommentBuilder()->AppendComment(
        styleable_comment.str());
  }

  // Add the ResourceIds to the array member.
  for (const StyleableAttr& styleable_attr : sorted_attributes) {
    styleable_array_def->AddElement(styleable_attr.attr_ref->id
                                        ? styleable_attr.attr_ref->id.value()
                                        : ResourceId(0));
  }

  // Add the Styleable array to the Styleable class.
  out_styleable_class_def->AddMember(std::move(styleable_array_def));

  // Now we emit the indices into the array.
  for (size_t i = 0; i < attr_count; i++) {
    const StyleableAttr& styleable_attr = sorted_attributes[i];

    if (!styleable_attr.symbol) {
      continue;
    }

    if (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
        !styleable_attr.symbol->is_public) {
      // Don't write entries for non-public attributes.
      continue;
    }

    StringPiece comment = styleable_attr.attr_ref->GetComment();
    if (styleable_attr.symbol->attribute && comment.empty()) {
      comment = styleable_attr.symbol->attribute->GetComment();
    }

    if (comment.contains("@removed")) {
      // Removed attributes are public but hidden from the documentation, so
      // don't emit them
      // as part of the class documentation.
      continue;
    }

    const ResourceName& attr_name = styleable_attr.attr_ref->name.value();

    StringPiece package_name = attr_name.package;
    if (package_name.empty()) {
      package_name = context_->GetCompilationPackage();
    }

    std::unique_ptr<IntMember> index_member = util::make_unique<IntMember>(
        sorted_attributes[i].field_name, static_cast<uint32_t>(i));

    AnnotationProcessor* attr_processor = index_member->GetCommentBuilder();

    if (!comment.empty()) {
      attr_processor->AppendComment("<p>\n@attr description");
      attr_processor->AppendComment(comment);
    } else {
      std::stringstream default_comment;
      default_comment << "<p>This symbol is the offset where the "
                      << "{@link " << package_name << ".R.attr#"
                      << Transform(attr_name.entry) << "}\n"
                      << "attribute's value can be found in the "
                      << "{@link #" << class_name << "} array.";
      attr_processor->AppendComment(default_comment.str());
    }

    attr_processor->AppendNewLine();

    AddAttributeFormatDoc(attr_processor,
                          styleable_attr.symbol->attribute.get());
    attr_processor->AppendNewLine();

    std::stringstream doclava_name;
    doclava_name << "@attr name " << package_name << ":" << attr_name.entry;

    attr_processor->AppendComment(doclava_name.str());

    out_styleable_class_def->AddMember(std::move(index_member));
  }
}

bool JavaClassGenerator::AddMembersToTypeClass(
    const StringPiece& package_name_to_generate,
    const ResourceTablePackage* package, const ResourceTableType* type,
    ClassDefinition* out_type_class_def) {
  for (const auto& entry : type->entries) {
    if (SkipSymbol(entry->symbol_status.state)) {
      continue;
    }

    ResourceId id;
    if (package->id && type->id && entry->id) {
      id = ResourceId(package->id.value(), type->id.value(), entry->id.value());
    }

    std::string unmangled_package;
    std::string unmangled_name = entry->name;
    if (NameMangler::Unmangle(&unmangled_name, &unmangled_package)) {
      // The entry name was mangled, and we successfully unmangled it.
      // Check that we want to emit this symbol.
      if (package->name != unmangled_package) {
        // Skip the entry if it doesn't belong to the package we're writing.
        continue;
      }
    } else if (package_name_to_generate != package->name) {
      // We are processing a mangled package name,
      // but this is a non-mangled resource.
      continue;
    }

    if (!IsValidSymbol(unmangled_name)) {
      ResourceNameRef resource_name(package_name_to_generate, type->type,
                                    unmangled_name);
      std::stringstream err;
      err << "invalid symbol name '" << resource_name << "'";
      error_ = err.str();
      return false;
    }

    if (type->type == ResourceType::kStyleable) {
      CHECK(!entry->values.empty());

      const Styleable* styleable =
          static_cast<const Styleable*>(entry->values.front()->value.get());

      // Comments are handled within this method.
      AddMembersToStyleableClass(package_name_to_generate, unmangled_name,
                                 styleable, out_type_class_def);
    } else {
      std::unique_ptr<ResourceMember> resource_member =
          util::make_unique<ResourceMember>(Transform(unmangled_name), id);

      // Build the comments and annotations for this entry.
      AnnotationProcessor* processor = resource_member->GetCommentBuilder();

      // Add the comments from any <public> tags.
      if (entry->symbol_status.state != SymbolState::kUndefined) {
        processor->AppendComment(entry->symbol_status.comment);
      }

      // Add the comments from all configurations of this entry.
      for (const auto& config_value : entry->values) {
        processor->AppendComment(config_value->value->GetComment());
      }

      // If this is an Attribute, append the format Javadoc.
      if (!entry->values.empty()) {
        if (Attribute* attr =
                ValueCast<Attribute>(entry->values.front()->value.get())) {
          // We list out the available values for the given attribute.
          AddAttributeFormatDoc(processor, attr);
        }
      }

      out_type_class_def->AddMember(std::move(resource_member));
    }
  }
  return true;
}

bool JavaClassGenerator::Generate(const StringPiece& package_name_to_generate,
                                  std::ostream* out) {
  return Generate(package_name_to_generate, package_name_to_generate, out);
}

static void AppendJavaDocAnnotations(
    const std::vector<std::string>& annotations,
    AnnotationProcessor* processor) {
  for (const std::string& annotation : annotations) {
    std::string proper_annotation = "@";
    proper_annotation += annotation;
    processor->AppendComment(proper_annotation);
  }
}

bool JavaClassGenerator::Generate(const StringPiece& package_name_to_generate,
                                  const StringPiece& out_package_name,
                                  std::ostream* out) {
  ClassDefinition r_class("R", ClassQualifier::None, true);

  for (const auto& package : table_->packages) {
    for (const auto& type : package->types) {
      if (type->type == ResourceType::kAttrPrivate) {
        continue;
      }

      const bool force_creation_if_empty =
          (options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic);

      std::unique_ptr<ClassDefinition> class_def =
          util::make_unique<ClassDefinition>(ToString(type->type),
                                             ClassQualifier::Static,
                                             force_creation_if_empty);

      bool result = AddMembersToTypeClass(
          package_name_to_generate, package.get(), type.get(), class_def.get());
      if (!result) {
        return false;
      }

      if (type->type == ResourceType::kAttr) {
        // Also include private attributes in this same class.
        ResourceTableType* priv_type =
            package->FindType(ResourceType::kAttrPrivate);
        if (priv_type) {
          result =
              AddMembersToTypeClass(package_name_to_generate, package.get(),
                                    priv_type, class_def.get());
          if (!result) {
            return false;
          }
        }
      }

      if (type->type == ResourceType::kStyleable &&
          options_.types == JavaClassGeneratorOptions::SymbolTypes::kPublic) {
        // When generating a public R class, we don't want Styleable to be part
        // of the API.
        // It is only emitted for documentation purposes.
        class_def->GetCommentBuilder()->AppendComment("@doconly");
      }

      AppendJavaDocAnnotations(options_.javadoc_annotations,
                               class_def->GetCommentBuilder());

      r_class.AddMember(std::move(class_def));
    }
  }

  AppendJavaDocAnnotations(options_.javadoc_annotations,
                           r_class.GetCommentBuilder());

  if (!ClassDefinition::WriteJavaFile(&r_class, out_package_name,
                                      options_.use_final, out)) {
    return false;
  }

  out->flush();
  return true;
}

}  // namespace aapt
