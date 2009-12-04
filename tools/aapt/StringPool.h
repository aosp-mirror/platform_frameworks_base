//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef STRING_POOL_H
#define STRING_POOL_H

#include "Main.h"
#include "AaptAssets.h"

#include <utils/ResourceTypes.h>
#include <utils/String16.h>
#include <utils/TextOutput.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <ctype.h>
#include <errno.h>

#include <expat.h>

using namespace android;

#define PRINT_STRING_METRICS 0

void strcpy16_htod(uint16_t* dst, const uint16_t* src);

void printStringPool(const ResStringPool* pool);

/**
 * The StringPool class is used as an intermediate representation for
 * generating the string pool resource data structure that can be parsed with
 * ResStringPool in include/utils/ResourceTypes.h.
 */
class StringPool
{
public:
    struct entry {
        entry() : offset(0) { }
        entry(const String16& _value) : value(_value), offset(0) { }
        entry(const entry& o) : value(o.value), offset(o.offset), indices(o.indices) { }

        String16 value;
        size_t offset;
        Vector<size_t> indices;
    };

    struct entry_style_span {
        String16 name;
        ResStringPool_span span;
    };

    struct entry_style {
        entry_style() : offset(0) { }

        entry_style(const entry_style& o) : offset(o.offset), spans(o.spans) { }

        size_t offset;
        Vector<entry_style_span> spans;
    };

    /**
     * If 'sorted' is true, then the final strings in the resource data
     * structure will be generated in sorted order.  This allow for fast
     * lookup with ResStringPool::indexOfString() (O(log n)), at the expense
     * of support for styled string entries (which requires the same string
     * be included multiple times in the pool).
     *
     * If 'utf8' is true, strings will be encoded with UTF-8 instead of
     * left in Java's native UTF-16.
     */
    explicit StringPool(bool sorted = false, bool utf8 = false);

    /**
     * Add a new string to the pool.  If mergeDuplicates is true, thenif
     * the string already exists the existing entry for it will be used;
     * otherwise, or if the value doesn't already exist, a new entry is
     * created.
     *
     * Returns the index in the entry array of the new string entry.  Note that
     * if this string pool is sorted, the returned index will not be valid
     * when the pool is finally written.
     */
    ssize_t add(const String16& value, bool mergeDuplicates = false);

    ssize_t add(const String16& value, const Vector<entry_style_span>& spans);

    ssize_t add(const String16& ident, const String16& value,
                bool mergeDuplicates = false);

    status_t addStyleSpan(size_t idx, const String16& name,
                          uint32_t start, uint32_t end);
    status_t addStyleSpans(size_t idx, const Vector<entry_style_span>& spans);
    status_t addStyleSpan(size_t idx, const entry_style_span& span);

    size_t size() const;

    const entry& entryAt(size_t idx) const;

    size_t countIdentifiers() const;

    sp<AaptFile> createStringBlock();

    status_t writeStringBlock(const sp<AaptFile>& pool);

    /**
     * Find out an offset in the pool for a particular string.  If the string
     * pool is sorted, this can not be called until after createStringBlock()
     * or writeStringBlock() has been called
     * (which determines the offsets).  In the case of a string that appears
     * multiple times in the pool, the first offset will be returned.  Returns
     * -1 if the string does not exist.
     */
    ssize_t offsetForString(const String16& val) const;

    /**
     * Find all of the offsets in the pool for a particular string.  If the
     * string pool is sorted, this can not be called until after
     * createStringBlock() or writeStringBlock() has been called
     * (which determines the offsets).  Returns NULL if the string does not exist.
     */
    const Vector<size_t>* offsetsForString(const String16& val) const;

private:
    const bool                              mSorted;
    const bool                              mUTF8;
    // Raw array of unique strings, in some arbitrary order.
    Vector<entry>                           mEntries;
    // Array of indices into mEntries, in the order they were
    // added to the pool.  This can be different than mEntries
    // if the same string was added multiple times (it will appear
    // once in mEntries, with multiple occurrences in this array).
    Vector<size_t>                          mEntryArray;
    // Optional style span information associated with each index of
    // mEntryArray.
    Vector<entry_style>                     mEntryStyleArray;
    // Mapping from indices in mEntryArray to indices in mValues.
    Vector<size_t>                          mEntryArrayToValues;
    // Unique set of all the strings added to the pool, mapped to
    // the first index of mEntryArray where the value was added.
    DefaultKeyedVector<String16, ssize_t>   mValues;
    // Unique set of all (optional) identifiers of strings in the
    // pool, mapping to indices in mEntries.
    DefaultKeyedVector<String16, ssize_t>   mIdents;

};

#endif

