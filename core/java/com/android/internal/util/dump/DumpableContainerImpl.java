/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.util.ArrayMap;
import android.util.Dumpable;
import android.util.DumpableContainer;
import android.util.IndentingPrintWriter;
import android.util.Log;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Helper class for {@link DumpableContainer} implementations - they can "implement it by
 * association", i.e., by delegating the interface methods to a {@code DumpableContainerImpl}.
 *
 * <p>This class is not thread safe.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class DumpableContainerImpl implements DumpableContainer {

    private static final String TAG = DumpableContainerImpl.class.getSimpleName();

    private static final boolean DEBUG = false;

    private final ArrayMap<String, Dumpable> mDumpables = new ArrayMap<>();

    @Override
    public boolean addDumpable(Dumpable dumpable) {
        Objects.requireNonNull(dumpable, "dumpable");
        String name = dumpable.getDumpableName();
        Objects.requireNonNull(name, () -> "name of" + dumpable);

        if (mDumpables.containsKey(name)) {
            if (DEBUG) {
                Log.d(TAG, "addDumpable(): ignoring " + dumpable + " as there is already a dumpable"
                        + " with that name (" + name + "): " + mDumpables.get(name));
            }
            return false;
        }

        if (DEBUG) {
            Log.d(TAG, "Adding " + name + " -> " + dumpable);
        }
        mDumpables.put(name,  dumpable);
        return true;
    }

    @Override
    public boolean removeDumpable(Dumpable dumpable) {
        Objects.requireNonNull(dumpable, "dumpable");
        String name = dumpable.getDumpableName();
        if (name == null) {
            if (DEBUG) {
                Log.d(TAG, "Tried to remove nameless dumpable: " + dumpable);
            }
            return false;
        }

        Dumpable candidate = mDumpables.get(name);
        if (candidate == null) {
            if (DEBUG) {
                Log.d(TAG, "Dumpable with name " + name + " not found");
            }
            return false;
        }

        // Make sure it's the right one
        if (candidate != dumpable) {
            Log.w(TAG, "removeDumpable(): passed dumpable (" + dumpable + ") named " + name
                    + ", but internal dumpable with that name is " + candidate);
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "Removing dumpable named " + name);
        }
        mDumpables.remove(name);
        return true;
    }

    /**
     * Dumps the number of dumpable, without a newline.
     */
    private int dumpNumberDumpables(IndentingPrintWriter writer) {
        int size = mDumpables.size();
        if (size == 0) {
            writer.print("No dumpables");
        } else {
            writer.print(size); writer.print(" dumpables");
        }
        return size;
    }

    /**
     * Lists the name of all dumpables to the given {@code writer}.
     */
    public void listDumpables(String prefix, PrintWriter writer) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, prefix, prefix);

        int size = dumpNumberDumpables(ipw);
        if (size == 0) {
            ipw.println();
            return;
        }
        ipw.print(": ");
        for (int i = 0; i < size; i++) {
            ipw.print(mDumpables.keyAt(i));
            if (i < size - 1) ipw.print(' ');
        }
        ipw.println();
    }

    /**
     * Dumps the content of all dumpables to the given {@code writer}.
     */
    public void dumpAllDumpables(String prefix, PrintWriter writer, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, prefix, prefix);
        int size = dumpNumberDumpables(ipw);
        if (size == 0) {
            ipw.println();
            return;
        }
        ipw.println(":");

        for (int i = 0; i < size; i++) {
            String dumpableName = mDumpables.keyAt(i);
            ipw.print('#'); ipw.print(i); ipw.print(": "); ipw.println(dumpableName);
            Dumpable dumpable = mDumpables.valueAt(i);
            indentAndDump(ipw, dumpable, args);
        }
    }

    private void indentAndDump(IndentingPrintWriter writer, Dumpable dumpable, String[] args) {
        writer.increaseIndent();
        try {
            dumpable.dump(writer, args);
        } finally {
            writer.decreaseIndent();
        }
    }

    /**
     * Dumps the content of a specific dumpable to the given {@code writer}.
     */
    @SuppressWarnings("resource") // cannot close ipw as it would close writer
    public void dumpOneDumpable(String prefix, PrintWriter writer, String dumpableName,
            String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, prefix, prefix);
        Dumpable dumpable = mDumpables.get(dumpableName);
        if (dumpable == null) {
            ipw.print("No "); ipw.println(dumpableName);
            return;
        }
        ipw.print(dumpableName); ipw.println(':');
        indentAndDump(ipw, dumpable, args);
    }
}
