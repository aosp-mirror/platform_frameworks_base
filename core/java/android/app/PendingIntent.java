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

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AndroidException;

/**
 * A description of an Intent and target action to perform with it.  Instances
 * of this class are created with {@link #getActivity},
 * {@link #getBroadcast}, {@link #getService}; the returned object can be
 * handed to other applications so that they can perform the action you
 * described on your behalf at a later time.
 *
 * <p>By giving a PendingIntent to another application,
 * you are granting it the right to perform the operation you have specified
 * as if the other application was yourself (with the same permissions and
 * identity).  As such, you should be careful about how you build the PendingIntent:
 * often, for example, the base Intent you supply will have the component
 * name explicitly set to one of your own components, to ensure it is ultimately
 * sent there and nowhere else.
 *
 * <p>A PendingIntent itself is simply a reference to a token maintained by
 * the system describing the original data used to retrieve it.  This means
 * that, even if its owning application's process is killed, the
 * PendingIntent itself will remain usable from other processes that
 * have been given it.  If the creating application later re-retrieves the
 * same kind of PendingIntent (same operation, same Intent action, data,
 * categories, and components, and same flags), it will receive a PendingIntent
 * representing the same token if that is still valid, and can thus call
 * {@link #cancel} to remove it.
 */
public final class PendingIntent implements Parcelable {
    private final IIntentSender mTarget;

    /**
     * Flag for use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}: this
     * PendingIntent can only be used once.  If set, after
     * {@link #send()} is called on it, it will be automatically
     * canceled for you and any future attempt to send through it will fail.
     */
    public static final int FLAG_ONE_SHOT = 1<<30;
    /**
     * Flag for use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}: if the described PendingIntent does not already
     * exist, then simply return null instead of creating it.
     */
    public static final int FLAG_NO_CREATE = 1<<29;
    /**
     * Flag for use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}: if the described PendingIntent already exists,
     * the current one is canceled before generating a new one.  You can use
     * this to retrieve a new PendingIntent when you are only changing the
     * extra data in the Intent; by canceling the previous pending intent,
     * this ensures that only entities given the new data will be able to
     * launch it.  If this assurance is not an issue, consider
     * {@link #FLAG_UPDATE_CURRENT}.
     */
    public static final int FLAG_CANCEL_CURRENT = 1<<28;
    /**
     * Flag for use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}: if the described PendingIntent already exists,
     * then keep it but its replace its extra data with what is in this new
     * Intent.  This can be used if you are creating intents where only the
     * extras change, and don't care that any entities that received your
     * previous PendingIntent will be able to launch it with your new
     * extras even if they are not explicitly given to it.
     */
    public static final int FLAG_UPDATE_CURRENT = 1<<27;

    /**
     * Exception thrown when trying to send through a PendingIntent that
     * has been canceled or is otherwise no longer able to execute the request.
     */
    public static class CanceledException extends AndroidException {
        public CanceledException() {
        }

        public CanceledException(String name) {
            super(name);
        }

        public CanceledException(Exception cause) {
            super(cause);
        }
    }

    /**
     * Callback interface for discovering when a send operation has
     * completed.  Primarily for use with a PendingIntent that is
     * performing a broadcast, this provides the same information as
     * calling {@link Context#sendOrderedBroadcast(Intent, String,
     * android.content.BroadcastReceiver, Handler, int, String, Bundle)
     * Context.sendBroadcast()} with a final BroadcastReceiver.
     */
    public interface OnFinished {
        /**
         * Called when a send operation as completed.
         *
         * @param pendingIntent The PendingIntent this operation was sent through.
         * @param intent The original Intent that was sent.
         * @param resultCode The final result code determined by the send.
         * @param resultData The final data collected by a broadcast.
         * @param resultExtras The final extras collected by a broadcast.
         */
        void onSendFinished(PendingIntent pendingIntent, Intent intent,
                int resultCode, String resultData, Bundle resultExtras);
    }

