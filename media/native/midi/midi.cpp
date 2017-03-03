/*
 * Copyright (C) 2017 The Android Open Source Project
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
#include <utils/Errors.h>
#include <utils/Log.h>

#include "android/media/midi/BpMidiDeviceServer.h"
#include "media/MidiDeviceInfo.h"
#include "MidiDeviceRegistry.h"
#include "MidiPortRegistry.h"

#include "midi.h"

using android::IBinder;
using android::BBinder;
using android::OK;
using android::sp;
using android::status_t;
using android::base::unique_fd;
using android::binder::Status;
using android::media::midi::BpMidiDeviceServer;
using android::media::midi::MidiDeviceInfo;
using android::media::midi::MidiDeviceRegistry;
using android::media::midi::MidiPortRegistry;

#define SIZE_MIDIRECEIVEBUFFER AMIDI_BUFFER_SIZE

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

status_t AMIDI_getDeviceById(int32_t id, AMIDI_Device *devicePtr) {
    return MidiDeviceRegistry::getInstance().obtainDeviceToken(id, devicePtr);
}

status_t AMIDI_getDeviceInfo(AMIDI_Device device, AMIDI_DeviceInfo *deviceInfoPtr) {
    sp<BpMidiDeviceServer> deviceServer;
    status_t result = MidiDeviceRegistry::getInstance().getDeviceByToken(device, &deviceServer);
    if (result != OK) {
        ALOGE("AMIDI_getDeviceInfo bad device token %d: %d", device, result);
        return result;
    }

    MidiDeviceInfo deviceInfo;
    Status txResult = deviceServer->getDeviceInfo(&deviceInfo);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_getDeviceInfo transaction error: %d", txResult.transactionError());
        return txResult.transactionError();
    }

    deviceInfoPtr->type = deviceInfo.getType();
    deviceInfoPtr->uid = deviceInfo.getUid();
    deviceInfoPtr->isPrivate = deviceInfo.isPrivate();
    deviceInfoPtr->inputPortCount = deviceInfo.getInputPortNames().size();
    deviceInfoPtr->outputPortCount = deviceInfo.getOutputPortNames().size();
    return OK;
}

/*
 * Output (receiving) API
 */
status_t AMIDI_openOutputPort(AMIDI_Device device, int portNumber, AMIDI_OutputPort *outputPortPtr) {
    sp<BpMidiDeviceServer> deviceServer;
    status_t result = MidiDeviceRegistry::getInstance().getDeviceByToken(device, &deviceServer);
    if (result != OK) {
        ALOGE("AMIDI_openOutputPort bad device token %d: %d", device, result);
        return result;
    }

    sp<BBinder> portToken(new BBinder());
    unique_fd ufd;
    Status txResult = deviceServer->openOutputPort(portToken, portNumber, &ufd);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_openOutputPort transaction error: %d", txResult.transactionError());
        return txResult.transactionError();
    }

    result = MidiPortRegistry::getInstance().addOutputPort(
            device, portToken, std::move(ufd), outputPortPtr);
    if (result != OK) {
        ALOGE("AMIDI_openOutputPort port registration error: %d", result);
        // Close port
        return result;
    }
    return OK;
}

ssize_t AMIDI_receive(AMIDI_OutputPort outputPort, AMIDI_Message *messages, ssize_t maxMessages) {
    unique_fd *ufd;
    // TODO: May return a nicer self-unlocking object
    status_t result = MidiPortRegistry::getInstance().getOutputPortFdAndLock(outputPort, &ufd);
    if (result != OK) {
        return result;
    }

    ssize_t messagesRead = 0;
    while (messagesRead < maxMessages) {
        struct pollfd checkFds[1] = { { *ufd, POLLIN, 0 } };
        int pollResult = poll(checkFds, 1, 0);
        if (pollResult < 1) {
            result = android::INVALID_OPERATION;
            break;
        }

        AMIDI_Message *message = &messages[messagesRead];
        uint8_t readBuffer[AMIDI_PACKET_SIZE];
        memset(readBuffer, 0, sizeof(readBuffer));
        ssize_t readCount = read(*ufd, readBuffer, sizeof(readBuffer));
        if (readCount == EINTR) {
            continue;
        }
        if (readCount < 1) {
            result = android::NOT_ENOUGH_DATA;
            break;
        }

        // set Packet Format definition at the top of this file.
        size_t dataSize = 0;
        message->opcode = readBuffer[0];
        message->timestamp = 0;
        if (message->opcode == AMIDI_OPCODE_DATA && readCount >= AMIDI_PACKET_OVERHEAD) {
            dataSize = readCount - AMIDI_PACKET_OVERHEAD;
            if (dataSize) {
                memcpy(message->buffer, readBuffer + 1, dataSize);
            }
            message->timestamp = *(uint64_t*) (readBuffer + readCount - sizeof(uint64_t));
        }
        message->len = dataSize;
        ++messagesRead;
    }

    MidiPortRegistry::getInstance().unlockOutputPort(outputPort);
    return result == OK ? messagesRead : result;
}

