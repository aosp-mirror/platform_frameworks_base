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

    enum Command {
        REGISTER("register"),
        DELAY("delay"),
        INJECT("inject"),
        SYNC("sync");

        final String mCommandName;

        Command(String command) {
            mCommandName = command;
        }
    }

    // Constants representing evdev event types, from include/uapi/linux/input-event-codes.h in the
    // kernel.
    public static final int EV_KEY = 0x01;
    public static final int EV_REL = 0x02;
    public static final int EV_ABS = 0x03;
    public static final int EV_MSC = 0x04;
    public static final int EV_SW = 0x05;
    public static final int EV_LED = 0x11;
    public static final int EV_SND = 0x12;
    public static final int EV_FF = 0x15;

    public enum UinputControlCode {
        UI_SET_EVBIT("UI_SET_EVBIT", 100),
        UI_SET_KEYBIT("UI_SET_KEYBIT", 101),
        UI_SET_RELBIT("UI_SET_RELBIT", 102),
        UI_SET_ABSBIT("UI_SET_ABSBIT", 103),
        UI_SET_MSCBIT("UI_SET_MSCBIT", 104),
        UI_SET_LEDBIT("UI_SET_LEDBIT", 105),
        UI_SET_SNDBIT("UI_SET_SNDBIT", 106),
        UI_SET_FFBIT("UI_SET_FFBIT", 107),
        UI_SET_SWBIT("UI_SET_SWBIT", 109),
        UI_SET_PROPBIT("UI_SET_PROPBIT", 110);

        private final String mName;
        private final int mValue;

        UinputControlCode(String name, int value) {
            this.mName = name;
            this.mValue = value;
        }

        public String getName() {
            return mName;
        }

        public int getValue() {
            return mValue;
        }
    }

    // These constants come from "include/uapi/linux/input.h" in the kernel
    enum Bus {
        USB(0x03), BLUETOOTH(0x05);
        private final int mValue;

        Bus(int value) {
            mValue = value;
        }

        int getValue() {
            return mValue;
        }
    }

    private int mId;
    private Command mCommand;
    private String mName;
    private int mVid;
    private int mPid;
    private Bus mBus;
    private int[] mInjections;
    private SparseArray<int[]> mConfiguration;
    private int mDuration;
    private int mFfEffectsMax = 0;
    private String mInputport;
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
        return mVid;
    }

    public int getProductId() {
        return mPid;
    }

    public int getBus() {
        return mBus.getValue();
    }

    public int[] getInjections() {
        return mInjections;
    }

    public SparseArray<int[]> getConfiguration() {
        return mConfiguration;
    }

    public int getDuration() {
        return mDuration;
    }

    public int getFfEffectsMax() {
        return mFfEffectsMax;
    }

    public SparseArray<InputAbsInfo>  getAbsInfo() {
        return mAbsInfo;
    }

    public String getPort() {
        return mInputport;
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
            + ", vid=" + mVid
            + ", pid=" + mPid
            + ", bus=" + mBus
            + ", events=" + Arrays.toString(mInjections)
            + ", configuration=" + mConfiguration
            + ", duration=" + mDuration
            + ", ff_effects_max=" + mFfEffectsMax
            + ", port=" + mInputport
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

        public void setCommand(String command) {
            Objects.requireNonNull(command, "Command must not be null");
            for (Command cmd : Command.values()) {
                if (cmd.mCommandName.equals(command)) {
                    mEvent.mCommand = cmd;
                    return;
                }
            }
            throw new IllegalStateException("Unrecognized command: " + command);
        }

        public void setName(String name) {
            mEvent.mName = name;
        }

        public void setInjections(int[] events) {
            mEvent.mInjections = events;
        }

        public void setConfiguration(SparseArray<int[]> configuration) {
            mEvent.mConfiguration = configuration;
        }

        public void setVid(int vid) {
            mEvent.mVid = vid;
        }

        public void setPid(int pid) {
            mEvent.mPid = pid;
        }

        public void setBus(Bus bus) {
            mEvent.mBus = bus;
        }

        public void setDuration(int duration) {
            mEvent.mDuration = duration;
        }

        public void setFfEffectsMax(int ffEffectsMax) {
            mEvent.mFfEffectsMax = ffEffectsMax;
        }

        public void setAbsInfo(SparseArray<InputAbsInfo> absInfo) {
            mEvent.mAbsInfo = absInfo;
        }

        public void setInputport(String port) {
            mEvent.mInputport = port;
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
                    if (mEvent.mDuration <= 0) {
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
