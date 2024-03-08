/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.icu.text.DisplayContext;
import android.icu.text.LocaleDisplayNames;
import android.icu.util.ULocale;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Printer;
import android.util.Slog;

import com.android.internal.inputmethod.SubtypeLocaleUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * This class is used to specify meta information of a subtype contained in an input method editor
 * (IME). Subtype can describe locale (e.g. en_US, fr_FR...) and mode (e.g. voice, keyboard...),
 * and is used for IME switch and settings. The input method subtype allows the system to bring up
 * the specified subtype of the designated IME directly.
 *
 * <p>It should be defined in an XML resource file of the input method with the
 * <code>&lt;subtype&gt;</code> element, which resides within an {@code <input-method>} element.
 * For more information, see the guide to
 * <a href="{@docRoot}guide/topics/text/creating-input-method.html">
 * Creating an Input Method</a>.</p>
 *
 * @see InputMethodInfo
 *
 * @attr ref android.R.styleable#InputMethod_Subtype_label
 * @attr ref android.R.styleable#InputMethod_Subtype_icon
 * @attr ref android.R.styleable#InputMethod_Subtype_languageTag
 * @attr ref android.R.styleable#InputMethod_Subtype_imeSubtypeLocale
 * @attr ref android.R.styleable#InputMethod_Subtype_imeSubtypeMode
 * @attr ref android.R.styleable#InputMethod_Subtype_imeSubtypeExtraValue
 * @attr ref android.R.styleable#InputMethod_Subtype_isAuxiliary
 * @attr ref android.R.styleable#InputMethod_Subtype_overridesImplicitlyEnabledSubtype
 * @attr ref android.R.styleable#InputMethod_Subtype_subtypeId
 * @attr ref android.R.styleable#InputMethod_Subtype_isAsciiCapable
 */
public final class InputMethodSubtype implements Parcelable {
    private static final String TAG = InputMethodSubtype.class.getSimpleName();
    private static final String LANGUAGE_TAG_NONE = "";
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_KEY_VALUE_SEPARATOR = "=";
    // TODO: remove this
    private static final String EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME =
            "UntranslatableReplacementStringInSubtypeName";
    /** {@hide} */
    public static final int SUBTYPE_ID_NONE = 0;

    private static final String SUBTYPE_MODE_KEYBOARD = "keyboard";

    private static final String UNDEFINED_LANGUAGE_TAG = "und";

    private final boolean mIsAuxiliary;
    private final boolean mOverridesImplicitlyEnabledSubtype;
    private final boolean mIsAsciiCapable;
    private final int mSubtypeHashCode;
    private final int mSubtypeIconResId;
    private final int mSubtypeNameResId;
    private final CharSequence mSubtypeNameOverride;
    private final String mPkLanguageTag;
    private final String mPkLayoutType;
    private final int mSubtypeId;
    private final String mSubtypeLocale;
    private final String mSubtypeLanguageTag;
    private final String mSubtypeMode;
    private final String mSubtypeExtraValue;
    private final Object mLock = new Object();
    private volatile Locale mCachedLocaleObj;
    private volatile HashMap<String, String> mExtraValueHashMapCache;

    /**
     * A volatile cache to optimize {@link #getCanonicalizedLanguageTag()}.
     *
     * <p>{@code null} means that the initial evaluation is not yet done.</p>
     */
    @Nullable
    private volatile String mCachedCanonicalizedLanguageTag;

    /**
     * InputMethodSubtypeBuilder is a builder class of InputMethodSubtype.
     * This class is designed to be used with
     * {@link android.view.inputmethod.InputMethodManager#setAdditionalInputMethodSubtypes}.
     * The developer needs to be aware of what each parameter means.
     */
    public static class InputMethodSubtypeBuilder {
        /**
         * @param isAuxiliary should true when this subtype is auxiliary, false otherwise.
         * An auxiliary subtype has the following differences with a regular subtype:
         * - An auxiliary subtype cannot be chosen as the default IME in Settings.
         * - The framework will never switch to this subtype through
         *   {@link android.view.inputmethod.InputMethodManager#switchToLastInputMethod}.
         * Note that the subtype will still be available in the IME switcher.
         * The intent is to allow for IMEs to specify they are meant to be invoked temporarily
         * in a one-shot way, and to return to the previous IME once finished (e.g. voice input).
         */
        public InputMethodSubtypeBuilder setIsAuxiliary(boolean isAuxiliary) {
            mIsAuxiliary = isAuxiliary;
            return this;
        }
        private boolean mIsAuxiliary = false;

