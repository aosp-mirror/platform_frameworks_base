/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * An event reported to a supplied [IntentSender] by the system chooser when an activity is selected
 * or other actions are taken to complete the session.
 *
 * @see Intent#EXTRA_CHOOSER_RESULT_INTENT_SENDER
 */
@FlaggedApi(android.service.chooser.Flags.FLAG_ENABLE_CHOOSER_RESULT)
public final class ChooserResult implements Parcelable {

    /**
     * Controls whether to send ChooserResult to the optional IntentSender supplied to the Chooser.
     * <p>
     * When enabled, ChooserResult is added to the provided Intent as
     * {@link Intent#EXTRA_CHOOSER_RESULT}, and sent for actions such as copy and edit, in addition
     * to activity selection. When disabled, only the selected component
     * is provided in {@link Intent#EXTRA_CHOSEN_COMPONENT}.
     * <p>
     * See: {@link Intent#createChooser(Intent, CharSequence, IntentSender)}
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Overridable
    public static final long SEND_CHOOSER_RESULT = 263474465L;

    /** @hide */
    @IntDef({
            CHOOSER_RESULT_UNKNOWN,
            CHOOSER_RESULT_SELECTED_COMPONENT,
            CHOOSER_RESULT_COPY,
            CHOOSER_RESULT_EDIT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultType { }

    /** An unknown action was taken to complete the session. */
    public static final int CHOOSER_RESULT_UNKNOWN = -1;
    /** The session was completed by selecting an activity to launch. */
    public static final int CHOOSER_RESULT_SELECTED_COMPONENT = 0;
    /** The session was completed by invoking the copy action. */
    public static final int CHOOSER_RESULT_COPY = 1;
    /** The session was completed by invoking the edit action. */
    public static final int CHOOSER_RESULT_EDIT = 2;

    @ResultType
    private final int mType;
    private final ComponentName mSelectedComponent;
    private final boolean mIsShortcut;

    private ChooserResult(@NonNull Parcel source) {
        mType = source.readInt();
        mSelectedComponent = ComponentName.readFromParcel(source);
        mIsShortcut = source.readBoolean();
    }

    /** @hide */
    @TestApi
    public ChooserResult(@ResultType int type, @Nullable ComponentName componentName,
            boolean isShortcut) {
        mType = type;
        mSelectedComponent = componentName;
        mIsShortcut = isShortcut;
    }

    /**
     * The type of the result.
     *
     * @return the type of the result
     */
    @ResultType
    public int getType() {
        return mType;
    }

    /**
     * Provides the component of the Activity selected for results with type
     * when type is {@link ChooserResult#CHOOSER_RESULT_SELECTED_COMPONENT}.
     * <p>
     * For all other types, this value is null.
     *
     * @return the component name selected
     */
    @Nullable
    public ComponentName getSelectedComponent() {
        return mSelectedComponent;
    }

    /**
     * Whether the selected component was provided by the app from as a shortcut.
     *
     * @return true if the selected component is a shortcut, false otherwise
     */
    public boolean isShortcut() {
        return mIsShortcut;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<ChooserResult> CREATOR =
            new Creator<>() {
                @Override
                public ChooserResult createFromParcel(Parcel source) {
                    return new ChooserResult(source);
                }

                @Override
                public ChooserResult[] newArray(int size) {
                    return new ChooserResult[0];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        ComponentName.writeToParcel(mSelectedComponent, dest);
        dest.writeBoolean(mIsShortcut);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChooserResult that = (ChooserResult) o;
        return mType == that.mType
                && mIsShortcut == that.mIsShortcut
                && Objects.equals(mSelectedComponent, that.mSelectedComponent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mSelectedComponent, mIsShortcut);
    }
}
