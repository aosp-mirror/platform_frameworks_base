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

package android.app;

import android.annotation.NonNull;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.io.PrintWriter;

/**
 * Represents a remote action that can be called from another process.  The action can have an
 * associated visualization including metadata like an icon or title.
 */
public final class RemoteAction implements Parcelable {

    private static final String TAG = "RemoteAction";

    private final Icon mIcon;
    private final CharSequence mTitle;
    private final CharSequence mContentDescription;
    private final PendingIntent mActionIntent;
    private boolean mEnabled;
    private boolean mShouldShowIcon;

    RemoteAction(Parcel in) {
        mIcon = Icon.CREATOR.createFromParcel(in);
        mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mActionIntent = PendingIntent.CREATOR.createFromParcel(in);
        mEnabled = in.readBoolean();
        mShouldShowIcon = in.readBoolean();
    }

    public RemoteAction(@NonNull Icon icon, @NonNull CharSequence title,
            @NonNull CharSequence contentDescription, @NonNull PendingIntent intent) {
        if (icon == null || title == null || contentDescription == null || intent == null) {
            throw new IllegalArgumentException("Expected icon, title, content description and " +
                    "action callback");
        }
        mIcon = icon;
        mTitle = title;
        mContentDescription = contentDescription;
        mActionIntent = intent;
        mEnabled = true;
        mShouldShowIcon = true;
    }

    /**
     * Sets whether this action is enabled.
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Return whether this action is enabled.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Sets whether the icon should be shown.
     */
    public void setShouldShowIcon(boolean shouldShowIcon) {
        mShouldShowIcon = shouldShowIcon;
    }

    /**
     * Return whether the icon should be shown.
     */
    public boolean shouldShowIcon() {
        return mShouldShowIcon;
    }

    /**
     * Return an icon representing the action.
     */
    public @NonNull Icon getIcon() {
        return mIcon;
    }

    /**
     * Return an title representing the action.
     */
    public @NonNull CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Return a content description representing the action.
     */
    public @NonNull CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Return the action intent.
     */
    public @NonNull PendingIntent getActionIntent() {
        return mActionIntent;
    }

    @Override
    public RemoteAction clone() {
        RemoteAction action = new RemoteAction(mIcon, mTitle, mContentDescription, mActionIntent);
        action.setEnabled(mEnabled);
        action.setShouldShowIcon(mShouldShowIcon);
        return action;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mIcon.writeToParcel(out, 0);
        TextUtils.writeToParcel(mTitle, out, flags);
        TextUtils.writeToParcel(mContentDescription, out, flags);
        mActionIntent.writeToParcel(out, flags);
        out.writeBoolean(mEnabled);
        out.writeBoolean(mShouldShowIcon);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("title=" + mTitle);
        pw.print(" enabled=" + mEnabled);
        pw.print(" contentDescription=" + mContentDescription);
        pw.print(" icon=" + mIcon);
        pw.print(" action=" + mActionIntent.getIntent());
        pw.print(" shouldShowIcon=" + mShouldShowIcon);
        pw.println();
    }

    public static final Parcelable.Creator<RemoteAction> CREATOR =
            new Parcelable.Creator<RemoteAction>() {
                public RemoteAction createFromParcel(Parcel in) {
                    return new RemoteAction(in);
                }
                public RemoteAction[] newArray(int size) {
                    return new RemoteAction[size];
                }
            };
}