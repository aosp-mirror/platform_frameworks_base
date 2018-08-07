/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "Configuration"
#include <utils/Log.h>

#include <androidfw/AssetManager2.h>

#include <android_runtime/android_content_res_Configuration.h>
#include <android_runtime/android_util_AssetManager.h>

using namespace android;

AConfiguration* AConfiguration_new() {
    AConfiguration* config = new AConfiguration;
    memset(config, 0, sizeof(AConfiguration));
    return config;
}

void AConfiguration_delete(AConfiguration* config) {
    delete config;
}

void AConfiguration_fromAssetManager(AConfiguration* out, AAssetManager* am) {
    ScopedLock<AssetManager2> locked_mgr(*AssetManagerForNdkAssetManager(am));
    ResTable_config config = locked_mgr->GetConfiguration();

    // AConfiguration is not a virtual subclass, so we can memcpy.
    memcpy(out, &config, sizeof(config));
}

void AConfiguration_copy(AConfiguration* dest, AConfiguration* src) {
    *dest = *src;
}

int32_t AConfiguration_getMcc(AConfiguration* config) {
    return config->mcc;
}

int32_t AConfiguration_getMnc(AConfiguration* config) {
    return config->mnc;
}

void AConfiguration_getLanguage(AConfiguration* config, char* outLanguage) {
    outLanguage[0] = config->language[0];
    outLanguage[1] = config->language[1];
}

void AConfiguration_getCountry(AConfiguration* config, char* outCountry) {
    outCountry[0] = config->country[0];
    outCountry[1] = config->country[1];
}

int32_t AConfiguration_getOrientation(AConfiguration* config) {
    return config->orientation;
}

int32_t AConfiguration_getTouchscreen(AConfiguration* config) {
    return config->touchscreen;
}

int32_t AConfiguration_getDensity(AConfiguration* config) {
    return config->density;
}

int32_t AConfiguration_getKeyboard(AConfiguration* config) {
    return config->keyboard;
}

int32_t AConfiguration_getNavigation(AConfiguration* config) {
    return config->navigation;
}

int32_t AConfiguration_getKeysHidden(AConfiguration* config) {
    return config->inputFlags&ResTable_config::MASK_KEYSHIDDEN;
}

int32_t AConfiguration_getNavHidden(AConfiguration* config) {
    return (config->inputFlags&ResTable_config::MASK_NAVHIDDEN)
            >> ResTable_config::SHIFT_NAVHIDDEN;
}

int32_t AConfiguration_getSdkVersion(AConfiguration* config) {
    return config->sdkVersion;
}

int32_t AConfiguration_getScreenSize(AConfiguration* config) {
    return config->screenLayout&ResTable_config::MASK_SCREENSIZE;
}

int32_t AConfiguration_getScreenLong(AConfiguration* config) {
    return (config->screenLayout&ResTable_config::MASK_SCREENLONG)
            >> ResTable_config::SHIFT_SCREENLONG;
}

int32_t AConfiguration_getScreenRound(AConfiguration* config) {
    return (config->screenLayout2&ResTable_config::MASK_SCREENROUND);
}

int32_t AConfiguration_getUiModeType(AConfiguration* config) {
    return config->uiMode&ResTable_config::MASK_UI_MODE_TYPE;
}

int32_t AConfiguration_getUiModeNight(AConfiguration* config) {
    return (config->uiMode&ResTable_config::MASK_UI_MODE_NIGHT)
            >> ResTable_config::SHIFT_UI_MODE_NIGHT;

}

int32_t AConfiguration_getScreenWidthDp(AConfiguration* config) {
    return config->screenWidthDp;
}

int32_t AConfiguration_getScreenHeightDp(AConfiguration* config) {
    return config->screenHeightDp;
}

int32_t AConfiguration_getSmallestScreenWidthDp(AConfiguration* config) {
    return config->smallestScreenWidthDp;
}

int32_t AConfiguration_getLayoutDirection(AConfiguration* config) {
    return (config->screenLayout&ResTable_config::MASK_LAYOUTDIR)
            >> ResTable_config::SHIFT_LAYOUTDIR;
}

// ----------------------------------------------------------------------

void AConfiguration_setMcc(AConfiguration* config, int32_t mcc) {
    config->mcc = mcc;
}

void AConfiguration_setMnc(AConfiguration* config, int32_t mnc) {
    config->mnc = mnc;
}

