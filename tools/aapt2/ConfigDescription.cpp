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
#include "Locale.h"
#include "SdkConstants.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <string>
#include <vector>

namespace aapt {

using android::ResTable_config;

static const char* kWildcardName = "any";

const ConfigDescription& ConfigDescription::defaultConfig() {
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
    if (out) out->mcc = 0;
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

bool ConfigDescription::parse(const StringPiece& str, ConfigDescription* out) {
  std::vector<std::string> parts = util::splitAndLowercase(str, '-');

  ConfigDescription config;
  ssize_t partsConsumed = 0;
  LocaleValue locale;

  const auto partsEnd = parts.end();
  auto partIter = parts.begin();

  if (str.size() == 0) {
    goto success;
  }

  if (parseMcc(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseMnc(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  // Locale spans a few '-' separators, so we let it
  // control the index.
  partsConsumed = locale.initFromParts(partIter, partsEnd);
  if (partsConsumed < 0) {
    return false;
  } else {
    locale.writeTo(&config);
    partIter += partsConsumed;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseLayoutDirection(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseSmallestScreenWidthDp(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenWidthDp(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenHeightDp(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenLayoutSize(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenLayoutLong(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenRound(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseOrientation(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseUiModeType(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseUiModeNight(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseDensity(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseTouchscreen(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseKeysHidden(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseKeyboard(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseNavHidden(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseNavigation(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseScreenSize(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  if (parseVersion(partIter->c_str(), &config)) {
    ++partIter;
    if (partIter == partsEnd) {
      goto success;
    }
  }

  // Unrecognized.
  return false;

success:
  if (out != NULL) {
    applyVersionForCompatibility(&config);
    *out = config;
  }
  return true;
}

void ConfigDescription::applyVersionForCompatibility(
    ConfigDescription* config) {
  uint16_t minSdk = 0;
  if (config->screenLayout2 & ResTable_config::MASK_SCREENROUND) {
    minSdk = SDK_MARSHMALLOW;
  } else if (config->density == ResTable_config::DENSITY_ANY) {
    minSdk = SDK_LOLLIPOP;
  } else if (config->smallestScreenWidthDp !=
                 ResTable_config::SCREENWIDTH_ANY ||
             config->screenWidthDp != ResTable_config::SCREENWIDTH_ANY ||
             config->screenHeightDp != ResTable_config::SCREENHEIGHT_ANY) {
    minSdk = SDK_HONEYCOMB_MR2;
  } else if ((config->uiMode & ResTable_config::MASK_UI_MODE_TYPE) !=
                 ResTable_config::UI_MODE_TYPE_ANY ||
             (config->uiMode & ResTable_config::MASK_UI_MODE_NIGHT) !=
                 ResTable_config::UI_MODE_NIGHT_ANY) {
    minSdk = SDK_FROYO;
  } else if ((config->screenLayout & ResTable_config::MASK_SCREENSIZE) !=
                 ResTable_config::SCREENSIZE_ANY ||
             (config->screenLayout & ResTable_config::MASK_SCREENLONG) !=
                 ResTable_config::SCREENLONG_ANY ||
             config->density != ResTable_config::DENSITY_DEFAULT) {
    minSdk = SDK_DONUT;
  }

  if (minSdk > config->sdkVersion) {
    config->sdkVersion = minSdk;
  }
}

ConfigDescription ConfigDescription::copyWithoutSdkVersion() const {
  ConfigDescription copy = *this;
  copy.sdkVersion = 0;
  return copy;
}

bool ConfigDescription::dominates(const ConfigDescription& o) const {
  if (*this == defaultConfig() || *this == o) {
    return true;
  }
  return matchWithDensity(o) && !o.matchWithDensity(*this) &&
         !isMoreSpecificThan(o) && !o.hasHigherPrecedenceThan(*this);
}

bool ConfigDescription::hasHigherPrecedenceThan(
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

bool ConfigDescription::conflictsWith(const ConfigDescription& o) const {
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
         !pred(screenLayout & MASK_UI_MODE_TYPE,
               o.screenLayout & MASK_UI_MODE_TYPE) ||
         !pred(uiMode & MASK_UI_MODE_TYPE, o.uiMode & MASK_UI_MODE_TYPE) ||
         !pred(uiMode & MASK_UI_MODE_NIGHT, o.uiMode & MASK_UI_MODE_NIGHT) ||
         !pred(screenLayout2 & MASK_SCREENROUND,
               o.screenLayout2 & MASK_SCREENROUND) ||
         !pred(orientation, o.orientation) ||
         !pred(touchscreen, o.touchscreen) ||
         !pred(inputFlags & MASK_KEYSHIDDEN, o.inputFlags & MASK_KEYSHIDDEN) ||
         !pred(inputFlags & MASK_NAVHIDDEN, o.inputFlags & MASK_NAVHIDDEN) ||
         !pred(keyboard, o.keyboard) || !pred(navigation, o.navigation);
}

bool ConfigDescription::isCompatibleWith(const ConfigDescription& o) const {
  return !conflictsWith(o) && !dominates(o) && !o.dominates(*this);
}

}  // namespace aapt
