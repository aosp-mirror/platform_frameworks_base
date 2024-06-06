/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.icu.util.ULocale;
import android.inputmethodservice.InputMethodService;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Printer;
import android.util.Slog;
import android.util.Xml;
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to specify meta information of an input method.
 *
 * <p>It should be defined in an XML resource file with an {@code <input-method>} element.
 * For more information, see the guide to
 * <a href="{@docRoot}guide/topics/text/creating-input-method.html">
 * Creating an Input Method</a>.</p>
 *
 * @see InputMethodSubtype
 *
 * @attr ref android.R.styleable#InputMethod_settingsActivity
 * @attr ref android.R.styleable#InputMethod_isDefault
 * @attr ref android.R.styleable#InputMethod_supportsSwitchingToNextInputMethod
 * @attr ref android.R.styleable#InputMethod_supportsInlineSuggestions
 * @attr ref android.R.styleable#InputMethod_supportsInlineSuggestionsWithTouchExploration
 * @attr ref android.R.styleable#InputMethod_suppressesSpellChecker
 * @attr ref android.R.styleable#InputMethod_showInInputMethodPicker
 * @attr ref android.R.styleable#InputMethod_configChanges
 */
public final class InputMethodInfo implements Parcelable {

    /**
     * {@link Intent#getAction() Intent action} for IME that
     * {@link #supportsStylusHandwriting() supports stylus handwriting}.
     *
     * @see #createStylusHandwritingSettingsActivityIntent()
     */
    public static final String ACTION_STYLUS_HANDWRITING_SETTINGS =
            "android.view.inputmethod.action.STYLUS_HANDWRITING_SETTINGS";

    /**
     * {@link Intent#getAction() Intent action} for the IME language settings.
     *
     * @see #createImeLanguageSettingsActivityIntent()
     */
    @FlaggedApi(android.view.inputmethod.Flags.FLAG_IME_SWITCHER_REVAMP)
    public static final String ACTION_IME_LANGUAGE_SETTINGS =
            "android.view.inputmethod.action.IME_LANGUAGE_SETTINGS";

    /**
     * Maximal length of a component name
     * @hide
     */
    @TestApi
    public static final int COMPONENT_NAME_MAX_LENGTH = 1000;

    /**
     * The maximum amount of IMEs that are loaded per package (in order).
     * If a package contains more IMEs, they will be ignored and cannot be enabled.
     * @hide
     */
    @TestApi
    @SuppressLint("MinMaxConstant")
    public static final int MAX_IMES_PER_PACKAGE = 20;

    static final String TAG = "InputMethodInfo";

    /**
     * The Service that implements this input method component.
     */
    final ResolveInfo mService;

    /**
     * IME only supports VR mode.
     */
    final boolean mIsVrOnly;

    /**
     * IME only supports virtual devices.
     */
    final boolean mIsVirtualDeviceOnly;

    /**
     * The unique string Id to identify the input method.  This is generated
     * from the input method component.
     */
    final String mId;

    /**
     * The input method setting activity's name, used by the system settings to
     * launch the setting activity of this input method.
     */
    final String mSettingsActivityName;

    /**
     * The input method language settings activity's name, used to
     * launch the language settings activity of this input method.
     */
    @Nullable
    private final String mLanguageSettingsActivityName;

    /**
     * The resource in the input method's .apk that holds a boolean indicating
     * whether it should be considered the default input method for this
     * system.  This is a resource ID instead of the final value so that it
     * can change based on the configuration (in particular locale).
     */
    final int mIsDefaultResId;

    /**
     * An array-like container of the subtypes.
     */
    @UnsupportedAppUsage
    private final InputMethodSubtypeArray mSubtypes;

    private final boolean mIsAuxIme;

    /**
     * Caveat: mForceDefault must be false for production. This flag is only for test.
     */
    private final boolean mForceDefault;

    /**
     * The flag whether this IME supports ways to switch to a next input method (e.g. globe key.)
     */
    private final boolean mSupportsSwitchingToNextInputMethod;

    /**
     * The flag whether this IME supports inline suggestions.
     */
    private final boolean mInlineSuggestionsEnabled;

    /**
     * The flag whether this IME supports inline suggestions when touch exploration is enabled.
     */
    private final boolean mSupportsInlineSuggestionsWithTouchExploration;

    /**
     * The flag whether this IME suppresses spell checker.
     */
    private final boolean mSuppressesSpellChecker;

    /**
     * The flag whether this IME should be shown as an option in the IME picker.
     */
    private final boolean mShowInInputMethodPicker;

