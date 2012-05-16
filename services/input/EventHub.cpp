/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "EventHub"

// #define LOG_NDEBUG 0

#include "EventHub.h"

#include <hardware_legacy/power.h>

#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/Timers.h>
#include <utils/threads.h>
#include <utils/Errors.h>

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <memory.h>
#include <errno.h>
#include <assert.h>

#include <androidfw/KeyLayoutMap.h>
#include <androidfw/KeyCharacterMap.h>
#include <androidfw/VirtualKeyMap.h>

#include <sha1.h>
#include <string.h>
#include <stdint.h>
#include <dirent.h>

#include <sys/inotify.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/limits.h>

/* this macro is used to tell if "bit" is set in "array"
 * it selects a byte from the array, and does a boolean AND
 * operation with a byte that only has the relevant bit set.
 * eg. to check for the 12th bit, we do (array[1] & 1<<4)
 */
#define test_bit(bit, array)    (array[bit/8] & (1<<(bit%8)))

/* this macro computes the number of bytes needed to represent a bit array of the specified size */
#define sizeof_bit_array(bits)  ((bits + 7) / 8)

#define INDENT "  "
#define INDENT2 "    "
#define INDENT3 "      "

namespace android {

static const char *WAKE_LOCK_ID = "KeyEvents";
static const char *DEVICE_PATH = "/dev/input";

/* return the larger integer */
static inline int max(int v1, int v2)
{
    return (v1 > v2) ? v1 : v2;
}

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

static String8 sha1(const String8& in) {
    SHA1_CTX ctx;
    SHA1Init(&ctx);
    SHA1Update(&ctx, reinterpret_cast<const u_char*>(in.string()), in.size());
    u_char digest[SHA1_DIGEST_LENGTH];
    SHA1Final(digest, &ctx);

    String8 out;
    for (size_t i = 0; i < SHA1_DIGEST_LENGTH; i++) {
        out.appendFormat("%02x", digest[i]);
    }
    return out;
}

static void setDescriptor(InputDeviceIdentifier& identifier) {
    // Compute a device descriptor that uniquely identifies the device.
    // The descriptor is assumed to be a stable identifier.  Its value should not
    // change between reboots, reconnections, firmware updates or new releases of Android.
    // Ideally, we also want the descriptor to be short and relatively opaque.
    String8 rawDescriptor;
    rawDescriptor.appendFormat(":%04x:%04x:", identifier.vendor, identifier.product);
    if (!identifier.uniqueId.isEmpty()) {
        rawDescriptor.append("uniqueId:");
        rawDescriptor.append(identifier.uniqueId);
    } if (identifier.vendor == 0 && identifier.product == 0) {
        // If we don't know the vendor and product id, then the device is probably
        // built-in so we need to rely on other information to uniquely identify
        // the input device.  Usually we try to avoid relying on the device name or
        // location but for built-in input device, they are unlikely to ever change.
        if (!identifier.name.isEmpty()) {
            rawDescriptor.append("name:");
            rawDescriptor.append(identifier.name);
        } else if (!identifier.location.isEmpty()) {
            rawDescriptor.append("location:");
            rawDescriptor.append(identifier.location);
        }
    }
    identifier.descriptor = sha1(rawDescriptor);
    ALOGV("Created descriptor: raw=%s, cooked=%s", rawDescriptor.string(),
            identifier.descriptor.string());
}

// --- Global Functions ---

uint32_t getAbsAxisUsage(int32_t axis, uint32_t deviceClasses) {
    // Touch devices get dibs on touch-related axes.
    if (deviceClasses & INPUT_DEVICE_CLASS_TOUCH) {
        switch (axis) {
        case ABS_X:
        case ABS_Y:
        case ABS_PRESSURE:
        case ABS_TOOL_WIDTH:
        case ABS_DISTANCE:
        case ABS_TILT_X:
        case ABS_TILT_Y:
        case ABS_MT_SLOT:
        case ABS_MT_TOUCH_MAJOR:
        case ABS_MT_TOUCH_MINOR:
        case ABS_MT_WIDTH_MAJOR:
        case ABS_MT_WIDTH_MINOR:
        case ABS_MT_ORIENTATION:
        case ABS_MT_POSITION_X:
        case ABS_MT_POSITION_Y:
        case ABS_MT_TOOL_TYPE:
        case ABS_MT_BLOB_ID:
        case ABS_MT_TRACKING_ID:
        case ABS_MT_PRESSURE:
        case ABS_MT_DISTANCE:
            return INPUT_DEVICE_CLASS_TOUCH;
        }
    }

    // Joystick devices get the rest.
    return deviceClasses & INPUT_DEVICE_CLASS_JOYSTICK;
}

// --- EventHub::Device ---

EventHub::Device::Device(int fd, int32_t id, const String8& path,
        const InputDeviceIdentifier& identifier) :
        next(NULL),
        fd(fd), id(id), path(path), identifier(identifier),
        classes(0), configuration(NULL), virtualKeyMap(NULL),
        ffEffectPlaying(false), ffEffectId(-1) {
    memset(keyBitmask, 0, sizeof(keyBitmask));
    memset(absBitmask, 0, sizeof(absBitmask));
    memset(relBitmask, 0, sizeof(relBitmask));
    memset(swBitmask, 0, sizeof(swBitmask));
    memset(ledBitmask, 0, sizeof(ledBitmask));
    memset(ffBitmask, 0, sizeof(ffBitmask));
    memset(propBitmask, 0, sizeof(propBitmask));
}

EventHub::Device::~Device() {
    close();
    delete configuration;
    delete virtualKeyMap;
}

void EventHub::Device::close() {
    if (fd >= 0) {
        ::close(fd);
        fd = -1;
    }
}


// --- EventHub ---

const uint32_t EventHub::EPOLL_ID_INOTIFY;
const uint32_t EventHub::EPOLL_ID_WAKE;
const int EventHub::EPOLL_SIZE_HINT;
const int EventHub::EPOLL_MAX_EVENTS;

EventHub::EventHub(void) :
        mBuiltInKeyboardId(NO_BUILT_IN_KEYBOARD), mNextDeviceId(1),
        mOpeningDevices(0), mClosingDevices(0),
        mNeedToSendFinishedDeviceScan(false),
        mNeedToReopenDevices(false), mNeedToScanDevices(true),
        mPendingEventCount(0), mPendingEventIndex(0), mPendingINotify(false) {
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);

    mEpollFd = epoll_create(EPOLL_SIZE_HINT);
    LOG_ALWAYS_FATAL_IF(mEpollFd < 0, "Could not create epoll instance.  errno=%d", errno);

