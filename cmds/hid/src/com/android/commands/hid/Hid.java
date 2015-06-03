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

import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;
import android.util.SparseArray;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

public class Hid {
    private static final String TAG = "HID";

    private final Event.Reader mReader;
    private final SparseArray<Device> mDevices;

    private static void usage() {
        error("Usage: hid [FILE]");
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            usage();
            System.exit(1);
        }

        InputStream stream = null;
        try {
            if (args[0].equals("-")) {
                stream = System.in;
            } else {
                File f = new File(args[0]);
                stream = new FileInputStream(f);
            }
            (new Hid(stream)).run();
        } catch (Exception e) {
            error("HID injection failed.", e);
            System.exit(1);
        } finally {
            IoUtils.closeQuietly(stream);
        }
    }

    private Hid(InputStream in) {
        mDevices = new SparseArray<Device>();
        try {
            mReader = new Event.Reader(new InputStreamReader(in, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void run() {
        try {
            Event e = null;
            while ((e = mReader.getNextEvent()) != null) {
                process(e);
            }
        } catch (IOException ex) {
            error("Error reading in events.", ex);
        }

        for (int i = 0; i < mDevices.size(); i++) {
            mDevices.valueAt(i).close();
        }
    }


    private void process(Event e) {
        final int index = mDevices.indexOfKey(e.getId());
        if (index >= 0) {
            Device d = mDevices.valueAt(index);
            if (Event.COMMAND_DELAY.equals(e.getCommand())) {
                d.addDelay(e.getDuration());
            } else if (Event.COMMAND_REPORT.equals(e.getCommand())) {
                d.sendReport(e.getReport());
            } else {
                error("Unknown command \"" + e.getCommand() + "\". Ignoring event.");
            }
        } else {
            registerDevice(e);
        }
    }

    private void registerDevice(Event e) {
        if (!Event.COMMAND_REGISTER.equals(e.getCommand())) {
            throw new IllegalStateException(
                    "Tried to send command \"" + e.getCommand() + "\" to an unregistered device!");
        }
        int id = e.getId();
        Device d = new Device(id, e.getName(), e.getVendorId(), e.getProductId(),
                e.getDescriptor(), e.getReport());
        mDevices.append(id, d);
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
