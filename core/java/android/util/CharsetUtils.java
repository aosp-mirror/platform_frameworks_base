/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.util;

import android.os.Build;
import android.text.TextUtils;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * A class containing utility methods related to character sets. This
 * class is primarily useful for code that wishes to be vendor-aware
 * in its interpretation of Japanese charset names (used in DoCoMo,
 * KDDI, and SoftBank).
 * </p>
 *
 * <p>
 * <b>Note:</b> Developers will need to add an appropriate mapping for
 * each vendor-specific charset. You may need to modify the C libraries
 * like icu4c in order to let Android support an additional charset.
 * </p>
 *
 * @hide
 */
public final class CharsetUtils {
    /**
     * name of the vendor "DoCoMo". <b>Note:</b> This isn't a public
     * constant, in order to keep this class from becoming a de facto
     * reference list of vendor names.
     */
    private static final String VENDOR_DOCOMO = "docomo";
    /**
     * Name of the vendor "KDDI".
     */
    private static final String VENDOR_KDDI = "kddi";
    /**
     * Name of the vendor "SoftBank".
     */
    private static final String VENDOR_SOFTBANK = "softbank";

    /**
     * Represents one-to-one mapping from a vendor name to a charset specific to the vendor.
     */
    private static final Map<String, String> sVendorShiftJisMap = new HashMap<String, String>();

    static {
        // These variants of Shift_JIS come from icu's mapping data (convrtrs.txt)
        sVendorShiftJisMap.put(VENDOR_DOCOMO, "docomo-shift_jis-2007");
        sVendorShiftJisMap.put(VENDOR_KDDI, "kddi-shift_jis-2007");
        sVendorShiftJisMap.put(VENDOR_SOFTBANK, "softbank-shift_jis-2007");
    }

    /**
     * This class is uninstantiable.
     */
    private CharsetUtils() {
        // This space intentionally left blank.
    }

    /**
     * Returns the name of the vendor-specific character set
     * corresponding to the given original character set name and
     * vendor. If there is no vendor-specific character set for the
     * given name/vendor pair, this returns the original character set name.
     *
     * @param charsetName the base character set name
     * @param vendor the vendor to specialize for. All characters should be lower-cased.
     * @return the specialized character set name, or {@code charsetName} if
     * there is no specialized name
     */
    public static String nameForVendor(String charsetName, String vendor) {
        if (!TextUtils.isEmpty(charsetName) && !TextUtils.isEmpty(vendor)) {
            // You can add your own mapping here.
            if (isShiftJis(charsetName)) {
                final String vendorShiftJis = sVendorShiftJisMap.get(vendor);
                if (vendorShiftJis != null) {
                    return vendorShiftJis;
                }
            }
        }

        return charsetName;
    }

    /**
     * Returns the name of the vendor-specific character set
     * corresponding to the given original character set name and the
     * default vendor (that is, the targeted vendor of the device this
     * code is running on). This method merely calls through to
     * {@link #nameForVendor(String,String)}, passing the default vendor
     * as the second argument.
     * 
     * @param charsetName the base character set name
     * @return the specialized character set name, or {@code charsetName} if
     * there is no specialized name
     */
    public static String nameForDefaultVendor(String charsetName) {
        return nameForVendor(charsetName, getDefaultVendor());
    }

    /**
     * Returns the vendor-specific character set corresponding to the
     * given original character set name and vendor. If there is no
     * vendor-specific character set for the given name/vendor pair,
     * this returns the character set corresponding to the original
     * name. The vendor name is matched case-insensitively. This
     * method merely calls {@code Charset.forName()} on a name
     * transformed by a call to {@link #nameForVendor(String,String)}.
     * 
     * @param charsetName the base character set name
     * @param vendor the vendor to specialize for
     * @return the specialized character set, or the one corresponding
     * directly to {@code charsetName} if there is no specialized
     * variant
     * @throws UnsupportedCharsetException thrown if the named character
     * set is not supported by the system
     * @throws IllegalCharsetNameException thrown if {@code charsetName}
     * has invalid syntax
     */
    public static Charset charsetForVendor(String charsetName, String vendor)
            throws UnsupportedCharsetException, IllegalCharsetNameException {
        charsetName = nameForVendor(charsetName, vendor);
        return Charset.forName(charsetName);
    }
    
    /**
     * Returns the vendor-specific character set corresponding to the
     * given original character set name and default vendor (that is,
     * the targeted vendor of the device this code is running on). 
     * This method merely calls through to {@link
     * #charsetForVendor(String,String)}, passing the default vendor
     * as the second argument.
     * 
     * @param charsetName the base character set name
     * @return the specialized character set, or the one corresponding
     * directly to {@code charsetName} if there is no specialized
     * variant
     * @throws UnsupportedCharsetException thrown if the named character
     * set is not supported by the system
     * @throws IllegalCharsetNameException thrown if {@code charsetName}
     * has invalid syntax
     */
    public static Charset charsetForVendor(String charsetName)
            throws UnsupportedCharsetException, IllegalCharsetNameException {
        return charsetForVendor(charsetName, getDefaultVendor());
    }

    /**
     * Returns whether the given character set name indicates the Shift-JIS
     * encoding. Returns false if the name is null.
     * 
     * @param charsetName the character set name
     * @return {@code true} if the name corresponds to Shift-JIS or
     * {@code false} if not
     */
    private static boolean isShiftJis(String charsetName) {
        // Bail quickly if the length doesn't match.
        if (charsetName == null) {
            return false;
        }
        int length = charsetName.length();
        if (length != 4 && length != 9) {
            return false;
        }

        return charsetName.equalsIgnoreCase("shift_jis")
            || charsetName.equalsIgnoreCase("shift-jis")
            || charsetName.equalsIgnoreCase("sjis");
    }

    /**
     * Gets the default vendor for this build.
     * 
     * @return the default vendor name
     */
    private static String getDefaultVendor() {
        return Build.BRAND;
    }
}
