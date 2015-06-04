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
namespace xml {

constexpr uint32_t kLowPriority = 0xffffffffu;

// A vector that maps String refs to their final destination in the out buffer.
using FlatStringRefList = std::vector<std::pair<StringPool::Ref, android::ResStringPool_ref*>>;

struct XmlFlattener : public Visitor {
    XmlFlattener(BigBuffer* outBuffer, StringPool* pool, FlatStringRefList* stringRefs,
                 const std::u16string& defaultPackage) :
            mOut(outBuffer), mPool(pool), mStringRefs(stringRefs),
            mDefaultPackage(defaultPackage) {
    }

    // No copying.
    XmlFlattener(const XmlFlattener&) = delete;
    XmlFlattener& operator=(const XmlFlattener&) = delete;

    void writeNamespace(Namespace* node, uint16_t type) {
        const size_t startIndex = mOut->size();
        android::ResXMLTree_node* flatNode = mOut->nextBlock<android::ResXMLTree_node>();
        android::ResXMLTree_namespaceExt* flatNs =
                mOut->nextBlock<android::ResXMLTree_namespaceExt>();
        mOut->align4();

        flatNode->header = { type, sizeof(*flatNode), (uint32_t)(mOut->size() - startIndex) };
        flatNode->lineNumber = node->lineNumber;
        flatNode->comment.index = -1;
        addString(node->namespacePrefix, kLowPriority, &flatNs->prefix);
        addString(node->namespaceUri, kLowPriority, &flatNs->uri);
    }

    virtual void visit(Namespace* node) override {
        // Extract the package/prefix from this namespace node.
        Maybe<std::u16string> package = util::extractPackageFromNamespace(node->namespaceUri);
        if (package) {
            mPackageAliases.emplace_back(
                    node->namespacePrefix,
                    package.value().empty() ? mDefaultPackage : package.value());
        }

        writeNamespace(node, android::RES_XML_START_NAMESPACE_TYPE);
        for (const auto& child : node->children) {
            child->accept(this);
        }
        writeNamespace(node, android::RES_XML_END_NAMESPACE_TYPE);

        if (package) {
            mPackageAliases.pop_back();
        }
    }

    virtual void visit(Text* node) override {
        if (util::trimWhitespace(node->text).empty()) {
            return;
        }

        const size_t startIndex = mOut->size();
        android::ResXMLTree_node* flatNode = mOut->nextBlock<android::ResXMLTree_node>();
        android::ResXMLTree_cdataExt* flatText = mOut->nextBlock<android::ResXMLTree_cdataExt>();
        mOut->align4();

        const uint16_t type = android::RES_XML_CDATA_TYPE;
        flatNode->header = { type, sizeof(*flatNode), (uint32_t)(mOut->size() - startIndex) };
        flatNode->lineNumber = node->lineNumber;
        flatNode->comment.index = -1;
        addString(node->text, kLowPriority, &flatText->data);
    }

    virtual void visit(Element* node) override {
        const size_t startIndex = mOut->size();
        android::ResXMLTree_node* flatNode = mOut->nextBlock<android::ResXMLTree_node>();
        android::ResXMLTree_attrExt* flatElem = mOut->nextBlock<android::ResXMLTree_attrExt>();

        const uint16_t type = android::RES_XML_START_ELEMENT_TYPE;
        flatNode->header = { type, sizeof(*flatNode), 0 };
        flatNode->lineNumber = node->lineNumber;
        flatNode->comment.index = -1;

        addString(node->namespaceUri, kLowPriority, &flatElem->ns);
        addString(node->name, kLowPriority, &flatElem->name);
        flatElem->attributeStart = sizeof(*flatElem);
        flatElem->attributeSize = sizeof(android::ResXMLTree_attribute);
        flatElem->attributeCount = node->attributes.size();

        if (!writeAttributes(mOut, node, flatElem)) {
            mError = true;
        }

        mOut->align4();
        flatNode->header.size = (uint32_t)(mOut->size() - startIndex);

        for (const auto& child : node->children) {
            child->accept(this);
        }

        const size_t startEndIndex = mOut->size();
        android::ResXMLTree_node* flatEndNode = mOut->nextBlock<android::ResXMLTree_node>();
        android::ResXMLTree_endElementExt* flatEndElem =
                mOut->nextBlock<android::ResXMLTree_endElementExt>();
        mOut->align4();

        const uint16_t endType = android::RES_XML_END_ELEMENT_TYPE;
        flatEndNode->header = { endType, sizeof(*flatEndNode),
                (uint32_t)(mOut->size() - startEndIndex) };
        flatEndNode->lineNumber = node->lineNumber;
        flatEndNode->comment.index = -1;

        addString(node->namespaceUri, kLowPriority, &flatEndElem->ns);
        addString(node->name, kLowPriority, &flatEndElem->name);
    }

