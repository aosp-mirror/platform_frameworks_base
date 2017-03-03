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

#ifndef ANDROID_MEDIA_MIDI_PORT_REGISTRY_H_
#define ANDROID_MEDIA_MIDI_PORT_REGISTRY_H_

#include <atomic>
#include <map>

#include <android-base/unique_fd.h>
#include <binder/IBinder.h>
#include <utils/Errors.h>
#include <utils/Singleton.h>

#include "midi.h"

namespace android {
namespace media {
namespace midi {

/*
 * Maintains lists of all active input and output MIDI ports and controls access to them. Provides
 * exclusive access to specific MIDI ports.
 */
class MidiPortRegistry : public Singleton<MidiPortRegistry> {
  public:
    /*
     * Creates an output port entry and associates it with the specified MIDI device.
     * Called by AMIDI_openOutputPort();
     *
     * device       The native API device ID.
     * portToken    The port token (returned from the device server).
     * udf          File descriptor for the data port associated with the MIDI output port.
     * portPtr      Receives the native API port ID of the port being opened.
     */
    status_t addOutputPort(
            AMIDI_Device device,
            sp<IBinder> portToken,
            base::unique_fd &&ufd,
            AMIDI_OutputPort *portPtr);

    /*
     * Removes for the output port list a previously added output port.
     * Called by AMIDI_closeOutputPort();
     *
     * port         The native API port ID of the port being closed.
     * devicePtr    Receives the native API device ID associated with the port.
     * portTokenPtr Receives the binder token associated with the port.
     */
    status_t removeOutputPort(
            AMIDI_OutputPort port,
            AMIDI_Device *devicePtr,
            sp<IBinder> *portTokenPtr);

    /*
     * Creates an input port entry and associates it with the specified MIDI device.
     * Called by AMIDI_openInputPort();
     *
     * device       The native API device ID.
     * portToken    The port token (returned from the device server).
     * udf          File descriptor for the data port associated with the MIDI input port.
     * portPtr      Receives the native API port ID of the port being opened.
     */
    status_t addInputPort(
            AMIDI_Device device,
            sp<IBinder> portToken,
            base::unique_fd &&ufd,
            AMIDI_InputPort *portPtr);

    /*
     * Removes for the input port list a previously added input port.
     * Called by AMIDI_closeINputPort();
     *
     * port         The native API port ID of the port being closed.
     * devicePtr    Receives the native API device ID associated with the port.
     * portTokenPtr Receives the binder token associated with the port.
     */
    status_t removeInputPort(
            AMIDI_InputPort port,
            AMIDI_Device *devicePtr,
            sp<IBinder> *portTokenPtr);

    /*
     * Retrieves an exclusive-access file descriptor for an output port.
     * Called from AMIDI_receive().
     *
     * port     The native API id of the output port.
     * ufdPtr   Receives the exclusive-access file descriptor for the output port.
     */
    status_t getOutputPortFdAndLock(AMIDI_OutputPort port, base::unique_fd **ufdPtr);

    /*
     * Releases exclusive-access to the port and invalidates the previously received file
     * descriptor.
     * Called from AMIDI_receive().
     *
     * port The native API id of the output port.
     */
    status_t unlockOutputPort(AMIDI_OutputPort port);

    /*
     * Retrieves an exclusive-access file descriptor for an input port.
     * (Not being used as (perhaps) AMIDI_sendWithTimestamp() doesn't need exclusive access
     * to the port).
     *
     * port     The native API id of the input port.
     * ufdPtr   Receives the exclusive-access file descriptor for the input port.
     */
    status_t getInputPortFdAndLock(AMIDI_InputPort port, base::unique_fd **ufdPtr);

    /*
     * Releases exclusive-access to the port and invalidates the previously received file
     * descriptor.
     * (Not used. See above).
     *
     * port The native API id of the input port.
     */
    status_t unlockInputPort(AMIDI_InputPort port);

    /*
     * Retrieves an unlocked (multi-access) file descriptor for an input port.
     * Used by AMIDI_sendWith(), AMIDI_sendWithTimestamp & AMIDI_flush.
     *
     * port     The native API id of the input port.
     * ufdPtr   Receives the multi-access file descriptor for the input port.
     */
    status_t getInputPortFd(AMIDI_InputPort port, base::unique_fd **ufdPtr);

  private:
    friend class Singleton<MidiPortRegistry>;
    MidiPortRegistry();

    /*
     * Output (data receiving) ports.
     */
    struct OutputPort;
    enum {
        MIDI_OUTPUT_PORT_STATE_CLOSED = 0,
        MIDI_OUTPUT_PORT_STATE_OPEN_IDLE,
        MIDI_OUTPUT_PORT_STATE_OPEN_ACTIVE
    };

    struct OutputPortEntry {
        std::atomic_int state;
        OutputPort *port;
    };

    typedef std::map<AMIDI_OutputPort, OutputPortEntry*> OutputPortMap;
    // Access is synchronized per record via 'state' field.
    std::atomic<AMIDI_OutputPort> mNextOutputPortToken;
    OutputPortMap  mOutputPortMap;

    /*
     * Input (data sending) ports.
     */
    struct InputPort;
    enum {
        MIDI_INPUT_PORT_STATE_CLOSED = 0,
        MIDI_INPUT_PORT_STATE_OPEN_IDLE,
        MIDI_INPUT_PORT_STATE_OPEN_ACTIVE
    };

    struct InputPortEntry {
        std::atomic_int state;
        InputPort *port;
    };

    typedef std::map<AMIDI_OutputPort, InputPortEntry*> InputPortMap;
    // Access is synchronized per record via 'state' field.
    std::atomic<AMIDI_InputPort> mNextInputPortToken;
    InputPortMap  mInputPortMap;

};

} // namespace midi
} // namespace media
} // namespace android

#endif // ANDROID_MEDIA_MIDI_PORT_REGISTRY_H_
