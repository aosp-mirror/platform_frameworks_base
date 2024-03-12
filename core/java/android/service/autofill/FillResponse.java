/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.service.autofill.AutofillServiceHelper.assertValid;
import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;
import static android.view.autofill.Helper.sDebug;

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.assist.classification.FieldClassification;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Response for an {@link
 * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}.
 *
 * <p>See the main {@link AutofillService} documentation for more details and examples.
 */
public final class FillResponse implements Parcelable {

    /**
     * Flag used to generate {@link FillEventHistory.Event events} of type
     * {@link FillEventHistory.Event#TYPE_CONTEXT_COMMITTED}&mdash;if this flag is not passed to
     * {@link Builder#setFlags(int)}, these events are not generated.
     */
    public static final int FLAG_TRACK_CONTEXT_COMMITED = 0x1;

    /**
     * Flag used to change the behavior of {@link FillResponse.Builder#disableAutofill(long)}&mdash;
     * when this flag is passed to {@link Builder#setFlags(int)}, autofill is disabled only for the
     * activiy that generated the {@link FillRequest}, not the whole app.
     */
    public static final int FLAG_DISABLE_ACTIVITY_ONLY = 0x2;

    /**
     * Flag used to request to wait for a delayed fill from the remote Autofill service if it's
     * passed to {@link Builder#setFlags(int)}.
     *
     * <p>Some datasets (i.e. OTP) take time to produce. This flags allows remote service to send
     * a {@link FillResponse} to the latest {@link FillRequest} via
     * {@link FillRequest#getDelayedFillIntentSender()} even if the original {@link FillCallback}
     * has timed out.
     */
    public static final int FLAG_DELAY_FILL = 0x4;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_TRACK_CONTEXT_COMMITED,
            FLAG_DISABLE_ACTIVITY_ONLY,
            FLAG_DELAY_FILL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface FillResponseFlags {}

    private final @Nullable ParceledListSlice<Dataset> mDatasets;
    private final @Nullable SaveInfo mSaveInfo;
    private final @Nullable Bundle mClientState;
    private final @Nullable RemoteViews mPresentation;
    private final @Nullable InlinePresentation mInlinePresentation;
    private final @Nullable InlinePresentation mInlineTooltipPresentation;
    private final @Nullable RemoteViews mDialogPresentation;
    private final @Nullable RemoteViews mDialogHeader;
    private final @Nullable RemoteViews mHeader;
    private final @Nullable RemoteViews mFooter;
    private final @Nullable IntentSender mAuthentication;
    private final @Nullable AutofillId[] mAuthenticationIds;
    private final @Nullable AutofillId[] mIgnoredIds;
    private final @Nullable AutofillId[] mFillDialogTriggerIds;
    private final long mDisableDuration;
    private final @Nullable AutofillId[] mFieldClassificationIds;
    private final int mFlags;
    private int mRequestId;
    private final @Nullable UserData mUserData;
    private final @Nullable int[] mCancelIds;
    private final boolean mSupportsInlineSuggestions;
    private final @DrawableRes int mIconResourceId;
    private final @StringRes int mServiceDisplayNameResourceId;
    private final boolean mShowFillDialogIcon;
    private final boolean mShowSaveDialogIcon;
    private final @Nullable FieldClassification[] mDetectedFieldTypes;

    /**
    * Creates a shollow copy of the provided FillResponse.
    *
    * @hide
    */
    public static FillResponse shallowCopy(
            FillResponse r, List<Dataset> datasets, SaveInfo saveInfo) {
        return new FillResponse(
                (datasets != null) ? new ParceledListSlice<>(datasets) : null,
                saveInfo,
                r.mClientState,
                r.mPresentation,
                r.mInlinePresentation,
                r.mInlineTooltipPresentation,
                r.mDialogPresentation,
                r.mDialogHeader,
                r.mHeader,
                r.mFooter,
                r.mAuthentication,
                r.mAuthenticationIds,
                r.mIgnoredIds,
                r.mFillDialogTriggerIds,
                r.mDisableDuration,
                r.mFieldClassificationIds,
                r.mFlags,
                r.mRequestId,
                r.mUserData,
                r.mCancelIds,
                r.mSupportsInlineSuggestions,
                r.mIconResourceId,
                r.mServiceDisplayNameResourceId,
                r.mShowFillDialogIcon,
                r.mShowSaveDialogIcon,
                r.mDetectedFieldTypes);
    }

    private FillResponse(ParceledListSlice<Dataset> datasets, SaveInfo saveInfo, Bundle clientState,
            RemoteViews presentation, InlinePresentation inlinePresentation,
            InlinePresentation inlineTooltipPresentation, RemoteViews dialogPresentation,
            RemoteViews dialogHeader, RemoteViews header, RemoteViews footer,
            IntentSender authentication, AutofillId[] authenticationIds, AutofillId[] ignoredIds,
            AutofillId[] fillDialogTriggerIds, long disableDuration,
            AutofillId[] fieldClassificationIds, int flags, int requestId, UserData userData,
            int[] cancelIds, boolean supportsInlineSuggestions, int iconResourceId,
            int serviceDisplayNameResourceId, boolean showFillDialogIcon,
            boolean showSaveDialogIcon,
            FieldClassification[] detectedFieldTypes) {
        mDatasets = datasets;
        mSaveInfo = saveInfo;
        mClientState = clientState;
        mPresentation = presentation;
        mInlinePresentation = inlinePresentation;
        mInlineTooltipPresentation = inlineTooltipPresentation;
        mDialogPresentation = dialogPresentation;
        mDialogHeader = dialogHeader;
        mHeader = header;
        mFooter = footer;
        mAuthentication = authentication;
        mAuthenticationIds = authenticationIds;
        mIgnoredIds = ignoredIds;
        mFillDialogTriggerIds = fillDialogTriggerIds;
        mDisableDuration = disableDuration;
        mFieldClassificationIds = fieldClassificationIds;
        mFlags = flags;
        mRequestId = requestId;
        mUserData = userData;
        mCancelIds = cancelIds;
        mSupportsInlineSuggestions = supportsInlineSuggestions;
        mIconResourceId = iconResourceId;
        mServiceDisplayNameResourceId = serviceDisplayNameResourceId;
        mShowFillDialogIcon = showFillDialogIcon;
        mShowSaveDialogIcon = showSaveDialogIcon;
        mDetectedFieldTypes = detectedFieldTypes;
    }

