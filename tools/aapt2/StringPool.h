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

#include <functional>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

#include "ConfigDescription.h"
#include "util/BigBuffer.h"

namespace aapt {

struct Span {
  std::string name;
  uint32_t first_char;
  uint32_t last_char;
};

struct StyleString {
  std::string str;
  std::vector<Span> spans;
};

class StringPool {
 public:
  class Context {
   public:
    enum : uint32_t {
      kStylePriority = 0u,
      kHighPriority = 1u,
      kNormalPriority = 0x7fffffffu,
      kLowPriority = 0xffffffffu,
    };
    uint32_t priority = kNormalPriority;
    ConfigDescription config;

    Context() = default;
    Context(uint32_t p, const ConfigDescription& c) : priority(p), config(c) {}
    explicit Context(uint32_t p) : priority(p) {}
    explicit Context(const ConfigDescription& c)
        : priority(kNormalPriority), config(c) {}
  };

  class Entry;

  class Ref {
   public:
    Ref();
    Ref(const Ref&);
    ~Ref();

    Ref& operator=(const Ref& rhs);
    bool operator==(const Ref& rhs) const;
    bool operator!=(const Ref& rhs) const;
    const std::string* operator->() const;
    const std::string& operator*() const;

    size_t index() const;
    const Context& GetContext() const;

   private:
    friend class StringPool;

    explicit Ref(Entry* entry);

    Entry* entry_;
  };

  class StyleEntry;

  class StyleRef {
   public:
    StyleRef();
    StyleRef(const StyleRef&);
    ~StyleRef();

    StyleRef& operator=(const StyleRef& rhs);
    bool operator==(const StyleRef& rhs) const;
    bool operator!=(const StyleRef& rhs) const;
    const StyleEntry* operator->() const;
    const StyleEntry& operator*() const;

    size_t index() const;
    const Context& GetContext() const;

   private:
    friend class StringPool;

    explicit StyleRef(StyleEntry* entry);

    StyleEntry* entry_;
  };

  class Entry {
   public:
    std::string value;
    Context context;
    size_t index;

   private:
    friend class StringPool;
    friend class Ref;

    int ref_;
  };

  struct Span {
    Ref name;
    uint32_t first_char;
    uint32_t last_char;
  };

  class StyleEntry {
   public:
    Ref str;
    std::vector<Span> spans;

   private:
    friend class StringPool;
    friend class StyleRef;

    int ref_;
  };

  using const_iterator = std::vector<std::unique_ptr<Entry>>::const_iterator;

  static bool FlattenUtf8(BigBuffer* out, const StringPool& pool);
  static bool FlattenUtf16(BigBuffer* out, const StringPool& pool);

  StringPool() = default;
  StringPool(StringPool&&) = default;
  StringPool& operator=(StringPool&&) = default;

  /**
   * Adds a string to the pool, unless it already exists. Returns
   * a reference to the string in the pool.
   */
  Ref MakeRef(const android::StringPiece& str);

  /**
   * Adds a string to the pool, unless it already exists, with a context
   * object that can be used when sorting the string pool. Returns
   * a reference to the string in the pool.
   */
  Ref MakeRef(const android::StringPiece& str, const Context& context);

  /**
   * Adds a style to the string pool and returns a reference to it.
   */
  StyleRef MakeRef(const StyleString& str);

  /**
   * Adds a style to the string pool with a context object that
   * can be used when sorting the string pool. Returns a reference
   * to the style in the string pool.
   */
  StyleRef MakeRef(const StyleString& str, const Context& context);

  /**
   * Adds a style from another string pool. Returns a reference to the
   * style in the string pool.
   */
  StyleRef MakeRef(const StyleRef& ref);

  /**
   * Moves pool into this one without coalescing strings. When this
   * function returns, pool will be empty.
   */
  void Merge(StringPool&& pool);

  /**
   * Returns the number of strings in the table.
   */
  inline size_t size() const;

  /**
   * Reserves space for strings and styles as an optimization.
   */
  void HintWillAdd(size_t string_count, size_t style_count);

  /**
   * Sorts the strings according to some comparison function.
   */
  void Sort(const std::function<bool(const Entry&, const Entry&)>& cmp);

  /**
   * Removes any strings that have no references.
   */
  void Prune();

 private:
  DISALLOW_COPY_AND_ASSIGN(StringPool);

  friend const_iterator begin(const StringPool& pool);
  friend const_iterator end(const StringPool& pool);

  static bool Flatten(BigBuffer* out, const StringPool& pool, bool utf8);

  Ref MakeRefImpl(const android::StringPiece& str, const Context& context, bool unique);

  std::vector<std::unique_ptr<Entry>> strings_;
  std::vector<std::unique_ptr<StyleEntry>> styles_;
  std::unordered_multimap<android::StringPiece, Entry*> indexed_strings_;
};

//
// Inline implementation
//

inline size_t StringPool::size() const { return strings_.size(); }

inline StringPool::const_iterator begin(const StringPool& pool) {
  return pool.strings_.begin();
}

inline StringPool::const_iterator end(const StringPool& pool) {
  return pool.strings_.end();
}

}  // namespace aapt

#endif  // AAPT_STRING_POOL_H
