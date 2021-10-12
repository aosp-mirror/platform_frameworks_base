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

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;

import src.com.android.commands.uinput.InputAbsInfo;

/**
 * An event is a JSON file defined action event to instruct uinput to perform a command like
 * device registration or uinput events injection.
 */
public class Event {
    private static final String TAG = "UinputEvent";

    public static final String COMMAND_REGISTER = "register";
    public static final String COMMAND_DELAY = "delay";
    public static final String COMMAND_INJECT = "inject";
    private static final int ABS_CNT = 64;

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
    private String mCommand;
    private String mName;
    private int mVid;
    private int mPid;
    private Bus mBus;
    private int[] mInjections;
    private SparseArray<int[]> mConfiguration;
    private int mDuration;
    private int mFfEffectsMax = 0;
    private SparseArray<InputAbsInfo> mAbsInfo;

    public int getId() {
        return mId;
    }

    public String getCommand() {
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
            + "}";
    }

    private static class Builder {
        private Event mEvent;

        Builder() {
            mEvent = new Event();
        }

        public void setId(int id) {
            mEvent.mId = id;
        }

        private void setCommand(String command) {
            mEvent.mCommand = command;
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

        public Event build() {
            if (mEvent.mId == -1) {
                throw new IllegalStateException("No event id");
            } else if (mEvent.mCommand == null) {
                throw new IllegalStateException("Event does not contain a command");
            }
            if (COMMAND_REGISTER.equals(mEvent.mCommand)) {
                if (mEvent.mConfiguration == null) {
                    throw new IllegalStateException(
                            "Device registration is missing configuration");
                }
            } else if (COMMAND_DELAY.equals(mEvent.mCommand)) {
                if (mEvent.mDuration <= 0) {
                    throw new IllegalStateException("Delay has missing or invalid duration");
                }
            } else if (COMMAND_INJECT.equals(mEvent.mCommand)) {
                if (mEvent.mInjections  == null) {
                    throw new IllegalStateException("Inject command is missing injection data");
                }
            } else {
                throw new IllegalStateException("Unknown command " + mEvent.mCommand);
            }
            return mEvent;
        }
    }

    /**
     *  A class that parses the JSON event format from an input stream to build device events.
     */
    public static class Reader {
        private JsonReader mReader;

        public Reader(InputStreamReader in) {
            mReader = new JsonReader(in);
            mReader.setLenient(true);
        }

        /**
         * Get next event entry from JSON file reader.
         */
        public Event getNextEvent() throws IOException {
            Event e = null;
            while (e == null && mReader.peek() != JsonToken.END_DOCUMENT) {
                Event.Builder eb = new Event.Builder();
                try {
                    mReader.beginObject();
                    while (mReader.hasNext()) {
                        String name = mReader.nextName();
                        switch (name) {
                            case "id":
                                eb.setId(readInt());
                                break;
                            case "command":
                                eb.setCommand(mReader.nextString());
                                break;
                            case "name":
                                eb.setName(mReader.nextString());
                                break;
                            case "vid":
                                eb.setVid(readInt());
                                break;
                            case "pid":
                                eb.setPid(readInt());
                                break;
                            case "bus":
                                eb.setBus(readBus());
                                break;
                            case "events":
                                int[] injections = readIntList().stream()
                                            .mapToInt(Integer::intValue).toArray();
                                eb.setInjections(injections);
                                break;
                            case "configuration":
                                eb.setConfiguration(readConfiguration());
                                break;
                            case "ff_effects_max":
                                eb.setFfEffectsMax(readInt());
                                break;
                            case "abs_info":
                                eb.setAbsInfo(readAbsInfoArray());
                                break;
                            case "duration":
                                eb.setDuration(readInt());
                                break;
                            default:
                                mReader.skipValue();
                        }
                    }
                    mReader.endObject();
                } catch (IllegalStateException ex) {
                    error("Error reading in object, ignoring.", ex);
                    consumeRemainingElements();
                    mReader.endObject();
                    continue;
                }
                e = eb.build();
            }

            return e;
        }

