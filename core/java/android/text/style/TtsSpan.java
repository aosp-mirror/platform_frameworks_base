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

import android.os.Parcel;
import android.os.PersistableBundle;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A span that supplies addition meta-data intended for text-to-speech rendering
 * of the associated text.  If the text is being processed by a text-to-speech
 * engine, the engine may use the data in this span in addition to or instead of
 * its associated text.
 */
public class TtsSpan implements ParcelableSpan {
    private final String mType;
    private final PersistableBundle mArgs;

    /**
     * This span type can be used to add morphosyntactic features to the text it
     * spans over, or synthesize a something else than the spanned text.  Use
     * the argument {@link #ARG_TEXT} to set a different text.
     * Accepts the arguments {@link TtsSpan#ARG_GENDER},
     * {@link TtsSpan#ARG_ANIMACY}, {@link TtsSpan#ARG_MULTIPLICITY} and
     * {@link TtsSpan#ARG_CASE}.
     */
    public static final String TYPE_TEXT = "android.type.text";

    /**
     * The text associated with this span is a cardinal.  Must include the
     * number to be synthesized with {@link #ARG_NUMBER}.
     * Also accepts the arguments {@link TtsSpan#ARG_GENDER},
     * {@link TtsSpan#ARG_ANIMACY}, {@link TtsSpan#ARG_MULTIPLICITY} and
     * {@link TtsSpan#ARG_CASE}.
     */
    public static final String TYPE_CARDINAL = "android.type.cardinal";

    /**
     * String supplying the text to be synthesized.  The synthesizer is free
     * to decide how to interpret the text.
     */
    public static final String ARG_TEXT = "android.arg.text";

    /**
     * Argument used to specify a whole number.  The value can be a string of
     * digits of any size optionally prefixed with a - or +.
     */
    public static final String ARG_NUMBER = "android.arg.number";

    /**
     * String argument supplying gender information.  Can be any of
     * {@link TtsSpan#GENDER_NEUTRAL}, {@link TtsSpan#GENDER_MALE} and
     * {@link TtsSpan#GENDER_FEMALE}.
     */
    public static final String ARG_GENDER = "android.arg.gender";

    public static final String GENDER_NEUTRAL = "android.neutral";
    public static final String GENDER_MALE = "android.male";
    public static final String GENDER_FEMALE = "android.female";

    /**
     * String argument supplying animacy information.  Can be
     * {@link TtsSpan#ANIMACY_ANIMATE} or
     * {@link TtsSpan#ANIMACY_INANIMATE}
     */
    public static final String ARG_ANIMACY = "android.arg.animacy";

    public static final String ANIMACY_ANIMATE = "android.animate";
    public static final String ANIMACY_INANIMATE = "android.inanimate";

    /**
     * String argument supplying multiplicity information.  Can be any of
     * {@link TtsSpan#MULTIPLICITY_SINGLE},
     * {@link TtsSpan#MULTIPLICITY_DUAL} and
     * {@link TtsSpan#MULTIPLICITY_PLURAL}
     */
    public static final String ARG_MULTIPLICITY = "android.arg.multiplicity";

    public static final String MULTIPLICITY_SINGLE = "android.single";
    public static final String MULTIPLICITY_DUAL = "android.dual";
    public static final String MULTIPLICITY_PLURAL = "android.plural";

    /**
     * String argument supplying case information.  Can be any of
     * {@link TtsSpan#CASE_NOMINATIVE}, {@link TtsSpan#CASE_ACCUSATIVE},
     * {@link TtsSpan#CASE_DATIVE}, {@link TtsSpan#CASE_ABLATIVE},
     * {@link TtsSpan#CASE_GENITIVE}, {@link TtsSpan#CASE_VOCATIVE},
     * {@link TtsSpan#CASE_LOCATIVE} and
     * {@link TtsSpan#CASE_INSTRUMENTAL}
     */
    public static final String ARG_CASE = "android.arg.case";

    public static final String CASE_NOMINATIVE = "android.nomative";
    public static final String CASE_ACCUSATIVE = "android.accusative";
    public static final String CASE_DATIVE = "android.dative";
    public static final String CASE_ABLATIVE = "android.ablative";
    public static final String CASE_GENITIVE = "android.genitive";
    public static final String CASE_VOCATIVE = "android.vocative";
    public static final String CASE_LOCATIVE = "android.locative";
    public static final String CASE_INSTRUMENTAL = "android.instrumental";

    public TtsSpan(String type, PersistableBundle args) {
        mType = type;
        mArgs = args;
    }

    public TtsSpan(Parcel src) {
        mType = src.readString();
        mArgs = src.readPersistableBundle();
    }

    public String getType() {
        return mType;
    }

    public PersistableBundle getArgs() {
        return mArgs;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writePersistableBundle(mArgs);
    }

    @Override
    public int getSpanTypeId() {
        return TextUtils.TTS_SPAN;
    }
}
