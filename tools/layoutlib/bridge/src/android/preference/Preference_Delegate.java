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

import com.android.internal.R;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

/**
 * Delegate that provides implementation for native methods in {@link Preference}
 * <p/>
 * Through the layoutlib_create tool, selected methods of Preference have been replaced by calls to
 * methods of the same name in this delegate class.
 */
public class Preference_Delegate {

    @LayoutlibDelegate
    /*package*/ static View getView(Preference pref, View convertView, ViewGroup parent) {
        Context context = pref.getContext();
        BridgeContext bc = context instanceof BridgeContext ? ((BridgeContext) context) : null;
        convertView = pref.getView_Original(convertView, parent);
        if (bc != null) {
            Object cookie = bc.getCookie(pref);
            if (cookie != null) {
                bc.addViewKey(convertView, cookie);
            }
        }
        return convertView;
    }

    /**
     * Inflates the parser and returns the ListView containing the Preferences.
     */
    public static View inflatePreference(Context context, XmlPullParser parser, ViewGroup root) {
        PreferenceManager pm = new PreferenceManager(context);
        PreferenceInflater inflater = new BridgePreferenceInflater(context, pm);
        PreferenceScreen ps = (PreferenceScreen) inflater.inflate(parser, null, true);
        pm.setPreferences(ps);
        ListView preferenceView = createContainerView(context, root);
        ps.bind(preferenceView);
        return preferenceView;
    }

    private static ListView createContainerView(Context context, ViewGroup root) {
        TypedArray a = context.obtainStyledAttributes(null, R.styleable.PreferenceFragment,
                R.attr.preferenceFragmentStyle, 0);
        int mLayoutResId = a.getResourceId(R.styleable.PreferenceFragment_layout,
                        R.layout.preference_list_fragment);
        a.recycle();

        LayoutInflater inflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(mLayoutResId, root, true);

        return (ListView) root.findViewById(android.R.id.list);
    }
}