void AConfiguration_setLanguage(AConfiguration* config, const char* language) {
    config->language[0] = language[0];
    config->language[1] = language[1];
}

void AConfiguration_setCountry(AConfiguration* config, const char* country) {
    config->country[0] = country[0];
    config->country[1] = country[1];
}

void AConfiguration_setOrientation(AConfiguration* config, int32_t orientation) {
    config->orientation = orientation;
}

void AConfiguration_setTouchscreen(AConfiguration* config, int32_t touchscreen) {
    config->touchscreen = touchscreen;
}

void AConfiguration_setDensity(AConfiguration* config, int32_t density) {
    config->density = density;
}

void AConfiguration_setKeyboard(AConfiguration* config, int32_t keyboard) {
    config->keyboard = keyboard;
}

void AConfiguration_setNavigation(AConfiguration* config, int32_t navigation) {
    config->navigation = navigation;
}

void AConfiguration_setKeysHidden(AConfiguration* config, int32_t keysHidden) {
    config->inputFlags = (config->inputFlags&~ResTable_config::MASK_KEYSHIDDEN)
            | (keysHidden&ResTable_config::MASK_KEYSHIDDEN);
}

void AConfiguration_setNavHidden(AConfiguration* config, int32_t navHidden) {
    config->inputFlags = (config->inputFlags&~ResTable_config::MASK_NAVHIDDEN)
            | ((navHidden<<ResTable_config::SHIFT_NAVHIDDEN)&ResTable_config::MASK_NAVHIDDEN);
}

void AConfiguration_setSdkVersion(AConfiguration* config, int32_t sdkVersion) {
    config->sdkVersion = sdkVersion;
}

void AConfiguration_setScreenSize(AConfiguration* config, int32_t screenSize) {
    config->screenLayout = (config->screenLayout&~ResTable_config::MASK_SCREENSIZE)
            | (screenSize&ResTable_config::MASK_SCREENSIZE);
}

void AConfiguration_setScreenLong(AConfiguration* config, int32_t screenLong) {
    config->screenLayout = (config->screenLayout&~ResTable_config::MASK_SCREENLONG)
            | ((screenLong<<ResTable_config::SHIFT_SCREENLONG)&ResTable_config::MASK_SCREENLONG);
}

void AConfiguration_setScreenRound(AConfiguration* config, int32_t screenRound) {
    config->screenLayout2 = (config->screenLayout2&~ResTable_config::MASK_SCREENROUND)
            | (screenRound&ResTable_config::MASK_SCREENROUND);
}

void AConfiguration_setUiModeType(AConfiguration* config, int32_t uiModeType) {
    config->uiMode = (config->uiMode&~ResTable_config::MASK_UI_MODE_TYPE)
            | (uiModeType&ResTable_config::MASK_UI_MODE_TYPE);
}

void AConfiguration_setUiModeNight(AConfiguration* config, int32_t uiModeNight) {
    config->uiMode = (config->uiMode&~ResTable_config::MASK_UI_MODE_NIGHT)
            | ((uiModeNight<<ResTable_config::SHIFT_UI_MODE_NIGHT)&ResTable_config::MASK_UI_MODE_NIGHT);

}

void AConfiguration_setScreenWidthDp(AConfiguration* config, int32_t value) {
    config->screenWidthDp = value;
}

void AConfiguration_setScreenHeightDp(AConfiguration* config, int32_t value) {
    config->screenHeightDp = value;
}

void AConfiguration_setSmallestScreenWidthDp(AConfiguration* config, int32_t value) {
    config->smallestScreenWidthDp = value;
}

void AConfiguration_setLayoutDirection(AConfiguration* config, int32_t value) {
    config->screenLayout = (config->screenLayout&~ResTable_config::MASK_LAYOUTDIR)
            | ((value<<ResTable_config::SHIFT_LAYOUTDIR)&ResTable_config::MASK_LAYOUTDIR);
}

// ----------------------------------------------------------------------

int32_t AConfiguration_diff(AConfiguration* config1, AConfiguration* config2) {
    return (config1->diff(*config2));
}

int32_t AConfiguration_match(AConfiguration* base, AConfiguration* requested) {
    return base->match(*requested);
}

int32_t AConfiguration_isBetterThan(AConfiguration* base, AConfiguration* test,
        AConfiguration* requested) {
    return base->isBetterThan(*test, requested);
}
