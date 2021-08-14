/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.codegentest;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.StringDef;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.companion.ICompanionDeviceManager;
import android.content.pm.PackageManager;
import android.net.LinkAddress;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.DataClass;
import com.android.internal.util.DataClass.Each;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sample data class, showing off various code generation features.
 *
 * See javadoc on non-generated code for the explanation of the various features.
 *
 * See {@link SampleDataClassTest} for various invariants the generated code is expected to hold.
 */
@DataClass(
//        genParcelable = true, // implied by `implements Parcelable`
//        genAidl = true,       // implied by `implements Parcelable`
//        genGetters = true,    // on by default
//        genConstDefs = true,  // implied by presence of constants with common prefix
        genBuilder = true,      // on by default if optional fields present, but suppressed by
                                // genConstructor
        genConstructor = true,  // on by default but normally suppressed by genBuilder
        genEqualsHashCode = true,
        genToString = true,
        genForEachField = true,
        genSetters = true
)
public final class SampleDataClass implements Parcelable {

    /**
     * For any group of {@link int} or {@link String} constants like these, a corresponding
     * {@link IntDef}/{@link StringDef} will get generated, with name based on common prefix
     * by default.
     *
     * When {@link #SampleDataClass constructing} an instance, fields annotated with these
     * annotations get automatically validated, with only provided constants being a valid value.
     *
     * @see StateName, the generated {@link StringDef}
     * @see #mStateName annotated with {@link StateName}
     */
    public static final String STATE_NAME_UNDEFINED = "?";
    public static final String STATE_NAME_ON = "on";
    public static final String STATE_NAME_OFF = "off";

    /**
     * Additionally, for any generated {@link IntDef} a corresponding static
     * *ToString method will be also generated, and used in {@link #toString()}.
     *
     * @see #stateToString(int)
     * @see #toString()
     * @see State
     */
    public static final int STATE_ON = 1;
    public static final int STATE_OFF = 0;
    public static final int STATE_UNDEFINED
            = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;

    /**
     * {@link IntDef}s with values specified in hex("0x...") are considered to be
     * {@link IntDef#flag flags}, while ones specified with regular int literals are considered
     * not to be flags.
     *
     * This affects their string representation, e.g. see the difference in
     * {@link #requestFlagsToString} vs {@link #stateToString}.
     *
     * This also affects the validation logic when {@link #SampleDataClass constructing}
     * an instance, with any flag combination("|") being valid.
     *
     * You can customize the name of the generated {@link IntDef}/{@link StringDef} annotation
     * by annotating each constant with the desired name before running the generation.
     *
     * Here the annotation is named {@link RequestFlags} instead of the default {@code Flags}.
     */
    public static final @RequestFlags int FLAG_MANUAL_REQUEST = 0x1;
    public static final @RequestFlags int FLAG_COMPATIBILITY_MODE_REQUEST = 0x2;
    public static final @RequestFlags int FLAG_AUGMENTED_REQUEST = 0x80000000;


    /**
     * Any property javadoc should go onto the field, and will be copied where appropriate,
     * including getters, constructor parameters, builder setters, etc.
     *
     * <p>
     * This allows to avoid the burden of maintaining copies of the same documentation
     * pieces in multiple places for each field.
     */
    private int mNum;
    /**
     * Various javadoc features should work as expected when copied, e.g {@code code},
     * {@link #mName links}, <a href="https://google.com">html links</a>, etc.
     *
     * @see #mNum2 ..and so should blocks at the bottom, e.g. {@code @see} blocks.
     */
    private int mNum2;
    /**
     * {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
     * desired public API surface.
     *
     * @see #getNum4() is hidden
     * @see Builder#setNum4(int) also hidden
     * @hide
     */
    private int mNum4;

    /**
     * {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
     */
    private @Nullable String mName;
    /**
     * Fields with default value expressions ("mFoo = ...") are optional, and are automatically
     * initialized to the provided default expression, unless explicitly set.
     *
     * When using a {@link Builder} optional fields are passed via a {@link Builder#setName2 setter}
     * while mandatory fields are passed via {@link Builder#Builder constructor}.
     */
    private @NonNull String mName2 = "Bob";
    /**
     * Alternatively, when default value computation is expensive,
     * {@link #defaultName4 defaultFieldName()} can be defined to compute the default value.
     */
    private @NonNull String mName4;
    private static String defaultName4() {
        // Expensive computation
        return "Bob4";
    }

    /**
     * For parcelling, any field type supported by {@link Parcel} is supported out of the box.
     * E.g. {@link Parcelable} subclasses, {@link String}, {@link int}, {@link boolean}, etc.
     */
    private @Nullable AccessibilityNodeInfo mOtherParcelable = null;
    /**
     * Additionally, support for parcelling other types can be added by implementing a
     * {@link Parcelling}, and referencing it in the {@link DataClass.ParcelWith} field annotation.
     *
     * @see MyDateParcelling an example {@link Parcelling} implementation
     */
    @DataClass.ParcelWith(MyDateParcelling.class)
    private @NonNull Date mDate = new Date(42 * 42);
    /**
     * If a {@link Parcelling} is fairly common, consider putting it in {@link Parcelling.BuiltIn}
     * to encourage its reuse.
     */
    @DataClass.ParcelWith(Parcelling.BuiltIn.ForPattern.class)
    private @NonNull Pattern mPattern = Pattern.compile("");

    /**
     * For lists, when using a {@link Builder}, other than a regular
     * {@link Builder#setLinkAddresses2(List) setter}, and additional
     * {@link Builder#addLinkAddresses2(LinkAddress) add} method is generated for convenience.
     */
    private @NonNull List<LinkAddress> mLinkAddresses2 = new ArrayList<>();
    /**
     * For aesthetics, you may want to consider providing a singular version of the plural field
     * name, which would be used for the {@link #mLinkAddresses2 above mentioned} "add" method.
     *
     * @see Builder#addLinkAddress(LinkAddress)
     */
    @DataClass.PluralOf("linkAddress")
    private @NonNull ArrayList<LinkAddress> mLinkAddresses = new ArrayList<>();
    /**
     * For array fields, when using a {@link Builder}, vararg argument format is used for
     * convenience.
     *
     * @see Builder#setLinkAddresses4(LinkAddress...)
     */
    private @Nullable LinkAddress[] mLinkAddresses4 = null;

    /**
     * {@link IntDef}/{@link StringDef}-annotated fields propagate the annotation to
     * getter/constructor/setter/builder parameters, making for a nicer api.
     *
     * @see #getStateName
     * @see Builder#setStateName
     */
    private @StateName @NonNull String mStateName = STATE_NAME_UNDEFINED;
    /**
     * Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
     */
    private @RequestFlags int mFlags;
    /**
     * Above is true for both {@link IntDef#flag flags} and enum-like {@link IntDef}s
     */
    private @State int mState = STATE_UNDEFINED;


    /**
     * Making a field public will suppress getter generation in favor of accessing it directly.
     */
    public @NonNull CharSequence charSeq = "";
    /**
     * Final fields suppress generating a setter (when setters are requested).
     */
    private final @Nullable LinkAddress[] mLinkAddresses5;
    /**
     * Transient fields are completely ignored and can be used for caching.
     */
    private transient LinkAddress[] mLinkAddresses6;
    /**
     * When using transient fields for caching it's often also a good idea to initialize them
     * lazily.
     *
     * You can declare a special method like {@link #lazyInitTmpStorage()}, to let the
     * {@link #getTmpStorage getter} lazily-initialize the value on demand.
     */
    transient int[] mTmpStorage;
    private int[] lazyInitTmpStorage() {
        return new int[100];
    }

