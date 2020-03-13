/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "TvRemote-native-uiBridge"

#include "com_android_server_tv_GamepadKeys.h"
#include "com_android_server_tv_TvKeys.h"

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/ScopedUtfChars.h>
#include <android/keycodes.h>

#include <utils/BitSet.h>
#include <utils/Errors.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <ctype.h>
#include <linux/input.h>
#include <unistd.h>
#include <sys/time.h>
#include <time.h>
#include <stdint.h>
#include <map>
#include <fcntl.h>
#include <linux/uinput.h>
#include <signal.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/types.h>

#define SLOT_UNKNOWN -1

namespace android {

static std::map<int32_t,int> keysMap;
static std::map<int32_t,int32_t> slotsMap;
static BitSet32 mtSlots;

static void initKeysMap() {
    if (keysMap.empty()) {
        for (size_t i = 0; i < NELEM(KEYS); i++) {
            keysMap[KEYS[i].androidKeyCode] = KEYS[i].linuxKeyCode;
        }
    }
}

static int32_t getLinuxKeyCode(int32_t androidKeyCode) {
    std::map<int,int>::iterator it = keysMap.find(androidKeyCode);
    if (it != keysMap.end()) {
        return it->second;
    }
    return KEY_UNKNOWN;
}

static int findSlot(int32_t pointerId) {
    std::map<int,int>::iterator it = slotsMap.find(pointerId);
    if (it != slotsMap.end()) {
        return it->second;
    }
    return SLOT_UNKNOWN;
}

static int assignSlot(int32_t pointerId) {
    if (!mtSlots.isFull()) {
        uint32_t slot = mtSlots.markFirstUnmarkedBit();
        slotsMap[pointerId] = slot;
        return slot;
    }
    return SLOT_UNKNOWN;
}

static void unassignSlot(int32_t pointerId) {
    int slot = findSlot(pointerId);
    if (slot != SLOT_UNKNOWN) {
        mtSlots.clearBit(slot);
        slotsMap.erase(pointerId);
    }
}

static const int kInvalidFileDescriptor = -1;

// Convenience class to manage an opened /dev/uinput device
class UInputDescriptor {
public:
    UInputDescriptor() : mFd(kInvalidFileDescriptor) {
        memset(&mUinputDescriptor, 0, sizeof(mUinputDescriptor));
    }

    // Auto-closes any open /dev/uinput descriptor unless detached.
    ~UInputDescriptor();

    // Open /dev/uinput and prepare to register
    // the device with the given name and unique Id
    bool Open(const char* name, const char* uniqueId);

    // Checks if the current file descriptor is valid
    bool IsValid() const { return mFd != kInvalidFileDescriptor; }

    void EnableKey(int keyCode);

    void EnableAxesEvents();
    void EnableAxis(int axis, int rangeMin, int rangeMax);

    bool Create();

