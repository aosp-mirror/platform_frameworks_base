//
// Copyright 2011 The Android Open Source Project
//
// Build resource files from raw assets.
//

#include "ResourceFilter.h"

status_t
ResourceFilter::parse(const char* arg)
{
    if (arg == NULL) {
        return 0;
    }

    const char* p = arg;
    const char* q;

    while (true) {
        q = strchr(p, ',');
        if (q == NULL) {
            q = p + strlen(p);
        }

        String8 part(p, q-p);

        if (part == "zz_ZZ") {
            mContainsPseudo = true;
        }
        int axis;
        uint32_t value;
        if (AaptGroupEntry::parseNamePart(part, &axis, &value)) {
            fprintf(stderr, "Invalid configuration: %s\n", arg);
            fprintf(stderr, "                       ");
            for (int i=0; i<p-arg; i++) {
                fprintf(stderr, " ");
            }
            for (int i=0; i<q-p; i++) {
                fprintf(stderr, "^");
            }
            fprintf(stderr, "\n");
            return 1;
        }

        ssize_t index = mData.indexOfKey(axis);
        if (index < 0) {
            mData.add(axis, SortedVector<uint32_t>());
        }
        SortedVector<uint32_t>& sv = mData.editValueFor(axis);
        sv.add(value);
        // if it's a locale with a region, also match an unmodified locale of the
        // same language
        if (axis == AXIS_LANGUAGE) {
            if (value & 0xffff0000) {
                sv.add(value & 0x0000ffff);
            }
        }
        p = q;
        if (!*p) break;
        p++;
    }

    return NO_ERROR;
}

bool
ResourceFilter::isEmpty() const
{
    return mData.size() == 0;
}

bool
ResourceFilter::match(int axis, uint32_t value) const
{
    if (value == 0) {
        // they didn't specify anything so take everything
        return true;
    }
    ssize_t index = mData.indexOfKey(axis);
    if (index < 0) {
        // we didn't request anything on this axis so take everything
        return true;
    }
    const SortedVector<uint32_t>& sv = mData.valueAt(index);
    return sv.indexOf(value) >= 0;
}

bool
ResourceFilter::match(int axis, const ResTable_config& config) const
{
    return match(axis, AaptGroupEntry::getConfigValueForAxis(config, axis));
}

bool
ResourceFilter::match(const ResTable_config& config) const
{
    for (int i=AXIS_START; i<=AXIS_END; i++) {
        if (!match(i, AaptGroupEntry::getConfigValueForAxis(config, i))) {
            return false;
        }
    }
    return true;
}

const SortedVector<uint32_t>* ResourceFilter::configsForAxis(int axis) const
{
    ssize_t index = mData.indexOfKey(axis);
    if (index < 0) {
        return NULL;
    }
    return &mData.valueAt(index);
}
