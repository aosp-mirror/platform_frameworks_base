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


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;

/**
 * @hide
 **/
@DataClass(genGetters = true, genAidl = false, genSetters = true, genParcelable = true)
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class ParsedApexSystemServiceImpl implements ParsedApexSystemService, Parcelable {

    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedString.class)
    @NonNull
    private String name;

    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedString.class)
    @Nullable
    private String jarPath;

    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedString.class)
    @Nullable
    private String minSdkVersion;

    @DataClass.ParcelWith(Parcelling.BuiltIn.ForInternedString.class)
    @Nullable
    private String maxSdkVersion;

    private int initOrder;

    public ParsedApexSystemServiceImpl() {
    }

    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedApexSystemServiceImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public ParsedApexSystemServiceImpl(
            @NonNull String name,
            @Nullable String jarPath,
            @Nullable String minSdkVersion,
            @Nullable String maxSdkVersion,
            int initOrder) {
        this.name = name;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, name);
        this.jarPath = jarPath;
        this.minSdkVersion = minSdkVersion;
        this.maxSdkVersion = maxSdkVersion;
        this.initOrder = initOrder;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @NonNull String getName() {
        return name;
    }

    @DataClass.Generated.Member
    public @Nullable String getJarPath() {
        return jarPath;
    }

    @DataClass.Generated.Member
    public @Nullable String getMinSdkVersion() {
        return minSdkVersion;
    }

    @DataClass.Generated.Member
    public @Nullable String getMaxSdkVersion() {
        return maxSdkVersion;
    }

    @DataClass.Generated.Member
    public int getInitOrder() {
        return initOrder;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedApexSystemServiceImpl setName(@NonNull String value) {
        name = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, name);
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedApexSystemServiceImpl setJarPath(@NonNull String value) {
        jarPath = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedApexSystemServiceImpl setMinSdkVersion(@NonNull String value) {
        minSdkVersion = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedApexSystemServiceImpl setMaxSdkVersion(@NonNull String value) {
        maxSdkVersion = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedApexSystemServiceImpl setInitOrder( int value) {
        initOrder = value;
        return this;
    }

    @DataClass.Generated.Member
    static Parcelling<String> sParcellingForName =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForInternedString.class);
    static {
        if (sParcellingForName == null) {
            sParcellingForName = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForInternedString());
        }
    }

    @DataClass.Generated.Member
    static Parcelling<String> sParcellingForJarPath =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForInternedString.class);
    static {
        if (sParcellingForJarPath == null) {
            sParcellingForJarPath = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForInternedString());
        }
    }

    @DataClass.Generated.Member
    static Parcelling<String> sParcellingForMinSdkVersion =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForInternedString.class);
    static {
        if (sParcellingForMinSdkVersion == null) {
            sParcellingForMinSdkVersion = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForInternedString());
        }
    }

    @DataClass.Generated.Member
    static Parcelling<String> sParcellingForMaxSdkVersion =
            Parcelling.Cache.get(
                    Parcelling.BuiltIn.ForInternedString.class);
    static {
        if (sParcellingForMaxSdkVersion == null) {
            sParcellingForMaxSdkVersion = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForInternedString());
        }
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (jarPath != null) flg |= 0x2;
        if (minSdkVersion != null) flg |= 0x4;
        if (maxSdkVersion != null) flg |= 0x8;
        dest.writeByte(flg);
        sParcellingForName.parcel(name, dest, flags);
        sParcellingForJarPath.parcel(jarPath, dest, flags);
        sParcellingForMinSdkVersion.parcel(minSdkVersion, dest, flags);
        sParcellingForMaxSdkVersion.parcel(maxSdkVersion, dest, flags);
        dest.writeInt(initOrder);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected ParsedApexSystemServiceImpl(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        String _name = sParcellingForName.unparcel(in);
        String _jarPath = sParcellingForJarPath.unparcel(in);
        String _minSdkVersion = sParcellingForMinSdkVersion.unparcel(in);
        String _maxSdkVersion = sParcellingForMaxSdkVersion.unparcel(in);
        int _initOrder = in.readInt();

        this.name = _name;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, name);
        this.jarPath = _jarPath;
        this.minSdkVersion = _minSdkVersion;
        this.maxSdkVersion = _maxSdkVersion;
        this.initOrder = _initOrder;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<ParsedApexSystemServiceImpl> CREATOR
            = new Parcelable.Creator<ParsedApexSystemServiceImpl>() {
        @Override
        public ParsedApexSystemServiceImpl[] newArray(int size) {
            return new ParsedApexSystemServiceImpl[size];
        }

        @Override
        public ParsedApexSystemServiceImpl createFromParcel(@NonNull android.os.Parcel in) {
            return new ParsedApexSystemServiceImpl(in);
        }
    };

    @DataClass.Generated(
            time = 1701710844088L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedApexSystemServiceImpl.java",
            inputSignatures = "private @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) @android.annotation.NonNull java.lang.String name\nprivate @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) @android.annotation.Nullable java.lang.String jarPath\nprivate @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) @android.annotation.Nullable java.lang.String minSdkVersion\nprivate @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) @android.annotation.Nullable java.lang.String maxSdkVersion\nprivate  int initOrder\nclass ParsedApexSystemServiceImpl extends java.lang.Object implements [com.android.internal.pm.pkg.component.ParsedApexSystemService, android.os.Parcelable]\n@com.android.internal.util.DataClass(genGetters=true, genAidl=false, genSetters=true, genParcelable=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
