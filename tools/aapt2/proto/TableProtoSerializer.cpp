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

#include "Resource.h"
#include "ResourceTable.h"
#include "StringPool.h"
#include "ValueVisitor.h"
#include "proto/ProtoHelpers.h"
#include "proto/ProtoSerialize.h"
#include "util/BigBuffer.h"

using google::protobuf::io::CodedOutputStream;
using google::protobuf::io::CodedInputStream;
using google::protobuf::io::ZeroCopyOutputStream;

namespace aapt {

namespace {

class PbSerializerVisitor : public RawValueVisitor {
public:
    using RawValueVisitor::visit;

    /**
     * Constructor to use when expecting to serialize any value.
     */
    PbSerializerVisitor(StringPool* sourcePool, StringPool* symbolPool, pb::Value* outPbValue) :
            mSourcePool(sourcePool), mSymbolPool(symbolPool), mOutPbValue(outPbValue),
            mOutPbItem(nullptr) {
    }

    /**
     * Constructor to use when expecting to serialize an Item.
     */
    PbSerializerVisitor(StringPool* sourcePool, StringPool* symbolPool, pb::Item* outPbItem) :
            mSourcePool(sourcePool), mSymbolPool(symbolPool), mOutPbValue(nullptr),
            mOutPbItem(outPbItem) {
    }

    void visit(Reference* ref) override {
        serializeReferenceToPb(*ref, getPbItem()->mutable_ref());
    }

    void visit(String* str) override {
        getPbItem()->mutable_str()->set_idx(str->value.getIndex());
    }

    void visit(StyledString* str) override {
        getPbItem()->mutable_str()->set_idx(str->value.getIndex());
    }

    void visit(FileReference* file) override {
        getPbItem()->mutable_file()->set_path_idx(file->path.getIndex());
    }

    void visit(Id* id) override {
        getPbItem()->mutable_id();
    }

    void visit(RawString* rawStr) override {
        getPbItem()->mutable_raw_str()->set_idx(rawStr->value.getIndex());
    }

    void visit(BinaryPrimitive* prim) override {
        android::Res_value val = {};
        prim->flatten(&val);

        pb::Primitive* pbPrim = getPbItem()->mutable_prim();
        pbPrim->set_type(val.dataType);
        pbPrim->set_data(val.data);
    }

    void visitItem(Item* item) override {
        assert(false && "unimplemented item");
    }

    void visit(Attribute* attr) override {
        pb::Attribute* pbAttr = getPbCompoundValue()->mutable_attr();
        pbAttr->set_format_flags(attr->typeMask);
        pbAttr->set_min_int(attr->minInt);
        pbAttr->set_max_int(attr->maxInt);

        for (auto& symbol : attr->symbols) {
            pb::Attribute_Symbol* pbSymbol = pbAttr->add_symbols();
            serializeItemCommonToPb(symbol.symbol, pbSymbol);
            serializeReferenceToPb(symbol.symbol, pbSymbol->mutable_name());
            pbSymbol->set_value(symbol.value);
        }
    }

    void visit(Style* style) override {
        pb::Style* pbStyle = getPbCompoundValue()->mutable_style();
        if (style->parent) {
            serializeReferenceToPb(style->parent.value(), pbStyle->mutable_parent());
            serializeSourceToPb(style->parent.value().getSource(),
                                mSourcePool,
                                pbStyle->mutable_parent_source());
        }

        for (Style::Entry& entry : style->entries) {
            pb::Style_Entry* pbEntry = pbStyle->add_entries();
            serializeReferenceToPb(entry.key, pbEntry->mutable_key());

            pb::Item* pbItem = pbEntry->mutable_item();
            serializeItemCommonToPb(entry.key, pbEntry);
            PbSerializerVisitor subVisitor(mSourcePool, mSymbolPool, pbItem);
            entry.value->accept(&subVisitor);
        }
    }

    void visit(Styleable* styleable) override {
        pb::Styleable* pbStyleable = getPbCompoundValue()->mutable_styleable();
        for (Reference& entry : styleable->entries) {
            pb::Styleable_Entry* pbEntry = pbStyleable->add_entries();
            serializeItemCommonToPb(entry, pbEntry);
            serializeReferenceToPb(entry, pbEntry->mutable_attr());
        }
    }

    void visit(Array* array) override {
        pb::Array* pbArray = getPbCompoundValue()->mutable_array();
        for (auto& value : array->items) {
            pb::Array_Entry* pbEntry = pbArray->add_entries();
            serializeItemCommonToPb(*value, pbEntry);
            PbSerializerVisitor subVisitor(mSourcePool, mSymbolPool, pbEntry->mutable_item());
            value->accept(&subVisitor);
        }
    }