    // Detaches from the current file descriptor
    // Returns the file descriptor for /dev/uniput
    int Detach();

private:
    int mFd;
    struct uinput_user_dev mUinputDescriptor;
};

UInputDescriptor::~UInputDescriptor() {
    if (mFd != kInvalidFileDescriptor) {
        close(mFd);
        mFd = kInvalidFileDescriptor;
    }
}

int UInputDescriptor::Detach() {
    int fd = mFd;
    mFd = kInvalidFileDescriptor;
    return fd;
}

bool UInputDescriptor::Open(const char* name, const char* uniqueId) {
    if (IsValid()) {
        ALOGE("UInput device already open");
        return false;
    }

    mFd = ::open("/dev/uinput", O_WRONLY | O_NDELAY);
    if (mFd < 0) {
        ALOGE("Cannot open /dev/uinput: %s.", strerror(errno));
        mFd = kInvalidFileDescriptor;
        return false;
    }

    // write device unique id to the phys property
    ioctl(mFd, UI_SET_PHYS, uniqueId);

    memset(&mUinputDescriptor, 0, sizeof(mUinputDescriptor));
    strlcpy(mUinputDescriptor.name, name, UINPUT_MAX_NAME_SIZE);
    mUinputDescriptor.id.version = 1;
    mUinputDescriptor.id.bustype = BUS_VIRTUAL;

    // All UInput devices we use process keys
    ioctl(mFd, UI_SET_EVBIT, EV_KEY);

    return true;
}

void UInputDescriptor::EnableKey(int keyCode) {
    ioctl(mFd, UI_SET_KEYBIT, keyCode);
}

void UInputDescriptor::EnableAxesEvents() {
    ioctl(mFd, UI_SET_EVBIT, EV_ABS);
}

void UInputDescriptor::EnableAxis(int axis, int rangeMin, int rangeMax) {
    if ((axis < 0) || (axis >= NELEM(mUinputDescriptor.absmin))) {
        ALOGE("Invalid axis number: %d", axis);
        return;
    }

    if (ioctl(mFd, UI_SET_ABSBIT, axis) != 0) {
        ALOGE("Failed to set absbit for %d", axis);
    }

    mUinputDescriptor.absmin[axis] = rangeMin;
    mUinputDescriptor.absmax[axis] = rangeMax;
    mUinputDescriptor.absfuzz[axis] = 0;
    mUinputDescriptor.absflat[axis] = 0;
}

bool UInputDescriptor::Create() {
    // register the input device
    if (write(mFd, &mUinputDescriptor, sizeof(mUinputDescriptor)) != sizeof(mUinputDescriptor)) {
        ALOGE("Cannot write uinput_user_dev to fd %d: %s.", mFd, strerror(errno));
        return false;
    }

    if (ioctl(mFd, UI_DEV_CREATE) != 0) {
        ALOGE("Unable to create uinput device: %s.", strerror(errno));
        return false;
    }

    ALOGV("Created uinput device, fd=%d.", mFd);

    return true;
}

class NativeConnection {
public:
    enum class ConnectionType {
        kRemoteDevice,
        kGamepadDevice,
    };

    ~NativeConnection();

    static NativeConnection* open(const char* name, const char* uniqueId,
            int32_t width, int32_t height, int32_t maxPointerId);

    static NativeConnection* openGamepad(const char* name, const char* uniqueId);

    void sendEvent(int32_t type, int32_t code, int32_t value);

    int32_t getMaxPointers() const { return mMaxPointers; }

    ConnectionType getType() const { return mType; }

    bool IsGamepad() const { return getType() == ConnectionType::kGamepadDevice; }

    bool IsRemote() const { return getType() == ConnectionType::kRemoteDevice; }

private:
    NativeConnection(int fd, int32_t maxPointers, ConnectionType type);

