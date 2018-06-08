package android.text;

import static android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE;

import android.text.style.BulletSpan;

import java.util.Random;

/**
 *
 */
public class NonEditableTextGenerator {

    enum TextType {
        STRING,
        SPANNED,
        SPANNABLE_BUILDER
    }

    private boolean mCreateBoring;
    private TextType mTextType;
    private int mSequenceLength;
    private final Random mRandom;

    public NonEditableTextGenerator(Random random) {
        mRandom = random;
    }

    public NonEditableTextGenerator setCreateBoring(boolean createBoring) {
        mCreateBoring = createBoring;
        return this;
    }

    public NonEditableTextGenerator setTextType(TextType textType) {
        mTextType = textType;
        return this;
    }

    public NonEditableTextGenerator setSequenceLength(int sequenceLength) {
        mSequenceLength = sequenceLength;
        return this;
    }

    /**
     * Sample charSequence generated:
     * NRjPzjvUadHmH ExoEoTqfx pCLw qtndsqfpk AqajVCbgjGZ igIeC dfnXRgA
     */
    public CharSequence build() {
        final RandomCharSequenceGenerator sequenceGenerator = new RandomCharSequenceGenerator(
                mRandom);
        if (mSequenceLength > 0) {
            sequenceGenerator.setSequenceLength(mSequenceLength);
        }

        final CharSequence charSequence = sequenceGenerator.buildLatinSequence();

        switch (mTextType) {
            case SPANNED:
            case SPANNABLE_BUILDER:
                return createSpannable(charSequence);
            case STRING:
            default:
                return createString(charSequence);
        }
    }

    private Spannable createSpannable(CharSequence charSequence) {
        final Spannable spannable = (mTextType == TextType.SPANNABLE_BUILDER) ?
                new SpannableStringBuilder(charSequence) : new SpannableString(charSequence);

        if (!mCreateBoring) {
            // add a paragraph style to make it non boring
            spannable.setSpan(new BulletSpan(), 0, spannable.length(), SPAN_INCLUSIVE_INCLUSIVE);
        }

        spannable.setSpan(new Object(), 0, spannable.length(), SPAN_INCLUSIVE_INCLUSIVE);
        spannable.setSpan(new Object(), 0, 1, SPAN_INCLUSIVE_INCLUSIVE);

        return spannable;
    }

    private String createString(CharSequence charSequence) {
        if (mCreateBoring) {
            return charSequence.toString();
        } else {
            // BoringLayout checks to see if there is a surrogate pair and if so tells that
            // the charSequence is not suitable for boring. Add an emoji to make it non boring.
            // Emoji is added instead of RTL, since emoji stays in the same run and is a more
            // common case.
            return charSequence.toString() + "\uD83D\uDC68\uD83C\uDFFF";
        }
    }

    public static class RandomCharSequenceGenerator {

        private static final int DEFAULT_MIN_WORD_LENGTH = 3;
        private static final int DEFAULT_MAX_WORD_LENGTH = 15;
        private static final int DEFAULT_SEQUENCE_LENGTH = 256;

        private int mMinWordLength = DEFAULT_MIN_WORD_LENGTH;
        private int mMaxWordLength = DEFAULT_MAX_WORD_LENGTH;
        private int mSequenceLength = DEFAULT_SEQUENCE_LENGTH;
        private final Random mRandom;

        public RandomCharSequenceGenerator(Random random) {
            mRandom = random;
        }

        public RandomCharSequenceGenerator setSequenceLength(int sequenceLength) {
            mSequenceLength = sequenceLength;
            return this;
        }

        public CharSequence buildLatinSequence() {
            final StringBuilder result = new StringBuilder();
            while (result.length() < mSequenceLength) {
                // add random word
                result.append(buildLatinWord());
                result.append(' ');
            }
            return result.substring(0, mSequenceLength);
        }

        public CharSequence buildLatinWord() {
            final StringBuilder result = new StringBuilder();
            // create a random length that is (mMinWordLength + random amount of chars) where
            // total size is less than mMaxWordLength
            final int length = mRandom.nextInt(mMaxWordLength - mMinWordLength) + mMinWordLength;
            while (result.length() < length) {
                // add random letter
                int base = mRandom.nextInt(2) == 0 ? 'A' : 'a';
                result.append(Character.toChars(mRandom.nextInt(26) + base));
            }
            return result.toString();
        }
    }

}
