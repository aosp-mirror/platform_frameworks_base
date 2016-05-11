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

#ifndef AAPT_RESOURCE_VALUES_H
#define AAPT_RESOURCE_VALUES_H

#include "Diagnostics.h"
#include "Resource.h"
#include "StringPool.h"
#include "io/File.h"
#include "util/Maybe.h"

#include <array>
#include <androidfw/ResourceTypes.h>
#include <ostream>
#include <vector>

namespace aapt {

struct RawValueVisitor;

/**
 * A resource value. This is an all-encompassing representation
 * of Item and Map and their subclasses. The way to do
 * type specific operations is to check the Value's type() and
 * cast it to the appropriate subclass. This isn't super clean,
 * but it is the simplest strategy.
 */
struct Value {
	virtual ~Value() = default;

    /**
     * Whether this value is weak and can be overridden without
     * warning or error. Default is false.
     */
    bool isWeak() const {
        return mWeak;
    }

    void setWeak(bool val) {
        mWeak = val;
    }

    // Whether the value is marked as translateable.
    // This does not persist when flattened.
    // It is only used during compilation phase.
    void setTranslateable(bool val) {
        mTranslateable = val;
    }

    // Default true.
    bool isTranslateable() const {
        return mTranslateable;
    }

    /**
     * Returns the source where this value was defined.
     */
    const Source& getSource() const {
        return mSource;
    }

    void setSource(const Source& source) {
        mSource = source;
    }

    void setSource(Source&& source) {
        mSource = std::move(source);
    }

    /**
     * Returns the comment that was associated with this resource.
     */
    StringPiece16 getComment() const {
        return mComment;
    }

    void setComment(const StringPiece16& str) {
        mComment = str.toString();
    }

    void setComment(std::u16string&& str) {
        mComment = std::move(str);
    }

    virtual bool equals(const Value* value) const = 0;

    /**
     * Calls the appropriate overload of ValueVisitor.
     */
    virtual void accept(RawValueVisitor* visitor) = 0;

    /**
     * Clone the value.
     */
    virtual Value* clone(StringPool* newPool) const = 0;

    /**
     * Human readable printout of this value.
     */
    virtual void print(std::ostream* out) const = 0;

protected:
    Source mSource;
    std::u16string mComment;
    bool mWeak = false;
    bool mTranslateable = true;
};

/**
 * Inherit from this to get visitor accepting implementations for free.
 */
template <typename Derived>
struct BaseValue : public Value {
    void accept(RawValueVisitor* visitor) override;
};

/**
 * A resource item with a single value. This maps to android::ResTable_entry.
 */
struct Item : public Value {
    /**
     * Clone the Item.
     */
    virtual Item* clone(StringPool* newPool) const override = 0;

    /**
     * Fills in an android::Res_value structure with this Item's binary representation.
     * Returns false if an error occurred.
     */
    virtual bool flatten(android::Res_value* outValue) const = 0;
};

/**
 * Inherit from this to get visitor accepting implementations for free.
 */
template <typename Derived>
struct BaseItem : public Item {
    void accept(RawValueVisitor* visitor) override;
};

/**
 * A reference to another resource. This maps to android::Res_value::TYPE_REFERENCE.
 *
 * A reference can be symbolic (with the name set to a valid resource name) or be
 * numeric (the id is set to a valid resource ID).
 */
struct Reference : public BaseItem<Reference> {
    enum class Type {
        kResource,
        kAttribute,
    };

    Maybe<ResourceName> name;
    Maybe<ResourceId> id;
    Reference::Type referenceType;
    bool privateReference = false;

    Reference();
    explicit Reference(const ResourceNameRef& n, Type type = Type::kResource);
    explicit Reference(const ResourceId& i, Type type = Type::kResource);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    Reference* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

/**
 * An ID resource. Has no real value, just a place holder.
 */
struct Id : public BaseItem<Id> {
    Id() { mWeak = true; }
    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* out) const override;
    Id* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

/**
 * A raw, unprocessed string. This may contain quotations,
 * escape sequences, and whitespace. This shall *NOT*
 * end up in the final resource table.
 */
struct RawString : public BaseItem<RawString> {
    StringPool::Ref value;

    RawString(const StringPool::Ref& ref);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    RawString* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct String : public BaseItem<String> {
    StringPool::Ref value;

    String(const StringPool::Ref& ref);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    String* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct StyledString : public BaseItem<StyledString> {
    StringPool::StyleRef value;

    StyledString(const StringPool::StyleRef& ref);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    StyledString* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct FileReference : public BaseItem<FileReference> {
    StringPool::Ref path;

    /**
     * A handle to the file object from which this file can be read.
     */
    io::IFile* file = nullptr;

    FileReference() = default;
    FileReference(const StringPool::Ref& path);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    FileReference* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

/**
 * Represents any other android::Res_value.
 */
struct BinaryPrimitive : public BaseItem<BinaryPrimitive> {
    android::Res_value value;

    BinaryPrimitive() = default;
    BinaryPrimitive(const android::Res_value& val);
    BinaryPrimitive(uint8_t dataType, uint32_t data);

    bool equals(const Value* value) const override;
    bool flatten(android::Res_value* outValue) const override;
    BinaryPrimitive* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct Attribute : public BaseValue<Attribute> {
    struct Symbol {
        Reference symbol;
        uint32_t value;
    };

    uint32_t typeMask;
    int32_t minInt;
    int32_t maxInt;
    std::vector<Symbol> symbols;

    Attribute(bool w, uint32_t t = 0u);

    bool equals(const Value* value) const override;
    Attribute* clone(StringPool* newPool) const override;
    void printMask(std::ostream* out) const;
    void print(std::ostream* out) const override;
    bool matches(const Item* item, DiagMessage* outMsg) const;
};

struct Style : public BaseValue<Style> {
    struct Entry {
        Reference key;
        std::unique_ptr<Item> value;
    };

    Maybe<Reference> parent;

    /**
     * If set to true, the parent was auto inferred from the
     * style's name.
     */
    bool parentInferred = false;

    std::vector<Entry> entries;

    bool equals(const Value* value) const override;
    Style* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct Array : public BaseValue<Array> {
    std::vector<std::unique_ptr<Item>> items;

    bool equals(const Value* value) const override;
    Array* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct Plural : public BaseValue<Plural> {
    enum {
        Zero = 0,
        One,
        Two,
        Few,
        Many,
        Other,
        Count
    };

    std::array<std::unique_ptr<Item>, Count> values;

    bool equals(const Value* value) const override;
    Plural* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

struct Styleable : public BaseValue<Styleable> {
    std::vector<Reference> entries;

    bool equals(const Value* value) const override;
    Styleable* clone(StringPool* newPool) const override;
    void print(std::ostream* out) const override;
};

/**
 * Stream operator for printing Value objects.
 */
inline ::std::ostream& operator<<(::std::ostream& out, const Value& value) {
    value.print(&out);
    return out;
}

inline ::std::ostream& operator<<(::std::ostream& out, const Attribute::Symbol& s) {
    if (s.symbol.name) {
        out << s.symbol.name.value().entry;
    } else {
        out << "???";
    }
    return out << "=" << s.value;
}

} // namespace aapt

#endif // AAPT_RESOURCE_VALUES_H
