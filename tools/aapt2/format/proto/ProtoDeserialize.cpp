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

#include "format/proto/ProtoDeserialize.h"

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "android-base/logging.h"
#include "android-base/macros.h"
#include "androidfw/Locale.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Util.h"

using ::android::ConfigDescription;
using ::android::LocaleValue;
using ::android::ResStringPool;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace aapt {

namespace {

class ReferenceIdToNameVisitor : public DescendingValueVisitor {
 public:
  using DescendingValueVisitor::Visit;

  explicit ReferenceIdToNameVisitor(const std::map<ResourceId, ResourceNameRef>* mapping)
      : mapping_(mapping) {
    CHECK(mapping_ != nullptr);
  }

  void Visit(Reference* reference) override {
    if (!reference->id || !reference->id.value().is_valid()) {
      return;
    }

    ResourceId id = reference->id.value();
    auto cache_iter = mapping_->find(id);
    if (cache_iter != mapping_->end()) {
      reference->name = cache_iter->second.ToResourceName();
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceIdToNameVisitor);

  const std::map<ResourceId, ResourceNameRef>* mapping_;
};

}  // namespace

bool DeserializeConfigFromPb(const pb::Configuration& pb_config, ConfigDescription* out_config,
                             std::string* out_error) {
  out_config->mcc = static_cast<uint16_t>(pb_config.mcc());
  out_config->mnc = static_cast<uint16_t>(pb_config.mnc());

  if (!pb_config.locale().empty()) {
    LocaleValue lv;
    if (!lv.InitFromBcp47Tag(pb_config.locale())) {
      std::ostringstream error;
      error << "configuration has invalid locale '" << pb_config.locale() << "'";
      *out_error = error.str();
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
  out_config->grammaticalInflection = pb_config.grammatical_gender();
  return true;
}

static void DeserializeSourceFromPb(const pb::Source& pb_source, const ResStringPool& src_pool,
                                    android::Source* out_source) {
  out_source->path = android::util::GetString(src_pool, pb_source.path_idx());
  out_source->line = static_cast<size_t>(pb_source.position().line_number());
}

static Visibility::Level DeserializeVisibilityFromPb(const pb::Visibility::Level& pb_level) {
  switch (pb_level) {
    case pb::Visibility::PRIVATE:
      return Visibility::Level::kPrivate;
    case pb::Visibility::PUBLIC:
      return Visibility::Level::kPublic;
    default:
      break;
  }
  return Visibility::Level::kUndefined;
}

bool DeserializeOverlayableItemFromPb(const pb::OverlayableItem& pb_overlayable,
                                      const android::ResStringPool& src_pool,
                                      OverlayableItem* out_overlayable, std::string* out_error) {
  for (const int policy : pb_overlayable.policy()) {
    switch (policy) {
      case pb::OverlayableItem::PUBLIC:
        out_overlayable->policies |= PolicyFlags::PUBLIC;
        break;
      case pb::OverlayableItem::SYSTEM:
        out_overlayable->policies |= PolicyFlags::SYSTEM_PARTITION;
        break;
      case pb::OverlayableItem::VENDOR:
        out_overlayable->policies |= PolicyFlags::VENDOR_PARTITION;
        break;
      case pb::OverlayableItem::PRODUCT:
        out_overlayable->policies |= PolicyFlags::PRODUCT_PARTITION;
        break;
      case pb::OverlayableItem::SIGNATURE:
        out_overlayable->policies |= PolicyFlags::SIGNATURE;
        break;
      case pb::OverlayableItem::ODM:
        out_overlayable->policies |= PolicyFlags::ODM_PARTITION;
        break;
      case pb::OverlayableItem::OEM:
        out_overlayable->policies |= PolicyFlags::OEM_PARTITION;
        break;
      case pb::OverlayableItem::ACTOR:
        out_overlayable->policies |= PolicyFlags::ACTOR_SIGNATURE;
        break;
      case pb::OverlayableItem::CONFIG_SIGNATURE:
        out_overlayable->policies |= PolicyFlags::CONFIG_SIGNATURE;
        break;
      default:
        *out_error = "unknown overlayable policy";
        return false;
    }
  }

  if (pb_overlayable.has_source()) {
    DeserializeSourceFromPb(pb_overlayable.source(), src_pool, &out_overlayable->source);
  }

  out_overlayable->comment = pb_overlayable.comment();
  return true;
}

static bool DeserializePackageFromPb(const pb::Package& pb_package, const ResStringPool& src_pool,
                                     io::IFileCollection* files,
                                     const std::vector<std::shared_ptr<Overlayable>>& overlayables,
                                     ResourceTable* out_table, std::string* out_error) {
  std::map<ResourceId, ResourceNameRef> id_index;

  ResourceTablePackage* pkg = out_table->FindOrCreatePackage(pb_package.package_name());
  for (const pb::Type& pb_type : pb_package.type()) {
    auto res_type = ParseResourceNamedType(pb_type.name());
    if (!res_type) {
      std::ostringstream error;
      error << "unknown type '" << pb_type.name() << "'";
      *out_error = error.str();
      return false;
    }

    ResourceTableType* type = pkg->FindOrCreateType(*res_type);

    for (const pb::Entry& pb_entry : pb_type.entry()) {
      ResourceEntry* entry = type->CreateEntry(pb_entry.name());
      const ResourceId resource_id(
          pb_package.has_package_id() ? static_cast<uint8_t>(pb_package.package_id().id()) : 0u,
          pb_type.has_type_id() ? static_cast<uint8_t>(pb_type.type_id().id()) : 0u,
          pb_entry.has_entry_id() ? static_cast<uint16_t>(pb_entry.entry_id().id()) : 0u);
      if (resource_id.id != 0u) {
        entry->id = resource_id;
      }

      // Deserialize the symbol status (public/private with source and comments).
      if (pb_entry.has_visibility()) {
        const pb::Visibility& pb_visibility = pb_entry.visibility();
        if (pb_visibility.has_source()) {
          DeserializeSourceFromPb(pb_visibility.source(), src_pool, &entry->visibility.source);
        }
        entry->visibility.comment = pb_visibility.comment();
        entry->visibility.staged_api = pb_visibility.staged_api();

        const Visibility::Level level = DeserializeVisibilityFromPb(pb_visibility.level());
        entry->visibility.level = level;
        if (level == Visibility::Level::kPublic) {
          // Propagate the public visibility up to the Type.
          type->visibility_level = Visibility::Level::kPublic;
        } else if (level == Visibility::Level::kPrivate) {
          // Only propagate if no previous state was assigned.
          if (type->visibility_level == Visibility::Level::kUndefined) {
            type->visibility_level = Visibility::Level::kPrivate;
          }
        }
      }

      if (pb_entry.has_allow_new()) {
        const pb::AllowNew& pb_allow_new = pb_entry.allow_new();

        AllowNew allow_new;
        if (pb_allow_new.has_source()) {
          DeserializeSourceFromPb(pb_allow_new.source(), src_pool, &allow_new.source);
        }
        allow_new.comment = pb_allow_new.comment();
        entry->allow_new = std::move(allow_new);
      }

      if (pb_entry.has_overlayable_item()) {
        // Find the overlayable to which this item belongs
        pb::OverlayableItem pb_overlayable_item = pb_entry.overlayable_item();
        if (pb_overlayable_item.overlayable_idx() >= overlayables.size()) {
          *out_error =
              android::base::StringPrintf("invalid overlayable_idx value %d for entry %s/%s",
                                          pb_overlayable_item.overlayable_idx(),
                                          pb_type.name().c_str(), pb_entry.name().c_str());
          return false;
        }

        OverlayableItem overlayable_item(overlayables[pb_overlayable_item.overlayable_idx()]);
        if (!DeserializeOverlayableItemFromPb(pb_overlayable_item, src_pool, &overlayable_item,
                                              out_error)) {
          return false;
        }
        entry->overlayable_item = std::move(overlayable_item);
      }

      if (pb_entry.has_staged_id()) {
        const pb::StagedId& pb_staged_id = pb_entry.staged_id();

        StagedId staged_id;
        if (pb_staged_id.has_source()) {
          DeserializeSourceFromPb(pb_staged_id.source(), src_pool, &staged_id.source);
        }
        staged_id.id = pb_staged_id.staged_id();
        entry->staged_id = std::move(staged_id);
      }

      ResourceId resid(pb_package.package_id().id(), pb_type.type_id().id(),
                       pb_entry.entry_id().id());
      if (resid.is_valid()) {
        id_index[resid] = ResourceNameRef(pkg->name, type->named_type, entry->name);
      }

      for (const pb::ConfigValue& pb_config_value : pb_entry.config_value()) {
        const pb::Configuration& pb_config = pb_config_value.config();

        ConfigDescription config;
        if (!DeserializeConfigFromPb(pb_config, &config, out_error)) {
          return false;
        }

        ResourceConfigValue* config_value = entry->FindOrCreateValue(config, pb_config.product());
        if (config_value->value != nullptr) {
          *out_error = "duplicate configuration in resource table";
          return false;
        }

        config_value->value = DeserializeValueFromPb(pb_config_value.value(), src_pool, config,
                                                     &out_table->string_pool, files, out_error);
        if (config_value->value == nullptr) {
          return false;
        }
      }
    }
  }

  ReferenceIdToNameVisitor visitor(&id_index);
  VisitAllValuesInPackage(pkg, &visitor);
  return true;
}

bool DeserializeTableFromPb(const pb::ResourceTable& pb_table, io::IFileCollection* files,
                            ResourceTable* out_table, std::string* out_error) {
  // We import the android namespace because on Windows NO_ERROR is a macro, not an enum, which
  // causes errors when qualifying it with android::
  using namespace android;

  ResStringPool source_pool;
  if (pb_table.has_source_pool()) {
    status_t result = source_pool.setTo(pb_table.source_pool().data().data(),
                                        pb_table.source_pool().data().size());
    if (result != NO_ERROR) {
      *out_error = "invalid source pool";
      return false;
    }
  }

  for (const pb::DynamicRefTable& dynamic_ref : pb_table.dynamic_ref_table()) {
    out_table->included_packages_.insert(
        {dynamic_ref.package_id().id(), dynamic_ref.package_name()});
  }

  // Deserialize the overlayable groups of the table
  std::vector<std::shared_ptr<Overlayable>> overlayables;
  for (const pb::Overlayable& pb_overlayable : pb_table.overlayable()) {
    auto group = std::make_shared<Overlayable>(pb_overlayable.name(), pb_overlayable.actor());
    if (pb_overlayable.has_source()) {
      DeserializeSourceFromPb(pb_overlayable.source(), source_pool, &group->source);
    }
    overlayables.push_back(group);
  }

  for (const pb::Package& pb_package : pb_table.package()) {
    if (!DeserializePackageFromPb(pb_package, source_pool, files, overlayables, out_table,
                                  out_error)) {
      return false;
    }
  }
  return true;
}

static ResourceFile::Type DeserializeFileReferenceTypeFromPb(const pb::FileReference::Type& type) {
  switch (type) {
    case pb::FileReference::BINARY_XML:
      return ResourceFile::Type::kBinaryXml;
    case pb::FileReference::PROTO_XML:
      return ResourceFile::Type::kProtoXml;
    case pb::FileReference::PNG:
      return ResourceFile::Type::kPng;
    default:
      return ResourceFile::Type::kUnknown;
  }
}

bool DeserializeCompiledFileFromPb(const pb::internal::CompiledFile& pb_file,
                                   ResourceFile* out_file, std::string* out_error) {
  ResourceNameRef name_ref;
  if (!ResourceUtils::ParseResourceName(pb_file.resource_name(), &name_ref)) {
    std::ostringstream error;
    error << "invalid resource name in compiled file header: " << pb_file.resource_name();
    *out_error = error.str();
    return false;
  }

  out_file->name = name_ref.ToResourceName();
  out_file->source.path = pb_file.source_path();
  out_file->type = DeserializeFileReferenceTypeFromPb(pb_file.type());

  std::string config_error;
  if (!DeserializeConfigFromPb(pb_file.config(), &out_file->config, &config_error)) {
    std::ostringstream error;
    error << "invalid resource configuration in compiled file header: " << config_error;
    *out_error = error.str();
    return false;
  }

  for (const pb::internal::CompiledFile_Symbol& pb_symbol : pb_file.exported_symbol()) {
    if (!ResourceUtils::ParseResourceName(pb_symbol.resource_name(), &name_ref)) {
      std::ostringstream error;
      error << "invalid resource name for exported symbol in compiled file header: "
            << pb_file.resource_name();
      *out_error = error.str();
      return false;
    }

    size_t line = 0u;
    if (pb_symbol.has_source()) {
      line = pb_symbol.source().line_number();
    }
    out_file->exported_symbols.push_back(SourcedResourceName{name_ref.ToResourceName(), line});
  }
  return true;
}

static Reference::Type DeserializeReferenceTypeFromPb(const pb::Reference_Type& pb_type) {
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

static bool DeserializeReferenceFromPb(const pb::Reference& pb_ref, Reference* out_ref,
                                       std::string* out_error) {
  out_ref->reference_type = DeserializeReferenceTypeFromPb(pb_ref.type());
  out_ref->private_reference = pb_ref.private_();
  out_ref->is_dynamic = pb_ref.is_dynamic().value();

  if (pb_ref.id() != 0) {
    out_ref->id = ResourceId(pb_ref.id());
  }

  if (!pb_ref.name().empty()) {
    ResourceNameRef name_ref;
    if (!ResourceUtils::ParseResourceName(pb_ref.name(), &name_ref, nullptr)) {
      std::ostringstream error;
      error << "reference has invalid resource name '" << pb_ref.name() << "'";
      *out_error = error.str();
      return false;
    }
    out_ref->name = name_ref.ToResourceName();
  }
  if (pb_ref.type_flags() != 0) {
    out_ref->type_flags = pb_ref.type_flags();
  }
  out_ref->allow_raw = pb_ref.allow_raw();
  return true;
}

static bool DeserializeMacroFromPb(const pb::MacroBody& pb_ref, Macro* out_ref,
                                   std::string* out_error) {
  out_ref->raw_value = pb_ref.raw_string();

  if (pb_ref.has_style_string()) {
    out_ref->style_string.str = pb_ref.style_string().str();
    for (const auto& span : pb_ref.style_string().spans()) {
      out_ref->style_string.spans.emplace_back(android::Span{
          .name = span.name(), .first_char = span.start_index(), .last_char = span.end_index()});
    }
  }

  for (const auto& untranslatable_section : pb_ref.untranslatable_sections()) {
    out_ref->untranslatable_sections.emplace_back(
        UntranslatableSection{.start = static_cast<size_t>(untranslatable_section.start_index()),
                              .end = static_cast<size_t>(untranslatable_section.end_index())});
  }

  for (const auto& namespace_decls : pb_ref.namespace_stack()) {
    out_ref->alias_namespaces.emplace_back(
        Macro::Namespace{.alias = namespace_decls.prefix(),
                         .package_name = namespace_decls.package_name(),
                         .is_private = namespace_decls.is_private()});
  }

  return true;
}

template <typename T>
static void DeserializeItemMetaDataFromPb(const T& pb_item, const android::ResStringPool& src_pool,
                                          Value* out_value) {
  if (pb_item.has_source()) {
    android::Source source;
    DeserializeSourceFromPb(pb_item.source(), src_pool, &source);
    out_value->SetSource(std::move(source));
  }
  out_value->SetComment(pb_item.comment());
}

static size_t DeserializePluralEnumFromPb(const pb::Plural_Arity& arity) {
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

std::unique_ptr<Value> DeserializeValueFromPb(const pb::Value& pb_value,
                                              const android::ResStringPool& src_pool,
                                              const ConfigDescription& config,
                                              android::StringPool* value_pool,
                                              io::IFileCollection* files, std::string* out_error) {
  std::unique_ptr<Value> value;
  if (pb_value.has_item()) {
    value = DeserializeItemFromPb(pb_value.item(), src_pool, config, value_pool, files, out_error);
    if (value == nullptr) {
      return {};
    }

  } else if (pb_value.has_compound_value()) {
    const pb::CompoundValue& pb_compound_value = pb_value.compound_value();
    switch (pb_compound_value.value_case()) {
      case pb::CompoundValue::kAttr: {
        const pb::Attribute& pb_attr = pb_compound_value.attr();
        std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(pb_attr.format_flags());
        attr->min_int = pb_attr.min_int();
        attr->max_int = pb_attr.max_int();
        for (const pb::Attribute_Symbol& pb_symbol : pb_attr.symbol()) {
          Attribute::Symbol symbol;
          DeserializeItemMetaDataFromPb(pb_symbol, src_pool, &symbol.symbol);
          if (!DeserializeReferenceFromPb(pb_symbol.name(), &symbol.symbol, out_error)) {
            return {};
          }
          symbol.value = pb_symbol.value();
          symbol.type = pb_symbol.type() != 0U ? pb_symbol.type()
                                               : android::Res_value::TYPE_INT_DEC;
          attr->symbols.push_back(std::move(symbol));
        }
        value = std::move(attr);
      } break;

      case pb::CompoundValue::kStyle: {
        const pb::Style& pb_style = pb_compound_value.style();
        std::unique_ptr<Style> style = util::make_unique<Style>();
        if (pb_style.has_parent()) {
          style->parent = Reference();
          if (!DeserializeReferenceFromPb(pb_style.parent(), &style->parent.value(), out_error)) {
            return {};
          }

          if (pb_style.has_parent_source()) {
            android::Source parent_source;
            DeserializeSourceFromPb(pb_style.parent_source(), src_pool, &parent_source);
            style->parent.value().SetSource(std::move(parent_source));
          }
        }

        for (const pb::Style_Entry& pb_entry : pb_style.entry()) {
          Style::Entry entry;
          if (!DeserializeReferenceFromPb(pb_entry.key(), &entry.key, out_error)) {
            return {};
          }
          DeserializeItemMetaDataFromPb(pb_entry, src_pool, &entry.key);
          entry.value = DeserializeItemFromPb(pb_entry.item(), src_pool, config, value_pool, files,
                                              out_error);
          if (entry.value == nullptr) {
            return {};
          }

          // Copy the meta-data into the value as well.
          DeserializeItemMetaDataFromPb(pb_entry, src_pool, entry.value.get());
          style->entries.push_back(std::move(entry));
        }
        value = std::move(style);
      } break;

      case pb::CompoundValue::kStyleable: {
        const pb::Styleable& pb_styleable = pb_compound_value.styleable();
        std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
        for (const pb::Styleable_Entry& pb_entry : pb_styleable.entry()) {
          Reference attr_ref;
          DeserializeItemMetaDataFromPb(pb_entry, src_pool, &attr_ref);
          DeserializeReferenceFromPb(pb_entry.attr(), &attr_ref, out_error);
          styleable->entries.push_back(std::move(attr_ref));
        }
        value = std::move(styleable);
      } break;

      case pb::CompoundValue::kArray: {
        const pb::Array& pb_array = pb_compound_value.array();
        std::unique_ptr<Array> array = util::make_unique<Array>();
        for (const pb::Array_Element& pb_entry : pb_array.element()) {
          std::unique_ptr<Item> item = DeserializeItemFromPb(pb_entry.item(), src_pool, config,
                                                             value_pool, files, out_error);
          if (item == nullptr) {
            return {};
          }

          DeserializeItemMetaDataFromPb(pb_entry, src_pool, item.get());
          array->elements.push_back(std::move(item));
        }
        value = std::move(array);
      } break;

      case pb::CompoundValue::kPlural: {
        const pb::Plural& pb_plural = pb_compound_value.plural();
        std::unique_ptr<Plural> plural = util::make_unique<Plural>();
        for (const pb::Plural_Entry& pb_entry : pb_plural.entry()) {
          size_t plural_idx = DeserializePluralEnumFromPb(pb_entry.arity());
          plural->values[plural_idx] = DeserializeItemFromPb(pb_entry.item(), src_pool, config,
                                                             value_pool, files, out_error);
          if (!plural->values[plural_idx]) {
            return {};
          }

          DeserializeItemMetaDataFromPb(pb_entry, src_pool, plural->values[plural_idx].get());
        }
        value = std::move(plural);
      } break;

      case pb::CompoundValue::kMacro: {
        const pb::MacroBody& pb_macro = pb_compound_value.macro();
        auto macro = std::make_unique<Macro>();
        if (!DeserializeMacroFromPb(pb_macro, macro.get(), out_error)) {
          return {};
        }
        value = std::move(macro);
      } break;

      default:
        LOG(FATAL) << "unknown compound value: " << (int)pb_compound_value.value_case();
        break;
    }
  } else {
    LOG(FATAL) << "unknown value: " << (int)pb_value.value_case();
    return {};
  }

  CHECK(value) << "forgot to set value";

  value->SetWeak(pb_value.weak());
  DeserializeItemMetaDataFromPb(pb_value, src_pool, value.get());
  return value;
}

std::unique_ptr<Item> DeserializeItemFromPb(const pb::Item& pb_item,
                                            const android::ResStringPool& src_pool,
                                            const ConfigDescription& config,
                                            android::StringPool* value_pool,
                                            io::IFileCollection* files, std::string* out_error) {
  switch (pb_item.value_case()) {
    case pb::Item::kRef: {
      const pb::Reference& pb_ref = pb_item.ref();
      std::unique_ptr<Reference> ref = util::make_unique<Reference>();
      if (!DeserializeReferenceFromPb(pb_ref, ref.get(), out_error)) {
        return {};
      }
      return std::move(ref);
    } break;

    case pb::Item::kPrim: {
      const pb::Primitive& pb_prim = pb_item.prim();
      android::Res_value val = {};
      switch (pb_prim.oneof_value_case()) {
        case pb::Primitive::kNullValue: {
          val.dataType = android::Res_value::TYPE_NULL;
          val.data = android::Res_value::DATA_NULL_UNDEFINED;
        } break;
        case pb::Primitive::kEmptyValue: {
          val.dataType = android::Res_value::TYPE_NULL;
          val.data = android::Res_value::DATA_NULL_EMPTY;
        } break;
        case pb::Primitive::kFloatValue: {
          val.dataType = android::Res_value::TYPE_FLOAT;
          float float_val = pb_prim.float_value();
          val.data = *(uint32_t*)&float_val;
        } break;
        case pb::Primitive::kDimensionValue: {
          val.dataType = android::Res_value::TYPE_DIMENSION;
          val.data  = pb_prim.dimension_value();
        } break;
        case pb::Primitive::kFractionValue: {
          val.dataType = android::Res_value::TYPE_FRACTION;
          val.data  = pb_prim.fraction_value();
        } break;
        case pb::Primitive::kIntDecimalValue: {
          val.dataType = android::Res_value::TYPE_INT_DEC;
          val.data = static_cast<uint32_t>(pb_prim.int_decimal_value());
        } break;
        case pb::Primitive::kIntHexadecimalValue: {
          val.dataType = android::Res_value::TYPE_INT_HEX;
          val.data = pb_prim.int_hexadecimal_value();
        } break;
        case pb::Primitive::kBooleanValue: {
          val.dataType = android::Res_value::TYPE_INT_BOOLEAN;
          val.data = pb_prim.boolean_value() ? 0xFFFFFFFF : 0x0;
        } break;
        case pb::Primitive::kColorArgb8Value: {
          val.dataType = android::Res_value::TYPE_INT_COLOR_ARGB8;
          val.data = pb_prim.color_argb8_value();
        } break;
        case pb::Primitive::kColorRgb8Value: {
          val.dataType = android::Res_value::TYPE_INT_COLOR_RGB8;
          val.data = pb_prim.color_rgb8_value();
        } break;
        case pb::Primitive::kColorArgb4Value: {
          val.dataType = android::Res_value::TYPE_INT_COLOR_ARGB4;
          val.data = pb_prim.color_argb4_value();
        } break;
        case pb::Primitive::kColorRgb4Value: {
          val.dataType = android::Res_value::TYPE_INT_COLOR_RGB4;
          val.data = pb_prim.color_rgb4_value();
        } break;
        case pb::Primitive::kDimensionValueDeprecated: {  // DEPRECATED
          val.dataType = android::Res_value::TYPE_DIMENSION;
          float dimen_val = pb_prim.dimension_value_deprecated();
          val.data = *(uint32_t*)&dimen_val;
        } break;
        case pb::Primitive::kFractionValueDeprecated: {  // DEPRECATED
          val.dataType = android::Res_value::TYPE_FRACTION;
          float fraction_val = pb_prim.fraction_value_deprecated();
          val.data = *(uint32_t*)&fraction_val;
        } break;
        default: {
          LOG(FATAL) << "Unexpected Primitive type: "
                     << static_cast<uint32_t>(pb_prim.oneof_value_case());
          return {};
        } break;
      }
      return util::make_unique<BinaryPrimitive>(val);
    } break;

    case pb::Item::kId: {
      return util::make_unique<Id>();
    } break;

    case pb::Item::kStr: {
      return util::make_unique<String>(
          value_pool->MakeRef(pb_item.str().value(), android::StringPool::Context(config)));
    } break;

    case pb::Item::kRawStr: {
      return util::make_unique<RawString>(
          value_pool->MakeRef(pb_item.raw_str().value(), android::StringPool::Context(config)));
    } break;

    case pb::Item::kStyledStr: {
      const pb::StyledString& pb_str = pb_item.styled_str();
      android::StyleString style_str{pb_str.value()};
      for (const pb::StyledString::Span& pb_span : pb_str.span()) {
        style_str.spans.push_back(
            android::Span{pb_span.tag(), pb_span.first_char(), pb_span.last_char()});
      }
      return util::make_unique<StyledString>(value_pool->MakeRef(
          style_str,
          android::StringPool::Context(android::StringPool::Context::kNormalPriority, config)));
    } break;

    case pb::Item::kFile: {
      const pb::FileReference& pb_file = pb_item.file();
      std::unique_ptr<FileReference> file_ref =
          util::make_unique<FileReference>(value_pool->MakeRef(
              pb_file.path(),
              android::StringPool::Context(android::StringPool::Context::kHighPriority, config)));
      file_ref->type = DeserializeFileReferenceTypeFromPb(pb_file.type());
      if (files != nullptr) {
        file_ref->file = files->FindFile(*file_ref->path);
      }
      return std::move(file_ref);
    } break;

    default:
      LOG(FATAL) << "unknown item: " << (int)pb_item.value_case();
      break;
  }
  return {};
}

std::unique_ptr<xml::XmlResource> DeserializeXmlResourceFromPb(const pb::XmlNode& pb_node,
                                                               std::string* out_error) {
  if (!pb_node.has_element()) {
    return {};
  }

  std::unique_ptr<xml::XmlResource> resource = util::make_unique<xml::XmlResource>();
  resource->root = util::make_unique<xml::Element>();
  if (!DeserializeXmlFromPb(pb_node, resource->root.get(), &resource->string_pool, out_error)) {
    return {};
  }
  return resource;
}

bool DeserializeXmlFromPb(const pb::XmlNode& pb_node, xml::Element* out_el,
                          android::StringPool* value_pool, std::string* out_error) {
  const pb::XmlElement& pb_el = pb_node.element();
  out_el->name = pb_el.name();
  out_el->namespace_uri = pb_el.namespace_uri();
  out_el->line_number = pb_node.source().line_number();
  out_el->column_number = pb_node.source().column_number();

  for (const pb::XmlNamespace& pb_ns : pb_el.namespace_declaration()) {
    xml::NamespaceDecl decl;
    decl.uri = pb_ns.uri();
    decl.prefix = pb_ns.prefix();
    decl.line_number = pb_ns.source().line_number();
    decl.column_number = pb_ns.source().column_number();
    out_el->namespace_decls.push_back(std::move(decl));
  }

  for (const pb::XmlAttribute& pb_attr : pb_el.attribute()) {
    xml::Attribute attr;
    attr.name = pb_attr.name();
    attr.namespace_uri = pb_attr.namespace_uri();
    attr.value = pb_attr.value();
    if (pb_attr.resource_id() != 0u) {
      attr.compiled_attribute = xml::AaptAttribute{Attribute(), ResourceId(pb_attr.resource_id())};
    }
    if (pb_attr.has_compiled_item()) {
      attr.compiled_value =
          DeserializeItemFromPb(pb_attr.compiled_item(), {}, {}, value_pool, nullptr, out_error);
      if (attr.compiled_value == nullptr) {
        return {};
      }
      attr.compiled_value->SetSource(android::Source().WithLine(pb_attr.source().line_number()));
    }
    out_el->attributes.push_back(std::move(attr));
  }

  // Deserialize the children.
  for (const pb::XmlNode& pb_child : pb_el.child()) {
    switch (pb_child.node_case()) {
      case pb::XmlNode::NodeCase::kText: {
        std::unique_ptr<xml::Text> text = util::make_unique<xml::Text>();
        text->line_number = pb_child.source().line_number();
        text->column_number = pb_child.source().column_number();
        text->text = pb_child.text();
        out_el->AppendChild(std::move(text));
      } break;

      case pb::XmlNode::NodeCase::kElement: {
        std::unique_ptr<xml::Element> child_el = util::make_unique<xml::Element>();
        if (!DeserializeXmlFromPb(pb_child, child_el.get(), value_pool, out_error)) {
          return false;
        }
        out_el->AppendChild(std::move(child_el));
      } break;

      default:
        LOG(FATAL) << "unknown XmlNode " << (int)pb_child.node_case();
        break;
    }
  }
  return true;
}

}  // namespace aapt
