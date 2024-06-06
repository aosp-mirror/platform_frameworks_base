/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "BootAnimation"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <filesystem>
#include <vector>

#include <stdint.h>
#include <inttypes.h>
#include <sys/inotify.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <math.h>
#include <fcntl.h>
#include <utils/misc.h>
#include <utils/Trace.h>
#include <signal.h>
#include <time.h>

#include <cutils/atomic.h>
#include <cutils/properties.h>

#include <android/imagedecoder.h>
#include <androidfw/AssetManager.h>
#include <binder/IPCThreadState.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>

#include <android-base/properties.h>

#include <ui/DisplayMode.h>
#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/Region.h>

#include <gui/ISurfaceComposer.h>
#include <gui/DisplayEventReceiver.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/eglext.h>

#include "BootAnimation.h"

#define ANIM_PATH_MAX 255
#define STR(x)   #x
#define STRTO(x) STR(x)

namespace android {

using ui::DisplayMode;

static const char OEM_BOOTANIMATION_FILE[] = "/oem/media/bootanimation.zip";
static const char PRODUCT_BOOTANIMATION_DARK_FILE[] = "/product/media/bootanimation-dark.zip";
static const char PRODUCT_BOOTANIMATION_FILE[] = "/product/media/bootanimation.zip";
static const char SYSTEM_BOOTANIMATION_FILE[] = "/system/media/bootanimation.zip";
static const char APEX_BOOTANIMATION_FILE[] = "/apex/com.android.bootanimation/etc/bootanimation.zip";
static const char OEM_SHUTDOWNANIMATION_FILE[] = "/oem/media/shutdownanimation.zip";
static const char PRODUCT_SHUTDOWNANIMATION_FILE[] = "/product/media/shutdownanimation.zip";
static const char SYSTEM_SHUTDOWNANIMATION_FILE[] = "/system/media/shutdownanimation.zip";

static constexpr const char* PRODUCT_USERSPACE_REBOOT_ANIMATION_FILE = "/product/media/userspace-reboot.zip";
static constexpr const char* OEM_USERSPACE_REBOOT_ANIMATION_FILE = "/oem/media/userspace-reboot.zip";
static constexpr const char* SYSTEM_USERSPACE_REBOOT_ANIMATION_FILE = "/system/media/userspace-reboot.zip";

static const char BOOTANIM_DATA_DIR_PATH[] = "/data/misc/bootanim";
static const char BOOTANIM_TIME_DIR_NAME[] = "time";
static const char BOOTANIM_TIME_DIR_PATH[] = "/data/misc/bootanim/time";
static const char CLOCK_FONT_ASSET[] = "images/clock_font.png";
static const char CLOCK_FONT_ZIP_NAME[] = "clock_font.png";
static const char PROGRESS_FONT_ASSET[] = "images/progress_font.png";
static const char PROGRESS_FONT_ZIP_NAME[] = "progress_font.png";
static const char LAST_TIME_CHANGED_FILE_NAME[] = "last_time_change";
static const char LAST_TIME_CHANGED_FILE_PATH[] = "/data/misc/bootanim/time/last_time_change";
static const char ACCURATE_TIME_FLAG_FILE_NAME[] = "time_is_accurate";
static const char ACCURATE_TIME_FLAG_FILE_PATH[] = "/data/misc/bootanim/time/time_is_accurate";
static const char TIME_FORMAT_12_HOUR_FLAG_FILE_PATH[] = "/data/misc/bootanim/time/time_format_12_hour";
// Java timestamp format. Don't show the clock if the date is before 2000-01-01 00:00:00.
static const long long ACCURATE_TIME_EPOCH = 946684800000;
static constexpr char FONT_BEGIN_CHAR = ' ';
static constexpr char FONT_END_CHAR = '~' + 1;
static constexpr size_t FONT_NUM_CHARS = FONT_END_CHAR - FONT_BEGIN_CHAR + 1;
static constexpr size_t FONT_NUM_COLS = 16;
static constexpr size_t FONT_NUM_ROWS = FONT_NUM_CHARS / FONT_NUM_COLS;
static const int TEXT_CENTER_VALUE = INT_MAX;
static const int TEXT_MISSING_VALUE = INT_MIN;
static const char EXIT_PROP_NAME[] = "service.bootanim.exit";
static const char PROGRESS_PROP_NAME[] = "service.bootanim.progress";
static const char DISPLAYS_PROP_NAME[] = "persist.service.bootanim.displays";
static const char CLOCK_ENABLED_PROP_NAME[] = "persist.sys.bootanim.clock.enabled";
static const int ANIM_ENTRY_NAME_MAX = ANIM_PATH_MAX + 1;
static const int MAX_CHECK_EXIT_INTERVAL_US = 50000;
static constexpr size_t TEXT_POS_LEN_MAX = 16;
static const int DYNAMIC_COLOR_COUNT = 4;
static const char U_TEXTURE[] = "uTexture";
static const char U_FADE[] = "uFade";
static const char U_CROP_AREA[] = "uCropArea";
static const char U_START_COLOR_PREFIX[] = "uStartColor";
static const char U_END_COLOR_PREFIX[] = "uEndColor";
static const char U_COLOR_PROGRESS[] = "uColorProgress";
static const char A_UV[] = "aUv";
static const char A_POSITION[] = "aPosition";
static const char VERTEX_SHADER_SOURCE[] = R"(
    precision mediump float;
    attribute vec4 aPosition;
    attribute highp vec2 aUv;
    varying highp vec2 vUv;
    void main() {
        gl_Position = aPosition;
        vUv = aUv;
    })";
static const char IMAGE_FRAG_DYNAMIC_COLORING_SHADER_SOURCE[] = R"(
    precision mediump float;
    const float cWhiteMaskThreshold = 0.05;
    uniform sampler2D uTexture;
    uniform float uFade;
    uniform float uColorProgress;
    uniform vec3 uStartColor0;
    uniform vec3 uStartColor1;
    uniform vec3 uStartColor2;
    uniform vec3 uStartColor3;
    uniform vec3 uEndColor0;
    uniform vec3 uEndColor1;
    uniform vec3 uEndColor2;
    uniform vec3 uEndColor3;
    varying highp vec2 vUv;
    void main() {
        vec4 mask = texture2D(uTexture, vUv);
        float r = mask.r;
        float g = mask.g;
        float b = mask.b;
        float a = mask.a;
        // If all channels have values, render pixel as a shade of white.
        float useWhiteMask = step(cWhiteMaskThreshold, r)
            * step(cWhiteMaskThreshold, g)
            * step(cWhiteMaskThreshold, b)
            * step(cWhiteMaskThreshold, a);
        vec3 color = r * mix(uStartColor0, uEndColor0, uColorProgress)
                + g * mix(uStartColor1, uEndColor1, uColorProgress)
                + b * mix(uStartColor2, uEndColor2, uColorProgress)
                + a * mix(uStartColor3, uEndColor3, uColorProgress);
        color = mix(color, vec3((r + g + b + a) * 0.25), useWhiteMask);
        gl_FragColor = vec4(color.x, color.y, color.z, (1.0 - uFade));
    })";
static const char IMAGE_FRAG_SHADER_SOURCE[] = R"(
    precision mediump float;
    uniform sampler2D uTexture;
    uniform float uFade;
    varying highp vec2 vUv;
    void main() {
        vec4 color = texture2D(uTexture, vUv);
        gl_FragColor = vec4(color.x, color.y, color.z, (1.0 - uFade)) * color.a;
    })";
static const char TEXT_FRAG_SHADER_SOURCE[] = R"(
    precision mediump float;
    uniform sampler2D uTexture;
    uniform vec4 uCropArea;
    varying highp vec2 vUv;
    void main() {
        vec2 uv = vec2(mix(uCropArea.x, uCropArea.z, vUv.x),
                       mix(uCropArea.y, uCropArea.w, vUv.y));
        gl_FragColor = texture2D(uTexture, uv);
    })";

static GLfloat quadPositions[] = {
    -0.5f, -0.5f,
    +0.5f, -0.5f,
    +0.5f, +0.5f,
    +0.5f, +0.5f,
    -0.5f, +0.5f,
    -0.5f, -0.5f
};
static GLfloat quadUVs[] = {
    0.0f, 1.0f,
    1.0f, 1.0f,
    1.0f, 0.0f,
    1.0f, 0.0f,
    0.0f, 0.0f,
    0.0f, 1.0f
};

// ---------------------------------------------------------------------------

