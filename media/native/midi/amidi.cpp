/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_TAG "NativeMIDI"

#include <poll.h>
#include <unistd.h>

#include <binder/Binder.h>
#include <android_util_Binder.h>
#include <utils/Log.h>

#include <core_jni_helpers.h>

#include "android/media/midi/BpMidiDeviceServer.h"
#include "MidiDeviceInfo.h"

#include "include/amidi/AMidi.h"
#include "amidi_internal.h"

using namespace android::media::midi;

using android::IBinder;
using android::BBinder;
using android::OK;
using android::sp;
using android::status_t;
using android::base::unique_fd;
using android::binder::Status;

struct AMIDI_Port {
    std::atomic_int     state;      // One of the port status constants below.
    const AMidiDevice  *device;    // Points to the AMidiDevice associated with the port.
    sp<IBinder>         binderToken;// The Binder token associated with the port.
    unique_fd           ufd;        // The unique file descriptor associated with the port.
};

/*
 * Port Status Constants
 */
enum {
    MIDI_PORT_STATE_CLOSED = 0,
    MIDI_PORT_STATE_OPEN_IDLE,
    MIDI_PORT_STATE_OPEN_ACTIVE
};

/*
 * Port Type Constants
 */
enum {
    PORTTYPE_OUTPUT = 0,
    PORTTYPE_INPUT = 1
};

/* TRANSFER PACKET FORMAT (as defined in MidiPortImpl.java)
 *
 * Transfer packet format is as follows (see MidiOutputPort.mThread.run() to see decomposition):
 * |oc|md|md| ......... |md|ts|ts|ts|ts|ts|ts|ts|ts|
 *  ^ +--------------------+-----------------------+
 *  |  ^                    ^
 *  |  |                    |
 *  |  |                    + timestamp (8 bytes)
 *  |  |
 *  |  + MIDI data bytes (numBytes bytes)
 *  |
 *  + OpCode (AMIDI_OPCODE_DATA)
 *
 *  NOTE: The socket pair is configured to use SOCK_SEQPACKET mode.
 *  SOCK_SEQPACKET, for a sequenced-packet socket that is connection-oriented, preserves message
 *  boundaries, and delivers messages in the order that they were sent.
 *  So 'read()' always returns a whole message.
 */
#define AMIDI_PACKET_SIZE       1024
#define AMIDI_PACKET_OVERHEAD   9
#define AMIDI_BUFFER_SIZE       (AMIDI_PACKET_SIZE - AMIDI_PACKET_OVERHEAD)

// JNI IDs (see android_media_midi.cpp)
namespace android { namespace midi {
//  MidiDevice Fields
extern jfieldID gFidMidiNativeHandle;         // MidiDevice.mNativeHandle
extern jfieldID gFidMidiDeviceServerBinder;   // MidiDevice.mDeviceServerBinder
extern jfieldID gFidMidiDeviceInfo;           // MidiDevice.mDeviceInfo

//  MidiDeviceInfo Fields
extern jfieldID mFidMidiDeviceId;             // MidiDeviceInfo.mId
}}
using namespace android::midi;

static std::mutex openMutex; // Ensure that the device can be connected just once to 1 thread

//// Handy debugging function.
//static void AMIDI_logBuffer(const uint8_t *data, size_t numBytes) {
//    for (size_t index = 0; index < numBytes; index++) {
//      ALOGI("  data @%zu [0x%X]", index, data[index]);
//    }
//}

/*
 * Device Functions
 */
/**
 * Retrieves information for the native MIDI device.
 *
 * device           The Native API token for the device. This value is obtained from the
 *                  AMidiDevice_fromJava().
 * outDeviceInfoPtr Receives the associated device info.
 *
 * Returns AMEDIA_OK or a negative error code.
 *  - AMEDIA_ERROR_INVALID_PARAMETER
 *  AMEDIA_ERROR_UNKNOWN
 */
