/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.app.smartspace;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.smartspace.flags.Flags;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.widget.RemoteViews;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link SmartspaceTarget} is a data class which holds all properties necessary to inflate a
 * smartspace card. It contains data and related metadata which is supposed to be utilized by
 * smartspace clients based on their own UI/UX requirements. Some of the properties have
 * {@link SmartspaceAction} as their type because they can have associated actions.
 *
 * <p><b>NOTE: </b>
 * If either {@link mRemoteViews} or {@link mWidget} is set, it should be preferred over all
 * other properties. (An exception is thrown if both are set.)
 * Else, if {@link mSliceUri} is set, it should be preferred over all other data properties.
 * Otherwise, the instance should be treated as a data object.
 *
 * @hide
 */
@SystemApi
public final class SmartspaceTarget implements Parcelable {

    /** A unique Id for an instance of {@link SmartspaceTarget}. */
    @NonNull
    private final String mSmartspaceTargetId;

    /** A {@link SmartspaceAction} for the header in the Smartspace card. */
    @Nullable
    private final SmartspaceAction mHeaderAction;

    /** A {@link SmartspaceAction} for the base action in the Smartspace card. */
    @Nullable
    private final SmartspaceAction mBaseAction;

    /** A timestamp indicating when the card was created. */
    @CurrentTimeMillisLong
    private final long mCreationTimeMillis;

    /**
     * A timestamp indicating when the card should be removed from view, in case the service
     * disconnects or restarts.
     */
    @CurrentTimeMillisLong
    private final long mExpiryTimeMillis;

    /** A score assigned to a target. */
    private final float mScore;

    /** A {@link List<SmartspaceAction>} containing all action chips. */
    @NonNull
    private final List<SmartspaceAction> mActionChips;

    /** A {@link List<SmartspaceAction>} containing all icons for the grid. */
    @NonNull
    private final List<SmartspaceAction> mIconGrid;

    /**
     * {@link FeatureType} indicating the feature type of this card.
     *
     * @see FeatureType
     */
    @FeatureType
    private final int mFeatureType;

    /**
     * Indicates whether the content is sensitive. Certain UI surfaces may choose to skip rendering
     * real content until the device is unlocked.
     */
    private final boolean mSensitive;

    /** Indicating if the UI should show this target in its expanded state. */
    private final boolean mShouldShowExpanded;

    /** A Notification key if the target was generated using a notification. */
    @Nullable
    private final String mSourceNotificationKey;

    /** {@link ComponentName} for this target. */
    @NonNull
    private final ComponentName mComponentName;

    /** {@link UserHandle} for this target. */
    @NonNull
    private final UserHandle mUserHandle;

    /**
     * Target Id of other {@link SmartspaceTarget}s if it is associated with this target. This
     * association is added to tell the UI that a card would be more useful if displayed with the
     * associated smartspace target. This field is supposed to be taken as a suggestion and the
     * association can be ignored based on the situation in the UI. It is possible to have a one way
     * card association. In other words, Card B can be associated with Card A but not the other way
     * around.
     */
    @Nullable
    private final String mAssociatedSmartspaceTargetId;

    /** {@link Uri} Slice Uri if this target is a slice. */
    @Nullable
    private final Uri mSliceUri;

    /** {@link AppWidgetProviderInfo} if this target is a widget. */
    @Nullable
    private final AppWidgetProviderInfo mWidget;

    @Nullable
    private final RemoteViews mRemoteViews;

    @Nullable
    private final BaseTemplateData mTemplateData;

