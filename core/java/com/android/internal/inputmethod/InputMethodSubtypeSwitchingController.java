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

import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayDeque;

/**
 * InputMethodSubtypeSwitchingController controls the switching behavior of the subtypes.
 */
public class InputMethodSubtypeSwitchingController {
    private static final String TAG = InputMethodSubtypeSwitchingController.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int MAX_HISTORY_SIZE = 4;
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

    private final ArrayDeque<SubtypeParams> mTypedSubtypeHistory = new ArrayDeque<SubtypeParams>();

    // TODO: write unit tests for this method and the logic that determines the next subtype
    public void onCommitText(InputMethodInfo imi, InputMethodSubtype subtype) {
        synchronized(mTypedSubtypeHistory) {
            if (subtype == null) {
                Slog.w(TAG, "Invalid InputMethodSubtype: " + imi.getId() + ", " + subtype);
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "onCommitText: " + imi.getId() + ", " + subtype);
            }
            if (!imi.supportsSwitchingToNextInputMethod()) {
                Slog.w(TAG, imi.getId() + " doesn't support switching to next input method.");
                return;
            }
            if (mTypedSubtypeHistory.size() >= MAX_HISTORY_SIZE) {
                mTypedSubtypeHistory.poll();
            }
            mTypedSubtypeHistory.addFirst(new SubtypeParams(imi, subtype));
        }
    }
}