        /**
         * @param overridesImplicitlyEnabledSubtype should be true if this subtype should be
         * enabled by default if no other subtypes in the IME are enabled explicitly. Note that a
         * subtype with this parameter set will not be shown in the list of subtypes in each IME's
         * subtype enabler. A canonical use of this would be for an IME to supply an "automatic"
         * subtype that adapts to the current system language.
         */
        public InputMethodSubtypeBuilder setOverridesImplicitlyEnabledSubtype(
                boolean overridesImplicitlyEnabledSubtype) {
            mOverridesImplicitlyEnabledSubtype = overridesImplicitlyEnabledSubtype;
            return this;
        }
        private boolean mOverridesImplicitlyEnabledSubtype = false;

        /**
         * @param isAsciiCapable should be true if this subtype is ASCII capable. If the subtype
         * is ASCII capable, it should guarantee that the user can input ASCII characters with
         * this subtype. This is important because many password fields only allow
         * ASCII-characters.
         */
        public InputMethodSubtypeBuilder setIsAsciiCapable(boolean isAsciiCapable) {
            mIsAsciiCapable = isAsciiCapable;
            return this;
        }
        private boolean mIsAsciiCapable = false;

        /**
         * @param subtypeIconResId is a resource ID of the subtype icon drawable.
         */
        public InputMethodSubtypeBuilder setSubtypeIconResId(int subtypeIconResId) {
            mSubtypeIconResId = subtypeIconResId;
            return this;
        }
        private int mSubtypeIconResId = 0;

        /**
         * @param subtypeNameResId is the resource ID of the subtype name string.
         * The string resource may have exactly one %s in it. If present,
         * the %s part will be replaced with the locale's display name by
         * the formatter. Please refer to {@link #getDisplayName} for details.
         */
        public InputMethodSubtypeBuilder setSubtypeNameResId(int subtypeNameResId) {
            mSubtypeNameResId = subtypeNameResId;
            return this;
        }
        private int mSubtypeNameResId = 0;

        /**
         * Sets the untranslatable name of the subtype.
         *
         * This string is used as the subtype's display name if subtype's name res Id is 0.
         *
         * @param nameOverride is the name to set.
         */
        @NonNull
        public InputMethodSubtypeBuilder setSubtypeNameOverride(
                @NonNull CharSequence nameOverride) {
            mSubtypeNameOverride = nameOverride;
            return this;
        }
        private CharSequence mSubtypeNameOverride = "";

        /**
         * Sets the physical keyboard hint information, such as language and layout.
         *
         * The system can use the hint information to automatically configure the physical keyboard
         * for the subtype.
         *
         * @param languageTag is the preferred physical keyboard BCP-47 language tag. This is used
         * to match the keyboardLocale attribute in the physical keyboard definition. If it's
         * {@code null}, the subtype's language tag will be used.
         * @param layoutType  is the preferred physical keyboard layout, which is used to match the
         * keyboardLayoutType attribute in the physical keyboard definition. See
         * {@link android.hardware.input.InputManager#ACTION_QUERY_KEYBOARD_LAYOUTS}.
         */
        @NonNull
        public InputMethodSubtypeBuilder setPhysicalKeyboardHint(@Nullable ULocale languageTag,
                @NonNull String layoutType) {
            Objects.requireNonNull(layoutType, "layoutType cannot be null");
            mPkLanguageTag = languageTag == null ? "" : languageTag.toLanguageTag();
            mPkLayoutType = layoutType;
            return this;
        }
        private String mPkLanguageTag = "";
        private String mPkLayoutType = "";

        /**
         * @param subtypeId is the unique ID for this subtype. The input method framework keeps
         * track of enabled subtypes by ID. When the IME package gets upgraded, enabled IDs will
         * stay enabled even if other attributes are different. If the ID is unspecified or 0,
         * Arrays.hashCode(new Object[] {locale, mode, extraValue,
         * isAuxiliary, overridesImplicitlyEnabledSubtype, isAsciiCapable}) will be used instead.
         */
        public InputMethodSubtypeBuilder setSubtypeId(int subtypeId) {
            mSubtypeId = subtypeId;
            return this;
        }
        private int mSubtypeId = SUBTYPE_ID_NONE;

