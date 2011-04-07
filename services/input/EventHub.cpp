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

//
// Handle events, like key input and vsync.
//
// The goal is to provide an optimized solution for Linux, not an
// implementation that works well across all platforms.  We expect
// events to arrive on file descriptors, so that we can use a select()
// select() call to sleep.
//
// We can't select() on anything but network sockets in Windows, so we
// provide an alternative implementation of waitEvent for that platform.
//
#define LOG_TAG "EventHub"

//#define LOG_NDEBUG 0

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

#include <ui/KeyLayoutMap.h>
#include <ui/KeyCharacterMap.h>
#include <ui/VirtualKeyMap.h>

#include <string.h>
#include <stdint.h>
#include <dirent.h>
#ifdef HAVE_INOTIFY
# include <sys/inotify.h>
#endif
#ifdef HAVE_ANDROID_OS
# include <sys/limits.h>        /* not part of Linux */
#endif
#include <sys/poll.h>
#include <sys/ioctl.h>

/* this macro is used to tell if "bit" is set in "array"
 * it selects a byte from the array, and does a boolean AND
 * operation with a byte that only has the relevant bit set.
 * eg. to check for the 12th bit, we do (array[1] & 1<<4)
 */
#define test_bit(bit, array)    (array[bit/8] & (1<<(bit%8)))

/* this macro computes the number of bytes needed to represent a bit array of the specified size */
#define sizeof_bit_array(bits)  ((bits + 7) / 8)

// Fd at index 0 is always reserved for inotify
#define FIRST_ACTUAL_DEVICE_INDEX 1

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

// --- EventHub::Device ---

EventHub::Device::Device(int fd, int32_t id, const String8& path,
        const InputDeviceIdentifier& identifier) :
        next(NULL),
        fd(fd), id(id), path(path), identifier(identifier),
        classes(0), keyBitmask(NULL), relBitmask(NULL),
        configuration(NULL), virtualKeyMap(NULL) {
}

EventHub::Device::~Device() {
    close();
    delete[] keyBitmask;
    delete[] relBitmask;
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

EventHub::EventHub(void) :
        mError(NO_INIT), mBuiltInKeyboardId(-1), mNextDeviceId(1),
        mOpeningDevices(0), mClosingDevices(0),
        mOpened(false), mNeedToSendFinishedDeviceScan(false),
        mInputFdIndex(1) {
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);

    memset(mSwitches, 0, sizeof(mSwitches));
    mNumCpus = sysconf(_SC_NPROCESSORS_ONLN);
}

EventHub::~EventHub(void) {
    release_wake_lock(WAKE_LOCK_ID);
    // we should free stuff here...
}

status_t EventHub::errorCheck() const {
    return mError;
}

String8 EventHub::getDeviceName(int32_t deviceId) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device == NULL) return String8();
    return device->identifier.name;
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

    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device == NULL) return -1;

    struct input_absinfo info;

    if(ioctl(device->fd, EVIOCGABS(axis), &info)) {
        LOGW("Error reading absolute controller %d for device %s fd %d\n",
             axis, device->identifier.name.string(), device->fd);
        return -errno;
    }

    if (info.minimum != info.maximum) {
        outAxisInfo->valid = true;
        outAxisInfo->minValue = info.minimum;
        outAxisInfo->maxValue = info.maximum;
        outAxisInfo->flat = info.flat;
        outAxisInfo->fuzz = info.fuzz;
    }
    return OK;
}

bool EventHub::hasRelativeAxis(int32_t deviceId, int axis) const {
    if (axis >= 0 && axis <= REL_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device && device->relBitmask) {
            return test_bit(axis, device->relBitmask);
        }
    }
    return false;
}

