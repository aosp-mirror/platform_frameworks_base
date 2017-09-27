/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "proto/ProtoHelpers.h"

#include "Locale.h"

namespace aapt {

void SerializeStringPoolToPb(const StringPool& pool, pb::StringPool* out_pb_pool) {
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool);

  std::string* data = out_pb_pool->mutable_data();
  data->reserve(buffer.size());

  size_t offset = 0;
  for (const BigBuffer::Block& block : buffer) {
    data->insert(data->begin() + offset, block.buffer.get(), block.buffer.get() + block.size);
    offset += block.size;
  }
}

void SerializeSourceToPb(const Source& source, StringPool* src_pool, pb::Source* out_pb_source) {
  StringPool::Ref ref = src_pool->MakeRef(source.path);
  out_pb_source->set_path_idx(static_cast<uint32_t>(ref.index()));
  if (source.line) {
    out_pb_source->mutable_position()->set_line_number(static_cast<uint32_t>(source.line.value()));
  }
}

void DeserializeSourceFromPb(const pb::Source& pb_source, const android::ResStringPool& src_pool,
                             Source* out_source) {
  out_source->path = util::GetString(src_pool, pb_source.path_idx());
  out_source->line = static_cast<size_t>(pb_source.position().line_number());
}

pb::SymbolStatus_Visibility SerializeVisibilityToPb(SymbolState state) {
  switch (state) {
    case SymbolState::kPrivate:
      return pb::SymbolStatus_Visibility_PRIVATE;
    case SymbolState::kPublic:
      return pb::SymbolStatus_Visibility_PUBLIC;
    default:
      break;
  }
  return pb::SymbolStatus_Visibility_UNKNOWN;
}

SymbolState DeserializeVisibilityFromPb(pb::SymbolStatus_Visibility pb_visibility) {
  switch (pb_visibility) {
    case pb::SymbolStatus_Visibility_PRIVATE:
      return SymbolState::kPrivate;
    case pb::SymbolStatus_Visibility_PUBLIC:
      return SymbolState::kPublic;
    default:
      break;
  }
  return SymbolState::kUndefined;
}