    private static class FinishedDispatcher extends IIntentReceiver.Stub
            implements Runnable {
        private final PendingIntent mPendingIntent;
        private final OnFinished mWho;
        private final Handler mHandler;
        private Intent mIntent;
        private int mResultCode;
        private String mResultData;
        private Bundle mResultExtras;
        FinishedDispatcher(PendingIntent pi, OnFinished who, Handler handler) {
            mPendingIntent = pi;
            mWho = who;
            mHandler = handler;
        }
        public void performReceive(Intent intent, int resultCode,
                String data, Bundle extras, boolean serialized, boolean sticky) {
            mIntent = intent;
            mResultCode = resultCode;
            mResultData = data;
            mResultExtras = extras;
            if (mHandler == null) {
                run();
            } else {
                mHandler.post(this);
            }
        }
        public void run() {
            mWho.onSendFinished(mPendingIntent, mIntent, mResultCode,
                    mResultData, mResultExtras);
        }
    }

    /**
     * Retrieve a PendingIntent that will start a new activity, like calling
     * {@link Context#startActivity(Intent) Context.startActivity(Intent)}.
     * Note that the activity will be started outside of the context of an
     * existing activity, so you must use the {@link Intent#FLAG_ACTIVITY_NEW_TASK
     * Intent.FLAG_ACTIVITY_NEW_TASK} launch flag in the Intent.
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender (currently
     * not used).
     * @param intent Intent of the activity to be launched.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getActivity(Context context, int requestCode,
            Intent intent, int flags) {
        String packageName = context.getPackageName();
        String resolvedType = intent != null ? intent.resolveTypeIfNeeded(
                context.getContentResolver()) : null;
        try {
            intent.setAllowFds(false);
            IIntentSender target =
                ActivityManagerNative.getDefault().getIntentSender(
                    IActivityManager.INTENT_SENDER_ACTIVITY, packageName,
                    null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null, flags);
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Like {@link #getActivity(Context, int, Intent, int)}, but allows an
     * array of Intents to be supplied.  The first Intent in the array is
     * taken as the primary key for the PendingIntent, like the single Intent
     * given to {@link #getActivity(Context, int, Intent, int)}.  Upon sending
     * the resulting PendingIntent, all of the Intents are started in the same
     * way as they would be by passing them to {@link Context#startActivities(Intent[])}.
     *
     * <p class="note">
     * The <em>first</em> intent in the array will be started outside of the context of an
     * existing activity, so you must use the {@link Intent#FLAG_ACTIVITY_NEW_TASK
     * Intent.FLAG_ACTIVITY_NEW_TASK} launch flag in the Intent.  (Activities after
     * the first in the array are started in the context of the previous activity
     * in the array, so FLAG_ACTIVITY_NEW_TASK is not needed nor desired for them.)
     * </p>
     *
     * <p class="note">
     * The <em>last</em> intent in the array represents the key for the
     * PendingIntent.  In other words, it is the significant element for matching
     * (as done with the single intent given to {@link #getActivity(Context, int, Intent, int)},
     * its content will be the subject of replacement by
     * {@link #send(Context, int, Intent)} and {@link #FLAG_UPDATE_CURRENT}, etc.
     * This is because it is the most specific of the supplied intents, and the
     * UI the user actually sees when the intents are started.
     * </p>
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender (currently
     * not used).
     * @param intents Array of Intents of the activities to be launched.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getActivities(Context context, int requestCode,
            Intent[] intents, int flags) {
        String packageName = context.getPackageName();
        String[] resolvedTypes = new String[intents.length];
        for (int i=0; i<intents.length; i++) {
            intents[i].setAllowFds(false);
            resolvedTypes[i] = intents[i].resolveTypeIfNeeded(context.getContentResolver());
        }
        try {
            IIntentSender target =
                ActivityManagerNative.getDefault().getIntentSender(
                    IActivityManager.INTENT_SENDER_ACTIVITY, packageName,
                    null, null, requestCode, intents, resolvedTypes, flags);
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Retrieve a PendingIntent that will perform a broadcast, like calling
     * {@link Context#sendBroadcast(Intent) Context.sendBroadcast()}.
     *
     * @param context The Context in which this PendingIntent should perform
     * the broadcast.
     * @param requestCode Private request code for the sender (currently
     * not used).
     * @param intent The Intent to be broadcast.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getBroadcast(Context context, int requestCode,
            Intent intent, int flags) {
        String packageName = context.getPackageName();
        String resolvedType = intent != null ? intent.resolveTypeIfNeeded(
                context.getContentResolver()) : null;
        try {
            intent.setAllowFds(false);
            IIntentSender target =
                ActivityManagerNative.getDefault().getIntentSender(
                    IActivityManager.INTENT_SENDER_BROADCAST, packageName,
                    null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null, flags);
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Retrieve a PendingIntent that will start a service, like calling
     * {@link Context#startService Context.startService()}.  The start
     * arguments given to the service will come from the extras of the Intent.
     *
     * @param context The Context in which this PendingIntent should start
     * the service.
     * @param requestCode Private request code for the sender (currently
     * not used).
     * @param intent An Intent describing the service to be started.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getService(Context context, int requestCode,
            Intent intent, int flags) {
        String packageName = context.getPackageName();
        String resolvedType = intent != null ? intent.resolveTypeIfNeeded(
                context.getContentResolver()) : null;
        try {
            intent.setAllowFds(false);
            IIntentSender target =
                ActivityManagerNative.getDefault().getIntentSender(
                    IActivityManager.INTENT_SENDER_SERVICE, packageName,
                    null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null, flags);
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Retrieve a IntentSender object that wraps the existing sender of the PendingIntent
     *
     * @return Returns a IntentSender object that wraps the sender of PendingIntent
     *
     */
    public IntentSender getIntentSender() {
        return new IntentSender(mTarget);
    }

