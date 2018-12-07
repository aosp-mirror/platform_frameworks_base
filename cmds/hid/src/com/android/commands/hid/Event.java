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

package com.android.commands.hid;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.util.SparseArray;

import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class Event {
    private static final String TAG = "HidEvent";

    public static final String COMMAND_REGISTER = "register";
    public static final String COMMAND_DELAY = "delay";
    public static final String COMMAND_REPORT = "report";

    private int mId;
    private String mCommand;
    private String mName;
    private byte[] mDescriptor;
    private int mVid;
    private int mPid;
    private byte[] mReport;
    private SparseArray<byte[]> mFeatureReports;
    private int mDuration;

    public int getId() {
        return mId;
    }

    public String getCommand() {
        return mCommand;
    }

    public String getName() {
        return mName;
    }

    public byte[] getDescriptor() {
        return mDescriptor;
    }

    public int getVendorId() {
        return mVid;
    }

    public int getProductId() {
        return mPid;
    }

    public byte[] getReport() {
        return mReport;
    }

    public SparseArray<byte[]> getFeatureReports() {
        return mFeatureReports;
    }

    public int getDuration() {
        return mDuration;
    }

    public String toString() {
        return "Event{id=" + mId
            + ", command=" + String.valueOf(mCommand)
            + ", name=" + String.valueOf(mName)
            + ", descriptor=" + Arrays.toString(mDescriptor)
            + ", vid=" + mVid
            + ", pid=" + mPid
            + ", report=" + Arrays.toString(mReport)
            + ", feature_reports=" + mFeatureReports.toString()
            + ", duration=" + mDuration
            + "}";
    }

    private static class Builder {
        private Event mEvent;

        public Builder() {
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

        public void setDescriptor(byte[] descriptor) {
            mEvent.mDescriptor = descriptor;
        }

        public void setReport(byte[] report) {
            mEvent.mReport = report;
        }

        public void setFeatureReports(SparseArray<byte[]> reports) {
            mEvent.mFeatureReports = reports;
        }

        public void setVid(int vid) {
            mEvent.mVid = vid;
        }

        public void setPid(int pid) {
            mEvent.mPid = pid;
        }

        public void setDuration(int duration) {
            mEvent.mDuration = duration;
        }

        public Event build() {
            if (mEvent.mId == -1) {
                throw new IllegalStateException("No event id");
            } else if (mEvent.mCommand == null) {
                throw new IllegalStateException("Event does not contain a command");
            }
            if (COMMAND_REGISTER.equals(mEvent.mCommand)) {
                if (mEvent.mDescriptor == null) {
                    throw new IllegalStateException("Device registration is missing descriptor");
                }
            } else if (COMMAND_DELAY.equals(mEvent.mCommand)) {
                if (mEvent.mDuration <= 0) {
                    throw new IllegalStateException("Delay has missing or invalid duration");
                }
            } else if (COMMAND_REPORT.equals(mEvent.mCommand)) {
                if (mEvent.mReport == null) {
                    throw new IllegalStateException("Report command is missing report data");
                }
            }
            return mEvent;
        }
    }

    public static class Reader {
        private JsonReader mReader;

        public Reader(InputStreamReader in) {
            mReader = new JsonReader(in);
            mReader.setLenient(true);
        }

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
                            case "descriptor":
                                eb.setDescriptor(readData());
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
                            case "report":
                                eb.setReport(readData());
                                break;
                            case "feature_reports":
                                eb.setFeatureReports(readFeatureReports());
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

        private byte[] readData() throws IOException {
            ArrayList<Integer> data = new ArrayList<Integer>();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    data.add(Integer.decode(mReader.nextString()));
                }
                mReader.endArray();
            } catch (IllegalStateException|NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            }
            byte[] rawData = new byte[data.size()];
            for (int i = 0; i < data.size(); i++) {
                int d = data.get(i);
                if ((d & 0xFF) != d) {
                    throw new IllegalStateException("Invalid data, all values must be byte-sized");
                }
                rawData[i] = (byte)d;
            }
            return rawData;
        }

        private int readInt() throws IOException {
            String val = mReader.nextString();
            return Integer.decode(val);
        }

        private SparseArray<byte[]> readFeatureReports()
                throws IllegalStateException, IOException {
            SparseArray<byte[]> featureReports = new SparseArray();
            try {
                mReader.beginArray();
                while (mReader.hasNext()) {
                    // If "id" is not specified, it defaults to 0, which means
                    // report does not contain report ID (based on HID specs).
                    int id = 0;
                    byte[] data = null;
                    mReader.beginObject();
                    while (mReader.hasNext()) {
                        String name = mReader.nextName();
                        switch (name) {
                            case "id":
                                id = readInt();
                                break;
                            case "data":
                                data = readData();
                                break;
                            default:
                                consumeRemainingElements();
                                mReader.endObject();
                                throw new IllegalStateException("Invalid key in feature report: "
                                        + name);
                        }
                    }
                    mReader.endObject();
                    if (data != null)
                        featureReports.put(id, data);
                }
                mReader.endArray();
            } catch (IllegalStateException|NumberFormatException e) {
                consumeRemainingElements();
                mReader.endArray();
                throw new IllegalStateException("Encountered malformed data.", e);
            } finally {
                return featureReports;
            }
        }

        private void consumeRemainingElements() throws IOException {
            while (mReader.hasNext()) {
                mReader.skipValue();
            }
        }
    }

    private static void error(String msg) {
        error(msg, null);
    }

    private static void error(String msg, Exception e) {
        System.out.println(msg);
        Log.e(TAG, msg);
        if (e != null) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}