        /**
         * @param subtypeLocale is the locale supported by this subtype.
         */
        public InputMethodSubtypeBuilder setSubtypeLocale(String subtypeLocale) {
            mSubtypeLocale = subtypeLocale == null ? "" : subtypeLocale;
            return this;
        }
        private String mSubtypeLocale = "";

        /**
         * @param languageTag is the BCP-47 Language Tag supported by this subtype.
         */
        public InputMethodSubtypeBuilder setLanguageTag(String languageTag) {
            mSubtypeLanguageTag = languageTag == null ? LANGUAGE_TAG_NONE : languageTag;
            return this;
        }
        private String mSubtypeLanguageTag = LANGUAGE_TAG_NONE;

        /**
         * @param subtypeMode is the mode supported by this subtype.
         */
        public InputMethodSubtypeBuilder setSubtypeMode(String subtypeMode) {
            mSubtypeMode = subtypeMode == null ? "" : subtypeMode;
            return this;
        }
        private String mSubtypeMode = "";
        /**
         * @param subtypeExtraValue is the extra value of the subtype. This string is free-form,
         * but the API supplies tools to deal with a key-value comma-separated list; see
         * {@link #containsExtraValueKey} and {@link #getExtraValueOf}.
         */
        public InputMethodSubtypeBuilder setSubtypeExtraValue(String subtypeExtraValue) {
            mSubtypeExtraValue = subtypeExtraValue == null ? "" : subtypeExtraValue;
            return this;
        }
        private String mSubtypeExtraValue = "";

        /**
         * @return InputMethodSubtype using parameters in this InputMethodSubtypeBuilder.
         */
        public InputMethodSubtype build() {
            return new InputMethodSubtype(this);
        }
    }

    private static InputMethodSubtypeBuilder getBuilder(int nameId, int iconId,
            String locale, String mode, String extraValue, boolean isAuxiliary,
            boolean overridesImplicitlyEnabledSubtype, int id, boolean isAsciiCapable) {
        final InputMethodSubtypeBuilder builder = new InputMethodSubtypeBuilder();
        builder.mSubtypeNameResId = nameId;
        builder.mSubtypeIconResId = iconId;
        builder.mSubtypeLocale = locale;
        builder.mSubtypeMode = mode;
        builder.mSubtypeExtraValue = extraValue;
        builder.mIsAuxiliary = isAuxiliary;
        builder.mOverridesImplicitlyEnabledSubtype = overridesImplicitlyEnabledSubtype;
        builder.mSubtypeId = id;
        builder.mIsAsciiCapable = isAsciiCapable;
        return builder;
    }

    /**
     * Constructor with no subtype ID specified.
     * @deprecated use {@link InputMethodSubtypeBuilder} instead.
     * Arguments for this constructor have the same meanings as
     * {@link InputMethodSubtype#InputMethodSubtype(int, int, String, String, String, boolean,
     * boolean, int)} except "id".
     */
    @Deprecated
    public InputMethodSubtype(int nameId, int iconId, String locale, String mode, String extraValue,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype) {
        this(nameId, iconId, locale, mode, extraValue, isAuxiliary,
                overridesImplicitlyEnabledSubtype, 0);
    }