        private ArrayList<Integer> readIntList() throws IOException {
            ArrayList<Integer> data = new ArrayList<Integer>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    data.add(Integer.decode(mReader.nextString()));
                }
                mReader.endArray();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return data;
        }

        private byte[] readData() throws IOException {
            ArrayList<Integer> data = readIntList();
            byte[] rawData = new byte[data.size()];
            for (int i = 0; i < data.size(); i++) {
                int d = data.get(i);
                if ((d & 0xFF) != d) {
                    throw new IllegalStateException("Invalid data, all values must be byte-sized");
                }
                rawData[i] = (byte) d;
            }
            return rawData;
        }

        private int readInt() throws IOException {
            String val = mReader.nextString();
            return Integer.decode(val);
        }

        private Bus readBus() throws IOException {
            String val = mReader.nextString();
            return Bus.valueOf(val.toUpperCase());
        }

        private SparseArray<int[]> readConfiguration()
                throws IllegalStateException, IOException {
            SparseArray<int[]> configuration = new SparseArray<>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    int type = 0;
                    int[] data = null;
                    mReader.beginObject();
                    while (mReader.hasNext()) {
                        String name = mReader.nextName();
                        switch (name) {
                            case "type":
                                type = readInt();
                                break;
                            case "data":
                                data = readIntList().stream()
                                            .mapToInt(Integer::intValue).toArray();
                                break;
                            default:
                                consumeRemainingElements();
                                mReader.endObject();
                                throw new IllegalStateException(
                                        "Invalid key in device configuration: " + name);
                        }
                    }
                    mReader.endObject();
                    if (data != null) {
                        configuration.put(type, data);
                    }
                }
                mReader.endArray();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return configuration;
        }

        private InputAbsInfo readAbsInfo() throws IllegalStateException, IOException {
            InputAbsInfo absInfo = new InputAbsInfo();
            try {
                mReader.beginObject();
                while (mReader.hasNext()) {
                    String name = mReader.nextName();
                    switch (name) {
                        case "value":
                            absInfo.value = readInt();
                            break;
                        case "minimum":
                            absInfo.minimum = readInt();
                            break;
                        case "maximum":
                            absInfo.maximum = readInt();
                            break;
                        case "fuzz":
                            absInfo.fuzz = readInt();
                            break;
                        case "flat":
                            absInfo.flat = readInt();
                            break;
                        case "resolution":
                            absInfo.resolution = readInt();
                            break;
                        default:
                            consumeRemainingElements();
                            mReader.endObject();
                            throw new IllegalStateException("Invalid key in abs info: " + name);
                    }
                }
                mReader.endObject();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endObject();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return absInfo;
        }

        private SparseArray<InputAbsInfo> readAbsInfoArray()
                throws IllegalStateException, IOException {
            SparseArray<InputAbsInfo> infoArray = new SparseArray<>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    int type = 0;
                    InputAbsInfo absInfo = null;
                    mReader.beginObject();
                    while (mReader.hasNext()) {
                        String name = mReader.nextName();
                        switch (name) {
                            case "code":
                                type = readInt();
                                break;
                            case "info":
                                absInfo = readAbsInfo();
                                break;
                            default:
                                consumeRemainingElements();
                                mReader.endObject();
                                throw new IllegalStateException("Invalid key in abs info array: "
                                        + name);
                        }
                    }
                    mReader.endObject();
                    if (absInfo != null) {
                        infoArray.put(type, absInfo);
                    }
                }
                mReader.endArray();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return infoArray;
        }

        private void consumeRemainingElements() throws IOException {
            while (mReader.hasNext()) {
                mReader.skipValue();
            }
        }
    }

    private static void error(String msg, Exception e) {
        System.out.println(msg);
        Log.e(TAG, msg);
        if (e != null) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}
