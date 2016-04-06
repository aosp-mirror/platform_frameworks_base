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

#include "SdkConstants.h"
#include "flatten/ChunkWriter.h"
#include "flatten/ResourceTypeExtensions.h"
#include "flatten/XmlFlattener.h"
#include "xml/XmlDom.h"

#include <androidfw/ResourceTypes.h>
#include <algorithm>
#include <utils/misc.h>
#include <vector>

using namespace android;

namespace aapt {

namespace {

constexpr uint32_t kLowPriority = 0xffffffffu;

struct XmlFlattenerVisitor : public xml::Visitor {
    using xml::Visitor::visit;

    BigBuffer* mBuffer;
    XmlFlattenerOptions mOptions;
    StringPool mPool;
    std::map<uint8_t, StringPool> mPackagePools;

    struct StringFlattenDest {
        StringPool::Ref ref;
        ResStringPool_ref* dest;
    };
    std::vector<StringFlattenDest> mStringRefs;

    // Scratch vector to filter attributes. We avoid allocations
    // making this a member.
    std::vector<xml::Attribute*> mFilteredAttrs;


    XmlFlattenerVisitor(BigBuffer* buffer, XmlFlattenerOptions options) :
            mBuffer(buffer), mOptions(options) {
    }

    void addString(const StringPiece16& str, uint32_t priority, android::ResStringPool_ref* dest) {
        if (!str.empty()) {
            mStringRefs.push_back(StringFlattenDest{
                    mPool.makeRef(str, StringPool::Context{ priority }),
                    dest });
        } else {
            // The device doesn't think a string of size 0 is the same as null.
            dest->index = util::deviceToHost32(-1);
        }
    }

    void addString(const StringPool::Ref& ref, android::ResStringPool_ref* dest) {
        mStringRefs.push_back(StringFlattenDest{ ref, dest });
    }

    void writeNamespace(xml::Namespace* node, uint16_t type) {
        ChunkWriter writer(mBuffer);

        ResXMLTree_node* flatNode = writer.startChunk<ResXMLTree_node>(type);
        flatNode->lineNumber = util::hostToDevice32(node->lineNumber);
        flatNode->comment.index = util::hostToDevice32(-1);

        ResXMLTree_namespaceExt* flatNs = writer.nextBlock<ResXMLTree_namespaceExt>();
        addString(node->namespacePrefix, kLowPriority, &flatNs->prefix);
        addString(node->namespaceUri, kLowPriority, &flatNs->uri);

        writer.finish();
    }

    void visit(xml::Namespace* node) override {
        writeNamespace(node, android::RES_XML_START_NAMESPACE_TYPE);
        xml::Visitor::visit(node);
        writeNamespace(node, android::RES_XML_END_NAMESPACE_TYPE);
    }

    void visit(xml::Text* node) override {
        if (util::trimWhitespace(node->text).empty()) {
            // Skip whitespace only text nodes.
            return;
        }

        ChunkWriter writer(mBuffer);
        ResXMLTree_node* flatNode = writer.startChunk<ResXMLTree_node>(RES_XML_CDATA_TYPE);
        flatNode->lineNumber = util::hostToDevice32(node->lineNumber);
        flatNode->comment.index = util::hostToDevice32(-1);

        ResXMLTree_cdataExt* flatText = writer.nextBlock<ResXMLTree_cdataExt>();
        addString(node->text, kLowPriority, &flatText->data);

        writer.finish();
    }

