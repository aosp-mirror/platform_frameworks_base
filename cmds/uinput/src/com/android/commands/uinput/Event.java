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
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

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

    private static final int EV_KEY = 0x01;
    private static final int EV_REL = 0x02;
    private static final int EV_ABS = 0x03;
    private static final int EV_MSC = 0x04;
    private static final int EV_SW = 0x05;
    private static final int EV_LED = 0x11;
    private static final int EV_SND = 0x12;
    private static final int EV_FF = 0x15;

    private enum UinputControlCode {
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

        final String mName;
        final int mValue;

        UinputControlCode(String name, int value) {
            this.mName = name;
            this.mValue = value;
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

    private static class Builder {
        private Event mEvent;

        Builder() {
            mEvent = new Event();
        }

        public void setId(int id) {
            mEvent.mId = id;
        }

        private void setCommand(String command) {
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
                                int[] injections = readInjectedEvents().stream()
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
                            case "port":
                                eb.setInputport(mReader.nextString());
                                break;
                            case "syncToken":
                                eb.setSyncToken(mReader.nextString());
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

        private ArrayList<Integer> readInjectedEvents() throws IOException {
            ArrayList<Integer> data = new ArrayList<>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    // Read events in groups of three, because we expect an event type, event code,
                    // and event value.
                    final int type = readEvdevEventType();
                    data.add(type);
                    data.add(readEvdevEventCode(type));
                    data.add(readInt());
                }
                mReader.endArray();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return data;
        }

        private int readValueAsInt(Function<String, Integer> stringToInt) throws IOException {
            switch (mReader.peek()) {
                case NUMBER: {
                    return mReader.nextInt();
                }
                case STRING: {
                    final var str = mReader.nextString();
                    try {
                        // Attempt to first parse the value as an int.
                        return Integer.decode(str);
                    } catch (NumberFormatException e) {
                        // Then fall back to the supplied function.
                        return stringToInt.apply(str);
                    }
                }
                default: {
                    throw new IllegalStateException(
                            "Encountered malformed data. Expected int or string.");
                }
            }
        }

        private int readInt() throws IOException {
            return readValueAsInt((str) -> {
                throw new IllegalStateException("Encountered malformed data. Expected int.");
            });
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
                    UinputControlCode controlCode = null;
                    IntStream data = null;
                    mReader.beginObject();
                    while (mReader.hasNext()) {
                        String name = mReader.nextName();
                        switch (name) {
                            case "type":
                                controlCode = readUinputControlCode();
                                break;
                            case "data":
                                Objects.requireNonNull(controlCode,
                                        "Configuration 'type' must be specified before 'data'.");
                                data = readDataForControlCode(controlCode)
                                        .stream().mapToInt(Integer::intValue);
                                break;
                            default:
                                consumeRemainingElements();
                                mReader.endObject();
                                throw new IllegalStateException(
                                        "Invalid key in device configuration: " + name);
                        }
                    }
                    mReader.endObject();
                    if (controlCode != null && data != null) {
                        final int[] existing = configuration.get(controlCode.mValue);
                        configuration.put(controlCode.mValue, existing == null ? data.toArray()
                                : IntStream.concat(IntStream.of(existing), data).toArray());
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

        private UinputControlCode readUinputControlCode() throws IOException {
            var code = readValueAsInt((controlTypeStr) -> {
                for (UinputControlCode controlCode : UinputControlCode.values()) {
                    if (controlCode.mName.equals(controlTypeStr)) {
                        return controlCode.mValue;
                    }
                }
                return -1;
            });
            for (UinputControlCode controlCode : UinputControlCode.values()) {
                if (controlCode.mValue == code) {
                    return controlCode;
                }
            }
            return null;
        }

        private List<Integer> readDataForControlCode(
                UinputControlCode controlCode) throws IOException {
            return switch (controlCode) {
                case UI_SET_EVBIT -> readArrayAsInts(this::readEvdevEventType);
                case UI_SET_KEYBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_KEY));
                case UI_SET_RELBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_REL));
                case UI_SET_ABSBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_ABS));
                case UI_SET_MSCBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_MSC));
                case UI_SET_LEDBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_LED));
                case UI_SET_SNDBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_SND));
                case UI_SET_FFBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_FF));
                case UI_SET_SWBIT -> readArrayAsInts(() -> readEvdevEventCode(EV_SW));
                case UI_SET_PROPBIT -> readArrayAsInts(this::readEvdevInputProp);
            };
        }

        interface IntValueReader {
            int readNextValue() throws IOException;
        }

        private ArrayList<Integer> readArrayAsInts(
                IntValueReader nextValueReader) throws IOException {
            ArrayList<Integer> data = new ArrayList<>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    data.add(nextValueReader.readNextValue());
                }
                mReader.endArray();
            } catch (IllegalStateException | NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            return data;
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
                                type = readEvdevEventCode(EV_ABS);
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

        private int readEvdevEventType() throws IOException {
            return readValueAsInt(Device::getEvdevEventTypeByLabel);
        }

        private int readEvdevEventCode(int type) throws IOException {
            return readValueAsInt((str) -> Device.getEvdevEventCodeByLabel(type, str));
        }

        private int readEvdevInputProp() throws IOException {
            return readValueAsInt(Device::getEvdevInputPropByLabel);
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
