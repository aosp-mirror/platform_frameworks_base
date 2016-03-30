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

#include "Locale.h"
#include "util/Util.h"

#include <algorithm>
#include <ctype.h>
#include <string>
#include <vector>

namespace aapt {

using android::ResTable_config;

void LocaleValue::setLanguage(const char* languageChars) {
     size_t i = 0;
     while ((*languageChars) != '\0') {
          language[i++] = ::tolower(*languageChars);
          languageChars++;
     }
}

void LocaleValue::setRegion(const char* regionChars) {
    size_t i = 0;
    while ((*regionChars) != '\0') {
         region[i++] = ::toupper(*regionChars);
         regionChars++;
    }
}

void LocaleValue::setScript(const char* scriptChars) {
    size_t i = 0;
    while ((*scriptChars) != '\0') {
         if (i == 0) {
             script[i++] = ::toupper(*scriptChars);
         } else {
             script[i++] = ::tolower(*scriptChars);
         }
         scriptChars++;
    }
}

void LocaleValue::setVariant(const char* variantChars) {
     size_t i = 0;
     while ((*variantChars) != '\0') {
          variant[i++] = *variantChars;
          variantChars++;
     }
}

static inline bool isAlpha(const std::string& str) {
    return std::all_of(std::begin(str), std::end(str), ::isalpha);
}

static inline bool isNumber(const std::string& str) {
    return std::all_of(std::begin(str), std::end(str), ::isdigit);
}

bool LocaleValue::initFromFilterString(const StringPiece& str) {
     // A locale (as specified in the filter) is an underscore separated name such
     // as "en_US", "en_Latn_US", or "en_US_POSIX".
     std::vector<std::string> parts = util::splitAndLowercase(str, '_');

     const int numTags = parts.size();
     bool valid = false;
     if (numTags >= 1) {
         const std::string& lang = parts[0];
         if (isAlpha(lang) && (lang.length() == 2 || lang.length() == 3)) {
             setLanguage(lang.c_str());
             valid = true;
         }
     }

     if (!valid || numTags == 1) {
         return valid;
     }

     // At this point, valid == true && numTags > 1.
     const std::string& part2 = parts[1];
     if ((part2.length() == 2 && isAlpha(part2)) ||
         (part2.length() == 3 && isNumber(part2))) {
         setRegion(part2.c_str());
     } else if (part2.length() == 4 && isAlpha(part2)) {
         setScript(part2.c_str());
     } else if (part2.length() >= 4 && part2.length() <= 8) {
         setVariant(part2.c_str());
     } else {
         valid = false;
     }

     if (!valid || numTags == 2) {
         return valid;
     }

     // At this point, valid == true && numTags > 1.
     const std::string& part3 = parts[2];
     if (((part3.length() == 2 && isAlpha(part3)) ||
         (part3.length() == 3 && isNumber(part3))) && script[0]) {
         setRegion(part3.c_str());
     } else if (part3.length() >= 4 && part3.length() <= 8) {
         setVariant(part3.c_str());
     } else {
         valid = false;
     }

     if (!valid || numTags == 3) {
         return valid;
     }

     const std::string& part4 = parts[3];
     if (part4.length() >= 4 && part4.length() <= 8) {
         setVariant(part4.c_str());
     } else {
         valid = false;
     }

     if (!valid || numTags > 4) {
         return false;
     }

     return true;
}

ssize_t LocaleValue::initFromParts(std::vector<std::string>::iterator iter,
        std::vector<std::string>::iterator end) {
    const std::vector<std::string>::iterator startIter = iter;

    std::string& part = *iter;
    if (part[0] == 'b' && part[1] == '+') {
        // This is a "modified" BCP 47 language tag. Same semantics as BCP 47 tags,
        // except that the separator is "+" and not "-".
        std::vector<std::string> subtags = util::splitAndLowercase(part, '+');
        subtags.erase(subtags.begin());
        if (subtags.size() == 1) {
            setLanguage(subtags[0].c_str());
        } else if (subtags.size() == 2) {
            setLanguage(subtags[0].c_str());

            // The second tag can either be a region, a variant or a script.
            switch (subtags[1].size()) {
                case 2:
                case 3:
                    setRegion(subtags[1].c_str());
                    break;
                case 4:
                    if ('0' <= subtags[1][0] && subtags[1][0] <= '9') {
                        // This is a variant: fall through
                    } else {
                        setScript(subtags[1].c_str());
                        break;
                    }
                case 5:
                case 6:
                case 7:
                case 8:
                    setVariant(subtags[1].c_str());
                    break;
                default:
                    return -1;
            }
        } else if (subtags.size() == 3) {
            // The language is always the first subtag.
            setLanguage(subtags[0].c_str());

            // The second subtag can either be a script or a region code.
            // If its size is 4, it's a script code, else it's a region code.
            if (subtags[1].size() == 4) {
                setScript(subtags[1].c_str());
            } else if (subtags[1].size() == 2 || subtags[1].size() == 3) {
                setRegion(subtags[1].c_str());
            } else {
                return -1;
            }

            // The third tag can either be a region code (if the second tag was
            // a script), else a variant code.
            if (subtags[2].size() >= 4) {
                setVariant(subtags[2].c_str());
            } else {
                setRegion(subtags[2].c_str());
            }
        } else if (subtags.size() == 4) {
            setLanguage(subtags[0].c_str());
            setScript(subtags[1].c_str());
            setRegion(subtags[2].c_str());
            setVariant(subtags[3].c_str());
        } else {
            return -1;
        }

        ++iter;

    } else {
        if ((part.length() == 2 || part.length() == 3)
                && isAlpha(part) && part != "car") {
            setLanguage(part.c_str());
            ++iter;

            if (iter != end) {
                const std::string& regionPart = *iter;
                if (regionPart.c_str()[0] == 'r' && regionPart.length() == 3) {
                    setRegion(regionPart.c_str() + 1);
                    ++iter;
                }
            }
        }
    }

    return static_cast<ssize_t>(iter - startIter);
}


std::string LocaleValue::toDirName() const {
    std::string dirName;
    if (language[0]) {
        dirName += language;
    } else {
        return dirName;
    }

    if (script[0]) {
        dirName += "-s";
        dirName += script;
    }

    if (region[0]) {
        dirName += "-r";
        dirName += region;
    }

    if (variant[0]) {
        dirName += "-v";
        dirName += variant;
    }

    return dirName;
}

void LocaleValue::initFromResTable(const ResTable_config& config) {
    config.unpackLanguage(language);
    config.unpackRegion(region);
    if (config.localeScript[0] && !config.localeScriptWasComputed) {
        memcpy(script, config.localeScript, sizeof(config.localeScript));
    }

    if (config.localeVariant[0]) {
        memcpy(variant, config.localeVariant, sizeof(config.localeVariant));
    }
}

void LocaleValue::writeTo(ResTable_config* out) const {
    out->packLanguage(language);
    out->packRegion(region);

    if (script[0]) {
        memcpy(out->localeScript, script, sizeof(out->localeScript));
    }

    if (variant[0]) {
        memcpy(out->localeVariant, variant, sizeof(out->localeVariant));
    }
}

} // namespace aapt
