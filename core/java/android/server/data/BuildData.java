/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.server.data;

import android.os.Build;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static com.android.internal.util.Objects.nonNull;

/**
 * Build data transfer object. Keep in sync. with the server side version.
 */
public class BuildData {

    /** The version of the data returned by write() and understood by the constructor. */
    private static final int VERSION = 0;

    private final String fingerprint;
    private final String incrementalVersion;
    private final long time;    // in *seconds* since the epoch (not msec!)

    public BuildData() {
        this.fingerprint = "android:" + Build.FINGERPRINT;
        this.incrementalVersion = Build.VERSION.INCREMENTAL;
        this.time = Build.TIME / 1000;  // msec -> sec
    }

    public BuildData(String fingerprint, String incrementalVersion, long time) {
        this.fingerprint = nonNull(fingerprint);
        this.incrementalVersion = incrementalVersion;
        this.time = time;
    }

    /*package*/ BuildData(DataInput in) throws IOException {
        int dataVersion = in.readInt();
        if (dataVersion != VERSION) {
            throw new IOException("Expected " + VERSION + ". Got: " + dataVersion);
        }

        this.fingerprint = in.readUTF();
        this.incrementalVersion = Long.toString(in.readLong());
        this.time = in.readLong();
    }

    /*package*/ void write(DataOutput out) throws IOException {
        out.writeInt(VERSION);
        out.writeUTF(fingerprint);

        // TODO: change the format/version to expect a string for this field.
        // Version 0, still used by the server side, expects a long.
        long changelist;
        try {
            changelist = Long.parseLong(incrementalVersion);
        } catch (NumberFormatException ex) {
            changelist = -1;
        }
        out.writeLong(changelist);
        out.writeLong(time);
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getIncrementalVersion() {
        return incrementalVersion;
    }

    public long getTime() {
        return time;
    }
}
