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

#include "BigBuffer.h"
#include "Logger.h"
#include "Maybe.h"
#include "Resolver.h"
#include "Resource.h"
#include "ResourceParser.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "Source.h"
#include "StringPool.h"
#include "Util.h"
#include "XmlFlattener.h"

#include <androidfw/ResourceTypes.h>
#include <limits>
#include <map>
#include <string>
#include <vector>

namespace aapt {

constexpr const char16_t* kSchemaAndroid = u"http://schemas.android.com/apk/res/android";

struct AttributeValueFlattener : ValueVisitor {
    AttributeValueFlattener(
            std::shared_ptr<IResolver> resolver, SourceLogger* logger,
            android::Res_value* outValue, std::shared_ptr<XmlPullParser> parser, bool* outError,
            StringPool::Ref rawValue, std::u16string* defaultPackage,
            std::vector<std::pair<StringPool::Ref, android::ResStringPool_ref*>>* outStringRefs) :
            mResolver(resolver), mLogger(logger), mOutValue(outValue), mParser(parser),
            mError(outError), mRawValue(rawValue), mDefaultPackage(defaultPackage),
            mStringRefs(outStringRefs) {
    }

    void visit(Reference& reference, ValueVisitorArgs&) override {
        // First see if we can convert the package name from a prefix to a real
        // package name.
        ResourceName aliasedName = reference.name;

        if (!reference.name.package.empty()) {
            // Only if we specified a package do we look for its alias.
            mParser->applyPackageAlias(&reference.name.package, *mDefaultPackage);
        } else {
            reference.name.package = *mDefaultPackage;
        }

        Maybe<ResourceId> result = mResolver->findId(reference.name);
        if (!result || !result.value().isValid()) {
            std::ostream& out = mLogger->error(mParser->getLineNumber())
                    << "unresolved reference '"
                    << aliasedName
                    << "'";
            if (aliasedName != reference.name) {
                out << " (aka '" << reference.name << "')";
            }
            out << "'." << std::endl;
            *mError = true;
        } else {
            reference.id = result.value();
            reference.flatten(*mOutValue);
        }
    }

    void visit(String& string, ValueVisitorArgs&) override {
        mOutValue->dataType = android::Res_value::TYPE_STRING;
        mStringRefs->emplace_back(
                mRawValue,
                reinterpret_cast<android::ResStringPool_ref*>(mOutValue->data));
    }