    mINotifyFd = inotify_init();
    int result = inotify_add_watch(mINotifyFd, DEVICE_PATH, IN_DELETE | IN_CREATE);
    LOG_ALWAYS_FATAL_IF(result < 0, "Could not register INotify for %s.  errno=%d",
            DEVICE_PATH, errno);

    struct epoll_event eventItem;
    memset(&eventItem, 0, sizeof(eventItem));
    eventItem.events = EPOLLIN;
    eventItem.data.u32 = EPOLL_ID_INOTIFY;
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mINotifyFd, &eventItem);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not add INotify to epoll instance.  errno=%d", errno);

    int wakeFds[2];
    result = pipe(wakeFds);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not create wake pipe.  errno=%d", errno);

    mWakeReadPipeFd = wakeFds[0];
    mWakeWritePipeFd = wakeFds[1];

    result = fcntl(mWakeReadPipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not make wake read pipe non-blocking.  errno=%d",
            errno);

    result = fcntl(mWakeWritePipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not make wake write pipe non-blocking.  errno=%d",
            errno);

    eventItem.data.u32 = EPOLL_ID_WAKE;
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeReadPipeFd, &eventItem);
    LOG_ALWAYS_FATAL_IF(result != 0, "Could not add wake read pipe to epoll instance.  errno=%d",
            errno);
}

EventHub::~EventHub(void) {
    closeAllDevicesLocked();

    while (mClosingDevices) {
        Device* device = mClosingDevices;
        mClosingDevices = device->next;
        delete device;
    }

    ::close(mEpollFd);
    ::close(mINotifyFd);
    ::close(mWakeReadPipeFd);
    ::close(mWakeWritePipeFd);

    release_wake_lock(WAKE_LOCK_ID);
}

InputDeviceIdentifier EventHub::getDeviceIdentifier(int32_t deviceId) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device == NULL) return InputDeviceIdentifier();
    return device->identifier;
}

uint32_t EventHub::getDeviceClasses(int32_t deviceId) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device == NULL) return 0;
    return device->classes;
}

void EventHub::getConfiguration(int32_t deviceId, PropertyMap* outConfiguration) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && device->configuration) {
        *outConfiguration = *device->configuration;
    } else {
        outConfiguration->clear();
    }
}

status_t EventHub::getAbsoluteAxisInfo(int32_t deviceId, int axis,
        RawAbsoluteAxisInfo* outAxisInfo) const {
    outAxisInfo->clear();

    if (axis >= 0 && axis <= ABS_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device && !device->isVirtual() && test_bit(axis, device->absBitmask)) {
            struct input_absinfo info;
            if(ioctl(device->fd, EVIOCGABS(axis), &info)) {
                ALOGW("Error reading absolute controller %d for device %s fd %d, errno=%d",
                     axis, device->identifier.name.string(), device->fd, errno);
                return -errno;
            }

            if (info.minimum != info.maximum) {
                outAxisInfo->valid = true;
                outAxisInfo->minValue = info.minimum;
                outAxisInfo->maxValue = info.maximum;
                outAxisInfo->flat = info.flat;
                outAxisInfo->fuzz = info.fuzz;
                outAxisInfo->resolution = info.resolution;
            }
            return OK;
        }
    }
    return -1;
}

bool EventHub::hasRelativeAxis(int32_t deviceId, int axis) const {
    if (axis >= 0 && axis <= REL_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device) {
            return test_bit(axis, device->relBitmask);
        }
    }
    return false;
}

bool EventHub::hasInputProperty(int32_t deviceId, int property) const {
    if (property >= 0 && property <= INPUT_PROP_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device) {
            return test_bit(property, device->propBitmask);
        }
    }
    return false;
}

int32_t EventHub::getScanCodeState(int32_t deviceId, int32_t scanCode) const {
    if (scanCode >= 0 && scanCode <= KEY_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device && !device->isVirtual() && test_bit(scanCode, device->keyBitmask)) {
            uint8_t keyState[sizeof_bit_array(KEY_MAX + 1)];
            memset(keyState, 0, sizeof(keyState));
            if (ioctl(device->fd, EVIOCGKEY(sizeof(keyState)), keyState) >= 0) {
                return test_bit(scanCode, keyState) ? AKEY_STATE_DOWN : AKEY_STATE_UP;
            }
        }
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getKeyCodeState(int32_t deviceId, int32_t keyCode) const {
    AutoMutex _l(mLock);

    Device* device = getDeviceLocked(deviceId);
    if (device && !device->isVirtual() && device->keyMap.haveKeyLayout()) {
        Vector<int32_t> scanCodes;
        device->keyMap.keyLayoutMap->findScanCodesForKey(keyCode, &scanCodes);
        if (scanCodes.size() != 0) {
            uint8_t keyState[sizeof_bit_array(KEY_MAX + 1)];
            memset(keyState, 0, sizeof(keyState));
            if (ioctl(device->fd, EVIOCGKEY(sizeof(keyState)), keyState) >= 0) {
                for (size_t i = 0; i < scanCodes.size(); i++) {
                    int32_t sc = scanCodes.itemAt(i);
                    if (sc >= 0 && sc <= KEY_MAX && test_bit(sc, keyState)) {
                        return AKEY_STATE_DOWN;
                    }
                }
                return AKEY_STATE_UP;
            }
        }
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getSwitchState(int32_t deviceId, int32_t sw) const {
    if (sw >= 0 && sw <= SW_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device && !device->isVirtual() && test_bit(sw, device->swBitmask)) {
            uint8_t swState[sizeof_bit_array(SW_MAX + 1)];
            memset(swState, 0, sizeof(swState));
            if (ioctl(device->fd, EVIOCGSW(sizeof(swState)), swState) >= 0) {
                return test_bit(sw, swState) ? AKEY_STATE_DOWN : AKEY_STATE_UP;
            }
        }
    }
    return AKEY_STATE_UNKNOWN;
}

status_t EventHub::getAbsoluteAxisValue(int32_t deviceId, int32_t axis, int32_t* outValue) const {
    *outValue = 0;

    if (axis >= 0 && axis <= ABS_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device && !device->isVirtual() && test_bit(axis, device->absBitmask)) {
            struct input_absinfo info;
            if(ioctl(device->fd, EVIOCGABS(axis), &info)) {
                ALOGW("Error reading absolute controller %d for device %s fd %d, errno=%d",
                     axis, device->identifier.name.string(), device->fd, errno);
                return -errno;
            }

            *outValue = info.value;
            return OK;
        }
    }
    return -1;
}

bool EventHub::markSupportedKeyCodes(int32_t deviceId, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) const {
    AutoMutex _l(mLock);

    Device* device = getDeviceLocked(deviceId);
    if (device && device->keyMap.haveKeyLayout()) {
        Vector<int32_t> scanCodes;
        for (size_t codeIndex = 0; codeIndex < numCodes; codeIndex++) {
            scanCodes.clear();

            status_t err = device->keyMap.keyLayoutMap->findScanCodesForKey(
                    keyCodes[codeIndex], &scanCodes);
            if (! err) {
                // check the possible scan codes identified by the layout map against the
                // map of codes actually emitted by the driver
                for (size_t sc = 0; sc < scanCodes.size(); sc++) {
                    if (test_bit(scanCodes[sc], device->keyBitmask)) {
                        outFlags[codeIndex] = 1;
                        break;
                    }
                }
            }
        }
        return true;
    }
    return false;
}

status_t EventHub::mapKey(int32_t deviceId, int32_t scanCode, int32_t usageCode,
        int32_t* outKeycode, uint32_t* outFlags) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);

    if (device) {
        // Check the key character map first.
        sp<KeyCharacterMap> kcm = device->getKeyCharacterMap();
        if (kcm != NULL) {
            if (!kcm->mapKey(scanCode, usageCode, outKeycode)) {
                *outFlags = 0;
                return NO_ERROR;
            }
        }

        // Check the key layout next.
        if (device->keyMap.haveKeyLayout()) {
            if (!device->keyMap.keyLayoutMap->mapKey(
                    scanCode, usageCode, outKeycode, outFlags)) {
                return NO_ERROR;
            }
        }
    }

    *outKeycode = 0;
    *outFlags = 0;
    return NAME_NOT_FOUND;
}

