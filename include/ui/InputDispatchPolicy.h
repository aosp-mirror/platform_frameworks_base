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

#ifndef _UI_INPUT_DISPATCH_POLICY_H
#define _UI_INPUT_DISPATCH_POLICY_H

/**
 * Native input dispatch policy.
 */

#include <ui/Input.h>
#include <utils/Errors.h>
#include <utils/Vector.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

namespace android {

class InputChannel;

/*
 * An input target specifies how an input event is to be dispatched to a particular window
 * including the window's input channel, control flags, a timeout, and an X / Y offset to
 * be added to input event coordinates to compensate for the absolute position of the
 * window area.
 */
struct InputTarget {
    enum {
        /* This flag indicates that subsequent event delivery should be held until the
         * current event is delivered to this target or a timeout occurs. */
        FLAG_SYNC = 0x01,

        /* This flag indicates that a MotionEvent with ACTION_DOWN falls outside of the area of
         * this target and so should instead be delivered as an ACTION_OUTSIDE to this target. */
        FLAG_OUTSIDE = 0x02,

        /* This flag indicates that a KeyEvent or MotionEvent is being canceled.
         * In the case of a key event, it should be delivered with KeyEvent.FLAG_CANCELED set.
         * In the case of a motion event, it should be delivered as MotionEvent.ACTION_CANCEL. */
        FLAG_CANCEL = 0x04
    };

    // The input channel to be targeted.
    sp<InputChannel> inputChannel;

    // Flags for the input target.
    int32_t flags;

    // The timeout for event delivery to this target in nanoseconds.  Or -1 if none.
    nsecs_t timeout;

    // The x and y offset to add to a MotionEvent as it is delivered.
    // (ignored for KeyEvents)
    float xOffset, yOffset;
};

/*
 * Input dispatch policy interface.
 *
 * The input dispatch policy is used by the input dispatcher to interact with the
 * Window Manager and other system components.  This separation of concerns keeps
 * the input dispatcher relatively free of special case logic such as is required
 * to determine the target of iput events, when to wake the device, how to interact
 * with key guard, and when to transition to the home screen.
 *
 * The actual implementation is partially supported by callbacks into the DVM
 * via JNI.  This class is also mocked in the input dispatcher unit tests since
 * it is an ideal test seam.
 */
class InputDispatchPolicyInterface : public virtual RefBase {
protected:
    InputDispatchPolicyInterface() { }
    virtual ~InputDispatchPolicyInterface() { }

public:
    enum {
        ROTATION_0 = 0,
        ROTATION_90 = 1,
        ROTATION_180 = 2,
        ROTATION_270 = 3
    };

    enum {
        // The input dispatcher should do nothing and discard the input unless other
        // flags are set.
        ACTION_NONE = 0,

        // The input dispatcher should dispatch the input to the application.
        ACTION_DISPATCH = 0x00000001,

        // The input dispatcher should perform special filtering in preparation for
        // a pending app switch.
        ACTION_APP_SWITCH_COMING = 0x00000002,

        // The input dispatcher should add POLICY_FLAG_WOKE_HERE to the policy flags it
        // passes through the dispatch pipeline.
        ACTION_WOKE_HERE = 0x00000004,

        // The input dispatcher should add POLICY_FLAG_BRIGHT_HERE to the policy flags it
        // passes through the dispatch pipeline.
        ACTION_BRIGHT_HERE = 0x00000008
    };

    enum {
        TOUCHSCREEN_UNDEFINED = 0,
        TOUCHSCREEN_NOTOUCH = 1,
        TOUCHSCREEN_STYLUS = 2,
        TOUCHSCREEN_FINGER = 3
    };

    enum {
        KEYBOARD_UNDEFINED = 0,
        KEYBOARD_NOKEYS = 1,
        KEYBOARD_QWERTY = 2,
        KEYBOARD_12KEY = 3
    };

    enum {
        NAVIGATION_UNDEFINED = 0,
        NAVIGATION_NONAV = 1,
        NAVIGATION_DPAD = 2,
        NAVIGATION_TRACKBALL = 3,
        NAVIGATION_WHEEL = 4
    };

    struct VirtualKeyDefinition {
        int32_t scanCode;

        // configured position data, specified in display coords
        int32_t centerX;
        int32_t centerY;
        int32_t width;
        int32_t height;
    };

    /* Gets information about the display with the specified id.
     * Returns true if the display info is available, false otherwise.
     */
    virtual bool getDisplayInfo(int32_t displayId,
            int32_t* width, int32_t* height, int32_t* orientation) = 0;

    virtual void notifyConfigurationChanged(nsecs_t when,
            int32_t touchScreenConfig, int32_t keyboardConfig, int32_t navigationConfig) = 0;

    virtual void notifyLidSwitchChanged(nsecs_t when, bool lidOpen) = 0;

    virtual void virtualKeyFeedback(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t flags, int32_t keyCode,
            int32_t scanCode, int32_t metaState, nsecs_t downTime) = 0;

    virtual int32_t interceptKey(nsecs_t when, int32_t deviceId,
            bool down, int32_t keyCode, int32_t scanCode, uint32_t policyFlags) = 0;

    virtual int32_t interceptTrackball(nsecs_t when, bool buttonChanged, bool buttonDown,
            bool rolled) = 0;

    virtual int32_t interceptTouch(nsecs_t when) = 0;

    virtual bool allowKeyRepeat() = 0;
    virtual nsecs_t getKeyRepeatTimeout() = 0;

    virtual void getKeyEventTargets(KeyEvent* keyEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets) = 0;
    virtual void getMotionEventTargets(MotionEvent* motionEvent, uint32_t policyFlags,
            Vector<InputTarget>& outTargets) = 0;

    /* Determine whether to turn on some hacks we have to improve the touch interaction with a
     * certain device whose screen currently is not all that good.
     */
    virtual bool filterTouchEvents() = 0;

    /* Determine whether to turn on some hacks to improve touch interaction with another device
     * where touch coordinate data can get corrupted.
     */
    virtual bool filterJumpyTouchEvents() = 0;

    virtual void getVirtualKeyDefinitions(const String8& deviceName,
            Vector<VirtualKeyDefinition>& outVirtualKeyDefinitions) = 0;
    virtual void getExcludedDeviceNames(Vector<String8>& outExcludedDeviceNames) = 0;
};

} // namespace android

#endif // _UI_INPUT_DISPATCH_POLICY_H
