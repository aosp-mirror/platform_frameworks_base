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

#include "ConfigDescription.h"

#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "Locale.h"
#include "SdkConstants.h"
#include "util/Util.h"

using android::ResTable_config;
using android::StringPiece;

namespace aapt {

static const char* kWildcardName = "any";

const ConfigDescription& ConfigDescription::DefaultConfig() {
  static ConfigDescription config = {};
  return config;
}

static bool parseMcc(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->mcc = 0;
    return true;
  }
  const char* c = name;
  if (tolower(*c) != 'm') return false;
  c++;
  if (tolower(*c) != 'c') return false;
  c++;
  if (tolower(*c) != 'c') return false;
  c++;

  const char* val = c;

  while (*c >= '0' && *c <= '9') {
    c++;
  }
  if (*c != 0) return false;
  if (c - val != 3) return false;

  int d = atoi(val);
  if (d != 0) {
    if (out) out->mcc = d;
    return true;
  }

  return false;
}

static bool parseMnc(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->mnc = 0;
    return true;
  }
  const char* c = name;
  if (tolower(*c) != 'm') return false;
  c++;
  if (tolower(*c) != 'n') return false;
  c++;
  if (tolower(*c) != 'c') return false;
  c++;

  const char* val = c;

  while (*c >= '0' && *c <= '9') {
    c++;
  }
  if (*c != 0) return false;
  if (c - val == 0 || c - val > 3) return false;

  if (out) {
    out->mnc = atoi(val);
    if (out->mnc == 0) {
      out->mnc = ACONFIGURATION_MNC_ZERO;
    }
  }

  return true;
}

static bool parseLayoutDirection(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_LAYOUTDIR) |
          ResTable_config::LAYOUTDIR_ANY;
    return true;
  } else if (strcmp(name, "ldltr") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_LAYOUTDIR) |
          ResTable_config::LAYOUTDIR_LTR;
    return true;
  } else if (strcmp(name, "ldrtl") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_LAYOUTDIR) |
          ResTable_config::LAYOUTDIR_RTL;
    return true;
  }

  return false;
}

static bool parseScreenLayoutSize(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENSIZE) |
          ResTable_config::SCREENSIZE_ANY;
    return true;
  } else if (strcmp(name, "small") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENSIZE) |
          ResTable_config::SCREENSIZE_SMALL;
    return true;
  } else if (strcmp(name, "normal") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENSIZE) |
          ResTable_config::SCREENSIZE_NORMAL;
    return true;
  } else if (strcmp(name, "large") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENSIZE) |
          ResTable_config::SCREENSIZE_LARGE;
    return true;
  } else if (strcmp(name, "xlarge") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENSIZE) |
          ResTable_config::SCREENSIZE_XLARGE;
    return true;
  }

  return false;
}

static bool parseScreenLayoutLong(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENLONG) |
          ResTable_config::SCREENLONG_ANY;
    return true;
  } else if (strcmp(name, "long") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENLONG) |
          ResTable_config::SCREENLONG_YES;
    return true;
  } else if (strcmp(name, "notlong") == 0) {
    if (out)
      out->screenLayout =
          (out->screenLayout & ~ResTable_config::MASK_SCREENLONG) |
          ResTable_config::SCREENLONG_NO;
    return true;
  }

  return false;
}

static bool parseScreenRound(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->screenLayout2 =
          (out->screenLayout2 & ~ResTable_config::MASK_SCREENROUND) |
          ResTable_config::SCREENROUND_ANY;
    return true;
  } else if (strcmp(name, "round") == 0) {
    if (out)
      out->screenLayout2 =
          (out->screenLayout2 & ~ResTable_config::MASK_SCREENROUND) |
          ResTable_config::SCREENROUND_YES;
    return true;
  } else if (strcmp(name, "notround") == 0) {
    if (out)
      out->screenLayout2 =
          (out->screenLayout2 & ~ResTable_config::MASK_SCREENROUND) |
          ResTable_config::SCREENROUND_NO;
    return true;
  }
  return false;
}