status_t EventHub::mapAxis(int32_t deviceId, int32_t scanCode, AxisInfo* outAxisInfo) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);

    if (device && device->keyMap.haveKeyLayout()) {
        status_t err = device->keyMap.keyLayoutMap->mapAxis(scanCode, outAxisInfo);
        if (err == NO_ERROR) {
            return NO_ERROR;
        }
    }

    return NAME_NOT_FOUND;
}

void EventHub::setExcludedDevices(const Vector<String8>& devices) {
    AutoMutex _l(mLock);

    mExcludedDevices = devices;
}

bool EventHub::hasScanCode(int32_t deviceId, int32_t scanCode) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && scanCode >= 0 && scanCode <= KEY_MAX) {
        if (test_bit(scanCode, device->keyBitmask)) {
            return true;
        }
    }
    return false;
}

bool EventHub::hasLed(int32_t deviceId, int32_t led) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && led >= 0 && led <= LED_MAX) {
        if (test_bit(led, device->ledBitmask)) {
            return true;
        }
    }
    return false;
}

void EventHub::setLedState(int32_t deviceId, int32_t led, bool on) {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && !device->isVirtual() && led >= 0 && led <= LED_MAX) {
        struct input_event ev;
        ev.time.tv_sec = 0;
        ev.time.tv_usec = 0;
        ev.type = EV_LED;
        ev.code = led;
        ev.value = on ? 1 : 0;

        ssize_t nWrite;
        do {
            nWrite = write(device->fd, &ev, sizeof(struct input_event));
        } while (nWrite == -1 && errno == EINTR);
    }
}

void EventHub::getVirtualKeyDefinitions(int32_t deviceId,
        Vector<VirtualKeyDefinition>& outVirtualKeys) const {
    outVirtualKeys.clear();

    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && device->virtualKeyMap) {
        outVirtualKeys.appendVector(device->virtualKeyMap->getVirtualKeys());
    }
}

sp<KeyCharacterMap> EventHub::getKeyCharacterMap(int32_t deviceId) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device) {
        return device->getKeyCharacterMap();
    }
    return NULL;
}

bool EventHub::setKeyboardLayoutOverlay(int32_t deviceId,
        const sp<KeyCharacterMap>& map) {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device) {
        if (map != device->overlayKeyMap) {
            device->overlayKeyMap = map;
            device->combinedKeyMap = KeyCharacterMap::combine(
                    device->keyMap.keyCharacterMap, map);
            return true;
        }
    }
    return false;
}

void EventHub::vibrate(int32_t deviceId, nsecs_t duration) {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && !device->isVirtual()) {
        ff_effect effect;
        memset(&effect, 0, sizeof(effect));
        effect.type = FF_RUMBLE;
        effect.id = device->ffEffectId;
        effect.u.rumble.strong_magnitude = 0xc000;
        effect.u.rumble.weak_magnitude = 0xc000;
        effect.replay.length = (duration + 999999LL) / 1000000LL;
        effect.replay.delay = 0;
        if (ioctl(device->fd, EVIOCSFF, &effect)) {
            ALOGW("Could not upload force feedback effect to device %s due to error %d.",
                    device->identifier.name.string(), errno);
            return;
        }
        device->ffEffectId = effect.id;

        struct input_event ev;
        ev.time.tv_sec = 0;
        ev.time.tv_usec = 0;
        ev.type = EV_FF;
        ev.code = device->ffEffectId;
        ev.value = 1;
        if (write(device->fd, &ev, sizeof(ev)) != sizeof(ev)) {
            ALOGW("Could not start force feedback effect on device %s due to error %d.",
                    device->identifier.name.string(), errno);
            return;
        }
        device->ffEffectPlaying = true;
    }
}

void EventHub::cancelVibrate(int32_t deviceId) {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device && !device->isVirtual()) {
        if (device->ffEffectPlaying) {
            device->ffEffectPlaying = false;

            struct input_event ev;
            ev.time.tv_sec = 0;
            ev.time.tv_usec = 0;
            ev.type = EV_FF;
            ev.code = device->ffEffectId;
            ev.value = 0;
            if (write(device->fd, &ev, sizeof(ev)) != sizeof(ev)) {
                ALOGW("Could not stop force feedback effect on device %s due to error %d.",
                        device->identifier.name.string(), errno);
                return;
            }
        }
    }
}

EventHub::Device* EventHub::getDeviceLocked(int32_t deviceId) const {
    if (deviceId == BUILT_IN_KEYBOARD_ID) {
        deviceId = mBuiltInKeyboardId;
    }
    ssize_t index = mDevices.indexOfKey(deviceId);
    return index >= 0 ? mDevices.valueAt(index) : NULL;
}

EventHub::Device* EventHub::getDeviceByPathLocked(const char* devicePath) const {
    for (size_t i = 0; i < mDevices.size(); i++) {
        Device* device = mDevices.valueAt(i);
        if (device->path == devicePath) {
            return device;
        }
    }
    return NULL;
}

