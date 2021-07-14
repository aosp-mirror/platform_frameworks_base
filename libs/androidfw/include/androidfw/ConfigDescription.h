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

#ifndef ANDROIDFW_CONFIG_DESCRIPTION_H
#define ANDROIDFW_CONFIG_DESCRIPTION_H

#include <ostream>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

namespace android {

using ApiVersion = int;

enum : ApiVersion {
  SDK_CUPCAKE = 3,
  SDK_DONUT = 4,
  SDK_ECLAIR = 5,
  SDK_ECLAIR_0_1 = 6,
  SDK_ECLAIR_MR1 = 7,
  SDK_FROYO = 8,
  SDK_GINGERBREAD = 9,
  SDK_GINGERBREAD_MR1 = 10,
  SDK_HONEYCOMB = 11,
  SDK_HONEYCOMB_MR1 = 12,
  SDK_HONEYCOMB_MR2 = 13,
  SDK_ICE_CREAM_SANDWICH = 14,
  SDK_ICE_CREAM_SANDWICH_MR1 = 15,
  SDK_JELLY_BEAN = 16,
  SDK_JELLY_BEAN_MR1 = 17,
  SDK_JELLY_BEAN_MR2 = 18,
  SDK_KITKAT = 19,
  SDK_KITKAT_WATCH = 20,
  SDK_LOLLIPOP = 21,
  SDK_LOLLIPOP_MR1 = 22,
  SDK_MARSHMALLOW = 23,
  SDK_NOUGAT = 24,
  SDK_NOUGAT_MR1 = 25,
  SDK_O = 26,
  SDK_O_MR1 = 27,
  SDK_P = 28,
};

/*
 * Subclass of ResTable_config that adds convenient
 * initialization and comparison methods.
 */
struct ConfigDescription : public ResTable_config {
  /**
   * Returns an immutable default config.
   */
  static const ConfigDescription& DefaultConfig();

  /*
   * Parse a string of the form 'fr-sw600dp-land' and fill in the
   * given ResTable_config with resulting configuration parameters.
   *
   * The resulting configuration has the appropriate sdkVersion defined
   * for backwards compatibility.
   */
  static bool Parse(const android::StringPiece& str, ConfigDescription* out = nullptr);

  /**
   * If the configuration uses an axis that was added after
   * the original Android release, make sure the SDK version
   * is set accordingly.
   */
  static void ApplyVersionForCompatibility(ConfigDescription* config);

  ConfigDescription();
  ConfigDescription(const android::ResTable_config& o);  // NOLINT(google-explicit-constructor)
  ConfigDescription(const ConfigDescription& o);
  ConfigDescription(ConfigDescription&& o) noexcept;

  ConfigDescription& operator=(const android::ResTable_config& o);
  ConfigDescription& operator=(const ConfigDescription& o);
  ConfigDescription& operator=(ConfigDescription&& o) noexcept;

  ConfigDescription CopyWithoutSdkVersion() const;

  // Returns the BCP-47 language tag of this configuration's locale.
  std::string GetBcp47LanguageTag(bool canonicalize = false) const;

  std::string to_string() const;

  /**
   * A configuration X dominates another configuration Y, if X has at least the
   * precedence of Y and X is strictly more general than Y: for any type defined
   * by X, the same type is defined by Y with a value equal to or, in the case
   * of ranges, more specific than that of X.
   *
   * For example, the configuration 'en-w800dp' dominates 'en-rGB-w1024dp'. It
   * does not dominate 'fr', 'en-w720dp', or 'mcc001-en-w800dp'.
   */
  bool Dominates(const ConfigDescription& o) const;

  /**
   * Returns true if this configuration defines a more important configuration
   * parameter than o. For example, "en" has higher precedence than "v23",
   * whereas "en" has the same precedence as "en-v23".
   */
  bool HasHigherPrecedenceThan(const ConfigDescription& o) const;

  /**
   * A configuration conflicts with another configuration if both
   * configurations define an incompatible configuration parameter. An
   * incompatible configuration parameter is a non-range, non-density parameter
   * that is defined in both configurations as a different, non-default value.
   */
  bool ConflictsWith(const ConfigDescription& o) const;

  /**
   * A configuration is compatible with another configuration if both
   * configurations can match a common concrete device configuration and are
   * unrelated by domination. For example, land-v11 conflicts with port-v21
   * but is compatible with v21 (both land-v11 and v21 would match en-land-v23).
   */
  bool IsCompatibleWith(const ConfigDescription& o) const;

  bool MatchWithDensity(const ConfigDescription& o) const;

  bool operator<(const ConfigDescription& o) const;
  bool operator<=(const ConfigDescription& o) const;
  bool operator==(const ConfigDescription& o) const;
  bool operator!=(const ConfigDescription& o) const;
  bool operator>=(const ConfigDescription& o) const;
  bool operator>(const ConfigDescription& o) const;
};

inline ConfigDescription::ConfigDescription() {
  memset(this, 0, sizeof(*this));
  size = sizeof(android::ResTable_config);
}

inline ConfigDescription::ConfigDescription(const android::ResTable_config& o) {
  *static_cast<android::ResTable_config*>(this) = o;
  size = sizeof(android::ResTable_config);
}

inline ConfigDescription::ConfigDescription(const ConfigDescription& o)
  : android::ResTable_config(o) {
}

inline ConfigDescription::ConfigDescription(ConfigDescription&& o) noexcept {
  *this = o;
}

inline ConfigDescription& ConfigDescription::operator=(
    const android::ResTable_config& o) {
  *static_cast<android::ResTable_config*>(this) = o;
  size = sizeof(android::ResTable_config);
  return *this;
}

inline ConfigDescription& ConfigDescription::operator=(
    const ConfigDescription& o) {
  *static_cast<android::ResTable_config*>(this) = o;
  return *this;
}

inline ConfigDescription& ConfigDescription::operator=(ConfigDescription&& o) noexcept {
  *this = o;
  return *this;
}

inline bool ConfigDescription::MatchWithDensity(const ConfigDescription& o) const {
  return match(o) && (density == 0 || o.density != 0);
}

inline bool ConfigDescription::operator<(const ConfigDescription& o) const {
  return compare(o) < 0;
}

inline bool ConfigDescription::operator<=(const ConfigDescription& o) const {
  return compare(o) <= 0;
}

inline bool ConfigDescription::operator==(const ConfigDescription& o) const {
  return compare(o) == 0;
}

inline bool ConfigDescription::operator!=(const ConfigDescription& o) const {
  return compare(o) != 0;
}

inline bool ConfigDescription::operator>=(const ConfigDescription& o) const {
  return compare(o) >= 0;
}

inline bool ConfigDescription::operator>(const ConfigDescription& o) const {
  return compare(o) > 0;
}

inline ::std::ostream& operator<<(::std::ostream& out,
                                  const ConfigDescription& o) {
  return out << o.toString().string();
}

}  // namespace android

#endif  // ANDROIDFW_CONFIG_DESCRIPTION_H
