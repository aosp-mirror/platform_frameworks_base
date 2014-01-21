//
// Copyright 2011 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef RESOURCE_FILTER_H
#define RESOURCE_FILTER_H

#include "AaptAssets.h"

/**
 * Implements logic for parsing and handling "-c" and "--preferred-configurations"
 * options.
 */
class ResourceFilter
{
public:
    ResourceFilter() : mData(), mContainsPseudo(false) {}
    status_t parse(const char* arg);
    bool isEmpty() const;
    bool match(int axis, const ResTable_config& config) const;
    bool match(const ResTable_config& config) const;
    const SortedVector<AxisValue>* configsForAxis(int axis) const;
    inline bool containsPseudo() const { return mContainsPseudo; }

private:
    bool match(int axis, const AxisValue& value) const;

    KeyedVector<int,SortedVector<AxisValue> > mData;
    bool mContainsPseudo;
};

#endif
