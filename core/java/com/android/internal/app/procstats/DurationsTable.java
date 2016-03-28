/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

/**
 * Sparse mapping table to store durations of processes, etc running in different
 * states.
 */
public class DurationsTable extends SparseMappingTable.Table {
    public DurationsTable(SparseMappingTable tableData) {
        super(tableData);
    }

    /**
     * Add all of the durations from the other table into this one.
     * Resultant durations will be the sum of what is currently in the table
     * and the new value.
     */
    public void addDurations(DurationsTable from) {
        final int N = from.getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = from.getKeyAt(i);
            this.addDuration(SparseMappingTable.getIdFromKey(key), from.getValue(key));
        }
    }

    /**
     * Add the value into the value stored for the state.
     *
     * Resultant duration will be the sum of what is currently in the table
     * and the new value.
     */
    public void addDuration(int state, long value) {
        final int key = getOrAddKey((byte)state, 1);
        setValue(key, getValue(key) + value);
    }

    /*
    public long getDuration(int state, long now) {
        final int key = getKey((byte)state);
        if (key != SparseMappingTable.INVALID_KEY) {
            return getValue(key);
        } else {
            return 0;
        }
    }
    */
}