static bool parseWideColorGamut(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_WIDE_COLOR_GAMUT) |
          ResTable_config::WIDE_COLOR_GAMUT_ANY;
    return true;
  } else if (strcmp(name, "widecg") == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_WIDE_COLOR_GAMUT) |
          ResTable_config::WIDE_COLOR_GAMUT_YES;
    return true;
  } else if (strcmp(name, "nowidecg") == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_WIDE_COLOR_GAMUT) |
          ResTable_config::WIDE_COLOR_GAMUT_NO;
    return true;
  }
  return false;
}

static bool parseHdr(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_HDR) |
          ResTable_config::HDR_ANY;
    return true;
  } else if (strcmp(name, "highdr") == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_HDR) |
          ResTable_config::HDR_YES;
    return true;
  } else if (strcmp(name, "lowdr") == 0) {
    if (out)
      out->colorMode =
          (out->colorMode & ~ResTable_config::MASK_HDR) |
          ResTable_config::HDR_NO;
    return true;
  }
  return false;
}

static bool parseOrientation(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->orientation = out->ORIENTATION_ANY;
    return true;
  } else if (strcmp(name, "port") == 0) {
    if (out) out->orientation = out->ORIENTATION_PORT;
    return true;
  } else if (strcmp(name, "land") == 0) {
    if (out) out->orientation = out->ORIENTATION_LAND;
    return true;
  } else if (strcmp(name, "square") == 0) {
    if (out) out->orientation = out->ORIENTATION_SQUARE;
    return true;
  }

  return false;
}

static bool parseUiModeType(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_ANY;
    return true;
  } else if (strcmp(name, "desk") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_DESK;
    return true;
  } else if (strcmp(name, "car") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_CAR;
    return true;
  } else if (strcmp(name, "television") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_TELEVISION;
    return true;
  } else if (strcmp(name, "appliance") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_APPLIANCE;
    return true;
  } else if (strcmp(name, "watch") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_WATCH;
    return true;
  } else if (strcmp(name, "vrheadset") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_TYPE) |
                    ResTable_config::UI_MODE_TYPE_VR_HEADSET;
    return true;
  }

  return false;
}

static bool parseUiModeNight(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_NIGHT) |
                    ResTable_config::UI_MODE_NIGHT_ANY;
    return true;
  } else if (strcmp(name, "night") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_NIGHT) |
                    ResTable_config::UI_MODE_NIGHT_YES;
    return true;
  } else if (strcmp(name, "notnight") == 0) {
    if (out)
      out->uiMode = (out->uiMode & ~ResTable_config::MASK_UI_MODE_NIGHT) |
                    ResTable_config::UI_MODE_NIGHT_NO;
    return true;
  }

  return false;
}

