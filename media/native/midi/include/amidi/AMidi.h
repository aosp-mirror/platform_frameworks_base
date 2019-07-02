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
/**
 * @addtogroup Midi
 * @{
 */

/**
 * @file AMidi.h
 */

#ifndef ANDROID_MEDIA_AMIDI_H_
#define ANDROID_MEDIA_AMIDI_H_

#include <stdarg.h>
#include <stdint.h>
#include <sys/types.h>

#include <jni.h>

#include <media/NdkMediaError.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AMidiDevice AMidiDevice;
typedef struct AMidiInputPort AMidiInputPort;
typedef struct AMidiOutputPort AMidiOutputPort;

#define AMIDI_API __attribute__((visibility("default")))

/*
 * Message Op Codes. Used to parse MIDI data packets
 */
enum {
    AMIDI_OPCODE_DATA = 1,      /* The MIDI packet contains normal MIDI data */
    AMIDI_OPCODE_FLUSH = 2,     /* The MIDI packet contains just a MIDI FLUSH command. */
                                /* Forces the send of any pending MIDI data. */
};

/*
 * Type IDs for various MIDI devices.
 */
enum {
    AMIDI_DEVICE_TYPE_USB = 1,      /* A MIDI device connected to the Android USB port */
    AMIDI_DEVICE_TYPE_VIRTUAL = 2,  /* A software object implementing MidiDeviceService */
    AMIDI_DEVICE_TYPE_BLUETOOTH = 3 /* A MIDI device connected via BlueTooth */
};

/*
 * Device API
 */
/**
 * Connects a native Midi Device object to the associated Java MidiDevice object. Use this
 * AMidiDevice to access the rest of the native MIDI API. Use AMidiDevice_release() to
 * disconnect from the Java object when not being used any more.
 *
 * @param env   Points to the Java Environment.
 * @param midiDeviceObj   The Java MidiDevice Object.
 * @param outDevicePtrPtr  Points to the pointer to receive the AMidiDevice
 *
 * @return AMEDIA_OK on success, or a negative error value:
 *  @see AMEDIA_ERROR_INVALID_OBJECT - the midiDeviceObj
 *    is null or already connected to a native AMidiDevice
  *  @see AMEDIA_ERROR_UNKNOWN - an unknown error occurred.
 */
media_status_t AMIDI_API AMidiDevice_fromJava(
        JNIEnv *env, jobject midiDeviceObj, AMidiDevice **outDevicePtrPtr) __INTRODUCED_IN(29);

/**
 * Disconnects the native Midi Device Object from the associated Java MidiDevice object.
 *
 * @param midiDevice Points to the native AMIDI_MidiDevice.
 *
 * @return AMEDIA_OK on success,
 * or a negative error value:
 *  @see AMEDIA_ERROR_INVALID_PARAMETER - the device parameter is NULL.
 *  @see AMEDIA_ERROR_INVALID_OBJECT - the device is not consistent with the associated Java MidiDevice.
 *  @see AMEDIA_ERROR_INVALID_OBJECT - the JNI interface initialization to the associated java MidiDevice failed.
 *  @see AMEDIA_ERROR_UNKNOWN - couldn't retrieve the device info.
 */
media_status_t AMIDI_API AMidiDevice_release(const AMidiDevice *midiDevice) __INTRODUCED_IN(29);

/**
 * Gets the MIDI device type.
 *
 * @param device Specifies the MIDI device.
 *
 * @return The identifier of the MIDI device type:
 *  AMIDI_DEVICE_TYPE_USB
 *  AMIDI_DEVICE_TYPE_VIRTUAL
 *  AMIDI_DEVICE_TYPE_BLUETOOTH
 * or a negative error value:
 *  @see AMEDIA_ERROR_INVALID_PARAMETER - the device parameter is NULL.
 *  @see AMEDIA_ERROR_UNKNOWN - Unknown error.
 */
int32_t AMIDI_API AMidiDevice_getType(const AMidiDevice *device) __INTRODUCED_IN(29);

/**
 * Gets the number of input (sending) ports available on the specified MIDI device.
 *
 * @param device Specifies the MIDI device.
 *
 * @return If successful, returns the number of MIDI input (sending) ports available on the
 * device. If an error occurs, returns a negative value indicating the error:
 *  @see AMEDIA_ERROR_INVALID_PARAMETER - the device parameter is NULL.
 *  @see AMEDIA_ERROR_UNKNOWN - couldn't retrieve the device info.
 */
ssize_t AMIDI_API AMidiDevice_getNumInputPorts(const AMidiDevice *device) __INTRODUCED_IN(29);

/**
 * Gets the number of output (receiving) ports available on the specified MIDI device.
 *
 * @param device Specifies the MIDI device.
 *
 * @return If successful, returns the number of MIDI output (receiving) ports available on the
 * device. If an error occurs, returns a negative value indicating the error:
 *  @see AMEDIA_ERROR_INVALID_PARAMETER - the device parameter is NULL.
 *  @see AMEDIA_ERROR_UNKNOWN - couldn't retrieve the device info.
 */
ssize_t AMIDI_API AMidiDevice_getNumOutputPorts(const AMidiDevice *device) __INTRODUCED_IN(29);

/*
 * API for receiving data from the Output port of a device.
 */
