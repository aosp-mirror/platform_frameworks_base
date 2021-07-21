/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.util.dump;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;

/**
 * Dump either to a proto or a print writer using the same interface.
 *
 * <p>This mirrors the interface of {@link ProtoOutputStream}.
 */
public class DualDumpOutputStream {
    private static final String LOG_TAG = DualDumpOutputStream.class.getSimpleName();

    // When writing to a proto, the proto
    private final @Nullable ProtoOutputStream mProtoStream;

    // When printing in clear text, the writer
    private final @Nullable IndentingPrintWriter mIpw;
    // Temporary storage of data when printing to mIpw
    private final LinkedList<DumpObject> mDumpObjects = new LinkedList<>();

    private static abstract class Dumpable {
        final String name;

        private Dumpable(String name) {
            this.name = name;
        }

        abstract void print(IndentingPrintWriter ipw, boolean printName);
    }

    private static class DumpObject extends Dumpable {
        private final LinkedHashMap<String, ArrayList<Dumpable>> mSubObjects = new LinkedHashMap<>();

        private DumpObject(String name) {
            super(name);
        }

        @Override
        void print(IndentingPrintWriter ipw, boolean printName) {
            if (printName) {
                ipw.println(name + "={");
            } else {
                ipw.println("{");
            }
            ipw.increaseIndent();

            for (ArrayList<Dumpable> subObject: mSubObjects.values()) {
                int numDumpables = subObject.size();

                if (numDumpables == 1) {
                    subObject.get(0).print(ipw, true);
                } else {
                    ipw.println(subObject.get(0).name + "=[");
                    ipw.increaseIndent();

                    for (int i = 0; i < numDumpables; i++) {
                        subObject.get(i).print(ipw, false);
                    }

                    ipw.decreaseIndent();
                    ipw.println("]");
                }
            }

            ipw.decreaseIndent();
            ipw.println("}");
        }

        /**
         * Add new field / subobject to this object.
         *
         * <p>If a name is added twice, they will be printed as a array
         *
         * @param fieldName name of the field added
         * @param d The dumpable to add
         */
        public void add(String fieldName, Dumpable d) {
            ArrayList<Dumpable> l = mSubObjects.get(fieldName);

            if (l == null) {
                l = new ArrayList<>(1);
                mSubObjects.put(fieldName, l);
            }

            l.add(d);
        }
    }

    private static class DumpField extends Dumpable {
        private final String mValue;

        private DumpField(String name, String value) {
            super(name);
            this.mValue = value;
        }

        @Override
        void print(IndentingPrintWriter ipw, boolean printName) {
            if (printName) {
                ipw.println(name + "=" + mValue);
            } else {
                ipw.println(mValue);
            }
        }
    }

    /**
     * Create a new DualDumpOutputStream.
     *
     * @param proto the {@link ProtoOutputStream}
     */
    public DualDumpOutputStream(@NonNull ProtoOutputStream proto) {
        mProtoStream = proto;
        mIpw = null;
    }

    /**
     * Create a new DualDumpOutputStream.
     *
     * @param ipw the {@link IndentingPrintWriter}
     */
    public DualDumpOutputStream(@NonNull IndentingPrintWriter ipw) {
        mProtoStream = null;
        mIpw = ipw;

        // Add root object
        mDumpObjects.add(new DumpObject(null));
    }

    public void write(@NonNull String fieldName, long fieldId, double val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, boolean val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, int val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, float val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, byte[] val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, Arrays.toString(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, long val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, @Nullable String val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public long start(@NonNull String fieldName, long fieldId) {
        if (mProtoStream != null) {
            return mProtoStream.start(fieldId);
        } else {
            DumpObject d = new DumpObject(fieldName);
            mDumpObjects.getLast().add(fieldName, d);
            mDumpObjects.addLast(d);
            return System.identityHashCode(d);
        }
    }

    public void end(long token) {
        if (mProtoStream != null) {
            mProtoStream.end(token);
        } else {
            if (System.identityHashCode(mDumpObjects.getLast()) != token) {
                Log.w(LOG_TAG, "Unexpected token for ending " + mDumpObjects.getLast().name
                                + " at " + Arrays.toString(Thread.currentThread().getStackTrace()));
            }
            mDumpObjects.removeLast();
        }
    }

    public void flush() {
        if (mProtoStream != null) {
            mProtoStream.flush();
        } else {
            if (mDumpObjects.size() == 1) {
                mDumpObjects.getFirst().print(mIpw, false);

                // Reset root object
                mDumpObjects.clear();
                mDumpObjects.add(new DumpObject(null));
            }

            mIpw.flush();
        }
    }

    /**
     * Add a dump from a different service into this dump.
     *
     * <p>Only for clear text dump. For proto dump use {@link #write(String, long, byte[])}.
     *
     * @param fieldName The name of the field
     * @param nestedState The state of the dump
     */
    public void writeNested(@NonNull String fieldName, byte[] nestedState) {
        if (mIpw == null) {
            Log.w(LOG_TAG, "writeNested does not work for proto logging");
            return;
        }

        mDumpObjects.getLast().add(fieldName,
                new DumpField(fieldName, (new String(nestedState, StandardCharsets.UTF_8)).trim()));
    }

    /**
     * @return {@code true} iff we are dumping to a proto
     */
    public boolean isProto() {
        return mProtoStream != null;
    }
}
