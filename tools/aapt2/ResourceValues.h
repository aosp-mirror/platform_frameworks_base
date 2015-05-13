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

#include "Resource.h"
#include "StringPool.h"

#include <array>
#include <androidfw/ResourceTypes.h>
#include <ostream>
#include <vector>

namespace aapt {

struct ValueVisitor;
struct ConstValueVisitor;
struct ValueVisitorArgs;

/**
 * A resource value. This is an all-encompassing representation
 * of Item and Map and their subclasses. The way to do
 * type specific operations is to check the Value's type() and
 * cast it to the appropriate subclass. This isn't super clean,
 * but it is the simplest strategy.
 */
struct Value {
    /**
     * Whether or not this is an Item.
     */
    virtual bool isItem() const;

    /**
     * Whether this value is weak and can be overriden without
     * warning or error. Default for base class is false.
     */
    virtual bool isWeak() const;

    /**
     * Calls the appropriate overload of ValueVisitor.
     */
    virtual void accept(ValueVisitor& visitor, ValueVisitorArgs&& args) = 0;

    /**
     * Const version of accept().
     */
    virtual void accept(ConstValueVisitor& visitor, ValueVisitorArgs&& args) const = 0;

    /**
     * Clone the value.
     */
    virtual Value* clone(StringPool* newPool) const = 0;

    /**
     * Human readable printout of this value.
     */
    virtual void print(std::ostream& out) const = 0;
};

/**
 * Inherit from this to get visitor accepting implementations for free.
 */
template <typename Derived>
struct BaseValue : public Value {
    virtual void accept(ValueVisitor& visitor, ValueVisitorArgs&& args) override;
    virtual void accept(ConstValueVisitor& visitor, ValueVisitorArgs&& args) const override;
};

/**
 * A resource item with a single value. This maps to android::ResTable_entry.
 */
struct Item : public Value {
    /**
     * An Item is, of course, an Item.
     */
    virtual bool isItem() const override;

    /**
     * Clone the Item.
     */
    virtual Item* clone(StringPool* newPool) const override = 0;

    /**
     * Fills in an android::Res_value structure with this Item's binary representation.
     * Returns false if an error ocurred.
     */
    virtual bool flatten(android::Res_value& outValue) const = 0;
};

/**
 * Inherit from this to get visitor accepting implementations for free.
 */
template <typename Derived>
struct BaseItem : public Item {
    virtual void accept(ValueVisitor& visitor, ValueVisitorArgs&& args) override;
    virtual void accept(ConstValueVisitor& visitor, ValueVisitorArgs&& args) const override;
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

    ResourceName name;
    ResourceId id;
    Reference::Type referenceType;
    bool privateReference = false;

    Reference();
    Reference(const ResourceNameRef& n, Type type = Type::kResource);
    Reference(const ResourceId& i, Type type = Type::kResource);

    bool flatten(android::Res_value& outValue) const override;
    Reference* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

/**
 * An ID resource. Has no real value, just a place holder.
 */
struct Id : public BaseItem<Id> {
    bool isWeak() const override;
    bool flatten(android::Res_value& out) const override;
    Id* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

/**
 * A raw, unprocessed string. This may contain quotations,
 * escape sequences, and whitespace. This shall *NOT*
 * end up in the final resource table.
 */
struct RawString : public BaseItem<RawString> {
    StringPool::Ref value;

    RawString(const StringPool::Ref& ref);

