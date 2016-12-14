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
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.io.PrintWriter;

/**
 * Represents a remote action that can be called from another process.  The action can have an
 * associated visualization including metadata like an icon or title.
 */
public final class RemoteAction implements Parcelable {

    /**
     * Interface definition for a callback to be invoked when an action is invoked.
     */
    public interface OnActionListener {
        /**
         * Called when the associated action is invoked.
         *
         * @param action The action that was invoked.
         */
        void onAction(RemoteAction action);
    }

    private static final String TAG = "RemoteAction";

    private static final int MESSAGE_ACTION_INVOKED = 1;

    private final Icon mIcon;
    private final CharSequence mTitle;
    private final CharSequence mContentDescription;
    private OnActionListener mActionCallback;
    private final Messenger mMessenger;

    RemoteAction(Parcel in) {
        mIcon = Icon.CREATOR.createFromParcel(in);
        mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mMessenger = in.readParcelable(Messenger.class.getClassLoader());
    }

    public RemoteAction(@NonNull Icon icon, @NonNull CharSequence title,
            @NonNull CharSequence contentDescription, @NonNull OnActionListener callback) {
        if (icon == null || title == null || contentDescription == null || callback == null) {
            throw new IllegalArgumentException("Expected icon, title, content description and " +
                    "action callback");
        }
        mIcon = icon;
        mTitle = title;
        mContentDescription = contentDescription;
        mActionCallback = callback;
        mMessenger = new Messenger(new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_ACTION_INVOKED:
                        mActionCallback.onAction(RemoteAction.this);
                        break;
                }
            }
        });
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
     * Sends a message that the action was invoked.
     * @hide
     */
    public void sendActionInvoked() {
        Message m = Message.obtain();
        m.what = MESSAGE_ACTION_INVOKED;
        try {
            mMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send action-invoked", e);
        }
    }

    @Override
    public RemoteAction clone() {
        return new RemoteAction(mIcon, mTitle, mContentDescription, mActionCallback);
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
        out.writeParcelable(mMessenger, flags);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("title=" + mTitle);
        pw.print(" contentDescription=" + mContentDescription);
        pw.print(" icon=" + mIcon);
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