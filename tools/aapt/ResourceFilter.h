//
// Copyright 2011 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef RESOURCE_FILTER_H
#define RESOURCE_FILTER_H

#include <androidfw/ResourceTypes.h>
#include <set>
#include <utility>
#include <utils/Errors.h>
#include <utils/String8.h>
#include <utils/StrongPointer.h>
#include <utils/Vector.h>

#include "AaptAssets.h"
#include "ConfigDescription.h"

class ResourceFilter : public virtual android::RefBase {
public:
    virtual bool match(const android::ResTable_config& config) const = 0;
};

/**
 * Implements logic for parsing and handling "-c" options.
 */
class WeakResourceFilter : public ResourceFilter {
public:
    WeakResourceFilter()
        : mContainsPseudoAccented(false)
        , mContainsPseudoBidi(false) {}

    android::status_t parse(const android::String8& str);

    bool match(const android::ResTable_config& config) const;

    inline bool isEmpty() const {
        return mConfigMask == 0;
    }

    inline bool containsPseudo() const {
        return mContainsPseudoAccented;
    }

    inline bool containsPseudoBidi() const {
        return mContainsPseudoBidi;
    }

private:
    ConfigDescription mDefault;
    uint32_t mConfigMask;
    android::Vector<std::pair<ConfigDescription, uint32_t> > mConfigs;

    bool mContainsPseudoAccented;
    bool mContainsPseudoBidi;
};

/**
 * Matches resources that have at least one of the configurations
 * that this filter is looking for. In order to match a configuration,
 * the resource must have the exact same configuration.
 *
 * This filter acts as a logical OR when matching resources.
 *
 * For example, if the filter is looking for resources with
 * fr-land, de-land, or sw600dp:
 *
 * (PASS) fr-land
 * (FAIL) fr
 * (PASS) de-land
 * (FAIL) de
 * (FAIL) de-sw600dp
 * (PASS) sw600dp
 * (FAIL) sw600dp-land
 */
class StrongResourceFilter : public ResourceFilter {
public:
    StrongResourceFilter() {}
    explicit StrongResourceFilter(const std::set<ConfigDescription>& configs)
        : mConfigs(configs) {}

    android::status_t parse(const android::String8& str);

    bool match(const android::ResTable_config& config) const {
        std::set<ConfigDescription>::const_iterator iter = mConfigs.begin();
        for (; iter != mConfigs.end(); iter++) {
            if (iter->compare(config) == 0) {
                return true;
            }
        }
        return false;
    }

    inline const std::set<ConfigDescription>& getConfigs() const {
        return mConfigs;
    }

private:
    std::set<ConfigDescription> mConfigs;
};

/**
 * Negates the response of the target filter.
 */
class InverseResourceFilter : public ResourceFilter {
public:
    explicit InverseResourceFilter(const android::sp<ResourceFilter>& filter)
        : mFilter(filter) {}

    bool match(const android::ResTable_config& config) const {
        return !mFilter->match(config);
    }

private:
    const android::sp<ResourceFilter> mFilter;
};

/**
 * A logical AND of all the added filters.
 */
class AndResourceFilter : public ResourceFilter {
public:
    void addFilter(const android::sp<ResourceFilter>& filter) {
        mFilters.add(filter);
    }

    bool match(const android::ResTable_config& config) const {
        const size_t N = mFilters.size();
        for (size_t i = 0; i < N; i++) {
            if (!mFilters[i]->match(config)) {
                return false;
            }
        }
        return true;
    }

private:
    android::Vector<android::sp<ResourceFilter> > mFilters;
};
#endif