    bool success() const {
        return !mError;
    }

protected:
    void addString(const StringPiece16& str, uint32_t priority, android::ResStringPool_ref* dest) {
        if (!str.empty()) {
            mStringRefs->emplace_back(mPool->makeRef(str, StringPool::Context{ priority }), dest);
        } else {
            // The device doesn't think a string of size 0 is the same as null.
            dest->index = -1;
        }
    }

    void addString(const StringPool::Ref& ref, android::ResStringPool_ref* dest) {
        mStringRefs->emplace_back(ref, dest);
    }

    Maybe<std::u16string> getPackageAlias(const std::u16string& prefix) {
        const auto endIter = mPackageAliases.rend();
        for (auto iter = mPackageAliases.rbegin(); iter != endIter; ++iter) {
            if (iter->first == prefix) {
                return iter->second;
            }
        }
        return {};
    }

    const std::u16string& getDefaultPackage() const {
        return mDefaultPackage;
    }

    /**
     * Subclasses override this to deal with attributes. Attributes can be flattened as
     * raw values or as resources.
     */
    virtual bool writeAttributes(BigBuffer* out, Element* node,
                                 android::ResXMLTree_attrExt* flatElem) = 0;

private:
    BigBuffer* mOut;
    StringPool* mPool;
    FlatStringRefList* mStringRefs;
    std::u16string mDefaultPackage;
    bool mError = false;
    std::vector<std::pair<std::u16string, std::u16string>> mPackageAliases;
};

/**
 * Flattens XML, encoding the attributes as raw strings. This is used in the compile phase.
 */
struct CompileXmlFlattener : public XmlFlattener {
    CompileXmlFlattener(BigBuffer* outBuffer, StringPool* pool, FlatStringRefList* stringRefs,
                        const std::u16string& defaultPackage) :
            XmlFlattener(outBuffer, pool, stringRefs, defaultPackage) {
    }

    virtual bool writeAttributes(BigBuffer* out, Element* node,
                                 android::ResXMLTree_attrExt* flatElem) override {
        flatElem->attributeCount = node->attributes.size();
        if (node->attributes.empty()) {
            return true;
        }

        android::ResXMLTree_attribute* flatAttrs = out->nextBlock<android::ResXMLTree_attribute>(
                node->attributes.size());
        for (const Attribute& attr : node->attributes) {
            addString(attr.namespaceUri, kLowPriority, &flatAttrs->ns);
            addString(attr.name, kLowPriority, &flatAttrs->name);
            addString(attr.value, kLowPriority, &flatAttrs->rawValue);
            flatAttrs++;
        }
        return true;
    }
};

struct AttributeToFlatten {
    uint32_t resourceId = 0;
    const Attribute* xmlAttr = nullptr;
    const ::aapt::Attribute* resourceAttr = nullptr;
};

static bool lessAttributeId(const AttributeToFlatten& a, uint32_t id) {
    return a.resourceId < id;
}

/**
 * Flattens XML, encoding the attributes as resources.
 */
struct LinkedXmlFlattener : public XmlFlattener {
    LinkedXmlFlattener(BigBuffer* outBuffer, StringPool* pool,
                       std::map<std::u16string, StringPool>* packagePools,
                       FlatStringRefList* stringRefs,
                       const std::u16string& defaultPackage,
                       const std::shared_ptr<IResolver>& resolver,
                       SourceLogger* logger,
                       const FlattenOptions& options) :
            XmlFlattener(outBuffer, pool, stringRefs, defaultPackage), mResolver(resolver),
            mLogger(logger), mPackagePools(packagePools), mOptions(options) {
    }

