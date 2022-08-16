/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.autofill;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;
import android.view.inputmethod.InlineSuggestionsRequest;

import com.android.internal.util.DataClass;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a request to an autofill service
 * to interpret the screen and provide information to the system which views are
 * interesting for saving and what are the possible ways to fill the inputs on
 * the screen if applicable.
 *
 * @see AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)
 */
@DataClass(
        genToString = true,
        genHiddenConstructor = true,
        genHiddenConstDefs = true)
public final class FillRequest implements Parcelable {

    /**
     * Indicates autofill was explicitly requested by the user.
     *
     * <p>Users typically make an explicit request to autofill a screen in two situations:
     * <ul>
     *   <li>The app disabled autofill (using {@link View#setImportantForAutofill(int)}.
     *   <li>The service could not figure out how to autofill a screen (but the user knows the
     *       service has data for that app).
     * </ul>
     *
     * <p>This flag is particularly useful for the second case. For example, the service could offer
     * a complex UI where the user can map which screen views belong to each user data, or it could
     * offer a simpler UI where the user picks the data for just the view used to trigger the
     * request (that would be the view whose
     * {@link android.app.assist.AssistStructure.ViewNode#isFocused()} method returns {@code true}).
     *
     * <p>An explicit autofill request is triggered when the
     * {@link android.view.autofill.AutofillManager#requestAutofill(View)} or
     * {@link android.view.autofill.AutofillManager#requestAutofill(View, int, android.graphics.Rect)}
     * is called. For example, standard {@link android.widget.TextView} views show an
     * {@code AUTOFILL} option in the overflow menu that triggers such request.
     */
    public static final @RequestFlags int FLAG_MANUAL_REQUEST = 0x1;

    /**
     * Indicates this request was made using
     * <a href="AutofillService.html#CompatibilityMode">compatibility mode</a>.
     */
    public static final @RequestFlags int FLAG_COMPATIBILITY_MODE_REQUEST = 0x2;

    /**
     * Indicates the request came from a password field.
     *
     * (TODO: b/141703197) Temporary fix for augmented autofill showing passwords.
     *
     * @hide
     */
    public static final @RequestFlags int FLAG_PASSWORD_INPUT_TYPE = 0x4;

    /**
     * Indicates the view was not focused.
     *
     * <p><b>Note:</b> Defines the flag value to 0x10, because the flag value 0x08 has been defined
     * in {@link AutofillManager}.</p>
     *
     * @hide
     */
    public static final @RequestFlags int FLAG_VIEW_NOT_FOCUSED = 0x10;

    // The flag value 0x20 has been used.

    /**
     * Indicates the request supports fill dialog presentation for the fields, the
     * system will send the request when the activity just started.
     */
    public static final @RequestFlags int FLAG_SUPPORTS_FILL_DIALOG = 0x40;

    /**
     * Indicates the ime is showing while request coming.
     * @hide
     */
    public static final @RequestFlags int FLAG_IME_SHOWING = 0x80;

    /** @hide */
    public static final int INVALID_REQUEST_ID = Integer.MIN_VALUE;

    /**
     * Gets the unique id of this request.
     */
    private final int mId;

    /**
     * Gets the contexts associated with each previous fill request.
     *
     * <p><b>Note:</b> Starting on Android {@link android.os.Build.VERSION_CODES#Q}, it could also
     * include contexts from requests whose {@link SaveInfo} had the
     * {@link SaveInfo#FLAG_DELAY_SAVE} flag.
     */
    private final @NonNull List<FillContext> mFillContexts;

    /**
     * Gets the latest client state bundle set by the service in a
     * {@link FillResponse.Builder#setClientState(Bundle) fill response}.
     *
     * <p><b>Note:</b> Prior to Android {@link android.os.Build.VERSION_CODES#P}, only client state
     * bundles set by {@link FillResponse.Builder#setClientState(Bundle)} were considered. On
     * Android {@link android.os.Build.VERSION_CODES#P} and higher, bundles set in the result of
     * an authenticated request through the
     * {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE} extra are
     * also considered (and take precedence when set).
     *
     * @return The client state.
     */
    private final @Nullable Bundle mClientState;

    /**
     * Gets the flags associated with this request.
     *
     * @return any combination of {@link #FLAG_MANUAL_REQUEST},
     *         {@link #FLAG_SUPPORTS_FILL_DIALOG} and
     *         {@link #FLAG_COMPATIBILITY_MODE_REQUEST}.
     *
     */
    private final @RequestFlags int mFlags;

