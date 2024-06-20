/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.commands.uinput;

import android.annotation.Nullable;
import android.util.SparseArray;

import java.util.Arrays;
import java.util.Objects;

import src.com.android.commands.uinput.InputAbsInfo;

/**
 * An event is a JSON file defined action event to instruct uinput to perform a command like
 * device registration or uinput events injection.
 */
public class Event {
    private static final String TAG = "UinputEvent";

    public enum Command {
        REGISTER,
        DELAY,
        INJECT,
        SYNC,
        UPDATE_TIME_BASE,
    }

    // Constants representing evdev event types, from include/uapi/linux/input-event-codes.h in the
    // kernel.
    public static final int EV_SYN = 0x00;
    public static final int EV_KEY = 0x01;
    public static final int EV_REL = 0x02;
    public static final int EV_ABS = 0x03;
    public static final int EV_MSC = 0x04;
    public static final int EV_SW = 0x05;
    public static final int EV_LED = 0x11;
    public static final int EV_SND = 0x12;
    public static final int EV_FF = 0x15;

    public enum UinputControlCode {
        UI_SET_EVBIT(100),
        UI_SET_KEYBIT(101),
        UI_SET_RELBIT(102),
        UI_SET_ABSBIT(103),
        UI_SET_MSCBIT(104),
        UI_SET_LEDBIT(105),
        UI_SET_SNDBIT(106),
        UI_SET_FFBIT(107),
        UI_SET_SWBIT(109),
        UI_SET_PROPBIT(110);

        private final int mValue;

        UinputControlCode(int value) {
            this.mValue = value;
        }

        public int getValue() {
            return mValue;
        }

        /**
         * Returns the control code for the given evdev event type, or {@code null} if there is no
         * control code for that type.
         */
        public static @Nullable UinputControlCode forEventType(int eventType) {
            return switch (eventType) {
                case EV_KEY -> UI_SET_KEYBIT;
                case EV_REL -> UI_SET_RELBIT;
                case EV_ABS -> UI_SET_ABSBIT;
                case EV_MSC -> UI_SET_MSCBIT;
                case EV_SW -> UI_SET_SWBIT;
                case EV_LED -> UI_SET_LEDBIT;
                case EV_SND -> UI_SET_SNDBIT;
                case EV_FF -> UI_SET_FFBIT;
                default -> null;
            };
        }
    }

    private int mId;
    private Command mCommand;
    private String mName;
    private int mVendorId;
    private int mProductId;
    private int mVersionId;
    private int mBusId;
    private int[] mInjections;
    private long mTimestampOffsetMicros = -1;
    private SparseArray<int[]> mConfiguration;
    private long mDurationNanos;
    private int mFfEffectsMax = 0;
    private String mInputPort;
    private SparseArray<InputAbsInfo> mAbsInfo;
    private String mSyncToken;

    public int getId() {
        return mId;
    }

    public Command getCommand() {
        return mCommand;
    }

    public String getName() {
        return mName;
    }

    public int getVendorId() {
        return mVendorId;
    }

    public int getProductId() {
        return mProductId;
    }

    public int getVersionId() {
        return mVersionId;
    }

    public int getBus() {
        return mBusId;
    }

    public int[] getInjections() {
        return mInjections;
    }

    /**
     * Returns the number of microseconds that should be added to the previous {@code INJECT}
     * event's timestamp to produce the timestamp for this {@code INJECT} event. A value of -1
     * indicates that the current timestamp should be used instead.
     */
    public long getTimestampOffsetMicros() {
        return mTimestampOffsetMicros;
    }

    /**
     * Returns a {@link SparseArray} describing the event codes that should be registered for the
     * device. The keys are uinput ioctl codes (such as those returned from {@link
     * UinputControlCode#getValue()}, while the values are arrays of event codes to be enabled with
     * those ioctls. For example, key 101 (corresponding to {@link UinputControlCode#UI_SET_KEYBIT})
     * could have values 0x110 ({@code BTN_LEFT}), 0x111 ({@code BTN_RIGHT}), and 0x112
     * ({@code BTN_MIDDLE}).
     */
    public SparseArray<int[]> getConfiguration() {
        return mConfiguration;
    }