static bool parseDensity(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->density = ResTable_config::DENSITY_DEFAULT;
    return true;
  }

  if (strcmp(name, "anydpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_ANY;
    return true;
  }

  if (strcmp(name, "nodpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_NONE;
    return true;
  }

  if (strcmp(name, "ldpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_LOW;
    return true;
  }

  if (strcmp(name, "mdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_MEDIUM;
    return true;
  }

  if (strcmp(name, "tvdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_TV;
    return true;
  }

  if (strcmp(name, "hdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_HIGH;
    return true;
  }

  if (strcmp(name, "xhdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_XHIGH;
    return true;
  }

  if (strcmp(name, "xxhdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_XXHIGH;
    return true;
  }

  if (strcmp(name, "xxxhdpi") == 0) {
    if (out) out->density = ResTable_config::DENSITY_XXXHIGH;
    return true;
  }

  char* c = (char*)name;
  while (*c >= '0' && *c <= '9') {
    c++;
  }

  // check that we have 'dpi' after the last digit.
  if (toupper(c[0]) != 'D' || toupper(c[1]) != 'P' || toupper(c[2]) != 'I' ||
      c[3] != 0) {
    return false;
  }

  // temporarily replace the first letter with \0 to
  // use atoi.
  char tmp = c[0];
  c[0] = '\0';

  int d = atoi(name);
  c[0] = tmp;

  if (d != 0) {
    if (out) out->density = d;
    return true;
  }

  return false;
}

static bool parseTouchscreen(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->touchscreen = out->TOUCHSCREEN_ANY;
    return true;
  } else if (strcmp(name, "notouch") == 0) {
    if (out) out->touchscreen = out->TOUCHSCREEN_NOTOUCH;
    return true;
  } else if (strcmp(name, "stylus") == 0) {
    if (out) out->touchscreen = out->TOUCHSCREEN_STYLUS;
    return true;
  } else if (strcmp(name, "finger") == 0) {
    if (out) out->touchscreen = out->TOUCHSCREEN_FINGER;
    return true;
  }

  return false;
}

static bool parseKeysHidden(const char* name, ResTable_config* out) {
  uint8_t mask = 0;
  uint8_t value = 0;
  if (strcmp(name, kWildcardName) == 0) {
    mask = ResTable_config::MASK_KEYSHIDDEN;
    value = ResTable_config::KEYSHIDDEN_ANY;
  } else if (strcmp(name, "keysexposed") == 0) {
    mask = ResTable_config::MASK_KEYSHIDDEN;
    value = ResTable_config::KEYSHIDDEN_NO;
  } else if (strcmp(name, "keyshidden") == 0) {
    mask = ResTable_config::MASK_KEYSHIDDEN;
    value = ResTable_config::KEYSHIDDEN_YES;
  } else if (strcmp(name, "keyssoft") == 0) {
    mask = ResTable_config::MASK_KEYSHIDDEN;
    value = ResTable_config::KEYSHIDDEN_SOFT;
  }

  if (mask != 0) {
    if (out) out->inputFlags = (out->inputFlags & ~mask) | value;
    return true;
  }

  return false;
}

static bool parseKeyboard(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->keyboard = out->KEYBOARD_ANY;
    return true;
  } else if (strcmp(name, "nokeys") == 0) {
    if (out) out->keyboard = out->KEYBOARD_NOKEYS;
    return true;
  } else if (strcmp(name, "qwerty") == 0) {
    if (out) out->keyboard = out->KEYBOARD_QWERTY;
    return true;
  } else if (strcmp(name, "12key") == 0) {
    if (out) out->keyboard = out->KEYBOARD_12KEY;
    return true;
  }

  return false;
}

static bool parseNavHidden(const char* name, ResTable_config* out) {
  uint8_t mask = 0;
  uint8_t value = 0;
  if (strcmp(name, kWildcardName) == 0) {
    mask = ResTable_config::MASK_NAVHIDDEN;
    value = ResTable_config::NAVHIDDEN_ANY;
  } else if (strcmp(name, "navexposed") == 0) {
    mask = ResTable_config::MASK_NAVHIDDEN;
    value = ResTable_config::NAVHIDDEN_NO;
  } else if (strcmp(name, "navhidden") == 0) {
    mask = ResTable_config::MASK_NAVHIDDEN;
    value = ResTable_config::NAVHIDDEN_YES;
  }

  if (mask != 0) {
    if (out) out->inputFlags = (out->inputFlags & ~mask) | value;
    return true;
  }

  return false;
}

static bool parseNavigation(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) out->navigation = out->NAVIGATION_ANY;
    return true;
  } else if (strcmp(name, "nonav") == 0) {
    if (out) out->navigation = out->NAVIGATION_NONAV;
    return true;
  } else if (strcmp(name, "dpad") == 0) {
    if (out) out->navigation = out->NAVIGATION_DPAD;
    return true;
  } else if (strcmp(name, "trackball") == 0) {
    if (out) out->navigation = out->NAVIGATION_TRACKBALL;
    return true;
  } else if (strcmp(name, "wheel") == 0) {
    if (out) out->navigation = out->NAVIGATION_WHEEL;
    return true;
  }

  return false;
}

static bool parseScreenSize(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) {
      out->screenWidth = out->SCREENWIDTH_ANY;
      out->screenHeight = out->SCREENHEIGHT_ANY;
    }
    return true;
  }

  const char* x = name;
  while (*x >= '0' && *x <= '9') x++;
  if (x == name || *x != 'x') return false;
  std::string xName(name, x - name);
  x++;

  const char* y = x;
  while (*y >= '0' && *y <= '9') y++;
  if (y == name || *y != 0) return false;
  std::string yName(x, y - x);

  uint16_t w = (uint16_t)atoi(xName.c_str());
  uint16_t h = (uint16_t)atoi(yName.c_str());
  if (w < h) {
    return false;
  }

  if (out) {
    out->screenWidth = w;
    out->screenHeight = h;
  }

  return true;
}

static bool parseSmallestScreenWidthDp(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) {
      out->smallestScreenWidthDp = out->SCREENWIDTH_ANY;
    }
    return true;
  }

  if (*name != 's') return false;
  name++;
  if (*name != 'w') return false;
  name++;
  const char* x = name;
  while (*x >= '0' && *x <= '9') x++;
  if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
  std::string xName(name, x - name);

  if (out) {
    out->smallestScreenWidthDp = (uint16_t)atoi(xName.c_str());
  }

  return true;
}