    /**
     * Gets the {@link InlineSuggestionsRequest} associated
     * with this request.
     *
     * <p>Autofill Framework will send a {@code @non-null} {@link InlineSuggestionsRequest} if
     * currently inline suggestions are supported and can be displayed. If the Autofill service
     * wants to show inline suggestions, they may return {@link Dataset} with valid
     * {@link InlinePresentation}.</p>
     *
     * <p>The Autofill Service must set supportsInlineSuggestions in its XML to enable support
     * for inline suggestions.</p>
     *
     * @return the suggestionspec
     */
    private final @Nullable InlineSuggestionsRequest mInlineSuggestionsRequest;

    /**
     * Gets the {@link IntentSender} to send a delayed fill response.
     *
     * <p>The autofill service must first indicate that it wants to return a delayed
     * {@link FillResponse} by setting {@link FillResponse#FLAG_DELAY_FILL} in a successful
     * fill response. Then it can use this IntentSender to send an Intent with extra
     * {@link AutofillService#EXTRA_FILL_RESPONSE} with the delayed response.</p>
     *
     * <p>Note that this may be null if a delayed fill response is not supported for
     * this fill request.</p>
     */
    private final @Nullable IntentSender mDelayedFillIntentSender;

    private void onConstructed() {
        Preconditions.checkCollectionElementsNotNull(mFillContexts, "contexts");
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/./frameworks/base/core/java/android/service/autofill/FillRequest.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(flag = true, prefix = "FLAG_", value = {
        FLAG_MANUAL_REQUEST,
        FLAG_COMPATIBILITY_MODE_REQUEST,
        FLAG_PASSWORD_INPUT_TYPE,
        FLAG_VIEW_NOT_FOCUSED,
        FLAG_SUPPORTS_FILL_DIALOG,
        FLAG_IME_SHOWING
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface RequestFlags {}

    /** @hide */
    @DataClass.Generated.Member
    public static String requestFlagsToString(@RequestFlags int value) {
        return com.android.internal.util.BitUtils.flagsToString(
                value, FillRequest::singleRequestFlagsToString);
    }

    @DataClass.Generated.Member
    static String singleRequestFlagsToString(@RequestFlags int value) {
        switch (value) {
            case FLAG_MANUAL_REQUEST:
                    return "FLAG_MANUAL_REQUEST";
            case FLAG_COMPATIBILITY_MODE_REQUEST:
                    return "FLAG_COMPATIBILITY_MODE_REQUEST";
            case FLAG_PASSWORD_INPUT_TYPE:
                    return "FLAG_PASSWORD_INPUT_TYPE";
            case FLAG_VIEW_NOT_FOCUSED:
                    return "FLAG_VIEW_NOT_FOCUSED";
            case FLAG_SUPPORTS_FILL_DIALOG:
                    return "FLAG_SUPPORTS_FILL_DIALOG";
            case FLAG_IME_SHOWING:
                    return "FLAG_IME_SHOWING";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new FillRequest.
     *
     * @param id
     *   Gets the unique id of this request.
     * @param fillContexts
     *   Gets the contexts associated with each previous fill request.
     *
     *   <p><b>Note:</b> Starting on Android {@link android.os.Build.VERSION_CODES#Q}, it could also
     *   include contexts from requests whose {@link SaveInfo} had the
     *   {@link SaveInfo#FLAG_DELAY_SAVE} flag.
     * @param clientState
     *   Gets the latest client state bundle set by the service in a
     *   {@link FillResponse.Builder#setClientState(Bundle) fill response}.
     *
     *   <p><b>Note:</b> Prior to Android {@link android.os.Build.VERSION_CODES#P}, only client state
     *   bundles set by {@link FillResponse.Builder#setClientState(Bundle)} were considered. On
     *   Android {@link android.os.Build.VERSION_CODES#P} and higher, bundles set in the result of
     *   an authenticated request through the
     *   {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE} extra are
     *   also considered (and take precedence when set).
     * @param flags
     *   Gets the flags associated with this request.
     *
     *   @return any combination of {@link #FLAG_MANUAL_REQUEST},
     *           {@link #FLAG_SUPPORTS_FILL_DIALOG} and
     *           {@link #FLAG_COMPATIBILITY_MODE_REQUEST}.
     * @param inlineSuggestionsRequest
     *   Gets the {@link InlineSuggestionsRequest} associated
     *   with this request.
     *
     *   <p>Autofill Framework will send a {@code @non-null} {@link InlineSuggestionsRequest} if
     *   currently inline suggestions are supported and can be displayed. If the Autofill service
     *   wants to show inline suggestions, they may return {@link Dataset} with valid
     *   {@link InlinePresentation}.</p>
     *
     *   <p>The Autofill Service must set supportsInlineSuggestions in its XML to enable support
     *   for inline suggestions.</p>
     * @param delayedFillIntentSender
     *   Gets the {@link IntentSender} to send a delayed fill response.
     *
     *   <p>The autofill service must first indicate that it wants to return a delayed
     *   {@link FillResponse} by setting {@link FillResponse#FLAG_DELAY_FILL} in a successful
     *   fill response. Then it can use this IntentSender to send an Intent with extra
     *   {@link AutofillService#EXTRA_FILL_RESPONSE} with the delayed response.</p>
     *
     *   <p>Note that this may be null if a delayed fill response is not supported for
     *   this fill request.</p>
     * @hide
     */
    @DataClass.Generated.Member
    public FillRequest(
            int id,
            @NonNull List<FillContext> fillContexts,
            @Nullable Bundle clientState,
            @RequestFlags int flags,
            @Nullable InlineSuggestionsRequest inlineSuggestionsRequest,
            @Nullable IntentSender delayedFillIntentSender) {
        this.mId = id;
        this.mFillContexts = fillContexts;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFillContexts);
        this.mClientState = clientState;
        this.mFlags = flags;

        Preconditions.checkFlagsArgument(
                mFlags,
                FLAG_MANUAL_REQUEST
                        | FLAG_COMPATIBILITY_MODE_REQUEST
                        | FLAG_PASSWORD_INPUT_TYPE
                        | FLAG_VIEW_NOT_FOCUSED
                        | FLAG_SUPPORTS_FILL_DIALOG
                        | FLAG_IME_SHOWING);
        this.mInlineSuggestionsRequest = inlineSuggestionsRequest;
        this.mDelayedFillIntentSender = delayedFillIntentSender;

        onConstructed();
    }

    /**
     * Gets the unique id of this request.
     */
    @DataClass.Generated.Member
    public int getId() {
        return mId;
    }

    /**
     * Gets the contexts associated with each previous fill request.
     *
     * <p><b>Note:</b> Starting on Android {@link android.os.Build.VERSION_CODES#Q}, it could also
     * include contexts from requests whose {@link SaveInfo} had the
     * {@link SaveInfo#FLAG_DELAY_SAVE} flag.
     */
    @DataClass.Generated.Member
    public @NonNull List<FillContext> getFillContexts() {
        return mFillContexts;
    }

    /**
     * Gets the latest client state bundle set by the service in a
     * {@link FillResponse.Builder#setClientState(Bundle) fill response}.
     *
     * <p><b>Note:</b> Prior to Android {@link android.os.Build.VERSION_CODES#P}, only client state
     * bundles set by {@link FillResponse.Builder#setClientState(Bundle)} were considered. On
     * Android {@link android.os.Build.VERSION_CODES#P} and higher, bundles set in the result of
     * an authenticated request through the
     * {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE} extra are
     * also considered (and take precedence when set).
     *
     * @return The client state.
     */
    @DataClass.Generated.Member
    public @Nullable Bundle getClientState() {
        return mClientState;
    }

    /**
     * Gets the flags associated with this request.
     *
     * @return any combination of {@link #FLAG_MANUAL_REQUEST},
     *         {@link #FLAG_SUPPORTS_FILL_DIALOG} and
     *         {@link #FLAG_COMPATIBILITY_MODE_REQUEST}.
     */
    @DataClass.Generated.Member
    public @RequestFlags int getFlags() {
        return mFlags;
    }

    /**
     * Gets the {@link InlineSuggestionsRequest} associated
     * with this request.
     *
     * <p>Autofill Framework will send a {@code @non-null} {@link InlineSuggestionsRequest} if
     * currently inline suggestions are supported and can be displayed. If the Autofill service
     * wants to show inline suggestions, they may return {@link Dataset} with valid
     * {@link InlinePresentation}.</p>
     *
     * <p>The Autofill Service must set supportsInlineSuggestions in its XML to enable support
     * for inline suggestions.</p>
     *
     * @return the suggestionspec
     */
    @DataClass.Generated.Member
    public @Nullable InlineSuggestionsRequest getInlineSuggestionsRequest() {
        return mInlineSuggestionsRequest;
    }

    /**
     * Gets the {@link IntentSender} to send a delayed fill response.
     *
     * <p>The autofill service must first indicate that it wants to return a delayed
     * {@link FillResponse} by setting {@link FillResponse#FLAG_DELAY_FILL} in a successful
     * fill response. Then it can use this IntentSender to send an Intent with extra
     * {@link AutofillService#EXTRA_FILL_RESPONSE} with the delayed response.</p>
     *
     * <p>Note that this may be null if a delayed fill response is not supported for
     * this fill request.</p>
     */
    @DataClass.Generated.Member
    public @Nullable IntentSender getDelayedFillIntentSender() {
        return mDelayedFillIntentSender;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "FillRequest { " +
                "id = " + mId + ", " +
                "fillContexts = " + mFillContexts + ", " +
                "clientState = " + mClientState + ", " +
                "flags = " + requestFlagsToString(mFlags) + ", " +
                "inlineSuggestionsRequest = " + mInlineSuggestionsRequest + ", " +
                "delayedFillIntentSender = " + mDelayedFillIntentSender +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mClientState != null) flg |= 0x4;
        if (mInlineSuggestionsRequest != null) flg |= 0x10;
        if (mDelayedFillIntentSender != null) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeInt(mId);
        dest.writeParcelableList(mFillContexts, flags);
        if (mClientState != null) dest.writeBundle(mClientState);
        dest.writeInt(mFlags);
        if (mInlineSuggestionsRequest != null) dest.writeTypedObject(mInlineSuggestionsRequest, flags);
        if (mDelayedFillIntentSender != null) dest.writeTypedObject(mDelayedFillIntentSender, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ FillRequest(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int id = in.readInt();
        List<FillContext> fillContexts = new ArrayList<>();
        in.readParcelableList(fillContexts, FillContext.class.getClassLoader());
        Bundle clientState = (flg & 0x4) == 0 ? null : in.readBundle();
        int flags = in.readInt();
        InlineSuggestionsRequest inlineSuggestionsRequest = (flg & 0x10) == 0 ? null : (InlineSuggestionsRequest) in.readTypedObject(InlineSuggestionsRequest.CREATOR);
        IntentSender delayedFillIntentSender = (flg & 0x20) == 0 ? null : (IntentSender) in.readTypedObject(IntentSender.CREATOR);

        this.mId = id;
        this.mFillContexts = fillContexts;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFillContexts);
        this.mClientState = clientState;
        this.mFlags = flags;

        Preconditions.checkFlagsArgument(
                mFlags,
                FLAG_MANUAL_REQUEST
                        | FLAG_COMPATIBILITY_MODE_REQUEST
                        | FLAG_PASSWORD_INPUT_TYPE
                        | FLAG_VIEW_NOT_FOCUSED
                        | FLAG_SUPPORTS_FILL_DIALOG
                        | FLAG_IME_SHOWING);
        this.mInlineSuggestionsRequest = inlineSuggestionsRequest;
        this.mDelayedFillIntentSender = delayedFillIntentSender;

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<FillRequest> CREATOR
            = new Parcelable.Creator<FillRequest>() {
        @Override
        public FillRequest[] newArray(int size) {
            return new FillRequest[size];
        }

        @Override
        public FillRequest createFromParcel(@NonNull Parcel in) {
            return new FillRequest(in);
        }
    };

    @DataClass.Generated(
            time = 1647856966565L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/service/autofill/FillRequest.java",
            inputSignatures = "public static final @android.service.autofill.FillRequest.RequestFlags int FLAG_MANUAL_REQUEST\npublic static final @android.service.autofill.FillRequest.RequestFlags int FLAG_COMPATIBILITY_MODE_REQUEST\npublic static final @android.service.autofill.FillRequest.RequestFlags int FLAG_PASSWORD_INPUT_TYPE\npublic static final @android.service.autofill.FillRequest.RequestFlags int FLAG_VIEW_NOT_FOCUSED\npublic static final @android.service.autofill.FillRequest.RequestFlags int FLAG_SUPPORTS_FILL_DIALOG\npublic static final @android.service.autofill.FillRequest.RequestFlags int FLAG_IME_SHOWING\npublic static final  int INVALID_REQUEST_ID\nprivate final  int mId\nprivate final @android.annotation.NonNull java.util.List<android.service.autofill.FillContext> mFillContexts\nprivate final @android.annotation.Nullable android.os.Bundle mClientState\nprivate final @android.service.autofill.FillRequest.RequestFlags int mFlags\nprivate final @android.annotation.Nullable android.view.inputmethod.InlineSuggestionsRequest mInlineSuggestionsRequest\nprivate final @android.annotation.Nullable android.content.IntentSender mDelayedFillIntentSender\nprivate  void onConstructed()\nclass FillRequest extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genHiddenConstructor=true, genHiddenConstDefs=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
