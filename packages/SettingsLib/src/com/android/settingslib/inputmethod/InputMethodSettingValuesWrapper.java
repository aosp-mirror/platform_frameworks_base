/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.UiThread;
import android.content.ContentResolver;
import android.content.Context;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * This class is a wrapper for {@link InputMethodManager} and
 * {@link android.provider.Settings.Secure#ENABLED_INPUT_METHODS}. You need to refresh internal
 * states manually on some events when "InputMethodInfo"s and "InputMethodSubtype"s can be changed.
 *
 * <p>TODO: Consolidate this with {@link InputMethodAndSubtypeUtil}.</p>
 */
@UiThread
public class InputMethodSettingValuesWrapper {
    private static final String TAG = InputMethodSettingValuesWrapper.class.getSimpleName();

    private static volatile InputMethodSettingValuesWrapper sInstance;
    private final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    private final ContentResolver mContentResolver;
    private final InputMethodManager mImm;

    public static InputMethodSettingValuesWrapper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TAG) {
                if (sInstance == null) {
                    sInstance = new InputMethodSettingValuesWrapper(context);
                }
            }
        }
        return sInstance;
    }

    // Ensure singleton
    private InputMethodSettingValuesWrapper(Context context) {
        mContentResolver = context.getContentResolver();
        mImm = context.getSystemService(InputMethodManager.class);
        refreshAllInputMethodAndSubtypes();
    }

    public void refreshAllInputMethodAndSubtypes() {
        mMethodList.clear();
        mMethodList.addAll(mImm.getInputMethodList());
    }

    public List<InputMethodInfo> getInputMethodList() {
        return new ArrayList<>(mMethodList);
    }

    public boolean isAlwaysCheckedIme(InputMethodInfo imi) {
        final boolean isEnabled = isEnabledImi(imi);
        if (getEnabledInputMethodList().size() <= 1 && isEnabled) {
            return true;
        }

        final int enabledValidNonAuxAsciiCapableImeCount =
                getEnabledValidNonAuxAsciiCapableImeCount();

        return enabledValidNonAuxAsciiCapableImeCount <= 1
                && !(enabledValidNonAuxAsciiCapableImeCount == 1 && !isEnabled)
                && imi.isSystem()
                && InputMethodAndSubtypeUtil.isValidNonAuxAsciiCapableIme(imi);
    }

    private int getEnabledValidNonAuxAsciiCapableImeCount() {
        int count = 0;
        final List<InputMethodInfo> enabledImis = getEnabledInputMethodList();
        for (final InputMethodInfo imi : enabledImis) {
            if (InputMethodAndSubtypeUtil.isValidNonAuxAsciiCapableIme(imi)) {
                ++count;
            }
        }
        if (count == 0) {
            Log.w(TAG, "No \"enabledValidNonAuxAsciiCapableIme\"s found.");
        }
        return count;
    }

    public boolean isEnabledImi(InputMethodInfo imi) {
        final List<InputMethodInfo> enabledImis = getEnabledInputMethodList();
        for (final InputMethodInfo tempImi : enabledImis) {
            if (tempImi.getId().equals(imi.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the list of the enabled {@link InputMethodInfo} determined by
     * {@link android.provider.Settings.Secure#ENABLED_INPUT_METHODS} rather than just returning
     * {@link InputMethodManager#getEnabledInputMethodList()}.
     *
     * @return the list of the enabled {@link InputMethodInfo}
     */
    private ArrayList<InputMethodInfo> getEnabledInputMethodList() {
        final HashMap<String, HashSet<String>> enabledInputMethodsAndSubtypes =
                InputMethodAndSubtypeUtil.getEnabledInputMethodsAndSubtypeList(mContentResolver);
        final ArrayList<InputMethodInfo> result = new ArrayList<>();
        for (InputMethodInfo imi : mMethodList) {
            if (enabledInputMethodsAndSubtypes.keySet().contains(imi.getId())) {
                result.add(imi);
            }
        }
        return result;
    }
}
