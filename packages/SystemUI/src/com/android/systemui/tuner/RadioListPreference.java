/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toolbar;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.fragments.FragmentService;

import java.util.Objects;

public class RadioListPreference extends CustomListPreference {

    private OnClickListener mOnClickListener;
    private CharSequence mSummary;

    public RadioListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onPrepareDialogBuilder(Builder builder, OnClickListener listener) {
        mOnClickListener = listener;
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(summary);
        mSummary = summary;
    }

    @Override
    public CharSequence getSummary() {
        if (mSummary == null || mSummary.toString().contains("%s")) {
            return super.getSummary();
        }
        return mSummary;
    }

    @Override
    protected Dialog onDialogCreated(DialogFragment fragment, Dialog dialog) {
        Dialog d = new Dialog(getContext(), android.R.style.Theme_DeviceDefault_Settings);
        Toolbar t = (Toolbar) d.findViewById(com.android.internal.R.id.action_bar);
        View v = new View(getContext());
        v.setId(R.id.content);
        d.setContentView(v);
        t.setTitle(getTitle());
        t.setNavigationIcon(Utils.getDrawable(d.getContext(), android.R.attr.homeAsUpIndicator));
        t.setNavigationOnClickListener(view -> d.dismiss());

        RadioFragment f = new RadioFragment();
        f.setPreference(this);
        Dependency.get(FragmentService.class).getFragmentHostManager(v).getFragmentManager()
                .beginTransaction()
                .add(android.R.id.content, f)
                .commit();
        return d;
    }

    @Override
    protected void onDialogStateRestored(DialogFragment fragment, Dialog dialog,
            Bundle savedInstanceState) {
        super.onDialogStateRestored(fragment, dialog, savedInstanceState);
        View view = dialog.findViewById(R.id.content);
        RadioFragment radioFragment = (RadioFragment) Dependency.get(FragmentService.class)
                .getFragmentHostManager(view)
                .getFragmentManager()
                .findFragmentById(R.id.content);
        if (radioFragment != null) {
            radioFragment.setPreference(this);
        }
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
    }

    public static class RadioFragment extends TunerPreferenceFragment {
        private RadioListPreference mListPref;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getPreferenceManager().getContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);
            if (mListPref != null) {
                update();
            }
        }

        private void update() {
            Context context = getPreferenceManager().getContext();

            CharSequence[] entries = mListPref.getEntries();
            CharSequence[] values = mListPref.getEntryValues();
            CharSequence current = mListPref.getValue();
            for (int i = 0; i < entries.length; i++) {
                CharSequence entry = entries[i];
                SelectablePreference pref = new SelectablePreference(context);
                getPreferenceScreen().addPreference(pref);
                pref.setTitle(entry);
                pref.setChecked(Objects.equals(current, values[i]));
                pref.setKey(String.valueOf(i));
            }
        }

        @Override
        public boolean onPreferenceTreeClick(Preference preference) {
            mListPref.mOnClickListener.onClick(null, Integer.parseInt(preference.getKey()));
            return true;
        }

        public void setPreference(RadioListPreference radioListPreference) {
            mListPref = radioListPreference;
            if (getPreferenceManager() != null) {
                update();
            }
        }
    }
}