    private FillResponse(@NonNull Builder builder) {
        mDatasets = (builder.mDatasets != null) ? new ParceledListSlice<>(builder.mDatasets) : null;
        mSaveInfo = builder.mSaveInfo;
        mClientState = builder.mClientState;
        mPresentation = builder.mPresentation;
        mInlinePresentation = builder.mInlinePresentation;
        mInlineTooltipPresentation = builder.mInlineTooltipPresentation;
        mDialogPresentation = builder.mDialogPresentation;
        mDialogHeader = builder.mDialogHeader;
        mHeader = builder.mHeader;
        mFooter = builder.mFooter;
        mAuthentication = builder.mAuthentication;
        mAuthenticationIds = builder.mAuthenticationIds;
        mFillDialogTriggerIds = builder.mFillDialogTriggerIds;
        mIgnoredIds = builder.mIgnoredIds;
        mDisableDuration = builder.mDisableDuration;
        mFieldClassificationIds = builder.mFieldClassificationIds;
        mFlags = builder.mFlags;
        mRequestId = INVALID_REQUEST_ID;
        mUserData = builder.mUserData;
        mCancelIds = builder.mCancelIds;
        mSupportsInlineSuggestions = builder.mSupportsInlineSuggestions;
        mIconResourceId = builder.mIconResourceId;
        mServiceDisplayNameResourceId = builder.mServiceDisplayNameResourceId;
        mShowFillDialogIcon = builder.mShowFillDialogIcon;
        mShowSaveDialogIcon = builder.mShowSaveDialogIcon;
        mDetectedFieldTypes = builder.mDetectedFieldTypes;
    }

    /** @hide */
    @TestApi
    @NonNull
    public Set<FieldClassification> getDetectedFieldClassifications() {
        return Set.of(mDetectedFieldTypes);
    }

    /** @hide */
    public @Nullable Bundle getClientState() {
        return mClientState;
    }

    /** @hide */
    public @Nullable List<Dataset> getDatasets() {
        return (mDatasets != null) ? mDatasets.getList() : null;
    }

    /** @hide */
    public @Nullable SaveInfo getSaveInfo() {
        return mSaveInfo;
    }

    /** @hide */
    public @Nullable RemoteViews getPresentation() {
        return mPresentation;
    }

    /** @hide */
    public @Nullable InlinePresentation getInlinePresentation() {
        return mInlinePresentation;
    }

    /** @hide */
    public @Nullable InlinePresentation getInlineTooltipPresentation() {
        return mInlineTooltipPresentation;
    }

    /** @hide */
    public @Nullable RemoteViews getDialogPresentation() {
        return mDialogPresentation;
    }

    /** @hide */
    public @Nullable RemoteViews getDialogHeader() {
        return mDialogHeader;
    }

    /** @hide */
    public @Nullable RemoteViews getHeader() {
        return mHeader;
    }

    /** @hide */
    public @Nullable RemoteViews getFooter() {
        return mFooter;
    }