BootAnimation::BootAnimation(sp<Callbacks> callbacks)
        : Thread(false), mLooper(new Looper(false)), mClockEnabled(true), mTimeIsAccurate(false),
        mTimeFormat12Hour(false), mTimeCheckThread(nullptr), mCallbacks(callbacks) {
    ATRACE_CALL();
    mSession = new SurfaceComposerClient();

    std::string powerCtl = android::base::GetProperty("sys.powerctl", "");
    if (powerCtl.empty()) {
        mShuttingDown = false;
    } else {
        mShuttingDown = true;
    }
    ALOGD("%sAnimationStartTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
}

BootAnimation::~BootAnimation() {
    ATRACE_CALL();
    if (mAnimation != nullptr) {
        releaseAnimation(mAnimation);
        mAnimation = nullptr;
    }
    ALOGD("%sAnimationStopTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
}

void BootAnimation::onFirstRef() {
    ATRACE_CALL();
    status_t err = mSession->linkToComposerDeath(this);
    SLOGE_IF(err, "linkToComposerDeath failed (%s) ", strerror(-err));
    if (err == NO_ERROR) {
        // Load the animation content -- this can be slow (eg 200ms)
        // called before waitForSurfaceFlinger() in main() to avoid wait
        ALOGD("%sAnimationPreloadTiming start time: %" PRId64 "ms",
                mShuttingDown ? "Shutdown" : "Boot", elapsedRealtime());
        preloadAnimation();
        ALOGD("%sAnimationPreloadStopTiming start time: %" PRId64 "ms",
                mShuttingDown ? "Shutdown" : "Boot", elapsedRealtime());
    }
}

sp<SurfaceComposerClient> BootAnimation::session() const {
    return mSession;
}

void BootAnimation::binderDied(const wp<IBinder>&) {
    ATRACE_CALL();
    // woah, surfaceflinger died!
    SLOGD("SurfaceFlinger died, exiting...");

    // calling requestExit() is not enough here because the Surface code
    // might be blocked on a condition variable that will never be updated.
    kill( getpid(), SIGKILL );
    requestExit();
}

static void* decodeImage(const void* encodedData, size_t dataLength, AndroidBitmapInfo* outInfo,
    bool premultiplyAlpha) {
    ATRACE_CALL();
    AImageDecoder* decoder = nullptr;
    AImageDecoder_createFromBuffer(encodedData, dataLength, &decoder);
    if (!decoder) {
        return nullptr;
    }

    const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder);
    outInfo->width = AImageDecoderHeaderInfo_getWidth(info);
    outInfo->height = AImageDecoderHeaderInfo_getHeight(info);
    outInfo->format = AImageDecoderHeaderInfo_getAndroidBitmapFormat(info);
    outInfo->stride = AImageDecoder_getMinimumStride(decoder);
    outInfo->flags = 0;

    if (!premultiplyAlpha) {
        AImageDecoder_setUnpremultipliedRequired(decoder, true);
    }

    const size_t size = outInfo->stride * outInfo->height;
    void* pixels = malloc(size);
    int result = AImageDecoder_decodeImage(decoder, pixels, outInfo->stride, size);
    AImageDecoder_delete(decoder);

    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        free(pixels);
        return nullptr;
    }
    return pixels;
}

status_t BootAnimation::initTexture(Texture* texture, AssetManager& assets,
        const char* name, bool premultiplyAlpha) {
    ATRACE_CALL();
    Asset* asset = assets.open(name, Asset::ACCESS_BUFFER);
    if (asset == nullptr)
        return NO_INIT;

    AndroidBitmapInfo bitmapInfo;
    void* pixels = decodeImage(asset->getBuffer(false), asset->getLength(), &bitmapInfo,
        premultiplyAlpha);
    auto pixelDeleter = std::unique_ptr<void, decltype(free)*>{ pixels, free };

    asset->close();
    delete asset;

    if (!pixels) {
        return NO_INIT;
    }

    const int w = bitmapInfo.width;
    const int h = bitmapInfo.height;

    texture->w = w;
    texture->h = h;

    glGenTextures(1, &texture->name);
    glBindTexture(GL_TEXTURE_2D, texture->name);

    switch (bitmapInfo.format) {
        case ANDROID_BITMAP_FORMAT_A_8:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, w, h, 0, GL_ALPHA,
                    GL_UNSIGNED_BYTE, pixels);
            break;
        case ANDROID_BITMAP_FORMAT_RGBA_4444:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_SHORT_4_4_4_4, pixels);
            break;
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_BYTE, pixels);
            break;
        case ANDROID_BITMAP_FORMAT_RGB_565:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB,
                    GL_UNSIGNED_SHORT_5_6_5, pixels);
            break;
        default:
            break;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    return NO_ERROR;
}

status_t BootAnimation::initTexture(FileMap* map, int* width, int* height,
    bool premultiplyAlpha) {
    ATRACE_CALL();
    AndroidBitmapInfo bitmapInfo;
    void* pixels = decodeImage(map->getDataPtr(), map->getDataLength(), &bitmapInfo,
        premultiplyAlpha);
    auto pixelDeleter = std::unique_ptr<void, decltype(free)*>{ pixels, free };

    // FileMap memory is never released until application exit.
    // Release it now as the texture is already loaded and the memory used for
    // the packed resource can be released.
    delete map;

    if (!pixels) {
        return NO_INIT;
    }

    const int w = bitmapInfo.width;
    const int h = bitmapInfo.height;

    int tw = 1 << (31 - __builtin_clz(w));
    int th = 1 << (31 - __builtin_clz(h));
    if (tw < w) tw <<= 1;
    if (th < h) th <<= 1;

    switch (bitmapInfo.format) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888:
            if (!mUseNpotTextures && (tw != w || th != h)) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, nullptr);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, pixels);
            }
            break;

        case ANDROID_BITMAP_FORMAT_RGB_565:
            if (!mUseNpotTextures && (tw != w || th != h)) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, tw, th, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, nullptr);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, pixels);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, pixels);
            }
            break;
        default:
            break;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    *width = w;
    *height = h;

    return NO_ERROR;
}

class BootAnimation::DisplayEventCallback : public LooperCallback {
    BootAnimation* mBootAnimation;

public:
    DisplayEventCallback(BootAnimation* bootAnimation) {
        ATRACE_CALL();
        mBootAnimation = bootAnimation;
    }

    int handleEvent(int /* fd */, int events, void* /* data */) {
        ATRACE_CALL();
        if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
            ALOGE("Display event receiver pipe was closed or an error occurred. events=0x%x",
                    events);
            return 0; // remove the callback
        }

        if (!(events & Looper::EVENT_INPUT)) {
            ALOGW("Received spurious callback for unhandled poll event.  events=0x%x", events);
            return 1; // keep the callback
        }

        constexpr int kBufferSize = 100;
        DisplayEventReceiver::Event buffer[kBufferSize];
        ssize_t numEvents;
        do {
            numEvents = mBootAnimation->mDisplayEventReceiver->getEvents(buffer, kBufferSize);
            for (size_t i = 0; i < static_cast<size_t>(numEvents); i++) {
                const auto& event = buffer[i];
                if (event.header.type == DisplayEventReceiver::DISPLAY_EVENT_HOTPLUG) {
                    SLOGV("Hotplug received");

                    if (!event.hotplug.connected) {
                        // ignore hotplug disconnect
                        continue;
                    }
                    auto token = SurfaceComposerClient::getPhysicalDisplayToken(
                        event.header.displayId);

                    if (token != mBootAnimation->mDisplayToken) {
                        // ignore hotplug of a secondary display
                        continue;
                    }

                    DisplayMode displayMode;
                    const status_t error = SurfaceComposerClient::getActiveDisplayMode(
                        mBootAnimation->mDisplayToken, &displayMode);
                    if (error != NO_ERROR) {
                        SLOGE("Can't get active display mode.");
                    }
                    mBootAnimation->resizeSurface(displayMode.resolution.getWidth(),
                        displayMode.resolution.getHeight());
                }
            }
        } while (numEvents > 0);

        return 1;  // keep the callback
    }
};

EGLConfig BootAnimation::getEglConfig(const EGLDisplay& display) {
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_DEPTH_SIZE, 0,
        EGL_NONE
    };
    EGLint numConfigs;
    EGLConfig config;
    eglChooseConfig(display, attribs, &config, 1, &numConfigs);
    return config;
}

ui::Size BootAnimation::limitSurfaceSize(int width, int height) const {
    ui::Size limited(width, height);
    bool wasLimited = false;
    const float aspectRatio = float(width) / float(height);
    if (mMaxWidth != 0 && width > mMaxWidth) {
        limited.height = mMaxWidth / aspectRatio;
        limited.width = mMaxWidth;
        wasLimited = true;
    }
    if (mMaxHeight != 0 && limited.height > mMaxHeight) {
        limited.height = mMaxHeight;
        limited.width = mMaxHeight * aspectRatio;
        wasLimited = true;
    }
    SLOGV_IF(wasLimited, "Surface size has been limited to [%dx%d] from [%dx%d]",
             limited.width, limited.height, width, height);
    return limited;
}

