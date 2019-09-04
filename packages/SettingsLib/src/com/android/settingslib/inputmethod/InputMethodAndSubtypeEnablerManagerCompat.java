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

package com.android.settingslib.inputmethod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class InputMethodAndSubtypeEnablerManagerCompat implements
        Preference.OnPreferenceChangeListener {

    private final PreferenceFragmentCompat mFragment;

    private boolean mHaveHardKeyboard;
    private final HashMap<String, List<Preference>> mInputMethodAndSubtypePrefsMap =
            new HashMap<>();
    private final HashMap<String, TwoStatePreference> mAutoSelectionPrefsMap = new HashMap<>();
    private InputMethodManager mImm;
    // TODO: Change mInputMethodInfoList to Map
    private List<InputMethodInfo> mInputMethodInfoList;
    private final Collator mCollator = Collator.getInstance();

    public InputMethodAndSubtypeEnablerManagerCompat(PreferenceFragmentCompat fragment) {
        mFragment = fragment;
        mImm = fragment.getContext().getSystemService(InputMethodManager.class);

        mInputMethodInfoList = mImm.getInputMethodList();
    }

    public void init(PreferenceFragmentCompat fragment, String targetImi, PreferenceScreen root) {
        final Configuration config = fragment.getResources().getConfiguration();
        mHaveHardKeyboard = (config.keyboard == Configuration.KEYBOARD_QWERTY);

        for (final InputMethodInfo imi : mInputMethodInfoList) {
            // Add subtype preferences of this IME when it is specified or no IME is specified.
            if (imi.getId().equals(targetImi) || TextUtils.isEmpty(targetImi)) {
                addInputMethodSubtypePreferences(fragment, imi, root);
            }
        }
    }

    public void refresh(Context context, PreferenceFragmentCompat fragment) {
        // Refresh internal states in mInputMethodSettingValues to keep the latest
        // "InputMethodInfo"s and "InputMethodSubtype"s
        InputMethodSettingValuesWrapper
                .getInstance(context).refreshAllInputMethodAndSubtypes();
        InputMethodAndSubtypeUtilCompat.loadInputMethodSubtypeList(fragment,
                context.getContentResolver(), mInputMethodInfoList, mInputMethodAndSubtypePrefsMap);
        updateAutoSelectionPreferences();
    }

    public void save(Context context, PreferenceFragmentCompat fragment) {
        InputMethodAndSubtypeUtilCompat.saveInputMethodSubtypeList(fragment,
                context.getContentResolver(), mInputMethodInfoList, mHaveHardKeyboard);
    }

    @Override
    public boolean onPreferenceChange(final Preference pref, final Object newValue) {
        if (!(newValue instanceof Boolean)) {
            return true; // Invoke default behavior.
        }
        final boolean isChecking = (Boolean) newValue;
        for (final String imiId : mAutoSelectionPrefsMap.keySet()) {
            // An auto select subtype preference is changing.
            if (mAutoSelectionPrefsMap.get(imiId) == pref) {
                final TwoStatePreference autoSelectionPref = (TwoStatePreference) pref;
                autoSelectionPref.setChecked(isChecking);
                // Enable or disable subtypes depending on the auto selection preference.
                setAutoSelectionSubtypesEnabled(imiId, autoSelectionPref.isChecked());
                return false;
            }
        }
        // A subtype preference is changing.
        if (pref instanceof InputMethodSubtypePreference) {
            final InputMethodSubtypePreference subtypePref = (InputMethodSubtypePreference) pref;
            subtypePref.setChecked(isChecking);
            if (!subtypePref.isChecked()) {
                // It takes care of the case where no subtypes are explicitly enabled then the auto
                // selection preference is going to be checked.
                updateAutoSelectionPreferences();
            }
            return false;
        }
        return true; // Invoke default behavior.
    }

    private void addInputMethodSubtypePreferences(PreferenceFragmentCompat fragment,
            InputMethodInfo imi, final PreferenceScreen root) {
        Context prefContext = fragment.getPreferenceManager().getContext();

        final int subtypeCount = imi.getSubtypeCount();
        if (subtypeCount <= 1) {
            return;
        }
        final String imiId = imi.getId();
        final PreferenceCategory keyboardSettingsCategory =
                new PreferenceCategory(prefContext);
        root.addPreference(keyboardSettingsCategory);
        final PackageManager pm = prefContext.getPackageManager();
        final CharSequence label = imi.loadLabel(pm);

        keyboardSettingsCategory.setTitle(label);
        keyboardSettingsCategory.setKey(imiId);
        // TODO: Use toggle Preference if images are ready.
        final TwoStatePreference autoSelectionPref =
                new SwitchWithNoTextPreference(prefContext);
        mAutoSelectionPrefsMap.put(imiId, autoSelectionPref);
        keyboardSettingsCategory.addPreference(autoSelectionPref);
        autoSelectionPref.setOnPreferenceChangeListener(this);

        final PreferenceCategory activeInputMethodsCategory =
                new PreferenceCategory(prefContext);
        activeInputMethodsCategory.setTitle(R.string.active_input_method_subtypes);
        root.addPreference(activeInputMethodsCategory);

        CharSequence autoSubtypeLabel = null;
        final ArrayList<Preference> subtypePreferences = new ArrayList<>();
        for (int index = 0; index < subtypeCount; ++index) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(index);
            if (subtype.overridesImplicitlyEnabledSubtype()) {
                if (autoSubtypeLabel == null) {
                    autoSubtypeLabel = InputMethodAndSubtypeUtil.getSubtypeLocaleNameAsSentence(
                            subtype, prefContext, imi);
                }
            } else {
                final Preference subtypePref = new InputMethodSubtypePreference(
                        prefContext, subtype, imi);
                subtypePreferences.add(subtypePref);
            }
        }
        subtypePreferences.sort((lhs, rhs) -> {
            if (lhs instanceof InputMethodSubtypePreference) {
                return ((InputMethodSubtypePreference) lhs).compareTo(rhs, mCollator);
            }
            return lhs.compareTo(rhs);
        });
        for (final Preference pref : subtypePreferences) {
            activeInputMethodsCategory.addPreference(pref);
            pref.setOnPreferenceChangeListener(this);
            InputMethodAndSubtypeUtil.removeUnnecessaryNonPersistentPreference(pref);
        }
        mInputMethodAndSubtypePrefsMap.put(imiId, subtypePreferences);
        if (TextUtils.isEmpty(autoSubtypeLabel)) {
            autoSelectionPref.setTitle(
                    R.string.use_system_language_to_select_input_method_subtypes);
        } else {
            autoSelectionPref.setTitle(autoSubtypeLabel);
        }
    }

    private boolean isNoSubtypesExplicitlySelected(final String imiId) {
        final List<Preference> subtypePrefs = mInputMethodAndSubtypePrefsMap.get(imiId);
        for (final Preference pref : subtypePrefs) {
            if (pref instanceof TwoStatePreference && ((TwoStatePreference) pref).isChecked()) {
                return false;
            }
        }
        return true;
    }

    private void setAutoSelectionSubtypesEnabled(final String imiId,
            final boolean autoSelectionEnabled) {
        final TwoStatePreference autoSelectionPref = mAutoSelectionPrefsMap.get(imiId);
        if (autoSelectionPref == null) {
            return;
        }
        autoSelectionPref.setChecked(autoSelectionEnabled);
        final List<Preference> subtypePrefs = mInputMethodAndSubtypePrefsMap.get(imiId);
        for (final Preference pref : subtypePrefs) {
            if (pref instanceof TwoStatePreference) {
                // When autoSelectionEnabled is true, all subtype prefs need to be disabled with
                // implicitly checked subtypes. In case of false, all subtype prefs need to be
                // enabled.
                pref.setEnabled(!autoSelectionEnabled);
                if (autoSelectionEnabled) {
                    ((TwoStatePreference) pref).setChecked(false);
                }
            }
        }
        if (autoSelectionEnabled) {
            InputMethodAndSubtypeUtilCompat.saveInputMethodSubtypeList(
                    mFragment, mFragment.getContext().getContentResolver(),
                    mInputMethodInfoList, mHaveHardKeyboard);
            updateImplicitlyEnabledSubtypes(imiId);
        }
    }

    private void updateImplicitlyEnabledSubtypes(final String targetImiId) {
        // When targetImiId is null, apply to all subtypes of all IMEs
        for (final InputMethodInfo imi : mInputMethodInfoList) {
            final String imiId = imi.getId();
            final TwoStatePreference autoSelectionPref = mAutoSelectionPrefsMap.get(imiId);
            // No need to update implicitly enabled subtypes when the user has unchecked the
            // "subtype auto selection".
            if (autoSelectionPref == null || !autoSelectionPref.isChecked()) {
                continue;
            }
            if (imiId.equals(targetImiId) || targetImiId == null) {
                updateImplicitlyEnabledSubtypesOf(imi);
            }
        }
    }

    private void updateImplicitlyEnabledSubtypesOf(final InputMethodInfo imi) {
        final String imiId = imi.getId();
        final List<Preference> subtypePrefs = mInputMethodAndSubtypePrefsMap.get(imiId);
        final List<InputMethodSubtype> implicitlyEnabledSubtypes =
                mImm.getEnabledInputMethodSubtypeList(imi, true);
        if (subtypePrefs == null || implicitlyEnabledSubtypes == null) {
            return;
        }
        for (final Preference pref : subtypePrefs) {
            if (!(pref instanceof TwoStatePreference)) {
                continue;
            }
            final TwoStatePreference subtypePref = (TwoStatePreference) pref;
            subtypePref.setChecked(false);
            for (final InputMethodSubtype subtype : implicitlyEnabledSubtypes) {
                final String implicitlyEnabledSubtypePrefKey = imiId + subtype.hashCode();
                if (subtypePref.getKey().equals(implicitlyEnabledSubtypePrefKey)) {
                    subtypePref.setChecked(true);
                    break;
                }
            }
        }
    }

    private void updateAutoSelectionPreferences() {
        for (final String imiId : mInputMethodAndSubtypePrefsMap.keySet()) {
            setAutoSelectionSubtypesEnabled(imiId, isNoSubtypesExplicitlySelected(imiId));
        }
        updateImplicitlyEnabledSubtypes(null /* targetImiId */  /* check */);
    }
}
