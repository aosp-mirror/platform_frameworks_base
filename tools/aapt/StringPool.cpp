//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#include "StringPool.h"
#include "ResourceTable.h"

#include <utils/ByteOrder.h>
#include <utils/SortedVector.h>
#include "qsort_r_compat.h"

#if HAVE_PRINTF_ZD
#  define ZD "%zd"
#  define ZD_TYPE ssize_t
#else
#  define ZD "%ld"
#  define ZD_TYPE long
#endif

#define NOISY(x) //x

void strcpy16_htod(uint16_t* dst, const uint16_t* src)
{
    while (*src) {
        char16_t s = htods(*src);
        *dst++ = s;
        src++;
    }
    *dst = 0;
}

void printStringPool(const ResStringPool* pool)
{
    SortedVector<const void*> uniqueStrings;
    const size_t N = pool->size();
    for (size_t i=0; i<N; i++) {
        size_t len;
        if (pool->isUTF8()) {
            uniqueStrings.add(pool->string8At(i, &len));
        } else {
            uniqueStrings.add(pool->stringAt(i, &len));
        }
    }

    printf("String pool of " ZD " unique %s %s strings, " ZD " entries and "
            ZD " styles using " ZD " bytes:\n",
            (ZD_TYPE)uniqueStrings.size(), pool->isUTF8() ? "UTF-8" : "UTF-16",
            pool->isSorted() ? "sorted" : "non-sorted",
            (ZD_TYPE)N, (ZD_TYPE)pool->styleCount(), (ZD_TYPE)pool->bytes());

    const size_t NS = pool->size();
    for (size_t s=0; s<NS; s++) {
        String8 str = pool->string8ObjectAt(s);
        printf("String #" ZD ": %s\n", (ZD_TYPE) s, str.string());
    }
}

String8 StringPool::entry::makeConfigsString() const {
    String8 configStr(configTypeName);
    if (configStr.size() > 0) configStr.append(" ");
    if (configs.size() > 0) {
        for (size_t j=0; j<configs.size(); j++) {
            if (j > 0) configStr.append(", ");
            configStr.append(configs[j].toString());
        }
    } else {
        configStr = "(none)";
    }
    return configStr;
}

int StringPool::entry::compare(const entry& o) const {
    // Strings with styles go first, to reduce the size of the styles array.
    // We don't care about the relative order of these strings.
    if (hasStyles) {
        return o.hasStyles ? 0 : -1;
    }
    if (o.hasStyles) {
        return 1;
    }

    // Sort unstyled strings by type, then by logical configuration.
    int comp = configTypeName.compare(o.configTypeName);
    if (comp != 0) {
        return comp;
    }
    const size_t LHN = configs.size();
    const size_t RHN = o.configs.size();
    size_t i=0;
    while (i < LHN && i < RHN) {
        comp = configs[i].compareLogical(o.configs[i]);
        if (comp != 0) {
            return comp;
        }
        i++;
    }
    if (LHN < RHN) return -1;
    else if (LHN > RHN) return 1;
    return 0;
}

StringPool::StringPool(bool utf8) :
        mUTF8(utf8), mValues(-1)
{
}

ssize_t StringPool::add(const String16& value, const Vector<entry_style_span>& spans,
        const String8* configTypeName, const ResTable_config* config)
{
    ssize_t res = add(value, false, configTypeName, config);
    if (res >= 0) {
        addStyleSpans(res, spans);
    }
    return res;
}