size_t EventHub::getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize) {
    ALOG_ASSERT(bufferSize >= 1);

    AutoMutex _l(mLock);

    struct input_event readBuffer[bufferSize];

    RawEvent* event = buffer;
    size_t capacity = bufferSize;
    bool awoken = false;
    for (;;) {
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);

        // Reopen input devices if needed.
        if (mNeedToReopenDevices) {
            mNeedToReopenDevices = false;

            ALOGI("Reopening all input devices due to a configuration change.");

            closeAllDevicesLocked();
            mNeedToScanDevices = true;
            break; // return to the caller before we actually rescan
        }

        // Report any devices that had last been added/removed.
        while (mClosingDevices) {
            Device* device = mClosingDevices;
            ALOGV("Reporting device closed: id=%d, name=%s\n",
                 device->id, device->path.string());
            mClosingDevices = device->next;
            event->when = now;
            event->deviceId = device->id == mBuiltInKeyboardId ? BUILT_IN_KEYBOARD_ID : device->id;
            event->type = DEVICE_REMOVED;
            event += 1;
            delete device;
            mNeedToSendFinishedDeviceScan = true;
            if (--capacity == 0) {
                break;
            }
        }

        if (mNeedToScanDevices) {
            mNeedToScanDevices = false;
            scanDevicesLocked();
            mNeedToSendFinishedDeviceScan = true;
        }

        while (mOpeningDevices != NULL) {
            Device* device = mOpeningDevices;
            ALOGV("Reporting device opened: id=%d, name=%s\n",
                 device->id, device->path.string());
            mOpeningDevices = device->next;
            event->when = now;
            event->deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;
            event->type = DEVICE_ADDED;
            event += 1;
            mNeedToSendFinishedDeviceScan = true;
            if (--capacity == 0) {
                break;
            }
        }

        if (mNeedToSendFinishedDeviceScan) {
            mNeedToSendFinishedDeviceScan = false;
            event->when = now;
            event->type = FINISHED_DEVICE_SCAN;
            event += 1;
            if (--capacity == 0) {
                break;
            }
        }

        // Grab the next input event.
        bool deviceChanged = false;
        while (mPendingEventIndex < mPendingEventCount) {
            const struct epoll_event& eventItem = mPendingEventItems[mPendingEventIndex++];
            if (eventItem.data.u32 == EPOLL_ID_INOTIFY) {
                if (eventItem.events & EPOLLIN) {
                    mPendingINotify = true;
                } else {
                    ALOGW("Received unexpected epoll event 0x%08x for INotify.", eventItem.events);
                }
                continue;
            }

            if (eventItem.data.u32 == EPOLL_ID_WAKE) {
                if (eventItem.events & EPOLLIN) {
                    ALOGV("awoken after wake()");
                    awoken = true;
                    char buffer[16];
                    ssize_t nRead;
                    do {
                        nRead = read(mWakeReadPipeFd, buffer, sizeof(buffer));
                    } while ((nRead == -1 && errno == EINTR) || nRead == sizeof(buffer));
                } else {
                    ALOGW("Received unexpected epoll event 0x%08x for wake read pipe.",
                            eventItem.events);
                }
                continue;
            }

            ssize_t deviceIndex = mDevices.indexOfKey(eventItem.data.u32);
            if (deviceIndex < 0) {
                ALOGW("Received unexpected epoll event 0x%08x for unknown device id %d.",
                        eventItem.events, eventItem.data.u32);
                continue;
            }

            Device* device = mDevices.valueAt(deviceIndex);
            if (eventItem.events & EPOLLIN) {
                int32_t readSize = read(device->fd, readBuffer,
                        sizeof(struct input_event) * capacity);
                if (readSize == 0 || (readSize < 0 && errno == ENODEV)) {
                    // Device was removed before INotify noticed.
                    ALOGW("could not get event, removed? (fd: %d size: %d bufferSize: %d "
                            "capacity: %d errno: %d)\n",
                            device->fd, readSize, bufferSize, capacity, errno);
                    deviceChanged = true;
                    closeDeviceLocked(device);
                } else if (readSize < 0) {
                    if (errno != EAGAIN && errno != EINTR) {
                        ALOGW("could not get event (errno=%d)", errno);
                    }
                } else if ((readSize % sizeof(struct input_event)) != 0) {
                    ALOGE("could not get event (wrong size: %d)", readSize);
                } else {
                    int32_t deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;

                    size_t count = size_t(readSize) / sizeof(struct input_event);
                    for (size_t i = 0; i < count; i++) {
                        const struct input_event& iev = readBuffer[i];
                        ALOGV("%s got: t0=%d, t1=%d, type=%d, code=%d, value=%d",
                                device->path.string(),
                                (int) iev.time.tv_sec, (int) iev.time.tv_usec,
                                iev.type, iev.code, iev.value);

#ifdef HAVE_POSIX_CLOCKS
                        // Use the time specified in the event instead of the current time
                        // so that downstream code can get more accurate estimates of
                        // event dispatch latency from the time the event is enqueued onto
                        // the evdev client buffer.
                        //
                        // The event's timestamp fortuitously uses the same monotonic clock
                        // time base as the rest of Android.  The kernel event device driver
                        // (drivers/input/evdev.c) obtains timestamps using ktime_get_ts().
                        // The systemTime(SYSTEM_TIME_MONOTONIC) function we use everywhere
                        // calls clock_gettime(CLOCK_MONOTONIC) which is implemented as a
                        // system call that also queries ktime_get_ts().
                        event->when = nsecs_t(iev.time.tv_sec) * 1000000000LL
                                + nsecs_t(iev.time.tv_usec) * 1000LL;
                        ALOGV("event time %lld, now %lld", event->when, now);
#else
                        event->when = now;
#endif
                        event->deviceId = deviceId;
                        event->type = iev.type;
                        event->code = iev.code;
                        event->value = iev.value;
                        event += 1;
                    }
                    capacity -= count;
                    if (capacity == 0) {
                        // The result buffer is full.  Reset the pending event index
                        // so we will try to read the device again on the next iteration.
                        mPendingEventIndex -= 1;
                        break;
                    }
                }
            } else if (eventItem.events & EPOLLHUP) {
                ALOGI("Removing device %s due to epoll hang-up event.",
                        device->identifier.name.string());
                deviceChanged = true;
                closeDeviceLocked(device);
            } else {
                ALOGW("Received unexpected epoll event 0x%08x for device %s.",
                        eventItem.events, device->identifier.name.string());
            }
        }

        // readNotify() will modify the list of devices so this must be done after
        // processing all other events to ensure that we read all remaining events
        // before closing the devices.
        if (mPendingINotify && mPendingEventIndex >= mPendingEventCount) {
            mPendingINotify = false;
            readNotifyLocked();
            deviceChanged = true;
        }

        // Report added or removed devices immediately.
        if (deviceChanged) {
            continue;
        }

        // Return now if we have collected any events or if we were explicitly awoken.
        if (event != buffer || awoken) {
            break;
        }

        // Poll for events.  Mind the wake lock dance!
        // We hold a wake lock at all times except during epoll_wait().  This works due to some
        // subtle choreography.  When a device driver has pending (unread) events, it acquires
        // a kernel wake lock.  However, once the last pending event has been read, the device
        // driver will release the kernel wake lock.  To prevent the system from going to sleep
        // when this happens, the EventHub holds onto its own user wake lock while the client
        // is processing events.  Thus the system can only sleep if there are no events
        // pending or currently being processed.
        //
        // The timeout is advisory only.  If the device is asleep, it will not wake just to
        // service the timeout.
        mPendingEventIndex = 0;

        mLock.unlock(); // release lock before poll, must be before release_wake_lock
        release_wake_lock(WAKE_LOCK_ID);

        int pollResult = epoll_wait(mEpollFd, mPendingEventItems, EPOLL_MAX_EVENTS, timeoutMillis);

        acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);
        mLock.lock(); // reacquire lock after poll, must be after acquire_wake_lock

        if (pollResult == 0) {
            // Timed out.
            mPendingEventCount = 0;
            break;
        }

        if (pollResult < 0) {
            // An error occurred.
            mPendingEventCount = 0;

            // Sleep after errors to avoid locking up the system.
            // Hopefully the error is transient.
            if (errno != EINTR) {
                ALOGW("poll failed (errno=%d)\n", errno);
                usleep(100000);
            }
        } else {
            // Some events occurred.
            mPendingEventCount = size_t(pollResult);
        }
    }

    // All done, return the number of events we read.
    return event - buffer;
}

