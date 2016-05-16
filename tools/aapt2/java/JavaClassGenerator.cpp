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

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"

#include "java/AnnotationProcessor.h"
#include "java/ClassDefinition.h"
#include "java/JavaClassGenerator.h"
#include "process/SymbolTable.h"
#include "util/StringPiece.h"

#include <algorithm>
#include <ostream>
#include <set>
#include <sstream>
#include <tuple>

namespace aapt {

JavaClassGenerator::JavaClassGenerator(IAaptContext* context, ResourceTable* table,
                                       const JavaClassGeneratorOptions& options) :
        mContext(context), mTable(table), mOptions(options) {
}

static const std::set<StringPiece16> sJavaIdentifiers = {
    u"abstract", u"assert", u"boolean", u"break", u"byte",
    u"case", u"catch", u"char", u"class", u"const", u"continue",
    u"default", u"do", u"double", u"else", u"enum", u"extends",
    u"final", u"finally", u"float", u"for", u"goto", u"if",
    u"implements", u"import", u"instanceof", u"int", u"interface",
    u"long", u"native", u"new", u"package", u"private", u"protected",
    u"public", u"return", u"short", u"static", u"strictfp", u"super",
    u"switch", u"synchronized", u"this", u"throw", u"throws",
    u"transient", u"try", u"void", u"volatile", u"while", u"true",
    u"false", u"null"
};

static bool isValidSymbol(const StringPiece16& symbol) {
    return sJavaIdentifiers.find(symbol) == sJavaIdentifiers.end();
}

/*
 * Java symbols can not contain . or -, but those are valid in a resource name.
 * Replace those with '_'.
 */
static std::string transform(const StringPiece16& symbol) {
    std::string output = util::utf16ToUtf8(symbol);
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
static std::string transformNestedAttr(const ResourceNameRef& attrName,
                                       const std::string& styleableClassName,
                                       const StringPiece16& packageNameToGenerate) {
    std::string output = styleableClassName;

    // We may reference IDs from other packages, so prefix the entry name with
    // the package.
    if (!attrName.package.empty() && packageNameToGenerate != attrName.package) {
        output += "_" + transform(attrName.package);
    }
    output += "_" + transform(attrName.entry);
    return output;
}

static void addAttributeFormatDoc(AnnotationProcessor* processor, Attribute* attr) {
    const uint32_t typeMask = attr->typeMask;
    if (typeMask & android::ResTable_map::TYPE_REFERENCE) {
        processor->appendComment(
                "<p>May be a reference to another resource, in the form\n"
                "\"<code>@[+][<i>package</i>:]<i>type</i>/<i>name</i></code>\" or a theme\n"
                "attribute in the form\n"
                "\"<code>?[<i>package</i>:]<i>type</i>/<i>name</i></code>\".");
    }

    if (typeMask & android::ResTable_map::TYPE_STRING) {
        processor->appendComment(
                "<p>May be a string value, using '\\\\;' to escape characters such as\n"
                "'\\\\n' or '\\\\uxxxx' for a unicode character;");
    }

    if (typeMask & android::ResTable_map::TYPE_INTEGER) {
        processor->appendComment("<p>May be an integer value, such as \"<code>100</code>\".");
    }

    if (typeMask & android::ResTable_map::TYPE_BOOLEAN) {
        processor->appendComment(
                "<p>May be a boolean value, such as \"<code>true</code>\" or\n"
                "\"<code>false</code>\".");
    }

    if (typeMask & android::ResTable_map::TYPE_COLOR) {
        processor->appendComment(
                "<p>May be a color value, in the form of \"<code>#<i>rgb</i></code>\",\n"
                "\"<code>#<i>argb</i></code>\", \"<code>#<i>rrggbb</i></code\", or \n"
                "\"<code>#<i>aarrggbb</i></code>\".");
    }

    if (typeMask & android::ResTable_map::TYPE_FLOAT) {
        processor->appendComment(
                "<p>May be a floating point value, such as \"<code>1.2</code>\".");
    }

    if (typeMask & android::ResTable_map::TYPE_DIMENSION) {
        processor->appendComment(
                "<p>May be a dimension value, which is a floating point number appended with a\n"
                "unit such as \"<code>14.5sp</code>\".\n"
                "Available units are: px (pixels), dp (density-independent pixels),\n"
                "sp (scaled pixels based on preferred font size), in (inches), and\n"
                "mm (millimeters).");
    }

    if (typeMask & android::ResTable_map::TYPE_FRACTION) {
        processor->appendComment(
                "<p>May be a fractional value, which is a floating point number appended with\n"
                "either % or %p, such as \"<code>14.5%</code>\".\n"
                "The % suffix always means a percentage of the base size;\n"
                "the optional %p suffix provides a size relative to some parent container.");
    }

    if (typeMask & (android::ResTable_map::TYPE_FLAGS | android::ResTable_map::TYPE_ENUM)) {
        if (typeMask & android::ResTable_map::TYPE_FLAGS) {
            processor->appendComment(
                    "<p>Must be one or more (separated by '|') of the following "
                    "constant values.</p>");
        } else {
            processor->appendComment("<p>Must be one of the following constant values.</p>");
        }

        processor->appendComment("<table>\n<colgroup align=\"left\" />\n"
                                 "<colgroup align=\"left\" />\n"
                                 "<colgroup align=\"left\" />\n"
                                 "<tr><th>Constant</th><th>Value</th><th>Description</th></tr>\n");
        for (const Attribute::Symbol& symbol : attr->symbols) {
            std::stringstream line;
            line << "<tr><td>" << symbol.symbol.name.value().entry << "</td>"
            << "<td>" << std::hex << symbol.value << std::dec << "</td>"
            << "<td>" << util::trimWhitespace(symbol.symbol.getComment()) << "</td></tr>";
            processor->appendComment(line.str());
        }
        processor->appendComment("</table>");
    }
}

bool JavaClassGenerator::skipSymbol(SymbolState state) {
    switch (mOptions.types) {
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
    const Reference* attrRef;
    std::string fieldName;
    std::unique_ptr<SymbolTable::Symbol> symbol;
};

static bool lessStyleableAttr(const StyleableAttr& lhs, const StyleableAttr& rhs) {
    const ResourceId lhsId = lhs.attrRef->id ? lhs.attrRef->id.value() : ResourceId(0);
    const ResourceId rhsId = rhs.attrRef->id ? rhs.attrRef->id.value() : ResourceId(0);
    if (lhsId < rhsId) {
        return true;
    } else if (lhsId > rhsId) {
        return false;
    } else {
        return lhs.attrRef->name.value() < rhs.attrRef->name.value();
    }
}

void JavaClassGenerator::addMembersToStyleableClass(const StringPiece16& packageNameToGenerate,
                                                    const std::u16string& entryName,
                                                    const Styleable* styleable,
                                                    ClassDefinition* outStyleableClassDef) {
    const std::string className = transform(entryName);

    std::unique_ptr<ResourceArrayMember> styleableArrayDef =
            util::make_unique<ResourceArrayMember>(className);

    // This must be sorted by resource ID.
    std::vector<StyleableAttr> sortedAttributes;
    sortedAttributes.reserve(styleable->entries.size());
    for (const auto& attr : styleable->entries) {
        // If we are not encoding final attributes, the styleable entry may have no ID
        // if we are building a static library.
        assert((!mOptions.useFinal || attr.id) && "no ID set for Styleable entry");
        assert(attr.name && "no name set for Styleable entry");

        // We will need the unmangled, transformed name in the comments and the field,
        // so create it once and cache it in this StyleableAttr data structure.
        StyleableAttr styleableAttr = {};
        styleableAttr.attrRef = &attr;
        styleableAttr.fieldName = transformNestedAttr(attr.name.value(), className,
                                                      packageNameToGenerate);

        Reference mangledReference;
        mangledReference.id = attr.id;
        mangledReference.name = attr.name;
        if (mangledReference.name.value().package.empty()) {
            mangledReference.name.value().package = mContext->getCompilationPackage();
        }

        if (Maybe<ResourceName> mangledName =
                mContext->getNameMangler()->mangleName(mangledReference.name.value())) {
            mangledReference.name = mangledName;
        }

        // Look up the symbol so that we can write out in the comments what are possible
        // legal values for this attribute.
        const SymbolTable::Symbol* symbol = mContext->getExternalSymbols()->findByReference(
                mangledReference);
        if (symbol && symbol->attribute) {
            // Copy the symbol data structure because the returned instance can be destroyed.
            styleableAttr.symbol = util::make_unique<SymbolTable::Symbol>(*symbol);
        }
        sortedAttributes.push_back(std::move(styleableAttr));
    }

    // Sort the attributes by ID.
    std::sort(sortedAttributes.begin(), sortedAttributes.end(), lessStyleableAttr);

    const size_t attrCount = sortedAttributes.size();
    if (attrCount > 0) {
        // Build the comment string for the Styleable. It includes details about the
        // child attributes.
        std::stringstream styleableComment;
        if (!styleable->getComment().empty()) {
            styleableComment << styleable->getComment() << "\n";
        } else {
            styleableComment << "Attributes that can be used with a " << className << ".\n";
        }

        styleableComment <<
                "<p>Includes the following attributes:</p>\n"
                "<table>\n"
                "<colgroup align=\"left\" />\n"
                "<colgroup align=\"left\" />\n"
                "<tr><th>Attribute</th><th>Description</th></tr>\n";

        for (const StyleableAttr& entry : sortedAttributes) {
            if (!entry.symbol) {
                continue;
            }

            if (mOptions.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
                    !entry.symbol->isPublic) {
                // Don't write entries for non-public attributes.
                continue;
            }

            StringPiece16 attrCommentLine = entry.symbol->attribute->getComment();
            if (attrCommentLine.contains(StringPiece16(u"@removed"))) {
                // Removed attributes are public but hidden from the documentation, so don't emit
                // them as part of the class documentation.
                continue;
            }

            const ResourceName& attrName = entry.attrRef->name.value();
            styleableComment << "<tr><td>";
            styleableComment << "<code>{@link #"
                             << entry.fieldName << " "
                             << (!attrName.package.empty()
                                    ? attrName.package : mContext->getCompilationPackage())
                             << ":" << attrName.entry
                             << "}</code>";
            styleableComment << "</td>";

            styleableComment << "<td>";

            // Only use the comment up until the first '.'. This is to stay compatible with
            // the way old AAPT did it (presumably to keep it short and to avoid including
            // annotations like @hide which would affect this Styleable).
            auto iter = std::find(attrCommentLine.begin(), attrCommentLine.end(), u'.');
            if (iter != attrCommentLine.end()) {
                attrCommentLine = attrCommentLine.substr(
                        0, (iter - attrCommentLine.begin()) + 1);
            }
            styleableComment << attrCommentLine << "</td></tr>\n";
        }
        styleableComment << "</table>\n";

        for (const StyleableAttr& entry : sortedAttributes) {
            if (!entry.symbol) {
                continue;
            }

            if (mOptions.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
                    !entry.symbol->isPublic) {
                // Don't write entries for non-public attributes.
                continue;
            }
            styleableComment << "@see #" << entry.fieldName << "\n";
        }

        styleableArrayDef->getCommentBuilder()->appendComment(styleableComment.str());
    }

    // Add the ResourceIds to the array member.
    for (const StyleableAttr& styleableAttr : sortedAttributes) {
        styleableArrayDef->addElement(
                styleableAttr.attrRef->id ? styleableAttr.attrRef->id.value() : ResourceId(0));
    }

    // Add the Styleable array to the Styleable class.
    outStyleableClassDef->addMember(std::move(styleableArrayDef));

    // Now we emit the indices into the array.
    for (size_t i = 0; i < attrCount; i++) {
        const StyleableAttr& styleableAttr = sortedAttributes[i];

        if (!styleableAttr.symbol) {
            continue;
        }

        if (mOptions.types == JavaClassGeneratorOptions::SymbolTypes::kPublic &&
                !styleableAttr.symbol->isPublic) {
            // Don't write entries for non-public attributes.
            continue;
        }

        StringPiece16 comment = styleableAttr.attrRef->getComment();
        if (styleableAttr.symbol->attribute && comment.empty()) {
            comment = styleableAttr.symbol->attribute->getComment();
        }

        if (comment.contains(StringPiece16(u"@removed"))) {
            // Removed attributes are public but hidden from the documentation, so don't emit them
            // as part of the class documentation.
            continue;
        }

        const ResourceName& attrName = styleableAttr.attrRef->name.value();

        StringPiece16 packageName = attrName.package;
        if (packageName.empty()) {
            packageName = mContext->getCompilationPackage();
        }

        std::unique_ptr<IntMember> indexMember = util::make_unique<IntMember>(
                sortedAttributes[i].fieldName, static_cast<uint32_t>(i));

        AnnotationProcessor* attrProcessor = indexMember->getCommentBuilder();

        if (!comment.empty()) {
            attrProcessor->appendComment("<p>\n@attr description");
            attrProcessor->appendComment(comment);
        } else {
            std::stringstream defaultComment;
            defaultComment
                    << "<p>This symbol is the offset where the "
                    << "{@link " << packageName << ".R.attr#" << transform(attrName.entry) << "}\n"
                    << "attribute's value can be found in the "
                    << "{@link #" << className << "} array.";
            attrProcessor->appendComment(defaultComment.str());
        }

        attrProcessor->appendNewLine();

        addAttributeFormatDoc(attrProcessor, styleableAttr.symbol->attribute.get());
        attrProcessor->appendNewLine();

        std::stringstream doclavaName;
        doclavaName << "@attr name " << packageName << ":" << attrName.entry;;
        attrProcessor->appendComment(doclavaName.str());

        outStyleableClassDef->addMember(std::move(indexMember));
    }
}

bool JavaClassGenerator::addMembersToTypeClass(const StringPiece16& packageNameToGenerate,
                                               const ResourceTablePackage* package,
                                               const ResourceTableType* type,
                                               ClassDefinition* outTypeClassDef) {

    for (const auto& entry : type->entries) {
        if (skipSymbol(entry->symbolStatus.state)) {
            continue;
        }

        ResourceId id;
        if (package->id && type->id && entry->id) {
            id = ResourceId(package->id.value(), type->id.value(), entry->id.value());
        }

        std::u16string unmangledPackage;
        std::u16string unmangledName = entry->name;
        if (NameMangler::unmangle(&unmangledName, &unmangledPackage)) {
            // The entry name was mangled, and we successfully unmangled it.
            // Check that we want to emit this symbol.
            if (package->name != unmangledPackage) {
                // Skip the entry if it doesn't belong to the package we're writing.
                continue;
            }
        } else if (packageNameToGenerate != package->name) {
            // We are processing a mangled package name,
            // but this is a non-mangled resource.
            continue;
        }

        if (!isValidSymbol(unmangledName)) {
            ResourceNameRef resourceName(packageNameToGenerate, type->type, unmangledName);
            std::stringstream err;
            err << "invalid symbol name '" << resourceName << "'";
            mError = err.str();
            return false;
        }

        if (type->type == ResourceType::kStyleable) {
            assert(!entry->values.empty());

            const Styleable* styleable = static_cast<const Styleable*>(
                    entry->values.front()->value.get());

            // Comments are handled within this method.
            addMembersToStyleableClass(packageNameToGenerate, unmangledName, styleable,
                                       outTypeClassDef);
        } else {
            std::unique_ptr<ResourceMember> resourceMember =
                    util::make_unique<ResourceMember>(transform(unmangledName), id);

            // Build the comments and annotations for this entry.
            AnnotationProcessor* processor = resourceMember->getCommentBuilder();

            // Add the comments from any <public> tags.
            if (entry->symbolStatus.state != SymbolState::kUndefined) {
                processor->appendComment(entry->symbolStatus.comment);
            }

            // Add the comments from all configurations of this entry.
            for (const auto& configValue : entry->values) {
                processor->appendComment(configValue->value->getComment());
            }

            // If this is an Attribute, append the format Javadoc.
            if (!entry->values.empty()) {
                if (Attribute* attr = valueCast<Attribute>(entry->values.front()->value.get())) {
                    // We list out the available values for the given attribute.
                    addAttributeFormatDoc(processor, attr);
                }
            }

            outTypeClassDef->addMember(std::move(resourceMember));
        }
    }
    return true;
}

bool JavaClassGenerator::generate(const StringPiece16& packageNameToGenerate, std::ostream* out) {
    return generate(packageNameToGenerate, packageNameToGenerate, out);
}

static void appendJavaDocAnnotations(const std::vector<std::string>& annotations,
                                     AnnotationProcessor* processor) {
    for (const std::string& annotation : annotations) {
        std::string properAnnotation = "@";
        properAnnotation += annotation;
        processor->appendComment(properAnnotation);
    }
}

bool JavaClassGenerator::generate(const StringPiece16& packageNameToGenerate,
                                  const StringPiece16& outPackageName, std::ostream* out) {

    ClassDefinition rClass("R", ClassQualifier::None, true);

    for (const auto& package : mTable->packages) {
        for (const auto& type : package->types) {
            if (type->type == ResourceType::kAttrPrivate) {
                continue;
            }

            const bool forceCreationIfEmpty =
                    (mOptions.types == JavaClassGeneratorOptions::SymbolTypes::kPublic);

            std::unique_ptr<ClassDefinition> classDef = util::make_unique<ClassDefinition>(
                    util::utf16ToUtf8(toString(type->type)), ClassQualifier::Static,
                    forceCreationIfEmpty);

            bool result = addMembersToTypeClass(packageNameToGenerate, package.get(), type.get(),
                                                classDef.get());
            if (!result) {
                return false;
            }

            if (type->type == ResourceType::kAttr) {
                // Also include private attributes in this same class.
                ResourceTableType* privType = package->findType(ResourceType::kAttrPrivate);
                if (privType) {
                    result = addMembersToTypeClass(packageNameToGenerate, package.get(), privType,
                                                   classDef.get());
                    if (!result) {
                        return false;
                    }
                }
            }

            if (type->type == ResourceType::kStyleable &&
                    mOptions.types == JavaClassGeneratorOptions::SymbolTypes::kPublic) {
                // When generating a public R class, we don't want Styleable to be part of the API.
                // It is only emitted for documentation purposes.
                classDef->getCommentBuilder()->appendComment("@doconly");
            }

            appendJavaDocAnnotations(mOptions.javadocAnnotations, classDef->getCommentBuilder());

            rClass.addMember(std::move(classDef));
        }
    }

    appendJavaDocAnnotations(mOptions.javadocAnnotations, rClass.getCommentBuilder());

    if (!ClassDefinition::writeJavaFile(&rClass, util::utf16ToUtf8(outPackageName),
                                        mOptions.useFinal, out)) {
        return false;
    }

    out->flush();
    return true;
}

} // namespace aapt
