/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.test.ravenwood;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A class to store system properties defined by tests.
 */
public class RavenwoodTestProperties {
    final Map<String, String> mValues = new HashMap<>();

    /** Set of additional keys that should be considered readable */
    final Set<String> mKeyReadable = new HashSet<>();

    /** Set of additional keys that should be considered writable */
    final Set<String> mKeyWritable = new HashSet<>();

    public void setValue(String key, Object value) {
        final String valueString = (value == null) ? null : String.valueOf(value);
        if ((valueString == null) || valueString.isEmpty()) {
            mValues.remove(key);
        } else {
            mValues.put(key, valueString);
        }
    }

    public void setAccessNone(String key) {
        mKeyReadable.remove(key);
        mKeyWritable.remove(key);
    }

    public void setAccessReadOnly(String key) {
        mKeyReadable.add(key);
        mKeyWritable.remove(key);
    }

    public void setAccessReadWrite(String key) {
        mKeyReadable.add(key);
        mKeyWritable.add(key);
    }
}