    /**
     * Constructor.
     * @deprecated use {@link InputMethodSubtypeBuilder} instead.
     * "isAsciiCapable" is "false" in this constructor.
     * @param nameId Resource ID of the subtype name string. The string resource may have exactly
     * one %s in it. If there is, the %s part will be replaced with the locale's display name by
     * the formatter. Please refer to {@link #getDisplayName} for details.
     * @param iconId Resource ID of the subtype icon drawable.
     * @param locale The locale supported by the subtype
     * @param mode The mode supported by the subtype
     * @param extraValue The extra value of the subtype. This string is free-form, but the API
     * supplies tools to deal with a key-value comma-separated list; see
     * {@link #containsExtraValueKey} and {@link #getExtraValueOf}.
     * @param isAuxiliary true when this subtype is auxiliary, false otherwise. An auxiliary
     * subtype will not be shown in the list of enabled IMEs for choosing the current IME in
     * the Settings even when this subtype is enabled. Please note that this subtype will still
     * be shown in the list of IMEs in the IME switcher to allow the user to tentatively switch
     * to this subtype while an IME is shown. The framework will never switch the current IME to
     * this subtype by {@link android.view.inputmethod.InputMethodManager#switchToLastInputMethod}.
     * The intent of having this flag is to allow for IMEs that are invoked in a one-shot way as
     * auxiliary input mode, and return to the previous IME once it is finished (e.g. voice input).
     * @param overridesImplicitlyEnabledSubtype true when this subtype should be enabled by default
     * if no other subtypes in the IME are enabled explicitly. Note that a subtype with this
     * parameter being true will not be shown in the list of subtypes in each IME's subtype enabler.
     * Having an "automatic" subtype is an example use of this flag.
     * @param id The unique ID for the subtype. The input method framework keeps track of enabled
     * subtypes by ID. When the IME package gets upgraded, enabled IDs will stay enabled even if
     * other attributes are different. If the ID is unspecified or 0,
     * Arrays.hashCode(new Object[] {locale, mode, extraValue,
     * isAuxiliary, overridesImplicitlyEnabledSubtype, isAsciiCapable}) will be used instead.
     */
    @Deprecated
    public InputMethodSubtype(int nameId, int iconId, String locale, String mode, String extraValue,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype, int id) {
        this(getBuilder(nameId, iconId, locale, mode, extraValue, isAuxiliary,
                overridesImplicitlyEnabledSubtype, id, false));
    }

    /**
     * Constructor.
     * @param builder Builder for InputMethodSubtype
     */
    private InputMethodSubtype(InputMethodSubtypeBuilder builder) {
        mSubtypeNameResId = builder.mSubtypeNameResId;
        mSubtypeNameOverride = builder.mSubtypeNameOverride;
        mPkLanguageTag = builder.mPkLanguageTag;
        mPkLayoutType = builder.mPkLayoutType;
        mSubtypeIconResId = builder.mSubtypeIconResId;
        mSubtypeLocale = builder.mSubtypeLocale;
        mSubtypeLanguageTag = builder.mSubtypeLanguageTag;
        mSubtypeMode = builder.mSubtypeMode;
        mSubtypeExtraValue = builder.mSubtypeExtraValue;
        mIsAuxiliary = builder.mIsAuxiliary;
        mOverridesImplicitlyEnabledSubtype = builder.mOverridesImplicitlyEnabledSubtype;
        mSubtypeId = builder.mSubtypeId;
        mIsAsciiCapable = builder.mIsAsciiCapable;
        // If hashCode() of this subtype is 0 and you want to specify it as an id of this subtype,
        // just specify 0 as this subtype's id. Then, this subtype's id is treated as 0.
        if (mSubtypeId != SUBTYPE_ID_NONE) {
            mSubtypeHashCode = mSubtypeId;
        } else {
            mSubtypeHashCode = hashCodeInternal(mSubtypeLocale, mSubtypeMode, mSubtypeExtraValue,
                    mIsAuxiliary, mOverridesImplicitlyEnabledSubtype, mIsAsciiCapable);
        }
    }

    InputMethodSubtype(Parcel source) {
        String s;
        mSubtypeNameResId = source.readInt();
        CharSequence cs = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        mSubtypeNameOverride = cs != null ? cs : "";
        s = source.readString8();
        mPkLanguageTag = s != null ? s : "";
        s = source.readString8();
        mPkLayoutType = s != null ? s : "";
        mSubtypeIconResId = source.readInt();
        s = source.readString();
        mSubtypeLocale = s != null ? s : "";
        s = source.readString();
        mSubtypeLanguageTag = s != null ? s : LANGUAGE_TAG_NONE;
        s = source.readString();
        mSubtypeMode = s != null ? s : "";
        s = source.readString();
        mSubtypeExtraValue = s != null ? s : "";
        mIsAuxiliary = (source.readInt() == 1);
        mOverridesImplicitlyEnabledSubtype = (source.readInt() == 1);
        mSubtypeHashCode = source.readInt();
        mSubtypeId = source.readInt();
        mIsAsciiCapable = (source.readInt() == 1);
    }