static bool parseScreenWidthDp(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) {
      out->screenWidthDp = out->SCREENWIDTH_ANY;
    }
    return true;
  }

  if (*name != 'w') return false;
  name++;
  const char* x = name;
  while (*x >= '0' && *x <= '9') x++;
  if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
  std::string xName(name, x - name);

  if (out) {
    out->screenWidthDp = (uint16_t)atoi(xName.c_str());
  }

  return true;
}

static bool parseScreenHeightDp(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) {
      out->screenHeightDp = out->SCREENWIDTH_ANY;
    }
    return true;
  }

  if (*name != 'h') return false;
  name++;
  const char* x = name;
  while (*x >= '0' && *x <= '9') x++;
  if (x == name || x[0] != 'd' || x[1] != 'p' || x[2] != 0) return false;
  std::string xName(name, x - name);

  if (out) {
    out->screenHeightDp = (uint16_t)atoi(xName.c_str());
  }

  return true;
}

static bool parseVersion(const char* name, ResTable_config* out) {
  if (strcmp(name, kWildcardName) == 0) {
    if (out) {
      out->sdkVersion = out->SDKVERSION_ANY;
      out->minorVersion = out->MINORVERSION_ANY;
    }
    return true;
  }

  if (*name != 'v') {
    return false;
  }

  name++;
  const char* s = name;
  while (*s >= '0' && *s <= '9') s++;
  if (s == name || *s != 0) return false;
  std::string sdkName(name, s - name);

  if (out) {
    out->sdkVersion = (uint16_t)atoi(sdkName.c_str());
    out->minorVersion = 0;
  }

  return true;
}