status_t BootAnimation::readyToRun() {
    ATRACE_CALL();
    mAssets.addDefaultAssets();

    const std::vector<PhysicalDisplayId> ids = SurfaceComposerClient::getPhysicalDisplayIds();
    if (ids.empty()) {
        SLOGE("Failed to get ID for any displays\n");
        return NAME_NOT_FOUND;
    }

    // this system property specifies multi-display IDs to show the boot animation
    // multiple ids can be set with comma (,) as separator, for example:
    // setprop persist.boot.animation.displays 19260422155234049,19261083906282754
    Vector<PhysicalDisplayId> physicalDisplayIds;
    char displayValue[PROPERTY_VALUE_MAX] = "";
    property_get(DISPLAYS_PROP_NAME, displayValue, "");
    bool isValid = displayValue[0] != '\0';
    if (isValid) {
        char *p = displayValue;
        while (*p) {
            if (!isdigit(*p) && *p != ',') {
                isValid = false;
                break;
            }
            p ++;
        }
        if (!isValid)
            SLOGE("Invalid syntax for the value of system prop: %s", DISPLAYS_PROP_NAME);
    }
    if (isValid) {
        std::istringstream stream(displayValue);
        for (PhysicalDisplayId id; stream >> id.value; ) {
            physicalDisplayIds.add(id);
            if (stream.peek() == ',')
                stream.ignore();
        }

        // the first specified display id is used to retrieve mDisplayToken
        for (const auto id : physicalDisplayIds) {
            if (std::find(ids.begin(), ids.end(), id) != ids.end()) {
                if (const auto token = SurfaceComposerClient::getPhysicalDisplayToken(id)) {
                    mDisplayToken = token;
                    break;
                }
            }
        }
    }

    // If the system property is not present or invalid, display 0 is used
    if (mDisplayToken == nullptr) {
        mDisplayToken = SurfaceComposerClient::getPhysicalDisplayToken(ids.front());
        if (mDisplayToken == nullptr) {
            return NAME_NOT_FOUND;
        }
    }

    DisplayMode displayMode;
    const status_t error =
            SurfaceComposerClient::getActiveDisplayMode(mDisplayToken, &displayMode);
    if (error != NO_ERROR) {
        return error;
    }

    mMaxWidth = android::base::GetIntProperty("ro.surface_flinger.max_graphics_width", 0);
    mMaxHeight = android::base::GetIntProperty("ro.surface_flinger.max_graphics_height", 0);
    ui::Size resolution = displayMode.resolution;
    resolution = limitSurfaceSize(resolution.width, resolution.height);
    // create the native surface
    sp<SurfaceControl> control = session()->createSurface(String8("BootAnimation"),
            resolution.getWidth(), resolution.getHeight(), PIXEL_FORMAT_RGB_565,
            ISurfaceComposerClient::eOpaque);

    SurfaceComposerClient::Transaction t;
    if (isValid) {
        // In the case of multi-display, boot animation shows on the specified displays
        for (const auto id : physicalDisplayIds) {
            if (std::find(ids.begin(), ids.end(), id) != ids.end()) {
                if (const auto token = SurfaceComposerClient::getPhysicalDisplayToken(id)) {
                    t.setDisplayLayerStack(token, ui::DEFAULT_LAYER_STACK);
                }
            }
        }
        t.setLayerStack(control, ui::DEFAULT_LAYER_STACK);
    }

    t.setLayer(control, 0x40000000)
        .apply();

    sp<Surface> s = control->getSurface();

    // initialize opengl and egl
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    EGLConfig config = getEglConfig(display);
    EGLSurface surface = eglCreateWindowSurface(display, config, s.get(), nullptr);
    // Initialize egl context with client version number 2.0.
    EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, nullptr, contextAttributes);
    EGLint w, h;
    eglQuerySurface(display, surface, EGL_WIDTH, &w);
    eglQuerySurface(display, surface, EGL_HEIGHT, &h);

    if (eglMakeCurrent(display, surface, surface, context) == EGL_FALSE) {
        return NO_INIT;
    }

    mDisplay = display;
    mContext = context;
    mSurface = surface;
    mInitWidth = mWidth = w;
    mInitHeight = mHeight = h;
    mFlingerSurfaceControl = control;
    mFlingerSurface = s;
    mTargetInset = -1;

    // Rotate the boot animation according to the value specified in the sysprop
    // ro.bootanim.set_orientation_<display_id>. Four values are supported: ORIENTATION_0,
    // ORIENTATION_90, ORIENTATION_180 and ORIENTATION_270.
    // If the value isn't specified or is ORIENTATION_0, nothing will be changed.
    // This is needed to support having boot animation in orientations different from the natural
    // device orientation. For example, on tablets that may want to keep natural orientation
    // portrait for applications compatibility and to have the boot animation in landscape.
    rotateAwayFromNaturalOrientationIfNeeded();

    projectSceneToWindow();

    // Register a display event receiver
    mDisplayEventReceiver = std::make_unique<DisplayEventReceiver>();
    status_t status = mDisplayEventReceiver->initCheck();
    SLOGE_IF(status != NO_ERROR, "Initialization of DisplayEventReceiver failed with status: %d",
            status);
    mLooper->addFd(mDisplayEventReceiver->getFd(), 0, Looper::EVENT_INPUT,
            new DisplayEventCallback(this), nullptr);

    return NO_ERROR;
}

void BootAnimation::rotateAwayFromNaturalOrientationIfNeeded() {
    ATRACE_CALL();
    const auto orientation = parseOrientationProperty();

    if (orientation == ui::ROTATION_0) {
        // Do nothing if the sysprop isn't set or is set to ROTATION_0.
        return;
    }

    if (orientation == ui::ROTATION_90 || orientation == ui::ROTATION_270) {
        std::swap(mWidth, mHeight);
        std::swap(mInitWidth, mInitHeight);
        mFlingerSurfaceControl->updateDefaultBufferSize(mWidth, mHeight);
    }

    Rect displayRect(0, 0, mWidth, mHeight);
    Rect layerStackRect(0, 0, mWidth, mHeight);

    SurfaceComposerClient::Transaction t;
    t.setDisplayProjection(mDisplayToken, orientation, layerStackRect, displayRect);
    t.apply();
}

ui::Rotation BootAnimation::parseOrientationProperty() {
    ATRACE_CALL();
    const auto displayIds = SurfaceComposerClient::getPhysicalDisplayIds();
    if (displayIds.size() == 0) {
        return ui::ROTATION_0;
    }
    const auto displayId = displayIds[0];
    const auto syspropName = [displayId] {
        std::stringstream ss;
        ss << "ro.bootanim.set_orientation_" << displayId.value;
        return ss.str();
    }();
    auto syspropValue = android::base::GetProperty(syspropName, "");
    if (syspropValue == "") {
        syspropValue = android::base::GetProperty("ro.bootanim.set_orientation_logical_0", "");
    }

    if (syspropValue == "ORIENTATION_90") {
        return ui::ROTATION_90;
    } else if (syspropValue == "ORIENTATION_180") {
        return ui::ROTATION_180;
    } else if (syspropValue == "ORIENTATION_270") {
        return ui::ROTATION_270;
    }
    return ui::ROTATION_0;
}

void BootAnimation::projectSceneToWindow() {
    ATRACE_CALL();
    glViewport(0, 0, mWidth, mHeight);
    glScissor(0, 0, mWidth, mHeight);
}

void BootAnimation::resizeSurface(int newWidth, int newHeight) {
    ATRACE_CALL();
    // We assume this function is called on the animation thread.
    if (newWidth == mWidth && newHeight == mHeight) {
        return;
    }
    SLOGV("Resizing the boot animation surface to %d %d", newWidth, newHeight);

    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(mDisplay, mSurface);

    const auto limitedSize = limitSurfaceSize(newWidth, newHeight);
    mWidth = limitedSize.width;
    mHeight = limitedSize.height;

    mFlingerSurfaceControl->updateDefaultBufferSize(mWidth, mHeight);
    EGLConfig config = getEglConfig(mDisplay);
    EGLSurface surface = eglCreateWindowSurface(mDisplay, config, mFlingerSurface.get(), nullptr);
    if (eglMakeCurrent(mDisplay, surface, surface, mContext) == EGL_FALSE) {
        SLOGE("Can't make the new surface current. Error %d", eglGetError());
        return;
    }

    projectSceneToWindow();

    mSurface = surface;
}

bool BootAnimation::preloadAnimation() {
    ATRACE_CALL();
    findBootAnimationFile();
    if (!mZipFileName.empty()) {
        mAnimation = loadAnimation(mZipFileName);
        return (mAnimation != nullptr);
    }

    return false;
}

bool BootAnimation::findBootAnimationFileInternal(const std::vector<std::string> &files) {
    ATRACE_CALL();
    for (const std::string& f : files) {
        if (access(f.c_str(), R_OK) == 0) {
            mZipFileName = f.c_str();
            return true;
        }
    }
    return false;
}

void BootAnimation::findBootAnimationFile() {
    ATRACE_CALL();
    const bool playDarkAnim = android::base::GetIntProperty("ro.boot.theme", 0) == 1;
    static const std::vector<std::string> bootFiles = {
        APEX_BOOTANIMATION_FILE, playDarkAnim ? PRODUCT_BOOTANIMATION_DARK_FILE : PRODUCT_BOOTANIMATION_FILE,
        OEM_BOOTANIMATION_FILE, SYSTEM_BOOTANIMATION_FILE
    };
    static const std::vector<std::string> shutdownFiles = {
        PRODUCT_SHUTDOWNANIMATION_FILE, OEM_SHUTDOWNANIMATION_FILE, SYSTEM_SHUTDOWNANIMATION_FILE, ""
    };
    static const std::vector<std::string> userspaceRebootFiles = {
        PRODUCT_USERSPACE_REBOOT_ANIMATION_FILE, OEM_USERSPACE_REBOOT_ANIMATION_FILE,
        SYSTEM_USERSPACE_REBOOT_ANIMATION_FILE,
    };

    if (android::base::GetBoolProperty("sys.init.userspace_reboot.in_progress", false)) {
        findBootAnimationFileInternal(userspaceRebootFiles);
    } else if (mShuttingDown) {
        findBootAnimationFileInternal(shutdownFiles);
    } else {
        findBootAnimationFileInternal(bootFiles);
    }
}

