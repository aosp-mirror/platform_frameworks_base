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

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.HashMap;

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
        com.android.internal.R.string.autofill_address_summary_format,      // IDS_AUTOFILL_DIALOG_ADDRESS_SUMMARY_FORMAT
        com.android.internal.R.string.autofill_attention_ignored_re,        // IDS_AUTOFILL_ATTENTION_IGNORED_RE
        com.android.internal.R.string.autofill_region_ignored_re,           // IDS_AUTOFILL_REGION_IGNORED_RE
        com.android.internal.R.string.autofill_company_re,                  // IDS_AUTOFILL_COMPANY_RE
        com.android.internal.R.string.autofill_address_line_1_re,           // IDS_AUTOFILL_ADDRESS_LINE_1_RE
        com.android.internal.R.string.autofill_address_line_1_label_re,     // IDS_AUTOFILL_ADDRESS_LINE_1_LABEL_RE
        com.android.internal.R.string.autofill_address_line_2_re,           // IDS_AUTOFILL_ADDRESS_LINE_2_RE
        com.android.internal.R.string.autofill_address_line_3_re,           // IDS_AUTOFILL_ADDRESS_LINE_3_RE
        com.android.internal.R.string.autofill_country_re,                  // IDS_AUTOFILL_COUNTRY_RE
        com.android.internal.R.string.autofill_zip_code_re,                 // IDS_AUTOFILL_ZIP_CODE_RE
        com.android.internal.R.string.autofill_zip_4_re,                    // IDS_AUTOFILL_ZIP_4_RE
        com.android.internal.R.string.autofill_city_re,                     // IDS_AUTOFILL_CITY_RE
        com.android.internal.R.string.autofill_state_re,                    // IDS_AUTOFILL_STATE_RE
        com.android.internal.R.string.autofill_address_type_same_as_re,     // IDS_AUTOFILL_SAME_AS_RE
        com.android.internal.R.string.autofill_address_type_use_my_re,      // IDS_AUTOFILL_USE_MY_RE
        com.android.internal.R.string.autofill_billing_designator_re,       // IDS_AUTOFILL_BILLING_DESIGNATOR_RE
        com.android.internal.R.string.autofill_shipping_designator_re,      // IDS_AUTOFILL_SHIPPING_DESIGNATOR_RE
        com.android.internal.R.string.autofill_email_re,                    // IDS_AUTOFILL_EMAIL_RE
        com.android.internal.R.string.autofill_username_re,                 // IDS_AUTOFILL_USERNAME_RE
        com.android.internal.R.string.autofill_name_re,                     // IDS_AUTOFILL_NAME_RE
        com.android.internal.R.string.autofill_name_specific_re,            // IDS_AUTOFILL_NAME_SPECIFIC_RE
        com.android.internal.R.string.autofill_first_name_re,               // IDS_AUTOFILL_FIRST_NAME_RE
        com.android.internal.R.string.autofill_middle_initial_re,           // IDS_AUTOFILL_MIDDLE_INITIAL_RE
        com.android.internal.R.string.autofill_middle_name_re,              // IDS_AUTOFILL_MIDDLE_NAME_RE
        com.android.internal.R.string.autofill_last_name_re,                // IDS_AUTOFILL_LAST_NAME_RE
        com.android.internal.R.string.autofill_phone_re,                    // IDS_AUTOFILL_PHONE_RE
        com.android.internal.R.string.autofill_area_code_re,                // IDS_AUTOFILL_AREA_CODE_RE
        com.android.internal.R.string.autofill_phone_prefix_re,             // IDS_AUTOFILL_PHONE_PREFIX_RE
        com.android.internal.R.string.autofill_phone_suffix_re,             // IDS_AUTOFILL_PHONE_SUFFIX_RE
        com.android.internal.R.string.autofill_phone_extension_re,          // IDS_AUTOFILL_PHONE_EXTENSION_RE
        com.android.internal.R.string.autofill_name_on_card_re,             // IDS_AUTOFILL_NAME_ON_CARD_RE
        com.android.internal.R.string.autofill_name_on_card_contextual_re,  // IDS_AUTOFILL_NAME_ON_CARD_CONTEXTUAL_RE
        com.android.internal.R.string.autofill_card_cvc_re,                 // IDS_AUTOFILL_CARD_CVC_RE
        com.android.internal.R.string.autofill_card_number_re,              // IDS_AUTOFILL_CARD_NUMBER_RE
        com.android.internal.R.string.autofill_expiration_month_re,         // IDS_AUTOFILL_EXPIRATION_MONTH_RE
        com.android.internal.R.string.autofill_expiration_date_re,          // IDS_AUTOFILL_EXPIRATION_DATE_RE
        com.android.internal.R.string.autofill_card_ignored_re,             // IDS_AUTOFILL_CARD_IGNORED_RE
        com.android.internal.R.string.autofill_fax_re,                      // IDS_AUTOFILL_FAX_RE
        com.android.internal.R.string.autofill_country_code_re,             // IDS_AUTOFILL_COUNTRY_CODE_RE
        com.android.internal.R.string.autofill_area_code_notext_re,         // IDS_AUTOFILL_AREA_CODE_NOTEXT_RE
        com.android.internal.R.string.autofill_phone_prefix_separator_re,   // IDS_AUTOFILL_PHONE_PREFIX_SEPARATOR_RE
        com.android.internal.R.string.autofill_phone_suffix_separator_re,   // IDS_AUTOFILL_PHONE_SUFFIX_SEPARATOR_RE
        com.android.internal.R.string.autofill_province,                    // IDS_AUTOFILL_DIALOG_PROVINCE
        com.android.internal.R.string.autofill_postal_code,                 // IDS_AUTOFILL_DIALOG_POSTAL_CODE
        com.android.internal.R.string.autofill_state,                       // IDS_AUTOFILL_DIALOG_STATE
        com.android.internal.R.string.autofill_zip_code,                    // IDS_AUTOFILL_DIALOG_ZIP_CODE
        com.android.internal.R.string.autofill_county,                      // IDS_AUTOFILL_DIALOG_COUNTY
        com.android.internal.R.string.autofill_island,                      // IDS_AUTOFILL_DIALOG_ISLAND
        com.android.internal.R.string.autofill_district,                    // IDS_AUTOFILL_DIALOG_DISTRICT
        com.android.internal.R.string.autofill_department,                  // IDS_AUTOFILL_DIALOG_DEPARTMENT
        com.android.internal.R.string.autofill_prefecture,                  // IDS_AUTOFILL_DIALOG_PREFECTURE
        com.android.internal.R.string.autofill_parish,                      // IDS_AUTOFILL_DIALOG_PARISH
        com.android.internal.R.string.autofill_area,                        // IDS_AUTOFILL_DIALOG_AREA
        com.android.internal.R.string.autofill_emirate                      // IDS_AUTOFILL_DIALOG_EMIRATE
    };

    private static Context mApplicationContext;
    private static Map<Integer, SoftReference<String> > mStrings;

    public static void setApplicationContext(Context applicationContext) {
        mApplicationContext = applicationContext.getApplicationContext();
    }

    private static String loadString(int id) {
        if (mStrings == null) {
            mStrings = new HashMap<Integer, SoftReference<String> >(mIdsArray.length);
        }

        String localisedString = mApplicationContext.getResources().getString(mIdsArray[id]);
        mStrings.put(id, new SoftReference<String>(localisedString));
        return localisedString;
    }

    public static String getLocalisedString(int id) {
        if (mStrings == null) {
            // This is the first time we need a localised string.
            // loadString will create the Map.
            return loadString(id);
        }

        SoftReference<String> ref = mStrings.get(id);
        boolean needToLoad = ref == null || ref.get() == null;
        return needToLoad ? loadString(id) : ref.get();
    }
}