    /**
     * @return Resource ID of the subtype name string.
     */
    public int getNameResId() {
        return mSubtypeNameResId;
    }

    /**
     * @return The subtype's untranslatable name string.
     */
    @NonNull
    public CharSequence getNameOverride() {
        return mSubtypeNameOverride;
    }

    /**
     * Returns the physical keyboard BCP-47 language tag.
     *
     * @attr ref android.R.styleable#InputMethod_Subtype_physicalKeyboardHintLanguageTag
     * @see InputMethodSubtypeBuilder#setPhysicalKeyboardHint
     */
    @Nullable
    public ULocale getPhysicalKeyboardHintLanguageTag() {
        return TextUtils.isEmpty(mPkLanguageTag) ? null : ULocale.forLanguageTag(mPkLanguageTag);
    }

    /**
     * Returns the physical keyboard layout type string.
     *
     * @attr ref android.R.styleable#InputMethod_Subtype_physicalKeyboardHintLayoutType
     * @see InputMethodSubtypeBuilder#setPhysicalKeyboardHint
     */
    @NonNull
    public String getPhysicalKeyboardHintLayoutType() {
        return mPkLayoutType;
    }

    /**
     * @return Resource ID of the subtype icon drawable.
     */
    public int getIconResId() {
        return mSubtypeIconResId;
    }

    /**
     * @return The locale of the subtype. This method returns the "locale" string parameter passed
     * to the constructor.
     *
     * @deprecated Use {@link #getLanguageTag()} instead.
     */
    @Deprecated
    @NonNull
    public String getLocale() {
        return mSubtypeLocale;
    }

    /**
     * @return the BCP-47 Language Tag of the subtype.  Returns an empty string when no Language Tag
     * is specified.
     *
     * @see Locale#forLanguageTag(String)
     */
    @NonNull
    public String getLanguageTag() {
        return mSubtypeLanguageTag;
    }

    /**
     * @return {@link Locale} constructed from {@link #getLanguageTag()}. If the Language Tag is not
     * specified, then try to construct from {@link #getLocale()}
     *
     * <p>TODO: Consider to make this a public API, or move this to support lib.</p>
     * @hide
     */
    @Nullable
    public Locale getLocaleObject() {
        if (mCachedLocaleObj != null) {
            return mCachedLocaleObj;
        }
        synchronized (mLock) {
            if (mCachedLocaleObj != null) {
                return mCachedLocaleObj;
            }
            if (!TextUtils.isEmpty(mSubtypeLanguageTag)) {
                mCachedLocaleObj = Locale.forLanguageTag(mSubtypeLanguageTag);
            } else {
                mCachedLocaleObj = SubtypeLocaleUtils.constructLocaleFromString(mSubtypeLocale);
            }
            return mCachedLocaleObj;
        }
    }

    /**
     * Returns a canonicalized BCP 47 Language Tag initialized with {@link #getLocaleObject()}.
     *
     * <p>This has an internal cache mechanism.  Subsequent calls are in general cheap and fast.</p>
     *
     * @return a canonicalized BCP 47 Language Tag initialized with {@link #getLocaleObject()}. An
     *         empty string if {@link #getLocaleObject()} returns {@code null} or an empty
     *         {@link Locale} object.
     * @hide
     */
    @AnyThread
    @NonNull
    public String getCanonicalizedLanguageTag() {
        final String cachedValue = mCachedCanonicalizedLanguageTag;
        if (cachedValue != null) {
            return cachedValue;
        }

        String result = null;
        final Locale locale = getLocaleObject();
        if (locale != null) {
            final String langTag = locale.toLanguageTag();
            if (!TextUtils.isEmpty(langTag)) {
                result = ULocale.createCanonical(ULocale.forLanguageTag(langTag)).toLanguageTag();
            }
        }
        result = TextUtils.emptyIfNull(result);
        mCachedCanonicalizedLanguageTag = result;
        return result;
    }

