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

#ifndef AAPT_VALUE_VISITOR_H
#define AAPT_VALUE_VISITOR_H

#include "ResourceValues.h"
#include "ResourceTable.h"

namespace aapt {

/**
 * Visits a value and invokes the appropriate method based on its type. Does not traverse
 * into compound types. Use ValueVisitor for that.
 */
struct RawValueVisitor {
    virtual ~RawValueVisitor() = default;

    virtual void visitItem(Item* value) {}
    virtual void visit(Reference* value) { visitItem(value); }
    virtual void visit(RawString* value) { visitItem(value); }
    virtual void visit(String* value) { visitItem(value); }
    virtual void visit(StyledString* value) { visitItem(value); }
    virtual void visit(FileReference* value) { visitItem(value); }
    virtual void visit(Id* value) { visitItem(value); }
    virtual void visit(BinaryPrimitive* value) { visitItem(value); }

    virtual void visit(Attribute* value) {}
    virtual void visit(Style* value) {}
    virtual void visit(Array* value) {}
    virtual void visit(Plural* value) {}
    virtual void visit(Styleable* value) {}
};

#define DECL_VISIT_COMPOUND_VALUE(T) \
    virtual void visit(T* value) { \
        visitSubValues(value); \
    }

/**
 * Visits values, and if they are compound values, visits the components as well.
 */
struct ValueVisitor : public RawValueVisitor {
    // The compiler will think we're hiding an overload, when we actually intend
    // to call into RawValueVisitor. This will expose the visit methods in the super
    // class so the compiler knows we are trying to call them.
    using RawValueVisitor::visit;

    void visitSubValues(Attribute* attribute) {
        for (Attribute::Symbol& symbol : attribute->symbols) {
            visit(&symbol.symbol);
        }
    }

    void visitSubValues(Style* style) {
        if (style->parent) {
            visit(&style->parent.value());
        }

        for (Style::Entry& entry : style->entries) {
            visit(&entry.key);
            entry.value->accept(this);
        }
    }

    void visitSubValues(Array* array) {
        for (std::unique_ptr<Item>& item : array->items) {
            item->accept(this);
        }
    }

    void visitSubValues(Plural* plural) {
        for (std::unique_ptr<Item>& item : plural->values) {
            if (item) {
                item->accept(this);
            }
        }
    }

    void visitSubValues(Styleable* styleable) {
        for (Reference& reference : styleable->entries) {
            visit(&reference);
        }
    }

    DECL_VISIT_COMPOUND_VALUE(Attribute);
    DECL_VISIT_COMPOUND_VALUE(Style);
    DECL_VISIT_COMPOUND_VALUE(Array);
    DECL_VISIT_COMPOUND_VALUE(Plural);
    DECL_VISIT_COMPOUND_VALUE(Styleable);
};

/**
 * Do not use directly. Helper struct for dyn_cast.
 */
template <typename T>
struct DynCastVisitor : public RawValueVisitor {
    T* value = nullptr;

    void visit(T* v) override {
        value = v;
    }
};

/**
 * Specialization that checks if the value is an Item.
 */
template <>
struct DynCastVisitor<Item> : public RawValueVisitor {
    Item* value = nullptr;

    void visitItem(Item* item) override {
        value = item;
    }
};

template <typename T>
const T* valueCast(const Value* value) {
    return valueCast<T>(const_cast<Value*>(value));
}

/**
 * Returns a valid pointer to T if the Value is of subtype T.
 * Otherwise, returns nullptr.
 */
template <typename T>
T* valueCast(Value* value) {
    if (!value) {
        return nullptr;
    }
    DynCastVisitor<T> visitor;
    value->accept(&visitor);
    return visitor.value;
}

inline void visitAllValuesInPackage(ResourceTablePackage* pkg, RawValueVisitor* visitor) {
    for (auto& type : pkg->types) {
        for (auto& entry : type->entries) {
            for (auto& configValue : entry->values) {
                configValue->value->accept(visitor);
            }
        }
    }
}

inline void visitAllValuesInTable(ResourceTable* table, RawValueVisitor* visitor) {
    for (auto& pkg : table->packages) {
        visitAllValuesInPackage(pkg.get(), visitor);
    }
}

} // namespace aapt

#endif // AAPT_VALUE_VISITOR_H
