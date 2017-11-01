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

#ifndef ANDROID_MEDIA_MIDI_H_
#define ANDROID_MEDIA_MIDI_H_

#include <stdarg.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>

using android::status_t;

#ifdef __cplusplus
extern "C" {
#endif

struct AMIDI_Device;
struct AMIDI_InputPort;
struct AMIDI_OutputPort;

#define AMIDI_INVALID_HANDLE NULL

enum {
    AMIDI_OPCODE_DATA = 1,
    AMIDI_OPCODE_FLUSH = 2,
    AMIDI_PACKET_SIZE = 1024,  /* !!! Currently MidiPortImpl.MAX_PACKET_SIZE !!! */
    AMIDI_PACKET_OVERHEAD = 9,
    AMIDI_BUFFER_SIZE = AMIDI_PACKET_SIZE - AMIDI_PACKET_OVERHEAD
            /* !!! TBD, currently MidiPortImpl.MAX_PACKET_DATA_SIZE !!! */
};

typedef struct {
    uint32_t    opcode;
    uint8_t     buffer[AMIDI_BUFFER_SIZE];
    size_t      len;
    int64_t     timestamp;
} AMIDI_Message;

enum {
    AMIDI_DEVICE_TYPE_USB = 1,
    AMIDI_DEVICE_TYPE_VIRTUAL = 2,
    AMIDI_DEVICE_TYPE_BLUETOOTH = 3
};

typedef struct {
    int32_t type;
    int32_t uid;
    int32_t isPrivate;
    int32_t inputPortCount;
    int32_t outputPortCount;
} AMIDI_DeviceInfo;

/*
 * Device API
 */
/*
 * Retrieves information for the native MIDI device.
 *
 * device           The Native API token for the device.
 * deviceInfoPtr    Receives the associated device info.
 *
 * Returns OK or a (negative) error code.
 */
status_t AMIDI_getDeviceInfo(AMIDI_Device *device, AMIDI_DeviceInfo *deviceInfoPtr);

/*
 * API for receiving data from the Output port of a device.
 */
/*
 * Opens the output port.
 *
 * device           Identifies the device.
 * portNumber       Specifies the zero-based port index on the device to open.
 * outputPortPtr    Receives the native API port identifier of the opened port.
 *
 * Returns OK, or a (negative) error code.
 */
status_t AMIDI_openOutputPort(AMIDI_Device *device, int portNumber,
        AMIDI_OutputPort **outputPortPtr);

/*
 * Receives any pending MIDI messages (up to the specified maximum number of messages).
 *
 * outputPort   Identifies the port to receive messages from.
 * messages     Points to an array (size maxMessages) to receive the MIDI messages.
 * maxMessages  The number of messages allocated in the messages array.
 *
 * Returns the number of messages received, or a (negative) error code.
 */
ssize_t AMIDI_receive(AMIDI_OutputPort *outputPort, AMIDI_Message *messages, ssize_t maxMessages);

/*
 * Closes the output port.
 *
 * outputPort   The native API port identifier of the port.
 *
 * Returns OK, or a (negative) error code.
 */
status_t AMIDI_closeOutputPort(AMIDI_OutputPort *outputPort);

/*
 * API for sending data to the Input port of a device.
 */
/*
 * Opens the input port.
 *
 * device           Identifies the device.
 * portNumber       Specifies the zero-based port index on the device to open.
 * inputPortPtr     Receives the native API port identifier of the opened port.
 *
 * Returns OK, or a (negative) error code.
 */
status_t AMIDI_openInputPort(AMIDI_Device *device, int portNumber, AMIDI_InputPort **inputPortPtr);

/*
 * Returns the maximum number of bytes that can be received in a single MIDI message.
 */
ssize_t AMIDI_getMaxMessageSizeInBytes(AMIDI_InputPort *inputPort);

/*
 * Sends data to the specified input port.
 *
 * inputPort    The native API identifier of the port to send data to.
 * buffer       Points to the array of bytes containing the data to send.
 * numBytes     Specifies the number of bytes to write.
 *
 * Returns  The number of bytes sent or a (negative) error code.
 */
ssize_t AMIDI_send(AMIDI_InputPort *inputPort, uint8_t *buffer, ssize_t numBytes);

/*
 * Sends data to the specified input port with a timestamp.
 *
 * inputPort    The native API identifier of the port to send data to.
 * buffer       Points to the array of bytes containing the data to send.
 * numBytes     Specifies the number of bytes to write.
 * timestamp    The time stamp to associate with the sent data.
 *
 * Returns  The number of bytes sent or a (negative) error code.
 */
ssize_t AMIDI_sendWithTimestamp(AMIDI_InputPort *inputPort, uint8_t *buffer,
        ssize_t numBytes, int64_t timestamp);

/*
 * Sends a message with a 'MIDI flush command code' to the specified port.
 *
 * inputPort    The native API identifier of the port to send the flush message to.
 *
 * Returns OK, or a (negative) error code.
 */
status_t AMIDI_flush(AMIDI_InputPort *inputPort);

/*
 * Closes the input port.
 *
 * inputPort   The native API port identifier of the port.
 *
 *
 * Returns OK, or a (negative) error code.
 */
status_t AMIDI_closeInputPort(AMIDI_InputPort *inputPort);

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_MEDIA_MIDI_H_ */