    /**
     * Determines whether this {@link InputMethodSubtype} can be used as the key of mapping rules
     * between {@link InputMethodSubtype} and hardware keyboard layout.
     *
     * <p>Note that in a future build may require different rules.  Design the system so that the
     * system can automatically take care of any rule changes upon OTAs.</p>
     *
     * @return {@code true} if this {@link InputMethodSubtype} can be used as the key of mapping
     *         rules between {@link InputMethodSubtype} and hardware keyboard layout.
     * @hide
     */
    public boolean isSuitableForPhysicalKeyboardLayoutMapping() {
        if (hashCode() == SUBTYPE_ID_NONE) {
            return false;
        }
        if (!TextUtils.equals(getMode(), SUBTYPE_MODE_KEYBOARD)) {
            return false;
        }
        return !isAuxiliary();
    }

    /**
     * @return The mode of the subtype.
     */
    public String getMode() {
        return mSubtypeMode;
    }

    /**
     * @return The extra value of the subtype.
     */
    public String getExtraValue() {
        return mSubtypeExtraValue;
    }

    /**
     * @return true if this subtype is auxiliary, false otherwise. An auxiliary subtype will not be
     * shown in the list of enabled IMEs for choosing the current IME in the Settings even when this
     * subtype is enabled. Please note that this subtype will still be shown in the list of IMEs in
     * the IME switcher to allow the user to tentatively switch to this subtype while an IME is
     * shown. The framework will never switch the current IME to this subtype by
     * {@link android.view.inputmethod.InputMethodManager#switchToLastInputMethod}.
     * The intent of having this flag is to allow for IMEs that are invoked in a one-shot way as
     * auxiliary input mode, and return to the previous IME once it is finished (e.g. voice input).
     */
    public boolean isAuxiliary() {
        return mIsAuxiliary;
    }

    /**
     * @return true when this subtype will be enabled by default if no other subtypes in the IME
     * are enabled explicitly, false otherwise. Note that a subtype with this method returning true
     * will not be shown in the list of subtypes in each IME's subtype enabler. Having an
     * "automatic" subtype is an example use of this flag.
     */
    public boolean overridesImplicitlyEnabledSubtype() {
        return mOverridesImplicitlyEnabledSubtype;
    }

    /**
     * @return true if this subtype is Ascii capable, false otherwise. If the subtype is ASCII
     * capable, it should guarantee that the user can input ASCII characters with this subtype.
     * This is important because many password fields only allow ASCII-characters.
     */
    public boolean isAsciiCapable() {
        return mIsAsciiCapable;
    }

    /**
     * Returns a display name for this subtype.
     *
     * <p>If {@code subtypeNameResId} is specified (!= 0) text generated from that resource will
     * be returned. The localized string resource of the label should be capitalized for inclusion
     * in UI lists. The string resource may contain at most one {@code %s}. If present, the
     * {@code %s} will be replaced with the display name of the subtype locale in the user's locale.
     *
     * <p>If {@code subtypeNameResId} is not specified (== 0) the framework returns the display name
     * of the subtype locale, as capitalized for use in UI lists, in the user's locale.
     *
     * @param context {@link Context} will be used for getting {@link Locale} and
     * {@link android.content.pm.PackageManager}.
     * @param packageName The package name of the input method.
     * @param appInfo The {@link ApplicationInfo} of the input method.
     * @return a display name for this subtype.
     */
    @NonNull
    public CharSequence getDisplayName(
            Context context, String packageName, ApplicationInfo appInfo) {
        if (mSubtypeNameResId == 0) {
            return TextUtils.isEmpty(mSubtypeNameOverride)
                    ? getLocaleDisplayName(
                            getLocaleFromContext(context), getLocaleObject(),
                            DisplayContext.CAPITALIZATION_FOR_UI_LIST_OR_MENU)
                    : mSubtypeNameOverride;
        }

        final CharSequence subtypeName = context.getPackageManager().getText(
                packageName, mSubtypeNameResId, appInfo);
        if (TextUtils.isEmpty(subtypeName)) {
            return "";
        }
        final String subtypeNameString = subtypeName.toString();
        String replacementString;
        if (containsExtraValueKey(EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME)) {
            replacementString = getExtraValueOf(
                    EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME);
        } else {
            final DisplayContext displayContext;
            if (TextUtils.equals(subtypeNameString, "%s")) {
                displayContext = DisplayContext.CAPITALIZATION_FOR_UI_LIST_OR_MENU;
            } else if (subtypeNameString.startsWith("%s")) {
                displayContext = DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE;
            } else {
                displayContext = DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE;
            }
            replacementString = getLocaleDisplayName(getLocaleFromContext(context),
                    getLocaleObject(), displayContext);
        }
        if (replacementString == null) {
            replacementString = "";
        }
        try {
            return String.format(subtypeNameString, replacementString);
        } catch (IllegalFormatException e) {
            Slog.w(TAG, "Found illegal format in subtype name("+ subtypeName + "): " + e);
            return "";
        }
    }