void EventHub::wake() {
    ALOGV("wake() called");

    ssize_t nWrite;
    do {
        nWrite = write(mWakeWritePipeFd, "W", 1);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite != 1 && errno != EAGAIN) {
        ALOGW("Could not write wake signal, errno=%d", errno);
    }
}

void EventHub::scanDevicesLocked() {
    status_t res = scanDirLocked(DEVICE_PATH);
    if(res < 0) {
        ALOGE("scan dir failed for %s\n", DEVICE_PATH);
    }
    if (mDevices.indexOfKey(VIRTUAL_KEYBOARD_ID) < 0) {
        createVirtualKeyboardLocked();
    }
}

// ----------------------------------------------------------------------------

static bool containsNonZeroByte(const uint8_t* array, uint32_t startIndex, uint32_t endIndex) {
    const uint8_t* end = array + endIndex;
    array += startIndex;
    while (array != end) {
        if (*(array++) != 0) {
            return true;
        }
    }
    return false;
}

static const int32_t GAMEPAD_KEYCODES[] = {
        AKEYCODE_BUTTON_A, AKEYCODE_BUTTON_B, AKEYCODE_BUTTON_C,
        AKEYCODE_BUTTON_X, AKEYCODE_BUTTON_Y, AKEYCODE_BUTTON_Z,
        AKEYCODE_BUTTON_L1, AKEYCODE_BUTTON_R1,
        AKEYCODE_BUTTON_L2, AKEYCODE_BUTTON_R2,
        AKEYCODE_BUTTON_THUMBL, AKEYCODE_BUTTON_THUMBR,
        AKEYCODE_BUTTON_START, AKEYCODE_BUTTON_SELECT, AKEYCODE_BUTTON_MODE,
        AKEYCODE_BUTTON_1, AKEYCODE_BUTTON_2, AKEYCODE_BUTTON_3, AKEYCODE_BUTTON_4,
        AKEYCODE_BUTTON_5, AKEYCODE_BUTTON_6, AKEYCODE_BUTTON_7, AKEYCODE_BUTTON_8,
        AKEYCODE_BUTTON_9, AKEYCODE_BUTTON_10, AKEYCODE_BUTTON_11, AKEYCODE_BUTTON_12,
        AKEYCODE_BUTTON_13, AKEYCODE_BUTTON_14, AKEYCODE_BUTTON_15, AKEYCODE_BUTTON_16,
};

