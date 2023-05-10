/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.autofill;

import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_ADDRESS;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_DEBIT_CARD;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_GENERIC_CARD;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_PASSWORD;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_PAYMENT_CARD;
import static android.service.autofill.SaveInfo.SAVE_DATA_TYPE_USERNAME;

import android.util.ArraySet;

import java.util.Set;

/**
 * Helper class to manage autofill hints.
 * Provides utility methods like converting SaveTypes to applicable HintsConstants.
 */
public class HintsHelper {
    // Username fields
    public static final String AUTOFILL_HINT_NEW_USERNAME = "newUsername";
    public static final String AUTOFILL_HINT_USERNAME = "username";

    // Password fields
    public static final String AUTOFILL_HINT_NEW_PASSWORD = "newPassword";
    public static final String AUTOFILL_HINT_PASSWORD = "password";

    // Email hints
    public static final String AUTOFILL_HINT_EMAIL_ADDRESS = "emailAddress";

    // Phone number hints
    public static final String AUTOFILL_HINT_PHONE_COUNTRY_CODE = "phoneCountryCode";
    public static final String AUTOFILL_HINT_PHONE = "phone";
    public static final String AUTOFILL_HINT_PHONE_NATIONAL = "phoneNational";
    public static final String AUTOFILL_HINT_PHONE_NUMBER = "phoneNumber";
    public static final String AUTOFILL_HINT_PHONE_NUMBER_DEVICE = "phoneNumberDevice";

    // Credit card hints
    public static final String AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE =
            "creditCardExpirationDate";
    public static final String AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DAY = "creditCardExpirationDay";
    public static final String AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH =
            "creditCardExpirationMonth";
    public static final String AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR =
            "creditCardExpirationYear";
    public static final String AUTOFILL_HINT_CREDIT_CARD_NUMBER = "creditCardNumber";
    public static final String AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE = "creditCardSecurityCode";

    // Address hints
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS = "postalAddress";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_APT_NUMBER = "aptNumber";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_COUNTRY = "addressCountry";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_DEPENDENT_LOCALITY =
            "dependentLocality";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_ADDRESS = "extendedAddress";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_POSTAL_CODE =
            "extendedPostalCode";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_LOCALITY = "addressLocality";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_REGION = "addressRegion";
    public static final String AUTOFILL_HINT_POSTAL_ADDRESS_STREET_ADDRESS = "streetAddress";
    public static final String AUTOFILL_HINT_POSTAL_CODE = "postalCode";

    private HintsHelper() {}

    /**
     * Converts saveType to Autofill HintsConstants.
     * @param saveType
     * @return
     */
    public static Set<String> getHintsForSaveType(int saveType) {
        ArraySet<String> hintSet = new ArraySet<>();
        switch (saveType) {
            case SAVE_DATA_TYPE_PASSWORD:
                hintSet.add(AUTOFILL_HINT_NEW_USERNAME);
                hintSet.add(AUTOFILL_HINT_USERNAME);
                hintSet.add(AUTOFILL_HINT_NEW_PASSWORD);
                hintSet.add(AUTOFILL_HINT_PASSWORD);
                return hintSet;
            case SAVE_DATA_TYPE_USERNAME:
                hintSet.add(AUTOFILL_HINT_NEW_USERNAME);
                hintSet.add(AUTOFILL_HINT_USERNAME);
                return hintSet;
            case SAVE_DATA_TYPE_EMAIL_ADDRESS:
                hintSet.add(AUTOFILL_HINT_EMAIL_ADDRESS);
                return hintSet;
            case SAVE_DATA_TYPE_CREDIT_CARD:
            case SAVE_DATA_TYPE_DEBIT_CARD:
            case SAVE_DATA_TYPE_PAYMENT_CARD:
            case SAVE_DATA_TYPE_GENERIC_CARD:
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE);
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DAY);
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH);
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR);
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_NUMBER);
                hintSet.add(AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE);
                return hintSet;
            case SAVE_DATA_TYPE_ADDRESS:
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_APT_NUMBER);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_COUNTRY);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_DEPENDENT_LOCALITY);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_ADDRESS);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_POSTAL_CODE);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_LOCALITY);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_REGION);
                hintSet.add(AUTOFILL_HINT_POSTAL_ADDRESS_STREET_ADDRESS);
                hintSet.add(AUTOFILL_HINT_POSTAL_CODE);
                return hintSet;
            default:
                return hintSet;
        }
    }
}