GLuint compileShader(GLenum shaderType, const GLchar *source) {
    ATRACE_CALL();
    GLuint shader = glCreateShader(shaderType);
    glShaderSource(shader, 1, &source, 0);
    glCompileShader(shader);
    GLint isCompiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &isCompiled);
    if (isCompiled == GL_FALSE) {
        SLOGE("Compile shader failed. Shader type: %d", shaderType);
        GLint maxLength = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &maxLength);
        std::vector<GLchar> errorLog(maxLength);
        glGetShaderInfoLog(shader, maxLength, &maxLength, &errorLog[0]);
        SLOGE("Shader compilation error: %s", &errorLog[0]);
        return 0;
    }
    return shader;
}

GLuint linkShader(GLuint vertexShader, GLuint fragmentShader) {
    ATRACE_CALL();
    GLuint program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    GLint isLinked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, (int *)&isLinked);
    if (isLinked == GL_FALSE) {
        SLOGE("Linking shader failed. Shader handles: vert %d, frag %d",
            vertexShader, fragmentShader);
        return 0;
    }
    return program;
}

void BootAnimation::initShaders() {
    ATRACE_CALL();
    bool dynamicColoringEnabled = mAnimation != nullptr && mAnimation->dynamicColoringEnabled;
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, (const GLchar *)VERTEX_SHADER_SOURCE);
    GLuint imageFragmentShader =
        compileShader(GL_FRAGMENT_SHADER, dynamicColoringEnabled
            ? (const GLchar *)IMAGE_FRAG_DYNAMIC_COLORING_SHADER_SOURCE
            : (const GLchar *)IMAGE_FRAG_SHADER_SOURCE);
    GLuint textFragmentShader =
        compileShader(GL_FRAGMENT_SHADER, (const GLchar *)TEXT_FRAG_SHADER_SOURCE);

    // Initialize image shader.
    mImageShader = linkShader(vertexShader, imageFragmentShader);
    GLint positionLocation = glGetAttribLocation(mImageShader, A_POSITION);
    GLint uvLocation = glGetAttribLocation(mImageShader, A_UV);
    mImageTextureLocation = glGetUniformLocation(mImageShader, U_TEXTURE);
    mImageFadeLocation = glGetUniformLocation(mImageShader, U_FADE);
    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 2,  GL_FLOAT, GL_FALSE, 0, quadPositions);
    glVertexAttribPointer(uvLocation, 2, GL_FLOAT, GL_FALSE, 0, quadUVs);
    glEnableVertexAttribArray(uvLocation);

    // Initialize text shader.
    mTextShader = linkShader(vertexShader, textFragmentShader);
    positionLocation = glGetAttribLocation(mTextShader, A_POSITION);
    uvLocation = glGetAttribLocation(mTextShader, A_UV);
    mTextTextureLocation = glGetUniformLocation(mTextShader, U_TEXTURE);
    mTextCropAreaLocation = glGetUniformLocation(mTextShader, U_CROP_AREA);
    glEnableVertexAttribArray(positionLocation);
    glVertexAttribPointer(positionLocation, 2,  GL_FLOAT, GL_FALSE, 0, quadPositions);
    glVertexAttribPointer(uvLocation, 2, GL_FLOAT, GL_FALSE, 0, quadUVs);
    glEnableVertexAttribArray(uvLocation);
}

bool BootAnimation::threadLoop() {
    ATRACE_CALL();
    bool result;
    initShaders();

    // We have no bootanimation file, so we use the stock android logo
    // animation.
    if (mZipFileName.empty()) {
        ALOGD("No animation file");
        result = android();
    } else {
        result = movie();
    }

    mCallbacks->shutdown();
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(mDisplay, mContext);
    eglDestroySurface(mDisplay, mSurface);
    mFlingerSurface.clear();
    mFlingerSurfaceControl.clear();
    eglTerminate(mDisplay);
    eglReleaseThread();
    IPCThreadState::self()->stopProcess();
    return result;
}

bool BootAnimation::android() {
    ATRACE_CALL();
    glActiveTexture(GL_TEXTURE0);

    SLOGD("%sAnimationShownTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());
    initTexture(&mAndroid[0], mAssets, "images/android-logo-mask.png");
    initTexture(&mAndroid[1], mAssets, "images/android-logo-shine.png");

    mCallbacks->init({});

    // clear screen
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(mImageShader);

    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(mDisplay, mSurface);

    // Blend state
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    const nsecs_t startTime = systemTime();
    do {
        processDisplayEvents();
        const GLint xc = (mWidth  - mAndroid[0].w) / 2;
        const GLint yc = (mHeight - mAndroid[0].h) / 2;
        const Rect updateRect(xc, yc, xc + mAndroid[0].w, yc + mAndroid[0].h);
        glScissor(updateRect.left, mHeight - updateRect.bottom, updateRect.width(),
                updateRect.height());

        nsecs_t now = systemTime();
        double time = now - startTime;
        float t = 4.0f * float(time / us2ns(16667)) / mAndroid[1].w;
        GLint offset = (1 - (t - floorf(t))) * mAndroid[1].w;
        GLint x = xc - offset;

        glDisable(GL_SCISSOR_TEST);
        glClear(GL_COLOR_BUFFER_BIT);

        glEnable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[1].name);
        drawTexturedQuad(x,                 yc, mAndroid[1].w, mAndroid[1].h);
        drawTexturedQuad(x + mAndroid[1].w, yc, mAndroid[1].w, mAndroid[1].h);

        glEnable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[0].name);
        drawTexturedQuad(xc, yc, mAndroid[0].w, mAndroid[0].h);

        EGLBoolean res = eglSwapBuffers(mDisplay, mSurface);
        if (res == EGL_FALSE)
            break;

        // 12fps: don't animate too fast to preserve CPU
        const nsecs_t sleepTime = 83333 - ns2us(systemTime() - now);
        if (sleepTime > 0)
            usleep(sleepTime);

        checkExit();
    } while (!exitPending());

    glDeleteTextures(1, &mAndroid[0].name);
    glDeleteTextures(1, &mAndroid[1].name);
    return false;
}

void BootAnimation::checkExit() {
    ATRACE_CALL();
    // Allow surface flinger to gracefully request shutdown
    char value[PROPERTY_VALUE_MAX];
    property_get(EXIT_PROP_NAME, value, "0");
    int exitnow = atoi(value);
    if (exitnow) {
        requestExit();
    }
}

bool BootAnimation::validClock(const Animation::Part& part) {
    ATRACE_CALL();
    return part.clockPosX != TEXT_MISSING_VALUE && part.clockPosY != TEXT_MISSING_VALUE;
}

bool parseTextCoord(const char* str, int* dest) {
    ATRACE_CALL();
    if (strcmp("c", str) == 0) {
        *dest = TEXT_CENTER_VALUE;
        return true;
    }

    char* end;
    int val = (int) strtol(str, &end, 0);
    if (end == str || *end != '\0' || val == INT_MAX || val == INT_MIN) {
        return false;
    }
    *dest = val;
    return true;
}

// Parse two position coordinates. If only string is non-empty, treat it as the y value.
void parsePosition(const char* str1, const char* str2, int* x, int* y) {
    ATRACE_CALL();
    bool success = false;
    if (strlen(str1) == 0) {  // No values were specified
        // success = false
    } else if (strlen(str2) == 0) {  // we have only one value
        if (parseTextCoord(str1, y)) {
            *x = TEXT_CENTER_VALUE;
            success = true;
        }
    } else {
        if (parseTextCoord(str1, x) && parseTextCoord(str2, y)) {
            success = true;
        }
    }

    if (!success) {
        *x = TEXT_MISSING_VALUE;
        *y = TEXT_MISSING_VALUE;
    }
}

// Parse a color represented as an HTML-style 'RRGGBB' string: each pair of
// characters in str is a hex number in [0, 255], which are converted to
// floating point values in the range [0.0, 1.0] and placed in the
// corresponding elements of color.
//
// If the input string isn't valid, parseColor returns false and color is
// left unchanged.
static bool parseColor(const char str[7], float color[3]) {
    ATRACE_CALL();
    float tmpColor[3];
    for (int i = 0; i < 3; i++) {
        int val = 0;
        for (int j = 0; j < 2; j++) {
            val *= 16;
            char c = str[2*i + j];
            if      (c >= '0' && c <= '9') val += c - '0';
            else if (c >= 'A' && c <= 'F') val += (c - 'A') + 10;
            else if (c >= 'a' && c <= 'f') val += (c - 'a') + 10;
            else                           return false;
        }
        tmpColor[i] = static_cast<float>(val) / 255.0f;
    }
    memcpy(color, tmpColor, sizeof(tmpColor));
    return true;
}

// Parse a color represented as a signed decimal int string.
// E.g. "-2757722" (whose hex 2's complement is 0xFFD5EBA6).
// If the input color string is empty, set color with values in defaultColor.
static void parseColorDecimalString(const std::string& colorString,
    float color[3], float defaultColor[3]) {
    ATRACE_CALL();
    if (colorString == "") {
        memcpy(color, defaultColor, sizeof(float) * 3);
        return;
    }
    int colorInt = atoi(colorString.c_str());
    color[0] = ((float)((colorInt >> 16) & 0xFF)) / 0xFF; // r
    color[1] = ((float)((colorInt >> 8) & 0xFF)) / 0xFF; // g
    color[2] = ((float)(colorInt & 0xFF)) / 0xFF; // b
}