    const int mFd;
    const int32_t mMaxPointers;
    const ConnectionType mType;
};

NativeConnection::NativeConnection(int fd, int32_t maxPointers, ConnectionType type)
      : mFd(fd), mMaxPointers(maxPointers), mType(type) {}

NativeConnection::~NativeConnection() {
    ALOGI("Un-Registering uinput device %d.", mFd);
    ioctl(mFd, UI_DEV_DESTROY);
    close(mFd);
}

NativeConnection* NativeConnection::open(const char* name, const char* uniqueId,
        int32_t width, int32_t height, int32_t maxPointers) {
    ALOGI("Registering uinput device %s: touch pad size %dx%d, "
            "max pointers %d.", name, width, height, maxPointers);

    initKeysMap();

    UInputDescriptor descriptor;
    if (!descriptor.Open(name, uniqueId)) {
        return nullptr;
    }

    // set the keys mapped
    for (size_t i = 0; i < NELEM(KEYS); i++) {
        descriptor.EnableKey(KEYS[i].linuxKeyCode);
    }

    if (!descriptor.Create()) {
        return nullptr;
    }

    return new NativeConnection(descriptor.Detach(), maxPointers, ConnectionType::kRemoteDevice);
}

NativeConnection* NativeConnection::openGamepad(const char* name, const char* uniqueId) {
    ALOGI("Registering uinput device %s: gamepad", name);

    UInputDescriptor descriptor;
    if (!descriptor.Open(name, uniqueId)) {
        return nullptr;
    }

    // set the keys mapped for gamepads
    for (size_t i = 0; i < NELEM(GAMEPAD_KEY_CODES); i++) {
        descriptor.EnableKey(GAMEPAD_KEY_CODES[i]);
    }

    // define the axes that are required
    descriptor.EnableAxesEvents();
    for (size_t i = 0; i < NELEM(GAMEPAD_AXES); i++) {
        const Axis& axis = GAMEPAD_AXES[i];
        descriptor.EnableAxis(axis.number, axis.rangeMin, axis.rangeMax);
    }

    if (!descriptor.Create()) {
        return nullptr;
    }

    return new NativeConnection(descriptor.Detach(), 0, ConnectionType::kGamepadDevice);
}

void NativeConnection::sendEvent(int32_t type, int32_t code, int32_t value) {
    struct input_event iev;
    memset(&iev, 0, sizeof(iev));
    iev.type = type;
    iev.code = code;
    iev.value = value;
    write(mFd, &iev, sizeof(iev));
}

static jlong nativeOpen(JNIEnv* env, jclass clazz,
        jstring nameStr, jstring uniqueIdStr,
        jint width, jint height, jint maxPointers) {
    ScopedUtfChars name(env, nameStr);
    ScopedUtfChars uniqueId(env, uniqueIdStr);

    NativeConnection* connection = NativeConnection::open(name.c_str(), uniqueId.c_str(),
            width, height, maxPointers);
    return reinterpret_cast<jlong>(connection);
}

static jlong nativeGamepadOpen(JNIEnv* env, jclass clazz, jstring nameStr, jstring uniqueIdStr) {
    ScopedUtfChars name(env, nameStr);
    ScopedUtfChars uniqueId(env, uniqueIdStr);

    NativeConnection* connection = NativeConnection::openGamepad(name.c_str(), uniqueId.c_str());
    return reinterpret_cast<jlong>(connection);
}

static void nativeClose(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);
    delete connection;
}

static void nativeSendKey(JNIEnv* env, jclass clazz, jlong ptr, jint keyCode, jboolean down) {
    int32_t code = getLinuxKeyCode(keyCode);
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    if (connection->IsGamepad()) {
        ALOGE("Invalid key even for a gamepad - need to send gamepad events");
        return;
    }

    if (code != KEY_UNKNOWN) {
        connection->sendEvent(EV_KEY, code, down ? 1 : 0);
    } else {
        ALOGE("Received an unknown keycode of %d.", keyCode);
    }
}

static void nativeSendGamepadKey(JNIEnv* env, jclass clazz, jlong ptr, jint keyIndex,
                                 jboolean down) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    if (!connection->IsGamepad()) {
        ALOGE("Invalid gamepad key for non-gamepad device");
        return;
    }

    if ((keyIndex < 0) || (keyIndex >= NELEM(GAMEPAD_KEY_CODES))) {
        ALOGE("Invalid gamepad key index: %d", keyIndex);
        return;
    }

    connection->sendEvent(EV_KEY, GAMEPAD_KEY_CODES[keyIndex], down ? 1 : 0);
}

static void nativeSendGamepadAxisValue(JNIEnv* env, jclass clazz, jlong ptr, jint axis,
                                       jint value) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    if (!connection->IsGamepad()) {
        ALOGE("Invalid axis send for non-gamepad device");
        return;
    }

    connection->sendEvent(EV_ABS, axis, value);
}

