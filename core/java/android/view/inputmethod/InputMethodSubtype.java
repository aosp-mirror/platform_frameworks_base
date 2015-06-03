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

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.inputmethod.InputMethodUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;

/**
 * This class is used to specify meta information of a subtype contained in an input method editor
 * (IME). Subtype can describe locale (e.g. en_US, fr_FR...) and mode (e.g. voice, keyboard...),
 * and is used for IME switch and settings. The input method subtype allows the system to bring up
 * the specified subtype of the designated IME directly.
 *
 * <p>It should be defined in an XML resource file of the input method with the
 * <code>&lt;subtype&gt;</code> element, which resides within an {@code &lt;input-method>} element.
 * For more information, see the guide to
 * <a href="{@docRoot}guide/topics/text/creating-input-method.html">
 * Creating an Input Method</a>.</p>
 *
 * @see InputMethodInfo
 *
 * @attr ref android.R.styleable#InputMethod_Subtype_label
 * @attr ref android.R.styleable#InputMethod_Subtype_icon
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
    private static final String EXTRA_VALUE_PAIR_SEPARATOR = ",";
    private static final String EXTRA_VALUE_KEY_VALUE_SEPARATOR = "=";
    // TODO: remove this
    private static final String EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME =
            "UntranslatableReplacementStringInSubtypeName";

    private final boolean mIsAuxiliary;
    private final boolean mOverridesImplicitlyEnabledSubtype;
    private final boolean mIsAsciiCapable;
    private final int mSubtypeHashCode;
    private final int mSubtypeIconResId;
    private final int mSubtypeNameResId;
    private final int mSubtypeId;
    private final String mSubtypeLocale;
    private final String mSubtypeMode;
    private final String mSubtypeExtraValue;
    private volatile HashMap<String, String> mExtraValueHashMapCache;

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
         * @param subtypeId is the unique ID for this subtype. The input method framework keeps
         * track of enabled subtypes by ID. When the IME package gets upgraded, enabled IDs will
         * stay enabled even if other attributes are different. If the ID is unspecified or 0,
         * Arrays.hashCode(new Object[] {locale, mode, extraValue,
         * isAuxiliary, overridesImplicitlyEnabledSubtype}) will be used instead.
         */
        public InputMethodSubtypeBuilder setSubtypeId(int subtypeId) {
            mSubtypeId = subtypeId;
            return this;
        }
        private int mSubtypeId = 0;

        /**
         * @param subtypeLocale is the locale supported by this subtype.
         */
        public InputMethodSubtypeBuilder setSubtypeLocale(String subtypeLocale) {
            mSubtypeLocale = subtypeLocale == null ? "" : subtypeLocale;
            return this;
        }
        private String mSubtypeLocale = "";

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

     private static InputMethodSubtypeBuilder getBuilder(int nameId, int iconId, String locale,
             String mode, String extraValue, boolean isAuxiliary,
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
     * isAuxiliary, overridesImplicitlyEnabledSubtype}) will be used instead.
     */
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
        mSubtypeIconResId = builder.mSubtypeIconResId;
        mSubtypeLocale = builder.mSubtypeLocale;
        mSubtypeMode = builder.mSubtypeMode;
        mSubtypeExtraValue = builder.mSubtypeExtraValue;
        mIsAuxiliary = builder.mIsAuxiliary;
        mOverridesImplicitlyEnabledSubtype = builder.mOverridesImplicitlyEnabledSubtype;
        mSubtypeId = builder.mSubtypeId;
        mIsAsciiCapable = builder.mIsAsciiCapable;
        // If hashCode() of this subtype is 0 and you want to specify it as an id of this subtype,
        // just specify 0 as this subtype's id. Then, this subtype's id is treated as 0.
        mSubtypeHashCode = mSubtypeId != 0 ? mSubtypeId : hashCodeInternal(mSubtypeLocale,
                mSubtypeMode, mSubtypeExtraValue, mIsAuxiliary, mOverridesImplicitlyEnabledSubtype,
                mIsAsciiCapable);
    }

    InputMethodSubtype(Parcel source) {
        String s;
        mSubtypeNameResId = source.readInt();
        mSubtypeIconResId = source.readInt();
        s = source.readString();
        mSubtypeLocale = s != null ? s : "";
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
     * @return Resource ID of the subtype icon drawable.
     */
    public int getIconResId() {
        return mSubtypeIconResId;
    }

    /**
     * @return The locale of the subtype. This method returns the "locale" string parameter passed
     * to the constructor.
     */
    public String getLocale() {
        return mSubtypeLocale;
    }

    /**
     * @return The normalized {@link Locale} object of the subtype. The returned locale may or may
     * not equal to "locale" string parameter passed to the constructor.
     *
     * <p>TODO: Consider to make this a public API.</p>
     * @hide
     */
    @Nullable
    public Locale getLocaleObject() {
        // TODO: Move the following method from InputMethodUtils to InputMethodSubtype.
        return InputMethodUtils.constructLocaleFromString(mSubtypeLocale);
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
     * @param context Context will be used for getting Locale and PackageManager.
     * @param packageName The package name of the IME
     * @param appInfo The application info of the IME
     * @return a display name for this subtype. The string resource of the label (mSubtypeNameResId)
     * may have exactly one %s in it. If there is, the %s part will be replaced with the locale's
     * display name by the formatter. If there is not, this method returns the string specified by
     * mSubtypeNameResId. If mSubtypeNameResId is not specified (== 0), it's up to the framework to
     * generate an appropriate display name.
     */
    public CharSequence getDisplayName(
            Context context, String packageName, ApplicationInfo appInfo) {
        final Locale locale = getLocaleObject();
        final String localeStr = locale != null ? locale.getDisplayName() : mSubtypeLocale;
        if (mSubtypeNameResId == 0) {
            return localeStr;
        }
        final CharSequence subtypeName = context.getPackageManager().getText(
                packageName, mSubtypeNameResId, appInfo);
        if (!TextUtils.isEmpty(subtypeName)) {
            final String replacementString =
                    containsExtraValueKey(EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME)
                            ? getExtraValueOf(EXTRA_KEY_UNTRANSLATABLE_STRING_IN_SUBTYPE_NAME)
                            : localeStr;
            try {
                return String.format(
                        subtypeName.toString(), replacementString != null ? replacementString : "");
            } catch (IllegalFormatException e) {
                Slog.w(TAG, "Found illegal format in subtype name("+ subtypeName + "): " + e);
                return "";
            }
        } else {
            return localeStr;
        }
    }

    private HashMap<String, String> getExtraValueHashMap() {
        if (mExtraValueHashMapCache == null) {
            synchronized(this) {
                if (mExtraValueHashMapCache == null) {
                    mExtraValueHashMapCache = new HashMap<String, String>();
                    final String[] pairs = mSubtypeExtraValue.split(EXTRA_VALUE_PAIR_SEPARATOR);
                    final int N = pairs.length;
                    for (int i = 0; i < N; ++i) {
                        final String[] pair = pairs[i].split(EXTRA_VALUE_KEY_VALUE_SEPARATOR);
                        if (pair.length == 1) {
                            mExtraValueHashMapCache.put(pair[0], null);
                        } else if (pair.length > 1) {
                            if (pair.length > 2) {
                                Slog.w(TAG, "ExtraValue has two or more '='s");
                            }
                            mExtraValueHashMapCache.put(pair[0], pair[1]);
                        }
                    }
                }
            }
        }
        return mExtraValueHashMapCache;
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

    @Override
    public boolean equals(Object o) {
        if (o instanceof InputMethodSubtype) {
            InputMethodSubtype subtype = (InputMethodSubtype) o;
            if (subtype.mSubtypeId != 0 || mSubtypeId != 0) {
                return (subtype.hashCode() == hashCode());
            }
            return (subtype.hashCode() == hashCode())
                && (subtype.getLocale().equals(getLocale()))
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
        dest.writeInt(mSubtypeIconResId);
        dest.writeString(mSubtypeLocale);
        dest.writeString(mSubtypeMode);
        dest.writeString(mSubtypeExtraValue);
        dest.writeInt(mIsAuxiliary ? 1 : 0);
        dest.writeInt(mOverridesImplicitlyEnabledSubtype ? 1 : 0);
        dest.writeInt(mSubtypeHashCode);
        dest.writeInt(mSubtypeId);
        dest.writeInt(mIsAsciiCapable ? 1 : 0);
    }

    public static final Parcelable.Creator<InputMethodSubtype> CREATOR
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
     * @param context Context will be used for getting localized strings from IME
     * @param flags Flags for the sort order
     * @param imi InputMethodInfo of which subtypes are subject to be sorted
     * @param subtypeList List of InputMethodSubtype which will be sorted
     * @return Sorted list of subtypes
     * @hide
     */
    public static List<InputMethodSubtype> sort(Context context, int flags, InputMethodInfo imi,
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