static bool readFile(ZipFileRO* zip, const char* name, String8& outString) {
    ATRACE_CALL();
    ZipEntryRO entry = zip->findEntryByName(name);
    SLOGE_IF(!entry, "couldn't find %s", name);
    if (!entry) {
        return false;
    }

    FileMap* entryMap = zip->createEntryFileMap(entry);
    zip->releaseEntry(entry);
    SLOGE_IF(!entryMap, "entryMap is null");
    if (!entryMap) {
        return false;
    }

    outString = String8((char const*)entryMap->getDataPtr(), entryMap->getDataLength());
    delete entryMap;
    return true;
}

// The font image should be a 96x2 array of character images.  The
// columns are the printable ASCII characters 0x20 - 0x7f.  The
// top row is regular text; the bottom row is bold.
status_t BootAnimation::initFont(Font* font, const char* fallback) {
    ATRACE_CALL();
    status_t status = NO_ERROR;

    if (font->map != nullptr) {
        glGenTextures(1, &font->texture.name);
        glBindTexture(GL_TEXTURE_2D, font->texture.name);

        status = initTexture(font->map, &font->texture.w, &font->texture.h);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    } else if (fallback != nullptr) {
        status = initTexture(&font->texture, mAssets, fallback);
    } else {
        return NO_INIT;
    }

    if (status == NO_ERROR) {
        font->char_width = font->texture.w / FONT_NUM_COLS;
        font->char_height = font->texture.h / FONT_NUM_ROWS / 2;  // There are bold and regular rows
    }

    return status;
}

void BootAnimation::drawText(const char* str, const Font& font, bool bold, int* x, int* y) {
    ATRACE_CALL();
    glEnable(GL_BLEND);  // Allow us to draw on top of the animation
    glBindTexture(GL_TEXTURE_2D, font.texture.name);
    glUseProgram(mTextShader);
    glUniform1i(mTextTextureLocation, 0);

    const int len = strlen(str);
    const int strWidth = font.char_width * len;

    if (*x == TEXT_CENTER_VALUE) {
        *x = (mWidth - strWidth) / 2;
    } else if (*x < 0) {
        *x = mWidth + *x - strWidth;
    }
    if (*y == TEXT_CENTER_VALUE) {
        *y = (mHeight - font.char_height) / 2;
    } else if (*y < 0) {
        *y = mHeight + *y - font.char_height;
    }

    for (int i = 0; i < len; i++) {
        char c = str[i];

        if (c < FONT_BEGIN_CHAR || c > FONT_END_CHAR) {
            c = '?';
        }

        // Crop the texture to only the pixels in the current glyph
        const int charPos = (c - FONT_BEGIN_CHAR);  // Position in the list of valid characters
        const int row = charPos / FONT_NUM_COLS;
        const int col = charPos % FONT_NUM_COLS;
        // Bold fonts are expected in the second half of each row.
        float v0 = (row + (bold ? 0.5f : 0.0f)) / FONT_NUM_ROWS;
        float u0 = ((float)col) / FONT_NUM_COLS;
        float v1 = v0 + 1.0f / FONT_NUM_ROWS / 2;
        float u1 = u0 + 1.0f / FONT_NUM_COLS;
        glUniform4f(mTextCropAreaLocation, u0, v0, u1, v1);
        drawTexturedQuad(*x, *y, font.char_width, font.char_height);

        *x += font.char_width;
    }

    glDisable(GL_BLEND);  // Return to the animation's default behaviour
    glBindTexture(GL_TEXTURE_2D, 0);
}

// We render 12 or 24 hour time.
void BootAnimation::drawClock(const Font& font, const int xPos, const int yPos) {
    ATRACE_CALL();
    static constexpr char TIME_FORMAT_12[] = "%l:%M";
    static constexpr char TIME_FORMAT_24[] = "%H:%M";
    static constexpr int TIME_LENGTH = 6;

    time_t rawtime;
    time(&rawtime);
    struct tm* timeInfo = localtime(&rawtime);

    char timeBuff[TIME_LENGTH];
    const char* timeFormat = mTimeFormat12Hour ? TIME_FORMAT_12 : TIME_FORMAT_24;
    size_t length = strftime(timeBuff, TIME_LENGTH, timeFormat, timeInfo);

    if (length != TIME_LENGTH - 1) {
        SLOGE("Couldn't format time; abandoning boot animation clock");
        mClockEnabled = false;
        return;
    }

    char* out = timeBuff[0] == ' ' ? &timeBuff[1] : &timeBuff[0];
    int x = xPos;
    int y = yPos;
    drawText(out, font, false, &x, &y);
}

void BootAnimation::drawProgress(int percent, const Font& font, const int xPos, const int yPos) {
    ATRACE_CALL();
    static constexpr int PERCENT_LENGTH = 5;

    char percentBuff[PERCENT_LENGTH];
    // ';' has the ascii code just after ':', and the font resource contains '%'
    // for that ascii code.
    sprintf(percentBuff, "%d;", percent);
    int x = xPos;
    int y = yPos;
    drawText(percentBuff, font, false, &x, &y);
}

bool BootAnimation::parseAnimationDesc(Animation& animation)  {
    ATRACE_CALL();
    String8 desString;

    if (!readFile(animation.zip, "desc.txt", desString)) {
        return false;
    }
    char const* s = desString.c_str();
    std::string dynamicColoringPartName = "";
    bool postDynamicColoring = false;

    // Parse the description file
    for (;;) {
        const char* endl = strstr(s, "\n");
        if (endl == nullptr) break;
        String8 line(s, endl - s);
        const char* l = line.c_str();
        int fps = 0;
        int width = 0;
        int height = 0;
        int count = 0;
        int pause = 0;
        int progress = 0;
        int framesToFadeCount = 0;
        int colorTransitionStart = 0;
        int colorTransitionEnd = 0;
        char path[ANIM_ENTRY_NAME_MAX];
        char color[7] = "000000"; // default to black if unspecified
        char clockPos1[TEXT_POS_LEN_MAX + 1] = "";
        char clockPos2[TEXT_POS_LEN_MAX + 1] = "";
        char dynamicColoringPartNameBuffer[ANIM_ENTRY_NAME_MAX];
        char pathType;
        // start colors default to black if unspecified
        char start_color_0[7] = "000000";
        char start_color_1[7] = "000000";
        char start_color_2[7] = "000000";
        char start_color_3[7] = "000000";

        int nextReadPos;

        if (strlen(l) == 0) {
            s = ++endl;
            continue;
        }

        int topLineNumbers = sscanf(l, "%d %d %d %d", &width, &height, &fps, &progress);
        if (topLineNumbers == 3 || topLineNumbers == 4) {
            // SLOGD("> w=%d, h=%d, fps=%d, progress=%d", width, height, fps, progress);
            animation.width = width;
            animation.height = height;
            animation.fps = fps;
            if (topLineNumbers == 4) {
              animation.progressEnabled = (progress != 0);
            } else {
              animation.progressEnabled = false;
            }
        } else if (sscanf(l, "dynamic_colors %" STRTO(ANIM_PATH_MAX) "s #%6s #%6s #%6s #%6s %d %d",
            dynamicColoringPartNameBuffer,
            start_color_0, start_color_1, start_color_2, start_color_3,
            &colorTransitionStart, &colorTransitionEnd)) {
            animation.dynamicColoringEnabled = true;
            parseColor(start_color_0, animation.startColors[0]);
            parseColor(start_color_1, animation.startColors[1]);
            parseColor(start_color_2, animation.startColors[2]);
            parseColor(start_color_3, animation.startColors[3]);
            animation.colorTransitionStart = colorTransitionStart;
            animation.colorTransitionEnd = colorTransitionEnd;
            dynamicColoringPartName = std::string(dynamicColoringPartNameBuffer);
        } else if (sscanf(l, "%c %d %d %" STRTO(ANIM_PATH_MAX) "s%n",
                          &pathType, &count, &pause, path, &nextReadPos) >= 4) {
            if (pathType == 'f') {
                sscanf(l + nextReadPos, " %d #%6s %16s %16s", &framesToFadeCount, color, clockPos1,
                       clockPos2);
            } else {
                sscanf(l + nextReadPos, " #%6s %16s %16s", color, clockPos1, clockPos2);
            }
            // SLOGD("> type=%c, count=%d, pause=%d, path=%s, framesToFadeCount=%d, color=%s, "
            //       "clockPos1=%s, clockPos2=%s",
            //       pathType, count, pause, path, framesToFadeCount, color, clockPos1, clockPos2);
            Animation::Part part;
            if (path == dynamicColoringPartName) {
                // Part is specified to use dynamic coloring.
                part.useDynamicColoring = true;
                part.postDynamicColoring = false;
                postDynamicColoring = true;
            } else {
                // Part does not use dynamic coloring.
                part.useDynamicColoring = false;
                part.postDynamicColoring =  postDynamicColoring;
            }
            part.playUntilComplete = pathType == 'c';
            part.framesToFadeCount = framesToFadeCount;
            part.count = count;
            part.pause = pause;
            part.path = path;
            part.audioData = nullptr;
            part.animation = nullptr;
            if (!parseColor(color, part.backgroundColor)) {
                SLOGE("> invalid color '#%s'", color);
                part.backgroundColor[0] = 0.0f;
                part.backgroundColor[1] = 0.0f;
                part.backgroundColor[2] = 0.0f;
            }
            parsePosition(clockPos1, clockPos2, &part.clockPosX, &part.clockPosY);
            animation.parts.add(part);
        }
        else if (strcmp(l, "$SYSTEM") == 0) {
            // SLOGD("> SYSTEM");
            Animation::Part part;
            part.playUntilComplete = false;
            part.framesToFadeCount = 0;
            part.count = 1;
            part.pause = 0;
            part.audioData = nullptr;
            part.animation = loadAnimation(String8(SYSTEM_BOOTANIMATION_FILE));
            if (part.animation != nullptr)
                animation.parts.add(part);
        }
        s = ++endl;
    }

    return true;
}