    void visitItem(Item& item, ValueVisitorArgs&) override {
        item.flatten(*mOutValue);
    }

private:
    std::shared_ptr<IResolver> mResolver;
    SourceLogger* mLogger;
    android::Res_value* mOutValue;
    std::shared_ptr<XmlPullParser> mParser;
    bool* mError;
    StringPool::Ref mRawValue;
    std::u16string* mDefaultPackage;
    std::vector<std::pair<StringPool::Ref, android::ResStringPool_ref*>>* mStringRefs;
};

struct XmlAttribute {
    uint32_t resourceId;
    const XmlPullParser::Attribute* xmlAttr;
    const Attribute* attr;
    StringPool::Ref nameRef;
};

static bool lessAttributeId(const XmlAttribute& a, uint32_t id) {
    return a.resourceId < id;
}

XmlFlattener::XmlFlattener(const std::shared_ptr<ResourceTable>& table,
                           const std::shared_ptr<IResolver>& resolver) :
        mTable(table), mResolver(resolver) {
}

/**
 * Reads events from the parser and writes to a BigBuffer. The binary XML file
 * expects the StringPool to appear first, but we haven't collected the strings yet. We
 * write to a temporary BigBuffer while parsing the input, adding strings we encounter
 * to the StringPool. At the end, we write the StringPool to the given BigBuffer and
 * then move the data from the temporary BigBuffer into the given one. This incurs no
 * copies as the given BigBuffer simply takes ownership of the data.
 */
Maybe<size_t> XmlFlattener::flatten(const Source& source,
                                    const std::shared_ptr<XmlPullParser>& parser,
                                    BigBuffer* outBuffer, Options options) {
    SourceLogger logger(source);
    StringPool pool;
    bool error = false;

    size_t smallestStrippedAttributeSdk = std::numeric_limits<size_t>::max();

    // Attribute names are stored without packages, but we use
    // their StringPool index to lookup their resource IDs.
    // This will cause collisions, so we can't dedupe
    // attribute names from different packages. We use separate
    // pools that we later combine.
    std::map<std::u16string, StringPool> packagePools;

    // Attribute resource IDs are stored in the same order
    // as the attribute names appear in the StringPool.
    // Since the StringPool contains more than just attribute
    // names, to maintain a tight packing of resource IDs,
    // we must ensure that attribute names appear first
    // in our StringPool. For this, we assign a low priority
    // (0xffffffff) to non-attribute strings. Attribute
    // names will be stored along with a priority equal
    // to their resource ID so that they are ordered.
    StringPool::Context lowPriority { 0xffffffffu };

    // Once we sort the StringPool, we can assign the updated indices
    // to the correct data locations.
    std::vector<std::pair<StringPool::Ref, android::ResStringPool_ref*>> stringRefs;

    // Since we don't know the size of the final StringPool, we write to this
    // temporary BigBuffer, which we will append to outBuffer later.
    BigBuffer out(1024);
    while (XmlPullParser::isGoodEvent(parser->next())) {
        XmlPullParser::Event event = parser->getEvent();
        switch (event) {
            case XmlPullParser::Event::kStartNamespace:
            case XmlPullParser::Event::kEndNamespace: {
                const size_t startIndex = out.size();
                android::ResXMLTree_node* node = out.nextBlock<android::ResXMLTree_node>();
                if (event == XmlPullParser::Event::kStartNamespace) {
                    node->header.type = android::RES_XML_START_NAMESPACE_TYPE;
                } else {
                    node->header.type = android::RES_XML_END_NAMESPACE_TYPE;
                }

                node->header.headerSize = sizeof(*node);
                node->lineNumber = parser->getLineNumber();
                node->comment.index = -1;

                android::ResXMLTree_namespaceExt* ns =
                        out.nextBlock<android::ResXMLTree_namespaceExt>();
                stringRefs.emplace_back(
                        pool.makeRef(parser->getNamespacePrefix(), lowPriority), &ns->prefix);
                stringRefs.emplace_back(
                        pool.makeRef(parser->getNamespaceUri(), lowPriority), &ns->uri);

                out.align4();
                node->header.size = out.size() - startIndex;
                break;
            }

            case XmlPullParser::Event::kStartElement: {
                const size_t startIndex = out.size();
                android::ResXMLTree_node* node = out.nextBlock<android::ResXMLTree_node>();
                node->header.type = android::RES_XML_START_ELEMENT_TYPE;
                node->header.headerSize = sizeof(*node);
                node->lineNumber = parser->getLineNumber();
                node->comment.index = -1;

                android::ResXMLTree_attrExt* elem = out.nextBlock<android::ResXMLTree_attrExt>();
                if (!parser->getElementNamespace().empty()) {
                    stringRefs.emplace_back(
                            pool.makeRef(parser->getElementNamespace(), lowPriority), &elem->ns);
                } else {
                    // The device doesn't think a string of size 0 is the same as null.
                    elem->ns.index = -1;
                }
                stringRefs.emplace_back(
                        pool.makeRef(parser->getElementName(), lowPriority), &elem->name);
                elem->attributeStart = sizeof(*elem);
                elem->attributeSize = sizeof(android::ResXMLTree_attribute);

                // The resource system expects attributes to be sorted by resource ID.
                std::vector<XmlAttribute> sortedAttributes;
                uint32_t nextAttributeId = 0;
                const auto endAttrIter = parser->endAttributes();
                for (auto attrIter = parser->beginAttributes();
                        attrIter != endAttrIter;
                        ++attrIter) {
                    uint32_t id;
                    StringPool::Ref nameRef;
                    const Attribute* attr = nullptr;

                    if (options.maxSdkAttribute && attrIter->namespaceUri == kSchemaAndroid) {
                        size_t sdkVersion = findAttributeSdkLevel(attrIter->name);
                        if (sdkVersion > options.maxSdkAttribute.value()) {
                            // We will silently omit this attribute
                            smallestStrippedAttributeSdk =
                                    std::min(smallestStrippedAttributeSdk, sdkVersion);
                            continue;
                        }
                    }

                    ResourceNameRef genIdName;
                    bool create = false;
                    bool privateRef = false;
                    if (mTable && ResourceParser::tryParseReference(attrIter->value, &genIdName,
                            &create, &privateRef) && create) {
                        mTable->addResource(genIdName, {}, source.line(parser->getLineNumber()),
                                            util::make_unique<Id>());
                    }


                    Maybe<std::u16string> package = util::extractPackageFromNamespace(
                            attrIter->namespaceUri);
                    if (!package || !mResolver) {
                        // Attributes that have no resource ID (because they don't belong to a
                        // package) should appear after those that do have resource IDs. Assign
                        // them some integer value that will appear after.
                        id = 0x80000000u | nextAttributeId++;
                        nameRef = pool.makeRef(attrIter->name, StringPool::Context{ id });

                    } else {
                        // Find the Attribute object via our Resolver.
                        ResourceName attrName = {
                                package.value(), ResourceType::kAttr, attrIter->name };

                        if (attrName.package.empty()) {
                            attrName.package = options.defaultPackage;
                        }

                        Maybe<IResolver::Entry> result = mResolver->findAttribute(attrName);
                        if (!result || !result.value().id.isValid()) {
                            logger.error(parser->getLineNumber())
                                    << "unresolved attribute '"
                                    << attrName
                                    << "'."
                                    << std::endl;
                            error = true;
                            continue;
                        }

                        if (!result.value().attr) {
                            logger.error(parser->getLineNumber())
                                    << "not a valid attribute '"
                                    << attrName
                                    << "'."
                                    << std::endl;
                            error = true;
                            continue;
                        }

                        id = result.value().id.id;
                        attr = result.value().attr;

                        // Put the attribute name into a package specific pool, since we don't
                        // want to collapse names from different packages.
                        nameRef = packagePools[package.value()].makeRef(
                                attrIter->name, StringPool::Context{ id });
                    }

                    // Insert the attribute into the sorted vector.
                    auto iter = std::lower_bound(sortedAttributes.begin(), sortedAttributes.end(),
                                                 id, lessAttributeId);
                    sortedAttributes.insert(iter, XmlAttribute{ id, &*attrIter, attr, nameRef });
                }

                if (error) {
                    break;
                }

                // Now that we have filtered out some attributes, get the final count.
                elem->attributeCount = sortedAttributes.size();

                // Flatten the sorted attributes.
                uint16_t attributeIndex = 1;
                for (auto entry : sortedAttributes) {
                    android::ResXMLTree_attribute* attr =
                            out.nextBlock<android::ResXMLTree_attribute>();
                    if (!entry.xmlAttr->namespaceUri.empty()) {
                        stringRefs.emplace_back(
                                pool.makeRef(entry.xmlAttr->namespaceUri, lowPriority), &attr->ns);
                    } else {
                        attr->ns.index = -1;
                    }

                    StringPool::Ref rawValueRef = pool.makeRef(entry.xmlAttr->value, lowPriority);

                    stringRefs.emplace_back(entry.nameRef, &attr->name);

                    if (options.keepRawValues) {
                        stringRefs.emplace_back(rawValueRef, &attr->rawValue);
                    } else {
                        attr->rawValue.index = -1;
                    }

                    // Assign the indices for specific attributes.
                    if (entry.xmlAttr->namespaceUri == kSchemaAndroid &&
                            entry.xmlAttr->name == u"id") {
                        elem->idIndex = attributeIndex;
                    } else if (entry.xmlAttr->namespaceUri.empty()) {
                        if (entry.xmlAttr->name == u"class") {
                            elem->classIndex = attributeIndex;
                        } else if (entry.xmlAttr->name == u"style") {
                            elem->styleIndex = attributeIndex;
                        }
                    }
                    attributeIndex++;

                    std::unique_ptr<Item> value;
                    if (entry.attr) {
                        value = ResourceParser::parseItemForAttribute(entry.xmlAttr->value,
                                                                      *entry.attr);
                    } else {
                        bool create = false;
                        value = ResourceParser::tryParseReference(entry.xmlAttr->value, &create);
                    }

                    if (mResolver && value) {
                        AttributeValueFlattener flattener(
                                mResolver,
                                &logger,
                                &attr->typedValue,
                                parser,
                                &error,
                                rawValueRef,
                                &options.defaultPackage,
                                &stringRefs);
                        value->accept(flattener, {});
                    } else if (!value && entry.attr &&
                            !(entry.attr->typeMask & android::ResTable_map::TYPE_STRING)) {
                        logger.error(parser->getLineNumber())
                                << "'"
                                << *rawValueRef
                                << "' is not compatible with attribute "
                                << *entry.attr
                                << "."
                                << std::endl;
                        error = true;
                    } else {
                        attr->typedValue.dataType = android::Res_value::TYPE_STRING;
                        if (!options.keepRawValues) {
                            // Don't set the string twice.
                            stringRefs.emplace_back(rawValueRef, &attr->rawValue);
                        }
                        stringRefs.emplace_back(rawValueRef,
                                reinterpret_cast<android::ResStringPool_ref*>(
                                        &attr->typedValue.data));
                    }
                    attr->typedValue.size = sizeof(attr->typedValue);
                }

                out.align4();
                node->header.size = out.size() - startIndex;
                break;
            }

            case XmlPullParser::Event::kEndElement: {
                const size_t startIndex = out.size();
                android::ResXMLTree_node* node = out.nextBlock<android::ResXMLTree_node>();
                node->header.type = android::RES_XML_END_ELEMENT_TYPE;
                node->header.headerSize = sizeof(*node);
                node->lineNumber = parser->getLineNumber();
                node->comment.index = -1;

                android::ResXMLTree_endElementExt* elem =
                        out.nextBlock<android::ResXMLTree_endElementExt>();
                stringRefs.emplace_back(
                        pool.makeRef(parser->getElementNamespace(), lowPriority), &elem->ns);
                stringRefs.emplace_back(
                        pool.makeRef(parser->getElementName(), lowPriority), &elem->name);

                out.align4();
                node->header.size = out.size() - startIndex;
                break;
            }

            case XmlPullParser::Event::kText: {
                StringPiece16 text = util::trimWhitespace(parser->getText());
                if (text.empty()) {
                    break;
                }

                const size_t startIndex = out.size();
                android::ResXMLTree_node* node = out.nextBlock<android::ResXMLTree_node>();
                node->header.type = android::RES_XML_CDATA_TYPE;
                node->header.headerSize = sizeof(*node);
                node->lineNumber = parser->getLineNumber();
                node->comment.index = -1;

                android::ResXMLTree_cdataExt* elem = out.nextBlock<android::ResXMLTree_cdataExt>();
                stringRefs.emplace_back(pool.makeRef(text, lowPriority), &elem->data);

                out.align4();
                node->header.size = out.size() - startIndex;
                break;
            }

            default:
                break;
        }

    }
    out.align4();

    if (error) {
        return {};
    }

    if (parser->getEvent() == XmlPullParser::Event::kBadDocument) {
        logger.error(parser->getLineNumber())
                << parser->getLastError()
                << std::endl;
        return {};
    }

    // Merge the package pools into the main pool.
    for (auto& packagePoolEntry : packagePools) {
        pool.merge(std::move(packagePoolEntry.second));
    }

    // Sort so that attribute resource IDs show up first.
    pool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        return a.context.priority < b.context.priority;
    });

    // Now we flatten the string pool references into the correct places.
    for (const auto& refEntry : stringRefs) {
        refEntry.second->index = refEntry.first.getIndex();
    }

    // Write the XML header.
    const size_t beforeXmlTreeIndex = outBuffer->size();
    android::ResXMLTree_header* header = outBuffer->nextBlock<android::ResXMLTree_header>();
    header->header.type = android::RES_XML_TYPE;
    header->header.headerSize = sizeof(*header);

    // Flatten the StringPool.
    StringPool::flattenUtf16(outBuffer, pool);

    // Write the array of resource IDs, indexed by StringPool order.
    const size_t beforeResIdMapIndex = outBuffer->size();
    android::ResChunk_header* resIdMapChunk = outBuffer->nextBlock<android::ResChunk_header>();
    resIdMapChunk->type = android::RES_XML_RESOURCE_MAP_TYPE;
    resIdMapChunk->headerSize = sizeof(*resIdMapChunk);
    for (const auto& str : pool) {
        ResourceId id { str->context.priority };
        if (!id.isValid()) {
            // When we see the first non-resource ID,
            // we're done.
            break;
        }

        uint32_t* flatId = outBuffer->nextBlock<uint32_t>();
        *flatId = id.id;
    }
    resIdMapChunk->size = outBuffer->size() - beforeResIdMapIndex;

    // Move the temporary BigBuffer into outBuffer.
    outBuffer->appendBuffer(std::move(out));

    header->header.size = outBuffer->size() - beforeXmlTreeIndex;

    if (smallestStrippedAttributeSdk == std::numeric_limits<size_t>::max()) {
        // Nothing was stripped
        return 0u;
    }
    return smallestStrippedAttributeSdk;
}

} // namespace aapt