    public static final int FEATURE_UNDEFINED = 0;
    public static final int FEATURE_WEATHER = 1;
    public static final int FEATURE_CALENDAR = 2;
    public static final int FEATURE_COMMUTE_TIME = 3;
    public static final int FEATURE_FLIGHT = 4;
    public static final int FEATURE_TIPS = 5;
    public static final int FEATURE_REMINDER = 6;
    public static final int FEATURE_ALARM = 7;
    public static final int FEATURE_ONBOARDING = 8;
    public static final int FEATURE_SPORTS = 9;
    public static final int FEATURE_WEATHER_ALERT = 10;
    public static final int FEATURE_CONSENT = 11;
    public static final int FEATURE_STOCK_PRICE_CHANGE = 12;
    public static final int FEATURE_SHOPPING_LIST = 13;
    public static final int FEATURE_LOYALTY_CARD = 14;
    public static final int FEATURE_MEDIA = 15;
    public static final int FEATURE_BEDTIME_ROUTINE = 16;
    public static final int FEATURE_FITNESS_TRACKING = 17;
    public static final int FEATURE_ETA_MONITORING = 18;
    public static final int FEATURE_MISSED_CALL = 19;
    public static final int FEATURE_PACKAGE_TRACKING = 20;
    public static final int FEATURE_TIMER = 21;
    public static final int FEATURE_STOPWATCH = 22;
    public static final int FEATURE_UPCOMING_ALARM = 23;
    public static final int FEATURE_GAS_STATION_PAYMENT = 24;
    public static final int FEATURE_PAIRED_DEVICE_STATE = 25;
    public static final int FEATURE_DRIVING_MODE = 26;
    public static final int FEATURE_SLEEP_SUMMARY = 27;
    public static final int FEATURE_FLASHLIGHT = 28;
    public static final int FEATURE_TIME_TO_LEAVE = 29;
    public static final int FEATURE_DOORBELL = 30;
    public static final int FEATURE_MEDIA_RESUME = 31;
    public static final int FEATURE_CROSS_DEVICE_TIMER = 32;
    public static final int FEATURE_SEVERE_WEATHER_ALERT = 33;
    public static final int FEATURE_HOLIDAY_ALARM = 34;
    public static final int FEATURE_SAFETY_CHECK = 35;
    public static final int FEATURE_MEDIA_HEADS_UP = 36;
    public static final int FEATURE_STEP_COUNTING = 37;
    public static final int FEATURE_EARTHQUAKE_ALERT = 38;
    public static final int FEATURE_STEP_DATE = 39; // This represents a DATE. "STEP" is a typo.
    public static final int FEATURE_BLAZE_BUILD_PROGRESS = 40;
    public static final int FEATURE_EARTHQUAKE_OCCURRED = 41;