bool BootAnimation::preloadZip(Animation& animation) {
    ATRACE_CALL();
    // read all the data structures
    const size_t pcount = animation.parts.size();
    void *cookie = nullptr;
    ZipFileRO* zip = animation.zip;
    if (!zip->startIteration(&cookie)) {
        return false;
    }

    ZipEntryRO entry;
    char name[ANIM_ENTRY_NAME_MAX];
    while ((entry = zip->nextEntry(cookie)) != nullptr) {
        const int foundEntryName = zip->getEntryFileName(entry, name, ANIM_ENTRY_NAME_MAX);
        if (foundEntryName > ANIM_ENTRY_NAME_MAX || foundEntryName == -1) {
            SLOGE("Error fetching entry file name");
            continue;
        }

        const std::filesystem::path entryName(name);
        const std::filesystem::path path(entryName.parent_path());
        const std::filesystem::path leaf(entryName.filename());
        if (!leaf.empty()) {
            if (entryName == CLOCK_FONT_ZIP_NAME) {
                FileMap* map = zip->createEntryFileMap(entry);
                if (map) {
                    animation.clockFont.map = map;
                }
                continue;
            }

            if (entryName == PROGRESS_FONT_ZIP_NAME) {
                FileMap* map = zip->createEntryFileMap(entry);
                if (map) {
                    animation.progressFont.map = map;
                }
                continue;
            }

            for (size_t j = 0; j < pcount; j++) {
                if (path.string() == animation.parts[j].path.c_str()) {
                    uint16_t method;
                    // supports only stored png files
                    if (zip->getEntryInfo(entry, &method, nullptr, nullptr, nullptr, nullptr,
                            nullptr, nullptr)) {
                        if (method == ZipFileRO::kCompressStored) {
                            FileMap* map = zip->createEntryFileMap(entry);
                            if (map) {
                                Animation::Part& part(animation.parts.editItemAt(j));
                                if (leaf == "audio.wav") {
                                    // a part may have at most one audio file
                                    part.audioData = (uint8_t *)map->getDataPtr();
                                    part.audioLength = map->getDataLength();
                                } else if (leaf == "trim.txt") {
                                    part.trimData = String8((char const*)map->getDataPtr(),
                                                        map->getDataLength());
                                } else {
                                    Animation::Frame frame;
                                    frame.name = leaf.c_str();
                                    frame.map = map;
                                    frame.trimWidth = animation.width;
                                    frame.trimHeight = animation.height;
                                    frame.trimX = 0;
                                    frame.trimY = 0;
                                    part.frames.add(frame);
                                }
                            }
                        } else {
                            SLOGE("bootanimation.zip is compressed; must be only stored");
                        }
                    }
                }
            }
        }
    }

    // If there is trimData present, override the positioning defaults.
    for (Animation::Part& part : animation.parts) {
        const char* trimDataStr = part.trimData.c_str();
        for (size_t frameIdx = 0; frameIdx < part.frames.size(); frameIdx++) {
            const char* endl = strstr(trimDataStr, "\n");
            // No more trimData for this part.
            if (endl == nullptr) {
                break;
            }
            String8 line(trimDataStr, endl - trimDataStr);
            const char* lineStr = line.c_str();
            trimDataStr = ++endl;
            int width = 0, height = 0, x = 0, y = 0;
            if (sscanf(lineStr, "%dx%d+%d+%d", &width, &height, &x, &y) == 4) {
                Animation::Frame& frame(part.frames.editItemAt(frameIdx));
                frame.trimWidth = width;
                frame.trimHeight = height;
                frame.trimX = x;
                frame.trimY = y;
            } else {
                SLOGE("Error parsing trim.txt, line: %s", lineStr);
                break;
            }
        }
    }

    zip->endIteration(cookie);

    return true;
}

bool BootAnimation::movie() {
    ATRACE_CALL();
    if (mAnimation == nullptr) {
        mAnimation = loadAnimation(mZipFileName);
    }

    if (mAnimation == nullptr)
        return false;

    // mCallbacks->init() may get called recursively,
    // this loop is needed to get the same results
    for (const Animation::Part& part : mAnimation->parts) {
        if (part.animation != nullptr) {
            mCallbacks->init(part.animation->parts);
        }
    }
    mCallbacks->init(mAnimation->parts);

    bool anyPartHasClock = false;
    for (size_t i=0; i < mAnimation->parts.size(); i++) {
        if(validClock(mAnimation->parts[i])) {
            anyPartHasClock = true;
            break;
        }
    }
    if (!anyPartHasClock) {
        mClockEnabled = false;
    } else if (!android::base::GetBoolProperty(CLOCK_ENABLED_PROP_NAME, false)) {
        mClockEnabled = false;
    }

    // Check if npot textures are supported
    mUseNpotTextures = false;
    String8 gl_extensions;
    const char* exts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!exts) {
        glGetError();
    } else {
        gl_extensions = exts;
        if ((gl_extensions.find("GL_ARB_texture_non_power_of_two") != -1) ||
            (gl_extensions.find("GL_OES_texture_npot") != -1)) {
            mUseNpotTextures = true;
        }
    }

    // Blend required to draw time on top of animation frames.
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);

    glEnable(GL_TEXTURE_2D);
    glBindTexture(GL_TEXTURE_2D, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    bool clockFontInitialized = false;
    if (mClockEnabled) {
        clockFontInitialized =
            (initFont(&mAnimation->clockFont, CLOCK_FONT_ASSET) == NO_ERROR);
        mClockEnabled = clockFontInitialized;
    }

    initFont(&mAnimation->progressFont, PROGRESS_FONT_ASSET);

    if (mClockEnabled && !updateIsTimeAccurate()) {
        mTimeCheckThread = new TimeCheckThread(this);
        mTimeCheckThread->run("BootAnimation::TimeCheckThread", PRIORITY_NORMAL);
    }

    if (mAnimation->dynamicColoringEnabled) {
        initDynamicColors();
    }

    playAnimation(*mAnimation);

    if (mTimeCheckThread != nullptr) {
        mTimeCheckThread->requestExit();
        mTimeCheckThread = nullptr;
    }

    if (clockFontInitialized) {
        glDeleteTextures(1, &mAnimation->clockFont.texture.name);
    }

    releaseAnimation(mAnimation);
    mAnimation = nullptr;

    return false;
}

bool BootAnimation::shouldStopPlayingPart(const Animation::Part& part,
                                          const int fadedFramesCount,
                                          const int lastDisplayedProgress) {
    ATRACE_CALL();
    // stop playing only if it is time to exit and it's a partial part which has been faded out
    return exitPending() && !part.playUntilComplete && fadedFramesCount >= part.framesToFadeCount &&
        (lastDisplayedProgress == 0 || lastDisplayedProgress == 100);
}

// Linear mapping from range <a1, a2> to range <b1, b2>
float mapLinear(float x, float a1, float a2, float b1, float b2) {
    return b1 + ( x - a1 ) * ( b2 - b1 ) / ( a2 - a1 );
}

void BootAnimation::drawTexturedQuad(float xStart, float yStart, float width, float height) {
    ATRACE_CALL();
    // Map coordinates from screen space to world space.
    float x0 = mapLinear(xStart, 0, mWidth, -1, 1);
    float y0 = mapLinear(yStart, 0, mHeight, -1, 1);
    float x1 = mapLinear(xStart + width, 0, mWidth, -1, 1);
    float y1 = mapLinear(yStart + height, 0, mHeight, -1, 1);
    // Update quad vertex positions.
    quadPositions[0] = x0;
    quadPositions[1] = y0;
    quadPositions[2] = x1;
    quadPositions[3] = y0;
    quadPositions[4] = x1;
    quadPositions[5] = y1;
    quadPositions[6] = x1;
    quadPositions[7] = y1;
    quadPositions[8] = x0;
    quadPositions[9] = y1;
    quadPositions[10] = x0;
    quadPositions[11] = y0;
    glDrawArrays(GL_TRIANGLES, 0,
        sizeof(quadPositions) / sizeof(quadPositions[0]) / 2);
}

