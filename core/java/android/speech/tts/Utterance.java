package android.speech.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * This class acts as a builder for {@link Markup} instances.
 * <p>
 * Each Utterance consists of a list of the semiotic classes ({@link Utterance.TtsCardinal} and
 * {@link Utterance.TtsText}).
 * <p>Each semiotic class can be supplied with morphosyntactic features
 * (gender, animacy, multiplicity and case), it is up to the synthesis engine to use this
 * information during synthesis.
 * Examples where morphosyntactic features matter:
 * <ul>
 * <li>In French, the number one is verbalized differently based on the gender of the noun
 * it modifies. "un homme" (one man) versus "une femme" (one woman).
 * <li>In German the grammatical case (accusative, locative, etc) needs to be included to be
 * verbalize correctly. In German you'd have the sentence "Sie haben 1 kilometer vor Ihnen" (You
 * have 1 kilometer ahead of you), "1" in this case needs to become inflected to the accusative
 * form ("einen") instead of the nominative form "ein".
 * </p>
 * <p>
 * Utterance usage example:
 * Markup m1 = new Utterance().append("The Eiffel Tower is")
 *                            .append(new TtsCardinal(324))
 *                            .append("meters tall.");
 * Markup m2 = new Utterance().append("Sie haben")
 *                            .append(new TtsCardinal(1).setGender(Utterance.GENDER_MALE)
 *                            .append("Tag frei.");
 * </p>
 */
public class Utterance {

    /***
     * Toplevel type of markup representation.
     */
    public static final String TYPE_UTTERANCE = "utterance";
    /***
     * The no_warning_on_fallback parameter can be set to "false" or "true", true indicating that
     * no warning will be given when the synthesizer does not support Markup. This is used when
     * the user only provides a string to the API instead of a markup.
     */
    public static final String KEY_NO_WARNING_ON_FALLBACK = "no_warning_on_fallback";

    // Gender.
    public final static int GENDER_UNKNOWN = 0;
    public final static int GENDER_NEUTRAL = 1;
    public final static int GENDER_MALE = 2;
    public final static int GENDER_FEMALE = 3;

    // Animacy.
    public final static int ANIMACY_UNKNOWN = 0;
    public final static int ANIMACY_ANIMATE = 1;
    public final static int ANIMACY_INANIMATE = 2;

    // Multiplicity.
    public final static int MULTIPLICITY_UNKNOWN = 0;
    public final static int MULTIPLICITY_SINGLE = 1;
    public final static int MULTIPLICITY_DUAL = 2;
    public final static int MULTIPLICITY_PLURAL = 3;

    // Case.
    public final static int CASE_UNKNOWN = 0;
    public final static int CASE_NOMINATIVE = 1;
    public final static int CASE_ACCUSATIVE = 2;
    public final static int CASE_DATIVE = 3;
    public final static int CASE_ABLATIVE = 4;
    public final static int CASE_GENITIVE = 5;
    public final static int CASE_VOCATIVE = 6;
    public final static int CASE_LOCATIVE = 7;
    public final static int CASE_INSTRUMENTAL = 8;

    private List<AbstractTts<? extends AbstractTts<?>>> says =
            new ArrayList<AbstractTts<? extends AbstractTts<?>>>();
    Boolean mNoWarningOnFallback = null;

    /**
     * Objects deriving from this class can be appended to a Utterance. This class uses generics
     * so method from this class can return instances of its child classes, resulting in a better
     * API (CRTP pattern).
     */
    public static abstract class AbstractTts<C extends AbstractTts<C>> {

        protected Markup mMarkup = new Markup();

        /**
         * Empty constructor.
         */
        protected AbstractTts() {
        }

        /**
         * Construct with Markup.
         * @param markup
         */
        protected AbstractTts(Markup markup) {
            mMarkup = markup;
        }

        /**
         * Returns the type of this class, e.g. "cardinal" or "measure".
         * @return The type.
         */
        public String getType() {
            return mMarkup.getType();
        }

        /**
         * A fallback plain text can be provided, in case the engine does not support this class
         * type, or even Markup altogether.
         * @param plainText A string with the plain text.
         * @return This instance.
         */
        @SuppressWarnings("unchecked")
        public C setPlainText(String plainText) {
            mMarkup.setPlainText(plainText);
            return (C) this;
        }

        /**
         * Returns the plain text (fallback) string.
         * @return Plain text string or null if not set.
         */
        public String getPlainText() {
            return mMarkup.getPlainText();
        }

        /**
         * Populates the plainText if not set and builds a Markup instance.
         * @return The Markup object describing this instance.
         */
        public Markup getMarkup() {
            return new Markup(mMarkup);
        }

        @SuppressWarnings("unchecked")
        protected C setParameter(String key, String value) {
            mMarkup.setParameter(key, value);
            return (C) this;
        }

        protected String getParameter(String key) {
            return mMarkup.getParameter(key);
        }

        @SuppressWarnings("unchecked")
        protected C removeParameter(String key) {
            mMarkup.removeParameter(key);
            return (C) this;
        }

        /**
         * Returns a string representation of this instance, can be deserialized to an equal
         * Utterance instance.
         */
        public String toString() {
            return mMarkup.toString();
        }

        /**
         * Returns a generated plain text alternative for this instance if this instance isn't
         * better representated by the list of it's children.
         * @return Best effort plain text representation of this instance, can be null.
         */
        public String generatePlainText() {
            return null;
        }
    }

    public static abstract class AbstractTtsSemioticClass<C extends AbstractTtsSemioticClass<C>>
            extends AbstractTts<C> {
        // Keys.
        private static final String KEY_GENDER = "gender";
        private static final String KEY_ANIMACY = "animacy";
        private static final String KEY_MULTIPLICITY = "multiplicity";
        private static final String KEY_CASE = "case";

        protected AbstractTtsSemioticClass() {
            super();
        }

        protected AbstractTtsSemioticClass(Markup markup) {
            super(markup);
        }

        @SuppressWarnings("unchecked")
        public C setGender(int gender) {
            if (gender < 0 || gender > 3) {
                throw new IllegalArgumentException("Only four types of gender can be set: " +
                                                   "unknown, neutral, maculine and female.");
            }
            if (gender != GENDER_UNKNOWN) {
                setParameter(KEY_GENDER, String.valueOf(gender));
            } else {
                setParameter(KEY_GENDER, null);
            }
            return (C) this;
        }

        public int getGender() {
            String gender = mMarkup.getParameter(KEY_GENDER);
            return gender != null ? Integer.valueOf(gender) : GENDER_UNKNOWN;
        }

        @SuppressWarnings("unchecked")
        public C setAnimacy(int animacy) {
            if (animacy < 0 || animacy > 2) {
                throw new IllegalArgumentException(
                        "Only two types of animacy can be set: unknown, animate and inanimate");
            }
            if (animacy != ANIMACY_UNKNOWN) {
                setParameter(KEY_ANIMACY, String.valueOf(animacy));
            } else {
                setParameter(KEY_ANIMACY, null);
            }
            return (C) this;
        }

        public int getAnimacy() {
            String animacy = getParameter(KEY_ANIMACY);
            return animacy != null ? Integer.valueOf(animacy) : ANIMACY_UNKNOWN;
        }

        @SuppressWarnings("unchecked")
        public C setMultiplicity(int multiplicity) {
            if (multiplicity < 0 || multiplicity > 3) {
                throw new IllegalArgumentException(
                        "Only four types of multiplicity can be set: unknown, single, dual and " +
                        "plural.");
            }
            if (multiplicity != MULTIPLICITY_UNKNOWN) {
                setParameter(KEY_MULTIPLICITY, String.valueOf(multiplicity));
            } else {
                setParameter(KEY_MULTIPLICITY, null);
            }
            return (C) this;
        }

        public int getMultiplicity() {
            String multiplicity = mMarkup.getParameter(KEY_MULTIPLICITY);
            return multiplicity != null ? Integer.valueOf(multiplicity) : MULTIPLICITY_UNKNOWN;
        }

        @SuppressWarnings("unchecked")
        public C setCase(int grammaticalCase) {
            if (grammaticalCase < 0 || grammaticalCase > 8) {
                throw new IllegalArgumentException(
                        "Only nine types of grammatical case can be set.");
            }
            if (grammaticalCase != CASE_UNKNOWN) {
                setParameter(KEY_CASE, String.valueOf(grammaticalCase));
            } else {
                setParameter(KEY_CASE, null);
            }
            return (C) this;
        }

        public int getCase() {
            String grammaticalCase = mMarkup.getParameter(KEY_CASE);
            return grammaticalCase != null ? Integer.valueOf(grammaticalCase) : CASE_UNKNOWN;
        }
    }

    /**
     * Class that contains regular text, synthesis engine pronounces it using its regular pipeline.
     * Parameters:
     * <ul>
     *   <li>Text: the text to synthesize</li>
     * </ul>
     */
    public static class TtsText extends AbstractTtsSemioticClass<TtsText> {

        // The type of this node.
        protected static final String TYPE_TEXT = "text";
        // The text parameter stores the text to be synthesized.
        private static final String KEY_TEXT = "text";

        /**
         * Default constructor.
         */
        public TtsText() {
            mMarkup.setType(TYPE_TEXT);
        }

        /**
         * Constructor that sets the text to be synthesized.
         * @param text The text to be synthesized.
         */
        public TtsText(String text) {
            this();
            setText(text);
        }

        /**
         * Constructs a TtsText with the values of the Markup, does not check if the given Markup is
         * of the right type.
         */
        private TtsText(Markup markup) {
            super(markup);
        }

        /**
         * Sets the text to be synthesized.
         * @return This instance.
         */
        public TtsText setText(String text) {
            setParameter(KEY_TEXT, text);
            return this;
        }

        /**
         * Returns the text to be synthesized.
         * @return This instance.
         */
        public String getText() {
            return getParameter(KEY_TEXT);
        }

        /**
         * Generates a best effort plain text, in this case simply the text.
         */
        @Override
        public String generatePlainText() {
            return getText();
        }
    }

    /**
     * Contains a cardinal.
     * Parameters:
     * <ul>
     *   <li>integer: the integer to synthesize</li>
     * </ul>
     */
    public static class TtsCardinal extends AbstractTtsSemioticClass<TtsCardinal> {

        // The type of this node.
        protected static final String TYPE_CARDINAL = "cardinal";
        // The parameter integer stores the integer to synthesize.
        private static final String KEY_INTEGER = "integer";

        /**
         * Default constructor.
         */
        public TtsCardinal() {
            mMarkup.setType(TYPE_CARDINAL);
        }

        /**
         * Constructor that sets the integer to be synthesized.
         */
        public TtsCardinal(int integer) {
            this();
            setInteger(integer);
        }

        /**
         * Constructor that sets the integer to be synthesized.
         */
        public TtsCardinal(String integer) {
            this();
            setInteger(integer);
        }

        /**
         * Constructs a TtsText with the values of the Markup.
         * Does not check if the given Markup is of the right type.
         */
        private TtsCardinal(Markup markup) {
            super(markup);
        }

        /**
         * Sets the integer.
         * @return This instance.
         */
        public TtsCardinal setInteger(int integer) {
            return setInteger(String.valueOf(integer));
        }

        /**
         * Sets the integer.
         * @param integer A non-empty string of digits with an optional '-' in front.
         * @return This instance.
         */
        public TtsCardinal setInteger(String integer) {
            if (!integer.matches("-?\\d+")) {
                throw new IllegalArgumentException("Expected a cardinal: \"" + integer + "\"");
            }
            setParameter(KEY_INTEGER, integer);
            return this;
        }

        /**
         * Returns the integer parameter.
         */
        public String getInteger() {
            return getParameter(KEY_INTEGER);
        }

        /**
         * Generates a best effort plain text, in this case simply the integer.
         */
        @Override
        public String generatePlainText() {
            return getInteger();
        }
    }

    /**
     * Default constructor.
     */
    public Utterance() {}

    /**
     * Returns the plain text of a given Markup if it was set; if it's not set, recursively call the
     * this same method on its children.
     */
    private String constructPlainText(Markup m) {
        StringBuilder plainText = new StringBuilder();
        if (m.getPlainText() != null) {
            plainText.append(m.getPlainText());
        } else {
            for (Markup nestedMarkup : m.getNestedMarkups()) {
                String nestedPlainText = constructPlainText(nestedMarkup);
                if (!nestedPlainText.isEmpty()) {
                    if (plainText.length() != 0) {
                        plainText.append(" ");
                    }
                    plainText.append(nestedPlainText);
                }
            }
        }
        return plainText.toString();
    }

    /**
     * Creates a Markup instance with auto generated plain texts for the relevant nodes, in case the
     * user has not provided one already.
     * @return A Markup instance representing this utterance.
     */
    public Markup createMarkup() {
        Markup markup = new Markup(TYPE_UTTERANCE);
        StringBuilder plainText = new StringBuilder();
        for (AbstractTts<? extends AbstractTts<?>> say : says) {
            // Get a copy of this markup, and generate a plaintext for it if is not set.
            Markup sayMarkup = say.getMarkup();
            if (sayMarkup.getPlainText() == null) {
                sayMarkup.setPlainText(say.generatePlainText());
            }
            if (plainText.length() != 0) {
                plainText.append(" ");
            }
            plainText.append(constructPlainText(sayMarkup));
            markup.addNestedMarkup(sayMarkup);
        }
        if (mNoWarningOnFallback != null) {
            markup.setParameter(KEY_NO_WARNING_ON_FALLBACK,
                                mNoWarningOnFallback ? "true" : "false");
        }
        markup.setPlainText(plainText.toString());
        return markup;
    }

    /**
     * Appends an element to this Utterance instance.
     * @return this instance
     */
    public Utterance append(AbstractTts<? extends AbstractTts<?>> say) {
        says.add(say);
        return this;
    }

    private Utterance append(Markup markup) {
        if (markup.getType().equals(TtsText.TYPE_TEXT)) {
            append(new TtsText(markup));
        } else if (markup.getType().equals(TtsCardinal.TYPE_CARDINAL)) {
            append(new TtsCardinal(markup));
        } else {
            // Unknown node, a class we don't know about.
            if (markup.getPlainText() != null) {
                append(new TtsText(markup.getPlainText()));
            } else {
                // No plainText specified; add its children
                // seperately. In case of a new prosody node,
                // we would still verbalize it correctly.
                for (Markup nested : markup.getNestedMarkups()) {
                    append(nested);
                }
            }
        }
        return this;
    }

    /**
     * Returns a string representation of this Utterance instance. Can be deserialized back to an
     * Utterance instance with utteranceFromString(). Can be used to store utterances to be used
     * at a later time.
     */
    public String toString() {
        String out = "type: \"" + TYPE_UTTERANCE + "\"";
        if (mNoWarningOnFallback != null) {
            out += " no_warning_on_fallback: \"" + (mNoWarningOnFallback ? "true" : "false") + "\"";
        }
        for (AbstractTts<? extends AbstractTts<?>> say : says) {
            out += " markup { " + say.getMarkup().toString() + " }";
        }
        return out;
    }

    /**
     * Returns an Utterance instance from the string representation generated by toString().
     * @param string The string representation generated by toString().
     * @return The new Utterance instance.
     * @throws {@link IllegalArgumentException} if the input cannot be correctly parsed.
     */
    static public Utterance utteranceFromString(String string) throws IllegalArgumentException {
        Utterance utterance = new Utterance();
        Markup markup = Markup.markupFromString(string);
        if (!markup.getType().equals(TYPE_UTTERANCE)) {
            throw new IllegalArgumentException("Top level markup should be of type \"" +
                                               TYPE_UTTERANCE + "\", but was of type \"" +
                                               markup.getType() + "\".") ;
        }
        for (Markup nestedMarkup : markup.getNestedMarkups()) {
            utterance.append(nestedMarkup);
        }
        return utterance;
    }

    /**
     * Appends a new TtsText with the given text.
     * @param text The text to synthesize.
     * @return This instance.
     */
    public Utterance append(String text) {
        return append(new TtsText(text));
    }

    /**
     * Appends a TtsCardinal representing the given number.
     * @param integer The integer to synthesize.
     * @return this
     */
    public Utterance append(int integer) {
        return append(new TtsCardinal(integer));
    }

    /**
     * Returns the n'th element in this Utterance.
     * @param i The index.
     * @return The n'th element in this Utterance.
     * @throws {@link IndexOutOfBoundsException} - if i < 0 || i >= size()
     */
    public AbstractTts<? extends AbstractTts<?>> get(int i) {
        return says.get(i);
    }

    /**
     * Returns the number of elements in this Utterance.
     * @return The number of elements in this Utterance.
     */
    public int size() {
        return says.size();
    }

    @Override
    public boolean equals(Object o) {
        if ( this == o ) return true;
        if ( !(o instanceof Utterance) ) return false;
        Utterance utt = (Utterance) o;

        if (says.size() != utt.says.size()) {
            return false;
        }

        for (int i = 0; i < says.size(); i++) {
            if (!says.get(i).getMarkup().equals(utt.says.get(i).getMarkup())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Can be set to true or false, true indicating that the user provided only a string to the API,
     * at which the system will not issue a warning if the synthesizer falls back onto the plain
     * text when the synthesizer does not support Markup.
     */
    public Utterance setNoWarningOnFallback(boolean noWarning) {
        mNoWarningOnFallback = noWarning;
        return this;
    }
}