    /**
     * Cancel a currently active PendingIntent.  Only the original application
     * owning an PendingIntent can cancel it.
     */
    public void cancel() {
        try {
            ActivityManagerNative.getDefault().cancelIntentSender(mTarget);
        } catch (RemoteException e) {
        }
    }

    /**
     * Perform the operation associated with this PendingIntent.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send() throws CanceledException {
        send(null, 0, null, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent.
     *
     * @param code Result code to supply back to the PendingIntent's target.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(int code) throws CanceledException {
        send(null, code, null, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, allowing the
     * caller to specify information about the Intent to use.
     *
     * @param context The Context of the caller.
     * @param code Result code to supply back to the PendingIntent's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, Intent intent)
            throws CanceledException {
        send(context, code, intent, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, allowing the
     * caller to be notified when the send has completed.
     *
     * @param code Result code to supply back to the PendingIntent's target.
     * @param onFinished The object to call back on when the send has
     * completed, or null for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If null, the callback will happen from the thread
     * pool of the process.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(int code, OnFinished onFinished, Handler handler)
            throws CanceledException {
        send(null, code, null, onFinished, handler, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * <p>For the intent parameter, a PendingIntent
     * often has restrictions on which fields can be supplied here, based on
     * how the PendingIntent was retrieved in {@link #getActivity},
     * {@link #getBroadcast}, or {@link #getService}.
     *
     * @param context The Context of the caller.  This may be null if
     * <var>intent</var> is also null.
     * @param code Result code to supply back to the PendingIntent's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use null to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or null for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If null, the callback will happen from the thread
     * pool of the process.
     *
     * @see #send()
     * @see #send(int)
     * @see #send(Context, int, Intent)
     * @see #send(int, android.app.PendingIntent.OnFinished, Handler)
     * @see #send(Context, int, Intent, OnFinished, Handler, String)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, Intent intent,
            OnFinished onFinished, Handler handler) throws CanceledException {
        send(context, code, intent, onFinished, handler, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, allowing the
     * caller to specify information about the Intent to use and be notified
     * when the send has completed.
     *
     * <p>For the intent parameter, a PendingIntent
     * often has restrictions on which fields can be supplied here, based on
     * how the PendingIntent was retrieved in {@link #getActivity},
     * {@link #getBroadcast}, or {@link #getService}.
     *
     * @param context The Context of the caller.  This may be null if
     * <var>intent</var> is also null.
     * @param code Result code to supply back to the PendingIntent's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent.  Use null to not modify the original Intent.
     * @param onFinished The object to call back on when the send has
     * completed, or null for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If null, the callback will happen from the thread
     * pool of the process.
     * @param requiredPermission Name of permission that a recipient of the PendingIntent
     * is required to hold.  This is only valid for broadcast intents, and
     * corresponds to the permission argument in
     * {@link Context#sendBroadcast(Intent, String) Context.sendOrderedBroadcast(Intent, String)}.
     * If null, no permission is required.
     *
     * @see #send()
     * @see #send(int)
     * @see #send(Context, int, Intent)
     * @see #send(int, android.app.PendingIntent.OnFinished, Handler)
     * @see #send(Context, int, Intent, OnFinished, Handler)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, Intent intent,
            OnFinished onFinished, Handler handler, String requiredPermission)
            throws CanceledException {
        try {
            String resolvedType = intent != null ?
                    intent.resolveTypeIfNeeded(context.getContentResolver())
                    : null;
            int res = mTarget.send(code, intent, resolvedType,
                    onFinished != null
                            ? new FinishedDispatcher(this, onFinished, handler)
                            : null,
                    requiredPermission);
            if (res < 0) {
                throw new CanceledException();
            }
        } catch (RemoteException e) {
            throw new CanceledException(e);
        }
    }

    /**
     * Return the package name of the application that created this
     * PendingIntent, that is the identity under which you will actually be
     * sending the Intent.  The returned string is supplied by the system, so
     * that an application can not spoof its package.
     *
     * @return The package name of the PendingIntent, or null if there is
     * none associated with it.
     */
    public String getTargetPackage() {
        try {
            return ActivityManagerNative.getDefault()
                .getPackageForIntentSender(mTarget);
        } catch (RemoteException e) {
            // Should never happen.
            return null;
        }
    }

