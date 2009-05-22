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

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

/**
 * A class containing utility methods related to character sets. This
 * class is primarily useful for code that wishes to be vendor-aware
 * in its interpretation of Japanese encoding names.
 * 
 * <p>As of this writing, the only vendor that is recognized by this
 * class is Docomo (identified case-insensitively as {@code "docomo"}).</p>
 * 
 * <b>Note:</b> This class is hidden in Cupcake, with a plan to
 * un-hide in Donut. This was done because the first deployment to use
 * this code is based on Cupcake, but the API had to be introduced
 * after the public API freeze for that release. The upshot is that
 * only system applications can safely use this class until Donut is
 * available.
 * 
 * @hide
 */
public final class CharsetUtils {
    /**
     * name of the vendor "Docomo". <b>Note:</b> This isn't a public
     * constant, in order to keep this class from becoming a de facto
     * reference list of vendor names.
     */
    private static final String VENDOR_DOCOMO = "docomo";
    
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
     * given name/vendor pair, this returns the original character set
     * name. The vendor name is matched case-insensitively.
     * 
     * @param charsetName the base character set name
     * @param vendor the vendor to specialize for
     * @return the specialized character set name, or {@code charsetName} if
     * there is no specialized name
     */
    public static String nameForVendor(String charsetName, String vendor) {
        // TODO: Eventually, this may want to be table-driven.

        if (vendor.equalsIgnoreCase(VENDOR_DOCOMO)
                && isShiftJis(charsetName)) {
            return "docomo-shift_jis-2007";
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
