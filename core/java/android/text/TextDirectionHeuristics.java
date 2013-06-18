/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.text;


import android.view.View;

import java.nio.CharBuffer;

/**
 * Some objects that implement {@link TextDirectionHeuristic}. Use these with
 * the {@link BidiFormatter#unicodeWrap unicodeWrap()} methods in {@link BidiFormatter}.
 * Also notice that these direction heuristics correspond to the same types of constants
 * provided in the {@link android.view.View} class for {@link android.view.View#setTextDirection
 * setTextDirection()}, such as {@link android.view.View#TEXT_DIRECTION_RTL}.
 * <p>To support versions lower than {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
 * you can use the support library's {@link android.support.v4.text.TextDirectionHeuristicsCompat}
 * class.
 *
 */
public class TextDirectionHeuristics {

    /**
     * Always decides that the direction is left to right.
     */
    public static final TextDirectionHeuristic LTR =
        new TextDirectionHeuristicInternal(null /* no algorithm */, false);

    /**
     * Always decides that the direction is right to left.
     */
    public static final TextDirectionHeuristic RTL =
        new TextDirectionHeuristicInternal(null /* no algorithm */, true);

    /**
     * Determines the direction based on the first strong directional character, including bidi
     * format chars, falling back to left to right if it finds none. This is the default behavior
     * of the Unicode Bidirectional Algorithm.
     */
    public static final TextDirectionHeuristic FIRSTSTRONG_LTR =
        new TextDirectionHeuristicInternal(FirstStrong.INSTANCE, false);

    /**
     * Determines the direction based on the first strong directional character, including bidi
     * format chars, falling back to right to left if it finds none. This is similar to the default
     * behavior of the Unicode Bidirectional Algorithm, just with different fallback behavior.
     */
    public static final TextDirectionHeuristic FIRSTSTRONG_RTL =
        new TextDirectionHeuristicInternal(FirstStrong.INSTANCE, true);

    /**
     * If the text contains any strong right to left non-format character, determines that the
     * direction is right to left, falling back to left to right if it finds none.
     */
    public static final TextDirectionHeuristic ANYRTL_LTR =
        new TextDirectionHeuristicInternal(AnyStrong.INSTANCE_RTL, false);

    /**
     * Force the paragraph direction to the Locale direction. Falls back to left to right.
     */
    public static final TextDirectionHeuristic LOCALE = TextDirectionHeuristicLocale.INSTANCE;

    /**
     * State constants for taking care about true / false / unknown
     */
    private static final int STATE_TRUE = 0;
    private static final int STATE_FALSE = 1;
    private static final int STATE_UNKNOWN = 2;

    private static int isRtlText(int directionality) {
        switch (directionality) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
                return STATE_FALSE;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                return STATE_TRUE;
            default:
                return STATE_UNKNOWN;
        }
    }

    private static int isRtlTextOrFormat(int directionality) {
        switch (directionality) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                return STATE_FALSE;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                return STATE_TRUE;
            default:
                return STATE_UNKNOWN;
        }
    }

    /**
     * Computes the text direction based on an algorithm.  Subclasses implement
     * {@link #defaultIsRtl} to handle cases where the algorithm cannot determine the
     * direction from the text alone.
     */
    private static abstract class TextDirectionHeuristicImpl implements TextDirectionHeuristic {
        private final TextDirectionAlgorithm mAlgorithm;

        public TextDirectionHeuristicImpl(TextDirectionAlgorithm algorithm) {
            mAlgorithm = algorithm;
        }

        /**
         * Return true if the default text direction is rtl.
         */
        abstract protected boolean defaultIsRtl();

        @Override
        public boolean isRtl(char[] array, int start, int count) {
            return isRtl(CharBuffer.wrap(array), start, count);
        }

        @Override
        public boolean isRtl(CharSequence cs, int start, int count) {
            if (cs == null || start < 0 || count < 0 || cs.length() - count < start) {
                throw new IllegalArgumentException();
            }
            if (mAlgorithm == null) {
                return defaultIsRtl();
            }
            return doCheck(cs, start, count);
        }

        private boolean doCheck(CharSequence cs, int start, int count) {
            switch(mAlgorithm.checkRtl(cs, start, count)) {
                case STATE_TRUE:
                    return true;
                case STATE_FALSE:
                    return false;
                default:
                    return defaultIsRtl();
            }
        }
    }

    private static class TextDirectionHeuristicInternal extends TextDirectionHeuristicImpl {
        private final boolean mDefaultIsRtl;

        private TextDirectionHeuristicInternal(TextDirectionAlgorithm algorithm,
                boolean defaultIsRtl) {
            super(algorithm);
            mDefaultIsRtl = defaultIsRtl;
        }

        @Override
        protected boolean defaultIsRtl() {
            return mDefaultIsRtl;
        }
    }

    /**
     * Interface for an algorithm to guess the direction of a paragraph of text.
     */
    private static interface TextDirectionAlgorithm {
        /**
         * Returns whether the range of text is RTL according to the algorithm.
         */
        int checkRtl(CharSequence cs, int start, int count);
    }

    /**
     * Algorithm that uses the first strong directional character to determine the paragraph
     * direction. This is the standard Unicode Bidirectional algorithm.
     */
    private static class FirstStrong implements TextDirectionAlgorithm {
        @Override
        public int checkRtl(CharSequence cs, int start, int count) {
            int result = STATE_UNKNOWN;
            for (int i = start, e = start + count; i < e && result == STATE_UNKNOWN; ++i) {
                result = isRtlTextOrFormat(Character.getDirectionality(cs.charAt(i)));
            }
            return result;
        }

        private FirstStrong() {
        }

        public static final FirstStrong INSTANCE = new FirstStrong();
    }

    /**
     * Algorithm that uses the presence of any strong directional non-format
     * character (e.g. excludes LRE, LRO, RLE, RLO) to determine the
     * direction of text.
     */
    private static class AnyStrong implements TextDirectionAlgorithm {
        private final boolean mLookForRtl;

        @Override
        public int checkRtl(CharSequence cs, int start, int count) {
            boolean haveUnlookedFor = false;
            for (int i = start, e = start + count; i < e; ++i) {
                switch (isRtlText(Character.getDirectionality(cs.charAt(i)))) {
                    case STATE_TRUE:
                        if (mLookForRtl) {
                            return STATE_TRUE;
                        }
                        haveUnlookedFor = true;
                        break;
                    case STATE_FALSE:
                        if (!mLookForRtl) {
                            return STATE_FALSE;
                        }
                        haveUnlookedFor = true;
                        break;
                    default:
                        break;
                }
            }
            if (haveUnlookedFor) {
                return mLookForRtl ? STATE_FALSE : STATE_TRUE;
            }
            return STATE_UNKNOWN;
        }

        private AnyStrong(boolean lookForRtl) {
            this.mLookForRtl = lookForRtl;
        }

        public static final AnyStrong INSTANCE_RTL = new AnyStrong(true);
        public static final AnyStrong INSTANCE_LTR = new AnyStrong(false);
    }

    /**
     * Algorithm that uses the Locale direction to force the direction of a paragraph.
     */
    private static class TextDirectionHeuristicLocale extends TextDirectionHeuristicImpl {

        public TextDirectionHeuristicLocale() {
            super(null);
        }

        @Override
        protected boolean defaultIsRtl() {
            final int dir = TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault());
            return (dir == View.LAYOUT_DIRECTION_RTL);
        }

        public static final TextDirectionHeuristicLocale INSTANCE =
                new TextDirectionHeuristicLocale();
    }
}