    /**
     * Fields with certain annotations are automatically validated in constructor
     *
     * You can see overloads in {@link AnnotationValidations} for a list of currently
     * supported ones.
     *
     * You can also extend support to your custom annotations by creating another corresponding
     * overloads like
     * {@link AnnotationValidations#validate(Class, UserIdInt, int)}.
     *
     * @see #SampleDataClass
     */
    private @StringRes int mStringRes = 0;
    /**
     * Validation annotations may also have parameters.
     *
     * Parameter values will be supplied to validation method as name-value pairs.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int, String, int)
     */
    private @android.annotation.IntRange(from = 0, to = 6) int mDayOfWeek = 3;
    /**
     * Unnamed validation annotation parameter gets supplied to the validating method named as
     * "value".
     *
     * Validation annotations following {@link Each} annotation, will be applied for each
     * array/collection element instead.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int)
     */
    @Size(2)
    @NonNull
    @Each @FloatRange(from = 0f)
    private float[] mCoords = new float[] {0f, 0f};


    /**
     * Binder types are also supported
     */
    private @NonNull IBinder mToken = new Binder();
    /**
     * AIDL interface types are also supported
     */
    private @Nullable ICompanionDeviceManager mIPCInterface = null;


    /**
     * Manually declaring any method that would otherwise be generated suppresses its generation,
     * allowing for fine-grained overrides of the generated behavior.
     */
    public LinkAddress[] getLinkAddresses4() {
        //Suppress autogen
        return null;
    }

    /**
     * Additionally, some methods like {@link #equals}, {@link #hashCode}, {@link #toString},
     * {@link #writeToParcel}, {@link Parcelable.Creator#createFromParcel} allow you to define
     * special methods to override their behavior on a per-field basis.
     *
     * See the generateted methods' descriptions for the detailed instructions of what the method
     * signatures for such methods are expected to be.
     *
     * Here we use this to "fix" {@link Pattern} not implementing equals/hashCode.
     *
     * @see #equals
     * @see #hashCode
     */
    private boolean patternEquals(Pattern other) {
        return Objects.equals(mPattern.pattern(), other.pattern());
    }
    private int patternHashCode() {
        return Objects.hashCode(mPattern.pattern());
    }

    /**
     * Similarly, {@link #onConstructed()}, if defined, gets called at the end of constructing an
     * instance.
     *
     * At this point all fields should be in place, so this is the right place to put any custom
     * validation logic.
     */
    private void onConstructed() {
        Preconditions.checkState(mNum2 == mNum4);
    }