void SerializeConfig(const ConfigDescription& config, pb::Configuration* out_pb_config) {
  out_pb_config->set_mcc(config.mcc);
  out_pb_config->set_mnc(config.mnc);
  out_pb_config->set_locale(config.GetBcp47LanguageTag());

  switch (config.screenLayout & ConfigDescription::MASK_LAYOUTDIR) {
    case ConfigDescription::LAYOUTDIR_LTR:
      out_pb_config->set_layout_direction(pb::Configuration_LayoutDirection_LAYOUT_DIRECTION_LTR);
      break;

    case ConfigDescription::LAYOUTDIR_RTL:
      out_pb_config->set_layout_direction(pb::Configuration_LayoutDirection_LAYOUT_DIRECTION_RTL);
      break;
  }

  out_pb_config->set_screen_width(config.screenWidth);
  out_pb_config->set_screen_height(config.screenHeight);
  out_pb_config->set_screen_width_dp(config.screenWidthDp);
  out_pb_config->set_screen_height_dp(config.screenHeightDp);
  out_pb_config->set_smallest_screen_width_dp(config.smallestScreenWidthDp);

  switch (config.screenLayout & ConfigDescription::MASK_SCREENSIZE) {
    case ConfigDescription::SCREENSIZE_SMALL:
      out_pb_config->set_screen_layout_size(
          pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_SMALL);
      break;

    case ConfigDescription::SCREENSIZE_NORMAL:
      out_pb_config->set_screen_layout_size(
          pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_NORMAL);
      break;

    case ConfigDescription::SCREENSIZE_LARGE:
      out_pb_config->set_screen_layout_size(
          pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_LARGE);
      break;

    case ConfigDescription::SCREENSIZE_XLARGE:
      out_pb_config->set_screen_layout_size(
          pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_XLARGE);
      break;
  }

  switch (config.screenLayout & ConfigDescription::MASK_SCREENLONG) {
    case ConfigDescription::SCREENLONG_YES:
      out_pb_config->set_screen_layout_long(
          pb::Configuration_ScreenLayoutLong_SCREEN_LAYOUT_LONG_LONG);
      break;

    case ConfigDescription::SCREENLONG_NO:
      out_pb_config->set_screen_layout_long(
          pb::Configuration_ScreenLayoutLong_SCREEN_LAYOUT_LONG_NOTLONG);
      break;
  }

  switch (config.screenLayout2 & ConfigDescription::MASK_SCREENROUND) {
    case ConfigDescription::SCREENROUND_YES:
      out_pb_config->set_screen_round(pb::Configuration_ScreenRound_SCREEN_ROUND_ROUND);
      break;

    case ConfigDescription::SCREENROUND_NO:
      out_pb_config->set_screen_round(pb::Configuration_ScreenRound_SCREEN_ROUND_NOTROUND);
      break;
  }

  switch (config.colorMode & ConfigDescription::MASK_WIDE_COLOR_GAMUT) {
    case ConfigDescription::WIDE_COLOR_GAMUT_YES:
      out_pb_config->set_wide_color_gamut(pb::Configuration_WideColorGamut_WIDE_COLOR_GAMUT_WIDECG);
      break;

    case ConfigDescription::WIDE_COLOR_GAMUT_NO:
      out_pb_config->set_wide_color_gamut(
          pb::Configuration_WideColorGamut_WIDE_COLOR_GAMUT_NOWIDECG);
      break;
  }

  switch (config.colorMode & ConfigDescription::MASK_HDR) {
    case ConfigDescription::HDR_YES:
      out_pb_config->set_hdr(pb::Configuration_Hdr_HDR_HIGHDR);
      break;

    case ConfigDescription::HDR_NO:
      out_pb_config->set_hdr(pb::Configuration_Hdr_HDR_LOWDR);
      break;
  }

  switch (config.orientation) {
    case ConfigDescription::ORIENTATION_PORT:
      out_pb_config->set_orientation(pb::Configuration_Orientation_ORIENTATION_PORT);
      break;

    case ConfigDescription::ORIENTATION_LAND:
      out_pb_config->set_orientation(pb::Configuration_Orientation_ORIENTATION_LAND);
      break;

    case ConfigDescription::ORIENTATION_SQUARE:
      out_pb_config->set_orientation(pb::Configuration_Orientation_ORIENTATION_SQUARE);
      break;
  }

  switch (config.uiMode & ConfigDescription::MASK_UI_MODE_TYPE) {
    case ConfigDescription::UI_MODE_TYPE_NORMAL:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_NORMAL);
      break;

    case ConfigDescription::UI_MODE_TYPE_DESK:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_DESK);
      break;

    case ConfigDescription::UI_MODE_TYPE_CAR:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_CAR);
      break;

    case ConfigDescription::UI_MODE_TYPE_TELEVISION:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_TELEVISION);
      break;

    case ConfigDescription::UI_MODE_TYPE_APPLIANCE:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_APPLIANCE);
      break;

    case ConfigDescription::UI_MODE_TYPE_WATCH:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_WATCH);
      break;

    case ConfigDescription::UI_MODE_TYPE_VR_HEADSET:
      out_pb_config->set_ui_mode_type(pb::Configuration_UiModeType_UI_MODE_TYPE_VRHEADSET);
      break;
  }

  switch (config.uiMode & ConfigDescription::MASK_UI_MODE_NIGHT) {
    case ConfigDescription::UI_MODE_NIGHT_YES:
      out_pb_config->set_ui_mode_night(pb::Configuration_UiModeNight_UI_MODE_NIGHT_NIGHT);
      break;

    case ConfigDescription::UI_MODE_NIGHT_NO:
      out_pb_config->set_ui_mode_night(pb::Configuration_UiModeNight_UI_MODE_NIGHT_NOTNIGHT);
      break;
  }

  out_pb_config->set_density(config.density);

  switch (config.touchscreen) {
    case ConfigDescription::TOUCHSCREEN_NOTOUCH:
      out_pb_config->set_touchscreen(pb::Configuration_Touchscreen_TOUCHSCREEN_NOTOUCH);
      break;

    case ConfigDescription::TOUCHSCREEN_STYLUS:
      out_pb_config->set_touchscreen(pb::Configuration_Touchscreen_TOUCHSCREEN_STYLUS);
      break;

    case ConfigDescription::TOUCHSCREEN_FINGER:
      out_pb_config->set_touchscreen(pb::Configuration_Touchscreen_TOUCHSCREEN_FINGER);
      break;
  }

  switch (config.inputFlags & ConfigDescription::MASK_KEYSHIDDEN) {
    case ConfigDescription::KEYSHIDDEN_NO:
      out_pb_config->set_keys_hidden(pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSEXPOSED);
      break;

    case ConfigDescription::KEYSHIDDEN_YES:
      out_pb_config->set_keys_hidden(pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSHIDDEN);
      break;

    case ConfigDescription::KEYSHIDDEN_SOFT:
      out_pb_config->set_keys_hidden(pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSSOFT);
      break;
  }

  switch (config.keyboard) {
    case ConfigDescription::KEYBOARD_NOKEYS:
      out_pb_config->set_keyboard(pb::Configuration_Keyboard_KEYBOARD_NOKEYS);
      break;

    case ConfigDescription::KEYBOARD_QWERTY:
      out_pb_config->set_keyboard(pb::Configuration_Keyboard_KEYBOARD_QWERTY);
      break;

    case ConfigDescription::KEYBOARD_12KEY:
      out_pb_config->set_keyboard(pb::Configuration_Keyboard_KEYBOARD_TWELVEKEY);
      break;
  }

  switch (config.inputFlags & ConfigDescription::MASK_NAVHIDDEN) {
    case ConfigDescription::NAVHIDDEN_NO:
      out_pb_config->set_nav_hidden(pb::Configuration_NavHidden_NAV_HIDDEN_NAVEXPOSED);
      break;

    case ConfigDescription::NAVHIDDEN_YES:
      out_pb_config->set_nav_hidden(pb::Configuration_NavHidden_NAV_HIDDEN_NAVHIDDEN);
      break;
  }

  switch (config.navigation) {
    case ConfigDescription::NAVIGATION_NONAV:
      out_pb_config->set_navigation(pb::Configuration_Navigation_NAVIGATION_NONAV);
      break;

    case ConfigDescription::NAVIGATION_DPAD:
      out_pb_config->set_navigation(pb::Configuration_Navigation_NAVIGATION_DPAD);
      break;

    case ConfigDescription::NAVIGATION_TRACKBALL:
      out_pb_config->set_navigation(pb::Configuration_Navigation_NAVIGATION_TRACKBALL);
      break;

    case ConfigDescription::NAVIGATION_WHEEL:
      out_pb_config->set_navigation(pb::Configuration_Navigation_NAVIGATION_WHEEL);
      break;
  }

  out_pb_config->set_sdk_version(config.sdkVersion);
}