void BootAnimation::initDynamicColors() {
    ATRACE_CALL();
    for (int i = 0; i < DYNAMIC_COLOR_COUNT; i++) {
        const auto syspropName = "persist.bootanim.color" + std::to_string(i + 1);
        const auto syspropValue = android::base::GetProperty(syspropName, "");
        if (syspropValue != "") {
            SLOGI("Loaded dynamic color: %s -> %s", syspropName.c_str(), syspropValue.c_str());
            mDynamicColorsApplied = true;
        }
        parseColorDecimalString(syspropValue,
            mAnimation->endColors[i], mAnimation->startColors[i]);
    }
    glUseProgram(mImageShader);
    SLOGI("Dynamically coloring boot animation. Sysprops loaded? %i", mDynamicColorsApplied);
    for (int i = 0; i < DYNAMIC_COLOR_COUNT; i++) {
        float *startColor = mAnimation->startColors[i];
        float *endColor = mAnimation->endColors[i];
        glUniform3f(glGetUniformLocation(mImageShader,
            (U_START_COLOR_PREFIX + std::to_string(i)).c_str()),
            startColor[0], startColor[1], startColor[2]);
        glUniform3f(glGetUniformLocation(mImageShader,
            (U_END_COLOR_PREFIX + std::to_string(i)).c_str()),
            endColor[0], endColor[1], endColor[2]);
    }
    mImageColorProgressLocation = glGetUniformLocation(mImageShader, U_COLOR_PROGRESS);
}

bool BootAnimation::playAnimation(const Animation& animation) {
    ATRACE_CALL();
    const size_t pcount = animation.parts.size();
    nsecs_t frameDuration = s2ns(1) / animation.fps;

    SLOGD("%sAnimationShownTiming start time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());

    int fadedFramesCount = 0;
    int lastDisplayedProgress = 0;
    int colorTransitionStart = animation.colorTransitionStart;
    int colorTransitionEnd = animation.colorTransitionEnd;
    for (size_t i=0 ; i<pcount ; i++) {
        const Animation::Part& part(animation.parts[i]);
        const size_t fcount = part.frames.size();
        glBindTexture(GL_TEXTURE_2D, 0);

        // Handle animation package
        if (part.animation != nullptr) {
            playAnimation(*part.animation);
            if (exitPending())
                break;
            continue; //to next part
        }

        // process the part not only while the count allows but also if already fading
        for (int r=0 ; !part.count || r<part.count || fadedFramesCount > 0 ; r++) {
            if (shouldStopPlayingPart(part, fadedFramesCount, lastDisplayedProgress)) break;

            // It's possible that the sysprops were not loaded yet at this boot phase.
            // If that's the case, then we should keep trying until they are available.
            if (animation.dynamicColoringEnabled && !mDynamicColorsApplied
                && (part.useDynamicColoring || part.postDynamicColoring)) {
                SLOGD("Trying to load dynamic color sysprops.");
                initDynamicColors();
                if (mDynamicColorsApplied) {
                    // Sysprops were loaded. Next step is to adjust the animation if we loaded
                    // the colors after the animation should have started.
                    const int transitionLength = colorTransitionEnd - colorTransitionStart;
                    if (part.postDynamicColoring) {
                        colorTransitionStart = 0;
                        colorTransitionEnd = fmin(transitionLength, fcount - 1);
                    }
                }
            }

            mCallbacks->playPart(i, part, r);

            glClearColor(
                    part.backgroundColor[0],
                    part.backgroundColor[1],
                    part.backgroundColor[2],
                    1.0f);

            ALOGD("Playing files = %s/%s, Requested repeat = %d, playUntilComplete = %s",
                    animation.fileName.c_str(), part.path.c_str(), part.count,
                    part.playUntilComplete ? "true" : "false");

            // For the last animation, if we have progress indicator from
            // the system, display it.
            int currentProgress = android::base::GetIntProperty(PROGRESS_PROP_NAME, 0);
            bool displayProgress = animation.progressEnabled &&
                (i == (pcount -1)) && currentProgress != 0;

            for (size_t j=0 ; j<fcount ; j++) {
                if (shouldStopPlayingPart(part, fadedFramesCount, lastDisplayedProgress)) break;

                // Color progress is
                // - the animation progress, normalized from
                //   [colorTransitionStart,colorTransitionEnd] to [0, 1] for the dynamic coloring
                //   part.
                // - 0 for parts that come before,
                // - 1 for parts that come after.
                float colorProgress = part.useDynamicColoring
                    ? fmin(fmax(
                        ((float)j - colorTransitionStart) /
                            fmax(colorTransitionEnd - colorTransitionStart, 1.0f), 0.0f), 1.0f)
                    : (part.postDynamicColoring ? 1 : 0);

                processDisplayEvents();

                const double ratio_w = static_cast<double>(mWidth) / mInitWidth;
                const double ratio_h = static_cast<double>(mHeight) / mInitHeight;
                const int animationX = (mWidth - animation.width * ratio_w) / 2;
                const int animationY = (mHeight - animation.height * ratio_h) / 2;

                const Animation::Frame& frame(part.frames[j]);
                nsecs_t lastFrame = systemTime();

                if (r > 0) {
                    glBindTexture(GL_TEXTURE_2D, frame.tid);
                } else {
                    if (part.count != 1) {
                        glGenTextures(1, &frame.tid);
                        glBindTexture(GL_TEXTURE_2D, frame.tid);
                    }
                    int w, h;
                    // Set decoding option to alpha unpremultiplied so that the R, G, B channels
                    // of transparent pixels are preserved.
                    initTexture(frame.map, &w, &h, false /* don't premultiply alpha */);
                }

                const int trimWidth = frame.trimWidth * ratio_w;
                const int trimHeight = frame.trimHeight * ratio_h;
                const int trimX = frame.trimX * ratio_w;
                const int trimY = frame.trimY * ratio_h;
                const int xc = animationX + trimX;
                const int yc = animationY + trimY;
                glClear(GL_COLOR_BUFFER_BIT);
                // specify the y center as ceiling((mHeight - frame.trimHeight) / 2)
                // which is equivalent to mHeight - (yc + frame.trimHeight)
                const int frameDrawY = mHeight - (yc + trimHeight);

                float fade = 0;
                // if the part hasn't been stopped yet then continue fading if necessary
                if (exitPending() && part.hasFadingPhase()) {
                    fade = static_cast<float>(++fadedFramesCount) / part.framesToFadeCount;
                    if (fadedFramesCount >= part.framesToFadeCount) {
                        fadedFramesCount = MAX_FADED_FRAMES_COUNT; // no more fading
                    }
                }
                glUseProgram(mImageShader);
                glUniform1i(mImageTextureLocation, 0);
                glUniform1f(mImageFadeLocation, fade);
                if (animation.dynamicColoringEnabled) {
                    glUniform1f(mImageColorProgressLocation, colorProgress);
                }
                glEnable(GL_BLEND);
                drawTexturedQuad(xc, frameDrawY, trimWidth, trimHeight);
                glDisable(GL_BLEND);

                if (mClockEnabled && mTimeIsAccurate && validClock(part)) {
                    drawClock(animation.clockFont, part.clockPosX, part.clockPosY);
                }

                if (displayProgress) {
                    int newProgress = android::base::GetIntProperty(PROGRESS_PROP_NAME, 0);
                    // In case the new progress jumped suddenly, still show an
                    // increment of 1.
                    if (lastDisplayedProgress != 100) {
                      // Artificially sleep 1/10th a second to slow down the animation.
                      usleep(100000);
                      if (lastDisplayedProgress < newProgress) {
                        lastDisplayedProgress++;
                      }
                    }
                    // Put the progress percentage right below the animation.
                    int posY = animation.height / 3;
                    int posX = TEXT_CENTER_VALUE;
                    drawProgress(lastDisplayedProgress, animation.progressFont, posX, posY);
                }

                handleViewport(frameDuration);

                eglSwapBuffers(mDisplay, mSurface);

                nsecs_t now = systemTime();
                nsecs_t delay = frameDuration - (now - lastFrame);
                //SLOGD("%lld, %lld", ns2ms(now - lastFrame), ns2ms(delay));
                lastFrame = now;

                if (delay > 0) {
                    struct timespec spec;
                    spec.tv_sec  = (now + delay) / 1000000000;
                    spec.tv_nsec = (now + delay) % 1000000000;
                    int err;
                    do {
                        err = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &spec, nullptr);
                    } while (err == EINTR);
                }

                checkExit();
            }

            int pauseDuration = part.pause * ns2us(frameDuration);
            while(pauseDuration > 0 && !exitPending()){
                if (pauseDuration > MAX_CHECK_EXIT_INTERVAL_US) {
                    usleep(MAX_CHECK_EXIT_INTERVAL_US);
                    pauseDuration -= MAX_CHECK_EXIT_INTERVAL_US;
                } else {
                    usleep(pauseDuration);
                    break;
                }
                checkExit();
            }

            if (exitPending() && !part.count && mCurrentInset >= mTargetInset &&
                !part.hasFadingPhase()) {
                if (lastDisplayedProgress != 0 && lastDisplayedProgress != 100) {
                    android::base::SetProperty(PROGRESS_PROP_NAME, "100");
                    continue;
                }
                break; // exit the infinite non-fading part when it has been played at least once
            }
        }
    }

    // Free textures created for looping parts now that the animation is done.
    for (const Animation::Part& part : animation.parts) {
        if (part.count != 1) {
            const size_t fcount = part.frames.size();
            for (size_t j = 0; j < fcount; j++) {
                const Animation::Frame& frame(part.frames[j]);
                glDeleteTextures(1, &frame.tid);
            }
        }
    }

    ALOGD("%sAnimationShownTiming End time: %" PRId64 "ms", mShuttingDown ? "Shutdown" : "Boot",
            elapsedRealtime());

    return true;
}