    void visit(Plural* plural) override {
        pb::Plural* pbPlural = getPbCompoundValue()->mutable_plural();
        const size_t count = plural->values.size();
        for (size_t i = 0; i < count; i++) {
            if (!plural->values[i]) {
                // No plural value set here.
                continue;
            }

            pb::Plural_Entry* pbEntry = pbPlural->add_entries();
            pbEntry->set_arity(serializePluralEnumToPb(i));
            pb::Item* pbElement = pbEntry->mutable_item();
            serializeItemCommonToPb(*plural->values[i], pbEntry);
            PbSerializerVisitor subVisitor(mSourcePool, mSymbolPool, pbElement);
            plural->values[i]->accept(&subVisitor);
        }
    }

private:
    pb::Item* getPbItem() {
        if (mOutPbValue) {
            return mOutPbValue->mutable_item();
        }
        return mOutPbItem;
    }

    pb::CompoundValue* getPbCompoundValue() {
        assert(mOutPbValue);
        return mOutPbValue->mutable_compound_value();
    }

    template <typename T>
    void serializeItemCommonToPb(const Item& item, T* pbItem) {
        serializeSourceToPb(item.getSource(), mSourcePool, pbItem->mutable_source());
        if (!item.getComment().empty()) {
            pbItem->set_comment(item.getComment());
        }
    }

    void serializeReferenceToPb(const Reference& ref, pb::Reference* pbRef) {
        if (ref.id) {
            pbRef->set_id(ref.id.value().id);
        }

        if (ref.name) {
            StringPool::Ref symbolRef = mSymbolPool->makeRef(ref.name.value().toString());
            pbRef->set_symbol_idx(static_cast<uint32_t>(symbolRef.getIndex()));
        }

        pbRef->set_private_(ref.privateReference);
        pbRef->set_type(serializeReferenceTypeToPb(ref.referenceType));
    }

    StringPool* mSourcePool;
    StringPool* mSymbolPool;
    pb::Value* mOutPbValue;
    pb::Item* mOutPbItem;
};

} // namespace

std::unique_ptr<pb::ResourceTable> serializeTableToPb(ResourceTable* table) {
    // We must do this before writing the resources, since the string pool IDs may change.
    table->stringPool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        int diff = a.context.priority - b.context.priority;
        if (diff < 0) return true;
        if (diff > 0) return false;
        diff = a.context.config.compare(b.context.config);
        if (diff < 0) return true;
        if (diff > 0) return false;
        return a.value < b.value;
    });
    table->stringPool.prune();

    auto pbTable = util::make_unique<pb::ResourceTable>();
    serializeStringPoolToPb(table->stringPool, pbTable->mutable_string_pool());

    StringPool sourcePool, symbolPool;

    for (auto& package : table->packages) {
        pb::Package* pbPackage = pbTable->add_packages();
        if (package->id) {
            pbPackage->set_package_id(package->id.value());
        }
        pbPackage->set_package_name(package->name);

        for (auto& type : package->types) {
            pb::Type* pbType = pbPackage->add_types();
            if (type->id) {
                pbType->set_id(type->id.value());
            }
            pbType->set_name(toString(type->type).toString());

            for (auto& entry : type->entries) {
                pb::Entry* pbEntry = pbType->add_entries();
                if (entry->id) {
                    pbEntry->set_id(entry->id.value());
                }
                pbEntry->set_name(entry->name);

                // Write the SymbolStatus struct.
                pb::SymbolStatus* pbStatus = pbEntry->mutable_symbol_status();
                pbStatus->set_visibility(serializeVisibilityToPb(entry->symbolStatus.state));
                serializeSourceToPb(entry->symbolStatus.source, &sourcePool,
                                    pbStatus->mutable_source());
                pbStatus->set_comment(entry->symbolStatus.comment);

                for (auto& configValue : entry->values) {
                    pb::ConfigValue* pbConfigValue = pbEntry->add_config_values();
                    serializeConfig(configValue->config, pbConfigValue->mutable_config());
                    if (!configValue->product.empty()) {
                        pbConfigValue->mutable_config()->set_product(configValue->product);
                    }

                    pb::Value* pbValue = pbConfigValue->mutable_value();
                    serializeSourceToPb(configValue->value->getSource(), &sourcePool,
                                        pbValue->mutable_source());
                    if (!configValue->value->getComment().empty()) {
                        pbValue->set_comment(configValue->value->getComment());
                    }

                    if (configValue->value->isWeak()) {
                        pbValue->set_weak(true);
                    }

                    PbSerializerVisitor visitor(&sourcePool, &symbolPool, pbValue);
                    configValue->value->accept(&visitor);
                }
            }
        }
    }

    serializeStringPoolToPb(sourcePool, pbTable->mutable_source_pool());
    serializeStringPoolToPb(symbolPool, pbTable->mutable_symbol_pool());
    return pbTable;
}

