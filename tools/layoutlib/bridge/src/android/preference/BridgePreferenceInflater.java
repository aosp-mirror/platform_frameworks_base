/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.preference;

import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;

import android.content.Context;
import android.util.AttributeSet;

import java.util.Map;

public class BridgePreferenceInflater extends PreferenceInflater {

    private final Map<Preference, Object> mViewCookieMap;

    public BridgePreferenceInflater(Context context, PreferenceManager preferenceManager,
            Map<Preference, Object> viewCookieMap) {
        super(context, preferenceManager);
        mViewCookieMap = viewCookieMap;
    }

    @Override
    protected Preference onCreateItem(String name, AttributeSet attrs)
            throws ClassNotFoundException {
        Object viewKey;
        if (attrs instanceof BridgeXmlBlockParser) {
            viewKey = ((BridgeXmlBlockParser) attrs).getViewCookie();
        } else {
            viewKey = null;
        }
        Preference preference = super.onCreateItem(name, attrs);
        if (viewKey != null) {
            mViewCookieMap.put(preference, viewKey);
        }
        return preference;
    }
}