status_t EventHub::openDeviceLocked(const char *devicePath) {
    char buffer[80];

    ALOGV("Opening device: %s", devicePath);

    int fd = open(devicePath, O_RDWR | O_CLOEXEC);
    if(fd < 0) {
        ALOGE("could not open %s, %s\n", devicePath, strerror(errno));
        return -1;
    }

    InputDeviceIdentifier identifier;

    // Get device name.
    if(ioctl(fd, EVIOCGNAME(sizeof(buffer) - 1), &buffer) < 1) {
        //fprintf(stderr, "could not get device name for %s, %s\n", devicePath, strerror(errno));
    } else {
        buffer[sizeof(buffer) - 1] = '\0';
        identifier.name.setTo(buffer);
    }

    // Check to see if the device is on our excluded list
    for (size_t i = 0; i < mExcludedDevices.size(); i++) {
        const String8& item = mExcludedDevices.itemAt(i);
        if (identifier.name == item) {
            ALOGI("ignoring event id %s driver %s\n", devicePath, item.string());
            close(fd);
            return -1;
        }
    }

    // Get device driver version.
    int driverVersion;
    if(ioctl(fd, EVIOCGVERSION, &driverVersion)) {
        ALOGE("could not get driver version for %s, %s\n", devicePath, strerror(errno));
        close(fd);
        return -1;
    }

    // Get device identifier.
    struct input_id inputId;
    if(ioctl(fd, EVIOCGID, &inputId)) {
        ALOGE("could not get device input id for %s, %s\n", devicePath, strerror(errno));
        close(fd);
        return -1;
    }
    identifier.bus = inputId.bustype;
    identifier.product = inputId.product;
    identifier.vendor = inputId.vendor;
    identifier.version = inputId.version;

    // Get device physical location.
    if(ioctl(fd, EVIOCGPHYS(sizeof(buffer) - 1), &buffer) < 1) {
        //fprintf(stderr, "could not get location for %s, %s\n", devicePath, strerror(errno));
    } else {
        buffer[sizeof(buffer) - 1] = '\0';
        identifier.location.setTo(buffer);
    }

    // Get device unique id.
    if(ioctl(fd, EVIOCGUNIQ(sizeof(buffer) - 1), &buffer) < 1) {
        //fprintf(stderr, "could not get idstring for %s, %s\n", devicePath, strerror(errno));
    } else {
        buffer[sizeof(buffer) - 1] = '\0';
        identifier.uniqueId.setTo(buffer);
    }

    // Fill in the descriptor.
    setDescriptor(identifier);

    // Make file descriptor non-blocking for use with poll().
    if (fcntl(fd, F_SETFL, O_NONBLOCK)) {
        ALOGE("Error %d making device file descriptor non-blocking.", errno);
        close(fd);
        return -1;
    }

    // Allocate device.  (The device object takes ownership of the fd at this point.)
    int32_t deviceId = mNextDeviceId++;
    Device* device = new Device(fd, deviceId, String8(devicePath), identifier);

    ALOGV("add device %d: %s\n", deviceId, devicePath);
    ALOGV("  bus:        %04x\n"
         "  vendor      %04x\n"
         "  product     %04x\n"
         "  version     %04x\n",
        identifier.bus, identifier.vendor, identifier.product, identifier.version);
    ALOGV("  name:       \"%s\"\n", identifier.name.string());
    ALOGV("  location:   \"%s\"\n", identifier.location.string());
    ALOGV("  unique id:  \"%s\"\n", identifier.uniqueId.string());
    ALOGV("  descriptor: \"%s\"\n", identifier.descriptor.string());
    ALOGV("  driver:     v%d.%d.%d\n",
        driverVersion >> 16, (driverVersion >> 8) & 0xff, driverVersion & 0xff);

    // Load the configuration file for the device.
    loadConfigurationLocked(device);

    // Figure out the kinds of events the device reports.
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(device->keyBitmask)), device->keyBitmask);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(device->absBitmask)), device->absBitmask);
    ioctl(fd, EVIOCGBIT(EV_REL, sizeof(device->relBitmask)), device->relBitmask);
    ioctl(fd, EVIOCGBIT(EV_SW, sizeof(device->swBitmask)), device->swBitmask);
    ioctl(fd, EVIOCGBIT(EV_LED, sizeof(device->ledBitmask)), device->ledBitmask);
    ioctl(fd, EVIOCGBIT(EV_FF, sizeof(device->ffBitmask)), device->ffBitmask);
    ioctl(fd, EVIOCGPROP(sizeof(device->propBitmask)), device->propBitmask);

    // See if this is a keyboard.  Ignore everything in the button range except for
    // joystick and gamepad buttons which are handled like keyboards for the most part.
    bool haveKeyboardKeys = containsNonZeroByte(device->keyBitmask, 0, sizeof_bit_array(BTN_MISC))
            || containsNonZeroByte(device->keyBitmask, sizeof_bit_array(KEY_OK),
                    sizeof_bit_array(KEY_MAX + 1));
    bool haveGamepadButtons = containsNonZeroByte(device->keyBitmask, sizeof_bit_array(BTN_MISC),
                    sizeof_bit_array(BTN_MOUSE))
            || containsNonZeroByte(device->keyBitmask, sizeof_bit_array(BTN_JOYSTICK),
                    sizeof_bit_array(BTN_DIGI));
    if (haveKeyboardKeys || haveGamepadButtons) {
        device->classes |= INPUT_DEVICE_CLASS_KEYBOARD;
    }

    // See if this is a cursor device such as a trackball or mouse.
    if (test_bit(BTN_MOUSE, device->keyBitmask)
            && test_bit(REL_X, device->relBitmask)
            && test_bit(REL_Y, device->relBitmask)) {
        device->classes |= INPUT_DEVICE_CLASS_CURSOR;
    }

    // See if this is a touch pad.
    // Is this a new modern multi-touch driver?
    if (test_bit(ABS_MT_POSITION_X, device->absBitmask)
            && test_bit(ABS_MT_POSITION_Y, device->absBitmask)) {
        // Some joysticks such as the PS3 controller report axes that conflict
        // with the ABS_MT range.  Try to confirm that the device really is
        // a touch screen.
        if (test_bit(BTN_TOUCH, device->keyBitmask) || !haveGamepadButtons) {
            device->classes |= INPUT_DEVICE_CLASS_TOUCH | INPUT_DEVICE_CLASS_TOUCH_MT;
        }
    // Is this an old style single-touch driver?
    } else if (test_bit(BTN_TOUCH, device->keyBitmask)
            && test_bit(ABS_X, device->absBitmask)
            && test_bit(ABS_Y, device->absBitmask)) {
        device->classes |= INPUT_DEVICE_CLASS_TOUCH;
    }

    // See if this device is a joystick.
    // Assumes that joysticks always have gamepad buttons in order to distinguish them
    // from other devices such as accelerometers that also have absolute axes.
    if (haveGamepadButtons) {
        uint32_t assumedClasses = device->classes | INPUT_DEVICE_CLASS_JOYSTICK;
        for (int i = 0; i <= ABS_MAX; i++) {
            if (test_bit(i, device->absBitmask)
                    && (getAbsAxisUsage(i, assumedClasses) & INPUT_DEVICE_CLASS_JOYSTICK)) {
                device->classes = assumedClasses;
                break;
            }
        }
    }

    // Check whether this device has switches.
    for (int i = 0; i <= SW_MAX; i++) {
        if (test_bit(i, device->swBitmask)) {
            device->classes |= INPUT_DEVICE_CLASS_SWITCH;
            break;
        }
    }

    // Check whether this device supports the vibrator.
    if (test_bit(FF_RUMBLE, device->ffBitmask)) {
        device->classes |= INPUT_DEVICE_CLASS_VIBRATOR;
    }

    // Configure virtual keys.
    if ((device->classes & INPUT_DEVICE_CLASS_TOUCH)) {
        // Load the virtual keys for the touch screen, if any.
        // We do this now so that we can make sure to load the keymap if necessary.
        status_t status = loadVirtualKeyMapLocked(device);
        if (!status) {
            device->classes |= INPUT_DEVICE_CLASS_KEYBOARD;
        }
    }

    // Load the key map.
    // We need to do this for joysticks too because the key layout may specify axes.
    status_t keyMapStatus = NAME_NOT_FOUND;
    if (device->classes & (INPUT_DEVICE_CLASS_KEYBOARD | INPUT_DEVICE_CLASS_JOYSTICK)) {
        // Load the keymap for the device.
        keyMapStatus = loadKeyMapLocked(device);
    }

    // Configure the keyboard, gamepad or virtual keyboard.
    if (device->classes & INPUT_DEVICE_CLASS_KEYBOARD) {
        // Register the keyboard as a built-in keyboard if it is eligible.
        if (!keyMapStatus
                && mBuiltInKeyboardId == NO_BUILT_IN_KEYBOARD
                && isEligibleBuiltInKeyboard(device->identifier,
                        device->configuration, &device->keyMap)) {
            mBuiltInKeyboardId = device->id;
        }

        // 'Q' key support = cheap test of whether this is an alpha-capable kbd
        if (hasKeycodeLocked(device, AKEYCODE_Q)) {
            device->classes |= INPUT_DEVICE_CLASS_ALPHAKEY;
        }

        // See if this device has a DPAD.
        if (hasKeycodeLocked(device, AKEYCODE_DPAD_UP) &&
                hasKeycodeLocked(device, AKEYCODE_DPAD_DOWN) &&
                hasKeycodeLocked(device, AKEYCODE_DPAD_LEFT) &&
                hasKeycodeLocked(device, AKEYCODE_DPAD_RIGHT) &&
                hasKeycodeLocked(device, AKEYCODE_DPAD_CENTER)) {
            device->classes |= INPUT_DEVICE_CLASS_DPAD;
        }

        // See if this device has a gamepad.
        for (size_t i = 0; i < sizeof(GAMEPAD_KEYCODES)/sizeof(GAMEPAD_KEYCODES[0]); i++) {
            if (hasKeycodeLocked(device, GAMEPAD_KEYCODES[i])) {
                device->classes |= INPUT_DEVICE_CLASS_GAMEPAD;
                break;
            }
        }
    }

    // If the device isn't recognized as something we handle, don't monitor it.
    if (device->classes == 0) {
        ALOGV("Dropping device: id=%d, path='%s', name='%s'",
                deviceId, devicePath, device->identifier.name.string());
        delete device;
        return -1;
    }

    // Determine whether the device is external or internal.
    if (isExternalDeviceLocked(device)) {
        device->classes |= INPUT_DEVICE_CLASS_EXTERNAL;
    }

    // Register with epoll.
    struct epoll_event eventItem;
    memset(&eventItem, 0, sizeof(eventItem));
    eventItem.events = EPOLLIN;
    eventItem.data.u32 = deviceId;
    if (epoll_ctl(mEpollFd, EPOLL_CTL_ADD, fd, &eventItem)) {
        ALOGE("Could not add device fd to epoll instance.  errno=%d", errno);
        delete device;
        return -1;
    }

    // Enable wake-lock behavior on kernels that support it.
    // TODO: Only need this for devices that can really wake the system.
    bool usingSuspendBlockIoctl = !ioctl(fd, EVIOCSSUSPENDBLOCK, 1);

    // Tell the kernel that we want to use the monotonic clock for reporting timestamps
    // associated with input events.  This is important because the input system
    // uses the timestamps extensively and assumes they were recorded using the monotonic
    // clock.
    //
    // In older kernel, before Linux 3.4, there was no way to tell the kernel which
    // clock to use to input event timestamps.  The standard kernel behavior was to
    // record a real time timestamp, which isn't what we want.  Android kernels therefore
    // contained a patch to the evdev_event() function in drivers/input/evdev.c to
    // replace the call to do_gettimeofday() with ktime_get_ts() to cause the monotonic
    // clock to be used instead of the real time clock.
    //
    // As of Linux 3.4, there is a new EVIOCSCLOCKID ioctl to set the desired clock.
    // Therefore, we no longer require the Android-specific kernel patch described above
    // as long as we make sure to set select the monotonic clock.  We do that here.
    int clockId = CLOCK_MONOTONIC;
    bool usingClockIoctl = !ioctl(fd, EVIOCSCLOCKID, &clockId);

    ALOGI("New device: id=%d, fd=%d, path='%s', name='%s', classes=0x%x, "
            "configuration='%s', keyLayout='%s', keyCharacterMap='%s', builtinKeyboard=%s, "
            "usingSuspendBlockIoctl=%s, usingClockIoctl=%s",
         deviceId, fd, devicePath, device->identifier.name.string(),
         device->classes,
         device->configurationFile.string(),
         device->keyMap.keyLayoutFile.string(),
         device->keyMap.keyCharacterMapFile.string(),
         toString(mBuiltInKeyboardId == deviceId),
         toString(usingSuspendBlockIoctl), toString(usingClockIoctl));

    addDeviceLocked(device);
    return 0;
}

