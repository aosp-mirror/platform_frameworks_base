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

import android.app.ActivityManager;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.InputMethodUtils;
import com.android.internal.inputmethod.InputMethodUtils.InputMethodSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * This class is a wrapper for InputMethodSettings. You need to refresh internal states
 * manually on some events when "InputMethodInfo"s and "InputMethodSubtype"s can be
 * changed.
 */
// TODO: Consolidate this with {@link InputMethodAndSubtypeUtil}.
public class InputMethodSettingValuesWrapper {
    private static final String TAG = InputMethodSettingValuesWrapper.class.getSimpleName();

    private static volatile InputMethodSettingValuesWrapper sInstance;
    private final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    private final HashMap<String, InputMethodInfo> mMethodMap = new HashMap<>();
    private final InputMethodSettings mSettings;
    private final InputMethodManager mImm;
    private final HashSet<InputMethodInfo> mAsciiCapableEnabledImis = new HashSet<>();

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

    private static int getDefaultCurrentUserId() {
        try {
            return ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }
        return 0;
    }

    // Ensure singleton
    private InputMethodSettingValuesWrapper(Context context) {
        mSettings = new InputMethodSettings(context.getResources(), context.getContentResolver(),
                mMethodMap, mMethodList, getDefaultCurrentUserId(), false /* copyOnWrite */);
        mImm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        refreshAllInputMethodAndSubtypes();
    }

    public void refreshAllInputMethodAndSubtypes() {
        synchronized (mMethodMap) {
            mMethodList.clear();
            mMethodMap.clear();
            final List<InputMethodInfo> imms = mImm.getInputMethodList();
            mMethodList.addAll(imms);
            for (InputMethodInfo imi : imms) {
                mMethodMap.put(imi.getId(), imi);
            }
            updateAsciiCapableEnabledImis();
        }
    }

    // TODO: Add a cts to ensure at least one AsciiCapableSubtypeEnabledImis exist
    private void updateAsciiCapableEnabledImis() {
        synchronized (mMethodMap) {
            mAsciiCapableEnabledImis.clear();
            final List<InputMethodInfo> enabledImis = mSettings.getEnabledInputMethodListLocked();
            for (final InputMethodInfo imi : enabledImis) {
                final int subtypeCount = imi.getSubtypeCount();
                for (int i = 0; i < subtypeCount; ++i) {
                    final InputMethodSubtype subtype = imi.getSubtypeAt(i);
                    if (InputMethodUtils.SUBTYPE_MODE_KEYBOARD.equalsIgnoreCase(subtype.getMode())
                            && subtype.isAsciiCapable()) {
                        mAsciiCapableEnabledImis.add(imi);
                        break;
                    }
                }
            }
        }
    }

    public List<InputMethodInfo> getInputMethodList() {
        synchronized (mMethodMap) {
            return mMethodList;
        }
    }

    public boolean isAlwaysCheckedIme(InputMethodInfo imi, Context context) {
        final boolean isEnabled = isEnabledImi(imi);
        synchronized (mMethodMap) {
            if (mSettings.getEnabledInputMethodListLocked().size() <= 1 && isEnabled) {
                return true;
            }
        }

        final int enabledValidSystemNonAuxAsciiCapableImeCount =
                getEnabledValidSystemNonAuxAsciiCapableImeCount(context);

        return enabledValidSystemNonAuxAsciiCapableImeCount <= 1
                && !(enabledValidSystemNonAuxAsciiCapableImeCount == 1 && !isEnabled)
                && imi.isSystem()
                && isValidSystemNonAuxAsciiCapableIme(imi, context);

    }

    private int getEnabledValidSystemNonAuxAsciiCapableImeCount(Context context) {
        int count = 0;
        final List<InputMethodInfo> enabledImis;
        synchronized (mMethodMap) {
            enabledImis = mSettings.getEnabledInputMethodListLocked();
        }
        for (final InputMethodInfo imi : enabledImis) {
            if (isValidSystemNonAuxAsciiCapableIme(imi, context)) {
                ++count;
            }
        }
        if (count == 0) {
            Log.w(TAG, "No \"enabledValidSystemNonAuxAsciiCapableIme\"s found.");
        }
        return count;
    }

    public boolean isEnabledImi(InputMethodInfo imi) {
        final List<InputMethodInfo> enabledImis;
        synchronized (mMethodMap) {
            enabledImis = mSettings.getEnabledInputMethodListLocked();
        }
        for (final InputMethodInfo tempImi : enabledImis) {
            if (tempImi.getId().equals(imi.getId())) {
                return true;
            }
        }
        return false;
    }

    public boolean isValidSystemNonAuxAsciiCapableIme(InputMethodInfo imi, Context context) {
        if (imi.isAuxiliaryIme()) {
            return false;
        }
        final Locale systemLocale = context.getResources().getConfiguration().locale;
        if (InputMethodUtils.isSystemImeThatHasSubtypeOf(imi, context,
                    true /* checkDefaultAttribute */, systemLocale, false /* checkCountry */,
                    InputMethodUtils.SUBTYPE_MODE_ANY)) {
            return true;
        }
        if (mAsciiCapableEnabledImis.isEmpty()) {
            Log.w(TAG, "ascii capable subtype enabled imi not found. Fall back to English"
                    + " Keyboard subtype.");
            return InputMethodUtils.containsSubtypeOf(imi, Locale.ENGLISH, false /* checkCountry */,
                    InputMethodUtils.SUBTYPE_MODE_KEYBOARD);
        }
        return mAsciiCapableEnabledImis.contains(imi);
    }
}