    @Nullable
    private static Locale getLocaleFromContext(@Nullable final Context context) {
        if (context == null) {
            return null;
        }
        if (context.getResources() == null) {
            return null;
        }
        final Configuration configuration = context.getResources().getConfiguration();
        if (configuration == null) {
            return null;
        }
        return configuration.getLocales().get(0);
    }

    /**
     * @param displayLocale {@link Locale} to be used to display {@code localeToDisplay}
     * @param localeToDisplay {@link Locale} to be displayed in {@code displayLocale}
     * @param displayContext context parameter to be used to display {@code localeToDisplay} in
     * {@code displayLocale}
     * @return Returns the name of the {@code localeToDisplay} in the user's current locale.
     */
    @NonNull
    private static String getLocaleDisplayName(
            @Nullable Locale displayLocale, @Nullable Locale localeToDisplay,
            final DisplayContext displayContext) {
        if (localeToDisplay == null) {
            return "";
        }
        final Locale nonNullDisplayLocale =
                displayLocale != null ? displayLocale : Locale.getDefault();
        return LocaleDisplayNames
                .getInstance(nonNullDisplayLocale, displayContext)
                .localeDisplayName(localeToDisplay);
    }

    private HashMap<String, String> getExtraValueHashMap() {
        synchronized (this) {
            HashMap<String, String> extraValueMap = mExtraValueHashMapCache;
            if (extraValueMap != null) {
                return extraValueMap;
            }
            extraValueMap = new HashMap<>();
            final String[] pairs = mSubtypeExtraValue.split(EXTRA_VALUE_PAIR_SEPARATOR);
            for (int i = 0; i < pairs.length; ++i) {
                final String[] pair = pairs[i].split(EXTRA_VALUE_KEY_VALUE_SEPARATOR);
                if (pair.length == 1) {
                    extraValueMap.put(pair[0], null);
                } else if (pair.length > 1) {
                    if (pair.length > 2) {
                        Slog.w(TAG, "ExtraValue has two or more '='s");
                    }
                    extraValueMap.put(pair[0], pair[1]);
                }
            }
            mExtraValueHashMapCache = extraValueMap;
            return extraValueMap;
        }
    }

    /**
     * The string of ExtraValue in subtype should be defined as follows:
     * example: key0,key1=value1,key2,key3,key4=value4
     * @param key The key of extra value
     * @return The subtype contains specified the extra value
     */
    public boolean containsExtraValueKey(String key) {
        return getExtraValueHashMap().containsKey(key);
    }

    /**
     * The string of ExtraValue in subtype should be defined as follows:
     * example: key0,key1=value1,key2,key3,key4=value4
     * @param key The key of extra value
     * @return The value of the specified key
     */
    public String getExtraValueOf(String key) {
        return getExtraValueHashMap().get(key);
    }

    @Override
    public int hashCode() {
        return mSubtypeHashCode;
    }

    /**
     * @hide
     * @return {@code true} if a valid subtype ID exists.
     */
    public final boolean hasSubtypeId() {
        return mSubtypeId != SUBTYPE_ID_NONE;
    }