static void nativeSendPointerDown(JNIEnv* env, jclass clazz, jlong ptr,
        jint pointerId, jint x, jint y) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    if (connection->IsGamepad()) {
        ALOGE("Invalid pointer down event for a gamepad.");
        return;
    }

    int32_t slot = findSlot(pointerId);
    if (slot == SLOT_UNKNOWN) {
        slot = assignSlot(pointerId);
    }
    if (slot != SLOT_UNKNOWN) {
        connection->sendEvent(EV_ABS, ABS_MT_SLOT, slot);
        connection->sendEvent(EV_ABS, ABS_MT_TRACKING_ID, pointerId);
        connection->sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
        connection->sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    }
}

static void nativeSendPointerUp(JNIEnv* env, jclass clazz, jlong ptr,
        jint pointerId) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    if (connection->IsGamepad()) {
        ALOGE("Invalid pointer up event for a gamepad.");
        return;
    }

    int32_t slot = findSlot(pointerId);
    if (slot != SLOT_UNKNOWN) {
        connection->sendEvent(EV_ABS, ABS_MT_SLOT, slot);
        connection->sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        unassignSlot(pointerId);
    }
}

static void nativeSendPointerSync(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);
    connection->sendEvent(EV_SYN, SYN_REPORT, 0);
}

static void nativeClear(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    // Clear keys.
    if (connection->IsRemote()) {
        for (size_t i = 0; i < NELEM(KEYS); i++) {
            connection->sendEvent(EV_KEY, KEYS[i].linuxKeyCode, 0);
        }

        // Clear pointers.
        int32_t slot = SLOT_UNKNOWN;
        for (int32_t i = 0; i < connection->getMaxPointers(); i++) {
            slot = findSlot(i);
            if (slot != SLOT_UNKNOWN) {
                connection->sendEvent(EV_ABS, ABS_MT_SLOT, slot);
                connection->sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            }
        }
    } else {
        for (size_t i = 0; i < NELEM(GAMEPAD_KEY_CODES); i++) {
            connection->sendEvent(EV_KEY, GAMEPAD_KEY_CODES[i], 0);
        }

        for (size_t i = 0; i < NELEM(GAMEPAD_AXES); i++) {
            const Axis& axis = GAMEPAD_AXES[i];
            if ((axis.number == ABS_Z) || (axis.number == ABS_RZ)) {
                // Mark triggers unpressed
                connection->sendEvent(EV_ABS, axis.number, 0);
            } else {
                // Joysticks and dpad rests on center
                connection->sendEvent(EV_ABS, axis.number, (axis.rangeMin + axis.rangeMax) / 2);
            }
        }
    }

    // Sync pointer events
    connection->sendEvent(EV_SYN, SYN_REPORT, 0);
}

/*
 * JNI registration
 */

static JNINativeMethod gUinputBridgeMethods[] = {
        {"nativeOpen", "(Ljava/lang/String;Ljava/lang/String;III)J", (void*)nativeOpen},
        {"nativeGamepadOpen", "(Ljava/lang/String;Ljava/lang/String;)J", (void*)nativeGamepadOpen},
        {"nativeClose", "(J)V", (void*)nativeClose},
        {"nativeSendKey", "(JIZ)V", (void*)nativeSendKey},
        {"nativeSendPointerDown", "(JIII)V", (void*)nativeSendPointerDown},
        {"nativeSendPointerUp", "(JI)V", (void*)nativeSendPointerUp},
        {"nativeClear", "(J)V", (void*)nativeClear},
        {"nativeSendPointerSync", "(J)V", (void*)nativeSendPointerSync},
        {"nativeSendGamepadKey", "(JIZ)V", (void*)nativeSendGamepadKey},
        {"nativeSendGamepadAxisValue", "(JII)V", (void*)nativeSendGamepadAxisValue},
};

int register_android_server_tv_TvUinputBridge(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/tv/UinputBridge",
              gUinputBridgeMethods, NELEM(gUinputBridgeMethods));

    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    return 0;
}

} // namespace android
