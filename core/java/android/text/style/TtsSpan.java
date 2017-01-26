/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.text.style;

import java.text.NumberFormat;
import java.util.Locale;

import android.os.Parcel;
import android.os.PersistableBundle;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A span that supplies additional meta-data for the associated text intended
 * for text-to-speech engines. If the text is being processed by a
 * text-to-speech engine, the engine may use the data in this span in addition
 * to or instead of its associated text.
 *
 * Each instance of a TtsSpan has a type, for example {@link #TYPE_DATE}
 * or {@link #TYPE_MEASURE}. And a list of arguments, provided as
 * key-value pairs in a bundle.
 *
 * The inner classes are there for convenience and provide builders for each
 * TtsSpan type.
 */
public class TtsSpan implements ParcelableSpan {
    private final String mType;
    private final PersistableBundle mArgs;

    /**
     * This span type can be used to add morphosyntactic features to the text it
     * spans over, or synthesize a something else than the spanned text. Use
     * the argument {@link #ARG_TEXT} to set a different text.
     * Accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_TEXT = "android.type.text";

    /**
     * The text associated with this span is a cardinal. Must include the
     * number to be synthesized with {@link #ARG_NUMBER}.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_CARDINAL = "android.type.cardinal";

    /**
     * The text associated with this span is an ordinal. Must include the
     * number to be synthesized with {@link #ARG_NUMBER}.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_ORDINAL = "android.type.ordinal";

    /**
     * The text associated with this span is a decimal number. Must include the
     * number to be synthesized with {@link #ARG_INTEGER_PART} and
     * {@link #ARG_FRACTIONAL_PART}.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_DECIMAL = "android.type.decimal";

    /**
     * The text associated with this span is a fractional number. Must include
     * the number to be synthesized with {@link #ARG_NUMERATOR} and
     * {@link #ARG_DENOMINATOR}. {@link #ARG_INTEGER_PART} is optional
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_FRACTION = "android.type.fraction";

    /**
     * The text associated with this span is a measure, consisting of a number
     * and a unit. The number can be a cardinal, decimal or a fraction. Set the
     * number with the same arguments as {@link #TYPE_CARDINAL},
     * {@link #TYPE_DECIMAL} or {@link #TYPE_FRACTION}. The unit can be
     * specified with {@link #ARG_UNIT}.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_MEASURE = "android.type.measure";

    /**
     * The text associated with this span is a time, consisting of a number of
     * hours and minutes, specified with {@link #ARG_HOURS} and
     * {@link #ARG_MINUTES}.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and
     * {@link #ARG_CASE}.
     */
    public static final String TYPE_TIME = "android.type.time";

    /**
     * The text associated with this span is a date. At least one of the
     * arguments {@link #ARG_MONTH} and {@link #ARG_YEAR} has to be provided.
     * The argument {@link #ARG_DAY} is optional if {@link #ARG_MONTH} is set.
     * The argument {@link #ARG_WEEKDAY} is optional if {@link #ARG_DAY} is set.
     * Also accepts the arguments {@link #ARG_GENDER}, {@link #ARG_ANIMACY},
     * {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_DATE = "android.type.date";

    /**
     * The text associated with this span is a telephone number. The argument
     * {@link #ARG_NUMBER_PARTS} is required. {@link #ARG_COUNTRY_CODE} and
     * {@link #ARG_EXTENSION} are optional.
     * Also accepts the arguments {@link #ARG_GENDER}, {@link #ARG_ANIMACY},
     * {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_TELEPHONE = "android.type.telephone";

    /**
     * The text associated with this span is a URI (can be used for URLs and
     * email addresses). The full schema for URLs, which email addresses can
     * effectively be seen as a subset of, is:
     * protocol://username:password@domain:port/path?query_string#fragment_id
     * Hence populating just username and domain will read as an email address.
     * All arguments are optional, but at least one has to be provided:
     * {@link #ARG_PROTOCOL}, {@link #ARG_USERNAME}, {@link #ARG_PASSWORD},
     * {@link #ARG_DOMAIN}, {@link #ARG_PORT}, {@link #ARG_PATH},
     * {@link #ARG_QUERY_STRING} and {@link #ARG_FRAGMENT_ID}.
     * Also accepts the arguments {@link #ARG_GENDER}, {@link #ARG_ANIMACY},
     * {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_ELECTRONIC = "android.type.electronic";

    /**
     * The text associated with this span is an amount of money. Set the amount
     * with the same arguments as {@link #TYPE_DECIMAL}.
     * {@link #ARG_CURRENCY} is used to set the currency. {@link #ARG_QUANTITY}
     * is optional.
     * Also accepts the arguments {@link #ARG_GENDER}, {@link #ARG_ANIMACY},
     * {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_MONEY = "android.type.money";

    /**
     * The text associated with this span is a series of digits that have to be
     * read sequentially. The digits can be set with {@link #ARG_DIGITS}.
     * Also accepts the arguments {@link #ARG_GENDER}, {@link #ARG_ANIMACY},
     * {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_DIGITS = "android.type.digits";

    /**
     * The text associated with this span is a series of characters that have to
     * be read verbatim. The engine will attempt to read out any character like
     * punctuation but excluding whitespace. {@link #ARG_VERBATIM} is required.
     * Also accepts the arguments {@link #ARG_GENDER},
     * {@link #ARG_ANIMACY}, {@link #ARG_MULTIPLICITY} and {@link #ARG_CASE}.
     */
    public static final String TYPE_VERBATIM = "android.type.verbatim";

    /**
     * String argument supplying gender information. Can be any of
     * {@link #GENDER_NEUTRAL}, {@link #GENDER_MALE} and
     * {@link #GENDER_FEMALE}.
     */
    public static final String ARG_GENDER = "android.arg.gender";

    public static final String GENDER_NEUTRAL = "android.neutral";
    public static final String GENDER_MALE = "android.male";
    public static final String GENDER_FEMALE = "android.female";

    /**
     * String argument supplying animacy information. Can be
     * {@link #ANIMACY_ANIMATE} or
     * {@link #ANIMACY_INANIMATE}
     */
    public static final String ARG_ANIMACY = "android.arg.animacy";

    public static final String ANIMACY_ANIMATE = "android.animate";
    public static final String ANIMACY_INANIMATE = "android.inanimate";

    /**
     * String argument supplying multiplicity information. Can be any of
     * {@link #MULTIPLICITY_SINGLE}, {@link #MULTIPLICITY_DUAL} and
     * {@link #MULTIPLICITY_PLURAL}
     */
    public static final String ARG_MULTIPLICITY = "android.arg.multiplicity";

    public static final String MULTIPLICITY_SINGLE = "android.single";
    public static final String MULTIPLICITY_DUAL = "android.dual";
    public static final String MULTIPLICITY_PLURAL = "android.plural";

    /**
     * String argument supplying case information. Can be any of
     * {@link #CASE_NOMINATIVE}, {@link #CASE_ACCUSATIVE}, {@link #CASE_DATIVE},
     * {@link #CASE_ABLATIVE}, {@link #CASE_GENITIVE}, {@link #CASE_VOCATIVE},
     * {@link #CASE_LOCATIVE} and {@link #CASE_INSTRUMENTAL}
     */
    public static final String ARG_CASE = "android.arg.case";

    public static final String CASE_NOMINATIVE = "android.nominative";
    public static final String CASE_ACCUSATIVE = "android.accusative";
    public static final String CASE_DATIVE = "android.dative";
    public static final String CASE_ABLATIVE = "android.ablative";
    public static final String CASE_GENITIVE = "android.genitive";
    public static final String CASE_VOCATIVE = "android.vocative";
    public static final String CASE_LOCATIVE = "android.locative";
    public static final String CASE_INSTRUMENTAL = "android.instrumental";

    /**
     * String supplying the text to be synthesized. The synthesizer is free
     * to decide how to interpret the text.
     * Can be used with {@link #TYPE_TEXT}.
     */
    public static final String ARG_TEXT = "android.arg.text";

    /**
     * Argument used to specify a whole number. The value can be a string of
     * digits of any size optionally prefixed with a - or +.
     * Can be used with {@link #TYPE_CARDINAL} and {@link #TYPE_ORDINAL}.
     */
    public static final String ARG_NUMBER = "android.arg.number";

    /**
     * Argument used to specify the integer part of a decimal or fraction. The
     * value can be a string of digits of any size optionally prefixed with
     * a - or +.
     * Can be used with {@link #TYPE_DECIMAL} and {@link #TYPE_FRACTION}.
     */
    public static final String ARG_INTEGER_PART = "android.arg.integer_part";

    /**
     * Argument used to specify the fractional part of a decimal. The value can
     * be a string of digits of any size.
     * Can be used with {@link #TYPE_DECIMAL}.
     */
    public static final String ARG_FRACTIONAL_PART =
        "android.arg.fractional_part";

    /**
     * Argument used to choose the suffix (thousand, million, etc) that is used
     * to pronounce large amounts of money. For example it can be used to
     * disambiguate between "two thousand five hundred dollars" and
     * "two point five thousand dollars".
     * If implemented, engines should support at least "1000", "1000000",
     * "1000000000" and "1000000000000".
     * Example: if the {@link #ARG_INTEGER_PART} argument is "10", the
     * {@link #ARG_FRACTIONAL_PART} argument is "4", the {@link #ARG_QUANTITY}
     * argument is "1000" and the {@link #ARG_CURRENCY} argument is "usd", the
     * TTS engine may pronounce the span as "ten point four thousand dollars".
     * With the same example but with the quantity set as "1000000" the TTS
     * engine may pronounce the span as "ten point four million dollars".
     * Can be used with {@link #TYPE_MONEY}.
     */
    public static final String ARG_QUANTITY =
            "android.arg.quantity";

    /**
     * Argument used to specify the numerator of a fraction. The value can be a
     * string of digits of any size optionally prefixed with a - or +.
     * Can be used with {@link #TYPE_FRACTION}.
     */
    public static final String ARG_NUMERATOR = "android.arg.numerator";

    /**
     * Argument used to specify the denominator of a fraction. The value can be
     * a string of digits of any size optionally prefixed with a + or -.
     * Can be used with {@link #TYPE_FRACTION}.
     */
    public static final String ARG_DENOMINATOR = "android.arg.denominator";

    /**
     * Argument used to specify the unit of a measure. The unit should always be
     * specified in English singular form. Prefixes may be used. Engines will do
     * their best to pronounce them correctly in the language used. Engines are
     * expected to at least support the most common ones like "meter", "second",
     * "degree celsius" and "degree fahrenheit" with some common prefixes like
     * "milli" and "kilo".
     * Can be used with {@link #TYPE_MEASURE}.
     */
    public static final String ARG_UNIT = "android.arg.unit";

    /**
     * Argument used to specify the hours of a time. The hours should be
     * provided as an integer in the range from 0 up to and including 24.
     * Can be used with {@link #TYPE_TIME}.
     */
    public static final String ARG_HOURS = "android.arg.hours";

    /**
     * Argument used to specify the minutes of a time. The hours should be
     * provided as an integer in the range from 0 up to and including 59.
     * Can be used with {@link #TYPE_TIME}.
     */
    public static final String ARG_MINUTES = "android.arg.minutes";

    /**
     * Argument used to specify the weekday of a date. The value should be
     * provided as an integer and can be any of {@link #WEEKDAY_SUNDAY},
     * {@link #WEEKDAY_MONDAY}, {@link #WEEKDAY_TUESDAY},
     * {@link #WEEKDAY_WEDNESDAY}, {@link #WEEKDAY_THURSDAY},
     * {@link #WEEKDAY_FRIDAY} and {@link #WEEKDAY_SATURDAY}.
     * Can be used with {@link #TYPE_DATE}.
     */
    public static final String ARG_WEEKDAY = "android.arg.weekday";

    public static final int WEEKDAY_SUNDAY = 1;
    public static final int WEEKDAY_MONDAY = 2;
    public static final int WEEKDAY_TUESDAY = 3;
    public static final int WEEKDAY_WEDNESDAY = 4;
    public static final int WEEKDAY_THURSDAY = 5;
    public static final int WEEKDAY_FRIDAY = 6;
    public static final int WEEKDAY_SATURDAY = 7;

    /**
     * Argument used to specify the day of the month of a date. The value should
     * be provided as an integer in the range from 1 up to and including 31.
     * Can be used with {@link #TYPE_DATE}.
     */
    public static final String ARG_DAY = "android.arg.day";

    /**
     * Argument used to specify the month of a date. The value should be
     * provided as an integer and can be any of {@link #MONTH_JANUARY},
     * {@link #MONTH_FEBRUARY},  {@link #MONTH_MARCH}, {@link #MONTH_APRIL},
     * {@link #MONTH_MAY}, {@link #MONTH_JUNE}, {@link #MONTH_JULY},
     * {@link #MONTH_AUGUST}, {@link #MONTH_SEPTEMBER}, {@link #MONTH_OCTOBER},
     * {@link #MONTH_NOVEMBER} and {@link #MONTH_DECEMBER}.
     * Can be used with {@link #TYPE_DATE}.
     */
    public static final String ARG_MONTH = "android.arg.month";

    public static final int MONTH_JANUARY = 0;
    public static final int MONTH_FEBRUARY = 1;
    public static final int MONTH_MARCH = 2;
    public static final int MONTH_APRIL = 3;
    public static final int MONTH_MAY = 4;
    public static final int MONTH_JUNE = 5;
    public static final int MONTH_JULY = 6;
    public static final int MONTH_AUGUST = 7;
    public static final int MONTH_SEPTEMBER = 8;
    public static final int MONTH_OCTOBER = 9;
    public static final int MONTH_NOVEMBER = 10;
    public static final int MONTH_DECEMBER = 11;

    /**
     * Argument used to specify the year of a date. The value should be provided
     * as a positive integer.
     * Can be used with {@link #TYPE_DATE}.
     */
    public static final String ARG_YEAR = "android.arg.year";

    /**
     * Argument used to specify the country code of a telephone number. Can be
     * a string of digits optionally prefixed with a "+".
     * Can be used with {@link #TYPE_TELEPHONE}.
     */
    public static final String ARG_COUNTRY_CODE = "android.arg.country_code";

    /**
     * Argument used to specify the main number part of a telephone number. Can
     * be a string of digits where the different parts of the telephone number
     * can be separated with a space, '-', '/' or '.'.
     * Can be used with {@link #TYPE_TELEPHONE}.
     */
    public static final String ARG_NUMBER_PARTS = "android.arg.number_parts";

    /**
     * Argument used to specify the extension part of a telephone number. Can be
     * a string of digits.
     * Can be used with {@link #TYPE_TELEPHONE}.
     */
    public static final String ARG_EXTENSION = "android.arg.extension";

    /**
     * Argument used to specify the protocol of a URI. Examples are "http" and
     * "ftp".
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_PROTOCOL = "android.arg.protocol";

    /**
     * Argument used to specify the username part of a URI. Should be set as a
     * string.
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_USERNAME = "android.arg.username";

    /**
     * Argument used to specify the password part of a URI. Should be set as a
     * string.
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_PASSWORD = "android.arg.password";

    /**
     * Argument used to specify the domain part of a URI. For example
     * "source.android.com".
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_DOMAIN = "android.arg.domain";

    /**
     * Argument used to specify the port number of a URI. Should be specified as
     * an integer.
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_PORT = "android.arg.port";

    /**
     * Argument used to specify the path part of a URI. For example
     * "source/index.html".
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_PATH = "android.arg.path";

    /**
     * Argument used to specify the query string of a URI. For example
     * "arg=value&argtwo=value".
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_QUERY_STRING = "android.arg.query_string";

    /**
     * Argument used to specify the fragment id of a URI. Should be specified as
     * a string.
     * Can be used with {@link #TYPE_ELECTRONIC}.
     */
    public static final String ARG_FRAGMENT_ID = "android.arg.fragment_id";

    /**
     * Argument used to specify the currency. Should be a ISO4217 currency code,
     * e.g. "USD".
     * Can be used with {@link #TYPE_MONEY}.
     */
    public static final String ARG_CURRENCY = "android.arg.money";

    /**
     * Argument used to specify a string of digits.
     * Can be used with {@link #TYPE_DIGITS}.
     */
    public static final String ARG_DIGITS = "android.arg.digits";

    /**
     * Argument used to specify a string where the characters are read verbatim,
     * except whitespace.
     * Can be used with {@link #TYPE_VERBATIM}.
     */
    public static final String ARG_VERBATIM = "android.arg.verbatim";

    public TtsSpan(String type, PersistableBundle args) {
        mType = type;
        mArgs = args;
    }

    public TtsSpan(Parcel src) {
        mType = src.readString();
        mArgs = src.readPersistableBundle();
    }

    /**
     * Returns the type.
     * @return The type of this instance.
     */
    public String getType() {
        return mType;
    }

    /**
     * Returns a bundle of the arguments set.
     * @return The bundle of the arguments set.
     */
    public PersistableBundle getArgs() {
        return mArgs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writePersistableBundle(mArgs);
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    public int getSpanTypeIdInternal() {
        return TextUtils.TTS_SPAN;
    }

    /**
     * A simple builder for TtsSpans.
     * This builder can be used directly, but the more specific subclasses of
     * this builder like {@link TtsSpan.TextBuilder} and
     * {@link TtsSpan.CardinalBuilder} are likely more useful.
     *
     * This class uses generics so methods from this class can return instances
     * of its child classes, resulting in a fluent API (CRTP pattern).
     */
    public static class Builder<C extends Builder<?>> {
        // Holds the type of this class.
        private final String mType;

        // Holds the arguments of this class. It only stores objects of type
        // String, Integer and Long.
        private PersistableBundle mArgs = new PersistableBundle();

        public Builder(String type) {
            mType = type;
        }

        /**
         * Returns a TtsSpan built from the parameters set by the setter
         * methods.
         * @return A TtsSpan built with parameters of this builder.
         */
        public TtsSpan build() {
            return new TtsSpan(mType, mArgs);
        }

        /**
         * Sets an argument to a string value.
         * @param arg The argument name.
         * @param value The value the argument should be set to.
         * @return This instance.
         */
        @SuppressWarnings("unchecked")
        public C setStringArgument(String arg, String value) {
            mArgs.putString(arg, value);
            return (C) this;
        }

        /**
         * Sets an argument to an int value.
         * @param arg The argument name.
         * @param value The value the argument should be set to.
         */
        @SuppressWarnings("unchecked")
        public C setIntArgument(String arg, int value) {
            mArgs.putInt(arg, value);
            return (C) this;
        }

        /**
         * Sets an argument to a long value.
         * @param arg The argument name.
         * @param value The value the argument should be set to.
         */
        @SuppressWarnings("unchecked")
        public C setLongArgument(String arg, long value) {
            mArgs.putLong(arg, value);
            return (C) this;
        }
    }

    /**
     * A builder for TtsSpans, has setters for morphosyntactic features.
     * This builder can be used directly, but the more specific subclasses of
     * this builder like {@link TtsSpan.TextBuilder} and
     * {@link TtsSpan.CardinalBuilder} are likely more useful.
     */
    public static class SemioticClassBuilder<C extends SemioticClassBuilder<?>>
            extends Builder<C> {

        public SemioticClassBuilder(String type) {
            super(type);
        }

        /**
         * Sets the gender information for this instance.
         * @param gender Can any of {@link #GENDER_NEUTRAL},
         *     {@link #GENDER_MALE} and {@link #GENDER_FEMALE}.
         * @return This instance.
         */
        public C setGender(String gender) {
            return setStringArgument(TtsSpan.ARG_GENDER, gender);
        }

        /**
         * Sets the animacy information for this instance.
         * @param animacy Can be any of {@link #ANIMACY_ANIMATE} and
         *     {@link #ANIMACY_INANIMATE}.
         * @return This instance.
         */
        public C setAnimacy(String animacy) {
            return setStringArgument(TtsSpan.ARG_ANIMACY, animacy);
        }

        /**
         * Sets the multiplicity information for this instance.
         * @param multiplicity Can be any of
         *     {@link #MULTIPLICITY_SINGLE}, {@link #MULTIPLICITY_DUAL} and
         *     {@link #MULTIPLICITY_PLURAL}.
         * @return This instance.
         */
        public C setMultiplicity(String multiplicity) {
            return setStringArgument(TtsSpan.ARG_MULTIPLICITY, multiplicity);
        }

        /**
         * Sets the grammatical case information for this instance.
         * @param grammaticalCase Can be any of {@link #CASE_NOMINATIVE},
         *     {@link #CASE_ACCUSATIVE}, {@link #CASE_DATIVE},
         *     {@link #CASE_ABLATIVE}, {@link #CASE_GENITIVE},
         *     {@link #CASE_VOCATIVE}, {@link #CASE_LOCATIVE} and
         *     {@link #CASE_INSTRUMENTAL}.
         * @return This instance.
         */
        public C setCase(String grammaticalCase) {
            return setStringArgument(TtsSpan.ARG_CASE, grammaticalCase);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_TEXT}.
     */
    public static class TextBuilder extends SemioticClassBuilder<TextBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_TEXT}.
         */
        public TextBuilder() {
            super(TtsSpan.TYPE_TEXT);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_TEXT} and sets the
         * {@link #ARG_TEXT} argument.
         * @param text The text to be synthesized.
         * @see #setText(String)
         */
        public TextBuilder(String text) {
            this();
            setText(text);
        }

        /**
         * Sets the {@link #ARG_TEXT} argument, the text to be synthesized.
         * @param text The string that will be synthesized.
         * @return This instance.
         */
        public TextBuilder setText(String text) {
            return setStringArgument(TtsSpan.ARG_TEXT, text);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_CARDINAL}.
     */
    public static class CardinalBuilder
            extends SemioticClassBuilder<CardinalBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_CARDINAL}.
         */
        public CardinalBuilder() {
            super(TtsSpan.TYPE_CARDINAL);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_CARDINAL} and sets the
         * {@link #ARG_NUMBER} argument.
         * @param number The number to synthesize.
         * @see #setNumber(long)
         */
        public CardinalBuilder(long number) {
            this();
            setNumber(number);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_CARDINAL} and sets the
         * {@link #ARG_NUMBER} argument.
         * @param number The number to synthesize.
         * @see #setNumber(String)
         */
        public CardinalBuilder(String number) {
            this();
            setNumber(number);
        }

        /**
         * Convenience method that converts the number to a String and set it to
         * the value for {@link #ARG_NUMBER}.
         * @param number The number that will be synthesized.
         * @return This instance.
         */
        public CardinalBuilder setNumber(long number) {
            return setNumber(String.valueOf(number));
        }

        /**
         * Sets the {@link #ARG_NUMBER} argument.
         * @param number A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public CardinalBuilder setNumber(String number) {
            return setStringArgument(TtsSpan.ARG_NUMBER, number);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_ORDINAL}.
     */
    public static class OrdinalBuilder
            extends SemioticClassBuilder<OrdinalBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_ORDINAL}.
         */
        public OrdinalBuilder() {
            super(TtsSpan.TYPE_ORDINAL);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_ORDINAL} and sets the
         * {@link #ARG_NUMBER} argument.
         * @param number The ordinal number to synthesize.
         * @see #setNumber(long)
         */
        public OrdinalBuilder(long number) {
            this();
            setNumber(number);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_ORDINAL} and sets the
         * {@link #ARG_NUMBER} argument.
         * @param number The number to synthesize.
         * @see #setNumber(String)
         */
        public OrdinalBuilder(String number) {
            this();
            setNumber(number);
        }

        /**
         * Convenience method that converts the number to a String and sets it
         * to the value for {@link #ARG_NUMBER}.
         * @param number The ordinal number that will be synthesized.
         * @return This instance.
         */
        public OrdinalBuilder setNumber(long number) {
            return setNumber(String.valueOf(number));
        }

        /**
         * Sets the {@link #ARG_NUMBER} argument.
         * @param number A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public OrdinalBuilder setNumber(String number) {
            return setStringArgument(TtsSpan.ARG_NUMBER, number);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_DECIMAL}.
     */
    public static class DecimalBuilder
            extends SemioticClassBuilder<DecimalBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_DECIMAL}.
         */
        public DecimalBuilder() {
            super(TtsSpan.TYPE_DECIMAL);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_DECIMAL} and sets the
         * {@link #ARG_INTEGER_PART} and {@link #ARG_FRACTIONAL_PART} arguments.
         * @see #setArgumentsFromDouble(double, int, int)
         */
        public DecimalBuilder(double number,
                              int minimumFractionDigits,
                              int maximumFractionDigits) {
            this();
            setArgumentsFromDouble(number,
                                   minimumFractionDigits,
                                   maximumFractionDigits);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_DECIMAL} and sets the
         * {@link #ARG_INTEGER_PART} and {@link #ARG_FRACTIONAL_PART} arguments.
         */
        public DecimalBuilder(String integerPart, String fractionalPart) {
            this();
            setIntegerPart(integerPart);
            setFractionalPart(fractionalPart);
        }

        /**
         * Convenience method takes a double and a maximum number of fractional
         * digits, it sets the {@link #ARG_INTEGER_PART} and
         * {@link #ARG_FRACTIONAL_PART} arguments.
         * @param number The number to be synthesized.
         * @param minimumFractionDigits The minimum number of fraction digits
         *     that are pronounced.
         * @param maximumFractionDigits The maximum number of fraction digits
         *     that are pronounced. If maximumFractionDigits <
         *     minimumFractionDigits then minimumFractionDigits will be assumed
         *     to be equal to maximumFractionDigits.
         * @return This instance.
         */
        public DecimalBuilder setArgumentsFromDouble(
                double number,
                int minimumFractionDigits,
                int maximumFractionDigits) {
            // Format double.
            NumberFormat formatter = NumberFormat.getInstance(Locale.US);
            formatter.setMinimumFractionDigits(maximumFractionDigits);
            formatter.setMaximumFractionDigits(maximumFractionDigits);
            formatter.setGroupingUsed(false);
            String str = formatter.format(number);

            // Split at decimal point.
            int i = str.indexOf('.');
            if (i >= 0) {
                setIntegerPart(str.substring(0, i));
                setFractionalPart(str.substring(i + 1));
            } else {
                setIntegerPart(str);
            }
            return this;
        }

        /**
         * Convenience method that converts the number to a String and sets it
         * to the value for {@link #ARG_INTEGER_PART}.
         * @param integerPart The integer part of the decimal.
         * @return This instance.
         */
        public DecimalBuilder setIntegerPart(long integerPart) {
            return setIntegerPart(String.valueOf(integerPart));
        }

        /**
         * Sets the {@link #ARG_INTEGER_PART} argument.
         * @param integerPart A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public DecimalBuilder setIntegerPart(String integerPart) {
            return setStringArgument(TtsSpan.ARG_INTEGER_PART, integerPart);
        }

        /**
         * Sets the {@link #ARG_FRACTIONAL_PART} argument.
         * @param fractionalPart A non-empty string of digits.
         * @return This instance.
         */
        public DecimalBuilder setFractionalPart(String fractionalPart) {
            return setStringArgument(TtsSpan.ARG_FRACTIONAL_PART,
                                     fractionalPart);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_FRACTION}.
     */
    public static class FractionBuilder
            extends SemioticClassBuilder<FractionBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_FRACTION}.
         */
        public FractionBuilder() {
            super(TtsSpan.TYPE_FRACTION);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_FRACTION} and sets the
         * {@link #ARG_INTEGER_PART}, {@link #ARG_NUMERATOR}, and
         * {@link #ARG_DENOMINATOR} arguments.
         */
        public FractionBuilder(long integerPart,
                               long numerator,
                               long denominator) {
            this();
            setIntegerPart(integerPart);
            setNumerator(numerator);
            setDenominator(denominator);
        }

        /**
         * Convenience method that converts the integer to a String and sets the
         * argument {@link #ARG_NUMBER}.
         * @param integerPart The integer part.
         * @return This instance.
         */
        public FractionBuilder setIntegerPart(long integerPart) {
            return setIntegerPart(String.valueOf(integerPart));
        }

        /**
         * Sets the {@link #ARG_INTEGER_PART} argument.
         * @param integerPart A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public FractionBuilder setIntegerPart(String integerPart) {
            return setStringArgument(TtsSpan.ARG_INTEGER_PART, integerPart);
        }

        /**
         * Convenience method that converts the numerator to a String and sets
         * the argument {@link #ARG_NUMERATOR}.
         * @param numerator The numerator.
         * @return This instance.
         */
        public FractionBuilder setNumerator(long numerator) {
            return setNumerator(String.valueOf(numerator));
        }

        /**
         * Sets the {@link #ARG_NUMERATOR} argument.
         * @param numerator A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public FractionBuilder setNumerator(String numerator) {
            return setStringArgument(TtsSpan.ARG_NUMERATOR, numerator);
        }

        /**
         * Convenience method that converts the denominator to a String and sets
         * the argument {@link #ARG_DENOMINATOR}.
         * @param denominator The denominator.
         * @return This instance.
         */
        public FractionBuilder setDenominator(long denominator) {
            return setDenominator(String.valueOf(denominator));
        }

        /**
         * Sets the {@link #ARG_DENOMINATOR} argument.
         * @param denominator A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public FractionBuilder setDenominator(String denominator) {
            return setStringArgument(TtsSpan.ARG_DENOMINATOR, denominator);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_MEASURE}.
     */
    public static class MeasureBuilder
            extends SemioticClassBuilder<MeasureBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_MEASURE}.
         */
        public MeasureBuilder() {
            super(TtsSpan.TYPE_MEASURE);
        }

        /**
         * Convenience method that converts the number to a String and set it to
         * the value for {@link #ARG_NUMBER}.
         * @param number The amount of the measure.
         * @return This instance.
         */
        public MeasureBuilder setNumber(long number) {
            return setNumber(String.valueOf(number));
        }

        /**
         * Sets the {@link #ARG_NUMBER} argument.
         * @param number A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public MeasureBuilder setNumber(String number) {
            return setStringArgument(TtsSpan.ARG_NUMBER, number);
        }

        /**
         * Convenience method that converts the integer part to a String and set
         * it to the value for {@link #ARG_INTEGER_PART}.
         * @param integerPart The integer part of a decimal or fraction.
         * @return This instance.
         */
        public MeasureBuilder setIntegerPart(long integerPart) {
            return setIntegerPart(String.valueOf(integerPart));
        }

        /**
         * Sets the {@link #ARG_INTEGER_PART} argument.
         * @param integerPart The integer part of a decimal or fraction; a
         * non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public MeasureBuilder setIntegerPart(String integerPart) {
            return setStringArgument(TtsSpan.ARG_INTEGER_PART, integerPart);
        }

        /**
         * Sets the {@link #ARG_FRACTIONAL_PART} argument.
         * @param fractionalPart The fractional part of a decimal; a non-empty
         *     string of digits with an optional leading + or -.
         * @return This instance.
         */
        public MeasureBuilder setFractionalPart(String fractionalPart) {
            return setStringArgument(TtsSpan.ARG_FRACTIONAL_PART,
                                     fractionalPart);
        }

        /**
         * Convenience method that converts the numerator to a String and set it
         * to the value for {@link #ARG_NUMERATOR}.
         * @param numerator The numerator of a fraction.
         * @return This instance.
         */
        public MeasureBuilder setNumerator(long numerator) {
            return setNumerator(String.valueOf(numerator));
        }

        /**
         * Sets the {@link #ARG_NUMERATOR} argument.
         * @param numerator The numerator of a fraction; a non-empty string of
         *     digits with an optional leading + or -.
         * @return This instance.
         */
        public MeasureBuilder setNumerator(String numerator) {
            return setStringArgument(TtsSpan.ARG_NUMERATOR, numerator);
        }

        /**
         * Convenience method that converts the denominator to a String and set
         * it to the value for {@link #ARG_DENOMINATOR}.
         * @param denominator The denominator of a fraction.
         * @return This instance.
         */
        public MeasureBuilder setDenominator(long denominator) {
            return setDenominator(String.valueOf(denominator));
        }

        /**
         * Sets the {@link #ARG_DENOMINATOR} argument.
         * @param denominator The denominator of a fraction; a non-empty string
         *     of digits with an optional leading + or -.
         * @return This instance.
         */
        public MeasureBuilder setDenominator(String denominator) {
            return setStringArgument(TtsSpan.ARG_DENOMINATOR, denominator);
        }

        /**
         * Sets the {@link #ARG_UNIT} argument.
         * @param unit The unit of the measure.
         * @return This instance.
         * @see TtsSpan.ARG_UNIT
         */
        public MeasureBuilder setUnit(String unit) {
            return setStringArgument(TtsSpan.ARG_UNIT, unit);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_TIME}.
     */
    public static class TimeBuilder
            extends SemioticClassBuilder<TimeBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_TIME}.
         */
        public TimeBuilder() {
            super(TtsSpan.TYPE_TIME);
        }

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_TIME} and
         * sets the {@link #ARG_HOURS} and {@link #ARG_MINUTES} arguments.
         */
        public TimeBuilder(int hours, int minutes) {
            this();
            setHours(hours);
            setMinutes(minutes);
        }

        /**
         * Sets the {@link #ARG_HOURS} argument.
         * @param hours The value to be set for hours. See {@link #ARG_HOURS}.
         * @return This instance.
         * @see #ARG_HOURS
         */
        public TimeBuilder setHours(int hours) {
            return setIntArgument(TtsSpan.ARG_HOURS, hours);
        }

        /**
         * Sets the {@link #ARG_MINUTES} argument.
         * @param minutes The value to be set for minutes. See
         *     {@link #ARG_MINUTES}.
         * @return This instance.
         * @see #ARG_MINUTES
         */
        public TimeBuilder setMinutes(int minutes) {
            return setIntArgument(TtsSpan.ARG_MINUTES, minutes);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_DATE}.
     */
    public static class DateBuilder
            extends SemioticClassBuilder<DateBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_DATE}.
         */
        public DateBuilder() {
            super(TtsSpan.TYPE_DATE);
        }

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_TIME} and
         * possibly sets the {@link #ARG_WEEKDAY}, {@link #ARG_DAY},
         * {@link #ARG_MONTH} and {@link #ARG_YEAR} arguments. Pass null to any
         * argument to leave it unset.
         */
        public DateBuilder(Integer weekday,
                           Integer day,
                           Integer month,
                           Integer year) {
            this();
            if (weekday != null) {
                setWeekday(weekday);
            }
            if (day != null) {
                setDay(day);
            }
            if (month != null) {
                setMonth(month);
            }
            if (year != null) {
                setYear(year);
            }
        }

        /**
         * Sets the {@link #ARG_WEEKDAY} argument.
         * @param weekday The value to be set for weekday. See
         *     {@link #ARG_WEEKDAY}.
         * @return This instance.
         * @see #ARG_WEEKDAY
         */
        public DateBuilder setWeekday(int weekday) {
            return setIntArgument(TtsSpan.ARG_WEEKDAY, weekday);
        }

        /**
         * Sets the {@link #ARG_DAY} argument.
         * @param day The value to be set for day. See {@link #ARG_DAY}.
         * @return This instance.
         * @see #ARG_DAY
         */
        public DateBuilder setDay(int day) {
            return setIntArgument(TtsSpan.ARG_DAY, day);
        }

        /**
         * Sets the {@link #ARG_MONTH} argument.
         * @param month The value to be set for month. See {@link #ARG_MONTH}.
         * @return This instance.
         * @see #ARG_MONTH
         */
        public DateBuilder setMonth(int month) {
            return setIntArgument(TtsSpan.ARG_MONTH, month);
        }

        /**
         * Sets the {@link #ARG_YEAR} argument.
         * @param year The value to be set for year. See {@link #ARG_YEAR}.
         * @return This instance.
         * @see #ARG_YEAR
         */
        public DateBuilder setYear(int year) {
            return setIntArgument(TtsSpan.ARG_YEAR, year);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_MONEY}.
     */
    public static class MoneyBuilder
            extends SemioticClassBuilder<MoneyBuilder> {

        /**
         * Creates a TtsSpan of type {@link #TYPE_MONEY}.
         */
        public MoneyBuilder() {
            super(TtsSpan.TYPE_MONEY);
        }

        /**
         * Convenience method that converts the number to a String and set it to
         * the value for {@link #ARG_INTEGER_PART}.
         * @param integerPart The integer part of the amount.
         * @return This instance.
         */
        public MoneyBuilder setIntegerPart(long integerPart) {
            return setIntegerPart(String.valueOf(integerPart));
        }

        /**
         * Sets the {@link #ARG_INTEGER_PART} argument.
         * @param integerPart A non-empty string of digits with an optional
         *     leading + or -.
         * @return This instance.
         */
        public MoneyBuilder setIntegerPart(String integerPart) {
            return setStringArgument(TtsSpan.ARG_INTEGER_PART, integerPart);
        }

        /**
         * Sets the {@link #ARG_FRACTIONAL_PART} argument.
         * @param fractionalPart Can be a string of digits of any size.
         * @return This instance.
         */
        public MoneyBuilder setFractionalPart(String fractionalPart) {
            return setStringArgument(TtsSpan.ARG_FRACTIONAL_PART, fractionalPart);
        }

        /**
         * Sets the {@link #ARG_CURRENCY} argument.
         * @param currency Should be a ISO4217 currency code, e.g. "USD".
         * @return This instance.
         */
        public MoneyBuilder setCurrency(String currency) {
            return setStringArgument(TtsSpan.ARG_CURRENCY, currency);
        }

        /**
         * Sets the {@link #ARG_QUANTITY} argument.
         * @param quantity
         * @return This instance.
         */
        public MoneyBuilder setQuantity(String quantity) {
            return setStringArgument(TtsSpan.ARG_QUANTITY, quantity);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_TELEPHONE}.
     */
    public static class TelephoneBuilder
            extends SemioticClassBuilder<TelephoneBuilder> {

        /**
         * Creates a TtsSpan of type {@link #TYPE_TELEPHONE}.
         */
        public TelephoneBuilder() {
            super(TtsSpan.TYPE_TELEPHONE);
        }

        /**
         * Creates a TtsSpan of type {@link #TYPE_TELEPHONE} and sets the
         * {@link #ARG_NUMBER_PARTS} argument.
         */
        public TelephoneBuilder(String numberParts) {
            this();
            setNumberParts(numberParts);
        }

        /**
         * Sets the {@link #ARG_COUNTRY_CODE} argument.
         * @param countryCode The country code can be a series of digits
         * optionally prefixed with a "+".
         * @return This instance.
         */
        public TelephoneBuilder setCountryCode(String countryCode) {
            return setStringArgument(TtsSpan.ARG_COUNTRY_CODE, countryCode);
        }

        /**
         * Sets the {@link #ARG_NUMBER_PARTS} argument.
         * @param numberParts The main telephone number. Can be a series of
         *     digits and letters separated by spaces, "/", "-" or ".".
         * @return This instance.
         */
        public TelephoneBuilder setNumberParts(String numberParts) {
            return setStringArgument(TtsSpan.ARG_NUMBER_PARTS, numberParts);
        }

        /**
         * Sets the {@link #ARG_EXTENSION} argument.
         * @param extension The extension can be a series of digits.
         * @return This instance.
         */
        public TelephoneBuilder setExtension(String extension) {
            return setStringArgument(TtsSpan.ARG_EXTENSION, extension);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_ELECTRONIC}.
     */
    public static class ElectronicBuilder
            extends SemioticClassBuilder<ElectronicBuilder> {

        /**
         * Creates a TtsSpan of type {@link #TYPE_ELECTRONIC}.
         */
        public ElectronicBuilder() {
            super(TtsSpan.TYPE_ELECTRONIC);
        }

        /**
         * Sets the {@link #ARG_USERNAME} and {@link #ARG_DOMAIN}
         *     arguments, representing an email address.
         * @param username The part before the @ in the email address.
         * @param domain The part after the @ in the email address.
         * @return This instance.
         */
        public ElectronicBuilder setEmailArguments(String username,
                                                   String domain) {
            return setDomain(domain).setUsername(username);
        }

        /**
         * Sets the {@link #ARG_PROTOCOL} argument.
         * @param protocol The protocol of the URI. Examples are "http" and
         *     "ftp".
         * @return This instance.
         */
        public ElectronicBuilder setProtocol(String protocol) {
            return setStringArgument(TtsSpan.ARG_PROTOCOL, protocol);
        }

        /**
         * Sets the {@link #ARG_USERNAME} argument.
         * @return This instance.
         */
        public ElectronicBuilder setUsername(String username) {
            return setStringArgument(TtsSpan.ARG_USERNAME, username);
        }

        /**
         * Sets the {@link #ARG_PASSWORD} argument.
         * @return This instance.
         */
        public ElectronicBuilder setPassword(String password) {
            return setStringArgument(TtsSpan.ARG_PASSWORD, password);
        }

        /**
         * Sets the {@link #ARG_DOMAIN} argument.
         * @param domain The domain, for example "source.android.com".
         * @return This instance.
         */
        public ElectronicBuilder setDomain(String domain) {
            return setStringArgument(TtsSpan.ARG_DOMAIN, domain);
        }

        /**
         * Sets the {@link #ARG_PORT} argument.
         * @return This instance.
         */
        public ElectronicBuilder setPort(int port) {
            return setIntArgument(TtsSpan.ARG_PORT, port);
        }

        /**
         * Sets the {@link #ARG_PATH} argument.
         * @param path For example "source/index.html".
         * @return This instance.
         */
        public ElectronicBuilder setPath(String path) {
            return setStringArgument(TtsSpan.ARG_PATH, path);
        }

        /**
         * Sets the {@link #ARG_QUERY_STRING} argument.
         * @param queryString For example "arg=value&argtwo=value".
         * @return This instance.
         */
        public ElectronicBuilder setQueryString(String queryString) {
            return setStringArgument(TtsSpan.ARG_QUERY_STRING, queryString);
        }

        /**
         * Sets the {@link #ARG_FRAGMENT_ID} argument.
         * @return This instance.
         */
        public ElectronicBuilder setFragmentId(String fragmentId) {
            return setStringArgument(TtsSpan.ARG_FRAGMENT_ID, fragmentId);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_DIGITS}.
     */
    public static class DigitsBuilder
            extends SemioticClassBuilder<DigitsBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_DIGITS}.
         */
        public DigitsBuilder() {
            super(TtsSpan.TYPE_DIGITS);
        }

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_DIGITS}
         * and sets the {@link #ARG_DIGITS} argument.
         */
        public DigitsBuilder(String digits) {
            this();
            setDigits(digits);
        }

        /**
         * Sets the {@link #ARG_DIGITS} argument.
         * @param digits A string of digits.
         * @return This instance.
         */
        public DigitsBuilder setDigits(String digits) {
            return setStringArgument(TtsSpan.ARG_DIGITS, digits);
        }
    }

    /**
     * A builder for TtsSpans of type {@link #TYPE_VERBATIM}.
     */
    public static class VerbatimBuilder
            extends SemioticClassBuilder<VerbatimBuilder> {

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_VERBATIM}.
         */
        public VerbatimBuilder() {
            super(TtsSpan.TYPE_VERBATIM);
        }

        /**
         * Creates a builder for a TtsSpan of type {@link #TYPE_VERBATIM}
         * and sets the {@link #ARG_VERBATIM} argument.
         */
        public VerbatimBuilder(String verbatim) {
            this();
            setVerbatim(verbatim);
        }

        /**
         * Sets the {@link #ARG_VERBATIM} argument.
         * @param verbatim A string of characters that will be read verbatim,
         *     except whitespace.
         * @return This instance.
         */
        public VerbatimBuilder setVerbatim(String verbatim) {
            return setStringArgument(TtsSpan.ARG_VERBATIM, verbatim);
        }
    }
}
