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

package android.view.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.os.Bundle;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;
import android.widget.inline.InlinePresentationSpec;

import com.android.internal.util.DataClass;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents an inline suggestion request made by one app to get suggestions from the
 * other source. See {@link InlineSuggestion} for more information.
 */
@DataClass(genEqualsHashCode = true, genToString = true, genBuilder = true)
public final class InlineSuggestionsRequest implements Parcelable {

    /** Constant used to indicate not putting a cap on the number of suggestions to return. */
    public static final int SUGGESTION_COUNT_UNLIMITED = Integer.MAX_VALUE;

    /**
     * Max number of suggestions expected from the response. It must be a positive value.
     * Defaults to {@code SUGGESTION_COUNT_UNLIMITED} if not set.
     */
    private final int mMaxSuggestionCount;

    /**
     * The {@link InlinePresentationSpec} for each suggestion in the response. If the max suggestion
     * count is larger than the number of specs in the list, then the last spec is used for the
     * remainder of the suggestions. The list should not be empty.
     */
    private final @NonNull List<InlinePresentationSpec> mInlinePresentationSpecs;

    /**
     * The package name of the app that requests for the inline suggestions and will host the
     * embedded suggestion views. The app does not have to set the value for the field because
     * it'll be set by the system for safety reasons.
     */
    private @NonNull String mHostPackageName;

    /**
     * The IME provided locales for the request. If non-empty, the inline suggestions should
     * return languages from the supported locales. If not provided, it'll default to system locale.
     */
    private @NonNull LocaleList mSupportedLocales;

    /**
     * The extras state propagated from the IME to pass extra data.
     */
    private @Nullable Bundle mExtras;

    /**
     * The host input token of the IME that made the request. This will be set by the system for
     * safety reasons.
     *
     * @hide
     */
    private @Nullable IBinder mHostInputToken;

    /**
     * The host display id of the IME that made the request. This will be set by the system for
     * safety reasons.
     *
     * @hide
     */
    private int mHostDisplayId;

    /**
     * The {@link InlinePresentationSpec} for each suggestion in the response. If the max suggestion
     * count is larger than the number of specs in the list, then the last spec is used for the
     * remainder of the suggestions. The list should not be empty.
     *
     * @hide
     * @removed
     */
    public @NonNull List<android.view.inline.InlinePresentationSpec> getPresentationSpecs() {
        final ArrayList<android.view.inline.InlinePresentationSpec> convertedSpecs =
                new ArrayList<>();
        for (int i = 0; i < mInlinePresentationSpecs.size(); i++) {
            final android.widget.inline.InlinePresentationSpec currSpec =
                    mInlinePresentationSpecs.get(i);
            final android.view.inline.InlinePresentationSpec.Builder builder =
                    new android.view.inline.InlinePresentationSpec.Builder(
                            currSpec.getMinSize(), currSpec.getMaxSize());
            final Bundle style = currSpec.getStyle();
            if (style != null) {
                builder.setStyle(style);
            }
            convertedSpecs.add(builder.build());
        }
        return convertedSpecs;
    }

    /**
     * @hide
     * @see {@link #mHostInputToken}.
     */
    public void setHostInputToken(IBinder hostInputToken) {
        mHostInputToken = hostInputToken;
    }

    // TODO(b/149609075): remove once IBinder parcelling is natively supported
    private void parcelHostInputToken(@NonNull Parcel parcel, int flags) {
        parcel.writeStrongBinder(mHostInputToken);
    }

    // TODO(b/149609075): remove once IBinder parcelling is natively supported
    private @Nullable IBinder unparcelHostInputToken(Parcel parcel) {
        return parcel.readStrongBinder();
    }

    /**
     * @hide
     * @see {@link #mHostDisplayId}.
     */
    public void setHostDisplayId(int hostDisplayId) {
        mHostDisplayId = hostDisplayId;
    }

    private void onConstructed() {
        Preconditions.checkState(!mInlinePresentationSpecs.isEmpty());
        Preconditions.checkState(mMaxSuggestionCount >= mInlinePresentationSpecs.size());
    }