    /**
     * @hide
     */
    @IntDef(prefix = {"FEATURE_"}, value = {
            FEATURE_UNDEFINED,
            FEATURE_WEATHER,
            FEATURE_CALENDAR,
            FEATURE_COMMUTE_TIME,
            FEATURE_FLIGHT,
            FEATURE_TIPS,
            FEATURE_REMINDER,
            FEATURE_ALARM,
            FEATURE_ONBOARDING,
            FEATURE_SPORTS,
            FEATURE_WEATHER_ALERT,
            FEATURE_CONSENT,
            FEATURE_STOCK_PRICE_CHANGE,
            FEATURE_SHOPPING_LIST,
            FEATURE_LOYALTY_CARD,
            FEATURE_MEDIA,
            FEATURE_BEDTIME_ROUTINE,
            FEATURE_FITNESS_TRACKING,
            FEATURE_ETA_MONITORING,
            FEATURE_MISSED_CALL,
            FEATURE_PACKAGE_TRACKING,
            FEATURE_TIMER,
            FEATURE_STOPWATCH,
            FEATURE_UPCOMING_ALARM,
            FEATURE_GAS_STATION_PAYMENT,
            FEATURE_PAIRED_DEVICE_STATE,
            FEATURE_DRIVING_MODE,
            FEATURE_SLEEP_SUMMARY,
            FEATURE_FLASHLIGHT,
            FEATURE_TIME_TO_LEAVE,
            FEATURE_DOORBELL,
            FEATURE_MEDIA_RESUME,
            FEATURE_CROSS_DEVICE_TIMER,
            FEATURE_SEVERE_WEATHER_ALERT,
            FEATURE_HOLIDAY_ALARM,
            FEATURE_SAFETY_CHECK,
            FEATURE_MEDIA_HEADS_UP,
            FEATURE_STEP_COUNTING,
            FEATURE_EARTHQUAKE_ALERT,
            FEATURE_STEP_DATE,
            FEATURE_BLAZE_BUILD_PROGRESS,
            FEATURE_EARTHQUAKE_OCCURRED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FeatureType {
    }

    public static final int UI_TEMPLATE_UNDEFINED = 0;
    // Default template whose data is represented by {@link BaseTemplateData}. The default
    // template is also a base card for the other types of templates.
    public static final int UI_TEMPLATE_DEFAULT = 1;
    // Sub-image template whose data is represented by {@link SubImageTemplateData}
    public static final int UI_TEMPLATE_SUB_IMAGE = 2;
    // Sub-list template whose data is represented by {@link SubListTemplateData}
    public static final int UI_TEMPLATE_SUB_LIST = 3;
    // Carousel template whose data is represented by {@link CarouselTemplateData}
    public static final int UI_TEMPLATE_CAROUSEL = 4;
    // Head-to-head template whose data is represented by {@link HeadToHeadTemplateData}
    public static final int UI_TEMPLATE_HEAD_TO_HEAD = 5;
    // Combined-cards template whose data is represented by {@link CombinedCardsTemplateData}
    public static final int UI_TEMPLATE_COMBINED_CARDS = 6;
    // Sub-card template whose data is represented by {@link SubCardTemplateData}
    public static final int UI_TEMPLATE_SUB_CARD = 7;
    // Reserved: 8
    // Template type used by non-UI template features for sending logging information in the
    // base template data. This should not be used for UI template features.
    // public static final int UI_TEMPLATE_LOGGING_ONLY = 8;

    /**
     * The types of the Smartspace ui templates.
     *
     * @hide
     */
    @IntDef(prefix = {"UI_TEMPLATE_"}, value = {
            UI_TEMPLATE_UNDEFINED,
            UI_TEMPLATE_DEFAULT,
            UI_TEMPLATE_SUB_IMAGE,
            UI_TEMPLATE_SUB_LIST,
            UI_TEMPLATE_CAROUSEL,
            UI_TEMPLATE_HEAD_TO_HEAD,
            UI_TEMPLATE_COMBINED_CARDS,
            UI_TEMPLATE_SUB_CARD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UiTemplateType {
    }

    private SmartspaceTarget(Parcel in) {
        this.mSmartspaceTargetId = in.readString();
        this.mHeaderAction = in.readTypedObject(SmartspaceAction.CREATOR);
        this.mBaseAction = in.readTypedObject(SmartspaceAction.CREATOR);
        this.mCreationTimeMillis = in.readLong();
        this.mExpiryTimeMillis = in.readLong();
        this.mScore = in.readFloat();
        this.mActionChips = in.createTypedArrayList(SmartspaceAction.CREATOR);
        this.mIconGrid = in.createTypedArrayList(SmartspaceAction.CREATOR);
        this.mFeatureType = in.readInt();
        this.mSensitive = in.readBoolean();
        this.mShouldShowExpanded = in.readBoolean();
        this.mSourceNotificationKey = in.readString();
        this.mComponentName = in.readTypedObject(ComponentName.CREATOR);
        this.mUserHandle = in.readTypedObject(UserHandle.CREATOR);
        this.mAssociatedSmartspaceTargetId = in.readString();
        this.mSliceUri = in.readTypedObject(Uri.CREATOR);
        this.mWidget = in.readTypedObject(AppWidgetProviderInfo.CREATOR);
        this.mTemplateData = in.readParcelable(/* loader= */null, BaseTemplateData.class);
        this.mRemoteViews = in.readTypedObject(RemoteViews.CREATOR);
    }

    private SmartspaceTarget(String smartspaceTargetId,
            SmartspaceAction headerAction, SmartspaceAction baseAction, long creationTimeMillis,
            long expiryTimeMillis, float score,
            List<SmartspaceAction> actionChips,
            List<SmartspaceAction> iconGrid, int featureType, boolean sensitive,
            boolean shouldShowExpanded, String sourceNotificationKey,
            ComponentName componentName, UserHandle userHandle,
            String associatedSmartspaceTargetId, Uri sliceUri,
            AppWidgetProviderInfo widget, BaseTemplateData templateData, RemoteViews remoteViews) {
        mSmartspaceTargetId = smartspaceTargetId;
        mHeaderAction = headerAction;
        mBaseAction = baseAction;
        mCreationTimeMillis = creationTimeMillis;
        mExpiryTimeMillis = expiryTimeMillis;
        mScore = score;
        mActionChips = actionChips;
        mIconGrid = iconGrid;
        mFeatureType = featureType;
        mSensitive = sensitive;
        mShouldShowExpanded = shouldShowExpanded;
        mSourceNotificationKey = sourceNotificationKey;
        mComponentName = componentName;
        mUserHandle = userHandle;
        mAssociatedSmartspaceTargetId = associatedSmartspaceTargetId;
        mSliceUri = sliceUri;
        mWidget = widget;
        mTemplateData = templateData;
        mRemoteViews = remoteViews;
    }

    /**
     * Returns the Id of the target.
     */
    @NonNull
    public String getSmartspaceTargetId() {
        return mSmartspaceTargetId;
    }

    /**
     * Returns the header action of the target.
     */
    @Nullable
    public SmartspaceAction getHeaderAction() {
        return mHeaderAction;
    }

    /**
     * Returns the base action of the target.
     */
    @Nullable
    public SmartspaceAction getBaseAction() {
        return mBaseAction;
    }

    /**
     * Returns the creation time of the target.
     */
    @CurrentTimeMillisLong
    public long getCreationTimeMillis() {
        return mCreationTimeMillis;
    }

    /**
     * Returns the expiry time of the target.
     */
    @CurrentTimeMillisLong
    public long getExpiryTimeMillis() {
        return mExpiryTimeMillis;
    }

    /**
     * Returns the score of the target.
     */
    public float getScore() {
        return mScore;
    }

    /**
     * Return the action chips of the target.
     */
    @NonNull
    public List<SmartspaceAction> getActionChips() {
        return mActionChips;
    }

    /**
     * Return the icons of the target.
     */
    @NonNull
    public List<SmartspaceAction> getIconGrid() {
        return mIconGrid;
    }

    /**
     * Returns the feature type of the target.
     */
    @FeatureType
    public int getFeatureType() {
        return mFeatureType;
    }

    /**
     * Returns whether the target is sensitive or not.
     */
    public boolean isSensitive() {
        return mSensitive;
    }

    /**
     * Returns whether the target should be shown in expanded state.
     */
    public boolean shouldShowExpanded() {
        return mShouldShowExpanded;
    }

    /**
     * Returns the source notification key of the target.
     */
    @Nullable
    public String getSourceNotificationKey() {
        return mSourceNotificationKey;
    }

    /**
     * Returns the component name of the target.
     */
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the user handle of the target.
     */
    @NonNull
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * Returns the id of a target associated with this instance.
     */
    @Nullable
    public String getAssociatedSmartspaceTargetId() {
        return mAssociatedSmartspaceTargetId;
    }

    /**
     * Returns the slice uri, if the target is a slice.
     */
    @Nullable
    public Uri getSliceUri() {
        return mSliceUri;
    }

    /**
     * Returns the AppWidgetProviderInfo, if the target is a widget.
     */
    @Nullable
    public AppWidgetProviderInfo getWidget() {
        return mWidget;
    }

    /**
     * Returns the UI template data.
     */
    @Nullable
    public BaseTemplateData getTemplateData() {
        return mTemplateData;
    }

    /**
     * Returns the {@link RemoteViews} to show over the target.
     */
    @FlaggedApi(Flags.FLAG_REMOTE_VIEWS)
    @Nullable
    public RemoteViews getRemoteViews() {
        return mRemoteViews;
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<SmartspaceTarget> CREATOR = new Creator<SmartspaceTarget>() {
        @Override
        public SmartspaceTarget createFromParcel(Parcel source) {
            return new SmartspaceTarget(source);
        }

        @Override
        public SmartspaceTarget[] newArray(int size) {
            return new SmartspaceTarget[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(this.mSmartspaceTargetId);
        dest.writeTypedObject(this.mHeaderAction, flags);
        dest.writeTypedObject(this.mBaseAction, flags);
        dest.writeLong(this.mCreationTimeMillis);
        dest.writeLong(this.mExpiryTimeMillis);
        dest.writeFloat(this.mScore);
        dest.writeTypedList(this.mActionChips);
        dest.writeTypedList(this.mIconGrid);
        dest.writeInt(this.mFeatureType);
        dest.writeBoolean(this.mSensitive);
        dest.writeBoolean(this.mShouldShowExpanded);
        dest.writeString(this.mSourceNotificationKey);
        dest.writeTypedObject(this.mComponentName, flags);
        dest.writeTypedObject(this.mUserHandle, flags);
        dest.writeString(this.mAssociatedSmartspaceTargetId);
        dest.writeTypedObject(this.mSliceUri, flags);
        dest.writeTypedObject(this.mWidget, flags);
        dest.writeParcelable(this.mTemplateData, flags);
        dest.writeTypedObject(this.mRemoteViews, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "SmartspaceTarget{"
                + "mSmartspaceTargetId='" + mSmartspaceTargetId + '\''
                + ", mHeaderAction=" + mHeaderAction
                + ", mBaseAction=" + mBaseAction
                + ", mCreationTimeMillis=" + mCreationTimeMillis
                + ", mExpiryTimeMillis=" + mExpiryTimeMillis
                + ", mScore=" + mScore
                + ", mActionChips=" + mActionChips
                + ", mIconGrid=" + mIconGrid
                + ", mFeatureType=" + mFeatureType
                + ", mSensitive=" + mSensitive
                + ", mShouldShowExpanded=" + mShouldShowExpanded
                + ", mSourceNotificationKey='" + mSourceNotificationKey + '\''
                + ", mComponentName=" + mComponentName
                + ", mUserHandle=" + mUserHandle
                + ", mAssociatedSmartspaceTargetId='" + mAssociatedSmartspaceTargetId + '\''
                + ", mSliceUri=" + mSliceUri
                + ", mWidget=" + mWidget
                + ", mTemplateData=" + mTemplateData
                + ", mRemoteViews=" + mRemoteViews
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmartspaceTarget that = (SmartspaceTarget) o;
        return mCreationTimeMillis == that.mCreationTimeMillis
                && mExpiryTimeMillis == that.mExpiryTimeMillis
                && Float.compare(that.mScore, mScore) == 0
                && mFeatureType == that.mFeatureType
                && mSensitive == that.mSensitive
                && mShouldShowExpanded == that.mShouldShowExpanded
                && mSmartspaceTargetId.equals(that.mSmartspaceTargetId)
                && Objects.equals(mHeaderAction, that.mHeaderAction)
                && Objects.equals(mBaseAction, that.mBaseAction)
                && Objects.equals(mActionChips, that.mActionChips)
                && Objects.equals(mIconGrid, that.mIconGrid)
                && Objects.equals(mSourceNotificationKey, that.mSourceNotificationKey)
                && mComponentName.equals(that.mComponentName)
                && mUserHandle.equals(that.mUserHandle)
                && Objects.equals(mAssociatedSmartspaceTargetId,
                that.mAssociatedSmartspaceTargetId)
                && Objects.equals(mSliceUri, that.mSliceUri)
                && Objects.equals(mWidget, that.mWidget)
                && Objects.equals(mTemplateData, that.mTemplateData)
                && Objects.equals(mRemoteViews, that.mRemoteViews);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSmartspaceTargetId, mHeaderAction, mBaseAction, mCreationTimeMillis,
                mExpiryTimeMillis, mScore, mActionChips, mIconGrid, mFeatureType, mSensitive,
                mShouldShowExpanded, mSourceNotificationKey, mComponentName, mUserHandle,
                mAssociatedSmartspaceTargetId, mSliceUri, mWidget, mTemplateData, mRemoteViews);
    }

    /**
     * A builder for {@link SmartspaceTarget} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private final String mSmartspaceTargetId;
        private final ComponentName mComponentName;
        private final UserHandle mUserHandle;

        private SmartspaceAction mHeaderAction;
        private SmartspaceAction mBaseAction;
        private long mCreationTimeMillis;
        private long mExpiryTimeMillis;
        private float mScore;
        private List<SmartspaceAction> mActionChips = new ArrayList<>();
        private List<SmartspaceAction> mIconGrid = new ArrayList<>();
        private int mFeatureType;
        private boolean mSensitive;
        private boolean mShouldShowExpanded;
        private String mSourceNotificationKey;
        private String mAssociatedSmartspaceTargetId;
        private Uri mSliceUri;
        private AppWidgetProviderInfo mWidget;
        private BaseTemplateData mTemplateData;

        private RemoteViews mRemoteViews;

        /**
         * A builder for {@link SmartspaceTarget}.
         *
         * @param smartspaceTargetId the id of this target
         * @param componentName      the componentName of this target
         * @param userHandle         the userHandle of this target
         */
        public Builder(@NonNull String smartspaceTargetId,
                @NonNull ComponentName componentName, @NonNull UserHandle userHandle) {
            this.mSmartspaceTargetId = smartspaceTargetId;
            this.mComponentName = componentName;
            this.mUserHandle = userHandle;
        }

        /**
         * Sets the header action.
         */
        @NonNull
        public Builder setHeaderAction(@NonNull SmartspaceAction headerAction) {
            this.mHeaderAction = headerAction;
            return this;
        }

        /**
         * Sets the base action.
         */
        @NonNull
        public Builder setBaseAction(@NonNull SmartspaceAction baseAction) {
            this.mBaseAction = baseAction;
            return this;
        }

        /**
         * Sets the creation time.
         */
        @NonNull
        public Builder setCreationTimeMillis(@CurrentTimeMillisLong long creationTimeMillis) {
            this.mCreationTimeMillis = creationTimeMillis;
            return this;
        }

        /**
         * Sets the expiration time.
         */
        @NonNull
        public Builder setExpiryTimeMillis(@CurrentTimeMillisLong long expiryTimeMillis) {
            this.mExpiryTimeMillis = expiryTimeMillis;
            return this;
        }

        /**
         * Sets the score.
         */
        @NonNull
        public Builder setScore(float score) {
            this.mScore = score;
            return this;
        }

        /**
         * Sets the action chips.
         */
        @NonNull
        public Builder setActionChips(@NonNull List<SmartspaceAction> actionChips) {
            this.mActionChips = actionChips;
            return this;
        }

        /**
         * Sets the icon grid.
         */
        @NonNull
        public Builder setIconGrid(@NonNull List<SmartspaceAction> iconGrid) {
            this.mIconGrid = iconGrid;
            return this;
        }

        /**
         * Sets the feature type.
         */
        @NonNull
        public Builder setFeatureType(int featureType) {
            this.mFeatureType = featureType;
            return this;
        }

        /**
         * Sets whether the contents are sensitive.
         */
        @NonNull
        public Builder setSensitive(boolean sensitive) {
            this.mSensitive = sensitive;
            return this;
        }

        /**
         * Sets whether to show the card as expanded.
         */
        @NonNull
        public Builder setShouldShowExpanded(boolean shouldShowExpanded) {
            this.mShouldShowExpanded = shouldShowExpanded;
            return this;
        }

        /**
         * Sets the source notification key.
         */
        @NonNull
        public Builder setSourceNotificationKey(@NonNull String sourceNotificationKey) {
            this.mSourceNotificationKey = sourceNotificationKey;
            return this;
        }

        /**
         * Sets the associated smartspace target id.
         */
        @NonNull
        public Builder setAssociatedSmartspaceTargetId(
                @NonNull String associatedSmartspaceTargetId) {
            this.mAssociatedSmartspaceTargetId = associatedSmartspaceTargetId;
            return this;
        }

        /**
         * Sets the slice uri.
         *
         * <p><b>NOTE: </b> If {@link mWidget} is also set, {@link mSliceUri} should be ignored.
         */
        @NonNull
        public Builder setSliceUri(@NonNull Uri sliceUri) {
            this.mSliceUri = sliceUri;
            return this;
        }

        /**
         * Sets the widget id.
         *
         * <p><b>NOTE: </b> If {@link mWidget} is set, all other @Nullable params should be
         * ignored.
         *
         * @throws An {@link IllegalStateException} is thrown if {@link mRemoteViews} is set.
         */
        @NonNull
        public Builder setWidget(@NonNull AppWidgetProviderInfo widget) {
            if (mRemoteViews != null) {
                throw new IllegalStateException(
                        "Widget providers and RemoteViews cannot be used at the same time.");
            }
            this.mWidget = widget;
            return this;
        }

        /**
         * Sets the UI template data.
         */
        @NonNull
        public Builder setTemplateData(
                @Nullable BaseTemplateData templateData) {
            mTemplateData = templateData;
            return this;
        }

        /**
         * Sets the {@link RemoteViews}.
         *
         * <p><b>NOTE: </b> If {@link RemoteViews} is set, all other @Nullable params should be
         * ignored.
         *
         * @throws An {@link IllegalStateException} is thrown if {@link mWidget} is set.
         */
        @FlaggedApi(Flags.FLAG_REMOTE_VIEWS)
        @NonNull
        public Builder setRemoteViews(@NonNull RemoteViews remoteViews) {
            if (mWidget != null) {
                throw new IllegalStateException(
                        "Widget providers and RemoteViews cannot be used at the same time.");
            }
            mRemoteViews = remoteViews;
            return this;
        }

        /**
         * Builds a new {@link SmartspaceTarget}.
         *
         * @throws IllegalStateException when non null fields are set as null.
         */
        @NonNull
        public SmartspaceTarget build() {
            if (mSmartspaceTargetId == null
                    || mComponentName == null
                    || mUserHandle == null) {
                throw new IllegalStateException("Please assign a value to all @NonNull args.");
            }
            return new SmartspaceTarget(mSmartspaceTargetId,
                    mHeaderAction, mBaseAction, mCreationTimeMillis, mExpiryTimeMillis, mScore,
                    mActionChips, mIconGrid, mFeatureType, mSensitive, mShouldShowExpanded,
                    mSourceNotificationKey, mComponentName, mUserHandle,
                    mAssociatedSmartspaceTargetId, mSliceUri, mWidget, mTemplateData, mRemoteViews);
        }
    }
}
