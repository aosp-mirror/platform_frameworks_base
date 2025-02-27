/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import static com.android.internal.pm.parsing.pkg.PackageImpl.sForInternedString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

import libcore.util.EmptyArray;

/**
 * @hide
 */
@DataClass(genGetters = true, genSetters = true, genBuilder = false, genParcelable = false)
public class ParsedMainComponentImpl extends ParsedComponentImpl implements ParsedMainComponent,
        Parcelable {

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String processName;
    private boolean directBootAware;
    private boolean enabled = true;
    private boolean exported;
    private int order;

    @Nullable
    private String splitName;
    @Nullable
    private String[] attributionTags;

    private int mIntentMatchingFlags;

    /**
     * Opt-out of all intent filter matching rules. The value corresponds to the <code>none</code>
     * value of {@link android.R.attr#intentMatchingFlags}
     * @hide
     */
    public static final int INTENT_MATCHING_FLAGS_NONE = 1;

    /**
     * Opt-in to enforce intent filter matching. The value corresponds to the
     * <code>enforceIntentFilter</code> value of {@link android.R.attr#intentMatchingFlags}
     * @hide
     */
    public static final int INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER = 1 << 1;

    /**
     * Allows intent filters to match actions even when the action value is null. The value
     * corresponds to the <code>allowNullAction</code> value of
     * {@link android.R.attr#intentMatchingFlags}
     * @hide
     */
    public static final int INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION = 1 << 2;

    public ParsedMainComponentImpl() {
    }

    public ParsedMainComponentImpl(ParsedMainComponent other) {
        super(other);
        this.processName = other.getProcessName();
        this.directBootAware = other.isDirectBootAware();
        this.enabled = other.isEnabled();
        this.exported = other.isExported();
        this.order = other.getOrder();
        this.splitName = other.getSplitName();
        this.attributionTags = other.getAttributionTags();
    }

    public ParsedMainComponentImpl setProcessName(String processName) {
        this.processName = TextUtils.safeIntern(processName);
        return this;
    }

    /**
     * A main component's name is a class name. This makes code slightly more readable.
     */
    public String getClassName() {
        return getName();
    }

    @NonNull
    @Override
    public String[] getAttributionTags() {
        return attributionTags == null ? EmptyArray.STRING : attributionTags;
    }

    /**
     * Sets the intent matching flags. This value is intended to be set from the "component" tags.
     * @see android.R.styleable#AndroidManifestApplication_intentMatchingFlags
     */
    public ParsedMainComponent setIntentMatchingFlags(int intentMatchingFlags) {
        mIntentMatchingFlags = intentMatchingFlags;
        return this;
    }

    @Override
    public int getIntentMatchingFlags() {
        return this.mIntentMatchingFlags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        sForInternedString.parcel(this.processName, dest, flags);
        dest.writeBoolean(this.directBootAware);
        dest.writeBoolean(this.enabled);
        dest.writeBoolean(this.exported);
        dest.writeInt(this.order);
        dest.writeString(this.splitName);
        dest.writeString8Array(this.attributionTags);
        dest.writeInt(this.mIntentMatchingFlags);
    }

    protected ParsedMainComponentImpl(Parcel in) {
        super(in);
        this.processName = sForInternedString.unparcel(in);
        this.directBootAware = in.readBoolean();
        this.enabled = in.readBoolean();
        this.exported = in.readBoolean();
        this.order = in.readInt();
        this.splitName = in.readString();
        this.attributionTags = in.createString8Array();
        this.mIntentMatchingFlags = in.readInt();
    }

    public static final Parcelable.Creator<ParsedMainComponentImpl> CREATOR =
            new Parcelable.Creator<ParsedMainComponentImpl>() {
                @Override
                public ParsedMainComponentImpl createFromParcel(Parcel source) {
                    return new ParsedMainComponentImpl(source);
                }

                @Override
                public ParsedMainComponentImpl[] newArray(int size) {
                    return new ParsedMainComponentImpl[size];
                }
            };



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedMainComponentImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @android.annotation.IntDef(prefix = "INTENT_MATCHING_FLAGS_", value = {
        INTENT_MATCHING_FLAGS_NONE,
        INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER,
        INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface IntentMatchingFlags {}

    @DataClass.Generated.Member
    public static String intentMatchingFlagsToString(@IntentMatchingFlags int value) {
        switch (value) {
            case INTENT_MATCHING_FLAGS_NONE:
                    return "INTENT_MATCHING_FLAGS_NONE";
            case INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER:
                    return "INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER";
            case INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION:
                    return "INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    public ParsedMainComponentImpl(
            @Nullable String processName,
            boolean directBootAware,
            boolean enabled,
            boolean exported,
            int order,
            @Nullable String splitName,
            @Nullable String[] attributionTags,
            int intentMatchingFlags) {
        this.processName = processName;
        this.directBootAware = directBootAware;
        this.enabled = enabled;
        this.exported = exported;
        this.order = order;
        this.splitName = splitName;
        this.attributionTags = attributionTags;
        this.mIntentMatchingFlags = intentMatchingFlags;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @Nullable String getProcessName() {
        return processName;
    }

    @DataClass.Generated.Member
    public boolean isDirectBootAware() {
        return directBootAware;
    }

    @DataClass.Generated.Member
    public boolean isEnabled() {
        return enabled;
    }

    @DataClass.Generated.Member
    public boolean isExported() {
        return exported;
    }

    @DataClass.Generated.Member
    public int getOrder() {
        return order;
    }

    @DataClass.Generated.Member
    public @Nullable String getSplitName() {
        return splitName;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setDirectBootAware( boolean value) {
        directBootAware = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setEnabled( boolean value) {
        enabled = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setExported( boolean value) {
        exported = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setOrder( int value) {
        order = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setSplitName(@NonNull String value) {
        splitName = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedMainComponentImpl setAttributionTags(@NonNull String... value) {
        attributionTags = value;
        return this;
    }

    @DataClass.Generated(
            time = 1729613643190L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedMainComponentImpl.java",
            inputSignatures = "private @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String processName\nprivate  boolean directBootAware\nprivate  boolean enabled\nprivate  boolean exported\nprivate  int order\nprivate @android.annotation.Nullable java.lang.String splitName\nprivate @android.annotation.Nullable java.lang.String[] attributionTags\nprivate  int mIntentMatchingFlags\npublic static final  int INTENT_MATCHING_FLAGS_NONE\npublic static final  int INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER\npublic static final  int INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION\npublic static final  android.os.Parcelable.Creator<com.android.internal.pm.pkg.component.ParsedMainComponentImpl> CREATOR\npublic  com.android.internal.pm.pkg.component.ParsedMainComponentImpl setProcessName(java.lang.String)\npublic  java.lang.String getClassName()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String[] getAttributionTags()\npublic  com.android.internal.pm.pkg.component.ParsedMainComponent setIntentMatchingFlags(int)\npublic @java.lang.Override int getIntentMatchingFlags()\npublic @java.lang.Override int describeContents()\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\nclass ParsedMainComponentImpl extends com.android.internal.pm.pkg.component.ParsedComponentImpl implements [com.android.internal.pm.pkg.component.ParsedMainComponent, android.os.Parcelable]\n@com.android.internal.util.DataClass(genGetters=true, genSetters=true, genBuilder=false, genParcelable=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
