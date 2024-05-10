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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.UiThread;
import android.content.ContentResolver;
import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;

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

    private static final Object sInstanceMapLock = new Object();
    /**
     * Manages mapping between user ID and corresponding singleton
     * {@link InputMethodSettingValuesWrapper} object.
     */
    @GuardedBy("sInstanceMapLock")
    private static SparseArray<InputMethodSettingValuesWrapper> sInstanceMap = new SparseArray<>();
    private final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    private final ContentResolver mContentResolver;
    private final InputMethodManager mImm;

    @AnyThread
    @NonNull
    public static InputMethodSettingValuesWrapper getInstance(@NonNull Context context) {
        final int requestUserId = context.getUserId();
        InputMethodSettingValuesWrapper valuesWrapper;
        // First time to create the wrapper.
        synchronized (sInstanceMapLock) {
            if (sInstanceMap.size() == 0) {
                valuesWrapper = new InputMethodSettingValuesWrapper(context);
                sInstanceMap.put(requestUserId, valuesWrapper);
                return valuesWrapper;
            }
            // We have same user context as request.
            if (sInstanceMap.indexOfKey(requestUserId) >= 0) {
                return sInstanceMap.get(requestUserId);
            }
            // Request by a new user context.
            valuesWrapper = new InputMethodSettingValuesWrapper(context);
            sInstanceMap.put(context.getUserId(), valuesWrapper);
        }

        return valuesWrapper;
    }

    // Ensure singleton
    private InputMethodSettingValuesWrapper(Context context) {
        mContentResolver = context.getContentResolver();
        mImm = context.getSystemService(InputMethodManager.class);
        refreshAllInputMethodAndSubtypes();
    }

    public void refreshAllInputMethodAndSubtypes() {
        mMethodList.clear();
        List<InputMethodInfo> imis = mImm.getInputMethodListAsUser(
                mContentResolver.getUserId(), DirectBootAwareness.ANY);
        for (int i = 0; i < imis.size(); ++i) {
            InputMethodInfo imi = imis.get(i);
            if (!imi.isVirtualDeviceOnly()) {
                mMethodList.add(imi);
            }
        }
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