    /**
     * The flag for configurations IME assumes the responsibility for handling in
     * {@link InputMethodService#onConfigurationChanged(Configuration)}}.
     */
    private final int mHandledConfigChanges;

    /**
     * The flag whether this IME supports Handwriting using stylus input.
     */
    private final boolean mSupportsStylusHandwriting;

    /** The flag whether this IME supports connectionless stylus handwriting sessions. */
    private final boolean mSupportsConnectionlessStylusHandwriting;

    /**
     * The stylus handwriting setting activity's name, used by the system settings to
     * launch the stylus handwriting specific setting activity of this input method.
     */
    private final String mStylusHandwritingSettingsActivityAttr;

    /**
     * @param service the {@link ResolveInfo} corresponds in which the IME is implemented.
     * @return a unique ID to be returned by {@link #getId()}. We have used
     *         {@link ComponentName#flattenToShortString()} for this purpose (and it is already
     *         unrealistic to switch to a different scheme as it is already implicitly assumed in
     *         many places).
     * @hide
     */
    public static String computeId(@NonNull ResolveInfo service) {
        final ServiceInfo si = service.serviceInfo;
        return new ComponentName(si.packageName, si.name).flattenToShortString();
    }

    /**
     * Constructor.
     *
     * @param context The Context in which we are parsing the input method.
     * @param service The ResolveInfo returned from the package manager about
     * this input method's component.
     */
    public InputMethodInfo(Context context, ResolveInfo service)
            throws XmlPullParserException, IOException {
        this(context, service, null);
    }

    /**
     * Constructor.
     *
     * @param context The Context in which we are parsing the input method.
     * @param service The ResolveInfo returned from the package manager about
     * this input method's component.
     * @param additionalSubtypes additional subtypes being added to this InputMethodInfo
     * @hide
     */
    public InputMethodInfo(Context context, ResolveInfo service,
            List<InputMethodSubtype> additionalSubtypes)
            throws XmlPullParserException, IOException {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        mId = computeId(service);
        boolean isAuxIme = true;
        boolean supportsSwitchingToNextInputMethod = false; // false as default
        boolean inlineSuggestionsEnabled = false; // false as default
        boolean supportsInlineSuggestionsWithTouchExploration = false; // false as default
        boolean suppressesSpellChecker = false; // false as default
        boolean showInInputMethodPicker = true; // true as default
        mForceDefault = false;

        PackageManager pm = context.getPackageManager();
        String settingsActivityComponent = null;
        String languageSettingsActivityComponent = null;
        String stylusHandwritingSettingsActivity = null;
        boolean isVrOnly;
        boolean isVirtualDeviceOnly;
        int isDefaultResId = 0;

        XmlResourceParser parser = null;
        final ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        try {
            parser = si.loadXmlMetaData(pm, InputMethod.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No "
                        + InputMethod.SERVICE_META_DATA + " meta-data");
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"input-method".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with input-method tag");
            }

            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.InputMethod);
            settingsActivityComponent = sa.getString(
                    com.android.internal.R.styleable.InputMethod_settingsActivity);
            if (Flags.imeSwitcherRevamp()) {
                languageSettingsActivityComponent = sa.getString(
                        com.android.internal.R.styleable.InputMethod_languageSettingsActivity);
            }
            if ((si.name != null && si.name.length() > COMPONENT_NAME_MAX_LENGTH)
                    || (settingsActivityComponent != null
                            && settingsActivityComponent.length()
                                > COMPONENT_NAME_MAX_LENGTH)
                    || (languageSettingsActivityComponent != null
                            && languageSettingsActivityComponent.length()
                                > COMPONENT_NAME_MAX_LENGTH)) {
                throw new XmlPullParserException(
                        "Activity name exceeds maximum of 1000 characters");
            }