void EventHub::createVirtualKeyboardLocked() {
    InputDeviceIdentifier identifier;
    identifier.name = "Virtual";
    identifier.uniqueId = "<virtual>";
    setDescriptor(identifier);

    Device* device = new Device(-1, VIRTUAL_KEYBOARD_ID, String8("<virtual>"), identifier);
    device->classes = INPUT_DEVICE_CLASS_KEYBOARD
            | INPUT_DEVICE_CLASS_ALPHAKEY
            | INPUT_DEVICE_CLASS_DPAD
            | INPUT_DEVICE_CLASS_VIRTUAL;
    loadKeyMapLocked(device);
    addDeviceLocked(device);
}

void EventHub::addDeviceLocked(Device* device) {
    mDevices.add(device->id, device);
    device->next = mOpeningDevices;
    mOpeningDevices = device;
}

void EventHub::loadConfigurationLocked(Device* device) {
    device->configurationFile = getInputDeviceConfigurationFilePathByDeviceIdentifier(
            device->identifier, INPUT_DEVICE_CONFIGURATION_FILE_TYPE_CONFIGURATION);
    if (device->configurationFile.isEmpty()) {
        ALOGD("No input device configuration file found for device '%s'.",
                device->identifier.name.string());
    } else {
        status_t status = PropertyMap::load(device->configurationFile,
                &device->configuration);
        if (status) {
            ALOGE("Error loading input device configuration file for device '%s'.  "
                    "Using default configuration.",
                    device->identifier.name.string());
        }
    }
}

status_t EventHub::loadVirtualKeyMapLocked(Device* device) {
    // The virtual key map is supplied by the kernel as a system board property file.
    String8 path;
    path.append("/sys/board_properties/virtualkeys.");
    path.append(device->identifier.name);
    if (access(path.string(), R_OK)) {
        return NAME_NOT_FOUND;
    }
    return VirtualKeyMap::load(path, &device->virtualKeyMap);
}

status_t EventHub::loadKeyMapLocked(Device* device) {
    return device->keyMap.load(device->identifier, device->configuration);
}

bool EventHub::isExternalDeviceLocked(Device* device) {
    if (device->configuration) {
        bool value;
        if (device->configuration->tryGetProperty(String8("device.internal"), value)) {
            return !value;
        }
    }
    return device->identifier.bus == BUS_USB || device->identifier.bus == BUS_BLUETOOTH;
}

bool EventHub::hasKeycodeLocked(Device* device, int keycode) const {
    if (!device->keyMap.haveKeyLayout() || !device->keyBitmask) {
        return false;
    }
    
    Vector<int32_t> scanCodes;
    device->keyMap.keyLayoutMap->findScanCodesForKey(keycode, &scanCodes);
    const size_t N = scanCodes.size();
    for (size_t i=0; i<N && i<=KEY_MAX; i++) {
        int32_t sc = scanCodes.itemAt(i);
        if (sc >= 0 && sc <= KEY_MAX && test_bit(sc, device->keyBitmask)) {
            return true;
        }
    }
    
    return false;
}