static media_status_t AMIDI_getDeviceInfo(const AMidiDevice *device,
        AMidiDeviceInfo *outDeviceInfoPtr) {
    if (device == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    MidiDeviceInfo deviceInfo;
    Status txResult = device->server->getDeviceInfo(&deviceInfo);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_getDeviceInfo transaction error: %d", txResult.transactionError());
        return AMEDIA_ERROR_UNKNOWN;
    }

    outDeviceInfoPtr->type = deviceInfo.getType();
    outDeviceInfoPtr->inputPortCount = deviceInfo.getInputPortNames().size();
    outDeviceInfoPtr->outputPortCount = deviceInfo.getOutputPortNames().size();

    return AMEDIA_OK;
}

media_status_t AMIDI_API AMidiDevice_fromJava(JNIEnv *env, jobject j_midiDeviceObj,
        AMidiDevice** devicePtrPtr)
{
    if (j_midiDeviceObj == nullptr) {
        ALOGE("AMidiDevice_fromJava() invalid MidiDevice object.");
        return AMEDIA_ERROR_INVALID_OBJECT;
    }

    {
        std::lock_guard<std::mutex> guard(openMutex);

        long handle = env->GetLongField(j_midiDeviceObj, gFidMidiNativeHandle);
        if (handle != 0) {
            // Already opened by someone.
            return AMEDIA_ERROR_INVALID_OBJECT;
        }

        jobject serverBinderObj = env->GetObjectField(j_midiDeviceObj, gFidMidiDeviceServerBinder);
        sp<IBinder> serverBinder = android::ibinderForJavaObject(env, serverBinderObj);
        if (serverBinder.get() == nullptr) {
            ALOGE("AMidiDevice_fromJava couldn't connect to native MIDI server.");
            return AMEDIA_ERROR_UNKNOWN;
        }

        // don't check allocation failures, just abort..
        AMidiDevice* devicePtr = new AMidiDevice;
        devicePtr->server = new BpMidiDeviceServer(serverBinder);
        jobject midiDeviceInfoObj = env->GetObjectField(j_midiDeviceObj, gFidMidiDeviceInfo);
        devicePtr->deviceId = env->GetIntField(midiDeviceInfoObj, mFidMidiDeviceId);

        // Synchronize with the associated Java MidiDevice.
        env->SetLongField(j_midiDeviceObj, gFidMidiNativeHandle, (long)devicePtr);
        env->GetJavaVM(&devicePtr->javaVM);
        devicePtr->midiDeviceObj = env->NewGlobalRef(j_midiDeviceObj);

        if (AMIDI_getDeviceInfo(devicePtr, &devicePtr->deviceInfo) != AMEDIA_OK) {
            // This is weird, but maybe not fatal?
            ALOGE("AMidiDevice_fromJava couldn't retrieve attributes of native device.");
        }

        *devicePtrPtr = devicePtr;
    }

    return AMEDIA_OK;
}