    /**
     * {@link DataClass#genForEachField} can be used to generate a generic {@link #forEachField}
     * utility, which can be used for various use-cases not covered out of the box.
     * Callback passed to {@link #forEachField} will be called once per each property with its name
     * and value.
     *
     * Here for example it's used to implement a typical dump method.
     *
     * Note that there are 2 {@link #forEachField} versions provided, one that treats each field
     * value as an {@link Object}, thus boxing primitives if any, and one that additionally takes
     * specialized callbacks for particular primitive field types used in given class.
     *
     * Some primitives like {@link Boolean}s and {@link Integer}s within [-128, 127] don't allocate
     * when boxed, so it's up to you to decide which one to use for a given use-case.
     */
    public void dump(PrintWriter pw) {
        forEachField((self, name, value) -> {
            pw.append("  ").append(name).append(": ").append(String.valueOf(value)).append('\n');
        });
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/tests/Codegen/src/com/android/codegentest/SampleDataClass.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @IntDef(prefix = "STATE_", value = {
        STATE_ON,
        STATE_OFF,
        STATE_UNDEFINED
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface State {}

    @DataClass.Generated.Member
    public static String stateToString(@State int value) {
        switch (value) {
            case STATE_ON:
                    return "STATE_ON";
            case STATE_OFF:
                    return "STATE_OFF";
            case STATE_UNDEFINED:
                    return "STATE_UNDEFINED";
            default: return Integer.toHexString(value);
        }
    }

    @IntDef(flag = true, prefix = "FLAG_", value = {
        FLAG_MANUAL_REQUEST,
        FLAG_COMPATIBILITY_MODE_REQUEST,
        FLAG_AUGMENTED_REQUEST
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface RequestFlags {}

    @DataClass.Generated.Member
    public static String requestFlagsToString(@RequestFlags int value) {
        return com.android.internal.util.BitUtils.flagsToString(
                value, SampleDataClass::singleRequestFlagsToString);
    }

    @DataClass.Generated.Member
    static String singleRequestFlagsToString(@RequestFlags int value) {
        switch (value) {
            case FLAG_MANUAL_REQUEST:
                    return "FLAG_MANUAL_REQUEST";
            case FLAG_COMPATIBILITY_MODE_REQUEST:
                    return "FLAG_COMPATIBILITY_MODE_REQUEST";
            case FLAG_AUGMENTED_REQUEST:
                    return "FLAG_AUGMENTED_REQUEST";
            default: return Integer.toHexString(value);
        }
    }

    @StringDef(prefix = "STATE_NAME_", value = {
        STATE_NAME_UNDEFINED,
        STATE_NAME_ON,
        STATE_NAME_OFF
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface StateName {}

    /**
     * Creates a new SampleDataClass.
     *
     * @param num
     *   Any property javadoc should go onto the field, and will be copied where appropriate,
     *   including getters, constructor parameters, builder setters, etc.
     *
     *   <p>
     *   This allows to avoid the burden of maintaining copies of the same documentation
     *   pieces in multiple places for each field.
     * @param num2
     *   Various javadoc features should work as expected when copied, e.g {@code code},
     *   {@link #mName links}, <a href="https://google.com">html links</a>, etc.
     * @param num4
     *   {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
     *   desired public API surface.
     * @param name
     *   {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
     * @param name2
     *   Fields with default value expressions ("mFoo = ...") are optional, and are automatically
     *   initialized to the provided default expression, unless explicitly set.
     *
     *   When using a {@link Builder} optional fields are passed via a {@link Builder#setName2 setter}
     *   while mandatory fields are passed via {@link Builder#Builder constructor}.
     * @param name4
     *   Alternatively, when default value computation is expensive,
     *   {@link #defaultName4 defaultFieldName()} can be defined to compute the default value.
     * @param otherParcelable
     *   For parcelling, any field type supported by {@link Parcel} is supported out of the box.
     *   E.g. {@link Parcelable} subclasses, {@link String}, {@link int}, {@link boolean}, etc.
     * @param date
     *   Additionally, support for parcelling other types can be added by implementing a
     *   {@link Parcelling}, and referencing it in the {@link DataClass.ParcelWith} field annotation.
     * @param pattern
     *   If a {@link Parcelling} is fairly common, consider putting it in {@link Parcelling.BuiltIn}
     *   to encourage its reuse.
     * @param linkAddresses2
     *   For lists, when using a {@link Builder}, other than a regular
     *   {@link Builder#setLinkAddresses2(List) setter}, and additional
     *   {@link Builder#addLinkAddresses2(LinkAddress) add} method is generated for convenience.
     * @param linkAddresses
     *   For aesthetics, you may want to consider providing a singular version of the plural field
     *   name, which would be used for the {@link #mLinkAddresses2 above mentioned} "add" method.
     * @param linkAddresses4
     *   For array fields, when using a {@link Builder}, vararg argument format is used for
     *   convenience.
     * @param stateName
     *   {@link IntDef}/{@link StringDef}-annotated fields propagate the annotation to
     *   getter/constructor/setter/builder parameters, making for a nicer api.
     * @param flags
     *   Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
     * @param state
     *   Above is true for both {@link IntDef#flag flags} and enum-like {@link IntDef}s
     * @param charSeq
     *   Making a field public will suppress getter generation in favor of accessing it directly.
     * @param linkAddresses5
     *   Final fields suppress generating a setter (when setters are requested).
     * @param stringRes
     *   Fields with certain annotations are automatically validated in constructor
     *
     *   You can see overloads in {@link AnnotationValidations} for a list of currently
     *   supported ones.
     *
     *   You can also extend support to your custom annotations by creating another corresponding
     *   overloads like
     *   {@link AnnotationValidations#validate(Class, UserIdInt, int)}.
     * @param dayOfWeek
     *   Validation annotations may also have parameters.
     *
     *   Parameter values will be supplied to validation method as name-value pairs.
     * @param coords
     *   Unnamed validation annotation parameter gets supplied to the validating method named as
     *   "value".
     *
     *   Validation annotations following {@link Each} annotation, will be applied for each
     *   array/collection element instead.
     * @param token
     *   Binder types are also supported
     * @param iPCInterface
     *   AIDL interface types are also supported
     */
    @DataClass.Generated.Member
    public SampleDataClass(
            int num,
            int num2,
            int num4,
            @Nullable String name,
            @NonNull String name2,
            @NonNull String name4,
            @Nullable AccessibilityNodeInfo otherParcelable,
            @NonNull Date date,
            @NonNull Pattern pattern,
            @NonNull List<LinkAddress> linkAddresses2,
            @NonNull ArrayList<LinkAddress> linkAddresses,
            @Nullable LinkAddress[] linkAddresses4,
            @StateName @NonNull String stateName,
            @RequestFlags int flags,
            @State int state,
            @NonNull CharSequence charSeq,
            @Nullable LinkAddress[] linkAddresses5,
            @StringRes int stringRes,
            @android.annotation.IntRange(from = 0, to = 6) int dayOfWeek,
            @Size(2) @NonNull @FloatRange(from = 0f) float[] coords,
            @NonNull IBinder token,
            @Nullable ICompanionDeviceManager iPCInterface) {
        this.mNum = num;
        this.mNum2 = num2;
        this.mNum4 = num4;
        this.mName = name;
        this.mName2 = name2;
        AnnotationValidations.validate(
                NonNull.class, null, mName2);
        this.mName4 = name4;
        AnnotationValidations.validate(
                NonNull.class, null, mName4);
        this.mOtherParcelable = otherParcelable;
        this.mDate = date;
        AnnotationValidations.validate(
                NonNull.class, null, mDate);
        this.mPattern = pattern;
        AnnotationValidations.validate(
                NonNull.class, null, mPattern);
        this.mLinkAddresses2 = linkAddresses2;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses2);
        this.mLinkAddresses = linkAddresses;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses);
        this.mLinkAddresses4 = linkAddresses4;
        this.mStateName = stateName;

        if (!(Objects.equals(mStateName, STATE_NAME_UNDEFINED))
                && !(Objects.equals(mStateName, STATE_NAME_ON))
                && !(Objects.equals(mStateName, STATE_NAME_OFF))) {
            throw new java.lang.IllegalArgumentException(
                    "stateName was " + mStateName + " but must be one of: "
                            + "STATE_NAME_UNDEFINED(" + STATE_NAME_UNDEFINED + "), "
                            + "STATE_NAME_ON(" + STATE_NAME_ON + "), "
                            + "STATE_NAME_OFF(" + STATE_NAME_OFF + ")");
        }

        AnnotationValidations.validate(
                NonNull.class, null, mStateName);
        this.mFlags = flags;

        Preconditions.checkFlagsArgument(
                mFlags,
                FLAG_MANUAL_REQUEST
                        | FLAG_COMPATIBILITY_MODE_REQUEST
                        | FLAG_AUGMENTED_REQUEST);
        this.mState = state;

        if (!(mState == STATE_ON)
                && !(mState == STATE_OFF)
                && !(mState == STATE_UNDEFINED)) {
            throw new java.lang.IllegalArgumentException(
                    "state was " + mState + " but must be one of: "
                            + "STATE_ON(" + STATE_ON + "), "
                            + "STATE_OFF(" + STATE_OFF + "), "
                            + "STATE_UNDEFINED(" + STATE_UNDEFINED + ")");
        }

        this.charSeq = charSeq;
        AnnotationValidations.validate(
                NonNull.class, null, charSeq);
        this.mLinkAddresses5 = linkAddresses5;
        this.mStringRes = stringRes;
        AnnotationValidations.validate(
                StringRes.class, null, mStringRes);
        this.mDayOfWeek = dayOfWeek;
        AnnotationValidations.validate(
                android.annotation.IntRange.class, null, mDayOfWeek,
                "from", 0,
                "to", 6);
        this.mCoords = coords;
        AnnotationValidations.validate(
                Size.class, null, mCoords.length,
                "value", 2);
        AnnotationValidations.validate(
                NonNull.class, null, mCoords);
        int coordsSize = mCoords.length;
        for (int i = 0; i < coordsSize; i++) {
            AnnotationValidations.validate(
                    FloatRange.class, null, mCoords[i],
                    "from", 0f);
        }

        this.mToken = token;
        AnnotationValidations.validate(
                NonNull.class, null, mToken);
        this.mIPCInterface = iPCInterface;

        onConstructed();
    }

    /**
     * Any property javadoc should go onto the field, and will be copied where appropriate,
     * including getters, constructor parameters, builder setters, etc.
     *
     * <p>
     * This allows to avoid the burden of maintaining copies of the same documentation
     * pieces in multiple places for each field.
     */
    @DataClass.Generated.Member
    public int getNum() {
        return mNum;
    }

    /**
     * Various javadoc features should work as expected when copied, e.g {@code code},
     * {@link #mName links}, <a href="https://google.com">html links</a>, etc.
     *
     * @see #mNum2 ..and so should blocks at the bottom, e.g. {@code @see} blocks.
     */
    @DataClass.Generated.Member
    public int getNum2() {
        return mNum2;
    }

    /**
     * {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
     * desired public API surface.
     *
     * @see #getNum4() is hidden
     * @see Builder#setNum4(int) also hidden
     * @hide
     */
    @DataClass.Generated.Member
    public int getNum4() {
        return mNum4;
    }

    /**
     * {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
     */
    @DataClass.Generated.Member
    public @Nullable String getName() {
        return mName;
    }

    /**
     * Fields with default value expressions ("mFoo = ...") are optional, and are automatically
     * initialized to the provided default expression, unless explicitly set.
     *
     * When using a {@link Builder} optional fields are passed via a {@link Builder#setName2 setter}
     * while mandatory fields are passed via {@link Builder#Builder constructor}.
     */
    @DataClass.Generated.Member
    public @NonNull String getName2() {
        return mName2;
    }

    /**
     * Alternatively, when default value computation is expensive,
     * {@link #defaultName4 defaultFieldName()} can be defined to compute the default value.
     */
    @DataClass.Generated.Member
    public @NonNull String getName4() {
        return mName4;
    }

    /**
     * For parcelling, any field type supported by {@link Parcel} is supported out of the box.
     * E.g. {@link Parcelable} subclasses, {@link String}, {@link int}, {@link boolean}, etc.
     */
    @DataClass.Generated.Member
    public @Nullable AccessibilityNodeInfo getOtherParcelable() {
        return mOtherParcelable;
    }

    /**
     * Additionally, support for parcelling other types can be added by implementing a
     * {@link Parcelling}, and referencing it in the {@link DataClass.ParcelWith} field annotation.
     *
     * @see MyDateParcelling an example {@link Parcelling} implementation
     */
    @DataClass.Generated.Member
    public @NonNull Date getDate() {
        return mDate;
    }

    /**
     * If a {@link Parcelling} is fairly common, consider putting it in {@link Parcelling.BuiltIn}
     * to encourage its reuse.
     */
    @DataClass.Generated.Member
    public @NonNull Pattern getPattern() {
        return mPattern;
    }

    /**
     * For lists, when using a {@link Builder}, other than a regular
     * {@link Builder#setLinkAddresses2(List) setter}, and additional
     * {@link Builder#addLinkAddresses2(LinkAddress) add} method is generated for convenience.
     */
    @DataClass.Generated.Member
    public @NonNull List<LinkAddress> getLinkAddresses2() {
        return mLinkAddresses2;
    }

    /**
     * For aesthetics, you may want to consider providing a singular version of the plural field
     * name, which would be used for the {@link #mLinkAddresses2 above mentioned} "add" method.
     *
     * @see Builder#addLinkAddress(LinkAddress)
     */
    @DataClass.Generated.Member
    public @NonNull ArrayList<LinkAddress> getLinkAddresses() {
        return mLinkAddresses;
    }

    /**
     * {@link IntDef}/{@link StringDef}-annotated fields propagate the annotation to
     * getter/constructor/setter/builder parameters, making for a nicer api.
     *
     * @see #getStateName
     * @see Builder#setStateName
     */
    @DataClass.Generated.Member
    public @StateName @NonNull String getStateName() {
        return mStateName;
    }

    /**
     * Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
     */
    @DataClass.Generated.Member
    public @RequestFlags int getFlags() {
        return mFlags;
    }

    /**
     * Above is true for both {@link IntDef#flag flags} and enum-like {@link IntDef}s
     */
    @DataClass.Generated.Member
    public @State int getState() {
        return mState;
    }

    /**
     * Final fields suppress generating a setter (when setters are requested).
     */
    @DataClass.Generated.Member
    public @Nullable LinkAddress[] getLinkAddresses5() {
        return mLinkAddresses5;
    }

    /**
     * Fields with certain annotations are automatically validated in constructor
     *
     * You can see overloads in {@link AnnotationValidations} for a list of currently
     * supported ones.
     *
     * You can also extend support to your custom annotations by creating another corresponding
     * overloads like
     * {@link AnnotationValidations#validate(Class, UserIdInt, int)}.
     *
     * @see #SampleDataClass
     */
    @DataClass.Generated.Member
    public @StringRes int getStringRes() {
        return mStringRes;
    }

    /**
     * Validation annotations may also have parameters.
     *
     * Parameter values will be supplied to validation method as name-value pairs.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int, String, int)
     */
    @DataClass.Generated.Member
    public @android.annotation.IntRange(from = 0, to = 6) int getDayOfWeek() {
        return mDayOfWeek;
    }

    /**
     * Unnamed validation annotation parameter gets supplied to the validating method named as
     * "value".
     *
     * Validation annotations following {@link Each} annotation, will be applied for each
     * array/collection element instead.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int)
     */
    @DataClass.Generated.Member
    public @Size(2) @NonNull @FloatRange(from = 0f) float[] getCoords() {
        return mCoords;
    }

    /**
     * Binder types are also supported
     */
    @DataClass.Generated.Member
    public @NonNull IBinder getToken() {
        return mToken;
    }

    /**
     * AIDL interface types are also supported
     */
    @DataClass.Generated.Member
    public @Nullable ICompanionDeviceManager getIPCInterface() {
        return mIPCInterface;
    }

    /**
     * When using transient fields for caching it's often also a good idea to initialize them
     * lazily.
     *
     * You can declare a special method like {@link #lazyInitTmpStorage()}, to let the
     * {@link #getTmpStorage getter} lazily-initialize the value on demand.
     */
    @DataClass.Generated.Member
    public int[] getTmpStorage() {
        int[] tmpStorage = mTmpStorage;
        if (tmpStorage == null) {
            // You can mark field as volatile for thread-safe double-check init
            tmpStorage = mTmpStorage = lazyInitTmpStorage();
        }
        return tmpStorage;
    }

    /**
     * Any property javadoc should go onto the field, and will be copied where appropriate,
     * including getters, constructor parameters, builder setters, etc.
     *
     * <p>
     * This allows to avoid the burden of maintaining copies of the same documentation
     * pieces in multiple places for each field.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setNum( int value) {
        mNum = value;
        return this;
    }

    /**
     * Various javadoc features should work as expected when copied, e.g {@code code},
     * {@link #mName links}, <a href="https://google.com">html links</a>, etc.
     *
     * @see #mNum2 ..and so should blocks at the bottom, e.g. {@code @see} blocks.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setNum2( int value) {
        mNum2 = value;
        return this;
    }

    /**
     * {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
     * desired public API surface.
     *
     * @see #getNum4() is hidden
     * @see Builder#setNum4(int) also hidden
     * @hide
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setNum4( int value) {
        mNum4 = value;
        return this;
    }

    /**
     * {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setName(@NonNull String value) {
        mName = value;
        return this;
    }

    /**
     * Fields with default value expressions ("mFoo = ...") are optional, and are automatically
     * initialized to the provided default expression, unless explicitly set.
     *
     * When using a {@link Builder} optional fields are passed via a {@link Builder#setName2 setter}
     * while mandatory fields are passed via {@link Builder#Builder constructor}.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setName2(@NonNull String value) {
        mName2 = value;
        AnnotationValidations.validate(
                NonNull.class, null, mName2);
        return this;
    }

    /**
     * Alternatively, when default value computation is expensive,
     * {@link #defaultName4 defaultFieldName()} can be defined to compute the default value.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setName4(@NonNull String value) {
        mName4 = value;
        AnnotationValidations.validate(
                NonNull.class, null, mName4);
        return this;
    }

    /**
     * For parcelling, any field type supported by {@link Parcel} is supported out of the box.
     * E.g. {@link Parcelable} subclasses, {@link String}, {@link int}, {@link boolean}, etc.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setOtherParcelable(@NonNull AccessibilityNodeInfo value) {
        mOtherParcelable = value;
        return this;
    }

    /**
     * Additionally, support for parcelling other types can be added by implementing a
     * {@link Parcelling}, and referencing it in the {@link DataClass.ParcelWith} field annotation.
     *
     * @see MyDateParcelling an example {@link Parcelling} implementation
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setDate(@NonNull Date value) {
        mDate = value;
        AnnotationValidations.validate(
                NonNull.class, null, mDate);
        return this;
    }

    /**
     * If a {@link Parcelling} is fairly common, consider putting it in {@link Parcelling.BuiltIn}
     * to encourage its reuse.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setPattern(@NonNull Pattern value) {
        mPattern = value;
        AnnotationValidations.validate(
                NonNull.class, null, mPattern);
        return this;
    }

    /**
     * For lists, when using a {@link Builder}, other than a regular
     * {@link Builder#setLinkAddresses2(List) setter}, and additional
     * {@link Builder#addLinkAddresses2(LinkAddress) add} method is generated for convenience.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setLinkAddresses2(@NonNull List<LinkAddress> value) {
        mLinkAddresses2 = value;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses2);
        return this;
    }

    /**
     * For aesthetics, you may want to consider providing a singular version of the plural field
     * name, which would be used for the {@link #mLinkAddresses2 above mentioned} "add" method.
     *
     * @see Builder#addLinkAddress(LinkAddress)
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setLinkAddresses(@NonNull ArrayList<LinkAddress> value) {
        mLinkAddresses = value;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses);
        return this;
    }

    /**
     * For array fields, when using a {@link Builder}, vararg argument format is used for
     * convenience.
     *
     * @see Builder#setLinkAddresses4(LinkAddress...)
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setLinkAddresses4(@NonNull LinkAddress... value) {
        mLinkAddresses4 = value;
        return this;
    }

    /**
     * {@link IntDef}/{@link StringDef}-annotated fields propagate the annotation to
     * getter/constructor/setter/builder parameters, making for a nicer api.
     *
     * @see #getStateName
     * @see Builder#setStateName
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setStateName(@StateName @NonNull String value) {
        mStateName = value;

        if (!(Objects.equals(mStateName, STATE_NAME_UNDEFINED))
                && !(Objects.equals(mStateName, STATE_NAME_ON))
                && !(Objects.equals(mStateName, STATE_NAME_OFF))) {
            throw new java.lang.IllegalArgumentException(
                    "stateName was " + mStateName + " but must be one of: "
                            + "STATE_NAME_UNDEFINED(" + STATE_NAME_UNDEFINED + "), "
                            + "STATE_NAME_ON(" + STATE_NAME_ON + "), "
                            + "STATE_NAME_OFF(" + STATE_NAME_OFF + ")");
        }

        AnnotationValidations.validate(
                NonNull.class, null, mStateName);
        return this;
    }

    /**
     * Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setFlags(@RequestFlags int value) {
        mFlags = value;

        Preconditions.checkFlagsArgument(
                mFlags,
                FLAG_MANUAL_REQUEST
                        | FLAG_COMPATIBILITY_MODE_REQUEST
                        | FLAG_AUGMENTED_REQUEST);
        return this;
    }

    /**
     * Above is true for both {@link IntDef#flag flags} and enum-like {@link IntDef}s
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setState(@State int value) {
        mState = value;

        if (!(mState == STATE_ON)
                && !(mState == STATE_OFF)
                && !(mState == STATE_UNDEFINED)) {
            throw new java.lang.IllegalArgumentException(
                    "state was " + mState + " but must be one of: "
                            + "STATE_ON(" + STATE_ON + "), "
                            + "STATE_OFF(" + STATE_OFF + "), "
                            + "STATE_UNDEFINED(" + STATE_UNDEFINED + ")");
        }

        return this;
    }

    /**
     * Fields with certain annotations are automatically validated in constructor
     *
     * You can see overloads in {@link AnnotationValidations} for a list of currently
     * supported ones.
     *
     * You can also extend support to your custom annotations by creating another corresponding
     * overloads like
     * {@link AnnotationValidations#validate(Class, UserIdInt, int)}.
     *
     * @see #SampleDataClass
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setStringRes(@StringRes int value) {
        mStringRes = value;
        AnnotationValidations.validate(
                StringRes.class, null, mStringRes);
        return this;
    }

    /**
     * Validation annotations may also have parameters.
     *
     * Parameter values will be supplied to validation method as name-value pairs.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int, String, int)
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setDayOfWeek(@android.annotation.IntRange(from = 0, to = 6) int value) {
        mDayOfWeek = value;
        AnnotationValidations.validate(
                android.annotation.IntRange.class, null, mDayOfWeek,
                "from", 0,
                "to", 6);
        return this;
    }

    /**
     * Unnamed validation annotation parameter gets supplied to the validating method named as
     * "value".
     *
     * Validation annotations following {@link Each} annotation, will be applied for each
     * array/collection element instead.
     *
     * @see AnnotationValidations#validate(Class, Size, int, String, int)
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setCoords(@Size(2) @NonNull @FloatRange(from = 0f) float... value) {
        mCoords = value;
        AnnotationValidations.validate(
                Size.class, null, mCoords.length,
                "value", 2);
        AnnotationValidations.validate(
                NonNull.class, null, mCoords);
        int coordsSize = mCoords.length;
        for (int i = 0; i < coordsSize; i++) {
            AnnotationValidations.validate(
                    FloatRange.class, null, mCoords[i],
                    "from", 0f);
        }

        return this;
    }

    /**
     * Binder types are also supported
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setToken(@NonNull IBinder value) {
        mToken = value;
        AnnotationValidations.validate(
                NonNull.class, null, mToken);
        return this;
    }

    /**
     * AIDL interface types are also supported
     */
    @DataClass.Generated.Member
    public @NonNull SampleDataClass setIPCInterface(@NonNull ICompanionDeviceManager value) {
        mIPCInterface = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "SampleDataClass { " +
                "num = " + mNum + ", " +
                "num2 = " + mNum2 + ", " +
                "num4 = " + mNum4 + ", " +
                "name = " + mName + ", " +
                "name2 = " + mName2 + ", " +
                "name4 = " + mName4 + ", " +
                "otherParcelable = " + mOtherParcelable + ", " +
                "date = " + mDate + ", " +
                "pattern = " + mPattern + ", " +
                "linkAddresses2 = " + mLinkAddresses2 + ", " +
                "linkAddresses = " + mLinkAddresses + ", " +
                "linkAddresses4 = " + java.util.Arrays.toString(mLinkAddresses4) + ", " +
                "stateName = " + mStateName + ", " +
                "flags = " + requestFlagsToString(mFlags) + ", " +
                "state = " + stateToString(mState) + ", " +
                "charSeq = " + charSeq + ", " +
                "linkAddresses5 = " + java.util.Arrays.toString(mLinkAddresses5) + ", " +
                "stringRes = " + mStringRes + ", " +
                "dayOfWeek = " + mDayOfWeek + ", " +
                "coords = " + java.util.Arrays.toString(mCoords) + ", " +
                "token = " + mToken + ", " +
                "iPCInterface = " + mIPCInterface +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(SampleDataClass other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        SampleDataClass that = (SampleDataClass) o;
        //noinspection PointlessBooleanExpression
        return true
                && mNum == that.mNum
                && mNum2 == that.mNum2
                && mNum4 == that.mNum4
                && Objects.equals(mName, that.mName)
                && Objects.equals(mName2, that.mName2)
                && Objects.equals(mName4, that.mName4)
                && Objects.equals(mOtherParcelable, that.mOtherParcelable)
                && Objects.equals(mDate, that.mDate)
                && patternEquals(that.mPattern)
                && Objects.equals(mLinkAddresses2, that.mLinkAddresses2)
                && Objects.equals(mLinkAddresses, that.mLinkAddresses)
                && java.util.Arrays.equals(mLinkAddresses4, that.mLinkAddresses4)
                && Objects.equals(mStateName, that.mStateName)
                && mFlags == that.mFlags
                && mState == that.mState
                && Objects.equals(charSeq, that.charSeq)
                && java.util.Arrays.equals(mLinkAddresses5, that.mLinkAddresses5)
                && mStringRes == that.mStringRes
                && mDayOfWeek == that.mDayOfWeek
                && java.util.Arrays.equals(mCoords, that.mCoords)
                && Objects.equals(mToken, that.mToken)
                && Objects.equals(mIPCInterface, that.mIPCInterface);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mNum;
        _hash = 31 * _hash + mNum2;
        _hash = 31 * _hash + mNum4;
        _hash = 31 * _hash + Objects.hashCode(mName);
        _hash = 31 * _hash + Objects.hashCode(mName2);
        _hash = 31 * _hash + Objects.hashCode(mName4);
        _hash = 31 * _hash + Objects.hashCode(mOtherParcelable);
        _hash = 31 * _hash + Objects.hashCode(mDate);
        _hash = 31 * _hash + patternHashCode();
        _hash = 31 * _hash + Objects.hashCode(mLinkAddresses2);
        _hash = 31 * _hash + Objects.hashCode(mLinkAddresses);
        _hash = 31 * _hash + java.util.Arrays.hashCode(mLinkAddresses4);
        _hash = 31 * _hash + Objects.hashCode(mStateName);
        _hash = 31 * _hash + mFlags;
        _hash = 31 * _hash + mState;
        _hash = 31 * _hash + Objects.hashCode(charSeq);
        _hash = 31 * _hash + java.util.Arrays.hashCode(mLinkAddresses5);
        _hash = 31 * _hash + mStringRes;
        _hash = 31 * _hash + mDayOfWeek;
        _hash = 31 * _hash + java.util.Arrays.hashCode(mCoords);
        _hash = 31 * _hash + Objects.hashCode(mToken);
        _hash = 31 * _hash + Objects.hashCode(mIPCInterface);
        return _hash;
    }

    @DataClass.Generated.Member
    void forEachField(
            @NonNull DataClass.PerIntFieldAction<SampleDataClass> actionInt,
            @NonNull DataClass.PerObjectFieldAction<SampleDataClass> actionObject) {
        actionInt.acceptInt(this, "num", mNum);
        actionInt.acceptInt(this, "num2", mNum2);
        actionInt.acceptInt(this, "num4", mNum4);
        actionObject.acceptObject(this, "name", mName);
        actionObject.acceptObject(this, "name2", mName2);
        actionObject.acceptObject(this, "name4", mName4);
        actionObject.acceptObject(this, "otherParcelable", mOtherParcelable);
        actionObject.acceptObject(this, "date", mDate);
        actionObject.acceptObject(this, "pattern", mPattern);
        actionObject.acceptObject(this, "linkAddresses2", mLinkAddresses2);
        actionObject.acceptObject(this, "linkAddresses", mLinkAddresses);
        actionObject.acceptObject(this, "linkAddresses4", mLinkAddresses4);
        actionObject.acceptObject(this, "stateName", mStateName);
        actionInt.acceptInt(this, "flags", mFlags);
        actionInt.acceptInt(this, "state", mState);
        actionObject.acceptObject(this, "charSeq", charSeq);
        actionObject.acceptObject(this, "linkAddresses5", mLinkAddresses5);
        actionInt.acceptInt(this, "stringRes", mStringRes);
        actionInt.acceptInt(this, "dayOfWeek", mDayOfWeek);
        actionObject.acceptObject(this, "coords", mCoords);
        actionObject.acceptObject(this, "token", mToken);
        actionObject.acceptObject(this, "iPCInterface", mIPCInterface);
    }

    /** @deprecated May cause boxing allocations - use with caution! */
    @Deprecated
    @DataClass.Generated.Member
    void forEachField(@NonNull DataClass.PerObjectFieldAction<SampleDataClass> action) {
        action.acceptObject(this, "num", mNum);
        action.acceptObject(this, "num2", mNum2);
        action.acceptObject(this, "num4", mNum4);
        action.acceptObject(this, "name", mName);
        action.acceptObject(this, "name2", mName2);
        action.acceptObject(this, "name4", mName4);
        action.acceptObject(this, "otherParcelable", mOtherParcelable);
        action.acceptObject(this, "date", mDate);
        action.acceptObject(this, "pattern", mPattern);
        action.acceptObject(this, "linkAddresses2", mLinkAddresses2);
        action.acceptObject(this, "linkAddresses", mLinkAddresses);
        action.acceptObject(this, "linkAddresses4", mLinkAddresses4);
        action.acceptObject(this, "stateName", mStateName);
        action.acceptObject(this, "flags", mFlags);
        action.acceptObject(this, "state", mState);
        action.acceptObject(this, "charSeq", charSeq);
        action.acceptObject(this, "linkAddresses5", mLinkAddresses5);
        action.acceptObject(this, "stringRes", mStringRes);
        action.acceptObject(this, "dayOfWeek", mDayOfWeek);
        action.acceptObject(this, "coords", mCoords);
        action.acceptObject(this, "token", mToken);
        action.acceptObject(this, "iPCInterface", mIPCInterface);
    }

    @DataClass.Generated.Member
    static Parcelling<Date> sParcellingForDate =
            Parcelling.Cache.get(
                    MyDateParcelling.class);
    static {
        if (sParcellingForDate == null) {
            sParcellingForDate = Parcelling.Cache.put(
                    new MyDateParcelling());
        }
    }

    @DataClass.Generated.Member
    static Parcelling<Pattern> sParcellingForPattern =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForPattern.class);
    static {
        if (sParcellingForPattern == null) {
            sParcellingForPattern = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForPattern());
        }
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        long flg = 0;
        if (mName != null) flg |= 0x8;
        if (mOtherParcelable != null) flg |= 0x40;
        if (mLinkAddresses4 != null) flg |= 0x800;
        if (mLinkAddresses5 != null) flg |= 0x10000;
        if (mIPCInterface != null) flg |= 0x200000;
        dest.writeLong(flg);
        dest.writeInt(mNum);
        dest.writeInt(mNum2);
        dest.writeInt(mNum4);
        if (mName != null) dest.writeString(mName);
        dest.writeString(mName2);
        dest.writeString(mName4);
        if (mOtherParcelable != null) dest.writeTypedObject(mOtherParcelable, flags);
        sParcellingForDate.parcel(mDate, dest, flags);
        sParcellingForPattern.parcel(mPattern, dest, flags);
        dest.writeParcelableList(mLinkAddresses2, flags);
        dest.writeParcelableList(mLinkAddresses, flags);
        if (mLinkAddresses4 != null) dest.writeTypedArray(mLinkAddresses4, flags);
        dest.writeString(mStateName);
        dest.writeInt(mFlags);
        dest.writeInt(mState);
        dest.writeCharSequence(charSeq);
        if (mLinkAddresses5 != null) dest.writeTypedArray(mLinkAddresses5, flags);
        dest.writeInt(mStringRes);
        dest.writeInt(mDayOfWeek);
        dest.writeFloatArray(mCoords);
        dest.writeStrongBinder(mToken);
        if (mIPCInterface != null) dest.writeStrongInterface(mIPCInterface);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ SampleDataClass(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        long flg = in.readLong();
        int num = in.readInt();
        int num2 = in.readInt();
        int num4 = in.readInt();
        String name = (flg & 0x8) == 0 ? null : in.readString();
        String name2 = in.readString();
        String name4 = in.readString();
        AccessibilityNodeInfo otherParcelable = (flg & 0x40) == 0 ? null : (AccessibilityNodeInfo) in.readTypedObject(AccessibilityNodeInfo.CREATOR);
        Date date = sParcellingForDate.unparcel(in);
        Pattern pattern = sParcellingForPattern.unparcel(in);
        List<LinkAddress> linkAddresses2 = new ArrayList<>();
        in.readParcelableList(linkAddresses2, LinkAddress.class.getClassLoader());
        ArrayList<LinkAddress> linkAddresses = new ArrayList<>();
        in.readParcelableList(linkAddresses, LinkAddress.class.getClassLoader());
        LinkAddress[] linkAddresses4 = (flg & 0x800) == 0 ? null : (LinkAddress[]) in.createTypedArray(LinkAddress.CREATOR);
        String stateName = in.readString();
        int flags = in.readInt();
        int state = in.readInt();
        CharSequence _charSeq = (CharSequence) in.readCharSequence();
        LinkAddress[] linkAddresses5 = (flg & 0x10000) == 0 ? null : (LinkAddress[]) in.createTypedArray(LinkAddress.CREATOR);
        int stringRes = in.readInt();
        int dayOfWeek = in.readInt();
        float[] coords = in.createFloatArray();
        IBinder token = (IBinder) in.readStrongBinder();
        ICompanionDeviceManager iPCInterface = (flg & 0x200000) == 0 ? null : ICompanionDeviceManager.Stub.asInterface(in.readStrongBinder());

        this.mNum = num;
        this.mNum2 = num2;
        this.mNum4 = num4;
        this.mName = name;
        this.mName2 = name2;
        AnnotationValidations.validate(
                NonNull.class, null, mName2);
        this.mName4 = name4;
        AnnotationValidations.validate(
                NonNull.class, null, mName4);
        this.mOtherParcelable = otherParcelable;
        this.mDate = date;
        AnnotationValidations.validate(
                NonNull.class, null, mDate);
        this.mPattern = pattern;
        AnnotationValidations.validate(
                NonNull.class, null, mPattern);
        this.mLinkAddresses2 = linkAddresses2;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses2);
        this.mLinkAddresses = linkAddresses;
        AnnotationValidations.validate(
                NonNull.class, null, mLinkAddresses);
        this.mLinkAddresses4 = linkAddresses4;
        this.mStateName = stateName;

        if (!(Objects.equals(mStateName, STATE_NAME_UNDEFINED))
                && !(Objects.equals(mStateName, STATE_NAME_ON))
                && !(Objects.equals(mStateName, STATE_NAME_OFF))) {
            throw new java.lang.IllegalArgumentException(
                    "stateName was " + mStateName + " but must be one of: "
                            + "STATE_NAME_UNDEFINED(" + STATE_NAME_UNDEFINED + "), "
                            + "STATE_NAME_ON(" + STATE_NAME_ON + "), "
                            + "STATE_NAME_OFF(" + STATE_NAME_OFF + ")");
        }

        AnnotationValidations.validate(
                NonNull.class, null, mStateName);
        this.mFlags = flags;

        Preconditions.checkFlagsArgument(
                mFlags,
                FLAG_MANUAL_REQUEST
                        | FLAG_COMPATIBILITY_MODE_REQUEST
                        | FLAG_AUGMENTED_REQUEST);
        this.mState = state;

        if (!(mState == STATE_ON)
                && !(mState == STATE_OFF)
                && !(mState == STATE_UNDEFINED)) {
            throw new java.lang.IllegalArgumentException(
                    "state was " + mState + " but must be one of: "
                            + "STATE_ON(" + STATE_ON + "), "
                            + "STATE_OFF(" + STATE_OFF + "), "
                            + "STATE_UNDEFINED(" + STATE_UNDEFINED + ")");
        }

        this.charSeq = _charSeq;
        AnnotationValidations.validate(
                NonNull.class, null, charSeq);
        this.mLinkAddresses5 = linkAddresses5;
        this.mStringRes = stringRes;
        AnnotationValidations.validate(
                StringRes.class, null, mStringRes);
        this.mDayOfWeek = dayOfWeek;
        AnnotationValidations.validate(
                android.annotation.IntRange.class, null, mDayOfWeek,
                "from", 0,
                "to", 6);
        this.mCoords = coords;
        AnnotationValidations.validate(
                Size.class, null, mCoords.length,
                "value", 2);
        AnnotationValidations.validate(
                NonNull.class, null, mCoords);
        int coordsSize = mCoords.length;
        for (int i = 0; i < coordsSize; i++) {
            AnnotationValidations.validate(
                    FloatRange.class, null, mCoords[i],
                    "from", 0f);
        }

        this.mToken = token;
        AnnotationValidations.validate(
                NonNull.class, null, mToken);
        this.mIPCInterface = iPCInterface;

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<SampleDataClass> CREATOR
            = new Parcelable.Creator<SampleDataClass>() {
        @Override
        public SampleDataClass[] newArray(int size) {
            return new SampleDataClass[size];
        }

        @Override
        public SampleDataClass createFromParcel(@NonNull Parcel in) {
            return new SampleDataClass(in);
        }
    };

    /**
     * A builder for {@link SampleDataClass}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private int mNum;
        private int mNum2;
        private int mNum4;
        private @Nullable String mName;
        private @NonNull String mName2;
        private @NonNull String mName4;
        private @Nullable AccessibilityNodeInfo mOtherParcelable;
        private @NonNull Date mDate;
        private @NonNull Pattern mPattern;
        private @NonNull List<LinkAddress> mLinkAddresses2;
        private @NonNull ArrayList<LinkAddress> mLinkAddresses;
        private @Nullable LinkAddress[] mLinkAddresses4;
        private @StateName @NonNull String mStateName;
        private @RequestFlags int mFlags;
        private @State int mState;
        private @NonNull CharSequence charSeq;
        private @Nullable LinkAddress[] mLinkAddresses5;
        private @StringRes int mStringRes;
        private @android.annotation.IntRange(from = 0, to = 6) int mDayOfWeek;
        private @Size(2) @NonNull @FloatRange(from = 0f) float[] mCoords;
        private @NonNull IBinder mToken;
        private @Nullable ICompanionDeviceManager mIPCInterface;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param num
         *   Any property javadoc should go onto the field, and will be copied where appropriate,
         *   including getters, constructor parameters, builder setters, etc.
         *
         *   <p>
         *   This allows to avoid the burden of maintaining copies of the same documentation
         *   pieces in multiple places for each field.
         * @param num2
         *   Various javadoc features should work as expected when copied, e.g {@code code},
         *   {@link #mName links}, <a href="https://google.com">html links</a>, etc.
         * @param num4
         *   {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
         *   desired public API surface.
         * @param name
         *   {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
         * @param flags
         *   Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
         * @param linkAddresses5
         *   Final fields suppress generating a setter (when setters are requested).
         */
        public Builder(
                int num,
                int num2,
                int num4,
                @Nullable String name,
                @RequestFlags int flags,
                @Nullable LinkAddress[] linkAddresses5) {
            mNum = num;
            mNum2 = num2;
            mNum4 = num4;
            mName = name;
            mFlags = flags;

            Preconditions.checkFlagsArgument(
                    mFlags,
                    FLAG_MANUAL_REQUEST
                            | FLAG_COMPATIBILITY_MODE_REQUEST
                            | FLAG_AUGMENTED_REQUEST);
            mLinkAddresses5 = linkAddresses5;
        }

        /**
         * Any property javadoc should go onto the field, and will be copied where appropriate,
         * including getters, constructor parameters, builder setters, etc.
         *
         * <p>
         * This allows to avoid the burden of maintaining copies of the same documentation
         * pieces in multiple places for each field.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setNum(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mNum = value;
            return this;
        }

        /**
         * Various javadoc features should work as expected when copied, e.g {@code code},
         * {@link #mName links}, <a href="https://google.com">html links</a>, etc.
         *
         * @see #mNum2 ..and so should blocks at the bottom, e.g. {@code @see} blocks.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setNum2(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mNum2 = value;
            return this;
        }

        /**
         * {@code @hide} javadoc annotation is also propagated, which can be used to adjust the
         * desired public API surface.
         *
         * @see #getNum4() is hidden
         * @see Builder#setNum4(int) also hidden
         * @hide
         */
        @DataClass.Generated.Member
        public @NonNull Builder setNum4(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mNum4 = value;
            return this;
        }

        /**
         * {@link Nullable} or {@link NonNull} annotation is required on all non-primitive fields.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mName = value;
            return this;
        }

        /**
         * Fields with default value expressions ("mFoo = ...") are optional, and are automatically
         * initialized to the provided default expression, unless explicitly set.
         *
         * When using a {@link Builder} optional fields are passed via a {@link Builder#setName2 setter}
         * while mandatory fields are passed via {@link Builder#Builder constructor}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setName2(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mName2 = value;
            return this;
        }

        /**
         * Alternatively, when default value computation is expensive,
         * {@link #defaultName4 defaultFieldName()} can be defined to compute the default value.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setName4(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mName4 = value;
            return this;
        }

        /**
         * For parcelling, any field type supported by {@link Parcel} is supported out of the box.
         * E.g. {@link Parcelable} subclasses, {@link String}, {@link int}, {@link boolean}, etc.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setOtherParcelable(@NonNull AccessibilityNodeInfo value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mOtherParcelable = value;
            return this;
        }

        /**
         * Additionally, support for parcelling other types can be added by implementing a
         * {@link Parcelling}, and referencing it in the {@link DataClass.ParcelWith} field annotation.
         *
         * @see MyDateParcelling an example {@link Parcelling} implementation
         */
        @DataClass.Generated.Member
        public @NonNull Builder setDate(@NonNull Date value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80;
            mDate = value;
            return this;
        }

        /**
         * If a {@link Parcelling} is fairly common, consider putting it in {@link Parcelling.BuiltIn}
         * to encourage its reuse.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setPattern(@NonNull Pattern value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100;
            mPattern = value;
            return this;
        }

        /**
         * For lists, when using a {@link Builder}, other than a regular
         * {@link Builder#setLinkAddresses2(List) setter}, and additional
         * {@link Builder#addLinkAddresses2(LinkAddress) add} method is generated for convenience.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setLinkAddresses2(@NonNull List<LinkAddress> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200;
            mLinkAddresses2 = value;
            return this;
        }

        /** @see #setLinkAddresses2 */
        @DataClass.Generated.Member
        public @NonNull Builder addLinkAddresses2(@NonNull LinkAddress value) {
            // You can refine this method's name by providing item's singular name, e.g.:
            // @DataClass.PluralOf("item")) mItems = ...

            if (mLinkAddresses2 == null) setLinkAddresses2(new ArrayList<>());
            mLinkAddresses2.add(value);
            return this;
        }

        /**
         * For aesthetics, you may want to consider providing a singular version of the plural field
         * name, which would be used for the {@link #mLinkAddresses2 above mentioned} "add" method.
         *
         * @see Builder#addLinkAddress(LinkAddress)
         */
        @DataClass.Generated.Member
        public @NonNull Builder setLinkAddresses(@NonNull ArrayList<LinkAddress> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x400;
            mLinkAddresses = value;
            return this;
        }

        /** @see #setLinkAddresses */
        @DataClass.Generated.Member
        public @NonNull Builder addLinkAddress(@NonNull LinkAddress value) {
            if (mLinkAddresses == null) setLinkAddresses(new ArrayList<>());
            mLinkAddresses.add(value);
            return this;
        }

        /**
         * For array fields, when using a {@link Builder}, vararg argument format is used for
         * convenience.
         *
         * @see Builder#setLinkAddresses4(LinkAddress...)
         */
        @DataClass.Generated.Member
        public @NonNull Builder setLinkAddresses4(@NonNull LinkAddress... value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x800;
            mLinkAddresses4 = value;
            return this;
        }

        /**
         * {@link IntDef}/{@link StringDef}-annotated fields propagate the annotation to
         * getter/constructor/setter/builder parameters, making for a nicer api.
         *
         * @see #getStateName
         * @see Builder#setStateName
         */
        @DataClass.Generated.Member
        public @NonNull Builder setStateName(@StateName @NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1000;
            mStateName = value;
            return this;
        }

        /**
         * Fields annotated with {@link IntDef} annotations also get a proper {@link #toString()} value.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setFlags(@RequestFlags int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2000;
            mFlags = value;
            return this;
        }

        /**
         * Above is true for both {@link IntDef#flag flags} and enum-like {@link IntDef}s
         */
        @DataClass.Generated.Member
        public @NonNull Builder setState(@State int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4000;
            mState = value;
            return this;
        }

        /**
         * Making a field public will suppress getter generation in favor of accessing it directly.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setCharSeq(@NonNull CharSequence value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8000;
            charSeq = value;
            return this;
        }

        /**
         * Final fields suppress generating a setter (when setters are requested).
         */
        @DataClass.Generated.Member
        public @NonNull Builder setLinkAddresses5(@NonNull LinkAddress... value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10000;
            mLinkAddresses5 = value;
            return this;
        }

        /**
         * Fields with certain annotations are automatically validated in constructor
         *
         * You can see overloads in {@link AnnotationValidations} for a list of currently
         * supported ones.
         *
         * You can also extend support to your custom annotations by creating another corresponding
         * overloads like
         * {@link AnnotationValidations#validate(Class, UserIdInt, int)}.
         *
         * @see #SampleDataClass
         */
        @DataClass.Generated.Member
        public @NonNull Builder setStringRes(@StringRes int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20000;
            mStringRes = value;
            return this;
        }

        /**
         * Validation annotations may also have parameters.
         *
         * Parameter values will be supplied to validation method as name-value pairs.
         *
         * @see AnnotationValidations#validate(Class, Size, int, String, int, String, int)
         */
        @DataClass.Generated.Member
        public @NonNull Builder setDayOfWeek(@android.annotation.IntRange(from = 0, to = 6) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40000;
            mDayOfWeek = value;
            return this;
        }

        /**
         * Unnamed validation annotation parameter gets supplied to the validating method named as
         * "value".
         *
         * Validation annotations following {@link Each} annotation, will be applied for each
         * array/collection element instead.
         *
         * @see AnnotationValidations#validate(Class, Size, int, String, int)
         */
        @DataClass.Generated.Member
        public @NonNull Builder setCoords(@Size(2) @NonNull @FloatRange(from = 0f) float... value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80000;
            mCoords = value;
            return this;
        }

        /**
         * Binder types are also supported
         */
        @DataClass.Generated.Member
        public @NonNull Builder setToken(@NonNull IBinder value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100000;
            mToken = value;
            return this;
        }

        /**
         * AIDL interface types are also supported
         */
        @DataClass.Generated.Member
        public @NonNull Builder setIPCInterface(@NonNull ICompanionDeviceManager value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200000;
            mIPCInterface = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull SampleDataClass build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x400000; // Mark builder used

            if ((mBuilderFieldsSet & 0x10) == 0) {
                mName2 = "Bob";
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mName4 = defaultName4();
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mOtherParcelable = null;
            }
            if ((mBuilderFieldsSet & 0x80) == 0) {
                mDate = new Date(42 * 42);
            }
            if ((mBuilderFieldsSet & 0x100) == 0) {
                mPattern = Pattern.compile("");
            }
            if ((mBuilderFieldsSet & 0x200) == 0) {
                mLinkAddresses2 = new ArrayList<>();
            }
            if ((mBuilderFieldsSet & 0x400) == 0) {
                mLinkAddresses = new ArrayList<>();
            }
            if ((mBuilderFieldsSet & 0x800) == 0) {
                mLinkAddresses4 = null;
            }
            if ((mBuilderFieldsSet & 0x1000) == 0) {
                mStateName = STATE_NAME_UNDEFINED;
            }
            if ((mBuilderFieldsSet & 0x4000) == 0) {
                mState = STATE_UNDEFINED;
            }
            if ((mBuilderFieldsSet & 0x8000) == 0) {
                charSeq = "";
            }
            if ((mBuilderFieldsSet & 0x20000) == 0) {
                mStringRes = 0;
            }
            if ((mBuilderFieldsSet & 0x40000) == 0) {
                mDayOfWeek = 3;
            }
            if ((mBuilderFieldsSet & 0x80000) == 0) {
                mCoords = new float[] { 0f, 0f };
            }
            if ((mBuilderFieldsSet & 0x100000) == 0) {
                mToken = new Binder();
            }
            if ((mBuilderFieldsSet & 0x200000) == 0) {
                mIPCInterface = null;
            }
            SampleDataClass o = new SampleDataClass(
                    mNum,
                    mNum2,
                    mNum4,
                    mName,
                    mName2,
                    mName4,
                    mOtherParcelable,
                    mDate,
                    mPattern,
                    mLinkAddresses2,
                    mLinkAddresses,
                    mLinkAddresses4,
                    mStateName,
                    mFlags,
                    mState,
                    charSeq,
                    mLinkAddresses5,
                    mStringRes,
                    mDayOfWeek,
                    mCoords,
                    mToken,
                    mIPCInterface);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x400000) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1616541539978L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/tests/Codegen/src/com/android/codegentest/SampleDataClass.java",
            inputSignatures = "public static final  java.lang.String STATE_NAME_UNDEFINED\npublic static final  java.lang.String STATE_NAME_ON\npublic static final  java.lang.String STATE_NAME_OFF\npublic static final  int STATE_ON\npublic static final  int STATE_OFF\npublic static final  int STATE_UNDEFINED\npublic static final @com.android.codegentest.SampleDataClass.RequestFlags int FLAG_MANUAL_REQUEST\npublic static final @com.android.codegentest.SampleDataClass.RequestFlags int FLAG_COMPATIBILITY_MODE_REQUEST\npublic static final @com.android.codegentest.SampleDataClass.RequestFlags int FLAG_AUGMENTED_REQUEST\nprivate  int mNum\nprivate  int mNum2\nprivate  int mNum4\nprivate @android.annotation.Nullable java.lang.String mName\nprivate @android.annotation.NonNull java.lang.String mName2\nprivate @android.annotation.NonNull java.lang.String mName4\nprivate @android.annotation.Nullable android.view.accessibility.AccessibilityNodeInfo mOtherParcelable\nprivate @com.android.internal.util.DataClass.ParcelWith(com.android.codegentest.MyDateParcelling.class) @android.annotation.NonNull java.util.Date mDate\nprivate @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForPattern.class) @android.annotation.NonNull java.util.regex.Pattern mPattern\nprivate @android.annotation.NonNull java.util.List<android.net.LinkAddress> mLinkAddresses2\nprivate @com.android.internal.util.DataClass.PluralOf(\"linkAddress\") @android.annotation.NonNull java.util.ArrayList<android.net.LinkAddress> mLinkAddresses\nprivate @android.annotation.Nullable android.net.LinkAddress[] mLinkAddresses4\nprivate @com.android.codegentest.SampleDataClass.StateName @android.annotation.NonNull java.lang.String mStateName\nprivate @com.android.codegentest.SampleDataClass.RequestFlags int mFlags\nprivate @com.android.codegentest.SampleDataClass.State int mState\npublic @android.annotation.NonNull java.lang.CharSequence charSeq\nprivate final @android.annotation.Nullable android.net.LinkAddress[] mLinkAddresses5\nprivate transient  android.net.LinkAddress[] mLinkAddresses6\ntransient  int[] mTmpStorage\nprivate @android.annotation.StringRes int mStringRes\nprivate @android.annotation.IntRange int mDayOfWeek\nprivate @android.annotation.Size @android.annotation.NonNull @com.android.internal.util.DataClass.Each @android.annotation.FloatRange float[] mCoords\nprivate @android.annotation.NonNull android.os.IBinder mToken\nprivate @android.annotation.Nullable android.companion.ICompanionDeviceManager mIPCInterface\nprivate static  java.lang.String defaultName4()\nprivate  int[] lazyInitTmpStorage()\npublic  android.net.LinkAddress[] getLinkAddresses4()\nprivate  boolean patternEquals(java.util.regex.Pattern)\nprivate  int patternHashCode()\nprivate  void onConstructed()\npublic  void dump(java.io.PrintWriter)\nclass SampleDataClass extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genBuilder=true, genConstructor=true, genEqualsHashCode=true, genToString=true, genForEachField=true, genSetters=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