    void visit(xml::Element* node) override {
        {
            ChunkWriter startWriter(mBuffer);
            ResXMLTree_node* flatNode = startWriter.startChunk<ResXMLTree_node>(
                    RES_XML_START_ELEMENT_TYPE);
            flatNode->lineNumber = util::hostToDevice32(node->lineNumber);
            flatNode->comment.index = util::hostToDevice32(-1);

            ResXMLTree_attrExt* flatElem = startWriter.nextBlock<ResXMLTree_attrExt>();
            addString(node->namespaceUri, kLowPriority, &flatElem->ns);
            addString(node->name, kLowPriority, &flatElem->name);
            flatElem->attributeStart = util::hostToDevice16(sizeof(*flatElem));
            flatElem->attributeSize = util::hostToDevice16(sizeof(ResXMLTree_attribute));

            writeAttributes(node, flatElem, &startWriter);

            startWriter.finish();
        }

        xml::Visitor::visit(node);

        {
            ChunkWriter endWriter(mBuffer);
            ResXMLTree_node* flatEndNode = endWriter.startChunk<ResXMLTree_node>(
                    RES_XML_END_ELEMENT_TYPE);
            flatEndNode->lineNumber = util::hostToDevice32(node->lineNumber);
            flatEndNode->comment.index = util::hostToDevice32(-1);

            ResXMLTree_endElementExt* flatEndElem = endWriter.nextBlock<ResXMLTree_endElementExt>();
            addString(node->namespaceUri, kLowPriority, &flatEndElem->ns);
            addString(node->name, kLowPriority, &flatEndElem->name);

            endWriter.finish();
        }
    }

    static bool cmpXmlAttributeById(const xml::Attribute* a, const xml::Attribute* b) {
        if (a->compiledAttribute && a->compiledAttribute.value().id) {
            if (b->compiledAttribute && b->compiledAttribute.value().id) {
                return a->compiledAttribute.value().id.value() < b->compiledAttribute.value().id.value();
            }
            return true;
        } else if (!b->compiledAttribute) {
            int diff = a->namespaceUri.compare(b->namespaceUri);
            if (diff < 0) {
                return true;
            } else if (diff > 0) {
                return false;
            }
            return a->name < b->name;
        }
        return false;
    }