    bool flatten(android::Res_value& outValue) const override;
    RawString* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct String : public BaseItem<String> {
    StringPool::Ref value;

    String(const StringPool::Ref& ref);

    bool flatten(android::Res_value& outValue) const override;
    String* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct StyledString : public BaseItem<StyledString> {
    StringPool::StyleRef value;

    StyledString(const StringPool::StyleRef& ref);

    bool flatten(android::Res_value& outValue) const override;
    StyledString* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct FileReference : public BaseItem<FileReference> {
    StringPool::Ref path;

    FileReference() = default;
    FileReference(const StringPool::Ref& path);

    bool flatten(android::Res_value& outValue) const override;
    FileReference* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

/**
 * Represents any other android::Res_value.
 */
struct BinaryPrimitive : public BaseItem<BinaryPrimitive> {
    android::Res_value value;

    BinaryPrimitive() = default;
    BinaryPrimitive(const android::Res_value& val);

    bool flatten(android::Res_value& outValue) const override;
    BinaryPrimitive* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct Attribute : public BaseValue<Attribute> {
    struct Symbol {
        Reference symbol;
        uint32_t value;
    };

    bool weak;
    uint32_t typeMask;
    uint32_t minInt;
    uint32_t maxInt;
    std::vector<Symbol> symbols;

    Attribute(bool w, uint32_t t = 0u);

    bool isWeak() const override;
    virtual Attribute* clone(StringPool* newPool) const override;
    void printMask(std::ostream& out) const;
    virtual void print(std::ostream& out) const override;
};

struct Style : public BaseValue<Style> {
    struct Entry {
        Reference key;
        std::unique_ptr<Item> value;
    };

    Reference parent;

    /**
     * If set to true, the parent was auto inferred from the
     * style's name.
     */
    bool parentInferred = false;

    std::vector<Entry> entries;

    Style* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct Array : public BaseValue<Array> {
    std::vector<std::unique_ptr<Item>> items;

    Array* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
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

    Plural* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

struct Styleable : public BaseValue<Styleable> {
    std::vector<Reference> entries;

    Styleable* clone(StringPool* newPool) const override;
    void print(std::ostream& out) const override;
};

/**
 * Stream operator for printing Value objects.
 */
inline ::std::ostream& operator<<(::std::ostream& out, const Value& value) {
    value.print(out);
    return out;
}

inline ::std::ostream& operator<<(::std::ostream& out, const Attribute::Symbol& s) {
    return out << s.symbol.name.entry << "=" << s.value;
}

/**
 * The argument object that gets passed through the value
 * back to the ValueVisitor. Subclasses of ValueVisitor should
 * subclass ValueVisitorArgs to contain the data they need
 * to operate.
 */
struct ValueVisitorArgs {};

/**
 * Visits a value and runs the appropriate method based on its type.
 */
struct ValueVisitor {
    virtual void visit(Reference& reference, ValueVisitorArgs& args) {
        visitItem(reference, args);
    }

    virtual void visit(RawString& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(String& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(StyledString& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(FileReference& file, ValueVisitorArgs& args) {
        visitItem(file, args);
    }

    virtual void visit(Id& id, ValueVisitorArgs& args) {
        visitItem(id, args);
    }

    virtual void visit(BinaryPrimitive& primitive, ValueVisitorArgs& args) {
        visitItem(primitive, args);
    }

    virtual void visit(Attribute& attr, ValueVisitorArgs& args) {}
    virtual void visit(Style& style, ValueVisitorArgs& args) {}
    virtual void visit(Array& array, ValueVisitorArgs& args) {}
    virtual void visit(Plural& array, ValueVisitorArgs& args) {}
    virtual void visit(Styleable& styleable, ValueVisitorArgs& args) {}

    virtual void visitItem(Item& item, ValueVisitorArgs& args) {}
};

/**
 * Const version of ValueVisitor.
 */
struct ConstValueVisitor {
    virtual void visit(const Reference& reference, ValueVisitorArgs& args) {
        visitItem(reference, args);
    }

    virtual void visit(const RawString& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(const String& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(const StyledString& string, ValueVisitorArgs& args) {
        visitItem(string, args);
    }

    virtual void visit(const FileReference& file, ValueVisitorArgs& args) {
        visitItem(file, args);
    }

    virtual void visit(const Id& id, ValueVisitorArgs& args) {
        visitItem(id, args);
    }

    virtual void visit(const BinaryPrimitive& primitive, ValueVisitorArgs& args) {
        visitItem(primitive, args);
    }

    virtual void visit(const Attribute& attr, ValueVisitorArgs& args) {}
    virtual void visit(const Style& style, ValueVisitorArgs& args) {}
    virtual void visit(const Array& array, ValueVisitorArgs& args) {}
    virtual void visit(const Plural& array, ValueVisitorArgs& args) {}
    virtual void visit(const Styleable& styleable, ValueVisitorArgs& args) {}

    virtual void visitItem(const Item& item, ValueVisitorArgs& args) {}
};

/**
 * Convenience Visitor that forwards a specific type to a function.
 * Args are not used as the function can bind variables. Do not use
 * directly, use the wrapper visitFunc() method.
 */
template <typename T, typename TFunc>
struct ValueVisitorFunc : ValueVisitor {
    TFunc func;

    ValueVisitorFunc(TFunc f) : func(f) {
    }

    void visit(T& value, ValueVisitorArgs&) override {
        func(value);
    }
};

/**
 * Const version of ValueVisitorFunc.
 */
template <typename T, typename TFunc>
struct ConstValueVisitorFunc : ConstValueVisitor {
    TFunc func;

    ConstValueVisitorFunc(TFunc f) : func(f) {
    }

    void visit(const T& value, ValueVisitorArgs&) override {
        func(value);
    }
};

template <typename T, typename TFunc>
void visitFunc(Value& value, TFunc f) {
    ValueVisitorFunc<T, TFunc> visitor(f);
    value.accept(visitor, ValueVisitorArgs{});
}

template <typename T, typename TFunc>
void visitFunc(const Value& value, TFunc f) {
    ConstValueVisitorFunc<T, TFunc> visitor(f);
    value.accept(visitor, ValueVisitorArgs{});
}

template <typename Derived>
void BaseValue<Derived>::accept(ValueVisitor& visitor, ValueVisitorArgs&& args) {
    visitor.visit(static_cast<Derived&>(*this), args);
}

template <typename Derived>
void BaseValue<Derived>::accept(ConstValueVisitor& visitor, ValueVisitorArgs&& args) const {
    visitor.visit(static_cast<const Derived&>(*this), args);
}

template <typename Derived>
void BaseItem<Derived>::accept(ValueVisitor& visitor, ValueVisitorArgs&& args) {
    visitor.visit(static_cast<Derived&>(*this), args);
}

template <typename Derived>
void BaseItem<Derived>::accept(ConstValueVisitor& visitor, ValueVisitorArgs&& args) const {
    visitor.visit(static_cast<const Derived&>(*this), args);
}

} // namespace aapt

#endif // AAPT_RESOURCE_VALUES_H
