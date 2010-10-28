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

package android.webkit;

import android.content.Context;

import java.util.List;
import java.util.Vector;

/**
 * @hide
 */
public class L10nUtils {

    // These array elements must be kept in sync with those defined in
    // external/chromium/android/app/l10n_utils.h
    private static int[] mIdsArray = {
        com.android.internal.R.string.autofill_address_name_separator,      // IDS_AUTOFILL_DIALOG_ADDRESS_NAME_SEPARATOR
        com.android.internal.R.string.autofill_address_summary_name_format, // IDS_AUTOFILL_DIALOG_ADDRESS_SUMMARY_NAME_FORMAT
        com.android.internal.R.string.autofill_address_summary_separator,   // IDS_AUTOFILL_DIALOG_ADDRESS_SUMMARY_SEPARATOR
        com.android.internal.R.string.autofill_address_summary_format       // IDS_AUTOFILL_DIALOG_ADDRESS_SUMMARY_FORMAT
    };

    private static List<String> mStrings;

    public static void loadStrings(Context context) {
        if (mStrings != null) {
            return;
        }

        mStrings = new Vector<String>(mIdsArray.length);
        for (int i = 0; i < mIdsArray.length; i++) {
            mStrings.add(context.getResources().getString(mIdsArray[i]));
        }
    }

    public static String getLocalisedString(int id) {
        return mStrings.get(id);
    }
}