    void writeAttributes(xml::Element* node, ResXMLTree_attrExt* flatElem, ChunkWriter* writer) {
        mFilteredAttrs.clear();
        mFilteredAttrs.reserve(node->attributes.size());

        // Filter the attributes.
        for (xml::Attribute& attr : node->attributes) {
            if (mOptions.maxSdkLevel && attr.compiledAttribute && attr.compiledAttribute.value().id) {
                size_t sdkLevel = findAttributeSdkLevel(attr.compiledAttribute.value().id.value());
                if (sdkLevel > mOptions.maxSdkLevel.value()) {
                    continue;
                }
            }
            mFilteredAttrs.push_back(&attr);
        }

        if (mFilteredAttrs.empty()) {
            return;
        }

        const ResourceId kIdAttr(0x010100d0);

        std::sort(mFilteredAttrs.begin(), mFilteredAttrs.end(), cmpXmlAttributeById);

        flatElem->attributeCount = util::hostToDevice16(mFilteredAttrs.size());

        ResXMLTree_attribute* flatAttr = writer->nextBlock<ResXMLTree_attribute>(
                mFilteredAttrs.size());
        uint16_t attributeIndex = 1;
        for (const xml::Attribute* xmlAttr : mFilteredAttrs) {
            // Assign the indices for specific attributes.
            if (xmlAttr->compiledAttribute && xmlAttr->compiledAttribute.value().id &&
                    xmlAttr->compiledAttribute.value().id.value() == kIdAttr) {
                flatElem->idIndex = util::hostToDevice16(attributeIndex);
            } else if (xmlAttr->namespaceUri.empty()) {
                if (xmlAttr->name == u"class") {
                    flatElem->classIndex = util::hostToDevice16(attributeIndex);
                } else if (xmlAttr->name == u"style") {
                    flatElem->styleIndex = util::hostToDevice16(attributeIndex);
                }
            }
            attributeIndex++;

            // Add the namespaceUri to the list of StringRefs to encode.
            addString(xmlAttr->namespaceUri, kLowPriority, &flatAttr->ns);

            flatAttr->rawValue.index = util::hostToDevice32(-1);

            if (!xmlAttr->compiledAttribute || !xmlAttr->compiledAttribute.value().id) {
                // The attribute has no associated ResourceID, so the string order doesn't matter.
                addString(xmlAttr->name, kLowPriority, &flatAttr->name);
            } else {
                // Attribute names are stored without packages, but we use
                // their StringPool index to lookup their resource IDs.
                // This will cause collisions, so we can't dedupe
                // attribute names from different packages. We use separate
                // pools that we later combine.
                //
                // Lookup the StringPool for this package and make the reference there.
                const xml::AaptAttribute& aaptAttr = xmlAttr->compiledAttribute.value();

                StringPool::Ref nameRef = mPackagePools[aaptAttr.id.value().packageId()].makeRef(
                        xmlAttr->name, StringPool::Context{ aaptAttr.id.value().id });

                // Add it to the list of strings to flatten.
                addString(nameRef, &flatAttr->name);
            }

            if (mOptions.keepRawValues || !xmlAttr->compiledValue) {
                // Keep raw values if the value is not compiled or
                // if we're building a static library (need symbols).
                addString(xmlAttr->value, kLowPriority, &flatAttr->rawValue);
            }

            if (xmlAttr->compiledValue) {
                bool result = xmlAttr->compiledValue->flatten(&flatAttr->typedValue);
                assert(result);
            } else {
                // Flatten as a regular string type.
                flatAttr->typedValue.dataType = android::Res_value::TYPE_STRING;
                addString(xmlAttr->value, kLowPriority,
                          (ResStringPool_ref*) &flatAttr->typedValue.data);
            }

            flatAttr->typedValue.size = util::hostToDevice16(sizeof(flatAttr->typedValue));
            flatAttr++;
        }
    }
};

} // namespace

bool XmlFlattener::flatten(IAaptContext* context, xml::Node* node) {
    BigBuffer nodeBuffer(1024);
    XmlFlattenerVisitor visitor(&nodeBuffer, mOptions);
    node->accept(&visitor);

    // Merge the package pools into the main pool.
    for (auto& packagePoolEntry : visitor.mPackagePools) {
        visitor.mPool.merge(std::move(packagePoolEntry.second));
    }

    // Sort the string pool so that attribute resource IDs show up first.
    visitor.mPool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        return a.context.priority < b.context.priority;
    });

    // Now we flatten the string pool references into the correct places.
    for (const auto& refEntry : visitor.mStringRefs) {
        refEntry.dest->index = util::hostToDevice32(refEntry.ref.getIndex());
    }

    // Write the XML header.
    ChunkWriter xmlHeaderWriter(mBuffer);
    xmlHeaderWriter.startChunk<ResXMLTree_header>(RES_XML_TYPE);

    // Flatten the StringPool.
    StringPool::flattenUtf16(mBuffer, visitor.mPool);

    {
        // Write the array of resource IDs, indexed by StringPool order.
        ChunkWriter resIdMapWriter(mBuffer);
        resIdMapWriter.startChunk<ResChunk_header>(RES_XML_RESOURCE_MAP_TYPE);
        for (const auto& str : visitor.mPool) {
            ResourceId id = { str->context.priority };
            if (id.id == kLowPriority || !id.isValid()) {
                // When we see the first non-resource ID,
                // we're done.
                break;
            }

            *resIdMapWriter.nextBlock<uint32_t>() = id.id;
        }
        resIdMapWriter.finish();
    }

    // Move the nodeBuffer and append it to the out buffer.
    mBuffer->appendBuffer(std::move(nodeBuffer));

    // Finish the xml header.
    xmlHeaderWriter.finish();
    return true;
}

bool XmlFlattener::consume(IAaptContext* context, xml::XmlResource* resource) {
    if (!resource->root) {
        return false;
    }
    return flatten(context, resource->root.get());
}

} // namespace aapt