/**
 * Opens the output port so that the client can receive data from it. The port remains open and
 * valid until AMidiOutputPort_close() is called for the returned AMidiOutputPort.
 *
 * @param device    Specifies the MIDI device.
 * @param portNumber Specifies the zero-based port index on the device to open. This value ranges
 *                  between 0 and one less than the number of output ports reported by the
 *                  AMidiDevice_getNumOutputPorts function.
 * @param outOutputPortPtr Receives the native API port identifier of the opened port.
 *
 * @return AMEDIA_OK, or a negative error code:
 *  @see AMEDIA_ERROR_UNKNOWN - Unknown Error.
 */
media_status_t AMIDI_API AMidiOutputPort_open(const AMidiDevice *device, int32_t portNumber,
                             AMidiOutputPort **outOutputPortPtr) __INTRODUCED_IN(29);

/**
 * Closes the output port.
 *
 * @param outputPort    The native API port identifier of the port.
 */
void AMIDI_API AMidiOutputPort_close(const AMidiOutputPort *outputPort) __INTRODUCED_IN(29);

/**
 * Receives the next pending MIDI message. To retrieve all pending messages, the client should
 * repeatedly call this method until it returns 0.
 *
 * Note that this is a non-blocking call. If there are no Midi messages are available, the function
 * returns 0 immediately (for 0 messages received).
 *
 * @param outputPort   Identifies the port to receive messages from.
 * @param opcodePtr  Receives the message Op Code.
 * @param buffer    Points to the buffer to receive the message data bytes.
 * @param maxBytes  Specifies the size of the buffer pointed to by the buffer parameter.
 * @param numBytesReceivedPtr  On exit, receives the actual number of bytes stored in buffer.
 * @param outTimestampPtr  If non-NULL, receives the timestamp associated with the message.
 *  (the current value of the running Java Virtual Machine's high-resolution time source,
 *  in nanoseconds)
 * @return the number of messages received (either 0 or 1), or a negative error code:
 *  @see AMEDIA_ERROR_UNKNOWN - Unknown Error.
 */
ssize_t AMIDI_API AMidiOutputPort_receive(const AMidiOutputPort *outputPort, int32_t *opcodePtr,
         uint8_t *buffer, size_t maxBytes, size_t* numBytesReceivedPtr, int64_t *outTimestampPtr) __INTRODUCED_IN(29);

/*
 * API for sending data to the Input port of a device.
 */
/**
 * Opens the input port so that the client can send data to it. The port remains open and
 * valid until AMidiInputPort_close() is called for the returned AMidiInputPort.
 *
 * @param device    Specifies the MIDI device.
 * @param portNumber Specifies the zero-based port index on the device to open. This value ranges
 *                  between 0 and one less than the number of input ports reported by the
 *                  AMidiDevice_getNumInputPorts() function..
 * @param outInputPortPtr Receives the native API port identifier of the opened port.
 *
 * @return AMEDIA_OK, or a negative error code:
 *  @see AMEDIA_ERROR_UNKNOWN - Unknown Error.
 */
media_status_t AMIDI_API AMidiInputPort_open(const AMidiDevice *device, int32_t portNumber,
                            AMidiInputPort **outInputPortPtr) __INTRODUCED_IN(29);

/**
 * Sends data to the specified input port.
 *
 * @param inputPort    The identifier of the port to send data to.
 * @param buffer       Points to the array of bytes containing the data to send.
 * @param numBytes     Specifies the number of bytes to write.
 *
 * @return The number of bytes sent, which could be less than specified or a negative error code:
 * @see AMEDIA_ERROR_INVALID_PARAMETER - The specified port was NULL, the specified buffer was NULL.
 */
ssize_t AMIDI_API AMidiInputPort_send(const AMidiInputPort *inputPort, const uint8_t *buffer,
                   size_t numBytes) __INTRODUCED_IN(29);

/**
 * Sends data to the specified input port with a timestamp.
 *
 * @param inputPort    The identifier of the port to send data to.
 * @param buffer       Points to the array of bytes containing the data to send.
 * @param numBytes     Specifies the number of bytes to write.
 * @param timestamp    The CLOCK_MONOTONIC time in nanoseconds to associate with the sent data.
 *
 * @return The number of bytes sent, which could be less than specified or a negative error code:
 * @see AMEDIA_ERROR_INVALID_PARAMETER - The specified port was NULL, the specified buffer was NULL.
 */
ssize_t AMIDI_API AMidiInputPort_sendWithTimestamp(const AMidiInputPort *inputPort,
        const uint8_t *buffer, size_t numBytes, int64_t timestamp) __INTRODUCED_IN(29);

/**
 * Sends a message with a 'MIDI flush command code' to the specified port. This should cause
 * a receiver to discard any pending MIDI data it may have accumulated and not processed.
 *
 * @param inputPort The identifier of the port to send the flush command to.
 *
 * @returns @see AMEDIA_OK if successful, otherwise a negative error code:
 * @see AMEDIA_ERROR_INVALID_PARAMETER - The specified port was NULL
 * @see AMEDIA_ERROR_UNSUPPORTED - The FLUSH command couldn't
 * be sent.
 */
media_status_t AMIDI_API AMidiInputPort_sendFlush(const AMidiInputPort *inputPort) __INTRODUCED_IN(29);

/**
 * Closes the input port.
 *
 * @param inputPort Identifies the input (sending) port to close.
 */
void AMIDI_API AMidiInputPort_close(const AMidiInputPort *inputPort) __INTRODUCED_IN(29);

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_MEDIA_AMIDI_H_ */
/**
@}
*/
