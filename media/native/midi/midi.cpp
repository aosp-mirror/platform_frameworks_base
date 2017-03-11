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

#include "midi.h"
#include "midi_internal.h"

using android::IBinder;
using android::BBinder;
using android::OK;
using android::sp;
using android::status_t;
using android::base::unique_fd;
using android::binder::Status;
using android::media::midi::MidiDeviceInfo;

struct AMIDI_Port {
    std::atomic_int state;
    AMIDI_Device    *device;
    sp<IBinder>     binderToken;
    unique_fd       ufd;
};

#define SIZE_MIDIRECEIVEBUFFER AMIDI_BUFFER_SIZE

enum {
    MIDI_PORT_STATE_CLOSED = 0,
    MIDI_PORT_STATE_OPEN_IDLE,
    MIDI_PORT_STATE_OPEN_ACTIVE
};

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

/*
 * Device Functions
 */
status_t AMIDI_getDeviceInfo(AMIDI_Device *device, AMIDI_DeviceInfo *deviceInfoPtr) {
    MidiDeviceInfo deviceInfo;
    Status txResult = device->server->getDeviceInfo(&deviceInfo);
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
 * Port Helpers
 */
static status_t AMIDI_openPort(AMIDI_Device *device, int portNumber, int type,
        AMIDI_Port **portPtr) {
    sp<BBinder> portToken(new BBinder());
    unique_fd ufd;
    Status txResult = type == PORTTYPE_OUTPUT
            ? device->server->openOutputPort(portToken, portNumber, &ufd)
            : device->server->openInputPort(portToken, portNumber, &ufd);
    if (!txResult.isOk()) {
        ALOGE("AMIDI_openPort transaction error: %d", txResult.transactionError());
        return txResult.transactionError();
    }

    AMIDI_Port* port = new AMIDI_Port;
    port->state = MIDI_PORT_STATE_OPEN_IDLE;
    port->device = device;
    port->binderToken = portToken;
    port->ufd = std::move(ufd);

    *portPtr = port;

    return OK;
}

static status_t AMIDI_closePort(AMIDI_Port *port) {
    int portState = MIDI_PORT_STATE_OPEN_IDLE;
    while (!port->state.compare_exchange_weak(portState, MIDI_PORT_STATE_CLOSED)) {
        if (portState == MIDI_PORT_STATE_CLOSED) {
            return -EINVAL; // Already closed
        }
    }

    Status txResult = port->device->server->closePort(port->binderToken);
    if (!txResult.isOk()) {
        return txResult.transactionError();
    }

    delete port;

    return OK;
}

/*
 * Output (receiving) API
 */
status_t AMIDI_openOutputPort(AMIDI_Device *device, int portNumber,
        AMIDI_OutputPort **outputPortPtr) {
    return AMIDI_openPort(device, portNumber, PORTTYPE_OUTPUT, (AMIDI_Port**)outputPortPtr);
}

ssize_t AMIDI_receive(AMIDI_OutputPort *outputPort, AMIDI_Message *messages, ssize_t maxMessages) {
    AMIDI_Port *port = (AMIDI_Port*)outputPort;
    int portState = MIDI_PORT_STATE_OPEN_IDLE;
    if (!port->state.compare_exchange_strong(portState, MIDI_PORT_STATE_OPEN_ACTIVE)) {
        // The port has been closed.
        return -EPIPE;
    }

    status_t result = OK;
    ssize_t messagesRead = 0;
    while (messagesRead < maxMessages) {
        struct pollfd checkFds[1] = { { port->ufd, POLLIN, 0 } };
        int pollResult = poll(checkFds, 1, 0);
        if (pollResult < 1) {
            result = android::INVALID_OPERATION;
            break;
        }

        AMIDI_Message *message = &messages[messagesRead];
        uint8_t readBuffer[AMIDI_PACKET_SIZE];
        memset(readBuffer, 0, sizeof(readBuffer));
        ssize_t readCount = read(port->ufd, readBuffer, sizeof(readBuffer));
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
            message->timestamp = *(uint64_t*)(readBuffer + readCount - sizeof(uint64_t));
        }
        message->len = dataSize;
        ++messagesRead;
    }

    port->state.store(MIDI_PORT_STATE_OPEN_IDLE);

    return result == OK ? messagesRead : result;
}

status_t AMIDI_closeOutputPort(AMIDI_OutputPort *outputPort) {
    return AMIDI_closePort((AMIDI_Port*)outputPort);
}

/*
 * Input (sending) API
 */
status_t AMIDI_openInputPort(AMIDI_Device *device, int portNumber, AMIDI_InputPort **inputPortPtr) {
    return AMIDI_openPort(device, portNumber, PORTTYPE_INPUT, (AMIDI_Port**)inputPortPtr);
}

status_t AMIDI_closeInputPort(AMIDI_InputPort *inputPort) {
    return AMIDI_closePort((AMIDI_Port*)inputPort);
}

ssize_t AMIDI_getMaxMessageSizeInBytes(AMIDI_InputPort */*inputPort*/) {
    return SIZE_MIDIRECEIVEBUFFER;
}

static ssize_t AMIDI_makeSendBuffer(
        uint8_t *buffer, uint8_t *data, ssize_t numBytes,uint64_t timestamp) {
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

ssize_t AMIDI_send(AMIDI_InputPort *inputPort, uint8_t *buffer, ssize_t numBytes) {
    return AMIDI_sendWithTimestamp(inputPort, buffer, numBytes, 0);
}

ssize_t AMIDI_sendWithTimestamp(AMIDI_InputPort *inputPort, uint8_t *data,
        ssize_t numBytes, int64_t timestamp) {

    if (numBytes > SIZE_MIDIRECEIVEBUFFER) {
        return android::BAD_VALUE;
    }

    // AMIDI_logBuffer(data, numBytes);

    uint8_t writeBuffer[SIZE_MIDIRECEIVEBUFFER + AMIDI_PACKET_OVERHEAD];
    ssize_t numTransferBytes = AMIDI_makeSendBuffer(writeBuffer, data, numBytes, timestamp);
    ssize_t numWritten = write(((AMIDI_Port*)inputPort)->ufd, writeBuffer, numTransferBytes);

    if (numWritten < numTransferBytes) {
        ALOGE("AMIDI_sendWithTimestamp Couldn't write MIDI data buffer. requested:%zu, written%zu",
                numTransferBytes, numWritten);
    }

    return numWritten - AMIDI_PACKET_OVERHEAD;
}

status_t AMIDI_flush(AMIDI_InputPort *inputPort) {
    uint8_t opCode = AMIDI_OPCODE_FLUSH;
    ssize_t numTransferBytes = 1;
    ssize_t numWritten = write(((AMIDI_Port*)inputPort)->ufd, &opCode, numTransferBytes);

    if (numWritten < numTransferBytes) {
        ALOGE("AMIDI_flush Couldn't write MIDI flush. requested:%zu, written%zu",
                numTransferBytes, numWritten);
        return android::INVALID_OPERATION;
    }

    return OK;
}

