/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content.res;

import java.util.Locale;

/*
 * Yuck-o.  This is not the right way to implement this.  When the ICU PluralRules
 * object has been integrated to android, we should switch to that.  For now, yuck-o.
 */

abstract class PluralRules {

    static final int QUANTITY_OTHER = 0x0000;
    static final int QUANTITY_ZERO  = 0x0001;
    static final int QUANTITY_ONE   = 0x0002;
    static final int QUANTITY_TWO   = 0x0004;
    static final int QUANTITY_FEW   = 0x0008;
    static final int QUANTITY_MANY  = 0x0010;

    static final int ID_OTHER = 0x01000004;

    abstract int quantityForNumber(int n);

    final int attrForNumber(int n) {
        return PluralRules.attrForQuantity(quantityForNumber(n));
    }

    static final int attrForQuantity(int quantity) {
        // see include/utils/ResourceTypes.h
        switch (quantity) {
            case QUANTITY_ZERO: return 0x01000005;
            case QUANTITY_ONE:  return 0x01000006;
            case QUANTITY_TWO:  return 0x01000007;
            case QUANTITY_FEW:  return 0x01000008;
            case QUANTITY_MANY: return 0x01000009;
            default:            return ID_OTHER;
        }
    }

    static final String stringForQuantity(int quantity) {
        switch (quantity) {
            case QUANTITY_ZERO:
                return "zero";
            case QUANTITY_ONE:
                return "one";
            case QUANTITY_TWO:
                return "two";
            case QUANTITY_FEW:
                return "few";
            case QUANTITY_MANY:
                return "many";
            default:
                return "other";
        }
    }

    static final PluralRules ruleForLocale(Locale locale) {
        String lang = locale.getLanguage();
        if ("cs".equals(lang)) {
            if (cs == null) cs = new cs();
            return cs;
        }
        else {
            if (en == null) en = new en();
            return en;
        }
    }

    private static PluralRules cs;
    private static class cs extends PluralRules {
        int quantityForNumber(int n) {
            if (n == 1) {
                return QUANTITY_ONE;
            }
            else if (n >= 2 && n <= 4) {
                return QUANTITY_FEW;
            }
            else {
                return QUANTITY_OTHER;
            }
        }
    }

    private static PluralRules en;
    private static class en extends PluralRules {
        int quantityForNumber(int n) {
            if (n == 1) {
                return QUANTITY_ONE;
            }
            else {
                return QUANTITY_OTHER;
            }
        }
    }
}

