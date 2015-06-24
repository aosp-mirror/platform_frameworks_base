/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics;

public class PorterDuff {

    // these value must match their native equivalents. See SkXfermode.h
    public enum Mode {
        /** [0, 0] */
        CLEAR       (0),
        /** [Sa, Sc] */
        SRC         (1),
        /** [Da, Dc] */
        DST         (2),
        /** [Sa + (1 - Sa)*Da, Rc = Sc + (1 - Sa)*Dc] */
        SRC_OVER    (3),
        /** [Sa + (1 - Sa)*Da, Rc = Dc + (1 - Da)*Sc] */
        DST_OVER    (4),
        /** [Sa * Da, Sc * Da] */
        SRC_IN      (5),
        /** [Sa * Da, Sa * Dc] */
        DST_IN      (6),
        /** [Sa * (1 - Da), Sc * (1 - Da)] */
        SRC_OUT     (7),
        /** [Da * (1 - Sa), Dc * (1 - Sa)] */
        DST_OUT     (8),
        /** [Da, Sc * Da + (1 - Sa) * Dc] */
        SRC_ATOP    (9),
        /** [Sa, Sa * Dc + Sc * (1 - Da)] */
        DST_ATOP    (10),
        /** [Sa + Da - 2 * Sa * Da, Sc * (1 - Da) + (1 - Sa) * Dc] */
        XOR         (11),
        /** [Sa + Da - Sa*Da,
             Sc*(1 - Da) + Dc*(1 - Sa) + min(Sc, Dc)] */
        DARKEN      (16),
        /** [Sa + Da - Sa*Da,
             Sc*(1 - Da) + Dc*(1 - Sa) + max(Sc, Dc)] */
        LIGHTEN     (17),
        /** [Sa * Da, Sc * Dc] */
        MULTIPLY    (13),
        /** [Sa + Da - Sa * Da, Sc + Dc - Sc * Dc] */
        SCREEN      (14),
        /** Saturate(S + D) */
        ADD         (12),
        OVERLAY     (15);

        Mode(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        /**
         * @hide
         */
        public final int nativeInt;
    }

    /**
     * @hide
     */
    public static final int modeToInt(Mode mode) {
        return mode.nativeInt;
    }

    /**
     * @hide
     */
    public static final Mode intToMode(int val) {
        switch (val) {
            default:
            case  0: return Mode.CLEAR;
            case  1: return Mode.SRC;
            case  2: return Mode.DST;
            case  3: return Mode.SRC_OVER;
            case  4: return Mode.DST_OVER;
            case  5: return Mode.SRC_IN;
            case  6: return Mode.DST_IN;
            case  7: return Mode.SRC_OUT;
            case  8: return Mode.DST_OUT;
            case  9: return Mode.SRC_ATOP;
            case 10: return Mode.DST_ATOP;
            case 11: return Mode.XOR;
            case 16: return Mode.DARKEN;
            case 17: return Mode.LIGHTEN;
            case 13: return Mode.MULTIPLY;
            case 14: return Mode.SCREEN;
            case 12: return Mode.ADD;
            case 15: return Mode.OVERLAY;
        }
    }
}
