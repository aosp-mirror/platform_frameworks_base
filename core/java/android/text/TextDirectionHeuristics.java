// Copyright 2011 Google Inc. All Rights Reserved.

package android.text;


/**
 * Some objects that implement TextDirectionHeuristic.
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
     * If the text contains any strong left to right non-format character, determines
     * that the direction is left to right, falling back to right to left if it
     * finds none.
     */
    public static final TextDirectionHeuristic ANYLTR_RTL =
        new TextDirectionHeuristicInternal(AnyStrong.INSTANCE_LTR, true);

    /**
     * Examines only the strong directional non-format characters, and if either
     * left to right or right to left characters are 60% or more of this total,
     * determines that the direction follows the majority of characters.  Falls
     * back to left to right if neither direction meets this threshold.
     */
    public static final TextDirectionHeuristic CHARCOUNT_LTR =
        new TextDirectionHeuristicInternal(CharCount.INSTANCE_DEFAULT, false);

    /**
     * Examines only the strong directional non-format characters, and if either
     * left to right or right to left characters are 60% or more of this total,
     * determines that the direction follows the majority of characters.  Falls
     * back to right to left if neither direction meets this threshold.
     */
    public static final TextDirectionHeuristic CHARCOUNT_RTL =
        new TextDirectionHeuristicInternal(CharCount.INSTANCE_DEFAULT, true);

    private static enum TriState {
        TRUE, FALSE, UNKNOWN;
    }

    /**
     * Computes the text direction based on an algorithm.  Subclasses implement
     * {@link #defaultIsRtl} to handle cases where the algorithm cannot determine the
     * direction from the text alone.
     * @hide
     */
    public static abstract class TextDirectionHeuristicImpl implements TextDirectionHeuristic {
        private final TextDirectionAlgorithm mAlgorithm;

        public TextDirectionHeuristicImpl(TextDirectionAlgorithm algorithm) {
            mAlgorithm = algorithm;
        }

        /**
         * Return true if the default text direction is rtl.
         */
        abstract protected boolean defaultIsRtl();

        @Override
        public boolean isRtl(CharSequence text, int start, int end) {
            if (text == null || start < 0 || end < start || text.length() < end) {
                throw new IllegalArgumentException();
            }
            if (mAlgorithm == null) {
                return defaultIsRtl();
            }
            text = text.subSequence(start, end);
            char[] chars = text.toString().toCharArray();
            return doCheck(chars, 0, chars.length);
        }

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
            case Character.DIRECTIONALITY_ARABIC_NUMBER:
                return TriState.TRUE;
            default:
                return TriState.UNKNOWN;
        }
    }

    /**
     * Interface for an algorithm to guess the direction of a paragraph of text.
     *
     * @hide
     */
    public static interface TextDirectionAlgorithm {
        /**
         * Returns whether the range of text is RTL according to the algorithm.
         *
         * @hide
         */
        TriState checkRtl(char[] text, int start, int count);
    }

    /**
     * Algorithm that uses the first strong directional character to determine
     * the paragraph direction.  This is the standard Unicode Bidirectional
     * algorithm.
     *
     * @hide
     */
    public static class FirstStrong implements TextDirectionAlgorithm {
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
     * @hide
     */
    public static class AnyStrong implements TextDirectionAlgorithm {
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
     * Algorithm that uses the relative proportion of strong directional
     * characters (excluding LRE, LRO, RLE, RLO) to determine the direction
     * of the paragraph, if the proportion exceeds a given threshold.
     *
     * @hide
     */
    public static class CharCount implements TextDirectionAlgorithm {
        private final float mThreshold;

        @Override
        public TriState checkRtl(char[] text, int start, int count) {
            int countLtr = 0;
            int countRtl = 0;
            for(int i = start, e = start + count; i < e; ++i) {
                switch (isRtlText(Character.getDirectionality(text[i]))) {
                    case TRUE:
                        ++countLtr;
                        break;
                    case FALSE:
                        ++countRtl;
                        break;
                    default:
                        break;
                }
            }
            int limit = (int)((countLtr + countRtl) * mThreshold);
            if (limit > 0) {
                if (countLtr > limit) {
                    return TriState.FALSE;
                }
                if (countRtl > limit) {
                    return TriState.TRUE;
                }
            }
            return TriState.UNKNOWN;
        }

        private CharCount(float threshold) {
            mThreshold = threshold;
        }

        public static CharCount withThreshold(float threshold) {
            if (threshold < 0 || threshold > 1) {
                throw new IllegalArgumentException();
            }
            if (threshold == DEFAULT_THRESHOLD) {
                return INSTANCE_DEFAULT;
            }
            return new CharCount(threshold);
        }

        public static final float DEFAULT_THRESHOLD = 0.6f;
        public static final CharCount INSTANCE_DEFAULT = new CharCount(DEFAULT_THRESHOLD);
    }
}
