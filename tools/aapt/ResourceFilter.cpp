//
// Copyright 2014 The Android Open Source Project
//
// Build resource files from raw assets.
//

#include "ResourceFilter.h"
#include "AaptUtil.h"
#include "AaptConfig.h"

status_t
WeakResourceFilter::parse(const String8& str)
{
    Vector<String8> configStrs = AaptUtil::split(str, ',');
    const size_t N = configStrs.size();
    mConfigs.clear();
    mConfigMask = 0;
    mConfigs.resize(N);
    for (size_t i = 0; i < N; i++) {
        const String8& part = configStrs[i];
        if (part == "en_XA") {
            mContainsPseudoAccented = true;
        } else if (part == "ar_XB") {
            mContainsPseudoBidi = true;
        }

        std::pair<ConfigDescription, uint32_t>& entry = mConfigs.editItemAt(i);

        AaptLocaleValue val;
        if (val.initFromFilterString(part)) {
            // For backwards compatibility, we accept configurations that
            // only specify locale in the standard 'en_US' format.
            val.writeTo(&entry.first);
        } else if (!AaptConfig::parse(part, &entry.first)) {
            fprintf(stderr, "Invalid configuration: %s\n", part.string());
            return UNKNOWN_ERROR;
        }

        entry.second = mDefault.diff(entry.first);

        // Ignore the version
        entry.second &= ~ResTable_config::CONFIG_VERSION;

        mConfigMask |= entry.second;
    }

    return NO_ERROR;
}

bool
WeakResourceFilter::match(const ResTable_config& config) const
{
    uint32_t mask = mDefault.diff(config);
    if ((mConfigMask & mask) == 0) {
        // The two configurations don't have any common axis.
        return true;
    }

    uint32_t matchedAxis = 0x0;
    const size_t N = mConfigs.size();
    for (size_t i = 0; i < N; i++) {
        const std::pair<ConfigDescription, uint32_t>& entry = mConfigs[i];
        uint32_t diff = entry.first.diff(config);
        if ((diff & entry.second) == 0) {
            // Mark the axis that was matched.
            matchedAxis |= entry.second;
        } else if ((diff & entry.second) == ResTable_config::CONFIG_LOCALE) {
            // If the locales differ, but the languages are the same and
            // the locale we are matching only has a language specified,
            // we match.
            if (config.language[0] &&
                    memcmp(config.language, entry.first.language, sizeof(config.language)) == 0) {
                if (config.country[0] == 0) {
                    matchedAxis |= ResTable_config::CONFIG_LOCALE;
                }
            }
        } else if ((diff & entry.second) == ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE) {
            // Special case if the smallest screen width doesn't match. We check that the
            // config being matched has a smaller screen width than the filter specified.
            if (config.smallestScreenWidthDp != 0 &&
                    config.smallestScreenWidthDp < entry.first.smallestScreenWidthDp) {
                matchedAxis |= ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE;
            }
        }
    }
    return matchedAxis == (mConfigMask & mask);
}

status_t
StrongResourceFilter::parse(const String8& str) {
    Vector<String8> configStrs = AaptUtil::split(str, ',');
    ConfigDescription config;
    mConfigs.clear();
    for (size_t i = 0; i < configStrs.size(); i++) {
        if (!AaptConfig::parse(configStrs[i], &config)) {
            fprintf(stderr, "Invalid configuration: %s\n", configStrs[i].string());
            return UNKNOWN_ERROR;
        }
        mConfigs.insert(config);
    }
    return NO_ERROR;
}