    virtual bool writeAttributes(BigBuffer* out, Element* node,
                                 android::ResXMLTree_attrExt* flatElem) override {
        bool error = false;
        std::vector<AttributeToFlatten> sortedAttributes;
        uint32_t nextAttributeId = 0x80000000u;

        // Sort and filter attributes by their resource ID.
        for (const Attribute& attr : node->attributes) {
            AttributeToFlatten attrToFlatten;
            attrToFlatten.xmlAttr = &attr;

            Maybe<std::u16string> package = util::extractPackageFromNamespace(attr.namespaceUri);
            if (package) {
                // Find the Attribute object via our Resolver.
                ResourceName attrName = { package.value(), ResourceType::kAttr, attr.name };
                if (attrName.package.empty()) {
                    attrName.package = getDefaultPackage();
                }

                Maybe<IResolver::Entry> result = mResolver->findAttribute(attrName);
                if (!result || !result.value().id.isValid() || !result.value().attr) {
                    error = true;
                    mLogger->error(node->lineNumber)
                            << "unresolved attribute '" << attrName << "'."
                            << std::endl;
                } else {
                    attrToFlatten.resourceId = result.value().id.id;
                    attrToFlatten.resourceAttr = result.value().attr;

                    size_t sdk = findAttributeSdkLevel(attrToFlatten.resourceId);
                    if (mOptions.maxSdkAttribute && sdk > mOptions.maxSdkAttribute.value()) {
                        // We need to filter this attribute out.
                        mSmallestFilteredSdk = std::min(mSmallestFilteredSdk, sdk);
                        continue;
                    }
                }
            }

            if (attrToFlatten.resourceId == 0) {
                // Attributes that have no resource ID (because they don't belong to a
                // package) should appear after those that do have resource IDs. Assign
                // them some integer value that will appear after.
                attrToFlatten.resourceId = nextAttributeId++;
            }

            // Insert the attribute into the sorted vector.
            auto iter = std::lower_bound(sortedAttributes.begin(), sortedAttributes.end(),
                                         attrToFlatten.resourceId, lessAttributeId);
            sortedAttributes.insert(iter, std::move(attrToFlatten));
        }

        flatElem->attributeCount = sortedAttributes.size();
        if (sortedAttributes.empty()) {
            return true;
        }

        android::ResXMLTree_attribute* flatAttr = out->nextBlock<android::ResXMLTree_attribute>(
                sortedAttributes.size());

        // Now that we have sorted the attributes into their final encoded order, it's time
        // to actually write them out.
        uint16_t attributeIndex = 1;
        for (const AttributeToFlatten& attrToFlatten : sortedAttributes) {
            Maybe<std::u16string> package = util::extractPackageFromNamespace(
                    attrToFlatten.xmlAttr->namespaceUri);

            // Assign the indices for specific attributes.
            if (package && package.value() == u"android" && attrToFlatten.xmlAttr->name == u"id") {
                flatElem->idIndex = attributeIndex;
            } else if (attrToFlatten.xmlAttr->namespaceUri.empty()) {
                if (attrToFlatten.xmlAttr->name == u"class") {
                    flatElem->classIndex = attributeIndex;
                } else if (attrToFlatten.xmlAttr->name == u"style") {
                    flatElem->styleIndex = attributeIndex;
                }
            }
            attributeIndex++;

            // Add the namespaceUri and name to the list of StringRefs to encode.
            addString(attrToFlatten.xmlAttr->namespaceUri, kLowPriority, &flatAttr->ns);
            flatAttr->rawValue.index = -1;

            if (!attrToFlatten.resourceAttr) {
                addString(attrToFlatten.xmlAttr->name, kLowPriority, &flatAttr->name);
            } else {
                // We've already extracted the package successfully before.
                assert(package);

                // Attribute names are stored without packages, but we use
                // their StringPool index to lookup their resource IDs.
                // This will cause collisions, so we can't dedupe
                // attribute names from different packages. We use separate
                // pools that we later combine.
                //
                // Lookup the StringPool for this package and make the reference there.
                StringPool::Ref nameRef = (*mPackagePools)[package.value()].makeRef(
                        attrToFlatten.xmlAttr->name,
                        StringPool::Context{ attrToFlatten.resourceId });

                // Add it to the list of strings to flatten.
                addString(nameRef, &flatAttr->name);

                if (mOptions.keepRawValues) {
                    // Keep raw values (this is for static libraries).
                    // TODO(with a smarter inflater for binary XML, we can do without this).
                    addString(attrToFlatten.xmlAttr->value, kLowPriority, &flatAttr->rawValue);
                }
            }

            error |= !flattenItem(node, attrToFlatten.xmlAttr->value, attrToFlatten.resourceAttr,
                                  flatAttr);
            flatAttr->typedValue.size = sizeof(flatAttr->typedValue);
            flatAttr++;
        }
        return !error;
    }

