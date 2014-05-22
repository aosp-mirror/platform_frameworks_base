/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.InputMethodUtils.InputMethodSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * InputMethodSubtypeSwitchingController controls the switching behavior of the subtypes.
 * <p>
 * This class is designed to be used from and only from {@link InputMethodManagerService} by using
 * {@link InputMethodManagerService#mMethodMap} as a global lock.
 * </p>
 */
public class InputMethodSubtypeSwitchingController {
    private static final String TAG = InputMethodSubtypeSwitchingController.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;

    public static class ImeSubtypeListItem implements Comparable<ImeSubtypeListItem> {
        public final CharSequence mImeName;
        public final CharSequence mSubtypeName;
        public final InputMethodInfo mImi;
        public final int mSubtypeId;
        private final boolean mIsSystemLocale;
        private final boolean mIsSystemLanguage;

        public ImeSubtypeListItem(CharSequence imeName, CharSequence subtypeName,
                InputMethodInfo imi, int subtypeId, String subtypeLocale, String systemLocale) {
            mImeName = imeName;
            mSubtypeName = subtypeName;
            mImi = imi;
            mSubtypeId = subtypeId;
            if (TextUtils.isEmpty(subtypeLocale)) {
                mIsSystemLocale = false;
                mIsSystemLanguage = false;
            } else {
                mIsSystemLocale = subtypeLocale.equals(systemLocale);
                mIsSystemLanguage = mIsSystemLocale
                        || subtypeLocale.startsWith(systemLocale.substring(0, 2));
            }
        }

        @Override
        public int compareTo(ImeSubtypeListItem other) {
            if (TextUtils.isEmpty(mImeName)) {
                return 1;
            }
            if (TextUtils.isEmpty(other.mImeName)) {
                return -1;
            }
            if (!TextUtils.equals(mImeName, other.mImeName)) {
                return mImeName.toString().compareTo(other.mImeName.toString());
            }
            if (TextUtils.equals(mSubtypeName, other.mSubtypeName)) {
                return 0;
            }
            if (mIsSystemLocale) {
                return -1;
            }
            if (other.mIsSystemLocale) {
                return 1;
            }
            if (mIsSystemLanguage) {
                return -1;
            }
            if (other.mIsSystemLanguage) {
                return 1;
            }
            if (TextUtils.isEmpty(mSubtypeName)) {
                return 1;
            }
            if (TextUtils.isEmpty(other.mSubtypeName)) {
                return -1;
            }
            return mSubtypeName.toString().compareTo(other.mSubtypeName.toString());
        }
    }

    private static class InputMethodAndSubtypeList {
        private final Context mContext;
        // Used to load label
        private final PackageManager mPm;
        private final String mSystemLocaleStr;
        private final InputMethodSettings mSettings;

        public InputMethodAndSubtypeList(Context context, InputMethodSettings settings) {
            mContext = context;
            mSettings = settings;
            mPm = context.getPackageManager();
            final Locale locale = context.getResources().getConfiguration().locale;
            mSystemLocaleStr = locale != null ? locale.toString() : "";
        }

        private final TreeMap<InputMethodInfo, List<InputMethodSubtype>> mSortedImmis =
                new TreeMap<InputMethodInfo, List<InputMethodSubtype>>(
                        new Comparator<InputMethodInfo>() {
                            @Override
                            public int compare(InputMethodInfo imi1, InputMethodInfo imi2) {
                                if (imi2 == null)
                                    return 0;
                                if (imi1 == null)
                                    return 1;
                                if (mPm == null) {
                                    return imi1.getId().compareTo(imi2.getId());
                                }
                                CharSequence imiId1 = imi1.loadLabel(mPm) + "/" + imi1.getId();
                                CharSequence imiId2 = imi2.loadLabel(mPm) + "/" + imi2.getId();
                                return imiId1.toString().compareTo(imiId2.toString());
                            }
                        });

        public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList() {
            return getSortedInputMethodAndSubtypeList(true, false, false);
        }

        public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList(
                boolean showSubtypes, boolean inputShown, boolean isScreenLocked) {
            final ArrayList<ImeSubtypeListItem> imList =
                    new ArrayList<ImeSubtypeListItem>();
            final HashMap<InputMethodInfo, List<InputMethodSubtype>> immis =
                    mSettings.getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked(
                            mContext);
            if (immis == null || immis.size() == 0) {
                return Collections.emptyList();
            }
            mSortedImmis.clear();
            mSortedImmis.putAll(immis);
            for (InputMethodInfo imi : mSortedImmis.keySet()) {
                if (imi == null) {
                    continue;
                }
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypeList = immis.get(imi);
                HashSet<String> enabledSubtypeSet = new HashSet<String>();
                for (InputMethodSubtype subtype : explicitlyOrImplicitlyEnabledSubtypeList) {
                    enabledSubtypeSet.add(String.valueOf(subtype.hashCode()));
                }
                final CharSequence imeLabel = imi.loadLabel(mPm);
                if (showSubtypes && enabledSubtypeSet.size() > 0) {
                    final int subtypeCount = imi.getSubtypeCount();
                    if (DEBUG) {
                        Slog.v(TAG, "Add subtypes: " + subtypeCount + ", " + imi.getId());
                    }
                    for (int j = 0; j < subtypeCount; ++j) {
                        final InputMethodSubtype subtype = imi.getSubtypeAt(j);
                        final String subtypeHashCode = String.valueOf(subtype.hashCode());
                        // We show all enabled IMEs and subtypes when an IME is shown.
                        if (enabledSubtypeSet.contains(subtypeHashCode)
                                && ((inputShown && !isScreenLocked) || !subtype.isAuxiliary())) {
                            final CharSequence subtypeLabel =
                                    subtype.overridesImplicitlyEnabledSubtype() ? null : subtype
                                            .getDisplayName(mContext, imi.getPackageName(),
                                                    imi.getServiceInfo().applicationInfo);
                            imList.add(new ImeSubtypeListItem(imeLabel,
                                    subtypeLabel, imi, j, subtype.getLocale(), mSystemLocaleStr));

                            // Removing this subtype from enabledSubtypeSet because we no
                            // longer need to add an entry of this subtype to imList to avoid
                            // duplicated entries.
                            enabledSubtypeSet.remove(subtypeHashCode);
                        }
                    }
                } else {
                    imList.add(new ImeSubtypeListItem(imeLabel, null, imi, NOT_A_SUBTYPE_ID, null,
                            mSystemLocaleStr));
                }
            }
            Collections.sort(imList);
            return imList;
        }
    }

    private final InputMethodSettings mSettings;
    private InputMethodAndSubtypeList mSubtypeList;

    @VisibleForTesting
    public static ImeSubtypeListItem getNextInputMethodLockedImpl(List<ImeSubtypeListItem> imList,
            boolean onlyCurrentIme, InputMethodInfo imi, InputMethodSubtype subtype) {
        if (imi == null) {
            return null;
        }
        if (imList.size() <= 1) {
            return null;
        }
        // Here we have two rotation groups, depending on the returned boolean value of
        // {@link InputMethodInfo#supportsSwitchingToNextInputMethod()}.
        final boolean expectedValueOfSupportsSwitchingToNextInputMethod =
                imi.supportsSwitchingToNextInputMethod();
        final int N = imList.size();
        final int currentSubtypeId =
                subtype != null ? InputMethodUtils.getSubtypeIdFromHashCode(imi,
                        subtype.hashCode()) : NOT_A_SUBTYPE_ID;
        for (int i = 0; i < N; ++i) {
            final ImeSubtypeListItem isli = imList.get(i);
            // Skip until the current IME/subtype is found.
            if (!isli.mImi.equals(imi) || isli.mSubtypeId != currentSubtypeId) {
                continue;
            }
            // Found the current IME/subtype. Start searching the next IME/subtype from here.
            for (int j = 0; j < N - 1; ++j) {
                final ImeSubtypeListItem candidate = imList.get((i + j + 1) % N);
                // Skip if the candidate doesn't belong to the expected rotation group.
                if (expectedValueOfSupportsSwitchingToNextInputMethod !=
                        candidate.mImi.supportsSwitchingToNextInputMethod()) {
                    continue;
                }
                // Skip if searching inside the current IME only, but the candidate is not
                // the current IME.
                if (onlyCurrentIme && !candidate.mImi.equals(imi)) {
                    continue;
                }
                return candidate;
            }
            // No appropriate IME/subtype is found in the list. Give up.
            return null;
        }
        // The current IME/subtype is not found in the list. Give up.
        return null;
    }

    private InputMethodSubtypeSwitchingController(InputMethodSettings settings, Context context) {
        mSettings = settings;
        resetCircularListLocked(context);
    }

    public static InputMethodSubtypeSwitchingController createInstanceLocked(
            InputMethodSettings settings, Context context) {
        return new InputMethodSubtypeSwitchingController(settings, context);
    }

    // TODO: write unit tests for this method and the logic that determines the next subtype
    public void onCommitTextLocked(InputMethodInfo imi, InputMethodSubtype subtype) {
        // TODO: Implement this.
    }

    public void resetCircularListLocked(Context context) {
        mSubtypeList = new InputMethodAndSubtypeList(context, mSettings);
    }

    public ImeSubtypeListItem getNextInputMethodLocked(
            boolean onlyCurrentIme, InputMethodInfo imi, InputMethodSubtype subtype) {
        return getNextInputMethodLockedImpl(mSubtypeList.getSortedInputMethodAndSubtypeList(),
                onlyCurrentIme, imi, subtype);
    }

    public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeListLocked(boolean showSubtypes,
            boolean inputShown, boolean isScreenLocked) {
        return mSubtypeList.getSortedInputMethodAndSubtypeList(
                showSubtypes, inputShown, isScreenLocked);
    }
}
