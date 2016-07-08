/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.midi;

import android.media.midi.MidiReceiver;
import android.media.midi.MidiSender;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Utility class for dispatching MIDI data to a list of {@link android.media.midi.MidiReceiver}s.
 * This class subclasses {@link android.media.midi.MidiReceiver} and dispatches any data it receives
 * to its receiver list. Any receivers that throw an exception upon receiving data will
 * be automatically removed from the receiver list. If a MidiReceiverFailureHandler has been
 * provided to the MidiDispatcher, it will be notified about the failure, but the exception
 * itself will be swallowed.
 */
public final class MidiDispatcher extends MidiReceiver {

    // MidiDispatcher's client and MidiReceiver's owner can be different
    // classes (e.g. MidiDeviceService is a client, but MidiDeviceServer is
    // the owner), and errors occuring during sending need to be reported
    // to the owner rather than to the sender.
    //
    // Note that the callbacks will be called on the sender's thread.
    public interface MidiReceiverFailureHandler {
        void onReceiverFailure(MidiReceiver receiver, IOException failure);
    }

    private final MidiReceiverFailureHandler mFailureHandler;
    private final CopyOnWriteArrayList<MidiReceiver> mReceivers
            = new CopyOnWriteArrayList<MidiReceiver>();

    private final MidiSender mSender = new MidiSender() {
        @Override
        public void onConnect(MidiReceiver receiver) {
            mReceivers.add(receiver);
        }

        @Override
        public void onDisconnect(MidiReceiver receiver) {
            mReceivers.remove(receiver);
        }
    };

    public MidiDispatcher() {
        this(null);
    }

    public MidiDispatcher(MidiReceiverFailureHandler failureHandler) {
        mFailureHandler = failureHandler;
    }

    /**
     * Returns the number of {@link android.media.midi.MidiReceiver}s this dispatcher contains.
     * @return the number of receivers
     */
    public int getReceiverCount() {
        return mReceivers.size();
    }

    /**
     * Returns a {@link android.media.midi.MidiSender} which is used to add and remove
     * {@link android.media.midi.MidiReceiver}s
     * to the dispatcher's receiver list.
     * @return the dispatcher's MidiSender
     */
    public MidiSender getSender() {
        return mSender;
    }

    @Override
    public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
       for (MidiReceiver receiver : mReceivers) {
            try {
                receiver.send(msg, offset, count, timestamp);
            } catch (IOException e) {
                // If the receiver fails we remove the receiver but do not propagate the exception.
                // Note that this may also happen if the client code stalls, and thus underlying
                // MidiInputPort.onSend has raised IOException for EAGAIN / EWOULDBLOCK error.
                mReceivers.remove(receiver);
                if (mFailureHandler != null) {
                    mFailureHandler.onReceiverFailure(receiver, e);
                }
            }
        }
    }

    @Override
    public void onFlush() throws IOException {
       for (MidiReceiver receiver : mReceivers) {
            try {
                receiver.flush();
            } catch (IOException e) {
                // This is just a special case of 'send' thus handle in the same way.
                mReceivers.remove(receiver);
                if (mFailureHandler != null) {
                    mFailureHandler.onReceiverFailure(receiver, e);
                }
            }
       }
    }
}