    /** @hide */
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /** @hide */
    public @Nullable AutofillId[] getAuthenticationIds() {
        return mAuthenticationIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getFillDialogTriggerIds() {
        return mFillDialogTriggerIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getIgnoredIds() {
        return mIgnoredIds;
    }

    /** @hide */
    public long getDisableDuration() {
        return mDisableDuration;
    }

    /** @hide */
    public @Nullable AutofillId[] getFieldClassificationIds() {
        return mFieldClassificationIds;
    }

    /** @hide */
    public @Nullable UserData getUserData() {
        return mUserData;
    }

    /** @hide */
    public @DrawableRes int getIconResourceId() {
        return mIconResourceId;
    }

    /** @hide */
    public @StringRes int getServiceDisplayNameResourceId() {
        return mServiceDisplayNameResourceId;
    }

    /** @hide */
    public boolean getShowFillDialogIcon() {
        return mShowFillDialogIcon;
    }

    /** @hide */
    public boolean getShowSaveDialogIcon() {
        return mShowSaveDialogIcon;
    }

    /** @hide */
    @TestApi
    public int getFlags() {
        return mFlags;
    }

    /**
     * Associates a {@link FillResponse} to a request.
     *
     * <p>Set inside of the {@link FillCallback} code, not the {@link AutofillService}.
     *
     * @param requestId The id of the request to associate the response to.
     *
     * @hide
     */
    public void setRequestId(int requestId) {
        mRequestId = requestId;
    }

    /** @hide */
    public int getRequestId() {
        return mRequestId;
    }

    /** @hide */
    @Nullable
    public int[] getCancelIds() {
        return mCancelIds;
    }

    /** @hide */
    public boolean supportsInlineSuggestions() {
        return mSupportsInlineSuggestions;
    }

    /**
     * Builder for {@link FillResponse} objects. You must to provide at least
     * one dataset or set an authentication intent with a presentation view.
     */
    public static final class Builder {
        private ArrayList<Dataset> mDatasets;
        private SaveInfo mSaveInfo;
        private Bundle mClientState;
        private RemoteViews mPresentation;
        private InlinePresentation mInlinePresentation;
        private InlinePresentation mInlineTooltipPresentation;
        private RemoteViews mDialogPresentation;
        private RemoteViews mDialogHeader;
        private RemoteViews mHeader;
        private RemoteViews mFooter;
        private IntentSender mAuthentication;
        private AutofillId[] mAuthenticationIds;
        private AutofillId[] mIgnoredIds;
        private long mDisableDuration;
        private AutofillId[] mFieldClassificationIds;
        private AutofillId[] mFillDialogTriggerIds;
        private int mFlags;
        private boolean mDestroyed;
        private UserData mUserData;
        private int[] mCancelIds;
        private boolean mSupportsInlineSuggestions;
        private int mIconResourceId;
        private int mServiceDisplayNameResourceId;
        private boolean mShowFillDialogIcon = true;
        private boolean mShowSaveDialogIcon = true;
        private FieldClassification[] mDetectedFieldTypes;

        /**
         * Adds a new {@link FieldClassification} to this response, to
         * help the platform provide more accurate detection results.
         *
         * Call this when a field has been detected with a type.
         *
         * Altough similiarly named with {@link #setFieldClassificationIds},
         * it provides a different functionality - setFieldClassificationIds should
         * be used when a field is only suspected to be Autofillable.
         * This method should be used when a field is certainly Autofillable
         * with a certain type.
         */
        @NonNull
        public Builder setDetectedFieldClassifications(
                @NonNull Set<FieldClassification> fieldInfos) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mDetectedFieldTypes = fieldInfos.toArray(new FieldClassification[0]);
            return this;
        }

        /**
         * Triggers a custom UI before autofilling the screen with any data set in this
         * response.
         *
         * <p><b>Note:</b> Although the name of this method suggests that it should be used just for
         * authentication flow, it can be used for other advanced flows; see {@link AutofillService}
         * for examples.
         *
         * <p>This is typically useful when a user interaction is required to unlock their
         * data vault if you encrypt the data set labels and data set data. It is recommended
         * to encrypt only the sensitive data and not the data set labels which would allow
         * auth on the data set level leading to a better user experience. Note that if you
         * use sensitive data as a label, for example an email address, then it should also
         * be encrypted. The provided {@link android.app.PendingIntent intent} must be an
         * {@link Activity} which implements your authentication flow. Also if you provide an auth
         * intent you also need to specify the presentation view to be shown in the fill UI
         * for the user to trigger your authentication flow.
         *
         * <p>When a user triggers autofill, the system launches the provided intent
         * whose extras will have the
         * {@link android.view.autofill.AutofillManager#EXTRA_ASSIST_STRUCTURE screen
         * content} and your {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE
         * client state}. Once you complete your authentication flow you should set the
         * {@link Activity} result to {@link android.app.Activity#RESULT_OK} and set the
         * {@link android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} extra
         * with the fully populated {@link FillResponse response} (or {@code null} if the screen
         * cannot be autofilled).
         *
         * <p> <b>IMPORTANT</b>: Extras must be non-null on the intent being set for Android 12
         * otherwise it will cause a crash. Do not use {@link Activity#setResult(int)}, instead use
         * {@link Activity#setResult(int, Intent) with non-null extras. Consider setting {
         * @link android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} to null or use
         * {@link Bundle#EMPTY} with {@link Intent#putExtras(Bundle)} on the intent when
         * finishing activity to avoid crash). </p>
         *
         * <p>For example, if you provided an empty {@link FillResponse response} because the
         * user's data was locked and marked that the response needs an authentication then
         * in the response returned if authentication succeeds you need to provide all
         * available data sets some of which may need to be further authenticated, for
         * example a credit card whose CVV needs to be entered.
         *
         * <p>If you provide an authentication intent you must also provide a presentation
         * which is used to visualize the response for triggering the authentication
         * flow.
         *
         * <p><b>Note:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * <p>Theme does not work with RemoteViews layout. Avoid hardcoded text color
         * or background color: Autofill on different platforms may have different themes.
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @param presentation The presentation to visualize the response.
         * @param ids id of Views that when focused will display the authentication UI.
         *
         * @return This builder.
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ul>
         *   <li>{@code ids} is {@code null}</li>
         *   <li>{@code ids} is empty</li>
         *   <li>{@code ids} contains a {@code null} element</li>
         *   <li>both {@code authentication} and {@code presentation} are {@code null}</li>
         *   <li>both {@code authentication} and {@code presentation} are non-{@code null}</li>
         * </ul>
         *
         * @throws IllegalStateException if a {@link #setHeader(RemoteViews) header} or a
         * {@link #setFooter(RemoteViews) footer} are already set for this builder.
         *
         * @see android.app.PendingIntent#getIntentSender()
         * @deprecated Use
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)}
         * instead.
         */
        @Deprecated
        @NonNull
        public Builder setAuthentication(@NonNull AutofillId[] ids,
                @Nullable IntentSender authentication, @Nullable RemoteViews presentation) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (mHeader != null || mFooter != null) {
                throw new IllegalStateException("Already called #setHeader() or #setFooter()");
            }

            if (authentication == null ^ presentation == null) {
                throw new IllegalArgumentException("authentication and presentation"
                        + " must be both non-null or null");
            }
            mAuthentication = authentication;
            mPresentation = presentation;
            mAuthenticationIds = assertValid(ids);
            return this;
        }

        /**
         * Triggers a custom UI before autofilling the screen with any data set in this
         * response.
         *
         * <p><b>Note:</b> Although the name of this method suggests that it should be used just for
         * authentication flow, it can be used for other advanced flows; see {@link AutofillService}
         * for examples.
         *
         * <p>This method is similar to
         * {@link #setAuthentication(AutofillId[], IntentSender, RemoteViews)}, but also accepts
         * an {@link InlinePresentation} presentation which is required for authenticating through
         * the inline autofill flow.
         *
         * <p><b>Note:</b> {@link #setHeader(RemoteViews)} or {@link #setFooter(RemoteViews)} does
         * not work with {@link InlinePresentation}.</p>
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @param presentation The presentation to visualize the response.
         * @param inlinePresentation The inlinePresentation to visualize the response inline.
         * @param ids id of Views that when focused will display the authentication UI.
         *
         * @return This builder.
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ul>
         *   <li>{@code ids} is {@code null}</li>
         *   <li>{@code ids} is empty</li>
         *   <li>{@code ids} contains a {@code null} element</li>
         *   <li>both {@code authentication} and {@code presentation} are {@code null}</li>
         *   <li>both {@code authentication} and {@code presentation} are non-{@code null}</li>
         *   <li>both {@code authentication} and {@code inlinePresentation} are {@code null}</li>
         *   <li>both {@code authentication} and {@code inlinePresentation} are
         *   non-{@code null}</li>
         * </ul>
         *
         * @throws IllegalStateException if a {@link #setHeader(RemoteViews) header} or a
         * {@link #setFooter(RemoteViews) footer} are already set for this builder.
         *
         * @see android.app.PendingIntent#getIntentSender()
         * @deprecated Use
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)}
         * instead.
         */
        @Deprecated
        @NonNull
        public Builder setAuthentication(@NonNull AutofillId[] ids,
                @Nullable IntentSender authentication, @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation) {
            return setAuthentication(ids, authentication, presentation, inlinePresentation, null);
        }

        /**
         * Triggers a custom UI before autofilling the screen with any data set in this
         * response.
         *
         * <p>This method like
         * {@link #setAuthentication(AutofillId[], IntentSender, RemoteViews, InlinePresentation)}
         * but allows setting an {@link InlinePresentation} for the inline suggestion tooltip.
         *
         * @deprecated Use
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)}
         * instead.
         */
        @Deprecated
        @NonNull
        public Builder setAuthentication(@SuppressLint("ArrayReturn") @NonNull AutofillId[] ids,
                @Nullable IntentSender authentication, @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation,
                @Nullable InlinePresentation inlineTooltipPresentation) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            return setAuthentication(ids, authentication, presentation,
                    inlinePresentation, inlineTooltipPresentation, null);
        }

