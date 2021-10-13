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

package com.android.systemui.flags;

import com.android.systemui.dagger.SysUISingleton;

import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;

/**
 * Concrete implementation of the a Flag manager that returns default values for debug builds
 */
@SysUISingleton
public class FeatureFlagManager implements FlagReader, FlagWriter {
    private static final String SYSPROP_PREFIX = "persist.systemui.flag_";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_VALUE = "value";
    private static final String TYPE_BOOLEAN = "boolean";
    private final SystemPropertiesHelper mSystemPropertiesHelper;

    @Inject
    public FeatureFlagManager(SystemPropertiesHelper systemPropertiesHelper) {
        mSystemPropertiesHelper = systemPropertiesHelper;
    }

    /** Return a {@link BooleanFlag}'s value. */
    public boolean isEnabled(int key, boolean defaultValue) {
        String data = mSystemPropertiesHelper.get(keyToSysPropKey(key));
        if (data.isEmpty()) {
            return defaultValue;
        }
        JSONObject json;
        try {
            json = new JSONObject(data);
            if (!assertType(json, TYPE_BOOLEAN)) {
                return defaultValue;
            }
            return json.getBoolean(FIELD_VALUE);
        } catch (JSONException e) {
            // TODO: delete the property
            return defaultValue;
        }
    }

    public void setEnabled(int key, boolean value) {
        JSONObject json = new JSONObject();
        try {
            json.put(FIELD_TYPE, TYPE_BOOLEAN);
            json.put(FIELD_VALUE, value);
            mSystemPropertiesHelper.set(keyToSysPropKey(key), json.toString());
        } catch (JSONException e) {
            // no-op
        }
    }

    public void addListener(Listener run) {}

    public void removeListener(Listener run) {}

    private static String keyToSysPropKey(int key) {
        return SYSPROP_PREFIX + key;
    }

    private static boolean assertType(JSONObject json, String type) {
        try {
            return json.getString(FIELD_TYPE).equals(TYPE_BOOLEAN);
        } catch (JSONException e) {
            return false;
        }
    }

}
