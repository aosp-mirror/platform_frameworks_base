/*
 * Copyright (C) 2023 The Android Open Source Project
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

/**
 * Uses multiple MidiEventSchedulers for waiting for events.
 *
 */
public class MidiEventMultiScheduler {
    private MultiLockMidiEventScheduler[] mMidiEventSchedulers;
    private int mNumEventSchedulers;
    private int mNumClosedSchedulers = 0;
    private final Object mMultiLock = new Object();

    private class MultiLockMidiEventScheduler extends MidiEventScheduler {
        @Override
        public void close() {
            synchronized (mMultiLock) {
                mNumClosedSchedulers++;
            }
            super.close();
        }

        @Override
        protected Object getLock() {
            return mMultiLock;
        }

        public boolean isEventBufferEmptyLocked() {
            return mEventBuffer.isEmpty();
        }

        public long getLowestTimeLocked() {
            return mEventBuffer.firstKey();
        }
    }

    /**
     * MidiEventMultiScheduler constructor
     *
     * @param numSchedulers the number of schedulers to create
     */
    public MidiEventMultiScheduler(int numSchedulers) {
        mNumEventSchedulers = numSchedulers;
        mMidiEventSchedulers = new MultiLockMidiEventScheduler[numSchedulers];
        for (int i = 0; i < numSchedulers; i++) {
            mMidiEventSchedulers[i] = new MultiLockMidiEventScheduler();
        }
    }

    /**
     * Waits for the next MIDI event. This will return true when it receives it.
     * If all MidiEventSchedulers have been closed, this will return false.
     *
     * @return true if a MIDI event is received and false if all schedulers are closed.
     */
    public boolean waitNextEvent() throws InterruptedException {
        synchronized (mMultiLock) {
            while (true) {
                if (mNumClosedSchedulers >= mNumEventSchedulers) {
                    return false;
                }
                long lowestTime = Long.MAX_VALUE;
                long now = System.nanoTime();
                for (MultiLockMidiEventScheduler eventScheduler : mMidiEventSchedulers) {
                    if (!eventScheduler.isEventBufferEmptyLocked()) {
                        lowestTime = Math.min(lowestTime,
                                eventScheduler.getLowestTimeLocked());
                    }
                }
                if (lowestTime <= now) {
                    return true;
                }
                long nanosToWait = lowestTime - now;
                // Add 1 millisecond so we don't wake up before it is
                // ready.
                long millisToWait = 1 + (nanosToWait / EventScheduler.NANOS_PER_MILLI);
                // Clip 64-bit value to 32-bit max.
                if (millisToWait > Integer.MAX_VALUE) {
                    millisToWait = Integer.MAX_VALUE;
                }
                mMultiLock.wait(millisToWait);
            }
        }
    }

    /**
     * Gets the number of MidiEventSchedulers.
     *
     * @return the number of MidiEventSchedulers.
     */
    public int getNumEventSchedulers() {
        return mNumEventSchedulers;
    }

    /**
     * Gets a specific MidiEventScheduler based on the index.
     *
     * @param index the zero indexed index of a MIDI event scheduler
     * @return a MidiEventScheduler
     */
    public MidiEventScheduler getEventScheduler(int index) {
        return mMidiEventSchedulers[index];
    }

    /**
     * Closes all event schedulers.
     */
    public void close() {
        for (MidiEventScheduler eventScheduler : mMidiEventSchedulers) {
            eventScheduler.close();
        }
    }
}
