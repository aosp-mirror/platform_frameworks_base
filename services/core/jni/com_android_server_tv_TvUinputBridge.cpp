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

// Refer to EventHub.h
#define MSC_ANDROID_TIME_SEC 0x6
#define MSC_ANDROID_TIME_USEC 0x7

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

class NativeConnection {
public:
    ~NativeConnection();

    static NativeConnection* open(const char* name, const char* uniqueId,
            int32_t width, int32_t height, int32_t maxPointerId);

    void sendEvent(int32_t type, int32_t code, int32_t value);

    int32_t getMaxPointers() const { return mMaxPointers; }

private:
    NativeConnection(int fd, int32_t maxPointers);

    const int mFd;
    const int32_t mMaxPointers;
};

NativeConnection::NativeConnection(int fd, int32_t maxPointers) :
        mFd(fd), mMaxPointers(maxPointers) {
}

NativeConnection::~NativeConnection() {
    ALOGI("Un-Registering uinput device %d.", mFd);
    ioctl(mFd, UI_DEV_DESTROY);
    close(mFd);
}

NativeConnection* NativeConnection::open(const char* name, const char* uniqueId,
        int32_t width, int32_t height, int32_t maxPointers) {
    ALOGI("Registering uinput device %s: touch pad size %dx%d, "
            "max pointers %d.", name, width, height, maxPointers);

    int fd = ::open("/dev/uinput", O_WRONLY | O_NDELAY);
    if (fd < 0) {
        ALOGE("Cannot open /dev/uinput: %s.", strerror(errno));
        return nullptr;
    }

    struct uinput_user_dev uinp;
    memset(&uinp, 0, sizeof(struct uinput_user_dev));
    strlcpy(uinp.name, name, UINPUT_MAX_NAME_SIZE);
    uinp.id.version = 1;
    uinp.id.bustype = BUS_VIRTUAL;

    // initialize keymap
    initKeysMap();

    // write device unique id to the phys property
    ioctl(fd, UI_SET_PHYS, uniqueId);

    // set the keys mapped
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (size_t i = 0; i < NELEM(KEYS); i++) {
        ioctl(fd, UI_SET_KEYBIT, KEYS[i].linuxKeyCode);
    }

    // set the misc events maps
    ioctl(fd, UI_SET_EVBIT, EV_MSC);
    ioctl(fd, UI_SET_MSCBIT, MSC_ANDROID_TIME_SEC);
    ioctl(fd, UI_SET_MSCBIT, MSC_ANDROID_TIME_USEC);

    // register the input device
    if (write(fd, &uinp, sizeof(uinp)) != sizeof(uinp)) {
        ALOGE("Cannot write uinput_user_dev to fd %d: %s.", fd, strerror(errno));
        close(fd);
        return NULL;
    }
    if (ioctl(fd, UI_DEV_CREATE) != 0) {
        ALOGE("Unable to create uinput device: %s.", strerror(errno));
        close(fd);
        return nullptr;
    }

    ALOGV("Created uinput device, fd=%d.", fd);
    return new NativeConnection(fd, maxPointers);
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

static void nativeClose(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);
    delete connection;
}

static void nativeSendTimestamp(JNIEnv* env, jclass clazz, jlong ptr, jlong timestamp) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

    connection->sendEvent(EV_MSC, MSC_ANDROID_TIME_SEC, timestamp / 1000L);
    connection->sendEvent(EV_MSC, MSC_ANDROID_TIME_USEC, (timestamp % 1000L) * 1000L);
}

static void nativeSendKey(JNIEnv* env, jclass clazz, jlong ptr, jint keyCode, jboolean down) {
    int32_t code = getLinuxKeyCode(keyCode);
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);
    if (code != KEY_UNKNOWN) {
        connection->sendEvent(EV_KEY, code, down ? 1 : 0);
    } else {
        ALOGE("Received an unknown keycode of %d.", keyCode);
    }
}

static void nativeSendPointerDown(JNIEnv* env, jclass clazz, jlong ptr,
        jint pointerId, jint x, jint y) {
    NativeConnection* connection = reinterpret_cast<NativeConnection*>(ptr);

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

    // Sync pointer events
    connection->sendEvent(EV_SYN, SYN_REPORT, 0);
}

/*
 * JNI registration
 */

static JNINativeMethod gUinputBridgeMethods[] = {
    { "nativeOpen", "(Ljava/lang/String;Ljava/lang/String;III)J",
        (void*)nativeOpen },
    { "nativeClose", "(J)V",
        (void*)nativeClose },
    { "nativeSendTimestamp", "(JJ)V",
        (void*)nativeSendTimestamp },
    { "nativeSendKey", "(JIZ)V",
        (void*)nativeSendKey },
    { "nativeSendPointerDown", "(JIII)V",
        (void*)nativeSendPointerDown },
    { "nativeSendPointerUp", "(JI)V",
        (void*)nativeSendPointerUp },
    { "nativeClear", "(J)V",
        (void*)nativeClear },
    { "nativeSendPointerSync", "(J)V",
        (void*)nativeSendPointerSync },
};

int register_android_server_tv_TvUinputBridge(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/tv/UinputBridge",
              gUinputBridgeMethods, NELEM(gUinputBridgeMethods));

    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    (void)res; // Don't complain about unused variable in the LOG_NDEBUG case

    return 0;
}

} // namespace android