    /**
     * @hide
     * Check to verify that this PendingIntent targets a specific package.
     */
    public boolean isTargetedToPackage() {
        try {
            return ActivityManagerNative.getDefault()
                .isIntentSenderTargetedToPackage(mTarget);
        } catch (RemoteException e) {
            // Should never happen.
            return false;
        }
    }

    /**
     * Comparison operator on two PendingIntent objects, such that true
     * is returned then they both represent the same operation from the
     * same package.  This allows you to use {@link #getActivity},
     * {@link #getBroadcast}, or {@link #getService} multiple times (even
     * across a process being killed), resulting in different PendingIntent
     * objects but whose equals() method identifies them as being the same
     * operation.
     */
    @Override
    public boolean equals(Object otherObj) {
        if (otherObj instanceof PendingIntent) {
            return mTarget.asBinder().equals(((PendingIntent)otherObj)
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
        sb.append("PendingIntent{");
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

    public static final Parcelable.Creator<PendingIntent> CREATOR
            = new Parcelable.Creator<PendingIntent>() {
        public PendingIntent createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null ? new PendingIntent(target) : null;
        }

        public PendingIntent[] newArray(int size) {
            return new PendingIntent[size];
        }
    };

    /**
     * Convenience function for writing either a PendingIntent or null pointer to
     * a Parcel.  You must use this with {@link #readPendingIntentOrNullFromParcel}
     * for later reading it.
     *
     * @param sender The PendingIntent to write, or null.
     * @param out Where to write the PendingIntent.
     */
    public static void writePendingIntentOrNullToParcel(PendingIntent sender,
            Parcel out) {
        out.writeStrongBinder(sender != null ? sender.mTarget.asBinder()
                : null);
    }

    /**
     * Convenience function for reading either a Messenger or null pointer from
     * a Parcel.  You must have previously written the Messenger with
     * {@link #writePendingIntentOrNullToParcel}.
     *
     * @param in The Parcel containing the written Messenger.
     *
     * @return Returns the Messenger read from the Parcel, or null if null had
     * been written.
     */
    public static PendingIntent readPendingIntentOrNullFromParcel(Parcel in) {
        IBinder b = in.readStrongBinder();
        return b != null ? new PendingIntent(b) : null;
    }

    /*package*/ PendingIntent(IIntentSender target) {
        mTarget = target;
    }

    /*package*/ PendingIntent(IBinder target) {
        mTarget = IIntentSender.Stub.asInterface(target);
    }

    /** @hide */
    public IIntentSender getTarget() {
        return mTarget;
    }
}