    /**
     * @hide
     * @return subtype ID. {@code 0} means that not subtype ID is specified.
     */
    public final int getSubtypeId() {
        return mSubtypeId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof InputMethodSubtype) {
            InputMethodSubtype subtype = (InputMethodSubtype) o;
            if (subtype.mSubtypeId != 0 || mSubtypeId != 0) {
                return (subtype.hashCode() == hashCode());
            }
            return (subtype.hashCode() == hashCode())
                    && (subtype.getLocale().equals(getLocale()))
                    && (subtype.getLanguageTag().equals(getLanguageTag()))
                    && (subtype.getMode().equals(getMode()))
                    && (subtype.getExtraValue().equals(getExtraValue()))
                    && (subtype.isAuxiliary() == isAuxiliary())
                    && (subtype.overridesImplicitlyEnabledSubtype()
                            == overridesImplicitlyEnabledSubtype())
                    && (subtype.isAsciiCapable() == isAsciiCapable());
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(mSubtypeNameResId);
        TextUtils.writeToParcel(mSubtypeNameOverride, dest, parcelableFlags);
        dest.writeString8(mPkLanguageTag);
        dest.writeString8(mPkLayoutType);
        dest.writeInt(mSubtypeIconResId);
        dest.writeString(mSubtypeLocale);
        dest.writeString(mSubtypeLanguageTag);
        dest.writeString(mSubtypeMode);
        dest.writeString(mSubtypeExtraValue);
        dest.writeInt(mIsAuxiliary ? 1 : 0);
        dest.writeInt(mOverridesImplicitlyEnabledSubtype ? 1 : 0);
        dest.writeInt(mSubtypeHashCode);
        dest.writeInt(mSubtypeId);
        dest.writeInt(mIsAsciiCapable ? 1 : 0);
    }

    void dump(@NonNull Printer pw, @NonNull String prefix) {
        pw.println(prefix + "mSubtypeNameOverride=" + mSubtypeNameOverride
                + " mPkLanguageTag=" + mPkLanguageTag
                + " mPkLayoutType=" + mPkLayoutType
                + " mSubtypeId=" + mSubtypeId
                + " mSubtypeLocale=" + mSubtypeLocale
                + " mSubtypeLanguageTag=" + mSubtypeLanguageTag
                + " mSubtypeMode=" + mSubtypeMode
                + " mIsAuxiliary=" + mIsAuxiliary
                + " mOverridesImplicitlyEnabledSubtype=" + mOverridesImplicitlyEnabledSubtype
                + " mIsAsciiCapable=" + mIsAsciiCapable
                + " mSubtypeHashCode=" + mSubtypeHashCode);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InputMethodSubtype> CREATOR
            = new Parcelable.Creator<InputMethodSubtype>() {
        @Override
        public InputMethodSubtype createFromParcel(Parcel source) {
            return new InputMethodSubtype(source);
        }

        @Override
        public InputMethodSubtype[] newArray(int size) {
            return new InputMethodSubtype[size];
        }
    };

    private static int hashCodeInternal(String locale, String mode, String extraValue,
            boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype,
            boolean isAsciiCapable) {
        // CAVEAT: Must revisit how to compute needsToCalculateCompatibleHashCode when a new
        // attribute is added in order to avoid enabled subtypes being unexpectedly disabled.
        final boolean needsToCalculateCompatibleHashCode = !isAsciiCapable;
        if (needsToCalculateCompatibleHashCode) {
            return Arrays.hashCode(new Object[] {locale, mode, extraValue, isAuxiliary,
                    overridesImplicitlyEnabledSubtype});
        }
        return Arrays.hashCode(new Object[] {locale, mode, extraValue, isAuxiliary,
                overridesImplicitlyEnabledSubtype, isAsciiCapable});
    }

    /**
     * Sort the list of InputMethodSubtype
     * @param imi InputMethodInfo of which subtypes are subject to be sorted
     * @param subtypeList List of InputMethodSubtype which will be sorted
     * @return Sorted list of subtypes
     * @hide
     */
    public static List<InputMethodSubtype> sort(InputMethodInfo imi,
            List<InputMethodSubtype> subtypeList) {
        if (imi == null) return subtypeList;
        final HashSet<InputMethodSubtype> inputSubtypesSet = new HashSet<InputMethodSubtype>(
                subtypeList);
        final ArrayList<InputMethodSubtype> sortedList = new ArrayList<InputMethodSubtype>();
        int N = imi.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (inputSubtypesSet.contains(subtype)) {
                sortedList.add(subtype);
                inputSubtypesSet.remove(subtype);
            }
        }
        // If subtypes in inputSubtypesSet remain, that means these subtypes are not
        // contained in imi, so the remaining subtypes will be appended.
        for (InputMethodSubtype subtype: inputSubtypesSet) {
            sortedList.add(subtype);
        }
        return sortedList;
    }
}