void BootAnimation::processDisplayEvents() {
    ATRACE_CALL();
    // This will poll mDisplayEventReceiver and if there are new events it'll call
    // displayEventCallback synchronously.
    mLooper->pollOnce(0);
}

void BootAnimation::handleViewport(nsecs_t timestep) {
    ATRACE_CALL();
    if (mShuttingDown || !mFlingerSurfaceControl || mTargetInset == 0) {
        return;
    }
    if (mTargetInset < 0) {
        // Poll the amount for the top display inset. This will return -1 until persistent properties
        // have been loaded.
        mTargetInset = android::base::GetIntProperty("persist.sys.displayinset.top",
                -1 /* default */, -1 /* min */, mHeight / 2 /* max */);
    }
    if (mTargetInset <= 0) {
        return;
    }

    if (mCurrentInset < mTargetInset) {
        // After the device boots, the inset will effectively be cropped away. We animate this here.
        float fraction = static_cast<float>(mCurrentInset) / mTargetInset;
        int interpolatedInset = (cosf((fraction + 1) * M_PI) / 2.0f + 0.5f) * mTargetInset;

        SurfaceComposerClient::Transaction()
                .setCrop(mFlingerSurfaceControl, Rect(0, interpolatedInset, mWidth, mHeight))
                .apply();
    } else {
        // At the end of the animation, we switch to the viewport that DisplayManager will apply
        // later. This changes the coordinate system, and means we must move the surface up by
        // the inset amount.
        Rect layerStackRect(0, 0, mWidth, mHeight - mTargetInset);
        Rect displayRect(0, mTargetInset, mWidth, mHeight);

        SurfaceComposerClient::Transaction t;
        t.setPosition(mFlingerSurfaceControl, 0, -mTargetInset)
                .setCrop(mFlingerSurfaceControl, Rect(0, mTargetInset, mWidth, mHeight));
        t.setDisplayProjection(mDisplayToken, ui::ROTATION_0, layerStackRect, displayRect);
        t.apply();

        mTargetInset = mCurrentInset = 0;
    }

    int delta = timestep * mTargetInset / ms2ns(200);
    mCurrentInset += delta;
}

void BootAnimation::releaseAnimation(Animation* animation) const {
    ATRACE_CALL();
    for (Vector<Animation::Part>::iterator it = animation->parts.begin(),
         e = animation->parts.end(); it != e; ++it) {
        if (it->animation)
            releaseAnimation(it->animation);
    }
    if (animation->zip)
        delete animation->zip;
    delete animation;
}

BootAnimation::Animation* BootAnimation::loadAnimation(const String8& fn) {
    ATRACE_CALL();
    if (mLoadedFiles.indexOf(fn) >= 0) {
        SLOGE("File \"%s\" is already loaded. Cyclic ref is not allowed",
            fn.c_str());
        return nullptr;
    }
    ZipFileRO *zip = ZipFileRO::open(fn.c_str());
    if (zip == nullptr) {
        SLOGE("Failed to open animation zip \"%s\": %s",
            fn.c_str(), strerror(errno));
        return nullptr;
    }

    ALOGD("%s is loaded successfully", fn.c_str());

    Animation *animation =  new Animation;
    animation->fileName = fn;
    animation->zip = zip;
    animation->clockFont.map = nullptr;
    mLoadedFiles.add(animation->fileName);

    parseAnimationDesc(*animation);
    if (!preloadZip(*animation)) {
        releaseAnimation(animation);
        return nullptr;
    }

    mLoadedFiles.remove(fn);
    return animation;
}

bool BootAnimation::updateIsTimeAccurate() {
    ATRACE_CALL();
    static constexpr long long MAX_TIME_IN_PAST =   60000LL * 60LL * 24LL * 30LL;  // 30 days
    static constexpr long long MAX_TIME_IN_FUTURE = 60000LL * 90LL;  // 90 minutes

    if (mTimeIsAccurate) {
        return true;
    }
    if (mShuttingDown) return true;
    struct stat statResult;

    if(stat(TIME_FORMAT_12_HOUR_FLAG_FILE_PATH, &statResult) == 0) {
        mTimeFormat12Hour = true;
    }

    if(stat(ACCURATE_TIME_FLAG_FILE_PATH, &statResult) == 0) {
        mTimeIsAccurate = true;
        return true;
    }

    FILE* file = fopen(LAST_TIME_CHANGED_FILE_PATH, "r");
    if (file != nullptr) {
      long long lastChangedTime = 0;
      fscanf(file, "%lld", &lastChangedTime);
      fclose(file);
      if (lastChangedTime > 0) {
        struct timespec now;
        clock_gettime(CLOCK_REALTIME, &now);
        // Match the Java timestamp format
        long long rtcNow = (now.tv_sec * 1000LL) + (now.tv_nsec / 1000000LL);
        if (ACCURATE_TIME_EPOCH < rtcNow
            && lastChangedTime > (rtcNow - MAX_TIME_IN_PAST)
            && lastChangedTime < (rtcNow + MAX_TIME_IN_FUTURE)) {
            mTimeIsAccurate = true;
        }
      }
    }

    return mTimeIsAccurate;
}

BootAnimation::TimeCheckThread::TimeCheckThread(BootAnimation* bootAnimation) : Thread(false),
    mInotifyFd(-1), mBootAnimWd(-1), mTimeWd(-1), mBootAnimation(bootAnimation) {}

BootAnimation::TimeCheckThread::~TimeCheckThread() {
    ATRACE_CALL();
    // mInotifyFd may be -1 but that's ok since we're not at risk of attempting to close a valid FD.
    close(mInotifyFd);
}

bool BootAnimation::TimeCheckThread::threadLoop() {
    ATRACE_CALL();
    bool shouldLoop = doThreadLoop() && !mBootAnimation->mTimeIsAccurate
        && mBootAnimation->mClockEnabled;
    if (!shouldLoop) {
        close(mInotifyFd);
        mInotifyFd = -1;
    }
    return shouldLoop;
}

bool BootAnimation::TimeCheckThread::doThreadLoop() {
    ATRACE_CALL();
    static constexpr int BUFF_LEN (10 * (sizeof(struct inotify_event) + NAME_MAX + 1));

    // Poll instead of doing a blocking read so the Thread can exit if requested.
    struct pollfd pfd = { mInotifyFd, POLLIN, 0 };
    ssize_t pollResult = poll(&pfd, 1, 1000);

    if (pollResult == 0) {
        return true;
    } else if (pollResult < 0) {
        SLOGE("Could not poll inotify events");
        return false;
    }

    char buff[BUFF_LEN] __attribute__ ((aligned(__alignof__(struct inotify_event))));;
    ssize_t length = read(mInotifyFd, buff, BUFF_LEN);
    if (length == 0) {
        return true;
    } else if (length < 0) {
        SLOGE("Could not read inotify events");
        return false;
    }

    const struct inotify_event *event;
    for (char* ptr = buff; ptr < buff + length; ptr += sizeof(struct inotify_event) + event->len) {
        event = (const struct inotify_event *) ptr;
        if (event->wd == mBootAnimWd && strcmp(BOOTANIM_TIME_DIR_NAME, event->name) == 0) {
            addTimeDirWatch();
        } else if (event->wd == mTimeWd && (strcmp(LAST_TIME_CHANGED_FILE_NAME, event->name) == 0
                || strcmp(ACCURATE_TIME_FLAG_FILE_NAME, event->name) == 0)) {
            return !mBootAnimation->updateIsTimeAccurate();
        }
    }

    return true;
}

void BootAnimation::TimeCheckThread::addTimeDirWatch() {
        ATRACE_CALL();
        mTimeWd = inotify_add_watch(mInotifyFd, BOOTANIM_TIME_DIR_PATH,
                IN_CLOSE_WRITE | IN_MOVED_TO | IN_ATTRIB);
        if (mTimeWd > 0) {
            // No need to watch for the time directory to be created if it already exists
            inotify_rm_watch(mInotifyFd, mBootAnimWd);
            mBootAnimWd = -1;
        }
}

status_t BootAnimation::TimeCheckThread::readyToRun() {
    ATRACE_CALL();
    mInotifyFd = inotify_init();
    if (mInotifyFd < 0) {
        SLOGE("Could not initialize inotify fd");
        return NO_INIT;
    }

    mBootAnimWd = inotify_add_watch(mInotifyFd, BOOTANIM_DATA_DIR_PATH, IN_CREATE | IN_ATTRIB);
    if (mBootAnimWd < 0) {
        close(mInotifyFd);
        mInotifyFd = -1;
        SLOGE("Could not add watch for %s: %s", BOOTANIM_DATA_DIR_PATH, strerror(errno));
        return NO_INIT;
    }

    addTimeDirWatch();

    if (mBootAnimation->updateIsTimeAccurate()) {
        close(mInotifyFd);
        mInotifyFd = -1;
        return ALREADY_EXISTS;
    }

    return NO_ERROR;
}

// ---------------------------------------------------------------------------

} // namespace android