    private static int defaultMaxSuggestionCount() {
        return SUGGESTION_COUNT_UNLIMITED;
    }

    private static String defaultHostPackageName() {
        return ActivityThread.currentPackageName();
    }

    private static LocaleList defaultSupportedLocales() {
        return LocaleList.getDefault();
    }

    @Nullable
    private static IBinder defaultHostInputToken() {
        return null;
    }

    @Nullable
    private static int defaultHostDisplayId() {
        return Display.INVALID_DISPLAY;
    }

    @Nullable
    private static Bundle defaultExtras() {
        return null;
    }

    /** @hide */
    abstract static class BaseBuilder {
        /**
         * The {@link android.view.inline.InlinePresentationSpec} for each suggestion in the
         * response. If the max suggestion count is larger than the number of specs in the list,
         * then the last spec is used for the remainder of the suggestions.
         * The list should not be empty.
         *
         * @hide
         * @removed
         */
        @NonNull Builder setPresentationSpecs(
                @NonNull List<android.view.inline.InlinePresentationSpec> specs) {
            ((Builder) this).checkNotUsed();
            ((Builder) this).mBuilderFieldsSet |= 0x2;
            final ArrayList<android.widget.inline.InlinePresentationSpec> convertedSpecs =
                    new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                final android.view.inline.InlinePresentationSpec currSpec = specs.get(i);
                final android.widget.inline.InlinePresentationSpec.Builder builder =
                        new android.widget.inline.InlinePresentationSpec.Builder(
                                currSpec.getMinSize(), currSpec.getMaxSize());
                final Bundle style = currSpec.getStyle();
                if (style != null) {
                    builder.setStyle(style);
                }
                convertedSpecs.add(builder.build());
            }
            ((Builder) this).mInlinePresentationSpecs = convertedSpecs;
            return ((Builder) this);
        }

        /**
         * @see #setPresentationSpecs
         *
         * @hide
         * @removed
         */
        public @NonNull Builder addPresentationSpecs(
                @NonNull android.view.inline.InlinePresentationSpec value) {
            if (((Builder) this).mInlinePresentationSpecs == null) {
                setPresentationSpecs(new ArrayList<>());
            }

            final android.widget.inline.InlinePresentationSpec.Builder builder =
                    new android.widget.inline.InlinePresentationSpec.Builder(
                            value.getMinSize(), value.getMaxSize());
            final Bundle style = value.getStyle();
            if (style != null) {
                builder.setStyle(style);
            }

            ((Builder) this).mInlinePresentationSpecs.add(builder.build());
            return ((Builder) this);
        }

        abstract Builder setInlinePresentationSpecs(
                @NonNull List<android.widget.inline.InlinePresentationSpec> specs);

        abstract Builder setHostPackageName(@Nullable String value);

        abstract Builder setHostInputToken(IBinder hostInputToken);

