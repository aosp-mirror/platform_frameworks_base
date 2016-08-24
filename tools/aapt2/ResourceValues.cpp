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

#include "Resource.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "io/File.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <limits>

namespace aapt {

template <typename Derived>
void BaseValue<Derived>::accept(RawValueVisitor* visitor) {
    visitor->visit(static_cast<Derived*>(this));
}

template <typename Derived>
void BaseItem<Derived>::accept(RawValueVisitor* visitor) {
    visitor->visit(static_cast<Derived*>(this));
}

RawString::RawString(const StringPool::Ref& ref) : value(ref) {
}

RawString* RawString::clone(StringPool* newPool) const {
    RawString* rs = new RawString(newPool->makeRef(*value));
    rs->mComment = mComment;
    rs->mSource = mSource;
    return rs;
}

bool RawString::flatten(android::Res_value* outValue) const {
    outValue->dataType = android::Res_value::TYPE_STRING;
    outValue->data = util::hostToDevice32(static_cast<uint32_t>(value.getIndex()));
    return true;
}

void RawString::print(std::ostream* out) const {
    *out << "(raw string) " << *value;
}

Reference::Reference() : referenceType(Reference::Type::kResource) {
}

Reference::Reference(const ResourceNameRef& n, Type t) :
        name(n.toResourceName()), referenceType(t) {
}

Reference::Reference(const ResourceId& i, Type type) : id(i), referenceType(type) {
}

bool Reference::flatten(android::Res_value* outValue) const {
    outValue->dataType = (referenceType == Reference::Type::kResource) ?
            android::Res_value::TYPE_REFERENCE : android::Res_value::TYPE_ATTRIBUTE;
    outValue->data = util::hostToDevice32(id ? id.value().id : 0);
    return true;
}

Reference* Reference::clone(StringPool* /*newPool*/) const {
    return new Reference(*this);
}

void Reference::print(std::ostream* out) const {
    *out << "(reference) ";
    if (referenceType == Reference::Type::kResource) {
        *out << "@";
        if (privateReference) {
            *out << "*";
        }
    } else {
        *out << "?";
    }

    if (name) {
        *out << name.value();
    }

    if (id && !Res_INTERNALID(id.value().id)) {
        *out << " " << id.value();
    }
}

bool Id::flatten(android::Res_value* out) const {
    out->dataType = android::Res_value::TYPE_INT_BOOLEAN;
    out->data = util::hostToDevice32(0);
    return true;
}

Id* Id::clone(StringPool* /*newPool*/) const {
    return new Id(*this);
}

void Id::print(std::ostream* out) const {
    *out << "(id)";
}

String::String(const StringPool::Ref& ref) : value(ref), mTranslateable(true) {
}

void String::setTranslateable(bool val) {
    mTranslateable = val;
}

bool String::isTranslateable() const {
    return mTranslateable;
}

bool String::flatten(android::Res_value* outValue) const {
    // Verify that our StringPool index is within encode-able limits.
    if (value.getIndex() > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    outValue->dataType = android::Res_value::TYPE_STRING;
    outValue->data = util::hostToDevice32(static_cast<uint32_t>(value.getIndex()));
    return true;
}

String* String::clone(StringPool* newPool) const {
    String* str = new String(newPool->makeRef(*value));
    str->mComment = mComment;
    str->mSource = mSource;
    return str;
}

void String::print(std::ostream* out) const {
    *out << "(string) \"" << *value << "\"";
}

StyledString::StyledString(const StringPool::StyleRef& ref) : value(ref), mTranslateable(true) {
}

void StyledString::setTranslateable(bool val) {
    mTranslateable = val;
}

bool StyledString::isTranslateable() const {
    return mTranslateable;
}

bool StyledString::flatten(android::Res_value* outValue) const {
    if (value.getIndex() > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    outValue->dataType = android::Res_value::TYPE_STRING;
    outValue->data = util::hostToDevice32(static_cast<uint32_t>(value.getIndex()));
    return true;
}

StyledString* StyledString::clone(StringPool* newPool) const {
    StyledString* str = new StyledString(newPool->makeRef(value));
    str->mComment = mComment;
    str->mSource = mSource;
    return str;
}

void StyledString::print(std::ostream* out) const {
    *out << "(styled string) \"" << *value->str << "\"";
}

FileReference::FileReference(const StringPool::Ref& _path) : path(_path) {
}

bool FileReference::flatten(android::Res_value* outValue) const {
    if (path.getIndex() > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    outValue->dataType = android::Res_value::TYPE_STRING;
    outValue->data = util::hostToDevice32(static_cast<uint32_t>(path.getIndex()));
    return true;
}

FileReference* FileReference::clone(StringPool* newPool) const {
    FileReference* fr = new FileReference(newPool->makeRef(*path));
    fr->file = file;
    fr->mComment = mComment;
    fr->mSource = mSource;
    return fr;
}

void FileReference::print(std::ostream* out) const {
    *out << "(file) " << *path;
}

BinaryPrimitive::BinaryPrimitive(const android::Res_value& val) : value(val) {
}

BinaryPrimitive::BinaryPrimitive(uint8_t dataType, uint32_t data) {
    value.dataType = dataType;
    value.data = data;
}

bool BinaryPrimitive::flatten(android::Res_value* outValue) const {
    outValue->dataType = value.dataType;
    outValue->data = util::hostToDevice32(value.data);
    return true;
}

BinaryPrimitive* BinaryPrimitive::clone(StringPool* /*newPool*/) const {
    return new BinaryPrimitive(*this);
}

void BinaryPrimitive::print(std::ostream* out) const {
    switch (value.dataType) {
        case android::Res_value::TYPE_NULL:
            *out << "(null)";
            break;
        case android::Res_value::TYPE_INT_DEC:
            *out << "(integer) " << static_cast<int32_t>(value.data);
            break;
        case android::Res_value::TYPE_INT_HEX:
            *out << "(integer) " << std::hex << value.data << std::dec;
            break;
        case android::Res_value::TYPE_INT_BOOLEAN:
            *out << "(boolean) " << (value.data != 0 ? "true" : "false");
            break;
        case android::Res_value::TYPE_INT_COLOR_ARGB8:
        case android::Res_value::TYPE_INT_COLOR_RGB8:
        case android::Res_value::TYPE_INT_COLOR_ARGB4:
        case android::Res_value::TYPE_INT_COLOR_RGB4:
            *out << "(color) #" << std::hex << value.data << std::dec;
            break;
        default:
            *out << "(unknown 0x" << std::hex << (int) value.dataType << ") 0x"
                 << std::hex << value.data << std::dec;
            break;
    }
}

Attribute::Attribute(bool w, uint32_t t) :
        typeMask(t),
        minInt(std::numeric_limits<int32_t>::min()),
        maxInt(std::numeric_limits<int32_t>::max()) {
    mWeak = w;
}

Attribute* Attribute::clone(StringPool* /*newPool*/) const {
    return new Attribute(*this);
}

void Attribute::printMask(std::ostream* out) const {
    if (typeMask == android::ResTable_map::TYPE_ANY) {
        *out << "any";
        return;
    }

    bool set = false;
    if ((typeMask & android::ResTable_map::TYPE_REFERENCE) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "reference";
    }

    if ((typeMask & android::ResTable_map::TYPE_STRING) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "string";
    }

    if ((typeMask & android::ResTable_map::TYPE_INTEGER) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "integer";
    }

    if ((typeMask & android::ResTable_map::TYPE_BOOLEAN) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "boolean";
    }

    if ((typeMask & android::ResTable_map::TYPE_COLOR) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "color";
    }

    if ((typeMask & android::ResTable_map::TYPE_FLOAT) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "float";
    }

    if ((typeMask & android::ResTable_map::TYPE_DIMENSION) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "dimension";
    }

    if ((typeMask & android::ResTable_map::TYPE_FRACTION) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "fraction";
    }

    if ((typeMask & android::ResTable_map::TYPE_ENUM) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "enum";
    }

    if ((typeMask & android::ResTable_map::TYPE_FLAGS) != 0) {
        if (!set) {
            set = true;
        } else {
            *out << "|";
        }
        *out << "flags";
    }
}

void Attribute::print(std::ostream* out) const {
    *out << "(attr) ";
    printMask(out);

    if (!symbols.empty()) {
        *out << " ["
            << util::joiner(symbols.begin(), symbols.end(), ", ")
            << "]";
    }

    if (isWeak()) {
        *out << " [weak]";
    }
}

static void buildAttributeMismatchMessage(DiagMessage* msg, const Attribute* attr,
                                          const Item* value) {
    *msg << "expected";
    if (attr->typeMask & android::ResTable_map::TYPE_BOOLEAN) {
        *msg << " boolean";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_COLOR) {
        *msg << " color";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_DIMENSION) {
        *msg << " dimension";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_ENUM) {
        *msg << " enum";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_FLAGS) {
        *msg << " flags";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_FLOAT) {
        *msg << " float";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_FRACTION) {
        *msg << " fraction";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_INTEGER) {
        *msg << " integer";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_REFERENCE) {
        *msg << " reference";
    }

    if (attr->typeMask & android::ResTable_map::TYPE_STRING) {
        *msg << " string";
    }

    *msg << " but got " << *value;
}

bool Attribute::matches(const Item* item, DiagMessage* outMsg) const {
    android::Res_value val = {};
    item->flatten(&val);

    // Always allow references.
    const uint32_t mask = typeMask | android::ResTable_map::TYPE_REFERENCE;
    if (!(mask & ResourceUtils::androidTypeToAttributeTypeMask(val.dataType))) {
        if (outMsg) {
            buildAttributeMismatchMessage(outMsg, this, item);
        }
        return false;

    } else if (ResourceUtils::androidTypeToAttributeTypeMask(val.dataType) &
            android::ResTable_map::TYPE_INTEGER) {
        if (static_cast<int32_t>(util::deviceToHost32(val.data)) < minInt) {
            if (outMsg) {
                *outMsg << *item << " is less than minimum integer " << minInt;
            }
            return false;
        } else if (static_cast<int32_t>(util::deviceToHost32(val.data)) > maxInt) {
            if (outMsg) {
                *outMsg << *item << " is greater than maximum integer " << maxInt;
            }
            return false;
        }
    }
    return true;
}

Style* Style::clone(StringPool* newPool) const {
    Style* style = new Style();
    style->parent = parent;
    style->parentInferred = parentInferred;
    style->mComment = mComment;
    style->mSource = mSource;
    for (auto& entry : entries) {
        style->entries.push_back(Entry{
                entry.key,
                std::unique_ptr<Item>(entry.value->clone(newPool))
        });
    }
    return style;
}

void Style::print(std::ostream* out) const {
    *out << "(style) ";
    if (parent && parent.value().name) {
        if (parent.value().privateReference) {
            *out << "*";
        }
        *out << parent.value().name.value();
    }
    *out << " ["
        << util::joiner(entries.begin(), entries.end(), ", ")
        << "]";
}

static ::std::ostream& operator<<(::std::ostream& out, const Style::Entry& value) {
    if (value.key.name) {
        out << value.key.name.value();
    } else {
        out << "???";
    }
    out << " = ";
    value.value->print(&out);
    return out;
}

Array* Array::clone(StringPool* newPool) const {
    Array* array = new Array();
    array->mComment = mComment;
    array->mSource = mSource;
    for (auto& item : items) {
        array->items.emplace_back(std::unique_ptr<Item>(item->clone(newPool)));
    }
    return array;
}

void Array::print(std::ostream* out) const {
    *out << "(array) ["
        << util::joiner(items.begin(), items.end(), ", ")
        << "]";
}

Plural* Plural::clone(StringPool* newPool) const {
    Plural* p = new Plural();
    p->mComment = mComment;
    p->mSource = mSource;
    const size_t count = values.size();
    for (size_t i = 0; i < count; i++) {
        if (values[i]) {
            p->values[i] = std::unique_ptr<Item>(values[i]->clone(newPool));
        }
    }
    return p;
}

void Plural::print(std::ostream* out) const {
    *out << "(plural)";
}

static ::std::ostream& operator<<(::std::ostream& out, const std::unique_ptr<Item>& item) {
    return out << *item;
}

Styleable* Styleable::clone(StringPool* /*newPool*/) const {
    return new Styleable(*this);
}

void Styleable::print(std::ostream* out) const {
    *out << "(styleable) " << " ["
        << util::joiner(entries.begin(), entries.end(), ", ")
        << "]";
}

} // namespace aapt
