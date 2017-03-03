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

#include "MidiPortRegistry.h"

namespace android {

ANDROID_SINGLETON_STATIC_INSTANCE(media::midi::MidiPortRegistry);

namespace media {
namespace midi {

//TODO Note that these 2 are identical
struct MidiPortRegistry::OutputPort {
    AMIDI_Device device;
    sp<IBinder> binderToken;
    base::unique_fd ufd;
};

struct MidiPortRegistry::InputPort {
    AMIDI_Device device;
    sp<IBinder> binderToken;
    base::unique_fd ufd;
};

MidiPortRegistry::MidiPortRegistry() : mNextOutputPortToken(0), mNextInputPortToken(0) {
}

status_t MidiPortRegistry::addOutputPort(
        AMIDI_Device device,
        sp<IBinder> portToken,
        base::unique_fd &&ufd,
        AMIDI_OutputPort *portPtr) {
    *portPtr = mNextOutputPortToken++;

    OutputPortEntry* portEntry = new OutputPortEntry;
    portEntry->port = new OutputPort;
    portEntry->state = MIDI_OUTPUT_PORT_STATE_OPEN_IDLE;
    portEntry->port = new OutputPort;
    portEntry->port->device = device;
    portEntry->port->binderToken = portToken;
    portEntry->port->ufd = std::move(ufd);

    mOutputPortMap[*portPtr] = portEntry;

    return OK;
}

status_t MidiPortRegistry::removeOutputPort(
        AMIDI_OutputPort port,
        AMIDI_Device *devicePtr,
        sp<IBinder> *portTokenPtr) {
    OutputPortMap::iterator itr = mOutputPortMap.find(port);
    if (itr == mOutputPortMap.end()) {
        return -EINVAL;
    }

    OutputPortEntry *entry = mOutputPortMap[port];
    int portState = MIDI_OUTPUT_PORT_STATE_OPEN_IDLE;
    while (!entry->state.compare_exchange_weak(portState, MIDI_OUTPUT_PORT_STATE_CLOSED)) {
        if (portState == MIDI_OUTPUT_PORT_STATE_CLOSED) {
            return -EINVAL; // Already closed
        }
    }
    *devicePtr = entry->port->device;
    *portTokenPtr = entry->port->binderToken;
    delete entry->port;
    entry->port = nullptr;

    mOutputPortMap.erase(itr);

    return OK;
}

status_t MidiPortRegistry::getOutputPortFdAndLock(
        AMIDI_OutputPort port, base::unique_fd **ufdPtr) {
    if (mOutputPortMap.find(port) == mOutputPortMap.end()) {
        return -EINVAL;
    }

    OutputPortEntry *entry = mOutputPortMap[port];
    int portState = MIDI_OUTPUT_PORT_STATE_OPEN_IDLE;
    if (!entry->state.compare_exchange_strong(portState, MIDI_OUTPUT_PORT_STATE_OPEN_ACTIVE)) {
        // The port has been closed.
        return -EPIPE;
    }
    *ufdPtr = &entry->port->ufd;

    return OK;
}

status_t MidiPortRegistry::unlockOutputPort(AMIDI_OutputPort port) {
    if (mOutputPortMap.find(port) == mOutputPortMap.end()) {
        return -EINVAL;
    }

    OutputPortEntry *entry = mOutputPortMap[port];
    entry->state.store(MIDI_OUTPUT_PORT_STATE_OPEN_IDLE);
    return OK;
}

status_t MidiPortRegistry::addInputPort(
        AMIDI_Device device,
        sp<IBinder> portToken,
        base::unique_fd &&ufd,
        AMIDI_InputPort *portPtr) {
    *portPtr = mNextInputPortToken++;

    InputPortEntry *entry = new InputPortEntry;

    entry->state = MIDI_INPUT_PORT_STATE_OPEN_IDLE;
    entry->port = new InputPort;
    entry->port->device = device;
    entry->port->binderToken = portToken;
    entry->port->ufd = std::move(ufd);

    mInputPortMap[*portPtr] = entry;

    return OK;
}

status_t MidiPortRegistry::removeInputPort(
        AMIDI_InputPort port,
        AMIDI_Device *devicePtr,
        sp<IBinder> *portTokenPtr) {
    InputPortMap::iterator itr = mInputPortMap.find(port);
    if (itr == mInputPortMap.end()) {
        return -EINVAL;
    }

    InputPortEntry *entry = mInputPortMap[port];
    int portState = MIDI_INPUT_PORT_STATE_OPEN_IDLE;
    while (!entry->state.compare_exchange_weak(portState, MIDI_INPUT_PORT_STATE_CLOSED)) {
        if (portState == MIDI_INPUT_PORT_STATE_CLOSED) return -EINVAL; // Already closed
    }

    *devicePtr = entry->port->device;
    *portTokenPtr = entry->port->binderToken;
    delete entry->port;
    entry->port = nullptr;

    mInputPortMap.erase(itr);

    return OK;
}

status_t MidiPortRegistry::getInputPortFd(AMIDI_InputPort port, base::unique_fd **ufdPtr) {
    if (mInputPortMap.find(port) == mInputPortMap.end()) {
        return -EINVAL;
    }

    InputPortEntry *entry = mInputPortMap[port];

    *ufdPtr = &entry->port->ufd;

    return OK;
}

status_t MidiPortRegistry::getInputPortFdAndLock(AMIDI_InputPort port, base::unique_fd **ufdPtr) {
    if (mInputPortMap.find(port) == mInputPortMap.end()) {
        return -EINVAL;
    }

    InputPortEntry *entry = mInputPortMap[port];

    int portState = MIDI_INPUT_PORT_STATE_OPEN_IDLE;
    if (!entry->state.compare_exchange_strong(portState, MIDI_INPUT_PORT_STATE_OPEN_ACTIVE)) {
        // The port has been closed.
        return -EPIPE;
    }
    *ufdPtr = &entry->port->ufd;
    return OK;
}

status_t MidiPortRegistry::MidiPortRegistry::unlockInputPort(AMIDI_InputPort port) {
    if (mInputPortMap.find(port) == mInputPortMap.end()) {
        return -EINVAL;
    }

    InputPortEntry *entry = mInputPortMap[port];
    entry->state.store(MIDI_INPUT_PORT_STATE_OPEN_IDLE);
    return OK;
}

} // namespace midi
} // namespace media
} // namespace android