        abstract Builder setHostDisplayId(int value);
    }



    // Code below generated by codegen v1.0.15.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/inputmethod/InlineSuggestionsRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ InlineSuggestionsRequest(
            int maxSuggestionCount,
            @NonNull List<InlinePresentationSpec> inlinePresentationSpecs,
            @NonNull String hostPackageName,
            @NonNull LocaleList supportedLocales,
            @Nullable Bundle extras,
            @Nullable IBinder hostInputToken,
            int hostDisplayId) {
        this.mMaxSuggestionCount = maxSuggestionCount;
        this.mInlinePresentationSpecs = inlinePresentationSpecs;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInlinePresentationSpecs);
        this.mHostPackageName = hostPackageName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHostPackageName);
        this.mSupportedLocales = supportedLocales;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSupportedLocales);
        this.mExtras = extras;
        this.mHostInputToken = hostInputToken;
        this.mHostDisplayId = hostDisplayId;

        onConstructed();
    }

    /**
     * Max number of suggestions expected from the response. It must be a positive value.
     * Defaults to {@code SUGGESTION_COUNT_UNLIMITED} if not set.
     */
    @DataClass.Generated.Member
    public int getMaxSuggestionCount() {
        return mMaxSuggestionCount;
    }

    /**
     * The {@link InlinePresentationSpec} for each suggestion in the response. If the max suggestion
     * count is larger than the number of specs in the list, then the last spec is used for the
     * remainder of the suggestions. The list should not be empty.
     */
    @DataClass.Generated.Member
    public @NonNull List<InlinePresentationSpec> getInlinePresentationSpecs() {
        return mInlinePresentationSpecs;
    }

    /**
     * The package name of the app that requests for the inline suggestions and will host the
     * embedded suggestion views. The app does not have to set the value for the field because
     * it'll be set by the system for safety reasons.
     */
    @DataClass.Generated.Member
    public @NonNull String getHostPackageName() {
        return mHostPackageName;
    }

    /**
     * The IME provided locales for the request. If non-empty, the inline suggestions should
     * return languages from the supported locales. If not provided, it'll default to system locale.
     */
    @DataClass.Generated.Member
    public @NonNull LocaleList getSupportedLocales() {
        return mSupportedLocales;
    }

    /**
     * The extras state propagated from the IME to pass extra data.
     */
    @DataClass.Generated.Member
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    /**
     * The host input token of the IME that made the request. This will be set by the system for
     * safety reasons.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable IBinder getHostInputToken() {
        return mHostInputToken;
    }

    /**
     * The host display id of the IME that made the request. This will be set by the system for
     * safety reasons.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public int getHostDisplayId() {
        return mHostDisplayId;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "InlineSuggestionsRequest { " +
                "maxSuggestionCount = " + mMaxSuggestionCount + ", " +
                "inlinePresentationSpecs = " + mInlinePresentationSpecs + ", " +
                "hostPackageName = " + mHostPackageName + ", " +
                "supportedLocales = " + mSupportedLocales + ", " +
                "extras = " + mExtras + ", " +
                "hostInputToken = " + mHostInputToken + ", " +
                "hostDisplayId = " + mHostDisplayId +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(InlineSuggestionsRequest other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        InlineSuggestionsRequest that = (InlineSuggestionsRequest) o;
        //noinspection PointlessBooleanExpression
        return true
                && mMaxSuggestionCount == that.mMaxSuggestionCount
                && java.util.Objects.equals(mInlinePresentationSpecs, that.mInlinePresentationSpecs)
                && java.util.Objects.equals(mHostPackageName, that.mHostPackageName)
                && java.util.Objects.equals(mSupportedLocales, that.mSupportedLocales)
                && java.util.Objects.equals(mExtras, that.mExtras)
                && java.util.Objects.equals(mHostInputToken, that.mHostInputToken)
                && mHostDisplayId == that.mHostDisplayId;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mMaxSuggestionCount;
        _hash = 31 * _hash + java.util.Objects.hashCode(mInlinePresentationSpecs);
        _hash = 31 * _hash + java.util.Objects.hashCode(mHostPackageName);
        _hash = 31 * _hash + java.util.Objects.hashCode(mSupportedLocales);
        _hash = 31 * _hash + java.util.Objects.hashCode(mExtras);
        _hash = 31 * _hash + java.util.Objects.hashCode(mHostInputToken);
        _hash = 31 * _hash + mHostDisplayId;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mExtras != null) flg |= 0x10;
        if (mHostInputToken != null) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeInt(mMaxSuggestionCount);
        dest.writeParcelableList(mInlinePresentationSpecs, flags);
        dest.writeString(mHostPackageName);
        dest.writeTypedObject(mSupportedLocales, flags);
        if (mExtras != null) dest.writeBundle(mExtras);
        parcelHostInputToken(dest, flags);
        dest.writeInt(mHostDisplayId);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ InlineSuggestionsRequest(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int maxSuggestionCount = in.readInt();
        List<InlinePresentationSpec> inlinePresentationSpecs = new ArrayList<>();
        in.readParcelableList(inlinePresentationSpecs, InlinePresentationSpec.class.getClassLoader());
        String hostPackageName = in.readString();
        LocaleList supportedLocales = (LocaleList) in.readTypedObject(LocaleList.CREATOR);
        Bundle extras = (flg & 0x10) == 0 ? null : in.readBundle();
        IBinder hostInputToken = unparcelHostInputToken(in);
        int hostDisplayId = in.readInt();

        this.mMaxSuggestionCount = maxSuggestionCount;
        this.mInlinePresentationSpecs = inlinePresentationSpecs;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInlinePresentationSpecs);
        this.mHostPackageName = hostPackageName;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mHostPackageName);
        this.mSupportedLocales = supportedLocales;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSupportedLocales);
        this.mExtras = extras;
        this.mHostInputToken = hostInputToken;
        this.mHostDisplayId = hostDisplayId;

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<InlineSuggestionsRequest> CREATOR
            = new Parcelable.Creator<InlineSuggestionsRequest>() {
        @Override
        public InlineSuggestionsRequest[] newArray(int size) {
            return new InlineSuggestionsRequest[size];
        }

        @Override
        public InlineSuggestionsRequest createFromParcel(@NonNull Parcel in) {
            return new InlineSuggestionsRequest(in);
        }
    };

    /**
     * A builder for {@link InlineSuggestionsRequest}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder extends BaseBuilder {

        private int mMaxSuggestionCount;
        private @NonNull List<InlinePresentationSpec> mInlinePresentationSpecs;
        private @NonNull String mHostPackageName;
        private @NonNull LocaleList mSupportedLocales;
        private @Nullable Bundle mExtras;
        private @Nullable IBinder mHostInputToken;
        private int mHostDisplayId;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param inlinePresentationSpecs
         *   The {@link InlinePresentationSpec} for each suggestion in the response. If the max suggestion
         *   count is larger than the number of specs in the list, then the last spec is used for the
         *   remainder of the suggestions. The list should not be empty.
         */
        public Builder(
                @NonNull List<InlinePresentationSpec> inlinePresentationSpecs) {
            mInlinePresentationSpecs = inlinePresentationSpecs;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mInlinePresentationSpecs);
        }

        /**
         * Max number of suggestions expected from the response. It must be a positive value.
         * Defaults to {@code SUGGESTION_COUNT_UNLIMITED} if not set.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setMaxSuggestionCount(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mMaxSuggestionCount = value;
            return this;
        }

        /**
         * The {@link InlinePresentationSpec} for each suggestion in the response. If the max suggestion
         * count is larger than the number of specs in the list, then the last spec is used for the
         * remainder of the suggestions. The list should not be empty.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setInlinePresentationSpecs(@NonNull List<InlinePresentationSpec> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mInlinePresentationSpecs = value;
            return this;
        }

        /** @see #setInlinePresentationSpecs */
        @DataClass.Generated.Member
        public @NonNull Builder addInlinePresentationSpecs(@NonNull InlinePresentationSpec value) {
            // You can refine this method's name by providing item's singular name, e.g.:
            // @DataClass.PluralOf("item")) mItems = ...

            if (mInlinePresentationSpecs == null) setInlinePresentationSpecs(new ArrayList<>());
            mInlinePresentationSpecs.add(value);
            return this;
        }

        /**
         * The package name of the app that requests for the inline suggestions and will host the
         * embedded suggestion views. The app does not have to set the value for the field because
         * it'll be set by the system for safety reasons.
         */
        @DataClass.Generated.Member
        @Override
        @NonNull Builder setHostPackageName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mHostPackageName = value;
            return this;
        }

        /**
         * The IME provided locales for the request. If non-empty, the inline suggestions should
         * return languages from the supported locales. If not provided, it'll default to system locale.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setSupportedLocales(@NonNull LocaleList value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mSupportedLocales = value;
            return this;
        }

        /**
         * The extras state propagated from the IME to pass extra data.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setExtras(@NonNull Bundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mExtras = value;
            return this;
        }

        /**
         * The host input token of the IME that made the request. This will be set by the system for
         * safety reasons.
         *
         * @hide
         */
        @DataClass.Generated.Member
        @Override
        @NonNull Builder setHostInputToken(@NonNull IBinder value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mHostInputToken = value;
            return this;
        }

        /**
         * The host display id of the IME that made the request. This will be set by the system for
         * safety reasons.
         *
         * @hide
         */
        @DataClass.Generated.Member
        @Override
        @NonNull Builder setHostDisplayId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mHostDisplayId = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull InlineSuggestionsRequest build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mMaxSuggestionCount = defaultMaxSuggestionCount();
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mHostPackageName = defaultHostPackageName();
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mSupportedLocales = defaultSupportedLocales();
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mExtras = defaultExtras();
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mHostInputToken = defaultHostInputToken();
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mHostDisplayId = defaultHostDisplayId();
            }
            InlineSuggestionsRequest o = new InlineSuggestionsRequest(
                    mMaxSuggestionCount,
                    mInlinePresentationSpecs,
                    mHostPackageName,
                    mSupportedLocales,
                    mExtras,
                    mHostInputToken,
                    mHostDisplayId);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x80) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1585528160885L,
            codegenVersion = "1.0.15",
            sourceFile = "frameworks/base/core/java/android/view/inputmethod/InlineSuggestionsRequest.java",
            inputSignatures = "public static final  int SUGGESTION_COUNT_UNLIMITED\nprivate final  int mMaxSuggestionCount\nprivate final @android.annotation.NonNull java.util.List<android.widget.inline.InlinePresentationSpec> mInlinePresentationSpecs\nprivate @android.annotation.NonNull java.lang.String mHostPackageName\nprivate @android.annotation.NonNull android.os.LocaleList mSupportedLocales\nprivate @android.annotation.Nullable android.os.Bundle mExtras\nprivate @android.annotation.Nullable android.os.IBinder mHostInputToken\nprivate  int mHostDisplayId\npublic @android.annotation.NonNull java.util.List<android.view.inline.InlinePresentationSpec> getPresentationSpecs()\npublic  void setHostInputToken(android.os.IBinder)\nprivate  void parcelHostInputToken(android.os.Parcel,int)\nprivate @android.annotation.Nullable android.os.IBinder unparcelHostInputToken(android.os.Parcel)\npublic  void setHostDisplayId(int)\nprivate  void onConstructed()\nprivate static  int defaultMaxSuggestionCount()\nprivate static  java.lang.String defaultHostPackageName()\nprivate static  android.os.LocaleList defaultSupportedLocales()\nprivate static @android.annotation.Nullable android.os.IBinder defaultHostInputToken()\nprivate static @android.annotation.Nullable int defaultHostDisplayId()\nprivate static @android.annotation.Nullable android.os.Bundle defaultExtras()\nclass InlineSuggestionsRequest extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genEqualsHashCode=true, genToString=true, genBuilder=true)\n @android.annotation.NonNull android.view.inputmethod.InlineSuggestionsRequest.Builder setPresentationSpecs(java.util.List<android.view.inline.InlinePresentationSpec>)\npublic @android.annotation.NonNull android.view.inputmethod.InlineSuggestionsRequest.Builder addPresentationSpecs(android.view.inline.InlinePresentationSpec)\nabstract  android.view.inputmethod.InlineSuggestionsRequest.Builder setInlinePresentationSpecs(java.util.List<android.widget.inline.InlinePresentationSpec>)\nabstract  android.view.inputmethod.InlineSuggestionsRequest.Builder setHostPackageName(java.lang.String)\nabstract  android.view.inputmethod.InlineSuggestionsRequest.Builder setHostInputToken(android.os.IBinder)\nabstract  android.view.inputmethod.InlineSuggestionsRequest.Builder setHostDisplayId(int)\nclass BaseBuilder extends java.lang.Object implements []")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
