//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#include "StringPool.h"

#include <utils/ByteOrder.h>

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
    const size_t NS = pool->size();
    for (size_t s=0; s<NS; s++) {
        size_t len;
        const char *str = (const char*)pool->string8At(s, &len);
        if (str == NULL) {
            str = String8(pool->stringAt(s, &len)).string();
        }

        printf("String #%ld: %s\n", s, str);
    }
}

StringPool::StringPool(bool sorted, bool utf8)
    : mSorted(sorted), mUTF8(utf8), mValues(-1), mIdents(-1)
{
}

ssize_t StringPool::add(const String16& value, bool mergeDuplicates)
{
    return add(String16(), value, mergeDuplicates);
}

ssize_t StringPool::add(const String16& value, const Vector<entry_style_span>& spans)
{
    ssize_t res = add(String16(), value, false);
    if (res >= 0) {
        addStyleSpans(res, spans);
    }
    return res;
}

ssize_t StringPool::add(const String16& ident, const String16& value,
                        bool mergeDuplicates)
{
    if (ident.size() > 0) {
        ssize_t idx = mIdents.valueFor(ident);
        if (idx >= 0) {
            fprintf(stderr, "ERROR: Duplicate string identifier %s\n",
                    String8(mEntries[idx].value).string());
            return UNKNOWN_ERROR;
        }
    }

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

    const bool first = vidx < 0;
    if (first || !mergeDuplicates) {
        pos = mEntryArray.add(eidx);
        if (first) {
            vidx = mValues.add(value, pos);
            const size_t N = mEntryArrayToValues.size();
            for (size_t i=0; i<N; i++) {
                size_t& e = mEntryArrayToValues.editItemAt(i);
                if ((ssize_t)e >= vidx) {
                    e++;
                }
            }
        }
        mEntryArrayToValues.add(vidx);
        if (!mSorted) {
            entry& ent = mEntries.editItemAt(eidx);
            ent.indices.add(pos);
        }
    }

    if (ident.size() > 0) {
        mIdents.add(ident, vidx);
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
    LOG_ALWAYS_FATAL_IF(mSorted, "Can't use styles with sorted string pools.");

    // Place blank entries in the span array up to this index.
    while (mEntryStyleArray.size() <= idx) {
        mEntryStyleArray.add();
    }

    entry_style& style = mEntryStyleArray.editItemAt(idx);
    style.spans.add(span);
    return NO_ERROR;
}

size_t StringPool::size() const
{
    return mSorted ? mValues.size() : mEntryArray.size();
}

const StringPool::entry& StringPool::entryAt(size_t idx) const
{
    if (!mSorted) {
        return mEntries[mEntryArray[idx]];
    } else {
        return mEntries[mEntryArray[mValues.valueAt(idx)]];
    }
}

size_t StringPool::countIdentifiers() const
{
    return mIdents.size();
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

    const size_t ENTRIES = size();

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
    if (mSorted) {
        header->flags |= htodl(ResStringPool_header::SORTED_FLAG);
    }
    if (mUTF8) {
        header->flags |= htodl(ResStringPool_header::UTF8_FLAG);
    }
    header->stringsStart = htodl(preSize);
    header->stylesStart = htodl(STYLES > 0 ? (preSize+strPos) : 0);

    // Write string index array.

    uint32_t* index = (uint32_t*)(header+1);
    if (mSorted) {
        for (i=0; i<ENTRIES; i++) {
            entry& ent = const_cast<entry&>(entryAt(i));
            ent.indices.clear();
            ent.indices.add(i);
            *index++ = htodl(ent.offset);
        }
    } else {
        for (i=0; i<ENTRIES; i++) {
            entry& ent = mEntries.editItemAt(mEntryArray[i]);
            *index++ = htodl(ent.offset);
            NOISY(printf("Writing entry #%d: \"%s\" ent=%d off=%d\n", i,
                    String8(ent.value).string(),
                    mEntryArray[i], ent.offset));
        }
    }

    // Write style index array.

    if (mSorted) {
        for (i=0; i<STYLES; i++) {
            LOG_ALWAYS_FATAL("Shouldn't be here!");
        }
    } else {
        for (i=0; i<STYLES; i++) {
            *index++ = htodl(mEntryStyleArray[i].offset);
        }
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
