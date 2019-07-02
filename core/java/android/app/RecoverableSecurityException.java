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

package android.app;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Specialization of {@link SecurityException} that contains additional
 * information about how to involve the end user to recover from the exception.
 * <p>
 * This exception is only appropriate where there is a concrete action the user
 * can take to recover and make forward progress, such as confirming or entering
 * authentication credentials, or granting access.
 * <p>
 * If the receiving app is actively involved with the user, it should present
 * the contained recovery details to help the user make forward progress.
 * <p class="note">
 * Note: legacy code that receives this exception may treat it as a general
 * {@link SecurityException}, and thus there is no guarantee that the messages
 * contained will be shown to the end user.
 */
public final class RecoverableSecurityException extends SecurityException implements Parcelable {
    private static final String TAG = "RecoverableSecurityException";

    private final CharSequence mUserMessage;
    private final RemoteAction mUserAction;

    /** {@hide} */
    public RecoverableSecurityException(Parcel in) {
        this(new SecurityException(in.readString()), in.readCharSequence(),
                RemoteAction.CREATOR.createFromParcel(in));
    }

    /**
     * Create an instance ready to be thrown.
     *
     * @param cause original cause with details designed for engineering
     *            audiences.
     * @param userMessage short message describing the issue for end user
     *            audiences, which may be shown in a notification or dialog.
     *            This should be localized and less than 64 characters. For
     *            example: <em>PIN required to access Document.pdf</em>
     * @param userAction primary action that will initiate the recovery. The
     *            title should be localized and less than 24 characters. For
     *            example: <em>Enter PIN</em>. This action must launch an
     *            activity that is expected to set
     *            {@link Activity#setResult(int)} before finishing to
     *            communicate the final status of the recovery. For example,
     *            apps that observe {@link Activity#RESULT_OK} may choose to
     *            immediately retry their operation.
     */
    public RecoverableSecurityException(@NonNull Throwable cause, @NonNull CharSequence userMessage,
            @NonNull RemoteAction userAction) {
        super(cause.getMessage());
        mUserMessage = Objects.requireNonNull(userMessage);
        mUserAction = Objects.requireNonNull(userAction);
    }

    /**
     * Return short message describing the issue for end user audiences, which
     * may be shown in a notification or dialog.
     */
    public @NonNull CharSequence getUserMessage() {
        return mUserMessage;
    }

    /**
     * Return primary action that will initiate the recovery.
     */
    public @NonNull RemoteAction getUserAction() {
        return mUserAction;
    }

    /**
     * Convenience method that will show a very simple notification populated
     * with the details from this exception.
     * <p>
     * If you want more flexibility over retrying your original operation once
     * the user action has finished, consider presenting your own UI that uses
     * {@link Activity#startIntentSenderForResult} to launch the
     * {@link PendingIntent#getIntentSender()} from {@link #getUserAction()}
     * when requested. If the result of that activity is
     * {@link Activity#RESULT_OK}, you should consider retrying.
     * <p>
     * This method will only display the most recent exception from any single
     * remote UID; notifications from older exceptions will always be replaced.
     *
     * @param channelId the {@link NotificationChannel} to use, which must have
     *            been already created using
     *            {@link NotificationManager#createNotificationChannel}.
     * @hide
     */
    public void showAsNotification(Context context, String channelId) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final Notification.Builder builder = new Notification.Builder(context, channelId)
                .setSmallIcon(com.android.internal.R.drawable.ic_print_error)
                .setContentTitle(mUserAction.getTitle())
                .setContentText(mUserMessage)
                .setContentIntent(mUserAction.getActionIntent())
                .setCategory(Notification.CATEGORY_ERROR);
        nm.notify(TAG, mUserAction.getActionIntent().getCreatorUid(), builder.build());
    }

    /**
     * Convenience method that will show a very simple dialog populated with the
     * details from this exception.
     * <p>
     * If you want more flexibility over retrying your original operation once
     * the user action has finished, consider presenting your own UI that uses
     * {@link Activity#startIntentSenderForResult} to launch the
     * {@link PendingIntent#getIntentSender()} from {@link #getUserAction()}
     * when requested. If the result of that activity is
     * {@link Activity#RESULT_OK}, you should consider retrying.
     * <p>
     * This method will only display the most recent exception from any single
     * remote UID; dialogs from older exceptions will always be replaced.
     *
     * @hide
     */
    public void showAsDialog(Activity activity) {
        final LocalDialog dialog = new LocalDialog();
        final Bundle args = new Bundle();
        args.putParcelable(TAG, this);
        dialog.setArguments(args);

        final String tag = TAG + "_" + mUserAction.getActionIntent().getCreatorUid();
        final FragmentManager fm = activity.getFragmentManager();
        final FragmentTransaction ft = fm.beginTransaction();
        final Fragment old = fm.findFragmentByTag(tag);
        if (old != null) {
            ft.remove(old);
        }
        ft.add(dialog, tag);
        ft.commitAllowingStateLoss();
    }

    /**
     * Implementation detail for
     * {@link RecoverableSecurityException#showAsDialog(Activity)}; needs to
     * remain static to be recreated across orientation changes.
     *
     * @hide
     */
    public static class LocalDialog extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final RecoverableSecurityException e = getArguments().getParcelable(TAG);
            return new AlertDialog.Builder(getActivity())
                    .setMessage(e.mUserMessage)
                    .setPositiveButton(e.mUserAction.getTitle(), (dialog, which) -> {
                        try {
                            e.mUserAction.getActionIntent().send();
                        } catch (PendingIntent.CanceledException ignored) {
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getMessage());
        dest.writeCharSequence(mUserMessage);
        mUserAction.writeToParcel(dest, flags);
    }

    public static final @android.annotation.NonNull Creator<RecoverableSecurityException> CREATOR =
            new Creator<RecoverableSecurityException>() {
        @Override
        public RecoverableSecurityException createFromParcel(Parcel source) {
            return new RecoverableSecurityException(source);
        }

        @Override
        public RecoverableSecurityException[] newArray(int size) {
            return new RecoverableSecurityException[size];
        }
    };
}