    Maybe<size_t> getSmallestFilteredSdk() const {
        if (mSmallestFilteredSdk == std::numeric_limits<size_t>::max()) {
            return {};
        }
        return mSmallestFilteredSdk;
    }

private:
    bool flattenItem(const Node* el, const std::u16string& value, const ::aapt::Attribute* attr,
                     android::ResXMLTree_attribute* flatAttr) {
        std::unique_ptr<Item> item;
        if (!attr) {
            bool create = false;
            item = ResourceParser::tryParseReference(value, &create);
            if (!item) {
                flatAttr->typedValue.dataType = android::Res_value::TYPE_STRING;
                addString(value, kLowPriority, &flatAttr->rawValue);
                addString(value, kLowPriority, reinterpret_cast<android::ResStringPool_ref*>(
                        &flatAttr->typedValue.data));
                return true;
            }
        } else {
            item = ResourceParser::parseItemForAttribute(value, *attr);
            if (!item) {
                if (!(attr->typeMask & android::ResTable_map::TYPE_STRING)) {
                    mLogger->error(el->lineNumber)
                            << "'"
                            << value
                            << "' is not compatible with attribute '"
                            << *attr
                            << "'."
                            << std::endl;
                    return false;
                }

                flatAttr->typedValue.dataType = android::Res_value::TYPE_STRING;
                addString(value, kLowPriority, &flatAttr->rawValue);
                addString(value, kLowPriority, reinterpret_cast<android::ResStringPool_ref*>(
                        &flatAttr->typedValue.data));
                return true;
            }
        }

        assert(item);

        bool error = false;

        // If this is a reference, resolve the name into an ID.
        visitFunc<Reference>(*item, [&](Reference& reference) {
            // First see if we can convert the package name from a prefix to a real
            // package name.
            ResourceName realName = reference.name;
            if (!realName.package.empty()) {
                Maybe<std::u16string> package = getPackageAlias(realName.package);
                if (package) {
                    realName.package = package.value();
                }
            } else {
                realName.package = getDefaultPackage();
            }

            Maybe<ResourceId> result = mResolver->findId(realName);
            if (!result || !result.value().isValid()) {
                std::ostream& out = mLogger->error(el->lineNumber)
                        << "unresolved reference '"
                        << reference.name
                        << "'";
                if (realName != reference.name) {
                    out << " (aka '" << realName << "')";
                }
                out << "'." << std::endl;
                error = true;
            } else {
                reference.id = result.value();
            }
        });

        if (error) {
            return false;
        }

        item->flatten(flatAttr->typedValue);
        return true;
    }