status_t EventHub::closeDeviceByPathLocked(const char *devicePath) {
    Device* device = getDeviceByPathLocked(devicePath);
    if (device) {
        closeDeviceLocked(device);
        return 0;
    }
    ALOGV("Remove device: %s not found, device may already have been removed.", devicePath);
    return -1;
}

void EventHub::closeAllDevicesLocked() {
    while (mDevices.size() > 0) {
        closeDeviceLocked(mDevices.valueAt(mDevices.size() - 1));
    }
}

void EventHub::closeDeviceLocked(Device* device) {
    ALOGI("Removed device: path=%s name=%s id=%d fd=%d classes=0x%x\n",
         device->path.string(), device->identifier.name.string(), device->id,
         device->fd, device->classes);

    if (device->id == mBuiltInKeyboardId) {
        ALOGW("built-in keyboard device %s (id=%d) is closing! the apps will not like this",
                device->path.string(), mBuiltInKeyboardId);
        mBuiltInKeyboardId = NO_BUILT_IN_KEYBOARD;
    }

    if (!device->isVirtual()) {
        if (epoll_ctl(mEpollFd, EPOLL_CTL_DEL, device->fd, NULL)) {
            ALOGW("Could not remove device fd from epoll instance.  errno=%d", errno);
        }
    }

    mDevices.removeItem(device->id);
    device->close();

    // Unlink for opening devices list if it is present.
    Device* pred = NULL;
    bool found = false;
    for (Device* entry = mOpeningDevices; entry != NULL; ) {
        if (entry == device) {
            found = true;
            break;
        }
        pred = entry;
        entry = entry->next;
    }
    if (found) {
        // Unlink the device from the opening devices list then delete it.
        // We don't need to tell the client that the device was closed because
        // it does not even know it was opened in the first place.
        ALOGI("Device %s was immediately closed after opening.", device->path.string());
        if (pred) {
            pred->next = device->next;
        } else {
            mOpeningDevices = device->next;
        }
        delete device;
    } else {
        // Link into closing devices list.
        // The device will be deleted later after we have informed the client.
        device->next = mClosingDevices;
        mClosingDevices = device;
    }
}

status_t EventHub::readNotifyLocked() {
    int res;
    char devname[PATH_MAX];
    char *filename;
    char event_buf[512];
    int event_size;
    int event_pos = 0;
    struct inotify_event *event;

    ALOGV("EventHub::readNotify nfd: %d\n", mINotifyFd);
    res = read(mINotifyFd, event_buf, sizeof(event_buf));
    if(res < (int)sizeof(*event)) {
        if(errno == EINTR)
            return 0;
        ALOGW("could not get event, %s\n", strerror(errno));
        return -1;
    }
    //printf("got %d bytes of event information\n", res);

    strcpy(devname, DEVICE_PATH);
    filename = devname + strlen(devname);
    *filename++ = '/';

    while(res >= (int)sizeof(*event)) {
        event = (struct inotify_event *)(event_buf + event_pos);
        //printf("%d: %08x \"%s\"\n", event->wd, event->mask, event->len ? event->name : "");
        if(event->len) {
            strcpy(filename, event->name);
            if(event->mask & IN_CREATE) {
                openDeviceLocked(devname);
            } else {
                ALOGI("Removing device '%s' due to inotify event\n", devname);
                closeDeviceByPathLocked(devname);
            }
        }
        event_size = sizeof(*event) + event->len;
        res -= event_size;
        event_pos += event_size;
    }
    return 0;
}

status_t EventHub::scanDirLocked(const char *dirname)
{
    char devname[PATH_MAX];
    char *filename;
    DIR *dir;
    struct dirent *de;
    dir = opendir(dirname);
    if(dir == NULL)
        return -1;
    strcpy(devname, dirname);
    filename = devname + strlen(devname);
    *filename++ = '/';
    while((de = readdir(dir))) {
        if(de->d_name[0] == '.' &&
           (de->d_name[1] == '\0' ||
            (de->d_name[1] == '.' && de->d_name[2] == '\0')))
            continue;
        strcpy(filename, de->d_name);
        openDeviceLocked(devname);
    }
    closedir(dir);
    return 0;
}

void EventHub::requestReopenDevices() {
    ALOGV("requestReopenDevices() called");

    AutoMutex _l(mLock);
    mNeedToReopenDevices = true;
}

void EventHub::dump(String8& dump) {
    dump.append("Event Hub State:\n");

    { // acquire lock
        AutoMutex _l(mLock);

        dump.appendFormat(INDENT "BuiltInKeyboardId: %d\n", mBuiltInKeyboardId);

        dump.append(INDENT "Devices:\n");

        for (size_t i = 0; i < mDevices.size(); i++) {
            const Device* device = mDevices.valueAt(i);
            if (mBuiltInKeyboardId == device->id) {
                dump.appendFormat(INDENT2 "%d: %s (aka device 0 - built-in keyboard)\n",
                        device->id, device->identifier.name.string());
            } else {
                dump.appendFormat(INDENT2 "%d: %s\n", device->id,
                        device->identifier.name.string());
            }
            dump.appendFormat(INDENT3 "Classes: 0x%08x\n", device->classes);
            dump.appendFormat(INDENT3 "Path: %s\n", device->path.string());
            dump.appendFormat(INDENT3 "Descriptor: %s\n", device->identifier.descriptor.string());
            dump.appendFormat(INDENT3 "Location: %s\n", device->identifier.location.string());
            dump.appendFormat(INDENT3 "UniqueId: %s\n", device->identifier.uniqueId.string());
            dump.appendFormat(INDENT3 "Identifier: bus=0x%04x, vendor=0x%04x, "
                    "product=0x%04x, version=0x%04x\n",
                    device->identifier.bus, device->identifier.vendor,
                    device->identifier.product, device->identifier.version);
            dump.appendFormat(INDENT3 "KeyLayoutFile: %s\n",
                    device->keyMap.keyLayoutFile.string());
            dump.appendFormat(INDENT3 "KeyCharacterMapFile: %s\n",
                    device->keyMap.keyCharacterMapFile.string());
            dump.appendFormat(INDENT3 "ConfigurationFile: %s\n",
                    device->configurationFile.string());
            dump.appendFormat(INDENT3 "HaveKeyboardLayoutOverlay: %s\n",
                    toString(device->overlayKeyMap != NULL));
        }
    } // release lock
}

void EventHub::monitor() {
    // Acquire and release the lock to ensure that the event hub has not deadlocked.
    mLock.lock();
    mLock.unlock();
}


}; // namespace android
