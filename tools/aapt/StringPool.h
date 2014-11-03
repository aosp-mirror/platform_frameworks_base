//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef STRING_POOL_H
#define STRING_POOL_H

#include "Main.h"
#include "AaptAssets.h"

#include <androidfw/ResourceTypes.h>
#include <utils/String16.h>
#include <utils/TypeHelpers.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <ctype.h>
#include <errno.h>

#include <libexpat/expat.h>

using namespace android;

#define PRINT_STRING_METRICS 0

#if __cplusplus >= 201103L
void strcpy16_htod(char16_t* dst, const char16_t* src);
#endif
void strcpy16_htod(uint16_t* dst, const char16_t* src);

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
        entry(const String16& _value) : value(_value), offset(0), hasStyles(false) { }
        entry(const entry& o) : value(o.value), offset(o.offset),
                hasStyles(o.hasStyles), indices(o.indices),
                configTypeName(o.configTypeName), configs(o.configs) { }

        String16 value;
        size_t offset;
        bool hasStyles;
        Vector<size_t> indices;
        String8 configTypeName;
        Vector<ResTable_config> configs;

        String8 makeConfigsString() const;

        int compare(const entry& o) const;

        inline bool operator<(const entry& o) const { return compare(o) < 0; }
        inline bool operator<=(const entry& o) const { return compare(o) <= 0; }
        inline bool operator==(const entry& o) const { return compare(o) == 0; }
        inline bool operator!=(const entry& o) const { return compare(o) != 0; }
        inline bool operator>=(const entry& o) const { return compare(o) >= 0; }
        inline bool operator>(const entry& o) const { return compare(o) > 0; }
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
     * If 'utf8' is true, strings will be encoded with UTF-8 instead of
     * left in Java's native UTF-16.
     */
    explicit StringPool(bool utf8 = false);

    /**
     * Add a new string to the pool.  If mergeDuplicates is true, thenif
     * the string already exists the existing entry for it will be used;
     * otherwise, or if the value doesn't already exist, a new entry is
     * created.
     *
     * Returns the index in the entry array of the new string entry.
     */
    ssize_t add(const String16& value, bool mergeDuplicates = false,
            const String8* configTypeName = NULL, const ResTable_config* config = NULL);

    ssize_t add(const String16& value, const Vector<entry_style_span>& spans,
            const String8* configTypeName = NULL, const ResTable_config* config = NULL);

    status_t addStyleSpan(size_t idx, const String16& name,
                          uint32_t start, uint32_t end);
    status_t addStyleSpans(size_t idx, const Vector<entry_style_span>& spans);
    status_t addStyleSpan(size_t idx, const entry_style_span& span);

    // Sort the contents of the string block by the configuration associated
    // with each item.  After doing this you can use mapOriginalPosToNewPos()
    // to find out the new position given the position originally returned by
    // add().
    void sortByConfig();

    // For use after sortByConfig() to map from the original position of
    // a string to its new sorted position.
    size_t mapOriginalPosToNewPos(size_t originalPos) const {
        return mOriginalPosToNewPos.itemAt(originalPos);
    }

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
    static int config_sort(void* state, const void* lhs, const void* rhs);

    const bool                              mUTF8;

    // The following data structures represent the actual structures
    // that will be generated for the final string pool.

    // Raw array of unique strings, in some arbitrary order.  This is the
    // actual strings that appear in the final string pool, in the order
    // that they will be written.
    Vector<entry>                           mEntries;
    // Array of indices into mEntries, in the order they were
    // added to the pool.  This can be different than mEntries
    // if the same string was added multiple times (it will appear
    // once in mEntries, with multiple occurrences in this array).
    // This is the lookup array that will be written for finding
    // the string for each offset/position in the string pool.
    Vector<size_t>                          mEntryArray;
    // Optional style span information associated with each index of
    // mEntryArray.
    Vector<entry_style>                     mEntryStyleArray;

    // The following data structures are used for book-keeping as the
    // string pool is constructed.

    // Unique set of all the strings added to the pool, mapped to
    // the first index of mEntryArray where the value was added.
    DefaultKeyedVector<String16, ssize_t>   mValues;
    // This array maps from the original position a string was placed at
    // in mEntryArray to its new position after being sorted with sortByConfig().
    Vector<size_t>                          mOriginalPosToNewPos;
};

// The entry types are trivially movable because all fields they contain, including
// the vectors and strings, are trivially movable.
namespace android {
    ANDROID_TRIVIAL_MOVE_TRAIT(StringPool::entry);
    ANDROID_TRIVIAL_MOVE_TRAIT(StringPool::entry_style_span);
    ANDROID_TRIVIAL_MOVE_TRAIT(StringPool::entry_style);
};

#endif

