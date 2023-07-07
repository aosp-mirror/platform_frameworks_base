/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.core;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public class KeyValueMap extends HashMap<String, Object> {

    public void setKeyValues(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new RuntimeException("Key-Value arguments passed into setKeyValues must be "
            + "an alternating list of keys and values!");
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!(keyValues[i] instanceof String)) {
                throw new RuntimeException("Key-value argument " + i + " must be a key of type "
                    + "String, but found an object of type " + keyValues[i].getClass() + "!");
            }
            String key = (String)keyValues[i];
            Object value = keyValues[i+1];
            put(key, value);
        }
    }

    public static KeyValueMap fromKeyValues(Object... keyValues) {
        KeyValueMap result = new KeyValueMap();
        result.setKeyValues(keyValues);
        return result;
    }

    public String getString(String key) {
        Object result = get(key);
        return result != null ? (String)result : null;
    }

    public int getInt(String key) {
        Object result = get(key);
        return result != null ? (Integer) result : 0;
    }

    public float getFloat(String key) {
        Object result = get(key);
        return result != null ? (Float) result : 0;
    }

    @Override
    public String toString() {
        StringWriter writer = new StringWriter();
        for (Map.Entry<String, Object> entry : entrySet()) {
            String valueString;
            Object value = entry.getValue();
            if (value instanceof String) {
                valueString = "\"" + value + "\"";
            } else {
                valueString = value.toString();
            }
            writer.write(entry.getKey() + " = " + valueString + ";\n");
        }
        return writer.toString();
    }

}