bool DeserializeConfigDescriptionFromPb(const pb::Configuration& pb_config,
                                        ConfigDescription* out_config) {
  out_config->mcc = static_cast<uint16_t>(pb_config.mcc());
  out_config->mnc = static_cast<uint16_t>(pb_config.mnc());

  if (!pb_config.locale().empty()) {
    LocaleValue lv;
    if (!lv.InitFromBcp47Tag(pb_config.locale())) {
      return false;
    }
    lv.WriteTo(out_config);
  }

  switch (pb_config.layout_direction()) {
    case pb::Configuration_LayoutDirection_LAYOUT_DIRECTION_LTR:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_LAYOUTDIR) |
                                 ConfigDescription::LAYOUTDIR_LTR;
      break;

    case pb::Configuration_LayoutDirection_LAYOUT_DIRECTION_RTL:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_LAYOUTDIR) |
                                 ConfigDescription::LAYOUTDIR_RTL;
      break;

    default:
      break;
  }

  out_config->smallestScreenWidthDp = static_cast<uint16_t>(pb_config.smallest_screen_width_dp());
  out_config->screenWidthDp = static_cast<uint16_t>(pb_config.screen_width_dp());
  out_config->screenHeightDp = static_cast<uint16_t>(pb_config.screen_height_dp());

  switch (pb_config.screen_layout_size()) {
    case pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_SMALL:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENSIZE) |
                                 ConfigDescription::SCREENSIZE_SMALL;
      break;

    case pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_NORMAL:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENSIZE) |
                                 ConfigDescription::SCREENSIZE_NORMAL;
      break;

    case pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_LARGE:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENSIZE) |
                                 ConfigDescription::SCREENSIZE_LARGE;
      break;

    case pb::Configuration_ScreenLayoutSize_SCREEN_LAYOUT_SIZE_XLARGE:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENSIZE) |
                                 ConfigDescription::SCREENSIZE_XLARGE;
      break;

    default:
      break;
  }

  switch (pb_config.screen_layout_long()) {
    case pb::Configuration_ScreenLayoutLong_SCREEN_LAYOUT_LONG_LONG:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENLONG) |
                                 ConfigDescription::SCREENLONG_YES;
      break;

    case pb::Configuration_ScreenLayoutLong_SCREEN_LAYOUT_LONG_NOTLONG:
      out_config->screenLayout = (out_config->screenLayout & ~ConfigDescription::MASK_SCREENLONG) |
                                 ConfigDescription::SCREENLONG_NO;
      break;

    default:
      break;
  }

  switch (pb_config.screen_round()) {
    case pb::Configuration_ScreenRound_SCREEN_ROUND_ROUND:
      out_config->screenLayout2 =
          (out_config->screenLayout2 & ~ConfigDescription::MASK_SCREENROUND) |
          ConfigDescription::SCREENROUND_YES;
      break;

    case pb::Configuration_ScreenRound_SCREEN_ROUND_NOTROUND:
      out_config->screenLayout2 =
          (out_config->screenLayout2 & ~ConfigDescription::MASK_SCREENROUND) |
          ConfigDescription::SCREENROUND_NO;
      break;

    default:
      break;
  }

  switch (pb_config.wide_color_gamut()) {
    case pb::Configuration_WideColorGamut_WIDE_COLOR_GAMUT_WIDECG:
      out_config->colorMode = (out_config->colorMode & ~ConfigDescription::MASK_WIDE_COLOR_GAMUT) |
                              ConfigDescription::WIDE_COLOR_GAMUT_YES;
      break;

    case pb::Configuration_WideColorGamut_WIDE_COLOR_GAMUT_NOWIDECG:
      out_config->colorMode = (out_config->colorMode & ~ConfigDescription::MASK_WIDE_COLOR_GAMUT) |
                              ConfigDescription::WIDE_COLOR_GAMUT_NO;
      break;

    default:
      break;
  }

  switch (pb_config.hdr()) {
    case pb::Configuration_Hdr_HDR_HIGHDR:
      out_config->colorMode =
          (out_config->colorMode & ~ConfigDescription::MASK_HDR) | ConfigDescription::HDR_YES;
      break;

    case pb::Configuration_Hdr_HDR_LOWDR:
      out_config->colorMode =
          (out_config->colorMode & ~ConfigDescription::MASK_HDR) | ConfigDescription::HDR_NO;
      break;

    default:
      break;
  }

  switch (pb_config.orientation()) {
    case pb::Configuration_Orientation_ORIENTATION_PORT:
      out_config->orientation = ConfigDescription::ORIENTATION_PORT;
      break;

    case pb::Configuration_Orientation_ORIENTATION_LAND:
      out_config->orientation = ConfigDescription::ORIENTATION_LAND;
      break;

    case pb::Configuration_Orientation_ORIENTATION_SQUARE:
      out_config->orientation = ConfigDescription::ORIENTATION_SQUARE;
      break;

    default:
      break;
  }

  switch (pb_config.ui_mode_type()) {
    case pb::Configuration_UiModeType_UI_MODE_TYPE_NORMAL:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_NORMAL;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_DESK:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_DESK;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_CAR:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_CAR;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_TELEVISION:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_TELEVISION;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_APPLIANCE:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_APPLIANCE;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_WATCH:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_WATCH;
      break;

    case pb::Configuration_UiModeType_UI_MODE_TYPE_VRHEADSET:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_TYPE) |
                           ConfigDescription::UI_MODE_TYPE_VR_HEADSET;
      break;

    default:
      break;
  }

  switch (pb_config.ui_mode_night()) {
    case pb::Configuration_UiModeNight_UI_MODE_NIGHT_NIGHT:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_NIGHT) |
                           ConfigDescription::UI_MODE_NIGHT_YES;
      break;

    case pb::Configuration_UiModeNight_UI_MODE_NIGHT_NOTNIGHT:
      out_config->uiMode = (out_config->uiMode & ~ConfigDescription::MASK_UI_MODE_NIGHT) |
                           ConfigDescription::UI_MODE_NIGHT_NO;
      break;

    default:
      break;
  }

  out_config->density = static_cast<uint16_t>(pb_config.density());

  switch (pb_config.touchscreen()) {
    case pb::Configuration_Touchscreen_TOUCHSCREEN_NOTOUCH:
      out_config->touchscreen = ConfigDescription::TOUCHSCREEN_NOTOUCH;
      break;

    case pb::Configuration_Touchscreen_TOUCHSCREEN_STYLUS:
      out_config->touchscreen = ConfigDescription::TOUCHSCREEN_STYLUS;
      break;

    case pb::Configuration_Touchscreen_TOUCHSCREEN_FINGER:
      out_config->touchscreen = ConfigDescription::TOUCHSCREEN_FINGER;
      break;

    default:
      break;
  }

  switch (pb_config.keys_hidden()) {
    case pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSEXPOSED:
      out_config->inputFlags = (out_config->inputFlags & ~ConfigDescription::MASK_KEYSHIDDEN) |
                               ConfigDescription::KEYSHIDDEN_NO;
      break;

    case pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSHIDDEN:
      out_config->inputFlags = (out_config->inputFlags & ~ConfigDescription::MASK_KEYSHIDDEN) |
                               ConfigDescription::KEYSHIDDEN_YES;
      break;

    case pb::Configuration_KeysHidden_KEYS_HIDDEN_KEYSSOFT:
      out_config->inputFlags = (out_config->inputFlags & ~ConfigDescription::MASK_KEYSHIDDEN) |
                               ConfigDescription::KEYSHIDDEN_SOFT;
      break;

    default:
      break;
  }

  switch (pb_config.keyboard()) {
    case pb::Configuration_Keyboard_KEYBOARD_NOKEYS:
      out_config->keyboard = ConfigDescription::KEYBOARD_NOKEYS;
      break;

    case pb::Configuration_Keyboard_KEYBOARD_QWERTY:
      out_config->keyboard = ConfigDescription::KEYBOARD_QWERTY;
      break;

    case pb::Configuration_Keyboard_KEYBOARD_TWELVEKEY:
      out_config->keyboard = ConfigDescription::KEYBOARD_12KEY;
      break;

    default:
      break;
  }

  switch (pb_config.nav_hidden()) {
    case pb::Configuration_NavHidden_NAV_HIDDEN_NAVEXPOSED:
      out_config->inputFlags = (out_config->inputFlags & ~ConfigDescription::MASK_NAVHIDDEN) |
                               ConfigDescription::NAVHIDDEN_NO;
      break;

    case pb::Configuration_NavHidden_NAV_HIDDEN_NAVHIDDEN:
      out_config->inputFlags = (out_config->inputFlags & ~ConfigDescription::MASK_NAVHIDDEN) |
                               ConfigDescription::NAVHIDDEN_YES;
      break;

    default:
      break;
  }

  switch (pb_config.navigation()) {
    case pb::Configuration_Navigation_NAVIGATION_NONAV:
      out_config->navigation = ConfigDescription::NAVIGATION_NONAV;
      break;

    case pb::Configuration_Navigation_NAVIGATION_DPAD:
      out_config->navigation = ConfigDescription::NAVIGATION_DPAD;
      break;

    case pb::Configuration_Navigation_NAVIGATION_TRACKBALL:
      out_config->navigation = ConfigDescription::NAVIGATION_TRACKBALL;
      break;

    case pb::Configuration_Navigation_NAVIGATION_WHEEL:
      out_config->navigation = ConfigDescription::NAVIGATION_WHEEL;
      break;

    default:
      break;
  }

  out_config->screenWidth = static_cast<uint16_t>(pb_config.screen_width());
  out_config->screenHeight = static_cast<uint16_t>(pb_config.screen_height());
  out_config->sdkVersion = static_cast<uint16_t>(pb_config.sdk_version());
  return true;
}