media_status_t AMIDI_API AMidiDevice_release(const AMidiDevice *device)
{
    if (device == nullptr || device->midiDeviceObj == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    JNIEnv* env;
    jint err = device->javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    LOG_ALWAYS_FATAL_IF(err != JNI_OK, "AMidiDevice_release Error accessing JNIEnv err:%d", err);

    // Synchronize with the associated Java MidiDevice.
    {
        std::lock_guard<std::mutex> guard(openMutex);
        long handle = env->GetLongField(device->midiDeviceObj, gFidMidiNativeHandle);
        if (handle == 0) {
            // Not opened as native.
            ALOGE("AMidiDevice_release() device not opened in native client.");
            return AMEDIA_ERROR_INVALID_OBJECT;
        }

        env->SetLongField(device->midiDeviceObj, gFidMidiNativeHandle, 0L);
    }
    env->DeleteGlobalRef(device->midiDeviceObj);

    delete device;

    return AMEDIA_OK;
}

int32_t AMIDI_API AMidiDevice_getType(const AMidiDevice *device) {
    if (device == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    return device->deviceInfo.type;
}

ssize_t AMIDI_API AMidiDevice_getNumInputPorts(const AMidiDevice *device) {
    if (device == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    return device->deviceInfo.inputPortCount;
}

ssize_t AMIDI_API AMidiDevice_getNumOutputPorts(const AMidiDevice *device) {
    if (device == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    return device->deviceInfo.outputPortCount;
}

/*
 * Port Helpers
 */
static media_status_t AMIDI_openPort(const AMidiDevice *device, int32_t portNumber, int type,
        AMIDI_Port **portPtr) {
    if (device == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    sp<BBinder> portToken(new BBinder());
    unique_fd ufd;
    Status txResult = type == PORTTYPE_OUTPUT
            ? device->server->openOutputPort(portToken, portNumber, &ufd)
            : device->server->openInputPort(portToken, portNumber, &ufd);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_openPort transaction error: %d", txResult.transactionError());
        return AMEDIA_ERROR_UNKNOWN;
    }

    AMIDI_Port *port = new AMIDI_Port;
    port->state = MIDI_PORT_STATE_OPEN_IDLE;
    port->device = device;
    port->binderToken = portToken;
    port->ufd = std::move(ufd);

    *portPtr = port;

    return AMEDIA_OK;
}

static void AMIDI_closePort(AMIDI_Port *port) {
    if (port == nullptr) {
        return;
    }

    int portState = MIDI_PORT_STATE_OPEN_IDLE;
    while (!port->state.compare_exchange_weak(portState, MIDI_PORT_STATE_CLOSED)) {
        if (portState == MIDI_PORT_STATE_CLOSED) {
            return; // Already closed
        }
    }

    Status txResult = port->device->server->closePort(port->binderToken);
    if (!txResult.isOk()) {
        ALOGE("Transaction error closing MIDI port:%d", txResult.transactionError());
    }

    delete port;
}

/*
 * Output (receiving) API
 */
media_status_t AMIDI_API AMidiOutputPort_open(const AMidiDevice *device, int32_t portNumber,
        AMidiOutputPort **outOutputPortPtr) {
    return AMIDI_openPort(device, portNumber, PORTTYPE_OUTPUT, (AMIDI_Port**)outOutputPortPtr);
}

/*
 *  A little RAII (https://en.wikipedia.org/wiki/Resource_acquisition_is_initialization)
 *  class to ensure that the port state is correct irrespective of errors.
 */
class MidiReceiver {
public:
    MidiReceiver(AMIDI_Port *port) : mPort(port) {}

    ~MidiReceiver() {
        // flag the port state to idle
        mPort->state.store(MIDI_PORT_STATE_OPEN_IDLE);
    }

    ssize_t receive(int32_t *opcodePtr, uint8_t *buffer, size_t maxBytes,
            size_t *numBytesReceivedPtr, int64_t *timestampPtr) {
        int portState = MIDI_PORT_STATE_OPEN_IDLE;
        // check to see if the port is idle, then set to active
        if (!mPort->state.compare_exchange_strong(portState, MIDI_PORT_STATE_OPEN_ACTIVE)) {
            // The port not idle or has been closed.
            return AMEDIA_ERROR_UNKNOWN;
        }

        struct pollfd checkFds[1] = { { mPort->ufd, POLLIN, 0 } };
        if (poll(checkFds, 1, 0) < 1) {
            // Nothing there
            return 0;
        }

        uint8_t readBuffer[AMIDI_PACKET_SIZE];
        ssize_t readCount = read(mPort->ufd, readBuffer, sizeof(readBuffer));
        if (readCount == EINTR || readCount < 1) {
            return  AMEDIA_ERROR_UNKNOWN;
        }

        // see Packet Format definition at the top of this file.
        size_t numMessageBytes = 0;
        *opcodePtr = readBuffer[0];
        if (*opcodePtr == AMIDI_OPCODE_DATA && readCount >= AMIDI_PACKET_OVERHEAD) {
            numMessageBytes = readCount - AMIDI_PACKET_OVERHEAD;
            numMessageBytes = std::min(maxBytes, numMessageBytes);
            memcpy(buffer, readBuffer + 1, numMessageBytes);
            if (timestampPtr != nullptr) {
                memcpy(timestampPtr, readBuffer + readCount - sizeof(uint64_t),
                        sizeof(*timestampPtr));
            }
        }
        *numBytesReceivedPtr = numMessageBytes;
        return 1;
    }

private:
    AMIDI_Port *mPort;
};

ssize_t AMIDI_API AMidiOutputPort_receive(const AMidiOutputPort *outputPort, int32_t *opcodePtr,
         uint8_t *buffer, size_t maxBytes, size_t* numBytesReceivedPtr, int64_t *timestampPtr) {

    if (outputPort == nullptr || buffer == nullptr) {
        return -EINVAL;
    }

   return MidiReceiver((AMIDI_Port*)outputPort).receive(opcodePtr, buffer, maxBytes,
           numBytesReceivedPtr, timestampPtr);
}

void AMIDI_API AMidiOutputPort_close(const AMidiOutputPort *outputPort) {
    AMIDI_closePort((AMIDI_Port*)outputPort);
}

/*
 * Input (sending) API
 */
media_status_t AMIDI_API AMidiInputPort_open(const AMidiDevice *device, int32_t portNumber,
        AMidiInputPort **outInputPortPtr) {
    return AMIDI_openPort(device, portNumber, PORTTYPE_INPUT, (AMIDI_Port**)outInputPortPtr);
}

void AMIDI_API AMidiInputPort_close(const AMidiInputPort *inputPort) {
    AMIDI_closePort((AMIDI_Port*)inputPort);
}

static ssize_t AMIDI_makeSendBuffer(
        uint8_t *buffer, const uint8_t *data, size_t numBytes, uint64_t timestamp) {
    // Error checking will happen in the caller since this isn't an API function.
    buffer[0] = AMIDI_OPCODE_DATA;
    memcpy(buffer + 1, data, numBytes);
    memcpy(buffer + 1 + numBytes, &timestamp, sizeof(timestamp));
    return numBytes + AMIDI_PACKET_OVERHEAD;
}

ssize_t AMIDI_API AMidiInputPort_send(const AMidiInputPort *inputPort, const uint8_t *buffer,
                            size_t numBytes) {
    return AMidiInputPort_sendWithTimestamp(inputPort, buffer, numBytes, 0);
}

ssize_t AMIDI_API AMidiInputPort_sendWithTimestamp(const AMidiInputPort *inputPort,
        const uint8_t *data, size_t numBytes, int64_t timestamp) {
    if (inputPort == nullptr || data == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    // AMIDI_logBuffer(data, numBytes);

    uint8_t writeBuffer[AMIDI_BUFFER_SIZE + AMIDI_PACKET_OVERHEAD];
    size_t numSent = 0;
    while (numSent < numBytes) {
        size_t blockSize = AMIDI_BUFFER_SIZE;
        blockSize = std::min(blockSize, numBytes - numSent);

        ssize_t numTransferBytes =
                AMIDI_makeSendBuffer(writeBuffer, data + numSent, blockSize, timestamp);
        ssize_t numWritten = write(((AMIDI_Port*)inputPort)->ufd, writeBuffer, numTransferBytes);
        if (numWritten < 0) {
            break;  // error so bail out.
        }
        if (numWritten < numTransferBytes) {
            ALOGE("AMidiInputPort_sendWithTimestamp Couldn't write MIDI data buffer."
                  " requested:%zu, written%zu",numTransferBytes, numWritten);
            break;  // bail
        }

        numSent += numWritten  - AMIDI_PACKET_OVERHEAD;
    }

    return numSent;
}

media_status_t AMIDI_API AMidiInputPort_sendFlush(const AMidiInputPort *inputPort) {
    if (inputPort == nullptr) {
        return AMEDIA_ERROR_INVALID_PARAMETER;
    }

    uint8_t opCode = AMIDI_OPCODE_FLUSH;
    ssize_t numTransferBytes = 1;
    ssize_t numWritten = write(((AMIDI_Port*)inputPort)->ufd, &opCode, numTransferBytes);

    if (numWritten < numTransferBytes) {
        ALOGE("AMidiInputPort_flush Couldn't write MIDI flush. requested:%zd, written:%zd",
                numTransferBytes, numWritten);
        return AMEDIA_ERROR_UNSUPPORTED;
    }

    return AMEDIA_OK;
}