            isVrOnly = sa.getBoolean(com.android.internal.R.styleable.InputMethod_isVrOnly, false);
            isVirtualDeviceOnly = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_isVirtualDeviceOnly, false);
            isDefaultResId = sa.getResourceId(
                    com.android.internal.R.styleable.InputMethod_isDefault, 0);
            supportsSwitchingToNextInputMethod = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_supportsSwitchingToNextInputMethod,
                    false);
            inlineSuggestionsEnabled = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_supportsInlineSuggestions, false);
            supportsInlineSuggestionsWithTouchExploration = sa.getBoolean(
                    com.android.internal.R.styleable
                            .InputMethod_supportsInlineSuggestionsWithTouchExploration, false);
            suppressesSpellChecker = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_suppressesSpellChecker, false);
            showInInputMethodPicker = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_showInInputMethodPicker, true);
            mHandledConfigChanges = sa.getInt(
                    com.android.internal.R.styleable.InputMethod_configChanges, 0);
            mSupportsStylusHandwriting = sa.getBoolean(
                    com.android.internal.R.styleable.InputMethod_supportsStylusHandwriting, false);
            mSupportsConnectionlessStylusHandwriting = sa.getBoolean(
                    com.android.internal.R.styleable
                            .InputMethod_supportsConnectionlessStylusHandwriting, false);
            stylusHandwritingSettingsActivity = sa.getString(
                    com.android.internal.R.styleable.InputMethod_stylusHandwritingSettingsActivity);
            sa.recycle();

            final int depth = parser.getDepth();
            // Parse all subtypes
            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    nodeName = parser.getName();
                    if (!"subtype".equals(nodeName)) {
                        throw new XmlPullParserException(
                                "Meta-data in input-method does not start with subtype tag");
                    }
                    final TypedArray a = res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.InputMethod_Subtype);
                    String pkLanguageTag = a.getString(com.android.internal.R.styleable
                            .InputMethod_Subtype_physicalKeyboardHintLanguageTag);
                    String pkLayoutType = a.getString(com.android.internal.R.styleable
                            .InputMethod_Subtype_physicalKeyboardHintLayoutType);
                    final InputMethodSubtype subtype = new InputMethodSubtypeBuilder()
                            .setSubtypeNameResId(a.getResourceId(com.android.internal.R.styleable
                                    .InputMethod_Subtype_label, 0))
                            .setSubtypeIconResId(a.getResourceId(com.android.internal.R.styleable
                                    .InputMethod_Subtype_icon, 0))
                            .setPhysicalKeyboardHint(
                                    pkLanguageTag == null ? null : new ULocale(pkLanguageTag),
                                    pkLayoutType == null ? "" : pkLayoutType)
                            .setLanguageTag(a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_languageTag))
                            .setSubtypeLocale(a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeLocale))
                            .setSubtypeMode(a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeMode))
                            .setSubtypeExtraValue(a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeExtraValue))
                            .setIsAuxiliary(a.getBoolean(com.android.internal.R.styleable
                                    .InputMethod_Subtype_isAuxiliary, false))
                            .setOverridesImplicitlyEnabledSubtype(a.getBoolean(
                                    com.android.internal.R.styleable
                                    .InputMethod_Subtype_overridesImplicitlyEnabledSubtype, false))
                            .setSubtypeId(a.getInt(com.android.internal.R.styleable
                                    .InputMethod_Subtype_subtypeId, 0 /* use Arrays.hashCode */))
                            .setIsAsciiCapable(a.getBoolean(com.android.internal.R.styleable
                                    .InputMethod_Subtype_isAsciiCapable, false)).build();
                    a.recycle();
                    if (!subtype.isAuxiliary()) {
                        isAuxIme = false;
                    }
                    subtypes.add(subtype);
                }
            }
        } catch (NameNotFoundException | IndexOutOfBoundsException | NumberFormatException e) {
            throw new XmlPullParserException(
                    "Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }

        if (subtypes.size() == 0) {
            isAuxIme = false;
        }

        if (additionalSubtypes != null) {
            final int N = additionalSubtypes.size();
            for (int i = 0; i < N; ++i) {
                final InputMethodSubtype subtype = additionalSubtypes.get(i);
                if (!subtypes.contains(subtype)) {
                    subtypes.add(subtype);
                } else {
                    Slog.w(TAG, "Duplicated subtype definition found: "
                            + subtype.getLocale() + ", " + subtype.getMode());
                }
            }
        }
        mSubtypes = new InputMethodSubtypeArray(subtypes);
        mSettingsActivityName = settingsActivityComponent;
        mLanguageSettingsActivityName = languageSettingsActivityComponent;
        mStylusHandwritingSettingsActivityAttr = stylusHandwritingSettingsActivity;
        mIsDefaultResId = isDefaultResId;
        mIsAuxIme = isAuxIme;
        mSupportsSwitchingToNextInputMethod = supportsSwitchingToNextInputMethod;
        mInlineSuggestionsEnabled = inlineSuggestionsEnabled;
        mSupportsInlineSuggestionsWithTouchExploration =
                supportsInlineSuggestionsWithTouchExploration;
        mSuppressesSpellChecker = suppressesSpellChecker;
        mShowInInputMethodPicker = showInInputMethodPicker;
        mIsVrOnly = isVrOnly;
        mIsVirtualDeviceOnly = isVirtualDeviceOnly;
    }

    /**
     * @hide
     */
    public InputMethodInfo(InputMethodInfo source) {
        mId = source.mId;
        mSettingsActivityName = source.mSettingsActivityName;
        mLanguageSettingsActivityName = source.mLanguageSettingsActivityName;
        mIsDefaultResId = source.mIsDefaultResId;
        mIsAuxIme = source.mIsAuxIme;
        mSupportsSwitchingToNextInputMethod = source.mSupportsSwitchingToNextInputMethod;
        mInlineSuggestionsEnabled = source.mInlineSuggestionsEnabled;
        mSupportsInlineSuggestionsWithTouchExploration =
                source.mSupportsInlineSuggestionsWithTouchExploration;
        mSuppressesSpellChecker = source.mSuppressesSpellChecker;
        mShowInInputMethodPicker = source.mShowInInputMethodPicker;
        mIsVrOnly = source.mIsVrOnly;
        mIsVirtualDeviceOnly = source.mIsVirtualDeviceOnly;
        mService = source.mService;
        mSubtypes = source.mSubtypes;
        mHandledConfigChanges = source.mHandledConfigChanges;
        mSupportsStylusHandwriting = source.mSupportsStylusHandwriting;
        mSupportsConnectionlessStylusHandwriting = source.mSupportsConnectionlessStylusHandwriting;
        mForceDefault = source.mForceDefault;
        mStylusHandwritingSettingsActivityAttr = source.mStylusHandwritingSettingsActivityAttr;
    }

    InputMethodInfo(Parcel source) {
        mId = source.readString();
        mSettingsActivityName = source.readString();
        mLanguageSettingsActivityName = source.readString8();
        mIsDefaultResId = source.readInt();
        mIsAuxIme = source.readInt() == 1;
        mSupportsSwitchingToNextInputMethod = source.readInt() == 1;
        mInlineSuggestionsEnabled = source.readInt() == 1;
        mSupportsInlineSuggestionsWithTouchExploration = source.readInt() == 1;
        mSuppressesSpellChecker = source.readBoolean();
        mShowInInputMethodPicker = source.readBoolean();
        mIsVrOnly = source.readBoolean();
        mIsVirtualDeviceOnly = source.readBoolean();
        mService = ResolveInfo.CREATOR.createFromParcel(source);
        mSubtypes = new InputMethodSubtypeArray(source);
        mHandledConfigChanges = source.readInt();
        mSupportsStylusHandwriting = source.readBoolean();
        mSupportsConnectionlessStylusHandwriting = source.readBoolean();
        mStylusHandwritingSettingsActivityAttr = source.readString8();
        mForceDefault = false;
    }

    /**
     * Temporary API for creating a built-in input method for test.
     */
    public InputMethodInfo(String packageName, String className,
            CharSequence label, String settingsActivity) {
        this(buildFakeResolveInfo(packageName, className, label), false /* isAuxIme */,
                settingsActivity, null /* languageSettingsActivity */, null /* subtypes */,
                0 /* isDefaultResId */, false /* forceDefault */,
                true /* supportsSwitchingToNextInputMethod */,
                false /* inlineSuggestionsEnabled */, false /* isVrOnly */,
                false /* isVirtualDeviceOnly */, 0 /* handledConfigChanges */,
                false /* supportsStylusHandwriting */,
                false /* supportConnectionlessStylusHandwriting */,
                null /* stylusHandwritingSettingsActivityAttr */,
                false /* inlineSuggestionsEnabled */);
    }

    /**
     * Test API for creating a built-in input method to verify stylus handwriting.
     * @hide
     */
    @TestApi
    public InputMethodInfo(@NonNull String packageName, @NonNull String className,
            @NonNull CharSequence label, @NonNull String settingsActivity,
            boolean supportStylusHandwriting,
            @NonNull String stylusHandwritingSettingsActivityAttr) {
        this(buildFakeResolveInfo(packageName, className, label), false /* isAuxIme */,
                settingsActivity, null /* languageSettingsActivity */,
                null /* subtypes */, 0 /* isDefaultResId */,
                false /* forceDefault */, true /* supportsSwitchingToNextInputMethod */,
                false /* inlineSuggestionsEnabled */, false /* isVrOnly */,
                false /* isVirtualDeviceOnly */, 0 /* handledConfigChanges */,
                supportStylusHandwriting, false /* supportConnectionlessStylusHandwriting */,
                stylusHandwritingSettingsActivityAttr, false /* inlineSuggestionsEnabled */);
    }

    /**
     * Test API for creating a built-in input method to verify stylus handwriting.
     * @hide
     */
    @TestApi
    public InputMethodInfo(@NonNull String packageName, @NonNull String className,
            @NonNull CharSequence label, @NonNull String settingsActivity,
            @NonNull String languageSettingsActivity, boolean supportStylusHandwriting,
            @NonNull String stylusHandwritingSettingsActivityAttr) {
        this(buildFakeResolveInfo(packageName, className, label), false /* isAuxIme */,
                settingsActivity, languageSettingsActivity, null /* subtypes */,
                0 /* isDefaultResId */, false /* forceDefault */,
                true /* supportsSwitchingToNextInputMethod */,
                false /* inlineSuggestionsEnabled */, false /* isVrOnly */,
                false /* isVirtualDeviceOnly */, 0 /* handledConfigChanges */,
                supportStylusHandwriting, false /* supportConnectionlessStylusHandwriting */,
                stylusHandwritingSettingsActivityAttr, false /* inlineSuggestionsEnabled */);
    }

    /**
     * Test API for creating a built-in input method to verify stylus handwriting.
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_CONNECTIONLESS_HANDWRITING)
    public InputMethodInfo(@NonNull String packageName, @NonNull String className,
            @NonNull CharSequence label, @NonNull String settingsActivity,
            @NonNull String languageSettingsActivity, boolean supportStylusHandwriting,
            boolean supportConnectionlessStylusHandwriting,
            @NonNull String stylusHandwritingSettingsActivityAttr) {
        this(buildFakeResolveInfo(packageName, className, label), false /* isAuxIme */,
                settingsActivity, languageSettingsActivity, null /* subtypes */,
                0 /* isDefaultResId */, false /* forceDefault */,
                true /* supportsSwitchingToNextInputMethod */,
                false /* inlineSuggestionsEnabled */, false /* isVrOnly */,
                false /* isVirtualDeviceOnly */, 0 /* handledConfigChanges */,
                supportStylusHandwriting, supportConnectionlessStylusHandwriting,
                stylusHandwritingSettingsActivityAttr, false /* inlineSuggestionsEnabled */);
    }

    /**
     * Temporary API for creating a built-in input method for test.
     * @hide
     */
    @TestApi
    public InputMethodInfo(@NonNull String packageName, @NonNull String className,
            @NonNull CharSequence label, @NonNull String settingsActivity,
            int handledConfigChanges) {
        this(buildFakeResolveInfo(packageName, className, label), false /* isAuxIme */,
                settingsActivity, null /* languageSettingsActivity */, null /* subtypes */,
                0 /* isDefaultResId */, false /* forceDefault */,
                true /* supportsSwitchingToNextInputMethod */,
                false /* inlineSuggestionsEnabled */, false /* isVrOnly */,
                false /* isVirtualDeviceOnly */, handledConfigChanges,
                false /* supportsStylusHandwriting */,
                false /* supportConnectionlessStylusHandwriting */,
                null /* stylusHandwritingSettingsActivityAttr */,
                false /* inlineSuggestionsEnabled */);
    }

    /**
     * Temporary API for creating a built-in input method for test.
     * @hide
     */
    public InputMethodInfo(ResolveInfo ri, boolean isAuxIme,
            String settingsActivity, List<InputMethodSubtype> subtypes, int isDefaultResId,
            boolean forceDefault) {
        this(ri, isAuxIme, settingsActivity, null /* languageSettingsActivity */, subtypes,
                isDefaultResId, forceDefault,
                true /* supportsSwitchingToNextInputMethod */, false /* inlineSuggestionsEnabled */,
                false /* isVrOnly */, false /* isVirtualDeviceOnly */, 0 /* handledconfigChanges */,
                false /* supportsStylusHandwriting */,
                false /* supportConnectionlessStylusHandwriting */,
                null /* stylusHandwritingSettingsActivityAttr */,
                false /* inlineSuggestionsEnabled */);
    }

    /**
     * Temporary API for creating a built-in input method for test.
     * @hide
     */
    public InputMethodInfo(ResolveInfo ri, boolean isAuxIme, String settingsActivity,
            List<InputMethodSubtype> subtypes, int isDefaultResId, boolean forceDefault,
            boolean supportsSwitchingToNextInputMethod, boolean isVrOnly) {
        this(ri, isAuxIme, settingsActivity, null /* languageSettingsActivity */, subtypes,
                isDefaultResId, forceDefault,
                supportsSwitchingToNextInputMethod, false /* inlineSuggestionsEnabled */, isVrOnly,
                false /* isVirtualDeviceOnly */,
                0 /* handledConfigChanges */, false /* supportsStylusHandwriting */,
                false /* supportConnectionlessStylusHandwriting */,
                null /* stylusHandwritingSettingsActivityAttr */,
                false /* inlineSuggestionsEnabled */);
    }

    /**
     * Temporary API for creating a built-in input method for test.
     * @hide
     */
    public InputMethodInfo(ResolveInfo ri, boolean isAuxIme, String settingsActivity,
            @Nullable String languageSettingsActivity, List<InputMethodSubtype> subtypes,
            int isDefaultResId, boolean forceDefault,
            boolean supportsSwitchingToNextInputMethod, boolean inlineSuggestionsEnabled,
            boolean isVrOnly, boolean isVirtualDeviceOnly, int handledConfigChanges,
            boolean supportsStylusHandwriting, boolean supportsConnectionlessStylusHandwriting,
            String stylusHandwritingSettingsActivityAttr,
            boolean supportsInlineSuggestionsWithTouchExploration) {
        final ServiceInfo si = ri.serviceInfo;
        mService = ri;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();
        mSettingsActivityName = settingsActivity;
        mLanguageSettingsActivityName = languageSettingsActivity;
        mIsDefaultResId = isDefaultResId;
        mIsAuxIme = isAuxIme;
        mSubtypes = new InputMethodSubtypeArray(subtypes);
        mForceDefault = forceDefault;
        mSupportsSwitchingToNextInputMethod = supportsSwitchingToNextInputMethod;
        mInlineSuggestionsEnabled = inlineSuggestionsEnabled;
        mSupportsInlineSuggestionsWithTouchExploration =
                supportsInlineSuggestionsWithTouchExploration;
        mSuppressesSpellChecker = false;
        mShowInInputMethodPicker = true;
        mIsVrOnly = isVrOnly;
        mIsVirtualDeviceOnly = isVirtualDeviceOnly;
        mHandledConfigChanges = handledConfigChanges;
        mSupportsStylusHandwriting = supportsStylusHandwriting;
        mSupportsConnectionlessStylusHandwriting = supportsConnectionlessStylusHandwriting;
        mStylusHandwritingSettingsActivityAttr = stylusHandwritingSettingsActivityAttr;
    }

    private static ResolveInfo buildFakeResolveInfo(String packageName, String className,
            CharSequence label) {
        ResolveInfo ri = new ResolveInfo();
        ServiceInfo si = new ServiceInfo();
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = className;
        si.exported = true;
        si.nonLocalizedLabel = label;
        ri.serviceInfo = si;
        return ri;
    }

    /**
     * @return a unique ID for this input method, which is guaranteed to be the same as the result
     *         of {@code getComponent().flattenToShortString()}.
     * @see ComponentName#unflattenFromString(String)
     */
    public String getId() {
        return mId;
    }

    /**
     * Return the .apk package that implements this input method.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
    }

    /**
     * Return the class name of the service component that implements
     * this input method.
     */
    public String getServiceName() {
        return mService.serviceInfo.name;
    }

    /**
     * Return the raw information about the Service implementing this
     * input method.  Do not modify the returned object.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Return the component of the service that implements this input
     * method.
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }

    /**
     * Load the user-displayed label for this input method.
     *
     * @param pm Supply a PackageManager used to load the input method's
     * resources.
     */
    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }

    /**
     * Load the user-displayed icon for this input method.
     *
     * @param pm Supply a PackageManager used to load the input method's
     * resources.
     */
    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }

    /**
     * Return the class name of an activity that provides a settings UI for
     * the input method.  You can launch this activity be starting it with
     * an {@link android.content.Intent} whose action is MAIN and with an
     * explicit {@link android.content.ComponentName}
     * composed of {@link #getPackageName} and the class name returned here.
     *
     * <p>A null will be returned if there is no settings activity associated
     * with the input method.</p>
     * @see #createStylusHandwritingSettingsActivityIntent()
     */
    public String getSettingsActivity() {
        return mSettingsActivityName;
    }

    /**
     * Returns true if IME supports VR mode only.
     * @hide
     */
    public boolean isVrOnly() {
        return mIsVrOnly;
    }

    /**
     * Returns true if IME supports only virtual devices.
     * @hide
     */
    @FlaggedApi(android.companion.virtual.flags.Flags.FLAG_VDM_CUSTOM_IME)
    @SystemApi
    public boolean isVirtualDeviceOnly() {
        return mIsVirtualDeviceOnly;
    }

    /**
     * Return the count of the subtypes of Input Method.
     */
    public int getSubtypeCount() {
        return mSubtypes.getCount();
    }

    /**
     * Return the Input Method's subtype at the specified index.
     *
     * @param index the index of the subtype to return.
     */
    public InputMethodSubtype getSubtypeAt(int index) {
        return mSubtypes.get(index);
    }

    /**
     * Return the resource identifier of a resource inside of this input
     * method's .apk that determines whether it should be considered a
     * default input method for the system.
     */
    public int getIsDefaultResourceId() {
        return mIsDefaultResId;
    }

    /**
     * Return whether or not this ime is a default ime or not.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isDefault(Context context) {
        if (mForceDefault) {
            return true;
        }
        try {
            if (getIsDefaultResourceId() == 0) {
                return false;
            }
            final Resources res = context.createPackageContext(getPackageName(), 0).getResources();
            return res.getBoolean(getIsDefaultResourceId());
        } catch (NameNotFoundException | NotFoundException e) {
            return false;
        }
    }

    /**
     * Returns the bit mask of kinds of configuration changes that this IME
     * can handle itself (without being restarted by the system).
     *
     * @attr ref android.R.styleable#InputMethod_configChanges
     */
    @ActivityInfo.Config
    public int getConfigChanges() {
        return mHandledConfigChanges;
    }

    /**
     * Returns if IME supports handwriting using stylus input.
     * @attr ref android.R.styleable#InputMethod_supportsStylusHandwriting
     * @see #createStylusHandwritingSettingsActivityIntent()
     */
    public boolean supportsStylusHandwriting() {
        return mSupportsStylusHandwriting;
    }

    /**
     * Returns whether the IME supports connectionless stylus handwriting sessions.
     *
     * @attr ref android.R.styleable#InputMethod_supportsConnectionlessStylusHandwriting
     */
    @FlaggedApi(Flags.FLAG_CONNECTIONLESS_HANDWRITING)
    public boolean supportsConnectionlessStylusHandwriting() {
        return mSupportsConnectionlessStylusHandwriting;
    }

    /**
     * Returns {@link Intent} for stylus handwriting settings activity with
     * {@link Intent#getAction() Intent action} {@link #ACTION_STYLUS_HANDWRITING_SETTINGS}
     * if IME {@link #supportsStylusHandwriting() supports stylus handwriting}, else
     * <code>null</code> if there are no associated settings for stylus handwriting / handwriting
     * is not supported or if
     * {@link android.R.styleable#InputMethod_stylusHandwritingSettingsActivity} is not defined.
     *
     * <p>To launch stylus settings, use this method to get the {@link android.content.Intent} to
     * launch the stylus handwriting settings activity.</p>
     * <p>e.g.<pre><code>startActivity(createStylusHandwritingSettingsActivityIntent());</code>
     * </pre></p>
     *
     * @attr ref R.styleable#InputMethod_stylusHandwritingSettingsActivity
     * @see #getSettingsActivity()
     * @see #supportsStylusHandwriting()
     */
    @Nullable
    public Intent createStylusHandwritingSettingsActivityIntent() {
        if (TextUtils.isEmpty(mStylusHandwritingSettingsActivityAttr)
                || !mSupportsStylusHandwriting) {
            return null;
        }
        // TODO(b/210039666): consider returning null if component is not enabled.
        return new Intent(ACTION_STYLUS_HANDWRITING_SETTINGS).setComponent(
                new ComponentName(getServiceInfo().packageName,
                        mStylusHandwritingSettingsActivityAttr));
    }

    /**
     * Returns {@link Intent} for IME language settings activity with
     * {@link Intent#getAction() Intent action} {@link #ACTION_IME_LANGUAGE_SETTINGS},
     * else <code>null</code> if
     * {@link android.R.styleable#InputMethod_languageSettingsActivity} is not defined.
     *
     * <p>To launch IME language settings, use this method to get the {@link Intent} to launch
     * the IME language settings activity.</p>
     * <p>e.g.<pre><code>startActivity(createImeLanguageSettingsActivityIntent());</code></pre></p>
     *
     * @attr ref R.styleable#InputMethod_languageSettingsActivity
     */
    @FlaggedApi(android.view.inputmethod.Flags.FLAG_IME_SWITCHER_REVAMP)
    @Nullable
    public Intent createImeLanguageSettingsActivityIntent() {
        if (TextUtils.isEmpty(mLanguageSettingsActivityName)) {
            return null;
        }
        return new Intent(ACTION_IME_LANGUAGE_SETTINGS).setComponent(
                new ComponentName(getServiceInfo().packageName,
                        mLanguageSettingsActivityName)
        );
    }

    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "mId=" + mId
                + " mSettingsActivityName=" + mSettingsActivityName
                + " mLanguageSettingsActivityName=" + mLanguageSettingsActivityName
                + " mIsVrOnly=" + mIsVrOnly
                + " mIsVirtualDeviceOnly=" + mIsVirtualDeviceOnly
                + " mSupportsSwitchingToNextInputMethod=" + mSupportsSwitchingToNextInputMethod
                + " mInlineSuggestionsEnabled=" + mInlineSuggestionsEnabled
                + " mSupportsInlineSuggestionsWithTouchExploration="
                + mSupportsInlineSuggestionsWithTouchExploration
                + " mSuppressesSpellChecker=" + mSuppressesSpellChecker
                + " mShowInInputMethodPicker=" + mShowInInputMethodPicker
                + " mSupportsStylusHandwriting=" + mSupportsStylusHandwriting
                + " mSupportsConnectionlessStylusHandwriting="
                + mSupportsConnectionlessStylusHandwriting
                + " mStylusHandwritingSettingsActivityAttr="
                        + mStylusHandwritingSettingsActivityAttr);
        pw.println(prefix + "mIsDefaultResId=0x"
                + Integer.toHexString(mIsDefaultResId));
        pw.println(prefix + "Service:");
        mService.dump(pw, prefix + "  ");
    }

    @Override
    public String toString() {
        return "InputMethodInfo{" + mId
                + ", settings: " + mSettingsActivityName
                + ", languageSettings: " + mLanguageSettingsActivityName
                + "}";
    }

    /**
     * Used to test whether the given parameter object is an
     * {@link InputMethodInfo} and its Id is the same to this one.
     *
     * @return true if the given parameter object is an
     *         {@link InputMethodInfo} and its Id is the same to this one.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) return true;
        if (o == null) return false;

        if (!(o instanceof InputMethodInfo)) return false;

        InputMethodInfo obj = (InputMethodInfo) o;
        return mId.equals(obj.mId);
    }

    @Override
    public int hashCode() {
        return mId.hashCode();
    }

    /**
     * @hide
     * @return {@code true} if the IME is a trusted system component (e.g. pre-installed)
     */
    public boolean isSystem() {
        return (mService.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * @hide
     */
    public boolean isAuxiliaryIme() {
        return mIsAuxIme;
    }

    /**
     * @return true if this input method supports ways to switch to a next input method.
     * @hide
     */
    public boolean supportsSwitchingToNextInputMethod() {
        return mSupportsSwitchingToNextInputMethod;
    }

    /**
     * @return true if this input method supports inline suggestions.
     * @hide
     */
    public boolean isInlineSuggestionsEnabled() {
        return mInlineSuggestionsEnabled;
    }

    /**
     * Returns {@code true} if this input method supports inline suggestions when touch exploration
     * is enabled.
     * @hide
     */
    public boolean supportsInlineSuggestionsWithTouchExploration() {
        return mSupportsInlineSuggestionsWithTouchExploration;
    }

    /**
     * Return {@code true} if this input method suppresses spell checker.
     */
    public boolean suppressesSpellChecker() {
        return mSuppressesSpellChecker;
    }

    /**
     * Returns {@code true} if this input method should be shown in menus for selecting an Input
     * Method, such as the system Input Method Picker. This is {@code false} if the IME is intended
     * to be accessed programmatically.
     */
    public boolean shouldShowInInputMethodPicker() {
        return mShowInInputMethodPicker;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mSettingsActivityName);
        dest.writeString8(mLanguageSettingsActivityName);
        dest.writeInt(mIsDefaultResId);
        dest.writeInt(mIsAuxIme ? 1 : 0);
        dest.writeInt(mSupportsSwitchingToNextInputMethod ? 1 : 0);
        dest.writeInt(mInlineSuggestionsEnabled ? 1 : 0);
        dest.writeInt(mSupportsInlineSuggestionsWithTouchExploration ? 1 : 0);
        dest.writeBoolean(mSuppressesSpellChecker);
        dest.writeBoolean(mShowInInputMethodPicker);
        dest.writeBoolean(mIsVrOnly);
        dest.writeBoolean(mIsVirtualDeviceOnly);
        mService.writeToParcel(dest, flags);
        mSubtypes.writeToParcel(dest);
        dest.writeInt(mHandledConfigChanges);
        dest.writeBoolean(mSupportsStylusHandwriting);
        dest.writeBoolean(mSupportsConnectionlessStylusHandwriting);
        dest.writeString8(mStylusHandwritingSettingsActivityAttr);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<InputMethodInfo> CREATOR
            = new Parcelable.Creator<InputMethodInfo>() {
        @Override
        public InputMethodInfo createFromParcel(Parcel source) {
            return new InputMethodInfo(source);
        }

        @Override
        public InputMethodInfo[] newArray(int size) {
            return new InputMethodInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