std::unique_ptr<pb::CompiledFile> serializeCompiledFileToPb(const ResourceFile& file) {
    auto pbFile = util::make_unique<pb::CompiledFile>();
    pbFile->set_resource_name(file.name.toString());
    pbFile->set_source_path(file.source.path);
    serializeConfig(file.config, pbFile->mutable_config());

    for (const SourcedResourceName& exported : file.exportedSymbols) {
        pb::CompiledFile_Symbol* pbSymbol = pbFile->add_exported_symbols();
        pbSymbol->set_resource_name(exported.name.toString());
        pbSymbol->set_line_no(exported.line);
    }
    return pbFile;
}

CompiledFileOutputStream::CompiledFileOutputStream(ZeroCopyOutputStream* out) : mOut(out) {
}

void CompiledFileOutputStream::ensureAlignedWrite() {
    const int padding = mOut.ByteCount() % 4;
    if (padding > 0) {
        uint32_t zero = 0u;
        mOut.WriteRaw(&zero, padding);
    }
}

void CompiledFileOutputStream::WriteLittleEndian32(uint32_t val) {
    ensureAlignedWrite();
    mOut.WriteLittleEndian32(val);
}

void CompiledFileOutputStream::WriteCompiledFile(const pb::CompiledFile* compiledFile) {
    ensureAlignedWrite();
    mOut.WriteLittleEndian64(static_cast<uint64_t>(compiledFile->ByteSize()));
    compiledFile->SerializeWithCachedSizes(&mOut);
}

void CompiledFileOutputStream::WriteData(const BigBuffer* buffer) {
    ensureAlignedWrite();
    mOut.WriteLittleEndian64(static_cast<uint64_t>(buffer->size()));
    for (const BigBuffer::Block& block : *buffer) {
        mOut.WriteRaw(block.buffer.get(), block.size);
    }
}

void CompiledFileOutputStream::WriteData(const void* data, size_t len) {
    ensureAlignedWrite();
    mOut.WriteLittleEndian64(static_cast<uint64_t>(len));
    mOut.WriteRaw(data, len);
}

bool CompiledFileOutputStream::HadError() {
    return mOut.HadError();
}

CompiledFileInputStream::CompiledFileInputStream(const void* data, size_t size) :
        mIn(static_cast<const uint8_t*>(data), size) {
}

void CompiledFileInputStream::ensureAlignedRead() {
    const int padding = mIn.CurrentPosition() % 4;
    if (padding > 0) {
        // Reads are always 4 byte aligned.
        mIn.Skip(padding);
    }
}

bool CompiledFileInputStream::ReadLittleEndian32(uint32_t* outVal) {
    ensureAlignedRead();
    return mIn.ReadLittleEndian32(outVal);
}

bool CompiledFileInputStream::ReadCompiledFile(pb::CompiledFile* outVal) {
    ensureAlignedRead();

    uint64_t pbSize = 0u;
    if (!mIn.ReadLittleEndian64(&pbSize)) {
        return false;
    }

    CodedInputStream::Limit l = mIn.PushLimit(static_cast<int>(pbSize));

    // Check that we haven't tried to read past the end.
    if (static_cast<uint64_t>(mIn.BytesUntilLimit()) != pbSize) {
        mIn.PopLimit(l);
        mIn.PushLimit(0);
        return false;
    }

    if (!outVal->ParsePartialFromCodedStream(&mIn)) {
        mIn.PopLimit(l);
        mIn.PushLimit(0);
        return false;
    }

    mIn.PopLimit(l);
    return true;
}

bool CompiledFileInputStream::ReadDataMetaData(uint64_t* outOffset, uint64_t* outLen) {
    ensureAlignedRead();

    uint64_t pbSize = 0u;
    if (!mIn.ReadLittleEndian64(&pbSize)) {
        return false;
    }

    // Check that we aren't trying to read past the end.
    if (pbSize > static_cast<uint64_t>(mIn.BytesUntilLimit())) {
        mIn.PushLimit(0);
        return false;
    }

    uint64_t offset = static_cast<uint64_t>(mIn.CurrentPosition());
    if (!mIn.Skip(pbSize)) {
        return false;
    }

    *outOffset = offset;
    *outLen = pbSize;
    return true;
}

} // namespace aapt
