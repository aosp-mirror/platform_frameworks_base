/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static com.android.internal.util.Objects.nonNull;

/**
 * Crash data transfer object. Keep in sync. with the server side version.
 */
public class CrashData {

    final String id;
    final String activity;
    final long time;
    final BuildData buildData;
    final ThrowableData throwableData;
    final byte[] state;

    public CrashData(String id, String activity, BuildData buildData,
            ThrowableData throwableData) {
        this.id = nonNull(id);
        this.activity = nonNull(activity);
        this.buildData = nonNull(buildData);
        this.throwableData = nonNull(throwableData);
        this.time = System.currentTimeMillis();
        this.state = null;
    }

    public CrashData(String id, String activity, BuildData buildData,
                     ThrowableData throwableData, byte[] state) {
        this.id = nonNull(id);
        this.activity = nonNull(activity);
        this.buildData = nonNull(buildData);
        this.throwableData = nonNull(throwableData);
        this.time = System.currentTimeMillis();
        this.state = state;
    }

    public CrashData(DataInput in) throws IOException {
        int dataVersion = in.readInt();
        if (dataVersion != 0 && dataVersion != 1) {
            throw new IOException("Expected 0 or 1. Got: " + dataVersion);
        }

        this.id = in.readUTF();
        this.activity = in.readUTF();
        this.time = in.readLong();
        this.buildData = new BuildData(in);
        this.throwableData = new ThrowableData(in);
        if (dataVersion == 1) {
            int len = in.readInt();
            if (len == 0) {
                this.state = null;
            } else {
                this.state = new byte[len];
                in.readFully(this.state, 0, len);
            }
        } else {
            this.state = null;
        }
    }

    public CrashData(String tag, Throwable throwable) {
        id = "";
        activity = tag;
        buildData = new BuildData();
        throwableData = new ThrowableData(throwable);
        time = System.currentTimeMillis();
        state = null;
    }

    public void write(DataOutput out) throws IOException {
        // version
        if (this.state == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
        }

        out.writeUTF(this.id);
        out.writeUTF(this.activity);
        out.writeLong(this.time);
        buildData.write(out);
        throwableData.write(out);
        if (this.state != null) {
            out.writeInt(this.state.length);
            out.write(this.state, 0, this.state.length);
        }
    }

    public BuildData getBuildData() {
        return buildData;
    }

    public ThrowableData getThrowableData() {
        return throwableData;
    }

    public String getId() {
        return id;
    }

    public String getActivity() {
        return activity;
    }

    public long getTime() {
        return time;
    }

    public byte[] getState() {
        return state;
    }
    
    /**
     * Return a brief description of this CrashData record.  The details of the
     * representation are subject to change.
     * 
     * @return Returns a String representing the contents of the object.
     */
    @Override
    public String toString() {
        return "[CrashData: id=" + id + " activity=" + activity + " time=" + time +
                " buildData=" + buildData.toString() + 
                " throwableData=" + throwableData.toString() + "]";
    }
}
