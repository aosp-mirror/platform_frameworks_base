/*
 * Copyright 2023 The Android Open Source Project
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
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

import src.com.android.commands.uinput.InputAbsInfo;

/**
 * A class that parses the JSON-like event format described in the README to build {@link Event}s.
 */
public class JsonStyleParser {
    private static final String TAG = "UinputJsonStyleParser";

    private JsonReader mReader;

    public JsonStyleParser(InputStreamReader in) {
        mReader = new JsonReader(in);
        mReader.setLenient(true);
    }

    /**
     * Gets the next event entry from the JSON file.
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
                        case "id" -> eb.setId(readInt());
                        case "command" -> eb.setCommand(mReader.nextString());
                        case "name" -> eb.setName(mReader.nextString());
                        case "vid" -> eb.setVid(readInt());
                        case "pid" -> eb.setPid(readInt());
                        case "bus" -> eb.setBus(readBus());
                        case "events" -> {
                            int[] injections = readInjectedEvents().stream()
                                    .mapToInt(Integer::intValue).toArray();
                            eb.setInjections(injections);
                        }
                        case "configuration" -> eb.setConfiguration(readConfiguration());
                        case "ff_effects_max" -> eb.setFfEffectsMax(readInt());
                        case "abs_info" -> eb.setAbsInfo(readAbsInfoArray());
                        case "duration" -> eb.setDuration(readInt());
                        case "port" -> eb.setInputport(mReader.nextString());
                        case "syncToken" -> eb.setSyncToken(mReader.nextString());
                        default -> mReader.skipValue();
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

    private Event.Bus readBus() throws IOException {
        String val = mReader.nextString();
        return Event.Bus.valueOf(val.toUpperCase());
    }

    private SparseArray<int[]> readConfiguration()
            throws IllegalStateException, IOException {
        SparseArray<int[]> configuration = new SparseArray<>();
        try {
            mReader.beginArray();
            while (mReader.hasNext()) {
                Event.UinputControlCode controlCode = null;
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
                    final int[] existing = configuration.get(controlCode.getValue());
                    configuration.put(controlCode.getValue(), existing == null ? data.toArray()
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

    private Event.UinputControlCode readUinputControlCode() throws IOException {
        var code = readValueAsInt((controlTypeStr) -> {
            try {
                return Event.UinputControlCode.valueOf(controlTypeStr).getValue();
            } catch (IllegalArgumentException ex) {
                return -1;
            }
        });
        for (Event.UinputControlCode controlCode : Event.UinputControlCode.values()) {
            if (controlCode.getValue() == code) {
                return controlCode;
            }
        }
        return null;
    }

    private List<Integer> readDataForControlCode(
            Event.UinputControlCode controlCode) throws IOException {
        return switch (controlCode) {
            case UI_SET_EVBIT -> readArrayAsInts(this::readEvdevEventType);
            case UI_SET_KEYBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_KEY));
            case UI_SET_RELBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_REL));
            case UI_SET_ABSBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_ABS));
            case UI_SET_MSCBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_MSC));
            case UI_SET_LEDBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_LED));
            case UI_SET_SNDBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_SND));
            case UI_SET_FFBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_FF));
            case UI_SET_SWBIT -> readArrayAsInts(() -> readEvdevEventCode(Event.EV_SW));
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
                    case "value" -> absInfo.value = readInt();
                    case "minimum" -> absInfo.minimum = readInt();
                    case "maximum" -> absInfo.maximum = readInt();
                    case "fuzz" -> absInfo.fuzz = readInt();
                    case "flat" -> absInfo.flat = readInt();
                    case "resolution" -> absInfo.resolution = readInt();
                    default -> {
                        consumeRemainingElements();
                        mReader.endObject();
                        throw new IllegalStateException("Invalid key in abs info: " + name);
                    }
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
                        case "code" -> type = readEvdevEventCode(Event.EV_ABS);
                        case "info" -> absInfo = readAbsInfo();
                        default -> {
                            consumeRemainingElements();
                            mReader.endObject();
                            throw new IllegalStateException("Invalid key in abs info array: "
                                    + name);
                        }
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

    private static void error(String msg, Exception e) {
        System.out.println(msg);
        Log.e(TAG, msg);
        if (e != null) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}
