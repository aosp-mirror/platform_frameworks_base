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

#ifndef _UI_INPUT_LISTENER_H
#define _UI_INPUT_LISTENER_H

#include <ui/Input.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>

namespace android {

class InputListenerInterface;


/* Superclass of all input event argument objects */
struct NotifyArgs {
    virtual ~NotifyArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const = 0;
};


/* Describes a configuration change event. */
struct NotifyConfigurationChangedArgs : public NotifyArgs {
    nsecs_t eventTime;

    inline NotifyConfigurationChangedArgs() { }

    NotifyConfigurationChangedArgs(nsecs_t eventTime);

    NotifyConfigurationChangedArgs(const NotifyConfigurationChangedArgs& other);

    virtual ~NotifyConfigurationChangedArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const;
};


/* Describes a key event. */
struct NotifyKeyArgs : public NotifyArgs {
    nsecs_t eventTime;
    int32_t deviceId;
    uint32_t source;
    uint32_t policyFlags;
    int32_t action;
    int32_t flags;
    int32_t keyCode;
    int32_t scanCode;
    int32_t metaState;
    nsecs_t downTime;

    inline NotifyKeyArgs() { }

    NotifyKeyArgs(nsecs_t eventTime, int32_t deviceId, uint32_t source, uint32_t policyFlags,
            int32_t action, int32_t flags, int32_t keyCode, int32_t scanCode,
            int32_t metaState, nsecs_t downTime);

    NotifyKeyArgs(const NotifyKeyArgs& other);

    virtual ~NotifyKeyArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const;
};


/* Describes a motion event. */
struct NotifyMotionArgs : public NotifyArgs {
    nsecs_t eventTime;
    int32_t deviceId;
    uint32_t source;
    uint32_t policyFlags;
    int32_t action;
    int32_t flags;
    int32_t metaState;
    int32_t buttonState;
    int32_t edgeFlags;
    uint32_t pointerCount;
    PointerProperties pointerProperties[MAX_POINTERS];
    PointerCoords pointerCoords[MAX_POINTERS];
    float xPrecision;
    float yPrecision;
    nsecs_t downTime;

    inline NotifyMotionArgs() { }

    NotifyMotionArgs(nsecs_t eventTime, int32_t deviceId, uint32_t source, uint32_t policyFlags,
            int32_t action, int32_t flags, int32_t metaState, int32_t buttonState,
            int32_t edgeFlags, uint32_t pointerCount,
            const PointerProperties* pointerProperties, const PointerCoords* pointerCoords,
            float xPrecision, float yPrecision, nsecs_t downTime);

    NotifyMotionArgs(const NotifyMotionArgs& other);

    virtual ~NotifyMotionArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const;
};


/* Describes a switch event. */
struct NotifySwitchArgs : public NotifyArgs {
    nsecs_t eventTime;
    uint32_t policyFlags;
    int32_t switchCode;
    int32_t switchValue;

    inline NotifySwitchArgs() { }

    NotifySwitchArgs(nsecs_t eventTime, uint32_t policyFlags,
            int32_t switchCode, int32_t switchValue);

    NotifySwitchArgs(const NotifySwitchArgs& other);

    virtual ~NotifySwitchArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const;
};


/* Describes a device reset event, such as when a device is added,
 * reconfigured, or removed. */
struct NotifyDeviceResetArgs : public NotifyArgs {
    nsecs_t eventTime;
    int32_t deviceId;

    inline NotifyDeviceResetArgs() { }

    NotifyDeviceResetArgs(nsecs_t eventTime, int32_t deviceId);

    NotifyDeviceResetArgs(const NotifyDeviceResetArgs& other);

    virtual ~NotifyDeviceResetArgs() { }

    virtual void notify(const sp<InputListenerInterface>& listener) const;
};


/*
 * The interface used by the InputReader to notify the InputListener about input events.
 */
class InputListenerInterface : public virtual RefBase {
protected:
    InputListenerInterface() { }
    virtual ~InputListenerInterface() { }

public:
    virtual void notifyConfigurationChanged(const NotifyConfigurationChangedArgs* args) = 0;
    virtual void notifyKey(const NotifyKeyArgs* args) = 0;
    virtual void notifyMotion(const NotifyMotionArgs* args) = 0;
    virtual void notifySwitch(const NotifySwitchArgs* args) = 0;
    virtual void notifyDeviceReset(const NotifyDeviceResetArgs* args) = 0;
};


/*
 * An implementation of the listener interface that queues up and defers dispatch
 * of decoded events until flushed.
 */
class QueuedInputListener : public InputListenerInterface {
protected:
    virtual ~QueuedInputListener();

public:
    QueuedInputListener(const sp<InputListenerInterface>& innerListener);

    virtual void notifyConfigurationChanged(const NotifyConfigurationChangedArgs* args);
    virtual void notifyKey(const NotifyKeyArgs* args);
    virtual void notifyMotion(const NotifyMotionArgs* args);
    virtual void notifySwitch(const NotifySwitchArgs* args);
    virtual void notifyDeviceReset(const NotifyDeviceResetArgs* args);

    void flush();

private:
    sp<InputListenerInterface> mInnerListener;
    Vector<NotifyArgs*> mArgsQueue;
};

} // namespace android

#endif // _UI_INPUT_LISTENER_H