        /**
         * Triggers a custom UI before autofilling the screen with any data set in this
         * response.
         *
         * <p><b>Note:</b> Although the name of this method suggests that it should be used just for
         * authentication flow, it can be used for other advanced flows; see {@link AutofillService}
         * for examples.
         *
         * <p>This is typically useful when a user interaction is required to unlock their
         * data vault if you encrypt the data set labels and data set data. It is recommended
         * to encrypt only the sensitive data and not the data set labels which would allow
         * auth on the data set level leading to a better user experience. Note that if you
         * use sensitive data as a label, for example an email address, then it should also
         * be encrypted. The provided {@link android.app.PendingIntent intent} must be an
         * {@link Activity} which implements your authentication flow. Also if you provide an auth
         * intent you also need to specify the presentation view to be shown in the fill UI
         * for the user to trigger your authentication flow.
         *
         * <p>When a user triggers autofill, the system launches the provided intent
         * whose extras will have the
         * {@link android.view.autofill.AutofillManager#EXTRA_ASSIST_STRUCTURE screen
         * content} and your {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE
         * client state}. Once you complete your authentication flow you should set the
         * {@link Activity} result to {@link android.app.Activity#RESULT_OK} and set the
         * {@link android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} extra
         * with the fully populated {@link FillResponse response} (or {@code null} if the screen
         * cannot be autofilled).
         *
         * <p>For example, if you provided an empty {@link FillResponse response} because the
         * user's data was locked and marked that the response needs an authentication then
         * in the response returned if authentication succeeds you need to provide all
         * available data sets some of which may need to be further authenticated, for
         * example a credit card whose CVV needs to be entered.
         *
         * <p>If you provide an authentication intent you must also provide a presentation
         * which is used to visualize the response for triggering the authentication
         * flow.
         *
         * <p><b>Note:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * <p><b>Note:</b> {@link #setHeader(RemoteViews)} or {@link #setFooter(RemoteViews)} does
         * not work with {@link InlinePresentation}.</p>
         *
         * @param ids id of Views that when focused will display the authentication UI.
         * @param authentication Intent to an activity with your authentication flow.
         * @param presentations The presentations to visualize the response.
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ul>
         *   <li>{@code ids} is {@code null}</li>
         *   <li>{@code ids} is empty</li>
         *   <li>{@code ids} contains a {@code null} element</li>
         *   <li>{@code authentication} is {@code null}, but either or both of
         *   {@code presentations.getPresentation()} and
         *   {@code presentations.getInlinePresentation()} is non-{@code null}</li>
         *   <li>{@code authentication} is non-{{@code null}, but both
         *   {@code presentations.getPresentation()} and
         *   {@code presentations.getInlinePresentation()} are {@code null}</li>
         * </ul>
         *
         * @throws IllegalStateException if a {@link #setHeader(RemoteViews) header} or a
         * {@link #setFooter(RemoteViews) footer} are already set for this builder.
         *
         * @return This builder.
         */
        @NonNull
        public Builder setAuthentication(@SuppressLint("ArrayReturn") @NonNull AutofillId[] ids,
                @Nullable IntentSender authentication,
                @Nullable Presentations presentations) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (presentations == null) {
                return setAuthentication(ids, authentication, null, null, null, null);
            }
            return setAuthentication(ids, authentication,
                    presentations.getMenuPresentation(),
                    presentations.getInlinePresentation(),
                    presentations.getInlineTooltipPresentation(),
                    presentations.getDialogPresentation());
        }

        /**
         * Triggers a custom UI before autofilling the screen with any data set in this
         * response.
         */
        @NonNull
        private Builder setAuthentication(@SuppressLint("ArrayReturn") @NonNull AutofillId[] ids,
                @Nullable IntentSender authentication, @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation,
                @Nullable InlinePresentation inlineTooltipPresentation,
                @Nullable RemoteViews dialogPresentation) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (mHeader != null || mFooter != null) {
                throw new IllegalStateException("Already called #setHeader() or #setFooter()");
            }

            if (authentication == null ^ (presentation == null && inlinePresentation == null)) {
                throw new IllegalArgumentException("authentication and presentation "
                        + "(dropdown or inline), must be both non-null or null");
            }
            mAuthentication = authentication;
            mPresentation = presentation;
            mInlinePresentation = inlinePresentation;
            mInlineTooltipPresentation = inlineTooltipPresentation;
            mDialogPresentation = dialogPresentation;
            mAuthenticationIds = assertValid(ids);
            return this;
        }

        /**
         * Specifies views that should not trigger new
         * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
         * FillCallback)} requests.
         *
         * <p>This is typically used when the service cannot autofill the view; for example, a
         * text field representing the result of a Captcha challenge.
         */
        @NonNull
        public Builder setIgnoredIds(AutofillId...ids) {
            throwIfDestroyed();
            mIgnoredIds = ids;
            return this;
        }

        /**
         * Adds a new {@link Dataset} to this response.
         *
         * <p><b>Note: </b> on Android {@link android.os.Build.VERSION_CODES#O}, the total number of
         * datasets is limited by the Binder transaction size, so it's recommended to keep it
         * small (in the range of 10-20 at most) and use pagination by adding a fake
         * {@link Dataset.Builder#setAuthentication(IntentSender) authenticated dataset} at the end
         * with a presentation string like "Next 10" that would return a new {@link FillResponse}
         * with the next 10 datasets, and so on. This limitation was lifted on
         * Android {@link android.os.Build.VERSION_CODES#O_MR1}, although the Binder transaction
         * size can still be reached if each dataset itself is too big.
         *
         * @return This builder.
         */
        @NonNull
        public Builder addDataset(@Nullable Dataset dataset) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            if (dataset == null) {
                return this;
            }
            if (mDatasets == null) {
                mDatasets = new ArrayList<>();
            }
            if (!mDatasets.add(dataset)) {
                return this;
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setDatasets(ArrayList<Dataset> dataset) {
            mDatasets = dataset;
            return this;
        }

        /**
         * Sets the {@link SaveInfo} associated with this response.
         *
         * @return This builder.
         */
        public @NonNull Builder setSaveInfo(@NonNull SaveInfo saveInfo) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mSaveInfo = saveInfo;
            return this;
        }

        /**
         * Sets a bundle with state that is passed to subsequent APIs that manipulate this response.
         *
         * <p>You can use this bundle to store intermediate state that is passed to subsequent calls
         * to {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal,
         * FillCallback)} and {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)}, and
         * you can also retrieve it by calling {@link FillEventHistory.Event#getClientState()}.
         *
         * <p>If this method is called on multiple {@link FillResponse} objects for the same
         * screen, just the latest bundle is passed back to the service.
         *
         * @param clientState The custom client state.
         * @return This builder.
         */
        @NonNull
        public Builder setClientState(@Nullable Bundle clientState) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            mClientState = clientState;
            return this;
        }

        /**
         * Sets which fields are used for
         * <a href="AutofillService.html#FieldClassification">field classification</a>
         *
         * <p><b>Note:</b> This method automatically adds the
         * {@link FillResponse#FLAG_TRACK_CONTEXT_COMMITED} to the {@link #setFlags(int) flags}.

         * @throws IllegalArgumentException is length of {@code ids} args is more than
         * {@link UserData#getMaxFieldClassificationIdsSize()}.
         * @throws IllegalStateException if {@link #build()} or {@link #disableAutofill(long)} was
         * already called.
         * @throws NullPointerException if {@code ids} or any element on it is {@code null}.
         */
        @NonNull
        public Builder setFieldClassificationIds(@NonNull AutofillId... ids) {
            throwIfDestroyed();
            throwIfDisableAutofillCalled();
            Preconditions.checkArrayElementsNotNull(ids, "ids");
            Preconditions.checkArgumentInRange(ids.length, 1,
                    UserData.getMaxFieldClassificationIdsSize(), "ids length");
            mFieldClassificationIds = ids;
            mFlags |= FLAG_TRACK_CONTEXT_COMMITED;
            return this;
        }

        /**
         * Sets flags changing the response behavior.
         *
         * @param flags a combination of {@link #FLAG_TRACK_CONTEXT_COMMITED} and
         * {@link #FLAG_DISABLE_ACTIVITY_ONLY}, or {@code 0}.
         *
         * @return This builder.
         */
        @NonNull
        public Builder setFlags(@FillResponseFlags int flags) {
            throwIfDestroyed();
            mFlags = Preconditions.checkFlagsArgument(flags,
                    FLAG_TRACK_CONTEXT_COMMITED | FLAG_DISABLE_ACTIVITY_ONLY | FLAG_DELAY_FILL);
            return this;
        }

        /**
         * Disables autofill for the app or activity.
         *
         * <p>This method is useful to optimize performance in cases where the service knows it
         * can not autofill an app&mdash;for example, when the service has a list of "denylisted"
         * apps such as office suites.
         *
         * <p>By default, it disables autofill for all activities in the app, unless the response is
         * {@link #setFlags(int) flagged} with {@link #FLAG_DISABLE_ACTIVITY_ONLY}.
         *
         * <p>Autofill for the app or activity is automatically re-enabled after any of the
         * following conditions:
         *
         * <ol>
         *   <li>{@code duration} milliseconds have passed.
         *   <li>The autofill service for the user has changed.
         *   <li>The device has rebooted.
         * </ol>
         *
         * <p><b>Note:</b> Activities that are running when autofill is re-enabled remain
         * disabled for autofill until they finish and restart.
         *
         * @param duration duration to disable autofill, in milliseconds.
         *
         * @return this builder
         *
         * @throws IllegalArgumentException if {@code duration} is not a positive number.
         * @throws IllegalStateException if either {@link #addDataset(Dataset)},
         *       {@link #setAuthentication(AutofillId[], IntentSender, Presentations)},
         *       {@link #setSaveInfo(SaveInfo)}, {@link #setClientState(Bundle)}, or
         *       {@link #setFieldClassificationIds(AutofillId...)} was already called.
         */
        @NonNull
        public Builder disableAutofill(long duration) {
            throwIfDestroyed();
            if (duration <= 0) {
                throw new IllegalArgumentException("duration must be greater than 0");
            }
            if (mAuthentication != null || mDatasets != null || mSaveInfo != null
                    || mFieldClassificationIds != null || mClientState != null) {
                throw new IllegalStateException("disableAutofill() must be the only method called");
            }

            mDisableDuration = duration;
            return this;
        }

        /**
         * Overwrites Save/Fill dialog header icon with a specific one specified by resource id.
         * The image is pulled from the package, so id should be defined in the manifest.
         *
         * @param id {@link android.graphics.drawable.Drawable} resource id of the icon to be used.
         * A value of 0 indicates to use the default header icon.
         *
         * @return this builder
         */
        @NonNull
        public Builder setIconResourceId(@DrawableRes int id) {
            throwIfDestroyed();

            mIconResourceId = id;
            return this;
        }

        /**
         * Overrides the service name in the Save Dialog header with a specific string defined
         * in the service provider's manifest.xml
         *
         * @param id Resoure Id of the custom string defined in the provider's manifest. If set
         * to 0, the default name will be used.
         *
         * @return this builder
         */
        @NonNull
        public Builder setServiceDisplayNameResourceId(@StringRes int id) {
            throwIfDestroyed();

            mServiceDisplayNameResourceId = id;
            return this;
        }

        /**
         * Whether or not to show the Autofill provider icon inside of the Fill Dialog
         *
         * @param show True to show, false to hide. Defaults to true.
         *
         * @return this builder
         */
        @NonNull
        public Builder setShowFillDialogIcon(boolean show) {
            throwIfDestroyed();

            mShowFillDialogIcon = show;
            return this;
        }

        /**
         * Whether or not to show the Autofill provider icon inside of the Save Dialog
         *
         * @param show True to show, false to hide. Defaults to true.
         *
         * @return this builder
         */
        @NonNull
        public Builder setShowSaveDialogIcon(boolean show) {
            throwIfDestroyed();

            mShowSaveDialogIcon = show;
            return this;
        }

        /**
         * Sets a header to be shown as the first element in the list of datasets.
         *
         * <p>When this method is called, you must also {@link #addDataset(Dataset) add a dataset},
         * otherwise {@link #build()} throws an {@link IllegalStateException}. Similarly, this
         * method should only be used on {@link FillResponse FillResponses} that do not require
         * authentication (as the header could have been set directly in the main presentation in
         * these cases).
         *
         * <p>Theme does not work with RemoteViews layout. Avoid hardcoded text color
         * or background color: Autofill on different platforms may have different themes.
         *
         * @param header a presentation to represent the header. This presentation is not clickable
         * &mdash;calling
         * {@link RemoteViews#setOnClickPendingIntent(int, android.app.PendingIntent)} on it would
         * have no effect.
         *
         * @return this builder
         *
         * @throws IllegalStateException if an
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)
         * authentication} was already set for this builder.
         */
        // TODO(b/69796626): make it sticky / update javadoc
        @NonNull
        public Builder setHeader(@NonNull RemoteViews header) {
            throwIfDestroyed();
            throwIfAuthenticationCalled();
            mHeader = Objects.requireNonNull(header);
            return this;
        }

        /**
         * Sets a footer to be shown as the last element in the list of datasets.
         *
         * <p>When this method is called, you must also {@link #addDataset(Dataset) add a dataset},
         * otherwise {@link #build()} throws an {@link IllegalStateException}. Similarly, this
         * method should only be used on {@link FillResponse FillResponses} that do not require
         * authentication (as the footer could have been set directly in the main presentation in
         * these cases).
         *
         * <p>Theme does not work with RemoteViews layout. Avoid hardcoded text color
         * or background color: Autofill on different platforms may have different themes.
         *
         * @param footer a presentation to represent the footer. This presentation is not clickable
         * &mdash;calling
         * {@link RemoteViews#setOnClickPendingIntent(int, android.app.PendingIntent)} on it would
         * have no effect.
         *
         * @return this builder
         *
         * @throws IllegalStateException if the FillResponse
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)
         * requires authentication}.
         */
        // TODO(b/69796626): make it sticky / update javadoc
        @NonNull
        public Builder setFooter(@NonNull RemoteViews footer) {
            throwIfDestroyed();
            throwIfAuthenticationCalled();
            mFooter = Objects.requireNonNull(footer);
            return this;
        }

        /**
         * Sets a specific {@link UserData} for field classification for this request only.
         *
         * <p>Any fields in this UserData will override corresponding fields in the generic
         * UserData object
         *
         * @return this builder
         * @throws IllegalStateException if the FillResponse
         * {@link #setAuthentication(AutofillId[], IntentSender, Presentations)
         * requires authentication}.
         */
        @NonNull
        public Builder setUserData(@NonNull UserData userData) {
            throwIfDestroyed();
            throwIfAuthenticationCalled();
            mUserData = Objects.requireNonNull(userData);
            return this;
        }

        /**
         * Sets target resource IDs of the child view in {@link RemoteViews Presentation Template}
         * which will cancel the session when clicked.
         * Those targets will be respectively applied to a child of the header, footer and
         * each {@link Dataset}.
         *
         * @param ids array of the resource id. Empty list or non-existing id has no effect.
         *
         * @return this builder
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder setPresentationCancelIds(@Nullable int[] ids) {
            throwIfDestroyed();
            mCancelIds = ids;
            return this;
        }

        /**
         * Sets the presentation of header in fill dialog UI. The header should have
         * a prompt for what datasets are shown in the dialog. If this is not set,
         * the dialog only shows your application icon.
         *
         * More details about the fill dialog, see
         * <a href="Dataset.html#FillDialogUI">fill dialog UI</a>
         */
        @NonNull
        public Builder setDialogHeader(@NonNull RemoteViews header) {
            throwIfDestroyed();
            Objects.requireNonNull(header);
            mDialogHeader = header;
            return this;
        }

        /**
         * Sets which fields are used for the fill dialog UI.
         *
         * More details about the fill dialog, see
         * <a href="Dataset.html#FillDialogUI">fill dialog UI</a>
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         * @throws NullPointerException if {@code ids} or any element on it is {@code null}.
         */
        @NonNull
        public Builder setFillDialogTriggerIds(@NonNull AutofillId... ids) {
            throwIfDestroyed();
            Preconditions.checkArrayElementsNotNull(ids, "ids");
            mFillDialogTriggerIds = ids;
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance.
         *
         * @throws IllegalStateException if any of the following conditions occur:
         * <ol>
         *   <li>{@link #build()} was already called.
         *   <li>No call was made to {@link #addDataset(Dataset)},
         *       {@link #setAuthentication(AutofillId[], IntentSender, Presentations)},
         *       {@link #setSaveInfo(SaveInfo)}, {@link #disableAutofill(long)},
         *       {@link #setClientState(Bundle)},
         *       or {@link #setFieldClassificationIds(AutofillId...)}.
         *   <li>{@link #setHeader(RemoteViews)} or {@link #setFooter(RemoteViews)} is called
         *       without any previous calls to {@link #addDataset(Dataset)}.
         * </ol>
         *
         * @return A built response.
         */
        @NonNull
        public FillResponse build() {
            throwIfDestroyed();
            if (mAuthentication == null && mDatasets == null && mSaveInfo == null
                    && mDisableDuration == 0 && mFieldClassificationIds == null
                    && mClientState == null) {
                throw new IllegalStateException("need to provide: at least one DataSet, or a "
                        + "SaveInfo, or an authentication with a presentation, "
                        + "or a FieldsDetection, or a client state, or disable autofill");
            }
            if (mDatasets == null && (mHeader != null || mFooter != null)) {
                throw new IllegalStateException(
                        "must add at least 1 dataset when using header or footer");
            }

            if (mDatasets != null) {
                for (final Dataset dataset : mDatasets) {
                    if (dataset.getFieldInlinePresentation(0) != null) {
                        mSupportsInlineSuggestions = true;
                        break;
                    }
                }
            } else if (mInlinePresentation != null) {
                mSupportsInlineSuggestions = true;
            }

            mDestroyed = true;
            return new FillResponse(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }

        private void throwIfDisableAutofillCalled() {
            if (mDisableDuration > 0) {
                throw new IllegalStateException("Already called #disableAutofill()");
            }
        }

        private void throwIfAuthenticationCalled() {
            if (mAuthentication != null) {
                throw new IllegalStateException("Already called #setAuthentication()");
            }
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        // TODO: create a dump() method instead
        final StringBuilder builder = new StringBuilder(
                "FillResponse : [mRequestId=" + mRequestId);
        if (mDatasets != null) {
            builder.append(", datasets=").append(mDatasets.getList());
        }
        if (mSaveInfo != null) {
            builder.append(", saveInfo=").append(mSaveInfo);
        }
        if (mClientState != null) {
            builder.append(", hasClientState");
        }
        if (mPresentation != null) {
            builder.append(", hasPresentation");
        }
        if (mInlinePresentation != null) {
            builder.append(", hasInlinePresentation");
        }
        if (mInlineTooltipPresentation != null) {
            builder.append(", hasInlineTooltipPresentation");
        }
        if (mDialogPresentation != null) {
            builder.append(", hasDialogPresentation");
        }
        if (mDialogHeader != null) {
            builder.append(", hasDialogHeader");
        }
        if (mHeader != null) {
            builder.append(", hasHeader");
        }
        if (mFooter != null) {
            builder.append(", hasFooter");
        }
        if (mAuthentication != null) {
            builder.append(", hasAuthentication");
        }
        if (mAuthenticationIds != null) {
            builder.append(", authenticationIds=").append(Arrays.toString(mAuthenticationIds));
        }
        if (mFillDialogTriggerIds != null) {
            builder.append(", fillDialogTriggerIds=")
                    .append(Arrays.toString(mFillDialogTriggerIds));
        }
        builder.append(", disableDuration=").append(mDisableDuration);
        if (mFlags != 0) {
            builder.append(", flags=").append(mFlags);
        }
        if (mFieldClassificationIds != null) {
            builder.append(Arrays.toString(mFieldClassificationIds));
        }
        if (mUserData != null) {
            builder.append(", userData=").append(mUserData);
        }
        if (mCancelIds != null) {
            builder.append(", mCancelIds=").append(mCancelIds.length);
        }
        builder.append(", mSupportInlinePresentations=").append(mSupportsInlineSuggestions);
        return builder.append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mDatasets, flags);
        parcel.writeParcelable(mSaveInfo, flags);
        parcel.writeParcelable(mClientState, flags);
        parcel.writeParcelableArray(mAuthenticationIds, flags);
        parcel.writeParcelable(mAuthentication, flags);
        parcel.writeParcelable(mPresentation, flags);
        parcel.writeParcelable(mInlinePresentation, flags);
        parcel.writeParcelable(mInlineTooltipPresentation, flags);
        parcel.writeParcelable(mDialogPresentation, flags);
        parcel.writeParcelable(mDialogHeader, flags);
        parcel.writeParcelableArray(mFillDialogTriggerIds, flags);
        parcel.writeParcelable(mHeader, flags);
        parcel.writeParcelable(mFooter, flags);
        parcel.writeParcelable(mUserData, flags);
        parcel.writeParcelableArray(mIgnoredIds, flags);
        parcel.writeLong(mDisableDuration);
        parcel.writeParcelableArray(mFieldClassificationIds, flags);
        parcel.writeParcelableArray(mDetectedFieldTypes, flags);
        parcel.writeInt(mIconResourceId);
        parcel.writeInt(mServiceDisplayNameResourceId);
        parcel.writeBoolean(mShowFillDialogIcon);
        parcel.writeBoolean(mShowSaveDialogIcon);
        parcel.writeInt(mFlags);
        parcel.writeIntArray(mCancelIds);
        parcel.writeInt(mRequestId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {
        @Override
        public FillResponse createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder();
            final ParceledListSlice<Dataset> datasetSlice = parcel.readParcelable(null, android.content.pm.ParceledListSlice.class);
            final List<Dataset> datasets = (datasetSlice != null) ? datasetSlice.getList() : null;
            final int datasetCount = (datasets != null) ? datasets.size() : 0;
            for (int i = 0; i < datasetCount; i++) {
                builder.addDataset(datasets.get(i));
            }
            builder.setSaveInfo(parcel.readParcelable(null, android.service.autofill.SaveInfo.class));
            builder.setClientState(parcel.readParcelable(null, android.os.Bundle.class));

            // Sets authentication state.
            final AutofillId[] authenticationIds = parcel.readParcelableArray(null,
                    AutofillId.class);
            final IntentSender authentication = parcel.readParcelable(null, android.content.IntentSender.class);
            final RemoteViews presentation = parcel.readParcelable(null, android.widget.RemoteViews.class);
            final InlinePresentation inlinePresentation = parcel.readParcelable(null, android.service.autofill.InlinePresentation.class);
            final InlinePresentation inlineTooltipPresentation = parcel.readParcelable(null, android.service.autofill.InlinePresentation.class);
            final RemoteViews dialogPresentation = parcel.readParcelable(null, android.widget.RemoteViews.class);
            if (authenticationIds != null) {
                builder.setAuthentication(authenticationIds, authentication, presentation,
                        inlinePresentation, inlineTooltipPresentation, dialogPresentation);
            }
            final RemoteViews dialogHeader = parcel.readParcelable(null, android.widget.RemoteViews.class);
            if (dialogHeader != null) {
                builder.setDialogHeader(dialogHeader);
            }
            final AutofillId[] triggerIds = parcel.readParcelableArray(null, AutofillId.class);
            if (triggerIds != null) {
                builder.setFillDialogTriggerIds(triggerIds);
            }
            final RemoteViews header = parcel.readParcelable(null, android.widget.RemoteViews.class);
            if (header != null) {
                builder.setHeader(header);
            }
            final RemoteViews footer = parcel.readParcelable(null, android.widget.RemoteViews.class);
            if (footer != null) {
                builder.setFooter(footer);
            }
            final UserData userData = parcel.readParcelable(null, android.service.autofill.UserData.class);
            if (userData != null) {
                builder.setUserData(userData);
            }

            builder.setIgnoredIds(parcel.readParcelableArray(null, AutofillId.class));
            final long disableDuration = parcel.readLong();
            if (disableDuration > 0) {
                builder.disableAutofill(disableDuration);
            }
            final AutofillId[] fieldClassifactionIds =
                    parcel.readParcelableArray(null, AutofillId.class);
            if (fieldClassifactionIds != null) {
                builder.setFieldClassificationIds(fieldClassifactionIds);
            }

            final FieldClassification[] detectedFields =
                    parcel.readParcelableArray(null, FieldClassification.class);
            if (detectedFields != null) {
                builder.setDetectedFieldClassifications(Set.of(detectedFields));
            }

            builder.setIconResourceId(parcel.readInt());
            builder.setServiceDisplayNameResourceId(parcel.readInt());
            builder.setShowFillDialogIcon(parcel.readBoolean());
            builder.setShowSaveDialogIcon(parcel.readBoolean());
            builder.setFlags(parcel.readInt());
            final int[] cancelIds = parcel.createIntArray();
            builder.setPresentationCancelIds(cancelIds);

            final FillResponse response = builder.build();
            response.setRequestId(parcel.readInt());

            return response;
        }

        @Override
        public FillResponse[] newArray(int size) {
            return new FillResponse[size];
        }
    };
}