pb::Reference_Type SerializeReferenceTypeToPb(Reference::Type type) {
  switch (type) {
    case Reference::Type::kResource:
      return pb::Reference_Type_REFERENCE;
    case Reference::Type::kAttribute:
      return pb::Reference_Type_ATTRIBUTE;
    default:
      break;
  }
  return pb::Reference_Type_REFERENCE;
}

Reference::Type DeserializeReferenceTypeFromPb(pb::Reference_Type pb_type) {
  switch (pb_type) {
    case pb::Reference_Type_REFERENCE:
      return Reference::Type::kResource;
    case pb::Reference_Type_ATTRIBUTE:
      return Reference::Type::kAttribute;
    default:
      break;
  }
  return Reference::Type::kResource;
}

pb::Plural_Arity SerializePluralEnumToPb(size_t plural_idx) {
  switch (plural_idx) {
    case Plural::Zero:
      return pb::Plural_Arity_ZERO;
    case Plural::One:
      return pb::Plural_Arity_ONE;
    case Plural::Two:
      return pb::Plural_Arity_TWO;
    case Plural::Few:
      return pb::Plural_Arity_FEW;
    case Plural::Many:
      return pb::Plural_Arity_MANY;
    default:
      break;
  }
  return pb::Plural_Arity_OTHER;
}

size_t DeserializePluralEnumFromPb(pb::Plural_Arity arity) {
  switch (arity) {
    case pb::Plural_Arity_ZERO:
      return Plural::Zero;
    case pb::Plural_Arity_ONE:
      return Plural::One;
    case pb::Plural_Arity_TWO:
      return Plural::Two;
    case pb::Plural_Arity_FEW:
      return Plural::Few;
    case pb::Plural_Arity_MANY:
      return Plural::Many;
    default:
      break;
  }
  return Plural::Other;
}

}  // namespace aapt