ssize_t StringPool::add(const String16& value,
        bool mergeDuplicates, const String8* configTypeName, const ResTable_config* config)
{
    ssize_t vidx = mValues.indexOfKey(value);
    ssize_t pos = vidx >= 0 ? mValues.valueAt(vidx) : -1;
    ssize_t eidx = pos >= 0 ? mEntryArray.itemAt(pos) : -1;
    if (eidx < 0) {
        eidx = mEntries.add(entry(value));
        if (eidx < 0) {
            fprintf(stderr, "Failure adding string %s\n", String8(value).string());
            return eidx;
        }
    }

    if (configTypeName != NULL) {
        entry& ent = mEntries.editItemAt(eidx);
        NOISY(printf("*** adding config type name %s, was %s\n",
                configTypeName->string(), ent.configTypeName.string()));
        if (ent.configTypeName.size() <= 0) {
            ent.configTypeName = *configTypeName;
        } else if (ent.configTypeName != *configTypeName) {
            ent.configTypeName = " ";
        }
    }

    if (config != NULL) {
        // Add this to the set of configs associated with the string.
        entry& ent = mEntries.editItemAt(eidx);
        size_t addPos;
        for (addPos=0; addPos<ent.configs.size(); addPos++) {
            int cmp = ent.configs.itemAt(addPos).compareLogical(*config);
            if (cmp >= 0) {
                if (cmp > 0) {
                    NOISY(printf("*** inserting config: %s\n", config->toString().string()));
                    ent.configs.insertAt(*config, addPos);
                }
                break;
            }
        }
        if (addPos >= ent.configs.size()) {
            NOISY(printf("*** adding config: %s\n", config->toString().string()));
            ent.configs.add(*config);
        }
    }

    const bool first = vidx < 0;
    const bool styled = (pos >= 0 && (size_t)pos < mEntryStyleArray.size()) ?
        mEntryStyleArray[pos].spans.size() : 0;
    if (first || styled || !mergeDuplicates) {
        pos = mEntryArray.add(eidx);
        if (first) {
            vidx = mValues.add(value, pos);
        }
        entry& ent = mEntries.editItemAt(eidx);
        ent.indices.add(pos);
    }

    NOISY(printf("Adding string %s to pool: pos=%d eidx=%d vidx=%d\n",
            String8(value).string(), pos, eidx, vidx));
    
    return pos;
}

status_t StringPool::addStyleSpan(size_t idx, const String16& name,
                                  uint32_t start, uint32_t end)
{
    entry_style_span span;
    span.name = name;
    span.span.firstChar = start;
    span.span.lastChar = end;
    return addStyleSpan(idx, span);
}

status_t StringPool::addStyleSpans(size_t idx, const Vector<entry_style_span>& spans)
{
    const size_t N=spans.size();
    for (size_t i=0; i<N; i++) {
        status_t err = addStyleSpan(idx, spans[i]);
        if (err != NO_ERROR) {
            return err;
        }
    }
    return NO_ERROR;
}

status_t StringPool::addStyleSpan(size_t idx, const entry_style_span& span)
{
    // Place blank entries in the span array up to this index.
    while (mEntryStyleArray.size() <= idx) {
        mEntryStyleArray.add();
    }

    entry_style& style = mEntryStyleArray.editItemAt(idx);
    style.spans.add(span);
    mEntries.editItemAt(mEntryArray[idx]).hasStyles = true;
    return NO_ERROR;
}

int StringPool::config_sort(void* state, const void* lhs, const void* rhs)
{
    StringPool* pool = (StringPool*)state;
    const entry& lhe = pool->mEntries[pool->mEntryArray[*static_cast<const size_t*>(lhs)]];
    const entry& rhe = pool->mEntries[pool->mEntryArray[*static_cast<const size_t*>(rhs)]];
    return lhe.compare(rhe);
}