    std::shared_ptr<IResolver> mResolver;
    SourceLogger* mLogger;
    std::map<std::u16string, StringPool>* mPackagePools;
    FlattenOptions mOptions;
    size_t mSmallestFilteredSdk = std::numeric_limits<size_t>::max();
};

/**
 * The binary XML file expects the StringPool to appear first, but we haven't collected the
 * strings yet. We write to a temporary BigBuffer while parsing the input, adding strings
 * we encounter to the StringPool. At the end, we write the StringPool to the given BigBuffer and
 * then move the data from the temporary BigBuffer into the given one. This incurs no
 * copies as the given BigBuffer simply takes ownership of the data.
 */
static void flattenXml(StringPool* pool, FlatStringRefList* stringRefs, BigBuffer* outBuffer,
                       BigBuffer&& xmlTreeBuffer) {
    // Sort the string pool so that attribute resource IDs show up first.
    pool->sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        return a.context.priority < b.context.priority;
    });

    // Now we flatten the string pool references into the correct places.
    for (const auto& refEntry : *stringRefs) {
        refEntry.second->index = refEntry.first.getIndex();
    }

    // Write the XML header.
    const size_t beforeXmlTreeIndex = outBuffer->size();
    android::ResXMLTree_header* header = outBuffer->nextBlock<android::ResXMLTree_header>();
    header->header.type = android::RES_XML_TYPE;
    header->header.headerSize = sizeof(*header);

    // Flatten the StringPool.
    StringPool::flattenUtf16(outBuffer, *pool);

    // Write the array of resource IDs, indexed by StringPool order.
    const size_t beforeResIdMapIndex = outBuffer->size();
    android::ResChunk_header* resIdMapChunk = outBuffer->nextBlock<android::ResChunk_header>();
    resIdMapChunk->type = android::RES_XML_RESOURCE_MAP_TYPE;
    resIdMapChunk->headerSize = sizeof(*resIdMapChunk);
    for (const auto& str : *pool) {
        ResourceId id { str->context.priority };
        if (id.id == kLowPriority || !id.isValid()) {
            // When we see the first non-resource ID,
            // we're done.
            break;
        }

        *outBuffer->nextBlock<uint32_t>() = id.id;
    }
    resIdMapChunk->size = outBuffer->size() - beforeResIdMapIndex;

    // Move the temporary BigBuffer into outBuffer.
    outBuffer->appendBuffer(std::move(xmlTreeBuffer));
    header->header.size = outBuffer->size() - beforeXmlTreeIndex;
}

bool flatten(Node* root, const std::u16string& defaultPackage, BigBuffer* outBuffer) {
    StringPool pool;

    // This will hold the StringRefs and the location in which to write the index.
    // Once we sort the StringPool, we can assign the updated indices
    // to the correct data locations.
    FlatStringRefList stringRefs;

    // Since we don't know the size of the final StringPool, we write to this
    // temporary BigBuffer, which we will append to outBuffer later.
    BigBuffer out(1024);

    CompileXmlFlattener flattener(&out, &pool, &stringRefs, defaultPackage);
    root->accept(&flattener);

    if (!flattener.success()) {
        return false;
    }

    flattenXml(&pool, &stringRefs, outBuffer, std::move(out));
    return true;
};

Maybe<size_t> flattenAndLink(const Source& source, Node* root,
                             const std::u16string& defaultPackage,
                             const std::shared_ptr<IResolver>& resolver,
                             const FlattenOptions& options, BigBuffer* outBuffer) {
    SourceLogger logger(source);
    StringPool pool;

    // Attribute names are stored without packages, but we use
    // their StringPool index to lookup their resource IDs.
    // This will cause collisions, so we can't dedupe
    // attribute names from different packages. We use separate
    // pools that we later combine.
    std::map<std::u16string, StringPool> packagePools;

    FlatStringRefList stringRefs;

    // Since we don't know the size of the final StringPool, we write to this
    // temporary BigBuffer, which we will append to outBuffer later.
    BigBuffer out(1024);

    LinkedXmlFlattener flattener(&out, &pool, &packagePools, &stringRefs, defaultPackage, resolver,
                                 &logger, options);
    root->accept(&flattener);

    if (!flattener.success()) {
        return {};
    }

    // Merge the package pools into the main pool.
    for (auto& packagePoolEntry : packagePools) {
        pool.merge(std::move(packagePoolEntry.second));
    }

    flattenXml(&pool, &stringRefs, outBuffer, std::move(out));

    if (flattener.getSmallestFilteredSdk()) {
        return flattener.getSmallestFilteredSdk();
    }
    return 0;
}

} // namespace xml
} // namespace aapt
