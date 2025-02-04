/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecom;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * Contains status label and icon displayed in the in-call UI.
 */
public final class StatusHints implements Parcelable {

    private final CharSequence mLabel;
    private Icon mIcon;
    private final Bundle mExtras;
    private static final String TAG = StatusHints.class.getSimpleName();

    /**
     * @hide
     */
    @SystemApi @Deprecated
    public StatusHints(ComponentName packageName, CharSequence label, int iconResId,
            Bundle extras) {
        this(label, iconResId == 0 ? null : Icon.createWithResource(packageName.getPackageName(),
            iconResId), extras);
    }

    public StatusHints(CharSequence label, Icon icon, Bundle extras) {
        mLabel = label;
        mIcon = validateAccountIconUserBoundary(icon, Binder.getCallingUserHandle());
        mExtras = extras;
    }

    /**
     * @param icon
     * @hide
     */
    @VisibleForTesting
    public StatusHints(@Nullable Icon icon) {
        mLabel = null;
        mExtras = null;
        mIcon = icon;
    }

    /**
     *
     * @param icon
     * @hide
     */
    public void setIcon(@Nullable Icon icon) {
        mIcon = icon;
    }

    /**
     * @return A package used to load the icon.
     *
     * @hide
     */
    @SystemApi @Deprecated
    public ComponentName getPackageName() {
        // Minimal compatibility shim for legacy apps' tests
        return new ComponentName("", "");
    }

    /**
     * @return The label displayed in the in-call UI.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * The icon resource ID for the icon to show.
     *
     * @return A resource ID.
     *
     * @hide
     */
    @SystemApi @Deprecated
    public int getIconResId() {
        // Minimal compatibility shim for legacy apps' tests
        return 0;
    }

    /**
     * @return An icon displayed in the in-call UI.
     *
     * @hide
     */
    @SystemApi @Deprecated
    public Drawable getIcon(Context context) {
        return mIcon.loadDrawable(context);
    }

    /**
     * @return An icon depicting the status.
     */
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * @return Extra data used to display status.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Validates the StatusHints image icon to see if it's not in the calling user space.
     * Invalidates the icon if so, otherwise returns back the original icon.
     *
     * @param icon
     * @return icon (validated)
     * @hide
     */
    public static Icon validateAccountIconUserBoundary(Icon icon, UserHandle callingUserHandle) {
        // Refer to Icon#getUriString for context. The URI string is invalid for icons of
        // incompatible types.
        if (icon != null && (icon.getType() == Icon.TYPE_URI
                || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            int callingUserId = callingUserHandle.getIdentifier();
            int requestingUserId = getUserIdFromAuthority(
                    icon.getUri().getAuthority(), callingUserId);
            if (callingUserId != requestingUserId) {
                return null;
            }

        }
        return icon;
    }

    /**
     * Derives the user id from the authority or the default user id if none could be found.
     * @param auth
     * @param defaultUserId
     * @return The user id from the given authority.
     * @hide
     */
    public static int getUserIdFromAuthority(String auth, int defaultUserId) {
        if (auth == null) return defaultUserId;
        int end = auth.lastIndexOf('@');
        if (end == -1) return defaultUserId;
        String userIdString = auth.substring(0, end);
        try {
            return Integer.parseInt(userIdString);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Error parsing userId." + e);
            return UserHandle.USER_NULL;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeCharSequence(mLabel);
        out.writeParcelable(mIcon, 0);
        out.writeParcelable(mExtras, 0);
    }

    public static final @android.annotation.NonNull Creator<StatusHints> CREATOR
            = new Creator<StatusHints>() {
        public StatusHints createFromParcel(Parcel in) {
            return new StatusHints(in);
        }

        public StatusHints[] newArray(int size) {
            return new StatusHints[size];
        }
    };

    private StatusHints(Parcel in) {
        mLabel = in.readCharSequence();
        mIcon = in.readParcelable(getClass().getClassLoader(), android.graphics.drawable.Icon.class);
        mExtras = in.readParcelable(getClass().getClassLoader(), android.os.Bundle.class);
    }

    @Override
    public boolean equals(Object other) {
        if (other != null && other instanceof StatusHints) {
            StatusHints otherHints = (StatusHints) other;
            return Objects.equals(otherHints.getLabel(), getLabel()) &&
                    Objects.equals(otherHints.getIcon(), getIcon()) &&
                    Objects.equals(otherHints.getExtras(), getExtras());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mLabel) + Objects.hashCode(mIcon) + Objects.hashCode(mExtras);
    }
}
