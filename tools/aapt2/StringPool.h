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

#ifndef AAPT_STRING_POOL_H
#define AAPT_STRING_POOL_H

#include "util/BigBuffer.h"
#include "ConfigDescription.h"
#include "util/StringPiece.h"

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace aapt {

struct Span {
    std::u16string name;
    uint32_t firstChar;
    uint32_t lastChar;
};

struct StyleString {
    std::u16string str;
    std::vector<Span> spans;
};

class StringPool {
public:
    struct Context {
        uint32_t priority;
        ConfigDescription config;
    };

    class Entry;

    class Ref {
    public:
        Ref();
        Ref(const Ref&);
        ~Ref();

        Ref& operator=(const Ref& rhs);
        const std::u16string* operator->() const;
        const std::u16string& operator*() const;

        size_t getIndex() const;
        const Context& getContext() const;

    private:
        friend class StringPool;

        Ref(Entry* entry);

        Entry* mEntry;
    };

    class StyleEntry;

    class StyleRef {
    public:
        StyleRef();
        StyleRef(const StyleRef&);
        ~StyleRef();

        StyleRef& operator=(const StyleRef& rhs);
        const StyleEntry* operator->() const;
        const StyleEntry& operator*() const;

        size_t getIndex() const;
        const Context& getContext() const;

    private:
        friend class StringPool;

        StyleRef(StyleEntry* entry);

        StyleEntry* mEntry;
    };

    class Entry {
    public:
        std::u16string value;
        Context context;
        size_t index;

    private:
        friend class StringPool;
        friend class Ref;

        int ref;
    };

    struct Span {
        Ref name;
        uint32_t firstChar;
        uint32_t lastChar;
    };

    class StyleEntry {
    public:
        Ref str;
        std::vector<Span> spans;

    private:
        friend class StringPool;
        friend class StyleRef;

        int ref;
    };

    using const_iterator = std::vector<std::unique_ptr<Entry>>::const_iterator;

    static bool flattenUtf8(BigBuffer* out, const StringPool& pool);
    static bool flattenUtf16(BigBuffer* out, const StringPool& pool);

    StringPool() = default;
    StringPool(const StringPool&) = delete;

    /**
     * Adds a string to the pool, unless it already exists. Returns
     * a reference to the string in the pool.
     */
    Ref makeRef(const StringPiece16& str);

    /**
     * Adds a string to the pool, unless it already exists, with a context
     * object that can be used when sorting the string pool. Returns
     * a reference to the string in the pool.
     */
    Ref makeRef(const StringPiece16& str, const Context& context);

    /**
     * Adds a style to the string pool and returns a reference to it.
     */
    StyleRef makeRef(const StyleString& str);

    /**
     * Adds a style to the string pool with a context object that
     * can be used when sorting the string pool. Returns a reference
     * to the style in the string pool.
     */
    StyleRef makeRef(const StyleString& str, const Context& context);

    /**
     * Adds a style from another string pool. Returns a reference to the
     * style in the string pool.
     */
    StyleRef makeRef(const StyleRef& ref);

    /**
     * Moves pool into this one without coalescing strings. When this
     * function returns, pool will be empty.
     */
    void merge(StringPool&& pool);

    /**
     * Retuns the number of strings in the table.
     */
    inline size_t size() const;

    /**
     * Reserves space for strings and styles as an optimization.
     */
    void hintWillAdd(size_t stringCount, size_t styleCount);

    /**
     * Sorts the strings according to some comparison function.
     */
    void sort(const std::function<bool(const Entry&, const Entry&)>& cmp);

    /**
     * Removes any strings that have no references.
     */
    void prune();

private:
    friend const_iterator begin(const StringPool& pool);
    friend const_iterator end(const StringPool& pool);

    static bool flatten(BigBuffer* out, const StringPool& pool, bool utf8);

    Ref makeRefImpl(const StringPiece16& str, const Context& context, bool unique);

    std::vector<std::unique_ptr<Entry>> mStrings;
    std::vector<std::unique_ptr<StyleEntry>> mStyles;
    std::multimap<StringPiece16, Entry*> mIndexedStrings;
};

//
// Inline implementation
//

inline size_t StringPool::size() const {
    return mStrings.size();
}

inline StringPool::const_iterator begin(const StringPool& pool) {
    return pool.mStrings.begin();
}

inline StringPool::const_iterator end(const StringPool& pool) {
    return pool.mStrings.end();
}

} // namespace aapt

#endif // AAPT_STRING_POOL_H
