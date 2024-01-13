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
#ifdef __ANDROID__
#include "HWUIProperties.sysprop.h"
#endif
#include <android-base/properties.h>
#include <cutils/compiler.h>
#include <log/log.h>

#include <algorithm>
#include <cstdlib>
#include <optional>

#include "src/core/SkTraceEventCommon.h"

#ifdef __ANDROID__
#include <com_android_graphics_hwui_flags.h>
namespace hwui_flags = com::android::graphics::hwui::flags;
#else
namespace hwui_flags {
constexpr bool clip_surfaceviews() {
    return false;
}
}  // namespace hwui_flags
#endif

namespace android {
namespace uirenderer {

#ifndef __ANDROID__ // Layoutlib does not compile HWUIProperties.sysprop as it depends on cutils properties
std::optional<bool> use_vulkan() {
    return base::GetBoolProperty("ro.hwui.use_vulkan", true);
}

std::optional<std::int32_t> render_ahead() {
    return base::GetIntProperty("ro.hwui.render_ahead", 0);
}
#endif

bool Properties::debugLayersUpdates = false;
bool Properties::debugOverdraw = false;
bool Properties::debugTraceGpuResourceCategories = false;
bool Properties::showDirtyRegions = false;
bool Properties::skipEmptyFrames = true;
bool Properties::useBufferAge = true;
bool Properties::enablePartialUpdates = true;
// Default true unless otherwise specified in RenderThread Configuration
bool Properties::enableRenderEffectCache = true;

DebugLevel Properties::debugLevel = kDebugDisabled;
OverdrawColorSet Properties::overdrawColorSet = OverdrawColorSet::Default;

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

bool Properties::filterOutTestOverhead = false;
bool Properties::disableVsync = false;
bool Properties::skpCaptureEnabled = false;
bool Properties::enableRTAnimations = true;

bool Properties::runningInEmulator = false;
bool Properties::debuggingEnabled = false;
bool Properties::isolatedProcess = false;

int Properties::contextPriority = 0;
float Properties::defaultSdrWhitePoint = 200.f;

bool Properties::useHintManager = false;
int Properties::targetCpuTimePercentage = 70;

bool Properties::enableWebViewOverlays = true;

bool Properties::isHighEndGfx = true;
bool Properties::isLowRam = false;
bool Properties::isSystemOrPersistent = false;

float Properties::maxHdrHeadroomOn8bit = 5.f;  // TODO: Refine this number

bool Properties::clipSurfaceViews = false;

StretchEffectBehavior Properties::stretchEffectBehavior = StretchEffectBehavior::ShaderHWUI;

DrawingEnabled Properties::drawingEnabled = DrawingEnabled::NotInitialized;

bool Properties::load() {
    bool prevDebugLayersUpdates = debugLayersUpdates;
    bool prevDebugOverdraw = debugOverdraw;

    debugOverdraw = false;
    std::string debugOverdrawProperty = base::GetProperty(PROPERTY_DEBUG_OVERDRAW, "");
    if (debugOverdrawProperty != "") {
        INIT_LOGD("  Overdraw debug enabled: %s", debugOverdrawProperty.c_str());
        if (debugOverdrawProperty == "show") {
            debugOverdraw = true;
            overdrawColorSet = OverdrawColorSet::Default;
        } else if (debugOverdrawProperty == "show_deuteranomaly") {
            debugOverdraw = true;
            overdrawColorSet = OverdrawColorSet::Deuteranomaly;
        }
    }

    sProfileType = ProfileType::None;
    std::string profileProperty = base::GetProperty(PROPERTY_PROFILE, "");
    if (profileProperty != "") {
        if (profileProperty == PROPERTY_PROFILE_VISUALIZE_BARS) {
            sProfileType = ProfileType::Bars;
        } else if (profileProperty == "true") {
            sProfileType = ProfileType::Console;
        }
    }

    debugLayersUpdates = base::GetBoolProperty(PROPERTY_DEBUG_LAYERS_UPDATES, false);
    INIT_LOGD("  Layers updates debug enabled: %d", debugLayersUpdates);

    showDirtyRegions = base::GetBoolProperty(PROPERTY_DEBUG_SHOW_DIRTY_REGIONS, false);

    debugLevel = (DebugLevel)base::GetIntProperty(PROPERTY_DEBUG, (int)kDebugDisabled);

    skipEmptyFrames = base::GetBoolProperty(PROPERTY_SKIP_EMPTY_DAMAGE, true);
    useBufferAge = base::GetBoolProperty(PROPERTY_USE_BUFFER_AGE, true);
    enablePartialUpdates = base::GetBoolProperty(PROPERTY_ENABLE_PARTIAL_UPDATES, true);

    filterOutTestOverhead = base::GetBoolProperty(PROPERTY_FILTER_TEST_OVERHEAD, false);

    skpCaptureEnabled = debuggingEnabled && base::GetBoolProperty(PROPERTY_CAPTURE_SKP_ENABLED, false);

    bool skiaBroadTracing = base::GetBoolProperty(PROPERTY_SKIA_TRACING_ENABLED, false);
    SkAndroidFrameworkTraceUtil::setEnableTracing(skiaBroadTracing);
    SkAndroidFrameworkTraceUtil::setUsePerfettoTrackEvents(
            base::GetBoolProperty(PROPERTY_SKIA_USE_PERFETTO_TRACK_EVENTS, false));
    debugTraceGpuResourceCategories =
            base::GetBoolProperty(PROPERTY_TRACE_GPU_RESOURCES, skiaBroadTracing);

    runningInEmulator = base::GetBoolProperty(PROPERTY_IS_EMULATOR, false);

    useHintManager = base::GetBoolProperty(PROPERTY_USE_HINT_MANAGER, false);
    targetCpuTimePercentage = base::GetIntProperty(PROPERTY_TARGET_CPU_TIME_PERCENTAGE, 70);
    if (targetCpuTimePercentage <= 0 || targetCpuTimePercentage > 100) targetCpuTimePercentage = 70;

    enableWebViewOverlays = base::GetBoolProperty(PROPERTY_WEBVIEW_OVERLAYS_ENABLED, true);

    auto hdrHeadroom = (float)atof(base::GetProperty(PROPERTY_8BIT_HDR_HEADROOM, "").c_str());
    if (hdrHeadroom >= 1.f) {
        maxHdrHeadroomOn8bit = std::min(hdrHeadroom, 100.f);
    }

    // call isDrawingEnabled to force loading of the property
    isDrawingEnabled();

    clipSurfaceViews =
            base::GetBoolProperty("debug.hwui.clip_surfaceviews", hwui_flags::clip_surfaceviews());

    return (prevDebugLayersUpdates != debugLayersUpdates) || (prevDebugOverdraw != debugOverdraw);
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

RenderPipelineType Properties::peekRenderPipelineType() {
    // If sRenderPipelineType has been locked, just return the locked type immediately.
    if (sRenderPipelineType != RenderPipelineType::NotInitialized) {
        return sRenderPipelineType;
    }
    bool useVulkan = use_vulkan().value_or(false);
    std::string rendererProperty = base::GetProperty(PROPERTY_RENDERER, useVulkan ? "skiavk" : "skiagl");
    if (rendererProperty == "skiavk") {
        return RenderPipelineType::SkiaVulkan;
    }
    return RenderPipelineType::SkiaGL;
}

RenderPipelineType Properties::getRenderPipelineType() {
    sRenderPipelineType = peekRenderPipelineType();
    return sRenderPipelineType;
}

void Properties::overrideRenderPipelineType(RenderPipelineType type) {
    // If we're doing actual rendering then we can't change the renderer after it's been set.
    // Unit tests can freely change this as often as it wants, though, as there's no actual
    // GL rendering happening
    if (sRenderPipelineType != RenderPipelineType::NotInitialized) {
        LOG_ALWAYS_FATAL_IF(sRenderPipelineType != type,
                            "Trying to change pipeline but it's already set");
        return;
    }
    sRenderPipelineType = type;
}

void Properties::setDrawingEnabled(bool newDrawingEnabled) {
    drawingEnabled = newDrawingEnabled ? DrawingEnabled::On : DrawingEnabled::Off;
    enableRTAnimations = newDrawingEnabled;
}

bool Properties::isDrawingEnabled() {
    if (drawingEnabled == DrawingEnabled::NotInitialized) {
        bool drawingEnabledProp = base::GetBoolProperty(PROPERTY_DRAWING_ENABLED, true);
        drawingEnabled = drawingEnabledProp ? DrawingEnabled::On : DrawingEnabled::Off;
        enableRTAnimations = drawingEnabledProp;
    }
    return drawingEnabled == DrawingEnabled::On;
}

}  // namespace uirenderer
}  // namespace android