bool ConfigDescription::Parse(const StringPiece& str, ConfigDescription* out) {
  std::vector<std::string> parts = util::SplitAndLowercase(str, '-');

  ConfigDescription config;
  ssize_t parts_consumed = 0;
  LocaleValue locale;

  const auto parts_end = parts.end();
  auto part_iter = parts.begin();

  if (str.size() == 0) {
    goto success;
  }

  if (parseMcc(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseMnc(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  // Locale spans a few '-' separators, so we let it
  // control the index.
  parts_consumed = locale.InitFromParts(part_iter, parts_end);
  if (parts_consumed < 0) {
    return false;
  } else {
    locale.WriteTo(&config);
    part_iter += parts_consumed;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseLayoutDirection(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseSmallestScreenWidthDp(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenWidthDp(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenHeightDp(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenLayoutSize(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenLayoutLong(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenRound(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseWideColorGamut(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseHdr(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseOrientation(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseUiModeType(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseUiModeNight(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseDensity(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseTouchscreen(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseKeysHidden(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseKeyboard(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseNavHidden(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseNavigation(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseScreenSize(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  if (parseVersion(part_iter->c_str(), &config)) {
    ++part_iter;
    if (part_iter == parts_end) {
      goto success;
    }
  }

  // Unrecognized.
  return false;

success:
  if (out != NULL) {
    ApplyVersionForCompatibility(&config);
    *out = config;
  }
  return true;
}

void ConfigDescription::ApplyVersionForCompatibility(
    ConfigDescription* config) {
  uint16_t min_sdk = 0;
  if ((config->uiMode & ResTable_config::MASK_UI_MODE_TYPE)
                == ResTable_config::UI_MODE_TYPE_VR_HEADSET ||
            config->colorMode & ResTable_config::MASK_WIDE_COLOR_GAMUT ||
            config->colorMode & ResTable_config::MASK_HDR) {
        min_sdk = SDK_O;
  } else if (config->screenLayout2 & ResTable_config::MASK_SCREENROUND) {
    min_sdk = SDK_MARSHMALLOW;
  } else if (config->density == ResTable_config::DENSITY_ANY) {
    min_sdk = SDK_LOLLIPOP;
  } else if (config->smallestScreenWidthDp !=
                 ResTable_config::SCREENWIDTH_ANY ||
             config->screenWidthDp != ResTable_config::SCREENWIDTH_ANY ||
             config->screenHeightDp != ResTable_config::SCREENHEIGHT_ANY) {
    min_sdk = SDK_HONEYCOMB_MR2;
  } else if ((config->uiMode & ResTable_config::MASK_UI_MODE_TYPE) !=
                 ResTable_config::UI_MODE_TYPE_ANY ||
             (config->uiMode & ResTable_config::MASK_UI_MODE_NIGHT) !=
                 ResTable_config::UI_MODE_NIGHT_ANY) {
    min_sdk = SDK_FROYO;
  } else if ((config->screenLayout & ResTable_config::MASK_SCREENSIZE) !=
                 ResTable_config::SCREENSIZE_ANY ||
             (config->screenLayout & ResTable_config::MASK_SCREENLONG) !=
                 ResTable_config::SCREENLONG_ANY ||
             config->density != ResTable_config::DENSITY_DEFAULT) {
    min_sdk = SDK_DONUT;
  }

  if (min_sdk > config->sdkVersion) {
    config->sdkVersion = min_sdk;
  }
}

ConfigDescription ConfigDescription::CopyWithoutSdkVersion() const {
  ConfigDescription copy = *this;
  copy.sdkVersion = 0;
  return copy;
}

std::string ConfigDescription::GetBcp47LanguageTag(bool canonicalize) const {
  char locale[RESTABLE_MAX_LOCALE_LEN];
  getBcp47Locale(locale, canonicalize);
  return std::string(locale);
}

std::string ConfigDescription::to_string() const {
  const android::String8 str = toString();
  return std::string(str.string(), str.size());
}

bool ConfigDescription::Dominates(const ConfigDescription& o) const {
  if (*this == o) {
    return true;
  }

  // Locale de-duping is not-trivial, disable for now (b/62409213).
  if (diff(o) & CONFIG_LOCALE) {
    return false;
  }

  if (*this == DefaultConfig()) {
    return true;
  }
  return MatchWithDensity(o) && !o.MatchWithDensity(*this) &&
         !isMoreSpecificThan(o) && !o.HasHigherPrecedenceThan(*this);
}

bool ConfigDescription::HasHigherPrecedenceThan(
    const ConfigDescription& o) const {
  // The order of the following tests defines the importance of one
  // configuration parameter over another. Those tests first are more
  // important, trumping any values in those following them.
  // The ordering should be the same as ResTable_config#isBetterThan.
  if (mcc || o.mcc) return (!o.mcc);
  if (mnc || o.mnc) return (!o.mnc);
  if (language[0] || o.language[0]) return (!o.language[0]);
  if (country[0] || o.country[0]) return (!o.country[0]);
  // Script and variant require either a language or country, both of which
  // have higher precedence.
  if ((screenLayout | o.screenLayout) & MASK_LAYOUTDIR) {
    return !(o.screenLayout & MASK_LAYOUTDIR);
  }
  if (smallestScreenWidthDp || o.smallestScreenWidthDp)
    return (!o.smallestScreenWidthDp);
  if (screenWidthDp || o.screenWidthDp) return (!o.screenWidthDp);
  if (screenHeightDp || o.screenHeightDp) return (!o.screenHeightDp);
  if ((screenLayout | o.screenLayout) & MASK_SCREENSIZE) {
    return !(o.screenLayout & MASK_SCREENSIZE);
  }
  if ((screenLayout | o.screenLayout) & MASK_SCREENLONG) {
    return !(o.screenLayout & MASK_SCREENLONG);
  }
  if ((screenLayout2 | o.screenLayout2) & MASK_SCREENROUND) {
    return !(o.screenLayout2 & MASK_SCREENROUND);
  }
  if ((colorMode | o.colorMode) & MASK_HDR) {
    return !(o.colorMode & MASK_HDR);
  }
  if ((colorMode | o.colorMode) & MASK_WIDE_COLOR_GAMUT) {
    return !(o.colorMode & MASK_WIDE_COLOR_GAMUT);
  }
  if (orientation || o.orientation) return (!o.orientation);
  if ((uiMode | o.uiMode) & MASK_UI_MODE_TYPE) {
    return !(o.uiMode & MASK_UI_MODE_TYPE);
  }
  if ((uiMode | o.uiMode) & MASK_UI_MODE_NIGHT) {
    return !(o.uiMode & MASK_UI_MODE_NIGHT);
  }
  if (density || o.density) return (!o.density);
  if (touchscreen || o.touchscreen) return (!o.touchscreen);
  if ((inputFlags | o.inputFlags) & MASK_KEYSHIDDEN) {
    return !(o.inputFlags & MASK_KEYSHIDDEN);
  }
  if ((inputFlags | o.inputFlags) & MASK_NAVHIDDEN) {
    return !(o.inputFlags & MASK_NAVHIDDEN);
  }
  if (keyboard || o.keyboard) return (!o.keyboard);
  if (navigation || o.navigation) return (!o.navigation);
  if (screenWidth || o.screenWidth) return (!o.screenWidth);
  if (screenHeight || o.screenHeight) return (!o.screenHeight);
  if (sdkVersion || o.sdkVersion) return (!o.sdkVersion);
  if (minorVersion || o.minorVersion) return (!o.minorVersion);
  // Both configurations have nothing defined except some possible future
  // value. Returning the comparison of the two configurations is a
  // "best effort" at this point to protect against incorrect dominations.
  return *this != o;
}

bool ConfigDescription::ConflictsWith(const ConfigDescription& o) const {
  // This method should be updated as new configuration parameters are
  // introduced (e.g. screenConfig2).
  auto pred = [](const uint32_t a, const uint32_t b) -> bool {
    return a == 0 || b == 0 || a == b;
  };
  // The values here can be found in ResTable_config#match. Density and range
  // values can't lead to conflicts, and are ignored.
  return !pred(mcc, o.mcc) || !pred(mnc, o.mnc) || !pred(locale, o.locale) ||
         !pred(screenLayout & MASK_LAYOUTDIR,
               o.screenLayout & MASK_LAYOUTDIR) ||
         !pred(screenLayout & MASK_SCREENLONG,
               o.screenLayout & MASK_SCREENLONG) ||
         !pred(uiMode & MASK_UI_MODE_TYPE, o.uiMode & MASK_UI_MODE_TYPE) ||
         !pred(uiMode & MASK_UI_MODE_NIGHT, o.uiMode & MASK_UI_MODE_NIGHT) ||
         !pred(screenLayout2 & MASK_SCREENROUND,
               o.screenLayout2 & MASK_SCREENROUND) ||
         !pred(colorMode & MASK_HDR, o.colorMode & MASK_HDR) ||
         !pred(colorMode & MASK_WIDE_COLOR_GAMUT,
               o.colorMode & MASK_WIDE_COLOR_GAMUT) ||
         !pred(orientation, o.orientation) ||
         !pred(touchscreen, o.touchscreen) ||
         !pred(inputFlags & MASK_KEYSHIDDEN, o.inputFlags & MASK_KEYSHIDDEN) ||
         !pred(inputFlags & MASK_NAVHIDDEN, o.inputFlags & MASK_NAVHIDDEN) ||
         !pred(keyboard, o.keyboard) || !pred(navigation, o.navigation);
}

bool ConfigDescription::IsCompatibleWith(const ConfigDescription& o) const {
  return !ConflictsWith(o) && !Dominates(o) && !o.Dominates(*this);
}

}  // namespace aapt
