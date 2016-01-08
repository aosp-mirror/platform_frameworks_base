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

package com.android.preload;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the collected data for a process.
 */
public class DumpData {
    /**
     * Name of the package (=application).
     */
    String packageName;

    /**
     * A map of class name to a string for the classloader. This may be a toString equivalent,
     * or just a unique ID.
     */
    Map<String, String> dumpData;

    /**
     * The Date when this data was captured. Mostly for display purposes.
     */
    Date date;

    /**
     * A cached value for the number of boot classpath classes (classloader value in dumpData is
     * null).
     */
    int bcpClasses;

    public DumpData(String packageName, Map<String, String> dumpData, Date date) {
        this.packageName = packageName;
        this.dumpData = dumpData;
        this.date = date;

        countBootClassPath();
    }

    public String getPackageName() {
        return packageName;
    }

    public Date getDate() {
        return date;
    }

    public Map<String, String> getDumpData() {
        return dumpData;
    }

    public void countBootClassPath() {
        bcpClasses = 0;
        for (Map.Entry<String, String> e : dumpData.entrySet()) {
            if (e.getValue() == null) {
                bcpClasses++;
            }
        }
    }

    // Return an inverted mapping.
    public Map<String, Set<String>> invertData() {
        Map<String, Set<String>> ret = new HashMap<>();
        for (Map.Entry<String, String> e : dumpData.entrySet()) {
            if (!ret.containsKey(e.getValue())) {
                ret.put(e.getValue(), new HashSet<String>());
            }
            ret.get(e.getValue()).add(e.getKey());
        }
        return ret;
    }
}