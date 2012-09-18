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

/**
 * Some objects that implement TextDirectionHeuristic.
 *
 * @hide
 */
public class TextDirectionHeuristics {

    /** Always decides that the direction is left to right. */
    public static final TextDirectionHeuristic LTR =
        new TextDirectionHeuristicInternal(null /* no algorithm */, false);

    /** Always decides that the direction is right to left. */
    public static final TextDirectionHeuristic RTL =
        new TextDirectionHeuristicInternal(null /* no algorithm */, true);

    /**
     * Determines the direction based on the first strong directional character,
     * including bidi format chars, falling back to left to right if it
     * finds none.  This is the default behavior of the Unicode Bidirectional
     * Algorithm.
     */
    public static final TextDirectionHeuristic FIRSTSTRONG_LTR =
        new TextDirectionHeuristicInternal(FirstStrong.INSTANCE, false);

    /**
     * Determines the direction based on the first strong directional character,
     * including bidi format chars, falling back to right to left if it
     * finds none.  This is similar to the default behavior of the Unicode
     * Bidirectional Algorithm, just with different fallback behavior.
     */
    public static final TextDirectionHeuristic FIRSTSTRONG_RTL =
        new TextDirectionHeuristicInternal(FirstStrong.INSTANCE, true);

    /**
     * If the text contains any strong right to left non-format character, determines
     * that the direction is right to left, falling back to left to right if it
     * finds none.
     */
    public static final TextDirectionHeuristic ANYRTL_LTR =
        new TextDirectionHeuristicInternal(AnyStrong.INSTANCE_RTL, false);

    /**
     * Force the paragraph direction to the Locale direction. Falls back to left to right.
     */
    public static final TextDirectionHeuristic LOCALE = TextDirectionHeuristicLocale.INSTANCE;

    private static enum TriState {
        TRUE, FALSE, UNKNOWN;
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
        public boolean isRtl(char[] chars, int start, int count) {
            if (chars == null || start < 0 || count < 0 || chars.length - count < start) {
                throw new IllegalArgumentException();
            }
            if (mAlgorithm == null) {
                return defaultIsRtl();
            }
            return doCheck(chars, start, count);
        }

        private boolean doCheck(char[] chars, int start, int count) {
            switch(mAlgorithm.checkRtl(chars, start, count)) {
                case TRUE:
                    return true;
                case FALSE:
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

    private static TriState isRtlText(int directionality) {
        switch (directionality) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
                return TriState.FALSE;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                return TriState.TRUE;
            default:
                return TriState.UNKNOWN;
        }
    }

    private static TriState isRtlTextOrFormat(int directionality) {
        switch (directionality) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                return TriState.FALSE;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                return TriState.TRUE;
            default:
                return TriState.UNKNOWN;
        }
    }

    /**
     * Interface for an algorithm to guess the direction of a paragraph of text.
     *
     */
    private static interface TextDirectionAlgorithm {
        /**
         * Returns whether the range of text is RTL according to the algorithm.
         *
         */
        TriState checkRtl(char[] text, int start, int count);
    }

    /**
     * Algorithm that uses the first strong directional character to determine
     * the paragraph direction.  This is the standard Unicode Bidirectional
     * algorithm.
     *
     */
    private static class FirstStrong implements TextDirectionAlgorithm {
        @Override
        public TriState checkRtl(char[] text, int start, int count) {
            TriState result = TriState.UNKNOWN;
            for (int i = start, e = start + count; i < e && result == TriState.UNKNOWN; ++i) {
                result = isRtlTextOrFormat(Character.getDirectionality(text[i]));
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
     *
     */
    private static class AnyStrong implements TextDirectionAlgorithm {
        private final boolean mLookForRtl;

        @Override
        public TriState checkRtl(char[] text, int start, int count) {
            boolean haveUnlookedFor = false;
            for (int i = start, e = start + count; i < e; ++i) {
                switch (isRtlText(Character.getDirectionality(text[i]))) {
                    case TRUE:
                        if (mLookForRtl) {
                            return TriState.TRUE;
                        }
                        haveUnlookedFor = true;
                        break;
                    case FALSE:
                        if (!mLookForRtl) {
                            return TriState.FALSE;
                        }
                        haveUnlookedFor = true;
                        break;
                    default:
                        break;
                }
            }
            if (haveUnlookedFor) {
                return mLookForRtl ? TriState.FALSE : TriState.TRUE;
            }
            return TriState.UNKNOWN;
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
