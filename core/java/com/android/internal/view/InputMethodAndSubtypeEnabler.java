/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class InputMethodAndSubtypeEnabler extends PreferenceActivity {

    private boolean mHaveHardKeyboard;

    private List<InputMethodInfo> mInputMethodProperties;

    private final TextUtils.SimpleStringSplitter mStringColonSplitter
            = new TextUtils.SimpleStringSplitter(':');

    private String mLastInputMethodId;
    private String mLastTickedInputMethodId;

    private AlertDialog mDialog = null;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Configuration config = getResources().getConfiguration();
        mHaveHardKeyboard = (config.keyboard == Configuration.KEYBOARD_QWERTY);
        onCreateIMM();
        setPreferenceScreen(createPreferenceHierarchy());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadInputMethodSubtypeList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveInputMethodSubtypeList();
    }

    @Override
    public boolean onPreferenceTreeClick(
            PreferenceScreen preferenceScreen, Preference preference) {

        if (preference instanceof CheckBoxPreference) {
            final CheckBoxPreference chkPref = (CheckBoxPreference) preference;
            final String id = chkPref.getKey();
            // TODO: Check subtype or not here
            if (chkPref.isChecked()) {
                InputMethodInfo selImi = null;
                final int N = mInputMethodProperties.size();
                for (int i = 0; i < N; i++) {
                    InputMethodInfo imi = mInputMethodProperties.get(i);
                    if (id.equals(imi.getId())) {
                        selImi = imi;
                        if (isSystemIme(imi)) {
                            setSubtypesPreferenceEnabled(id, true);
                            // This is a built-in IME, so no need to warn.
                            mLastTickedInputMethodId = id;
                            return super.onPreferenceTreeClick(preferenceScreen, preference);
                        }
                        break;
                    }
                }
                if (selImi == null) {
                    return super.onPreferenceTreeClick(preferenceScreen, preference);
                }
                chkPref.setChecked(false);
                if (mDialog == null) {
                    mDialog = (new AlertDialog.Builder(this))
                            .setTitle(android.R.string.dialog_alert_title)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setCancelable(true)
                            .setPositiveButton(android.R.string.ok,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int which) {
                                            chkPref.setChecked(true);
                                            setSubtypesPreferenceEnabled(id, true);
                                            mLastTickedInputMethodId = id;
                                        }

                            })
                            .setNegativeButton(android.R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int which) {
                                        }

                            })
                            .create();
                } else {
                    if (mDialog.isShowing()) {
                        mDialog.dismiss();
                    }
                }
                mDialog.setMessage(getResources().getString(
                        com.android.internal.R.string.ime_enabler_security_warning,
                        selImi.getServiceInfo().applicationInfo.loadLabel(getPackageManager())));
                mDialog.show();
            } else {
                if (id.equals(mLastTickedInputMethodId)) {
                    mLastTickedInputMethodId = null;
                }
                setSubtypesPreferenceEnabled(id, false);
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void onCreateIMM() {
        InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);

        // TODO: Change mInputMethodProperties to Map
        mInputMethodProperties = imm.getInputMethodList();

        mLastInputMethodId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    private PreferenceScreen createPreferenceHierarchy() {
        // Root
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(this);

        int N = (mInputMethodProperties == null ? 0 : mInputMethodProperties.size());
        // TODO: Use iterator.
        for (int i = 0; i < N; ++i) {
            PreferenceCategory keyboardSettingsCategory = new PreferenceCategory(this);
            root.addPreference(keyboardSettingsCategory);
            InputMethodInfo property = mInputMethodProperties.get(i);
            String prefKey = property.getId();

            PackageManager pm = getPackageManager();
            CharSequence label = property.loadLabel(pm);
            boolean systemIME = isSystemIme(property);

            keyboardSettingsCategory.setTitle(label);

            // Add a check box.
            // Don't show the toggle if it's the only keyboard in the system, or it's a system IME.
            if (mHaveHardKeyboard || (N > 1 && !systemIME)) {
                CheckBoxPreference chkbxPref = new CheckBoxPreference(this);
                chkbxPref.setKey(prefKey);
                chkbxPref.setTitle(label);
                keyboardSettingsCategory.addPreference(chkbxPref);
            }

            ArrayList<InputMethodSubtype> subtypes = property.getSubtypes();
            if (subtypes.size() > 0) {
                PreferenceCategory subtypesCategory = new PreferenceCategory(this);
                subtypesCategory.setTitle(getResources().getString(
                        com.android.internal.R.string.ime_enabler_subtype_title, label));
                root.addPreference(subtypesCategory);
                for (InputMethodSubtype subtype: subtypes) {
                    CharSequence subtypeLabel;
                    int nameResId = subtype.getNameResId();
                    if (nameResId != 0) {
                        subtypeLabel = pm.getText(property.getPackageName(), nameResId,
                                property.getServiceInfo().applicationInfo);
                    } else {
                        int modeResId = subtype.getModeResId();
                        CharSequence language = subtype.getLocale();
                        CharSequence mode = modeResId == 0 ? null
                                : pm.getText(property.getPackageName(), modeResId,
                                        property.getServiceInfo().applicationInfo);
                        // TODO: Use more friendly Title and UI
                        subtypeLabel = (mode == null ? "" : mode) + ","
                                + (language == null ? "" : language);
                    }
                    CheckBoxPreference chkbxPref = new CheckBoxPreference(this);
                    chkbxPref.setKey(prefKey + subtype.hashCode());
                    chkbxPref.setTitle(subtypeLabel);
                    chkbxPref.setSummary(label);
                    subtypesCategory.addPreference(chkbxPref);
                }
            }
        }
        return root;
    }

    private void loadInputMethodSubtypeList() {
        final HashSet<String> enabled = new HashSet<String>();
        String enabledStr = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        if (enabledStr != null) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(enabledStr);
            while (splitter.hasNext()) {
                enabled.add(splitter.next());
            }
        }

        // Update the statuses of the Check Boxes.
        int N = mInputMethodProperties.size();
        // TODO: Use iterator.
        for (int i = 0; i < N; ++i) {
            final String id = mInputMethodProperties.get(i).getId();
            CheckBoxPreference pref = (CheckBoxPreference) findPreference(
                    mInputMethodProperties.get(i).getId());
            if (pref != null) {
                boolean isEnabled = enabled.contains(id);
                pref.setChecked(isEnabled);
                setSubtypesPreferenceEnabled(id, isEnabled);
            }
        }
        mLastTickedInputMethodId = null;
    }

    private void saveInputMethodSubtypeList() {
        StringBuilder builder = new StringBuilder();
        StringBuilder disabledSysImes = new StringBuilder();

        int firstEnabled = -1;
        int N = mInputMethodProperties.size();
        for (int i = 0; i < N; ++i) {
            final InputMethodInfo property = mInputMethodProperties.get(i);
            final String id = property.getId();
            CheckBoxPreference pref = (CheckBoxPreference) findPreference(id);
            boolean currentInputMethod = id.equals(mLastInputMethodId);
            boolean systemIme = isSystemIme(property);
            // TODO: Append subtypes by using the separator ";"
            if (((N == 1 || systemIme) && !mHaveHardKeyboard)
                    || (pref != null && pref.isChecked())) {
                if (builder.length() > 0) builder.append(':');
                builder.append(id);
                if (firstEnabled < 0) {
                    firstEnabled = i;
                }
            } else if (currentInputMethod) {
                mLastInputMethodId = mLastTickedInputMethodId;
            }
            // If it's a disabled system ime, add it to the disabled list so that it
            // doesn't get enabled automatically on any changes to the package list
            if (pref != null && !pref.isChecked() && systemIme && mHaveHardKeyboard) {
                if (disabledSysImes.length() > 0) disabledSysImes.append(":");
                disabledSysImes.append(id);
            }
        }

        // If the last input method is unset, set it as the first enabled one.
        if (TextUtils.isEmpty(mLastInputMethodId)) {
            if (firstEnabled >= 0) {
                mLastInputMethodId = mInputMethodProperties.get(firstEnabled).getId();
            } else {
                mLastInputMethodId = null;
            }
        }

        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS, builder.toString());
        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS, disabledSysImes.toString());
        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD,
                mLastInputMethodId != null ? mLastInputMethodId : "");
    }

    private void setSubtypesPreferenceEnabled(String id, boolean enabled) {
        PreferenceScreen preferenceScreen = getPreferenceScreen();
        final int N = mInputMethodProperties.size();
        // TODO: Use iterator.
        for (int i = 0; i < N; i++) {
            InputMethodInfo imi = mInputMethodProperties.get(i);
            if (id.equals(imi.getId())) {
                for (InputMethodSubtype subtype: imi.getSubtypes()) {
                    preferenceScreen.findPreference(id + subtype.hashCode()).setEnabled(enabled);
                }
            }
        }
    }

    private boolean isSystemIme(InputMethodInfo property) {
        return (property.getServiceInfo().applicationInfo.flags
                & ApplicationInfo.FLAG_SYSTEM) != 0;
    }
}
