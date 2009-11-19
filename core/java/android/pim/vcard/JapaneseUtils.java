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

package android.pim.vcard;

import java.util.HashMap;
import java.util.Map;

/**
 * TextUtils especially for Japanese.
 */
/* package */ class JapaneseUtils {
    static private final Map<Character, String> sHalfWidthMap =
        new HashMap<Character, String>();

    static {
        // There's no logical mapping rule in Unicode. Sigh.
        sHalfWidthMap.put('\u3001', "\uFF64");
        sHalfWidthMap.put('\u3002', "\uFF61");
        sHalfWidthMap.put('\u300C', "\uFF62");
        sHalfWidthMap.put('\u300D', "\uFF63");
        sHalfWidthMap.put('\u301C', "~");
        sHalfWidthMap.put('\u3041', "\uFF67");
        sHalfWidthMap.put('\u3042', "\uFF71");
        sHalfWidthMap.put('\u3043', "\uFF68");
        sHalfWidthMap.put('\u3044', "\uFF72");
        sHalfWidthMap.put('\u3045', "\uFF69");
        sHalfWidthMap.put('\u3046', "\uFF73");
        sHalfWidthMap.put('\u3047', "\uFF6A");
        sHalfWidthMap.put('\u3048', "\uFF74");
        sHalfWidthMap.put('\u3049', "\uFF6B");
        sHalfWidthMap.put('\u304A', "\uFF75");
        sHalfWidthMap.put('\u304B', "\uFF76");
        sHalfWidthMap.put('\u304C', "\uFF76\uFF9E");
        sHalfWidthMap.put('\u304D', "\uFF77");
        sHalfWidthMap.put('\u304E', "\uFF77\uFF9E");
        sHalfWidthMap.put('\u304F', "\uFF78");
        sHalfWidthMap.put('\u3050', "\uFF78\uFF9E");
        sHalfWidthMap.put('\u3051', "\uFF79");
        sHalfWidthMap.put('\u3052', "\uFF79\uFF9E");
        sHalfWidthMap.put('\u3053', "\uFF7A");
        sHalfWidthMap.put('\u3054', "\uFF7A\uFF9E");
        sHalfWidthMap.put('\u3055', "\uFF7B");
        sHalfWidthMap.put('\u3056', "\uFF7B\uFF9E");
        sHalfWidthMap.put('\u3057', "\uFF7C");
        sHalfWidthMap.put('\u3058', "\uFF7C\uFF9E");
        sHalfWidthMap.put('\u3059', "\uFF7D");
        sHalfWidthMap.put('\u305A', "\uFF7D\uFF9E");
        sHalfWidthMap.put('\u305B', "\uFF7E");
        sHalfWidthMap.put('\u305C', "\uFF7E\uFF9E");
        sHalfWidthMap.put('\u305D', "\uFF7F");
        sHalfWidthMap.put('\u305E', "\uFF7F\uFF9E");
        sHalfWidthMap.put('\u305F', "\uFF80");
        sHalfWidthMap.put('\u3060', "\uFF80\uFF9E");
        sHalfWidthMap.put('\u3061', "\uFF81");
        sHalfWidthMap.put('\u3062', "\uFF81\uFF9E");
        sHalfWidthMap.put('\u3063', "\uFF6F");
        sHalfWidthMap.put('\u3064', "\uFF82");
        sHalfWidthMap.put('\u3065', "\uFF82\uFF9E");
        sHalfWidthMap.put('\u3066', "\uFF83");
        sHalfWidthMap.put('\u3067', "\uFF83\uFF9E");
        sHalfWidthMap.put('\u3068', "\uFF84");
        sHalfWidthMap.put('\u3069', "\uFF84\uFF9E");
        sHalfWidthMap.put('\u306A', "\uFF85");
        sHalfWidthMap.put('\u306B', "\uFF86");
        sHalfWidthMap.put('\u306C', "\uFF87");
        sHalfWidthMap.put('\u306D', "\uFF88");
        sHalfWidthMap.put('\u306E', "\uFF89");
        sHalfWidthMap.put('\u306F', "\uFF8A");
        sHalfWidthMap.put('\u3070', "\uFF8A\uFF9E");
        sHalfWidthMap.put('\u3071', "\uFF8A\uFF9F");
        sHalfWidthMap.put('\u3072', "\uFF8B");
        sHalfWidthMap.put('\u3073', "\uFF8B\uFF9E");
        sHalfWidthMap.put('\u3074', "\uFF8B\uFF9F");
        sHalfWidthMap.put('\u3075', "\uFF8C");
        sHalfWidthMap.put('\u3076', "\uFF8C\uFF9E");
        sHalfWidthMap.put('\u3077', "\uFF8C\uFF9F");
        sHalfWidthMap.put('\u3078', "\uFF8D");
        sHalfWidthMap.put('\u3079', "\uFF8D\uFF9E");
        sHalfWidthMap.put('\u307A', "\uFF8D\uFF9F");
        sHalfWidthMap.put('\u307B', "\uFF8E");
        sHalfWidthMap.put('\u307C', "\uFF8E\uFF9E");
        sHalfWidthMap.put('\u307D', "\uFF8E\uFF9F");
        sHalfWidthMap.put('\u307E', "\uFF8F");
        sHalfWidthMap.put('\u307F', "\uFF90");
        sHalfWidthMap.put('\u3080', "\uFF91");
        sHalfWidthMap.put('\u3081', "\uFF92");
        sHalfWidthMap.put('\u3082', "\uFF93");
        sHalfWidthMap.put('\u3083', "\uFF6C");
        sHalfWidthMap.put('\u3084', "\uFF94");
        sHalfWidthMap.put('\u3085', "\uFF6D");
        sHalfWidthMap.put('\u3086', "\uFF95");
        sHalfWidthMap.put('\u3087', "\uFF6E");
        sHalfWidthMap.put('\u3088', "\uFF96");
        sHalfWidthMap.put('\u3089', "\uFF97");
        sHalfWidthMap.put('\u308A', "\uFF98");
        sHalfWidthMap.put('\u308B', "\uFF99");
        sHalfWidthMap.put('\u308C', "\uFF9A");
        sHalfWidthMap.put('\u308D', "\uFF9B");
        sHalfWidthMap.put('\u308E', "\uFF9C");
        sHalfWidthMap.put('\u308F', "\uFF9C");
        sHalfWidthMap.put('\u3090', "\uFF72");
        sHalfWidthMap.put('\u3091', "\uFF74");
        sHalfWidthMap.put('\u3092', "\uFF66");
        sHalfWidthMap.put('\u3093', "\uFF9D");
        sHalfWidthMap.put('\u309B', "\uFF9E");
        sHalfWidthMap.put('\u309C', "\uFF9F");
        sHalfWidthMap.put('\u30A1', "\uFF67");
        sHalfWidthMap.put('\u30A2', "\uFF71");
        sHalfWidthMap.put('\u30A3', "\uFF68");
        sHalfWidthMap.put('\u30A4', "\uFF72");
        sHalfWidthMap.put('\u30A5', "\uFF69");
        sHalfWidthMap.put('\u30A6', "\uFF73");
        sHalfWidthMap.put('\u30A7', "\uFF6A");
        sHalfWidthMap.put('\u30A8', "\uFF74");
        sHalfWidthMap.put('\u30A9', "\uFF6B");
        sHalfWidthMap.put('\u30AA', "\uFF75");
        sHalfWidthMap.put('\u30AB', "\uFF76");
        sHalfWidthMap.put('\u30AC', "\uFF76\uFF9E");
        sHalfWidthMap.put('\u30AD', "\uFF77");
        sHalfWidthMap.put('\u30AE', "\uFF77\uFF9E");
        sHalfWidthMap.put('\u30AF', "\uFF78");
        sHalfWidthMap.put('\u30B0', "\uFF78\uFF9E");
        sHalfWidthMap.put('\u30B1', "\uFF79");
        sHalfWidthMap.put('\u30B2', "\uFF79\uFF9E");
        sHalfWidthMap.put('\u30B3', "\uFF7A");
        sHalfWidthMap.put('\u30B4', "\uFF7A\uFF9E");
        sHalfWidthMap.put('\u30B5', "\uFF7B");
        sHalfWidthMap.put('\u30B6', "\uFF7B\uFF9E");
        sHalfWidthMap.put('\u30B7', "\uFF7C");
        sHalfWidthMap.put('\u30B8', "\uFF7C\uFF9E");
        sHalfWidthMap.put('\u30B9', "\uFF7D");
        sHalfWidthMap.put('\u30BA', "\uFF7D\uFF9E");
        sHalfWidthMap.put('\u30BB', "\uFF7E");
        sHalfWidthMap.put('\u30BC', "\uFF7E\uFF9E");
        sHalfWidthMap.put('\u30BD', "\uFF7F");
        sHalfWidthMap.put('\u30BE', "\uFF7F\uFF9E");
        sHalfWidthMap.put('\u30BF', "\uFF80");
        sHalfWidthMap.put('\u30C0', "\uFF80\uFF9E");
        sHalfWidthMap.put('\u30C1', "\uFF81");
        sHalfWidthMap.put('\u30C2', "\uFF81\uFF9E");
        sHalfWidthMap.put('\u30C3', "\uFF6F");
        sHalfWidthMap.put('\u30C4', "\uFF82");
        sHalfWidthMap.put('\u30C5', "\uFF82\uFF9E");
        sHalfWidthMap.put('\u30C6', "\uFF83");
        sHalfWidthMap.put('\u30C7', "\uFF83\uFF9E");
        sHalfWidthMap.put('\u30C8', "\uFF84");
        sHalfWidthMap.put('\u30C9', "\uFF84\uFF9E");
        sHalfWidthMap.put('\u30CA', "\uFF85");
        sHalfWidthMap.put('\u30CB', "\uFF86");
        sHalfWidthMap.put('\u30CC', "\uFF87");
        sHalfWidthMap.put('\u30CD', "\uFF88");
        sHalfWidthMap.put('\u30CE', "\uFF89");
        sHalfWidthMap.put('\u30CF', "\uFF8A");
        sHalfWidthMap.put('\u30D0', "\uFF8A\uFF9E");
        sHalfWidthMap.put('\u30D1', "\uFF8A\uFF9F");
        sHalfWidthMap.put('\u30D2', "\uFF8B");
        sHalfWidthMap.put('\u30D3', "\uFF8B\uFF9E");
        sHalfWidthMap.put('\u30D4', "\uFF8B\uFF9F");
        sHalfWidthMap.put('\u30D5', "\uFF8C");
        sHalfWidthMap.put('\u30D6', "\uFF8C\uFF9E");
        sHalfWidthMap.put('\u30D7', "\uFF8C\uFF9F");
        sHalfWidthMap.put('\u30D8', "\uFF8D");
        sHalfWidthMap.put('\u30D9', "\uFF8D\uFF9E");
        sHalfWidthMap.put('\u30DA', "\uFF8D\uFF9F");
        sHalfWidthMap.put('\u30DB', "\uFF8E");
        sHalfWidthMap.put('\u30DC', "\uFF8E\uFF9E");
        sHalfWidthMap.put('\u30DD', "\uFF8E\uFF9F");
        sHalfWidthMap.put('\u30DE', "\uFF8F");
        sHalfWidthMap.put('\u30DF', "\uFF90");
        sHalfWidthMap.put('\u30E0', "\uFF91");
        sHalfWidthMap.put('\u30E1', "\uFF92");
        sHalfWidthMap.put('\u30E2', "\uFF93");
        sHalfWidthMap.put('\u30E3', "\uFF6C");
        sHalfWidthMap.put('\u30E4', "\uFF94");
        sHalfWidthMap.put('\u30E5', "\uFF6D");
        sHalfWidthMap.put('\u30E6', "\uFF95");
        sHalfWidthMap.put('\u30E7', "\uFF6E");
        sHalfWidthMap.put('\u30E8', "\uFF96");
        sHalfWidthMap.put('\u30E9', "\uFF97");
        sHalfWidthMap.put('\u30EA', "\uFF98");
        sHalfWidthMap.put('\u30EB', "\uFF99");
        sHalfWidthMap.put('\u30EC', "\uFF9A");
        sHalfWidthMap.put('\u30ED', "\uFF9B");
        sHalfWidthMap.put('\u30EE', "\uFF9C");
        sHalfWidthMap.put('\u30EF', "\uFF9C");
        sHalfWidthMap.put('\u30F0', "\uFF72");
        sHalfWidthMap.put('\u30F1', "\uFF74");
        sHalfWidthMap.put('\u30F2', "\uFF66");
        sHalfWidthMap.put('\u30F3', "\uFF9D");
        sHalfWidthMap.put('\u30F4', "\uFF73\uFF9E");
        sHalfWidthMap.put('\u30F5', "\uFF76");
        sHalfWidthMap.put('\u30F6', "\uFF79");
        sHalfWidthMap.put('\u30FB', "\uFF65");
        sHalfWidthMap.put('\u30FC', "\uFF70");
        sHalfWidthMap.put('\uFF01', "!");
        sHalfWidthMap.put('\uFF02', "\"");
        sHalfWidthMap.put('\uFF03', "#");
        sHalfWidthMap.put('\uFF04', "$");
        sHalfWidthMap.put('\uFF05', "%");
        sHalfWidthMap.put('\uFF06', "&");
        sHalfWidthMap.put('\uFF07', "'");
        sHalfWidthMap.put('\uFF08', "(");
        sHalfWidthMap.put('\uFF09', ")");
        sHalfWidthMap.put('\uFF0A', "*");
        sHalfWidthMap.put('\uFF0B', "+");
        sHalfWidthMap.put('\uFF0C', ",");
        sHalfWidthMap.put('\uFF0D', "-");
        sHalfWidthMap.put('\uFF0E', ".");
        sHalfWidthMap.put('\uFF0F', "/");
        sHalfWidthMap.put('\uFF10', "0");
        sHalfWidthMap.put('\uFF11', "1");
        sHalfWidthMap.put('\uFF12', "2");
        sHalfWidthMap.put('\uFF13', "3");
        sHalfWidthMap.put('\uFF14', "4");
        sHalfWidthMap.put('\uFF15', "5");
        sHalfWidthMap.put('\uFF16', "6");
        sHalfWidthMap.put('\uFF17', "7");
        sHalfWidthMap.put('\uFF18', "8");
        sHalfWidthMap.put('\uFF19', "9");
        sHalfWidthMap.put('\uFF1A', ":");
        sHalfWidthMap.put('\uFF1B', ";");
        sHalfWidthMap.put('\uFF1C', "<");
        sHalfWidthMap.put('\uFF1D', "=");
        sHalfWidthMap.put('\uFF1E', ">");
        sHalfWidthMap.put('\uFF1F', "?");
        sHalfWidthMap.put('\uFF20', "@");
        sHalfWidthMap.put('\uFF21', "A");
        sHalfWidthMap.put('\uFF22', "B");
        sHalfWidthMap.put('\uFF23', "C");
        sHalfWidthMap.put('\uFF24', "D");
        sHalfWidthMap.put('\uFF25', "E");
        sHalfWidthMap.put('\uFF26', "F");
        sHalfWidthMap.put('\uFF27', "G");
        sHalfWidthMap.put('\uFF28', "H");
        sHalfWidthMap.put('\uFF29', "I");
        sHalfWidthMap.put('\uFF2A', "J");
        sHalfWidthMap.put('\uFF2B', "K");
        sHalfWidthMap.put('\uFF2C', "L");
        sHalfWidthMap.put('\uFF2D', "M");
        sHalfWidthMap.put('\uFF2E', "N");
        sHalfWidthMap.put('\uFF2F', "O");
        sHalfWidthMap.put('\uFF30', "P");
        sHalfWidthMap.put('\uFF31', "Q");
        sHalfWidthMap.put('\uFF32', "R");
        sHalfWidthMap.put('\uFF33', "S");
        sHalfWidthMap.put('\uFF34', "T");
        sHalfWidthMap.put('\uFF35', "U");
        sHalfWidthMap.put('\uFF36', "V");
        sHalfWidthMap.put('\uFF37', "W");
        sHalfWidthMap.put('\uFF38', "X");
        sHalfWidthMap.put('\uFF39', "Y");
        sHalfWidthMap.put('\uFF3A', "Z");
        sHalfWidthMap.put('\uFF3B', "[");
        sHalfWidthMap.put('\uFF3C', "\\");
        sHalfWidthMap.put('\uFF3D', "]");
        sHalfWidthMap.put('\uFF3E', "^");
        sHalfWidthMap.put('\uFF3F', "_");
        sHalfWidthMap.put('\uFF41', "a");
        sHalfWidthMap.put('\uFF42', "b");
        sHalfWidthMap.put('\uFF43', "c");
        sHalfWidthMap.put('\uFF44', "d");
        sHalfWidthMap.put('\uFF45', "e");
        sHalfWidthMap.put('\uFF46', "f");
        sHalfWidthMap.put('\uFF47', "g");
        sHalfWidthMap.put('\uFF48', "h");
        sHalfWidthMap.put('\uFF49', "i");
        sHalfWidthMap.put('\uFF4A', "j");
        sHalfWidthMap.put('\uFF4B', "k");
        sHalfWidthMap.put('\uFF4C', "l");
        sHalfWidthMap.put('\uFF4D', "m");
        sHalfWidthMap.put('\uFF4E', "n");
        sHalfWidthMap.put('\uFF4F', "o");
        sHalfWidthMap.put('\uFF50', "p");
        sHalfWidthMap.put('\uFF51', "q");
        sHalfWidthMap.put('\uFF52', "r");
        sHalfWidthMap.put('\uFF53', "s");
        sHalfWidthMap.put('\uFF54', "t");
        sHalfWidthMap.put('\uFF55', "u");
        sHalfWidthMap.put('\uFF56', "v");
        sHalfWidthMap.put('\uFF57', "w");
        sHalfWidthMap.put('\uFF58', "x");
        sHalfWidthMap.put('\uFF59', "y");
        sHalfWidthMap.put('\uFF5A', "z");
        sHalfWidthMap.put('\uFF5B', "{");
        sHalfWidthMap.put('\uFF5C', "|");
        sHalfWidthMap.put('\uFF5D', "}");
        sHalfWidthMap.put('\uFF5E', "~");
        sHalfWidthMap.put('\uFF61', "\uFF61");
        sHalfWidthMap.put('\uFF62', "\uFF62");
        sHalfWidthMap.put('\uFF63', "\uFF63");
        sHalfWidthMap.put('\uFF64', "\uFF64");
        sHalfWidthMap.put('\uFF65', "\uFF65");
        sHalfWidthMap.put('\uFF66', "\uFF66");
        sHalfWidthMap.put('\uFF67', "\uFF67");
        sHalfWidthMap.put('\uFF68', "\uFF68");
        sHalfWidthMap.put('\uFF69', "\uFF69");
        sHalfWidthMap.put('\uFF6A', "\uFF6A");
        sHalfWidthMap.put('\uFF6B', "\uFF6B");
        sHalfWidthMap.put('\uFF6C', "\uFF6C");
        sHalfWidthMap.put('\uFF6D', "\uFF6D");
        sHalfWidthMap.put('\uFF6E', "\uFF6E");
        sHalfWidthMap.put('\uFF6F', "\uFF6F");
        sHalfWidthMap.put('\uFF70', "\uFF70");
        sHalfWidthMap.put('\uFF71', "\uFF71");
        sHalfWidthMap.put('\uFF72', "\uFF72");
        sHalfWidthMap.put('\uFF73', "\uFF73");
        sHalfWidthMap.put('\uFF74', "\uFF74");
        sHalfWidthMap.put('\uFF75', "\uFF75");
        sHalfWidthMap.put('\uFF76', "\uFF76");
        sHalfWidthMap.put('\uFF77', "\uFF77");
        sHalfWidthMap.put('\uFF78', "\uFF78");
        sHalfWidthMap.put('\uFF79', "\uFF79");
        sHalfWidthMap.put('\uFF7A', "\uFF7A");
        sHalfWidthMap.put('\uFF7B', "\uFF7B");
        sHalfWidthMap.put('\uFF7C', "\uFF7C");
        sHalfWidthMap.put('\uFF7D', "\uFF7D");
        sHalfWidthMap.put('\uFF7E', "\uFF7E");
        sHalfWidthMap.put('\uFF7F', "\uFF7F");
        sHalfWidthMap.put('\uFF80', "\uFF80");
        sHalfWidthMap.put('\uFF81', "\uFF81");
        sHalfWidthMap.put('\uFF82', "\uFF82");
        sHalfWidthMap.put('\uFF83', "\uFF83");
        sHalfWidthMap.put('\uFF84', "\uFF84");
        sHalfWidthMap.put('\uFF85', "\uFF85");
        sHalfWidthMap.put('\uFF86', "\uFF86");
        sHalfWidthMap.put('\uFF87', "\uFF87");
        sHalfWidthMap.put('\uFF88', "\uFF88");
        sHalfWidthMap.put('\uFF89', "\uFF89");
        sHalfWidthMap.put('\uFF8A', "\uFF8A");
        sHalfWidthMap.put('\uFF8B', "\uFF8B");
        sHalfWidthMap.put('\uFF8C', "\uFF8C");
        sHalfWidthMap.put('\uFF8D', "\uFF8D");
        sHalfWidthMap.put('\uFF8E', "\uFF8E");
        sHalfWidthMap.put('\uFF8F', "\uFF8F");
        sHalfWidthMap.put('\uFF90', "\uFF90");
        sHalfWidthMap.put('\uFF91', "\uFF91");
        sHalfWidthMap.put('\uFF92', "\uFF92");
        sHalfWidthMap.put('\uFF93', "\uFF93");
        sHalfWidthMap.put('\uFF94', "\uFF94");
        sHalfWidthMap.put('\uFF95', "\uFF95");
        sHalfWidthMap.put('\uFF96', "\uFF96");
        sHalfWidthMap.put('\uFF97', "\uFF97");
        sHalfWidthMap.put('\uFF98', "\uFF98");
        sHalfWidthMap.put('\uFF99', "\uFF99");
        sHalfWidthMap.put('\uFF9A', "\uFF9A");
        sHalfWidthMap.put('\uFF9B', "\uFF9B");
        sHalfWidthMap.put('\uFF9C', "\uFF9C");
        sHalfWidthMap.put('\uFF9D', "\uFF9D");
        sHalfWidthMap.put('\uFF9E', "\uFF9E");
        sHalfWidthMap.put('\uFF9F', "\uFF9F");
        sHalfWidthMap.put('\uFFE5', "\u005C\u005C");
    }

    /**
     * Return half-width version of that character if possible. Return null if not possible
     * @param ch input character
     * @return CharSequence object if the mapping for ch exists. Return null otherwise.
     */
    public static String tryGetHalfWidthText(char ch) {
        if (sHalfWidthMap.containsKey(ch)) {
            return sHalfWidthMap.get(ch);
        } else {
            return null;
        }
    }
}
