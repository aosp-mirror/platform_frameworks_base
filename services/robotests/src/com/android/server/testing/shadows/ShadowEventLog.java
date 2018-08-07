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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.util.EventLog;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

@Implements(EventLog.class)
public class ShadowEventLog {
    private final static LinkedHashSet<Entry> ENTRIES = new LinkedHashSet<>();

    @Implementation
    public static int writeEvent(int tag, Object... values) {
        ENTRIES.add(new Entry(tag, Arrays.asList(values)));
        // Currently we don't care about the return value, if we do, estimate it correctly
        return 0;
    }

    public static boolean hasEvent(int tag, Object... values) {
        return ENTRIES.contains(new Entry(tag, Arrays.asList(values)));
    }

    /** Clears the entries */
    public static void setUp() {
        ENTRIES.clear();
    }

    public static class Entry {
        public final int tag;
        public final List<Object> values;

        public Entry(int tag, List<Object> values) {
            this.tag = tag;
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entry entry = (Entry) o;
            return tag == entry.tag && values.equals(entry.values);
        }

        @Override
        public int hashCode() {
            int result = tag;
            result = 31 * result + values.hashCode();
            return result;
        }
    }
}
