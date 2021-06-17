/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.inputmethod;

import static android.perftests.utils.PerfTestActivity.INTENT_EXTRA_ADD_EDIT_TEXT;

import android.content.Intent;
import android.perftests.utils.PerfTestActivity;
import android.perftests.utils.WindowPerfTestBase;

public class ImePerfTestBase extends WindowPerfTestBase {
    static final long TIMEOUT_1_S_IN_MS = 1 * 1000L;

    /** Provides an activity that contains an edit text view.*/
    static class PerfTestActivityRule extends PerfTestActivityRuleBase {

        @Override
        public PerfTestActivity launchActivity(Intent intent) {
            intent.putExtra(INTENT_EXTRA_ADD_EDIT_TEXT, true);
            return super.launchActivity(intent);
        }
    }

    static String[] buildArray(String[]... arrays) {
        int length = 0;
        for (String[] array : arrays) {
            length += array.length;
        }
        String[] newArray = new String[length];
        int offset = 0;
        for (String[] array : arrays) {
            System.arraycopy(array, 0, newArray, offset, array.length);
            offset += array.length;
        }
        return newArray;
    }
}
