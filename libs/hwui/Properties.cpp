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

#include "Properties.h"
#include "Debug.h"
#include "DeviceInfo.h"

#include <algorithm>
#include <cstdlib>

#include <cutils/compiler.h>
#include <cutils/properties.h>
#include <log/log.h>

namespace android {
namespace uirenderer {

bool Properties::drawDeferDisabled = false;
bool Properties::drawReorderDisabled = false;
bool Properties::debugLayersUpdates = false;
bool Properties::debugOverdraw = false;
bool Properties::showDirtyRegions = false;
bool Properties::skipEmptyFrames = true;
bool Properties::useBufferAge = true;
bool Properties::enablePartialUpdates = true;

DebugLevel Properties::debugLevel = kDebugDisabled;
OverdrawColorSet Properties::overdrawColorSet = OverdrawColorSet::Default;
StencilClipDebug Properties::debugStencilClip = StencilClipDebug::Hide;

float Properties::overrideLightRadius = -1.0f;
float Properties::overrideLightPosY = -1.0f;
float Properties::overrideLightPosZ = -1.0f;
float Properties::overrideAmbientRatio = -1.0f;
int Properties::overrideAmbientShadowStrength = -1;
int Properties::overrideSpotShadowStrength = -1;

ProfileType Properties::sProfileType = ProfileType::None;
bool Properties::sDisableProfileBars = false;
RenderPipelineType Properties::sRenderPipelineType = RenderPipelineType::NotInitialized;
bool Properties::enableHighContrastText = false;

bool Properties::waitForGpuCompletion = false;
bool Properties::forceDrawFrame = false;

bool Properties::filterOutTestOverhead = false;
bool Properties::disableVsync = false;
bool Properties::skpCaptureEnabled = false;
bool Properties::enableRTAnimations = true;

bool Properties::runningInEmulator = false;
bool Properties::debuggingEnabled = false;
bool Properties::isolatedProcess = false;

int Properties::contextPriority = 0;

static int property_get_int(const char* key, int defaultValue) {
    char buf[PROPERTY_VALUE_MAX] = {
            '\0',
    };

    if (property_get(key, buf, "") > 0) {
        return atoi(buf);
    }
    return defaultValue;
}

bool Properties::load() {
    char property[PROPERTY_VALUE_MAX];
    bool prevDebugLayersUpdates = debugLayersUpdates;
    bool prevDebugOverdraw = debugOverdraw;
    StencilClipDebug prevDebugStencilClip = debugStencilClip;

    debugOverdraw = false;
    if (property_get(PROPERTY_DEBUG_OVERDRAW, property, nullptr) > 0) {
        INIT_LOGD("  Overdraw debug enabled: %s", property);
        if (!strcmp(property, "show")) {
            debugOverdraw = true;
            overdrawColorSet = OverdrawColorSet::Default;
        } else if (!strcmp(property, "show_deuteranomaly")) {
            debugOverdraw = true;
            overdrawColorSet = OverdrawColorSet::Deuteranomaly;
        }
    }

    // See Properties.h for valid values
    if (property_get(PROPERTY_DEBUG_STENCIL_CLIP, property, nullptr) > 0) {
        INIT_LOGD("  Stencil clip debug enabled: %s", property);
        if (!strcmp(property, "hide")) {
            debugStencilClip = StencilClipDebug::Hide;
        } else if (!strcmp(property, "highlight")) {
            debugStencilClip = StencilClipDebug::ShowHighlight;
        } else if (!strcmp(property, "region")) {
            debugStencilClip = StencilClipDebug::ShowRegion;
        }
    } else {
        debugStencilClip = StencilClipDebug::Hide;
    }

    sProfileType = ProfileType::None;
    if (property_get(PROPERTY_PROFILE, property, "") > 0) {
        if (!strcmp(property, PROPERTY_PROFILE_VISUALIZE_BARS)) {
            sProfileType = ProfileType::Bars;
        } else if (!strcmp(property, "true")) {
            sProfileType = ProfileType::Console;
        }
    }

    debugLayersUpdates = property_get_bool(PROPERTY_DEBUG_LAYERS_UPDATES, false);
    INIT_LOGD("  Layers updates debug enabled: %d", debugLayersUpdates);

    drawDeferDisabled = property_get_bool(PROPERTY_DISABLE_DRAW_DEFER, false);
    INIT_LOGD("  Draw defer %s", drawDeferDisabled ? "disabled" : "enabled");

    drawReorderDisabled = property_get_bool(PROPERTY_DISABLE_DRAW_REORDER, false);
    INIT_LOGD("  Draw reorder %s", drawReorderDisabled ? "disabled" : "enabled");

    showDirtyRegions = property_get_bool(PROPERTY_DEBUG_SHOW_DIRTY_REGIONS, false);

    debugLevel = (DebugLevel)property_get_int(PROPERTY_DEBUG, kDebugDisabled);

    skipEmptyFrames = property_get_bool(PROPERTY_SKIP_EMPTY_DAMAGE, true);
    useBufferAge = property_get_bool(PROPERTY_USE_BUFFER_AGE, true);
    enablePartialUpdates = property_get_bool(PROPERTY_ENABLE_PARTIAL_UPDATES, true);

    filterOutTestOverhead = property_get_bool(PROPERTY_FILTER_TEST_OVERHEAD, false);

    skpCaptureEnabled = debuggingEnabled && property_get_bool(PROPERTY_CAPTURE_SKP_ENABLED, false);

    runningInEmulator = property_get_bool(PROPERTY_QEMU_KERNEL, false);

    return (prevDebugLayersUpdates != debugLayersUpdates) || (prevDebugOverdraw != debugOverdraw) ||
           (prevDebugStencilClip != debugStencilClip);
}

void Properties::overrideProperty(const char* name, const char* value) {
    if (!strcmp(name, "disableProfileBars")) {
        sDisableProfileBars = !strcmp(value, "true");
        ALOGD("profile bars %s", sDisableProfileBars ? "disabled" : "enabled");
        return;
    } else if (!strcmp(name, "ambientRatio")) {
        overrideAmbientRatio = std::min(std::max(atof(value), 0.0), 10.0);
        ALOGD("ambientRatio = %.2f", overrideAmbientRatio);
        return;
    } else if (!strcmp(name, "lightRadius")) {
        overrideLightRadius = std::min(std::max(atof(value), 0.0), 3000.0);
        ALOGD("lightRadius = %.2f", overrideLightRadius);
        return;
    } else if (!strcmp(name, "lightPosY")) {
        overrideLightPosY = std::min(std::max(atof(value), 0.0), 3000.0);
        ALOGD("lightPos Y = %.2f", overrideLightPosY);
        return;
    } else if (!strcmp(name, "lightPosZ")) {
        overrideLightPosZ = std::min(std::max(atof(value), 0.0), 3000.0);
        ALOGD("lightPos Z = %.2f", overrideLightPosZ);
        return;
    } else if (!strcmp(name, "ambientShadowStrength")) {
        overrideAmbientShadowStrength = atoi(value);
        ALOGD("ambient shadow strength = 0x%x out of 0xff", overrideAmbientShadowStrength);
        return;
    } else if (!strcmp(name, "spotShadowStrength")) {
        overrideSpotShadowStrength = atoi(value);
        ALOGD("spot shadow strength = 0x%x out of 0xff", overrideSpotShadowStrength);
        return;
    }
    ALOGD("failed overriding property %s to %s", name, value);
}

ProfileType Properties::getProfileType() {
    if (CC_UNLIKELY(sDisableProfileBars && sProfileType == ProfileType::Bars))
        return ProfileType::None;
    return sProfileType;
}

RenderPipelineType Properties::getRenderPipelineType() {
    if (sRenderPipelineType != RenderPipelineType::NotInitialized) {
        return sRenderPipelineType;
    }
    char prop[PROPERTY_VALUE_MAX];
    property_get(PROPERTY_RENDERER, prop, "skiagl");
    if (!strcmp(prop, "skiagl")) {
        ALOGD("Skia GL Pipeline");
        sRenderPipelineType = RenderPipelineType::SkiaGL;
    } else if (!strcmp(prop, "skiavk")) {
        ALOGD("Skia Vulkan Pipeline");
        sRenderPipelineType = RenderPipelineType::SkiaVulkan;
    } else {  //"opengl"
        ALOGD("HWUI GL Pipeline");
        sRenderPipelineType = RenderPipelineType::OpenGL;
    }
    return sRenderPipelineType;
}

void Properties::overrideRenderPipelineType(RenderPipelineType type) {
#if !defined(HWUI_GLES_WRAP_ENABLED)
    // If we're doing actual rendering then we can't change the renderer after it's been set.
    // Unit tests can freely change this as often as it wants, though, as there's no actual
    // GL rendering happening
    if (sRenderPipelineType != RenderPipelineType::NotInitialized) {
        return;
    }
#endif
    sRenderPipelineType = type;
}

bool Properties::isSkiaEnabled() {
    auto renderType = getRenderPipelineType();
    return RenderPipelineType::SkiaGL == renderType || RenderPipelineType::SkiaVulkan == renderType;
}

};  // namespace uirenderer
};  // namespace android