int32_t EventHub::getScanCodeState(int32_t deviceId, int32_t scanCode) const {
    if (scanCode >= 0 && scanCode <= KEY_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device != NULL) {
            return getScanCodeStateLocked(device, scanCode);
        }
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getScanCodeStateLocked(Device* device, int32_t scanCode) const {
    uint8_t key_bitmask[sizeof_bit_array(KEY_MAX + 1)];
    memset(key_bitmask, 0, sizeof(key_bitmask));
    if (ioctl(device->fd,
               EVIOCGKEY(sizeof(key_bitmask)), key_bitmask) >= 0) {
        return test_bit(scanCode, key_bitmask) ? AKEY_STATE_DOWN : AKEY_STATE_UP;
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getKeyCodeState(int32_t deviceId, int32_t keyCode) const {
    AutoMutex _l(mLock);

    Device* device = getDeviceLocked(deviceId);
    if (device != NULL) {
        return getKeyCodeStateLocked(device, keyCode);
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getKeyCodeStateLocked(Device* device, int32_t keyCode) const {
    if (!device->keyMap.haveKeyLayout()) {
        return AKEY_STATE_UNKNOWN;
    }

    Vector<int32_t> scanCodes;
    device->keyMap.keyLayoutMap->findScanCodesForKey(keyCode, &scanCodes);

    uint8_t key_bitmask[sizeof_bit_array(KEY_MAX + 1)];
    memset(key_bitmask, 0, sizeof(key_bitmask));
    if (ioctl(device->fd, EVIOCGKEY(sizeof(key_bitmask)), key_bitmask) >= 0) {
        #if 0
        for (size_t i=0; i<=KEY_MAX; i++) {
            LOGI("(Scan code %d: down=%d)", i, test_bit(i, key_bitmask));
        }
        #endif
        const size_t N = scanCodes.size();
        for (size_t i=0; i<N && i<=KEY_MAX; i++) {
            int32_t sc = scanCodes.itemAt(i);
            //LOGI("Code %d: down=%d", sc, test_bit(sc, key_bitmask));
            if (sc >= 0 && sc <= KEY_MAX && test_bit(sc, key_bitmask)) {
                return AKEY_STATE_DOWN;
            }
        }
        return AKEY_STATE_UP;
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getSwitchState(int32_t deviceId, int32_t sw) const {
    if (sw >= 0 && sw <= SW_MAX) {
        AutoMutex _l(mLock);

        Device* device = getDeviceLocked(deviceId);
        if (device != NULL) {
            return getSwitchStateLocked(device, sw);
        }
    }
    return AKEY_STATE_UNKNOWN;
}

int32_t EventHub::getSwitchStateLocked(Device* device, int32_t sw) const {
    uint8_t sw_bitmask[sizeof_bit_array(SW_MAX + 1)];
    memset(sw_bitmask, 0, sizeof(sw_bitmask));
    if (ioctl(device->fd,
               EVIOCGSW(sizeof(sw_bitmask)), sw_bitmask) >= 0) {
        return test_bit(sw, sw_bitmask) ? AKEY_STATE_DOWN : AKEY_STATE_UP;
    }
    return AKEY_STATE_UNKNOWN;
}

bool EventHub::markSupportedKeyCodes(int32_t deviceId, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) const {
    AutoMutex _l(mLock);

    Device* device = getDeviceLocked(deviceId);
    if (device != NULL) {
        return markSupportedKeyCodesLocked(device, numCodes, keyCodes, outFlags);
    }
    return false;
}

bool EventHub::markSupportedKeyCodesLocked(Device* device, size_t numCodes,
        const int32_t* keyCodes, uint8_t* outFlags) const {
    if (!device->keyMap.haveKeyLayout() || !device->keyBitmask) {
        return false;
    }

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

status_t EventHub::mapKey(int32_t deviceId, int scancode,
        int32_t* outKeycode, uint32_t* outFlags) const
{
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    
    if (device && device->keyMap.haveKeyLayout()) {
        status_t err = device->keyMap.keyLayoutMap->mapKey(scancode, outKeycode, outFlags);
        if (err == NO_ERROR) {
            return NO_ERROR;
        }
    }
    
    if (mBuiltInKeyboardId != -1) {
        device = getDeviceLocked(mBuiltInKeyboardId);
        
        if (device && device->keyMap.haveKeyLayout()) {
            status_t err = device->keyMap.keyLayoutMap->mapKey(scancode, outKeycode, outFlags);
            if (err == NO_ERROR) {
                return NO_ERROR;
            }
        }
    }
    
    *outKeycode = 0;
    *outFlags = 0;
    return NAME_NOT_FOUND;
}

status_t EventHub::mapAxis(int32_t deviceId, int scancode, AxisInfo* outAxisInfo) const
{
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);

    if (device && device->keyMap.haveKeyLayout()) {
        status_t err = device->keyMap.keyLayoutMap->mapAxis(scancode, outAxisInfo);
        if (err == NO_ERROR) {
            return NO_ERROR;
        }
    }

    if (mBuiltInKeyboardId != -1) {
        device = getDeviceLocked(mBuiltInKeyboardId);

        if (device && device->keyMap.haveKeyLayout()) {
            status_t err = device->keyMap.keyLayoutMap->mapAxis(scancode, outAxisInfo);
            if (err == NO_ERROR) {
                return NO_ERROR;
            }
        }
    }

    return NAME_NOT_FOUND;
}

void EventHub::addExcludedDevice(const char* deviceName)
{
    AutoMutex _l(mLock);

    String8 name(deviceName);
    mExcludedDevices.push_back(name);
}

bool EventHub::hasLed(int32_t deviceId, int32_t led) const {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device) {
        uint8_t bitmask[sizeof_bit_array(LED_MAX + 1)];
        memset(bitmask, 0, sizeof(bitmask));
        if (ioctl(device->fd, EVIOCGBIT(EV_LED, sizeof(bitmask)), bitmask) >= 0) {
            if (test_bit(led, bitmask)) {
                return true;
            }
        }
    }
    return false;
}

void EventHub::setLedState(int32_t deviceId, int32_t led, bool on) {
    AutoMutex _l(mLock);
    Device* device = getDeviceLocked(deviceId);
    if (device) {
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

EventHub::Device* EventHub::getDeviceLocked(int32_t deviceId) const {
    if (deviceId == 0) {
        deviceId = mBuiltInKeyboardId;
    }

    size_t numDevices = mDevices.size();
    for (size_t i = FIRST_ACTUAL_DEVICE_INDEX; i < numDevices; i++) {
        Device* device = mDevices[i];
        if (device->id == deviceId) {
            return device;
        }
    }
    return NULL;
}

size_t EventHub::getEvents(int timeoutMillis, RawEvent* buffer, size_t bufferSize) {
    // Note that we only allow one caller to getEvents(), so don't need
    // to do locking here...  only when adding/removing devices.
    LOG_ASSERT(bufferSize >= 1);

    if (!mOpened) {
        mError = openPlatformInput() ? NO_ERROR : UNKNOWN_ERROR;
        mOpened = true;
        mNeedToSendFinishedDeviceScan = true;
    }

    struct input_event readBuffer[bufferSize];

    RawEvent* event = buffer;
    size_t capacity = bufferSize;
    for (;;) {
        nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);

        // Report any devices that had last been added/removed.
        while (mClosingDevices) {
            Device* device = mClosingDevices;
            LOGV("Reporting device closed: id=%d, name=%s\n",
                 device->id, device->path.string());
            mClosingDevices = device->next;
            event->when = now;
            event->deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;
            event->type = DEVICE_REMOVED;
            event += 1;
            delete device;
            mNeedToSendFinishedDeviceScan = true;
            if (--capacity == 0) {
                break;
            }
        }

        while (mOpeningDevices != NULL) {
            Device* device = mOpeningDevices;
            LOGV("Reporting device opened: id=%d, name=%s\n",
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
        // mInputFdIndex is initially 1 because index 0 is used for inotify.
        bool deviceWasRemoved = false;
        while (mInputFdIndex < mFds.size()) {
            const struct pollfd& pfd = mFds[mInputFdIndex];
            if (pfd.revents & POLLIN) {
                int32_t readSize = read(pfd.fd, readBuffer, sizeof(struct input_event) * capacity);
                if (readSize < 0) {
                    if (errno == ENODEV) {
                        deviceWasRemoved = true;
                        break;
                    }
                    if (errno != EAGAIN && errno != EINTR) {
                        LOGW("could not get event (errno=%d)", errno);
                    }
                } else if ((readSize % sizeof(struct input_event)) != 0) {
                    LOGE("could not get event (wrong size: %d)", readSize);
                } else if (readSize == 0) { // eof
                    deviceWasRemoved = true;
                    break;
                } else {
                    const Device* device = mDevices[mInputFdIndex];
                    int32_t deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;

                    size_t count = size_t(readSize) / sizeof(struct input_event);
                    for (size_t i = 0; i < count; i++) {
                        const struct input_event& iev = readBuffer[i];
                        LOGV("%s got: t0=%d, t1=%d, type=%d, code=%d, value=%d",
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
                        LOGV("event time %lld, now %lld", event->when, now);
#else
                        event->when = now;
#endif
                        event->deviceId = deviceId;
                        event->type = iev.type;
                        event->scanCode = iev.code;
                        event->value = iev.value;
                        event->keyCode = AKEYCODE_UNKNOWN;
                        event->flags = 0;
                        if (iev.type == EV_KEY && device->keyMap.haveKeyLayout()) {
                            status_t err = device->keyMap.keyLayoutMap->mapKey(iev.code,
                                        &event->keyCode, &event->flags);
                            LOGV("iev.code=%d keyCode=%d flags=0x%08x err=%d\n",
                                    iev.code, event->keyCode, event->flags, err);
                        }
                        event += 1;
                    }
                    capacity -= count;
                    if (capacity == 0) {
                        break;
                    }
                }
            }
            mInputFdIndex += 1;
        }

        // Handle the case where a device has been removed but INotify has not yet noticed.
        if (deviceWasRemoved) {
            AutoMutex _l(mLock);
            closeDeviceAtIndexLocked(mInputFdIndex);
            continue; // report added or removed devices immediately
        }

#if HAVE_INOTIFY
        // readNotify() will modify mFDs and mFDCount, so this must be done after
        // processing all other events.
        if(mFds[0].revents & POLLIN) {
            readNotify(mFds[0].fd);
            mFds.editItemAt(0).revents = 0;
            mInputFdIndex = mFds.size();
            continue; // report added or removed devices immediately
        }
#endif

        // Return now if we have collected any events, otherwise poll.
        if (event != buffer) {
            break;
        }

        // Poll for events.  Mind the wake lock dance!
        // We hold a wake lock at all times except during poll().  This works due to some
        // subtle choreography.  When a device driver has pending (unread) events, it acquires
        // a kernel wake lock.  However, once the last pending event has been read, the device
        // driver will release the kernel wake lock.  To prevent the system from going to sleep
        // when this happens, the EventHub holds onto its own user wake lock while the client
        // is processing events.  Thus the system can only sleep if there are no events
        // pending or currently being processed.
        //
        // The timeout is advisory only.  If the device is asleep, it will not wake just to
        // service the timeout.
        release_wake_lock(WAKE_LOCK_ID);

        int pollResult = poll(mFds.editArray(), mFds.size(), timeoutMillis);

        acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);

        if (pollResult == 0) {
            break; // timed out
        }
        if (pollResult < 0) {
            // Sleep after errors to avoid locking up the system.
            // Hopefully the error is transient.
            if (errno != EINTR) {
                LOGW("poll failed (errno=%d)\n", errno);
                usleep(100000);
            }
        } else {
            // On an SMP system, it is possible for the framework to read input events
            // faster than the kernel input device driver can produce a complete packet.
            // Because poll() wakes up as soon as the first input event becomes available,
            // the framework will often end up reading one event at a time until the
            // packet is complete.  Instead of one call to read() returning 71 events,
            // it could take 71 calls to read() each returning 1 event.
            //
            // Sleep for a short period of time after waking up from the poll() to give
            // the kernel time to finish writing the entire packet of input events.
            if (mNumCpus > 1) {
                usleep(250);
            }
        }

        // Prepare to process all of the FDs we just polled.
        mInputFdIndex = 1;
    }

    // All done, return the number of events we read.
    return event - buffer;
}

/*
 * Open the platform-specific input device.
 */
bool EventHub::openPlatformInput(void) {
    /*
     * Open platform-specific input device(s).
     */
    int res, fd;

#ifdef HAVE_INOTIFY
    fd = inotify_init();
    res = inotify_add_watch(fd, DEVICE_PATH, IN_DELETE | IN_CREATE);
    if(res < 0) {
        LOGE("could not add watch for %s, %s\n", DEVICE_PATH, strerror(errno));
    }
#else
    /*
     * The code in EventHub::getEvent assumes that mFDs[0] is an inotify fd.
     * We allocate space for it and set it to something invalid.
     */
    fd = -1;
#endif

    // Reserve fd index 0 for inotify.
    struct pollfd pollfd;
    pollfd.fd = fd;
    pollfd.events = POLLIN;
    pollfd.revents = 0;
    mFds.push(pollfd);
    mDevices.push(NULL);

    res = scanDir(DEVICE_PATH);
    if(res < 0) {
        LOGE("scan dir failed for %s\n", DEVICE_PATH);
    }

    return true;
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

int EventHub::openDevice(const char *devicePath) {
    char buffer[80];

    LOGV("Opening device: %s", devicePath);

    AutoMutex _l(mLock);

    int fd = open(devicePath, O_RDWR);
    if(fd < 0) {
        LOGE("could not open %s, %s\n", devicePath, strerror(errno));
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
    List<String8>::iterator iter = mExcludedDevices.begin();
    List<String8>::iterator end = mExcludedDevices.end();
    for ( ; iter != end; iter++) {
        const char* test = *iter;
        if (identifier.name == test) {
            LOGI("ignoring event id %s driver %s\n", devicePath, test);
            close(fd);
            return -1;
        }
    }

    // Get device driver version.
    int driverVersion;
    if(ioctl(fd, EVIOCGVERSION, &driverVersion)) {
        LOGE("could not get driver version for %s, %s\n", devicePath, strerror(errno));
        close(fd);
        return -1;
    }

    // Get device identifier.
    struct input_id inputId;
    if(ioctl(fd, EVIOCGID, &inputId)) {
        LOGE("could not get device input id for %s, %s\n", devicePath, strerror(errno));
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

    // Make file descriptor non-blocking for use with poll().
    if (fcntl(fd, F_SETFL, O_NONBLOCK)) {
        LOGE("Error %d making device file descriptor non-blocking.", errno);
        close(fd);
        return -1;
    }

    // Allocate device.  (The device object takes ownership of the fd at this point.)
    int32_t deviceId = mNextDeviceId++;
    Device* device = new Device(fd, deviceId, String8(devicePath), identifier);

#if 0
    LOGI("add device %d: %s\n", deviceId, devicePath);
    LOGI("  bus:       %04x\n"
         "  vendor     %04x\n"
         "  product    %04x\n"
         "  version    %04x\n",
        identifier.bus, identifier.vendor, identifier.product, identifier.version);
    LOGI("  name:      \"%s\"\n", identifier.name.string());
    LOGI("  location:  \"%s\"\n", identifier.location.string());
    LOGI("  unique id: \"%s\"\n", identifier.uniqueId.string());
    LOGI("  driver:    v%d.%d.%d\n",
        driverVersion >> 16, (driverVersion >> 8) & 0xff, driverVersion & 0xff);
#endif

    // Load the configuration file for the device.
    loadConfiguration(device);

    // Figure out the kinds of events the device reports.
    uint8_t key_bitmask[sizeof_bit_array(KEY_MAX + 1)];
    memset(key_bitmask, 0, sizeof(key_bitmask));
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(key_bitmask)), key_bitmask);

    uint8_t abs_bitmask[sizeof_bit_array(ABS_MAX + 1)];
    memset(abs_bitmask, 0, sizeof(abs_bitmask));
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bitmask)), abs_bitmask);

    uint8_t rel_bitmask[sizeof_bit_array(REL_MAX + 1)];
    memset(rel_bitmask, 0, sizeof(rel_bitmask));
    ioctl(fd, EVIOCGBIT(EV_REL, sizeof(rel_bitmask)), rel_bitmask);

    uint8_t sw_bitmask[sizeof_bit_array(SW_MAX + 1)];
    memset(sw_bitmask, 0, sizeof(sw_bitmask));
    ioctl(fd, EVIOCGBIT(EV_SW, sizeof(sw_bitmask)), sw_bitmask);

    device->keyBitmask = new uint8_t[sizeof(key_bitmask)];
    if (device->keyBitmask != NULL) {
        memcpy(device->keyBitmask, key_bitmask, sizeof(key_bitmask));
    } else {
        delete device;
        LOGE("out of memory allocating key bitmask");
        return -1;
    }

    device->relBitmask = new uint8_t[sizeof(rel_bitmask)];
    if (device->relBitmask != NULL) {
        memcpy(device->relBitmask, rel_bitmask, sizeof(rel_bitmask));
    } else {
        delete device;
        LOGE("out of memory allocating rel bitmask");
        return -1;
    }

    // See if this is a keyboard.  Ignore everything in the button range except for
    // joystick and gamepad buttons which are handled like keyboards for the most part.
    bool haveKeyboardKeys = containsNonZeroByte(key_bitmask, 0, sizeof_bit_array(BTN_MISC))
            || containsNonZeroByte(key_bitmask, sizeof_bit_array(KEY_OK),
                    sizeof_bit_array(KEY_MAX + 1));
    bool haveGamepadButtons = containsNonZeroByte(key_bitmask, sizeof_bit_array(BTN_MISC),
                    sizeof_bit_array(BTN_MOUSE))
            || containsNonZeroByte(key_bitmask, sizeof_bit_array(BTN_JOYSTICK),
                    sizeof_bit_array(BTN_DIGI));
    if (haveKeyboardKeys || haveGamepadButtons) {
        device->classes |= INPUT_DEVICE_CLASS_KEYBOARD;
    }

    // See if this is a cursor device such as a trackball or mouse.
    if (test_bit(BTN_MOUSE, key_bitmask)
            && test_bit(REL_X, rel_bitmask)
            && test_bit(REL_Y, rel_bitmask)) {
        device->classes |= INPUT_DEVICE_CLASS_CURSOR;
    }

    // See if this is a touch pad.
    // Is this a new modern multi-touch driver?
    if (test_bit(ABS_MT_POSITION_X, abs_bitmask)
            && test_bit(ABS_MT_POSITION_Y, abs_bitmask)) {
        // Some joysticks such as the PS3 controller report axes that conflict
        // with the ABS_MT range.  Try to confirm that the device really is
        // a touch screen.
        if (test_bit(BTN_TOUCH, key_bitmask) || !haveGamepadButtons) {
            device->classes |= INPUT_DEVICE_CLASS_TOUCH | INPUT_DEVICE_CLASS_TOUCH_MT;
        }
    // Is this an old style single-touch driver?
    } else if (test_bit(BTN_TOUCH, key_bitmask)
            && test_bit(ABS_X, abs_bitmask)
            && test_bit(ABS_Y, abs_bitmask)) {
        device->classes |= INPUT_DEVICE_CLASS_TOUCH;
    }

    // See if this device is a joystick.
    // Ignore touchscreens because they use the same absolute axes for other purposes.
    // Assumes that joysticks always have gamepad buttons in order to distinguish them
    // from other devices such as accelerometers that also have absolute axes.
    if (haveGamepadButtons
            && !(device->classes & INPUT_DEVICE_CLASS_TOUCH)
            && containsNonZeroByte(abs_bitmask, 0, sizeof_bit_array(ABS_MAX + 1))) {
        device->classes |= INPUT_DEVICE_CLASS_JOYSTICK;
    }

    // figure out the switches this device reports
    bool haveSwitches = false;
    for (int i=0; i<EV_SW; i++) {
        //LOGI("Device %d sw %d: has=%d", device->id, i, test_bit(i, sw_bitmask));
        if (test_bit(i, sw_bitmask)) {
            haveSwitches = true;
            if (mSwitches[i] == 0) {
                mSwitches[i] = device->id;
            }
        }
    }
    if (haveSwitches) {
        device->classes |= INPUT_DEVICE_CLASS_SWITCH;
    }

    if ((device->classes & INPUT_DEVICE_CLASS_TOUCH)) {
        // Load the virtual keys for the touch screen, if any.
        // We do this now so that we can make sure to load the keymap if necessary.
        status_t status = loadVirtualKeyMap(device);
        if (!status) {
            device->classes |= INPUT_DEVICE_CLASS_KEYBOARD;
        }
    }

    // Load the key map.
    // We need to do this for joysticks too because the key layout may specify axes.
    status_t keyMapStatus = NAME_NOT_FOUND;
    if (device->classes & (INPUT_DEVICE_CLASS_KEYBOARD | INPUT_DEVICE_CLASS_JOYSTICK)) {
        // Load the keymap for the device.
        keyMapStatus = loadKeyMap(device);
    }

    // Configure the keyboard, gamepad or virtual keyboard.
    if (device->classes & INPUT_DEVICE_CLASS_KEYBOARD) {
        // Set system properties for the keyboard.
        setKeyboardProperties(device, false);

        // Register the keyboard as a built-in keyboard if it is eligible.
        if (!keyMapStatus
                && mBuiltInKeyboardId == -1
                && isEligibleBuiltInKeyboard(device->identifier,
                        device->configuration, &device->keyMap)) {
            mBuiltInKeyboardId = device->id;
            setKeyboardProperties(device, true);
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
        LOGV("Dropping device: id=%d, path='%s', name='%s'",
                deviceId, devicePath, device->identifier.name.string());
        delete device;
        return -1;
    }

    // Determine whether the device is external or internal.
    if (isExternalDevice(device)) {
        device->classes |= INPUT_DEVICE_CLASS_EXTERNAL;
    }

    LOGI("New device: id=%d, fd=%d, path='%s', name='%s', classes=0x%x, "
            "configuration='%s', keyLayout='%s', keyCharacterMap='%s', builtinKeyboard=%s",
         deviceId, fd, devicePath, device->identifier.name.string(),
         device->classes,
         device->configurationFile.string(),
         device->keyMap.keyLayoutFile.string(),
         device->keyMap.keyCharacterMapFile.string(),
         toString(mBuiltInKeyboardId == deviceId));

    struct pollfd pollfd;
    pollfd.fd = fd;
    pollfd.events = POLLIN;
    pollfd.revents = 0;
    mFds.push(pollfd);
    mDevices.push(device);

    device->next = mOpeningDevices;
    mOpeningDevices = device;
    return 0;
}

void EventHub::loadConfiguration(Device* device) {
    device->configurationFile = getInputDeviceConfigurationFilePathByDeviceIdentifier(
            device->identifier, INPUT_DEVICE_CONFIGURATION_FILE_TYPE_CONFIGURATION);
    if (device->configurationFile.isEmpty()) {
        LOGD("No input device configuration file found for device '%s'.",
                device->identifier.name.string());
    } else {
        status_t status = PropertyMap::load(device->configurationFile,
                &device->configuration);
        if (status) {
            LOGE("Error loading input device configuration file for device '%s'.  "
                    "Using default configuration.",
                    device->identifier.name.string());
        }
    }
}

status_t EventHub::loadVirtualKeyMap(Device* device) {
    // The virtual key map is supplied by the kernel as a system board property file.
    String8 path;
    path.append("/sys/board_properties/virtualkeys.");
    path.append(device->identifier.name);
    if (access(path.string(), R_OK)) {
        return NAME_NOT_FOUND;
    }
    return VirtualKeyMap::load(path, &device->virtualKeyMap);
}

status_t EventHub::loadKeyMap(Device* device) {
    return device->keyMap.load(device->identifier, device->configuration);
}

void EventHub::setKeyboardProperties(Device* device, bool builtInKeyboard) {
    int32_t id = builtInKeyboard ? 0 : device->id;
    android::setKeyboardProperties(id, device->identifier,
            device->keyMap.keyLayoutFile, device->keyMap.keyCharacterMapFile);
}

void EventHub::clearKeyboardProperties(Device* device, bool builtInKeyboard) {
    int32_t id = builtInKeyboard ? 0 : device->id;
    android::clearKeyboardProperties(id);
}

bool EventHub::isExternalDevice(Device* device) {
    if (device->configuration) {
        bool value;
        if (device->configuration->tryGetProperty(String8("device.internal"), value)
                && value) {
            return false;
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

int EventHub::closeDevice(const char *devicePath) {
    AutoMutex _l(mLock);

    for (size_t i = FIRST_ACTUAL_DEVICE_INDEX; i < mDevices.size(); i++) {
        Device* device = mDevices[i];
        if (device->path == devicePath) {
            return closeDeviceAtIndexLocked(i);
        }
    }
    LOGV("Remove device: %s not found, device may already have been removed.", devicePath);
    return -1;
}

int EventHub::closeDeviceAtIndexLocked(int index) {
    Device* device = mDevices[index];
    LOGI("Removed device: path=%s name=%s id=%d fd=%d classes=0x%x\n",
         device->path.string(), device->identifier.name.string(), device->id,
         device->fd, device->classes);

    for (int j=0; j<EV_SW; j++) {
        if (mSwitches[j] == device->id) {
            mSwitches[j] = 0;
        }
    }

    if (device->id == mBuiltInKeyboardId) {
        LOGW("built-in keyboard device %s (id=%d) is closing! the apps will not like this",
                device->path.string(), mBuiltInKeyboardId);
        mBuiltInKeyboardId = -1;
        clearKeyboardProperties(device, true);
    }
    clearKeyboardProperties(device, false);

    mFds.removeAt(index);
    mDevices.removeAt(index);
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
        LOGI("Device %s was immediately closed after opening.", device->path.string());
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
    return 0;
}

int EventHub::readNotify(int nfd) {
#ifdef HAVE_INOTIFY
    int res;
    char devname[PATH_MAX];
    char *filename;
    char event_buf[512];
    int event_size;
    int event_pos = 0;
    struct inotify_event *event;

    LOGV("EventHub::readNotify nfd: %d\n", nfd);
    res = read(nfd, event_buf, sizeof(event_buf));
    if(res < (int)sizeof(*event)) {
        if(errno == EINTR)
            return 0;
        LOGW("could not get event, %s\n", strerror(errno));
        return 1;
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
                openDevice(devname);
            }
            else {
                closeDevice(devname);
            }
        }
        event_size = sizeof(*event) + event->len;
        res -= event_size;
        event_pos += event_size;
    }
#endif
    return 0;
}

int EventHub::scanDir(const char *dirname)
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
        openDevice(devname);
    }
    closedir(dir);
    return 0;
}

void EventHub::dump(String8& dump) {
    dump.append("Event Hub State:\n");

    { // acquire lock
        AutoMutex _l(mLock);

        dump.appendFormat(INDENT "BuiltInKeyboardId: %d\n", mBuiltInKeyboardId);

        dump.append(INDENT "Devices:\n");

        for (size_t i = FIRST_ACTUAL_DEVICE_INDEX; i < mDevices.size(); i++) {
            const Device* device = mDevices[i];
            if (device) {
                if (mBuiltInKeyboardId == device->id) {
                    dump.appendFormat(INDENT2 "%d: %s (aka device 0 - built-in keyboard)\n",
                            device->id, device->identifier.name.string());
                } else {
                    dump.appendFormat(INDENT2 "%d: %s\n", device->id,
                            device->identifier.name.string());
                }
                dump.appendFormat(INDENT3 "Classes: 0x%08x\n", device->classes);
                dump.appendFormat(INDENT3 "Path: %s\n", device->path.string());
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
            }
        }
    } // release lock
}

}; // namespace android
