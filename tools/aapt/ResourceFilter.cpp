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

        // Ignore any densities. Those are best handled in --preferred-density
        if ((entry.second & ResTable_config::CONFIG_DENSITY) != 0) {
            fprintf(stderr, "warning: ignoring flag -c %s. Use --preferred-density instead.\n", entry.first.toString().string());
            entry.first.density = 0;
            entry.second &= ~ResTable_config::CONFIG_DENSITY;
        }

        mConfigMask |= entry.second;
    }

    return NO_ERROR;
}

// Returns true if the locale script of the config should be considered matching
// the locale script of entry.
//
// If both the scripts are empty, the scripts are considered matching for
// backward compatibility reasons.
//
// If only one script is empty, we try to compute it based on the provided
// language and country. If we could not compute it, we assume it's either a
// new language we don't know about, or a private use language. We return true
// since we don't know any better and they might as well be a match.
//
// Finally, when we have two scripts (one of which could be computed), we return
// true if and only if they are an exact match.
inline bool
scriptsMatch(const ResTable_config& config, const ResTable_config& entry) {
    const char* configScript = config.localeScript;
    const char* entryScript = entry.localeScript;
    if (configScript[0] == '\0' && entryScript[0] == '\0') {
        return true;  // both scripts are empty. We match for backward compatibility reasons.
    }

    char scriptBuffer[sizeof(config.localeScript)];
    if (configScript[0] == '\0') {
        localeDataComputeScript(scriptBuffer, config.language, config.country);
        if (scriptBuffer[0] == '\0') {  // We can't compute the script, so we match.
            return true;
        }
        configScript = scriptBuffer;
    } else if (entryScript[0] == '\0') {
        localeDataComputeScript(
                scriptBuffer, entry.language, entry.country);
        if (scriptBuffer[0] == '\0') {  // We can't compute the script, so we match.
            return true;
        }
        entryScript = scriptBuffer;
    }
    return (memcmp(configScript, entryScript, sizeof(config.localeScript)) == 0);
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
            //
            // Exception: we won't match if a script is specified for at least
            // one of the locales and it's different from the other locale's
            // script. (We will compute the other script if at least one of the
            // scripts were explicitly set. In cases we can't compute an script,
            // we match.)
            if (config.language[0] != '\0' &&
                    config.country[0] == '\0' &&
                    config.localeVariant[0] == '\0' &&
                    config.language[0] == entry.first.language[0] &&
                    config.language[1] == entry.first.language[1] &&
                    scriptsMatch(config, entry.first)) {
                matchedAxis |= ResTable_config::CONFIG_LOCALE;
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