void StringPool::sortByConfig()
{
    LOG_ALWAYS_FATAL_IF(mOriginalPosToNewPos.size() > 0, "Can't sort string pool after already sorted.");

    const size_t N = mEntryArray.size();

    // This is a vector that starts out with a 1:1 mapping to entries
    // in the array, which we will sort to come up with the desired order.
    // At that point it maps from the new position in the array to the
    // original position the entry appeared.
    Vector<size_t> newPosToOriginalPos;
    newPosToOriginalPos.setCapacity(N);
    for (size_t i=0; i < N; i++) {
        newPosToOriginalPos.add(i);
    }

    // Sort the array.
    NOISY(printf("SORTING STRINGS BY CONFIGURATION...\n"));
    // Vector::sort uses insertion sort, which is very slow for this data set.
    // Use quicksort instead because we don't need a stable sort here.
    qsort_r_compat(newPosToOriginalPos.editArray(), N, sizeof(size_t), this, config_sort);
    //newPosToOriginalPos.sort(config_sort, this);
    NOISY(printf("DONE SORTING STRINGS BY CONFIGURATION.\n"));

    // Create the reverse mapping from the original position in the array
    // to the new position where it appears in the sorted array.  This is
    // so that clients can re-map any positions they had previously stored.
    mOriginalPosToNewPos = newPosToOriginalPos;
    for (size_t i=0; i<N; i++) {
        mOriginalPosToNewPos.editItemAt(newPosToOriginalPos[i]) = i;
    }

#if 0
    SortedVector<entry> entries;

    for (size_t i=0; i<N; i++) {
        printf("#%d was %d: %s\n", i, newPosToOriginalPos[i],
                mEntries[mEntryArray[newPosToOriginalPos[i]]].makeConfigsString().string());
        entries.add(mEntries[mEntryArray[i]]);
    }

    for (size_t i=0; i<entries.size(); i++) {
        printf("Sorted config #%d: %s\n", i,
                entries[i].makeConfigsString().string());
    }
#endif

    // Now we rebuild the arrays.
    Vector<entry> newEntries;
    Vector<size_t> newEntryArray;
    Vector<entry_style> newEntryStyleArray;
    DefaultKeyedVector<size_t, size_t> origOffsetToNewOffset;

    for (size_t i=0; i<N; i++) {
        // We are filling in new offset 'i'; oldI is where we can find it
        // in the original data structure.
        size_t oldI = newPosToOriginalPos[i];
        // This is the actual entry associated with the old offset.
        const entry& oldEnt = mEntries[mEntryArray[oldI]];
        // This is the same entry the last time we added it to the
        // new entry array, if any.
        ssize_t newIndexOfOffset = origOffsetToNewOffset.indexOfKey(oldI);
        size_t newOffset;
        if (newIndexOfOffset < 0) {
            // This is the first time we have seen the entry, so add
            // it.
            newOffset = newEntries.add(oldEnt);
            newEntries.editItemAt(newOffset).indices.clear();
        } else {
            // We have seen this entry before, use the existing one
            // instead of adding it again.
            newOffset = origOffsetToNewOffset.valueAt(newIndexOfOffset);
        }
        // Update the indices to include this new position.
        newEntries.editItemAt(newOffset).indices.add(i);
        // And add the offset of the entry to the new entry array.
        newEntryArray.add(newOffset);
        // Add any old style to the new style array.
        if (mEntryStyleArray.size() > 0) {
            if (oldI < mEntryStyleArray.size()) {
                newEntryStyleArray.add(mEntryStyleArray[oldI]);
            } else {
                newEntryStyleArray.add(entry_style());
            }
        }
    }

    // Now trim any entries at the end of the new style array that are
    // not needed.
    for (ssize_t i=newEntryStyleArray.size()-1; i>=0; i--) {
        const entry_style& style = newEntryStyleArray[i];
        if (style.spans.size() > 0) {
            // That's it.
            break;
        }
        // This one is not needed; remove.
        newEntryStyleArray.removeAt(i);
    }

    // All done, install the new data structures and upate mValues with
    // the new positions.
    mEntries = newEntries;
    mEntryArray = newEntryArray;
    mEntryStyleArray = newEntryStyleArray;
    mValues.clear();
    for (size_t i=0; i<mEntries.size(); i++) {
        const entry& ent = mEntries[i];
        mValues.add(ent.value, ent.indices[0]);
    }

#if 0
    printf("FINAL SORTED STRING CONFIGS:\n");
    for (size_t i=0; i<mEntries.size(); i++) {
        const entry& ent = mEntries[i];
        printf("#" ZD " %s: %s\n", (ZD_TYPE)i, ent.makeConfigsString().string(),
                String8(ent.value).string());
    }
#endif
}

sp<AaptFile> StringPool::createStringBlock()
{
    sp<AaptFile> pool = new AaptFile(String8(), AaptGroupEntry(),
                                     String8());
    status_t err = writeStringBlock(pool);
    return err == NO_ERROR ? pool : NULL;
}

