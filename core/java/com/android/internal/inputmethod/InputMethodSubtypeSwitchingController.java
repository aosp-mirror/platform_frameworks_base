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

import com.android.internal.inputmethod.InputMethodUtils.InputMethodSettings;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayDeque;
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
 */
public class InputMethodSubtypeSwitchingController {
    private static final String TAG = InputMethodSubtypeSwitchingController.class.getSimpleName();
    private static final boolean DEBUG = false;
    // TODO: Turn on this flag and add CTS when the platform starts expecting that all IMEs return
    // true for supportsSwitchingToNextInputMethod().
    private static final boolean REQUIRE_SWITCHING_SUPPORT = false;
    private static final int MAX_HISTORY_SIZE = 4;
    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;

    private static class SubtypeParams {
        public final InputMethodInfo mImi;
        public final InputMethodSubtype mSubtype;
        public final long mTime;

        public SubtypeParams(InputMethodInfo imi, InputMethodSubtype subtype) {
            mImi = imi;
            mSubtype = subtype;
            mTime = System.currentTimeMillis();
        }
    }

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

    private static class InputMethodAndSubtypeCircularList {
        private final Context mContext;
        // Used to load label
        private final PackageManager mPm;
        private final String mSystemLocaleStr;
        private final InputMethodSettings mSettings;

        public InputMethodAndSubtypeCircularList(Context context, InputMethodSettings settings) {
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

        public ImeSubtypeListItem getNextInputMethod(
                boolean onlyCurrentIme, InputMethodInfo imi, InputMethodSubtype subtype) {
            if (imi == null) {
                return null;
            }
            final List<ImeSubtypeListItem> imList =
                    getSortedInputMethodAndSubtypeList();
            if (imList.size() <= 1) {
                return null;
            }
            final int N = imList.size();
            final int currentSubtypeId =
                    subtype != null ? InputMethodUtils.getSubtypeIdFromHashCode(imi,
                            subtype.hashCode()) : NOT_A_SUBTYPE_ID;
            for (int i = 0; i < N; ++i) {
                final ImeSubtypeListItem isli = imList.get(i);
                if (isli.mImi.equals(imi) && isli.mSubtypeId == currentSubtypeId) {
                    if (!onlyCurrentIme) {
                        return imList.get((i + 1) % N);
                    }
                    for (int j = 0; j < N - 1; ++j) {
                        final ImeSubtypeListItem candidate = imList.get((i + j + 1) % N);
                        if (candidate.mImi.equals(imi)) {
                            return candidate;
                        }
                    }
                    return null;
                }
            }
            return null;
        }

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

    private final ArrayDeque<SubtypeParams> mTypedSubtypeHistory = new ArrayDeque<SubtypeParams>();
    private final Object mLock = new Object();
    private final InputMethodSettings mSettings;
    private InputMethodAndSubtypeCircularList mSubtypeList;

    public InputMethodSubtypeSwitchingController(InputMethodSettings settings) {
        mSettings = settings;
    }

    // TODO: write unit tests for this method and the logic that determines the next subtype
    public void onCommitText(InputMethodInfo imi, InputMethodSubtype subtype) {
        synchronized (mTypedSubtypeHistory) {
            if (subtype == null) {
                Slog.w(TAG, "Invalid InputMethodSubtype: " + imi.getId() + ", " + subtype);
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "onCommitText: " + imi.getId() + ", " + subtype);
            }
            if (REQUIRE_SWITCHING_SUPPORT) {
                if (!imi.supportsSwitchingToNextInputMethod()) {
                    Slog.w(TAG, imi.getId() + " doesn't support switching to next input method.");
                    return;
                }
            }
            if (mTypedSubtypeHistory.size() >= MAX_HISTORY_SIZE) {
                mTypedSubtypeHistory.poll();
            }
            mTypedSubtypeHistory.addFirst(new SubtypeParams(imi, subtype));
        }
    }

    public void resetCircularListLocked(Context context) {
        synchronized(mLock) {
            mSubtypeList = new InputMethodAndSubtypeCircularList(context, mSettings);
        }
    }

    public ImeSubtypeListItem getNextInputMethod(
            boolean onlyCurrentIme, InputMethodInfo imi, InputMethodSubtype subtype) {
        synchronized(mLock) {
            return mSubtypeList.getNextInputMethod(onlyCurrentIme, imi, subtype);
        }
    }

    public List<ImeSubtypeListItem> getSortedInputMethodAndSubtypeList(boolean showSubtypes,
            boolean inputShown, boolean isScreenLocked) {
        synchronized(mLock) {
            return mSubtypeList.getSortedInputMethodAndSubtypeList(
                    showSubtypes, inputShown, isScreenLocked);
        }
    }
}
