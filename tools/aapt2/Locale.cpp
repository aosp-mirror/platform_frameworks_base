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

#include <ctype.h>

#include <algorithm>
#include <string>
#include <vector>

#include "util/Util.h"

using ::android::ResTable_config;
using ::android::StringPiece;

namespace aapt {

void LocaleValue::set_language(const char* language_chars) {
  size_t i = 0;
  while ((*language_chars) != '\0') {
    language[i++] = ::tolower(*language_chars);
    language_chars++;
  }
}

void LocaleValue::set_region(const char* region_chars) {
  size_t i = 0;
  while ((*region_chars) != '\0') {
    region[i++] = ::toupper(*region_chars);
    region_chars++;
  }
}

void LocaleValue::set_script(const char* script_chars) {
  size_t i = 0;
  while ((*script_chars) != '\0') {
    if (i == 0) {
      script[i++] = ::toupper(*script_chars);
    } else {
      script[i++] = ::tolower(*script_chars);
    }
    script_chars++;
  }
}

void LocaleValue::set_variant(const char* variant_chars) {
  size_t i = 0;
  while ((*variant_chars) != '\0') {
    variant[i++] = *variant_chars;
    variant_chars++;
  }
}

static inline bool is_alpha(const std::string& str) {
  return std::all_of(std::begin(str), std::end(str), ::isalpha);
}

static inline bool is_number(const std::string& str) {
  return std::all_of(std::begin(str), std::end(str), ::isdigit);
}

bool LocaleValue::InitFromFilterString(const StringPiece& str) {
  // A locale (as specified in the filter) is an underscore separated name such
  // as "en_US", "en_Latn_US", or "en_US_POSIX".
  std::vector<std::string> parts = util::SplitAndLowercase(str, '_');

  const int num_tags = parts.size();
  bool valid = false;
  if (num_tags >= 1) {
    const std::string& lang = parts[0];
    if (is_alpha(lang) && (lang.length() == 2 || lang.length() == 3)) {
      set_language(lang.c_str());
      valid = true;
    }
  }

  if (!valid || num_tags == 1) {
    return valid;
  }

  // At this point, valid == true && numTags > 1.
  const std::string& part2 = parts[1];
  if ((part2.length() == 2 && is_alpha(part2)) ||
      (part2.length() == 3 && is_number(part2))) {
    set_region(part2.c_str());
  } else if (part2.length() == 4 && is_alpha(part2)) {
    set_script(part2.c_str());
  } else if (part2.length() >= 4 && part2.length() <= 8) {
    set_variant(part2.c_str());
  } else {
    valid = false;
  }

  if (!valid || num_tags == 2) {
    return valid;
  }

  // At this point, valid == true && numTags > 1.
  const std::string& part3 = parts[2];
  if (((part3.length() == 2 && is_alpha(part3)) ||
       (part3.length() == 3 && is_number(part3))) &&
      script[0]) {
    set_region(part3.c_str());
  } else if (part3.length() >= 4 && part3.length() <= 8) {
    set_variant(part3.c_str());
  } else {
    valid = false;
  }

  if (!valid || num_tags == 3) {
    return valid;
  }

  const std::string& part4 = parts[3];
  if (part4.length() >= 4 && part4.length() <= 8) {
    set_variant(part4.c_str());
  } else {
    valid = false;
  }

  if (!valid || num_tags > 4) {
    return false;
  }

  return true;
}

bool LocaleValue::InitFromBcp47Tag(const StringPiece& bcp47tag) {
  return InitFromBcp47TagImpl(bcp47tag, '-');
}

bool LocaleValue::InitFromBcp47TagImpl(const StringPiece& bcp47tag, const char separator) {
  std::vector<std::string> subtags = util::SplitAndLowercase(bcp47tag, separator);
  if (subtags.size() == 1) {
    set_language(subtags[0].c_str());
  } else if (subtags.size() == 2) {
    set_language(subtags[0].c_str());

    // The second tag can either be a region, a variant or a script.
    switch (subtags[1].size()) {
      case 2:
      case 3:
        set_region(subtags[1].c_str());
        break;
      case 4:
        if ('0' <= subtags[1][0] && subtags[1][0] <= '9') {
          // This is a variant: fall through
        } else {
          set_script(subtags[1].c_str());
          break;
        }
      case 5:
      case 6:
      case 7:
      case 8:
        set_variant(subtags[1].c_str());
        break;
      default:
        return false;
    }
  } else if (subtags.size() == 3) {
    // The language is always the first subtag.
    set_language(subtags[0].c_str());

    // The second subtag can either be a script or a region code.
    // If its size is 4, it's a script code, else it's a region code.
    if (subtags[1].size() == 4) {
      set_script(subtags[1].c_str());
    } else if (subtags[1].size() == 2 || subtags[1].size() == 3) {
      set_region(subtags[1].c_str());
    } else {
      return false;
    }

    // The third tag can either be a region code (if the second tag was
    // a script), else a variant code.
    if (subtags[2].size() >= 4) {
      set_variant(subtags[2].c_str());
    } else {
      set_region(subtags[2].c_str());
    }
  } else if (subtags.size() == 4) {
    set_language(subtags[0].c_str());
    set_script(subtags[1].c_str());
    set_region(subtags[2].c_str());
    set_variant(subtags[3].c_str());
  } else {
    return false;
  }
  return true;
}

ssize_t LocaleValue::InitFromParts(std::vector<std::string>::iterator iter,
                                   std::vector<std::string>::iterator end) {
  const std::vector<std::string>::iterator start_iter = iter;

  std::string& part = *iter;
  if (part[0] == 'b' && part[1] == '+') {
    // This is a "modified" BCP 47 language tag. Same semantics as BCP 47 tags,
    // except that the separator is "+" and not "-". Skip the prefix 'b+'.
    if (!InitFromBcp47TagImpl(StringPiece(part).substr(2), '+')) {
      return -1;
    }
    ++iter;
  } else {
    if ((part.length() == 2 || part.length() == 3) && is_alpha(part) && part != "car") {
      set_language(part.c_str());
      ++iter;

      if (iter != end) {
        const std::string& region_part = *iter;
        if (region_part.c_str()[0] == 'r' && region_part.length() == 3) {
          set_region(region_part.c_str() + 1);
          ++iter;
        }
      }
    }
  }
  return static_cast<ssize_t>(iter - start_iter);
}

void LocaleValue::InitFromResTable(const ResTable_config& config) {
  config.unpackLanguage(language);
  config.unpackRegion(region);
  if (config.localeScript[0] && !config.localeScriptWasComputed) {
    memcpy(script, config.localeScript, sizeof(config.localeScript));
  }

  if (config.localeVariant[0]) {
    memcpy(variant, config.localeVariant, sizeof(config.localeVariant));
  }
}

void LocaleValue::WriteTo(ResTable_config* out) const {
  out->packLanguage(language);
  out->packRegion(region);

  if (script[0]) {
    memcpy(out->localeScript, script, sizeof(out->localeScript));
  }

  if (variant[0]) {
    memcpy(out->localeVariant, variant, sizeof(out->localeVariant));
  }
}

}  // namespace aapt