#define ENCODE_LENGTH(str, chrsz, strSize) \
{ \
    size_t maxMask = 1 << ((chrsz*8)-1); \
    size_t maxSize = maxMask-1; \
    if (strSize > maxSize) { \
        *str++ = maxMask | ((strSize>>(chrsz*8))&maxSize); \
    } \
    *str++ = strSize; \
}

status_t StringPool::writeStringBlock(const sp<AaptFile>& pool)
{
    // Allow appending.  Sorry this is a little wacky.
    if (pool->getSize() > 0) {
        sp<AaptFile> block = createStringBlock();
        if (block == NULL) {
            return UNKNOWN_ERROR;
        }
        ssize_t res = pool->writeData(block->getData(), block->getSize());
        return (res >= 0) ? (status_t)NO_ERROR : res;
    }

    // First we need to add all style span names to the string pool.
    // We do this now (instead of when the span is added) so that these
    // will appear at the end of the pool, not disrupting the order
    // our client placed their own strings in it.
    
    const size_t STYLES = mEntryStyleArray.size();
    size_t i;

    for (i=0; i<STYLES; i++) {
        entry_style& style = mEntryStyleArray.editItemAt(i);
        const size_t N = style.spans.size();
        for (size_t i=0; i<N; i++) {
            entry_style_span& span = style.spans.editItemAt(i);
            ssize_t idx = add(span.name, true);
            if (idx < 0) {
                fprintf(stderr, "Error adding span for style tag '%s'\n",
                        String8(span.name).string());
                return idx;
            }
            span.span.name.index = (uint32_t)idx;
        }
    }

    const size_t ENTRIES = mEntryArray.size();

    // Now build the pool of unique strings.

    const size_t STRINGS = mEntries.size();
    const size_t preSize = sizeof(ResStringPool_header)
                         + (sizeof(uint32_t)*ENTRIES)
                         + (sizeof(uint32_t)*STYLES);
    if (pool->editData(preSize) == NULL) {
        fprintf(stderr, "ERROR: Out of memory for string pool\n");
        return NO_MEMORY;
    }

    const size_t charSize = mUTF8 ? sizeof(uint8_t) : sizeof(char16_t);

    size_t strPos = 0;
    for (i=0; i<STRINGS; i++) {
        entry& ent = mEntries.editItemAt(i);
        const size_t strSize = (ent.value.size());
        const size_t lenSize = strSize > (size_t)(1<<((charSize*8)-1))-1 ?
            charSize*2 : charSize;

        String8 encStr;
        if (mUTF8) {
            encStr = String8(ent.value);
        }

        const size_t encSize = mUTF8 ? encStr.size() : 0;
        const size_t encLenSize = mUTF8 ?
            (encSize > (size_t)(1<<((charSize*8)-1))-1 ?
                charSize*2 : charSize) : 0;

        ent.offset = strPos;

        const size_t totalSize = lenSize + encLenSize +
            ((mUTF8 ? encSize : strSize)+1)*charSize;

        void* dat = (void*)pool->editData(preSize + strPos + totalSize);
        if (dat == NULL) {
            fprintf(stderr, "ERROR: Out of memory for string pool\n");
            return NO_MEMORY;
        }
        dat = (uint8_t*)dat + preSize + strPos;
        if (mUTF8) {
            uint8_t* strings = (uint8_t*)dat;

            ENCODE_LENGTH(strings, sizeof(uint8_t), strSize)

            ENCODE_LENGTH(strings, sizeof(uint8_t), encSize)

            strncpy((char*)strings, encStr, encSize+1);
        } else {
            uint16_t* strings = (uint16_t*)dat;

            ENCODE_LENGTH(strings, sizeof(uint16_t), strSize)

            strcpy16_htod(strings, ent.value);
        }

        strPos += totalSize;
    }

    // Pad ending string position up to a uint32_t boundary.

    if (strPos&0x3) {
        size_t padPos = ((strPos+3)&~0x3);
        uint8_t* dat = (uint8_t*)pool->editData(preSize + padPos);
        if (dat == NULL) {
            fprintf(stderr, "ERROR: Out of memory padding string pool\n");
            return NO_MEMORY;
        }
        memset(dat+preSize+strPos, 0, padPos-strPos);
        strPos = padPos;
    }

    // Build the pool of style spans.

    size_t styPos = strPos;
    for (i=0; i<STYLES; i++) {
        entry_style& ent = mEntryStyleArray.editItemAt(i);
        const size_t N = ent.spans.size();
        const size_t totalSize = (N*sizeof(ResStringPool_span))
                               + sizeof(ResStringPool_ref);

        ent.offset = styPos-strPos;
        uint8_t* dat = (uint8_t*)pool->editData(preSize + styPos + totalSize);
        if (dat == NULL) {
            fprintf(stderr, "ERROR: Out of memory for string styles\n");
            return NO_MEMORY;
        }
        ResStringPool_span* span = (ResStringPool_span*)(dat+preSize+styPos);
        for (size_t i=0; i<N; i++) {
            span->name.index = htodl(ent.spans[i].span.name.index);
            span->firstChar = htodl(ent.spans[i].span.firstChar);
            span->lastChar = htodl(ent.spans[i].span.lastChar);
            span++;
        }
        span->name.index = htodl(ResStringPool_span::END);

        styPos += totalSize;
    }

    if (STYLES > 0) {
        // Add full terminator at the end (when reading we validate that
        // the end of the pool is fully terminated to simplify error
        // checking).
        size_t extra = sizeof(ResStringPool_span)-sizeof(ResStringPool_ref);
        uint8_t* dat = (uint8_t*)pool->editData(preSize + styPos + extra);
        if (dat == NULL) {
            fprintf(stderr, "ERROR: Out of memory for string styles\n");
            return NO_MEMORY;
        }
        uint32_t* p = (uint32_t*)(dat+preSize+styPos);
        while (extra > 0) {
            *p++ = htodl(ResStringPool_span::END);
            extra -= sizeof(uint32_t);
        }
        styPos += extra;
    }

    // Write header.

    ResStringPool_header* header =
        (ResStringPool_header*)pool->padData(sizeof(uint32_t));
    if (header == NULL) {
        fprintf(stderr, "ERROR: Out of memory for string pool\n");
        return NO_MEMORY;
    }
    memset(header, 0, sizeof(*header));
    header->header.type = htods(RES_STRING_POOL_TYPE);
    header->header.headerSize = htods(sizeof(*header));
    header->header.size = htodl(pool->getSize());
    header->stringCount = htodl(ENTRIES);
    header->styleCount = htodl(STYLES);
    if (mUTF8) {
        header->flags |= htodl(ResStringPool_header::UTF8_FLAG);
    }
    header->stringsStart = htodl(preSize);
    header->stylesStart = htodl(STYLES > 0 ? (preSize+strPos) : 0);

    // Write string index array.

    uint32_t* index = (uint32_t*)(header+1);
    for (i=0; i<ENTRIES; i++) {
        entry& ent = mEntries.editItemAt(mEntryArray[i]);
        *index++ = htodl(ent.offset);
        NOISY(printf("Writing entry #%d: \"%s\" ent=%d off=%d\n", i,
                String8(ent.value).string(),
                mEntryArray[i], ent.offset));
    }

    // Write style index array.

    for (i=0; i<STYLES; i++) {
        *index++ = htodl(mEntryStyleArray[i].offset);
    }

    return NO_ERROR;
}

ssize_t StringPool::offsetForString(const String16& val) const
{
    const Vector<size_t>* indices = offsetsForString(val);
    ssize_t res = indices != NULL && indices->size() > 0 ? indices->itemAt(0) : -1;
    NOISY(printf("Offset for string %s: %d (%s)\n", String8(val).string(), res,
            res >= 0 ? String8(mEntries[mEntryArray[res]].value).string() : String8()));
    return res;
}

const Vector<size_t>* StringPool::offsetsForString(const String16& val) const
{
    ssize_t pos = mValues.valueFor(val);
    if (pos < 0) {
        return NULL;
    }
    return &mEntries[mEntryArray[pos]].indices;
}