    public long getDurationNanos() {
        return mDurationNanos;
    }

    public int getFfEffectsMax() {
        return mFfEffectsMax;
    }

    public SparseArray<InputAbsInfo>  getAbsInfo() {
        return mAbsInfo;
    }

    public String getPort() {
        return mInputPort;
    }

    public String getSyncToken() {
        return mSyncToken;
    }

    /**
     * Convert an event to String.
     */
    public String toString() {
        return "Event{id=" + mId
            + ", command=" + mCommand
            + ", name=" + mName
            + ", vid=" + mVendorId
            + ", pid=" + mProductId
            + ", busId=" + mBusId
            + ", events=" + Arrays.toString(mInjections)
            + ", configuration=" + mConfiguration
            + ", duration=" + mDurationNanos + "ns"
            + ", ff_effects_max=" + mFfEffectsMax
            + ", port=" + mInputPort
            + "}";
    }

    public static class Builder {
        private Event mEvent;

        Builder() {
            mEvent = new Event();
        }

        public void setId(int id) {
            mEvent.mId = id;
        }

        public void setCommand(Command command) {
            mEvent.mCommand = command;
        }

        public void setName(String name) {
            mEvent.mName = name;
        }

        public void setInjections(int[] events) {
            mEvent.mInjections = events;
        }

        public void setTimestampOffsetMicros(long offsetMicros) {
            mEvent.mTimestampOffsetMicros = offsetMicros;
        }

        /**
         * Sets the event codes that should be registered with a {@code register} command.
         *
         * @param configuration An array of ioctls and event codes, as described at
         *                      {@link Event#getConfiguration()}.
         */
        public void setConfiguration(SparseArray<int[]> configuration) {
            mEvent.mConfiguration = configuration;
        }

        public void setVendorId(int vendorId) {
            mEvent.mVendorId = vendorId;
        }

        public void setProductId(int productId) {
            mEvent.mProductId = productId;
        }

        public void setVersionId(int versionId) {
            mEvent.mVersionId = versionId;
        }

        public void setBusId(int busId) {
            mEvent.mBusId = busId;
        }

        public void setDurationNanos(long durationNanos) {
            mEvent.mDurationNanos = durationNanos;
        }

        public void setFfEffectsMax(int ffEffectsMax) {
            mEvent.mFfEffectsMax = ffEffectsMax;
        }

        public void setAbsInfo(SparseArray<InputAbsInfo> absInfo) {
            mEvent.mAbsInfo = absInfo;
        }

        public void setInputPort(String port) {
            mEvent.mInputPort = port;
        }

        public void setSyncToken(String syncToken) {
            mEvent.mSyncToken = Objects.requireNonNull(syncToken, "Sync token must not be null");
        }

        public Event build() {
            if (mEvent.mId == -1) {
                throw new IllegalStateException("No event id");
            } else if (mEvent.mCommand == null) {
                throw new IllegalStateException("Event does not contain a command");
            }
            switch (mEvent.mCommand) {
                case REGISTER -> {
                    if (mEvent.mConfiguration == null) {
                        throw new IllegalStateException(
                                "Device registration is missing configuration");
                    }
                }
                case DELAY -> {
                    if (mEvent.mDurationNanos <= 0) {
                        throw new IllegalStateException("Delay has missing or invalid duration");
                    }
                }
                case INJECT -> {
                    if (mEvent.mInjections == null) {
                        throw new IllegalStateException("Inject command is missing injection data");
                    }
                }
                case SYNC -> {
                    if (mEvent.mSyncToken == null) {
                        throw new IllegalStateException("Sync command is missing sync token");
                    }
                }
            }
            return mEvent;
        }
    }
}