status_t AMIDI_closeOutputPort(AMIDI_OutputPort outputPort) {
    AMIDI_Device device;
    sp<IBinder> portToken;
    status_t result =
        MidiPortRegistry::getInstance().removeOutputPort(outputPort, &device, &portToken);
    if (result != OK) {
        return result;
    }

    sp<BpMidiDeviceServer> deviceServer;
    result = MidiDeviceRegistry::getInstance().getDeviceByToken(device, &deviceServer);
    if (result != OK) {
        return result;
    }

    Status txResult = deviceServer->closePort(portToken);
    if (!txResult.isOk()) {
        return txResult.transactionError();
    }
    return OK;
}

/*
 * Input (sending) API
 */
status_t AMIDI_openInputPort(AMIDI_Device device, int portNumber, AMIDI_InputPort *inputPortPtr) {
    sp<BpMidiDeviceServer> deviceServer;
    status_t result = MidiDeviceRegistry::getInstance().getDeviceByToken(device, &deviceServer);
    if (result != OK) {
        ALOGE("AMIDI_openInputPort bad device token %d: %d", device, result);
        return result;
    }

    sp<BBinder> portToken(new BBinder());
    unique_fd ufd; // this is the file descriptor of the "receive" port s
    Status txResult = deviceServer->openInputPort(portToken, portNumber, &ufd);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_openInputPort transaction error: %d", txResult.transactionError());
        return txResult.transactionError();
    }

    result = MidiPortRegistry::getInstance().addInputPort(
            device, portToken, std::move(ufd), inputPortPtr);
    if (result != OK) {
        ALOGE("AMIDI_openInputPort port registration error: %d", result);
        // Close port
        return result;
    }

    return OK;
}

status_t AMIDI_closeInputPort(AMIDI_InputPort inputPort) {
    AMIDI_Device device;
    sp<IBinder> portToken;
    status_t result = MidiPortRegistry::getInstance().removeInputPort(
            inputPort, &device, &portToken);
    if (result != OK) {
        ALOGE("AMIDI_closeInputPort remove port error: %d", result);
        return result;
    }

    sp<BpMidiDeviceServer> deviceServer;
    result = MidiDeviceRegistry::getInstance().getDeviceByToken(device, &deviceServer);
    if (result != OK) {
        ALOGE("AMIDI_closeInputPort can't find device error: %d", result);
        return result;
    }

    Status txResult = deviceServer->closePort(portToken);
    if (!txResult.isOk()) {
        result = txResult.transactionError();
        ALOGE("AMIDI_closeInputPort transaction error: %d", result);
        return result;
    }

    return OK;
}

ssize_t AMIDI_getMaxMessageSizeInBytes(AMIDI_InputPort /*inputPort*/) {
    return SIZE_MIDIRECEIVEBUFFER;
}

static ssize_t AMIDI_makeSendBuffer(uint8_t *buffer, uint8_t *data, ssize_t numBytes, uint64_t timestamp) {
    buffer[0] = AMIDI_OPCODE_DATA;
    memcpy(buffer + 1, data, numBytes);
    memcpy(buffer + 1 + numBytes, &timestamp, sizeof(timestamp));
    return numBytes + AMIDI_PACKET_OVERHEAD;
}

// Handy debugging function.
//static void AMIDI_logBuffer(uint8_t *data, size_t numBytes) {
//    for (size_t index = 0; index < numBytes; index++) {
//      ALOGI("  data @%zu [0x%X]", index, data[index]);
//    }
//}

ssize_t AMIDI_send(AMIDI_InputPort inputPort, uint8_t *buffer, ssize_t numBytes) {
    return AMIDI_sendWithTimestamp(inputPort, buffer, numBytes, 0);
}

ssize_t AMIDI_sendWithTimestamp(AMIDI_InputPort inputPort, uint8_t *data,
        ssize_t numBytes, int64_t timestamp) {

    if (numBytes > SIZE_MIDIRECEIVEBUFFER) {
        return android::BAD_VALUE;
    }

    // AMIDI_logBuffer(data, numBytes);

    unique_fd *ufd = NULL;
    status_t result = MidiPortRegistry::getInstance().getInputPortFd(inputPort, &ufd);
    if (result != OK) {
        return result;
    }

    uint8_t writeBuffer[SIZE_MIDIRECEIVEBUFFER + AMIDI_PACKET_OVERHEAD];
    ssize_t numTransferBytes = AMIDI_makeSendBuffer(writeBuffer, data, numBytes, timestamp);
    ssize_t numWritten = write(*ufd, writeBuffer, numTransferBytes);

    if (numWritten < numTransferBytes) {
        ALOGE("AMIDI_sendWithTimestamp Couldn't write MIDI data buffer. requested:%zu, written%zu",
                numTransferBytes, numWritten);
    }

    return numWritten - AMIDI_PACKET_OVERHEAD;
}

status_t AMIDI_flush(AMIDI_InputPort inputPort) {
    unique_fd *ufd = NULL;
    status_t result = MidiPortRegistry::getInstance().getInputPortFd(inputPort, &ufd);
    if (result != OK) {
        return result;
    }

    uint8_t opCode = AMIDI_OPCODE_FLUSH;
    ssize_t numTransferBytes = 1;
    ssize_t numWritten = write(*ufd, &opCode, numTransferBytes);

    if (numWritten < numTransferBytes) {
        ALOGE("AMIDI_flush Couldn't write MIDI flush. requested:%zu, written%zu",
                numTransferBytes, numWritten);
        return android::INVALID_OPERATION;
    }

    return OK;
}

