/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import static android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.PendingIntentInfo;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AndroidException;

import com.android.window.flags.Flags;

import java.util.concurrent.Executor;

/**
 * A description of an Intent and target action to perform with it.
 * The returned object can be
 * handed to other applications so that they can perform the action you
 * described on your behalf at a later time.
 *
 * <p>By giving a IntentSender to another application,
 * you are granting it the right to perform the operation you have specified
 * as if the other application was yourself (with the same permissions and
 * identity).  As such, you should be careful about how you build the IntentSender:
 * often, for example, the base Intent you supply will have the component
 * name explicitly set to one of your own components, to ensure it is ultimately
 * sent there and nowhere else.
 *
 * <p>A IntentSender itself is simply a reference to a token maintained by
 * the system describing the original data used to retrieve it.  This means
 * that, even if its owning application's process is killed, the
 * IntentSender itself will remain usable from other processes that
 * have been given it.  If the creating application later re-retrieves the
 * same kind of IntentSender (same operation, same Intent action, data,
 * categories, and components, and same flags), it will receive a IntentSender
 * representing the same token if that is still valid.
 *
 * <p>Instances of this class can not be made directly, but rather must be
 * created from an existing {@link android.app.PendingIntent} with
 * {@link android.app.PendingIntent#getIntentSender() PendingIntent.getIntentSender()}.
 */
public class IntentSender implements Parcelable {
    /** If enabled consider the deprecated @hide method as removed. */
    @ChangeId
    @EnabledAfter(targetSdkVersion = VANILLA_ICE_CREAM)
    private static final long REMOVE_HIDDEN_SEND_INTENT_METHOD = 356174596;

