/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _UI_INPUT_WINDOW_H
#define _UI_INPUT_WINDOW_H

#include <ui/Input.h>
#include <ui/InputTransport.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>
#include <utils/String8.h>

#include <SkRegion.h>

#include "InputApplication.h"

namespace android {

/*
 * Describes the properties of a window that can receive input.
 */
struct InputWindowInfo {
    // Window flags from WindowManager.LayoutParams
    enum {
        FLAG_ALLOW_LOCK_WHILE_SCREEN_ON     = 0x00000001,
        FLAG_DIM_BEHIND        = 0x00000002,
        FLAG_BLUR_BEHIND        = 0x00000004,
        FLAG_NOT_FOCUSABLE      = 0x00000008,
        FLAG_NOT_TOUCHABLE      = 0x00000010,
        FLAG_NOT_TOUCH_MODAL    = 0x00000020,
        FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040,
        FLAG_KEEP_SCREEN_ON     = 0x00000080,
        FLAG_LAYOUT_IN_SCREEN   = 0x00000100,
        FLAG_LAYOUT_NO_LIMITS   = 0x00000200,
        FLAG_FULLSCREEN      = 0x00000400,
        FLAG_FORCE_NOT_FULLSCREEN   = 0x00000800,
        FLAG_DITHER             = 0x00001000,
        FLAG_SECURE             = 0x00002000,
        FLAG_SCALED             = 0x00004000,
        FLAG_IGNORE_CHEEK_PRESSES    = 0x00008000,
        FLAG_LAYOUT_INSET_DECOR = 0x00010000,
        FLAG_ALT_FOCUSABLE_IM = 0x00020000,
        FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000,
        FLAG_SHOW_WHEN_LOCKED = 0x00080000,
        FLAG_SHOW_WALLPAPER = 0x00100000,
        FLAG_TURN_SCREEN_ON = 0x00200000,
        FLAG_DISMISS_KEYGUARD = 0x00400000,
        FLAG_SPLIT_TOUCH = 0x00800000,
        FLAG_HARDWARE_ACCELERATED = 0x01000000,
        FLAG_HARDWARE_ACCELERATED_SYSTEM = 0x02000000,
        FLAG_SLIPPERY = 0x04000000,
        FLAG_NEEDS_MENU_KEY = 0x08000000,
        FLAG_KEEP_SURFACE_WHILE_ANIMATING = 0x10000000,
        FLAG_COMPATIBLE_WINDOW = 0x20000000,
        FLAG_SYSTEM_ERROR = 0x40000000,
    };

    // Window types from WindowManager.LayoutParams
    enum {
        FIRST_APPLICATION_WINDOW = 1,
        TYPE_BASE_APPLICATION   = 1,
        TYPE_APPLICATION        = 2,
        TYPE_APPLICATION_STARTING = 3,
        LAST_APPLICATION_WINDOW = 99,
        FIRST_SUB_WINDOW        = 1000,
        TYPE_APPLICATION_PANEL  = FIRST_SUB_WINDOW,
        TYPE_APPLICATION_MEDIA  = FIRST_SUB_WINDOW+1,
        TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW+2,
        TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW+3,
        TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW+4,
        LAST_SUB_WINDOW         = 1999,
        FIRST_SYSTEM_WINDOW     = 2000,
        TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW,
        TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1,
        TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2,
        TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3,
        TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4,
        TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5,
        TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6,
        TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7,
        TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8,
        TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9,
        TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10,
        TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11,
        TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12,
        TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13,
        TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+14,
        TYPE_SECURE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+15,
        TYPE_DRAG               = FIRST_SYSTEM_WINDOW+16,
        TYPE_STATUS_BAR_SUB_PANEL  = FIRST_SYSTEM_WINDOW+17,
        TYPE_POINTER            = FIRST_SYSTEM_WINDOW+18,
        TYPE_NAVIGATION_BAR     = FIRST_SYSTEM_WINDOW+19,
        LAST_SYSTEM_WINDOW      = 2999,
    };

    enum {
        INPUT_FEATURE_DISABLE_TOUCH_PAD_GESTURES = 0x00000001,
    };

    sp<InputChannel> inputChannel;
    String8 name;
    int32_t layoutParamsFlags;
    int32_t layoutParamsType;
    nsecs_t dispatchingTimeout;
    int32_t frameLeft;
    int32_t frameTop;
    int32_t frameRight;
    int32_t frameBottom;
    float scaleFactor;
    SkRegion touchableRegion;
    bool visible;
    bool canReceiveKeys;
    bool hasFocus;
    bool hasWallpaper;
    bool paused;
    int32_t layer;
    int32_t ownerPid;
    int32_t ownerUid;
    int32_t inputFeatures;

    bool touchableRegionContainsPoint(int32_t x, int32_t y) const;
    bool frameContainsPoint(int32_t x, int32_t y) const;

    /* Returns true if the window is of a trusted type that is allowed to silently
     * overlay other windows for the purpose of implementing the secure views feature.
     * Trusted overlays, such as IME windows, can partly obscure other windows without causing
     * motion events to be delivered to them with AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED.
     */
    bool isTrustedOverlay() const;

    bool supportsSplitTouch() const;
};


/*
 * Handle for a window that can receive input.
 *
 * Used by the native input dispatcher to indirectly refer to the window manager objects
 * that describe a window.
 */
class InputWindowHandle : public RefBase {
public:
    const sp<InputApplicationHandle> inputApplicationHandle;

    inline const InputWindowInfo* getInfo() const {
        return mInfo;
    }

    inline sp<InputChannel> getInputChannel() const {
        return mInfo ? mInfo->inputChannel : NULL;
    }

    inline String8 getName() const {
        return mInfo ? mInfo->name : String8("<invalid>");
    }

    inline nsecs_t getDispatchingTimeout(nsecs_t defaultValue) const {
        return mInfo ? mInfo->dispatchingTimeout : defaultValue;
    }

    /**
     * Requests that the state of this object be updated to reflect
     * the most current available information about the application.
     *
     * This method should only be called from within the input dispatcher's
     * critical section.
     *
     * Returns true on success, or false if the handle is no longer valid.
     */
    virtual bool updateInfo() = 0;

    /**
     * Releases the storage used by the associated information when it is
     * no longer needed.
     */
    void releaseInfo();

protected:
    InputWindowHandle(const sp<InputApplicationHandle>& inputApplicationHandle);
    virtual ~InputWindowHandle();

    InputWindowInfo* mInfo;
};

} // namespace android

#endif // _UI_INPUT_WINDOW_H