    private static final Bundle SEND_INTENT_DEFAULT_OPTIONS =
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_COMPAT).toBundle();

    @UnsupportedAppUsage
    private final IIntentSender mTarget;
    IBinder mWhitelistToken;

    // cached pending intent information
    private @Nullable PendingIntentInfo mCachedInfo;

    /**
     * Exception thrown when trying to send through a PendingIntent that
     * has been canceled or is otherwise no longer able to execute the request.
     */
    public static class SendIntentException extends AndroidException {
        public SendIntentException() {
        }

        public SendIntentException(String name) {
            super(name);
        }

        public SendIntentException(Exception cause) {
            super(cause);
        }
    }

    /**
     * Callback interface for discovering when a send operation has
     * completed.  Primarily for use with a IntentSender that is
     * performing a broadcast, this provides the same information as
     * calling {@link Context#sendOrderedBroadcast(Intent, String,
     * android.content.BroadcastReceiver, Handler, int, String, Bundle)
     * Context.sendBroadcast()} with a final BroadcastReceiver.
     */
    public interface OnFinished {
        /**
         * Called when a send operation as completed.
         *
         * @param IntentSender The IntentSender this operation was sent through.
         * @param intent The original Intent that was sent.
         * @param resultCode The final result code determined by the send.
         * @param resultData The final data collected by a broadcast.
         * @param resultExtras The final extras collected by a broadcast.
         */
        void onSendFinished(IntentSender IntentSender, Intent intent,
                int resultCode, String resultData, Bundle resultExtras);
    }

    private static class FinishedDispatcher extends IIntentReceiver.Stub
            implements Runnable {
        private final IntentSender mIntentSender;
        private final OnFinished mWho;
        private final Executor mExecutor;
        private Intent mIntent;
        private int mResultCode;
        private String mResultData;
        private Bundle mResultExtras;
        FinishedDispatcher(IntentSender pi, OnFinished who, Executor executor) {
            mIntentSender = pi;
            mWho = who;
            mExecutor = executor;
        }
        public void performReceive(Intent intent, int resultCode, String data,
                Bundle extras, boolean serialized, boolean sticky, int sendingUser) {
            mIntent = intent;
            mResultCode = resultCode;
            mResultData = data;
            mResultExtras = extras;
            if (mExecutor == null) {
                run();
            } else {
                mExecutor.execute(this);
            }
        }
        public void run() {
            mWho.onSendFinished(mIntentSender, mIntent, mResultCode,
                    mResultData, mResultExtras);
        }
    }

    /**
     * Perform the operation associated with this IntentSender, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * @param context The Context of the caller.  This may be {@code null} if
     * <var>intent</var> is also {@code null}.
     * @param code Result code to supply back to the IntentSender's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use {@code null} to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or {@code null} for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If {@code null}, the callback will happen from the thread
     * pool of the process.
     *
     *
     * @throws SendIntentException Throws CanceledIntentException if the IntentSender
     * is no longer allowing more intents to be sent through it.
     */
    public void sendIntent(Context context, int code, Intent intent,
            OnFinished onFinished, Handler handler) throws SendIntentException {
        sendIntent(context, code, intent, null, SEND_INTENT_DEFAULT_OPTIONS,
                handler == null ? null : handler::post, onFinished);
    }

    /**
     * Perform the operation associated with this IntentSender, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * @param context The Context of the caller.  This may be {@code null} if
     * <var>intent</var> is also {@code null}.
     * @param code Result code to supply back to the IntentSender's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use {@code null} to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or {@code null} for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If {@code null}, the callback will happen from the thread
     * pool of the process.
     * @param requiredPermission Name of permission that a recipient of the PendingIntent
     * is required to hold.  This is only valid for broadcast intents, and
     * corresponds to the permission argument in
     * {@link Context#sendBroadcast(Intent, String) Context.sendOrderedBroadcast(Intent, String)}.
     * If {@code null}, no permission is required.
     *
     *
     * @throws SendIntentException Throws CanceledIntentException if the IntentSender
     * is no longer allowing more intents to be sent through it.
     */
    public void sendIntent(Context context, int code, Intent intent,
            OnFinished onFinished, Handler handler, String requiredPermission)
            throws SendIntentException {
        sendIntent(context, code, intent, requiredPermission, SEND_INTENT_DEFAULT_OPTIONS,
                handler == null ? null : handler::post, onFinished);
    }

    /**
     * Perform the operation associated with this IntentSender, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * @param context The Context of the caller.  This may be {@code null} if
     * <var>intent</var> is also {@code null}.
     * @param code Result code to supply back to the IntentSender's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use {@code null} to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or {@code null} for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If {@code null}, the callback will happen from the thread
     * pool of the process.
     * @param options Additional options the caller would like to provide to modify the sending
     * behavior.  Typically built from using {@link ActivityOptions} to apply to an activity start.
     *
     * @throws SendIntentException Throws CanceledIntentException if the IntentSender
     * is no longer allowing more intents to be sent through it.
     *
     * @deprecated use {@link #sendIntent(Context, int, Intent, String, Bundle, Executor,
     *         OnFinished)}
     *
     * @hide
     */
    @Deprecated public void sendIntent(Context context, int code, Intent intent,
            OnFinished onFinished, Handler handler, String requiredPermission,
            @Nullable Bundle options)
            throws SendIntentException {
        if (CompatChanges.isChangeEnabled(REMOVE_HIDDEN_SEND_INTENT_METHOD)) {
            throw new NoSuchMethodError("This overload of sendIntent was removed.");
        }
        sendIntent(context, code, intent, requiredPermission, options,
                handler == null ? null : handler::post, onFinished);
    }

    /**
     * Perform the operation associated with this IntentSender, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * @param context The Context of the caller.  This may be {@code null} if
     * <var>intent</var> is also {@code null}.
     * @param code Result code to supply back to the IntentSender's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use {@code null} to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or {@code null} for no callback.
     * @param executor Executor identifying the thread on which the callback
     * should happen.  If {@code null}, the callback will happen from the thread
     * pool of the process.
     * @param requiredPermission Name of permission that a recipient of the PendingIntent
     * is required to hold.  This is only valid for broadcast intents, and
     * corresponds to the permission argument in
     * {@link Context#sendBroadcast(Intent, String) Context.sendOrderedBroadcast(Intent, String)}.
     * If {@code null}, no permission is required.
     * @param options Additional options the caller would like to provide to modify the sending
     * behavior.  Typically built from using {@link ActivityOptions} to apply to an activity start.
     *
     * @throws SendIntentException Throws CanceledIntentException if the IntentSender
     * is no longer allowing more intents to be sent through it.
     */
    @FlaggedApi(Flags.FLAG_BAL_SEND_INTENT_WITH_OPTIONS)
    public void sendIntent(@Nullable Context context, int code, @Nullable Intent intent,
            @Nullable String requiredPermission, @Nullable Bundle options,
            @Nullable Executor executor, @Nullable OnFinished onFinished)
            throws SendIntentException {
        try {
            if (intent != null) {
                intent.collectExtraIntentKeys();
            }
            String resolvedType = intent != null ?
                    intent.resolveTypeIfNeeded(context.getContentResolver())
                    : null;
            final IApplicationThread app = ActivityThread.currentActivityThread()
                    .getApplicationThread();
            int res = ActivityManager.getService().sendIntentSender(app, mTarget, mWhitelistToken,
                    code, intent, resolvedType,
                    onFinished != null
                            ? new FinishedDispatcher(this, onFinished, executor)
                            : null,
                    requiredPermission, options);
            if (res < 0) {
                throw new SendIntentException();
            }
        } catch (RemoteException e) {
            throw new SendIntentException();
        }
    }

    /**
     * @deprecated Renamed to {@link #getCreatorPackage()}.
     */
    @Deprecated
    public String getTargetPackage() {
        return getCreatorPackage();
    }

    /**
     * Return the package name of the application that created this
     * IntentSender, that is the identity under which you will actually be
     * sending the Intent.  The returned string is supplied by the system, so
     * that an application can not spoof its package.
     *
     * @return The package name of the PendingIntent, or {@code null} if there is
     * none associated with it.
     */
    public String getCreatorPackage() {
        return getCachedInfo().getCreatorPackage();
    }

    /**
     * Return the uid of the application that created this
     * PendingIntent, that is the identity under which you will actually be
     * sending the Intent.  The returned integer is supplied by the system, so
     * that an application can not spoof its uid.
     *
     * @return The uid of the PendingIntent, or -1 if there is
     * none associated with it.
     */
    public int getCreatorUid() {
        return getCachedInfo().getCreatorUid();
    }

    /**
     * Return the user handle of the application that created this
     * PendingIntent, that is the user under which you will actually be
     * sending the Intent.  The returned UserHandle is supplied by the system, so
     * that an application can not spoof its user.  See
     * {@link android.os.Process#myUserHandle() Process.myUserHandle()} for
     * more explanation of user handles.
     *
     * @return The user handle of the PendingIntent,  {@code null} if there is
     * none associated with it.
     */
    public UserHandle getCreatorUserHandle() {
        int uid = getCachedInfo().getCreatorUid();
        return uid > 0 ? new UserHandle(UserHandle.getUserId(uid)) : null;
    }

    /**
     * Comparison operator on two IntentSender objects, such that true
     * is returned then they both represent the same operation from the
     * same package.
     */
    @Override
    public boolean equals(@Nullable Object otherObj) {
        if (otherObj instanceof IntentSender) {
            return mTarget.asBinder().equals(((IntentSender)otherObj)
                    .mTarget.asBinder());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mTarget.asBinder().hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("IntentSender{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(": ");
        sb.append(mTarget != null ? mTarget.asBinder() : null);
        sb.append('}');
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mTarget.asBinder());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<IntentSender> CREATOR
            = new Parcelable.Creator<IntentSender>() {
        public IntentSender createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null ? new IntentSender(target) : null;
        }

        public IntentSender[] newArray(int size) {
            return new IntentSender[size];
        }
    };

    /**
     * Convenience function for writing either a IntentSender or {@code null} pointer to
     * a Parcel.  You must use this with {@link #readIntentSenderOrNullFromParcel}
     * for later reading it.
     *
     * @param sender The IntentSender to write, or {@code null}.
     * @param out Where to write the IntentSender.
     */
    public static void writeIntentSenderOrNullToParcel(IntentSender sender,
            Parcel out) {
        out.writeStrongBinder(sender != null ? sender.mTarget.asBinder()
                : null);
    }

    /**
     * Convenience function for reading either a Messenger or {@code null} pointer from
     * a Parcel.  You must have previously written the Messenger with
     * {@link #writeIntentSenderOrNullToParcel}.
     *
     * @param in The Parcel containing the written Messenger.
     *
     * @return Returns the Messenger read from the Parcel, or @code null} if @code null} had
     * been written.
     */
    public static IntentSender readIntentSenderOrNullFromParcel(Parcel in) {
        IBinder b = in.readStrongBinder();
        return b != null ? new IntentSender(b) : null;
    }

    /** @hide */
    @UnsupportedAppUsage
    public IIntentSender getTarget() {
        return mTarget;
    }

    /** @hide */
    public IBinder getWhitelistToken() {
        return mWhitelistToken;
    }

    /** @hide */
    @UnsupportedAppUsage
    public IntentSender(IIntentSender target) {
        mTarget = target;
    }

    /** @hide */
    public IntentSender(IIntentSender target, IBinder allowlistToken) {
        mTarget = target;
        mWhitelistToken = allowlistToken;
    }

    /** @hide */
    public IntentSender(IBinder target) {
        mTarget = IIntentSender.Stub.asInterface(target);
    }

    private PendingIntentInfo getCachedInfo() {
        if (mCachedInfo == null) {
            try {
                mCachedInfo = ActivityManager.getService().getInfoForIntentSender(mTarget);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        return mCachedInfo;
    }

    /**
     * Check if the PendingIntent is marked with {@link android.app.PendingIntent#FLAG_IMMUTABLE}.
     * @hide
     */
    public boolean isImmutable() {
        return getCachedInfo().isImmutable();
    }
}
