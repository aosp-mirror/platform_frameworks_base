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

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.ActivityManager.INTENT_SENDER_BROADCAST;
import static android.app.ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE;
import static android.app.ActivityManager.INTENT_SENDER_SERVICE;

import android.Manifest.permission;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.annotation.TestApi;
import android.app.ActivityManager.PendingIntentInfo;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager.ResolveInfoFlagsBits;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AndroidException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A description of an Intent and target action to perform with it.  Instances
 * of this class are created with {@link #getActivity}, {@link #getActivities},
 * {@link #getBroadcast}, and {@link #getService}; the returned object can be
 * handed to other applications so that they can perform the action you
 * described on your behalf at a later time.
 *
 * <p>By giving a PendingIntent to another application,
 * you are granting it the right to perform the operation you have specified
 * as if the other application was yourself (with the same permissions and
 * identity).  As such, you should be careful about how you build the PendingIntent:
 * almost always, for example, the base Intent you supply should have the component
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
 *
 * <p>Because of this behavior, it is important to know when two Intents
 * are considered to be the same for purposes of retrieving a PendingIntent.
 * A common mistake people make is to create multiple PendingIntent objects
 * with Intents that only vary in their "extra" contents, expecting to get
 * a different PendingIntent each time.  This does <em>not</em> happen.  The
 * parts of the Intent that are used for matching are the same ones defined
 * by {@link Intent#filterEquals(Intent) Intent.filterEquals}.  If you use two
 * Intent objects that are equivalent as per
 * {@link Intent#filterEquals(Intent) Intent.filterEquals}, then you will get
 * the same PendingIntent for both of them.
 *
 * <p>There are two typical ways to deal with this.
 *
 * <p>If you truly need multiple distinct PendingIntent objects active at
 * the same time (such as to use as two notifications that are both shown
 * at the same time), then you will need to ensure there is something that
 * is different about them to associate them with different PendingIntents.
 * This may be any of the Intent attributes considered by
 * {@link Intent#filterEquals(Intent) Intent.filterEquals}, or different
 * request code integers supplied to {@link #getActivity}, {@link #getActivities},
 * {@link #getBroadcast}, or {@link #getService}.
 *
 * <p>If you only need one PendingIntent active at a time for any of the
 * Intents you will use, then you can alternatively use the flags
 * {@link #FLAG_CANCEL_CURRENT} or {@link #FLAG_UPDATE_CURRENT} to either
 * cancel or modify whatever current PendingIntent is associated with the
 * Intent you are supplying.
 *
 * <p>Also note that flags like {@link #FLAG_ONE_SHOT} or {@link #FLAG_IMMUTABLE} describe the
 * PendingIntent instance and thus, are used to identify it. Any calls to retrieve or modify a
 * PendingIntent created with these flags will also require these flags to be supplied in
 * conjunction with others. E.g. To retrieve an existing PendingIntent created with
 * FLAG_ONE_SHOT, <b>both</b> FLAG_ONE_SHOT and FLAG_NO_CREATE need to be supplied.
 */
public final class PendingIntent implements Parcelable {
    private static final String TAG = "PendingIntent";
    @NonNull
    private final IIntentSender mTarget;
    private IBinder mWhitelistToken;

    // cached pending intent information
    private @Nullable PendingIntentInfo mCachedInfo;

    /**
     * Structure to store information related to {@link #addCancelListener}, which is rarely used,
     * so we lazily allocate it to keep the PendingIntent class size small.
     */
    private final class CancelListerInfo extends IResultReceiver.Stub {
        private final ArraySet<Pair<Executor, CancelListener>> mCancelListeners = new ArraySet<>();

        /**
         * Whether the PI is canceled or not. Note this is essentially a "cache" that's updated
         * only when the client uses {@link #addCancelListener}. Even if this is false, that
         * still doesn't know the PI is *not* canceled, but if it's true, this PI is definitely
         * canceled.
         */
        private boolean mCanceled;

        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            notifyCancelListeners();
        }
    }

    @GuardedBy("mTarget")
    private @Nullable CancelListerInfo mCancelListerInfo;

    /**
     * It is now required to specify either {@link #FLAG_IMMUTABLE}
     * or {@link #FLAG_MUTABLE} when creating a PendingIntent.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.R)
    static final long PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED = 160794467L;

    /** @hide */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Overridable
    public static final long BLOCK_MUTABLE_IMPLICIT_PENDING_INTENT = 236704164L;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    FLAG_ONE_SHOT,
                    FLAG_NO_CREATE,
                    FLAG_CANCEL_CURRENT,
                    FLAG_UPDATE_CURRENT,
                    FLAG_IMMUTABLE,
                    FLAG_MUTABLE,
                    FLAG_MUTABLE_UNAUDITED,
                    FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,

                    Intent.FILL_IN_ACTION,
                    Intent.FILL_IN_DATA,
                    Intent.FILL_IN_CATEGORIES,
                    Intent.FILL_IN_COMPONENT,
                    Intent.FILL_IN_PACKAGE,
                    Intent.FILL_IN_SOURCE_BOUNDS,
                    Intent.FILL_IN_SELECTOR,
                    Intent.FILL_IN_CLIP_DATA
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /**
     * Flag indicating that this PendingIntent can be used only once.
     * For use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}. <p>If set, after
     * {@link #send()} is called on it, it will be automatically
     * canceled for you and any future attempt to send through it will fail.
     */
    public static final int FLAG_ONE_SHOT = 1<<30;
    /**
     * Flag indicating that if the described PendingIntent does not
     * already exist, then simply return null instead of creating it.
     * For use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}.
     */
    public static final int FLAG_NO_CREATE = 1<<29;
    /**
     * Flag indicating that if the described PendingIntent already exists,
     * the current one should be canceled before generating a new one.
     * For use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}. <p>You can use
     * this to retrieve a new PendingIntent when you are only changing the
     * extra data in the Intent; by canceling the previous pending intent,
     * this ensures that only entities given the new data will be able to
     * launch it.  If this assurance is not an issue, consider
     * {@link #FLAG_UPDATE_CURRENT}.
     */
    public static final int FLAG_CANCEL_CURRENT = 1<<28;
    /**
     * Flag indicating that if the described PendingIntent already exists,
     * then keep it but replace its extra data with what is in this new
     * Intent. For use with {@link #getActivity}, {@link #getBroadcast}, and
     * {@link #getService}. <p>This can be used if you are creating intents where only the
     * extras change, and don't care that any entities that received your
     * previous PendingIntent will be able to launch it with your new
     * extras even if they are not explicitly given to it.
     *
     * <p>{@link #FLAG_UPDATE_CURRENT} still works even if {@link
     * #FLAG_IMMUTABLE} is set - the creator of the PendingIntent can always
     * update the PendingIntent itself. The IMMUTABLE flag only limits the
     * ability to alter the semantics of the intent that is sent by {@link
     * #send} by the invoker of {@link #send}.
     */
    public static final int FLAG_UPDATE_CURRENT = 1<<27;

    /**
     * Flag indicating that the created PendingIntent should be immutable.
     * This means that the additional intent argument passed to the send
     * methods to fill in unpopulated properties of this intent will be
     * ignored.
     *
     * <p>{@link #FLAG_IMMUTABLE} only limits the ability to alter the
     * semantics of the intent that is sent by {@link #send} by the invoker of
     * {@link #send}. The creator of the PendingIntent can always update the
     * PendingIntent itself via {@link #FLAG_UPDATE_CURRENT}.
     */
    public static final int FLAG_IMMUTABLE = 1<<26;

    /**
     * Flag indicating that the created PendingIntent should be mutable.
     * This flag cannot be combined with {@link #FLAG_IMMUTABLE}. <p>Up until
     * {@link android.os.Build.VERSION_CODES#R}, PendingIntents are assumed to
     * be mutable by default, unless {@link #FLAG_IMMUTABLE} is set. Starting
     * with {@link android.os.Build.VERSION_CODES#S}, it will be required to
     * explicitly specify the mutability of PendingIntents on creation with
     * either {@link #FLAG_IMMUTABLE} or {@link #FLAG_MUTABLE}. It is strongly
     * recommended to use {@link #FLAG_IMMUTABLE} when creating a
     * PendingIntent. {@link #FLAG_MUTABLE} should only be used when some
     * functionality relies on modifying the underlying intent, e.g. any
     * PendingIntent that needs to be used with inline reply or bubbles.
     */
    public static final int FLAG_MUTABLE = 1<<25;

    /**
     * @deprecated Use {@link #FLAG_IMMUTABLE} or {@link #FLAG_MUTABLE} instead.
     * @hide
     */
    @Deprecated
    @TestApi
    public static final int FLAG_MUTABLE_UNAUDITED = FLAG_MUTABLE;

    /**
     * Flag indicating that the created PendingIntent with {@link #FLAG_MUTABLE}
     * is allowed to have an unsafe implicit Intent within. <p>Starting with
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, for apps that
     * target SDK {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or
     * higher, creation of a PendingIntent with {@link #FLAG_MUTABLE} and an
     * implicit Intent within will throw an {@link IllegalArgumentException}
     * for security reasons. To bypass this check, use
     * {@link #FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT} when creating a PendingIntent.
     * However, it is strongly recommended to not to use this flag and make the
     * Intent explicit or the PendingIntent immutable, thereby making the Intent
     * safe.
     */
    public static final int FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 1<<24;

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
        private static Handler sDefaultSystemHandler;
        FinishedDispatcher(PendingIntent pi, OnFinished who, Handler handler) {
            mPendingIntent = pi;
            mWho = who;
            if (handler == null && ActivityThread.isSystem()) {
                // We assign a default handler for the system process to avoid deadlocks when
                // processing receivers in various components that hold global service locks.
                if (sDefaultSystemHandler == null) {
                    sDefaultSystemHandler = new Handler(Looper.getMainLooper());
                }
                mHandler = sDefaultSystemHandler;
            } else {
                mHandler = handler;
            }
        }
        public void performReceive(Intent intent, int resultCode, String data,
                Bundle extras, boolean serialized, boolean sticky, int sendingUser) {
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
     * Listener for observing when pending intents are written to a parcel.
     *
     * @hide
     */
    public interface OnMarshaledListener {
        /**
         * Called when a pending intent is written to a parcel.
         *
         * @param intent The pending intent.
         * @param parcel The parcel to which it was written.
         * @param flags The parcel flags when it was written.
         */
        void onMarshaled(PendingIntent intent, Parcel parcel, int flags);
    }

    private static final ThreadLocal<List<OnMarshaledListener>> sOnMarshaledListener =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Registers an listener for pending intents being written to a parcel. This replaces any
     * listeners previously added.
     *
     * @param listener The listener, null to clear.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void setOnMarshaledListener(OnMarshaledListener listener) {
        final List<OnMarshaledListener> listeners = sOnMarshaledListener.get();
        listeners.clear();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Adds a listener for pending intents being written to a parcel.
     * @hide
     */
    static void addOnMarshaledListener(OnMarshaledListener listener) {
        sOnMarshaledListener.get().add(listener);
    }

    /**
     * Removes a listener for pending intents being written to a parcel.
     * @hide
     */
    static void removeOnMarshaledListener(OnMarshaledListener listener) {
        sOnMarshaledListener.get().remove(listener);
    }

    private static void checkPendingIntent(int flags, @NonNull Intent intent,
            @NonNull Context context, boolean isActivityResultType) {
        final boolean isFlagImmutableSet = (flags & PendingIntent.FLAG_IMMUTABLE) != 0;
        final boolean isFlagMutableSet = (flags & PendingIntent.FLAG_MUTABLE) != 0;
        final String packageName = context.getPackageName();

        if (isFlagImmutableSet && isFlagMutableSet) {
            throw new IllegalArgumentException(
                "Cannot set both FLAG_IMMUTABLE and FLAG_MUTABLE for PendingIntent");
        }

        if (Compatibility.isChangeEnabled(PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED)
                && !isFlagImmutableSet && !isFlagMutableSet) {
            String msg = packageName + ": Targeting S+ (version " + Build.VERSION_CODES.S
                    + " and above) requires that one of FLAG_IMMUTABLE or FLAG_MUTABLE"
                    + " be specified when creating a PendingIntent.\nStrongly consider"
                    + " using FLAG_IMMUTABLE, only use FLAG_MUTABLE if some functionality"
                    + " depends on the PendingIntent being mutable, e.g. if it needs to"
                    + " be used with inline replies or bubbles.";
                throw new IllegalArgumentException(msg);
        }

        // For apps with target SDK < U, warn that creation or retrieval of a mutable implicit
        // PendingIntent that is not of type {@link ActivityManager#INTENT_SENDER_ACTIVITY_RESULT}
        // will be blocked from target SDK U onwards for security reasons. The block itself
        // happens on the server side, but this warning has to stay here to preserve the client
        // side stack trace for app developers.
        if (isNewMutableDisallowedImplicitPendingIntent(flags, intent, isActivityResultType)
                && !Compatibility.isChangeEnabled(BLOCK_MUTABLE_IMPLICIT_PENDING_INTENT)) {
            String msg = "New mutable implicit PendingIntent: pkg=" + packageName
                    + ", action=" + intent.getAction()
                    + ", featureId=" + context.getAttributionTag()
                    + ". This will be blocked once the app targets U+"
                    + " for security reasons.";
            Log.w(TAG, new StackTrace(msg));
        }
    }

    /** @hide */
    public static boolean isNewMutableDisallowedImplicitPendingIntent(int flags,
            @NonNull Intent intent, boolean isActivityResultType) {
        if (isActivityResultType) {
            // Pending intents of type {@link ActivityManager#INTENT_SENDER_ACTIVITY_RESULT}
            // should be ignored as they are intrinsically tied to a target which means they
            // are already explicit.
            return false;
        }
        boolean isFlagNoCreateSet = (flags & PendingIntent.FLAG_NO_CREATE) != 0;
        boolean isFlagMutableSet = (flags & PendingIntent.FLAG_MUTABLE) != 0;
        boolean isImplicit = (intent.getComponent() == null) && (intent.getPackage() == null);
        boolean isFlagAllowUnsafeImplicitIntentSet =
                (flags & PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT) != 0;
        return !isFlagNoCreateSet && isFlagMutableSet && isImplicit
                && !isFlagAllowUnsafeImplicitIntentSet;
    }

    /**
     * Retrieve a PendingIntent that will start a new activity, like calling
     * {@link Context#startActivity(Intent) Context.startActivity(Intent)}.
     * Note that the activity will be started outside of the context of an
     * existing activity, so you must use the {@link Intent#FLAG_ACTIVITY_NEW_TASK
     * Intent.FLAG_ACTIVITY_NEW_TASK} launch flag in the Intent.
     *
     * <p class="note">For security reasons, the {@link android.content.Intent}
     * you supply here should almost always be an <em>explicit intent</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender
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
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public static PendingIntent getActivity(Context context, int requestCode,
            Intent intent, @Flags int flags) {
        return getActivity(context, requestCode, intent, flags, null);
    }

    /**
     * Retrieve a PendingIntent that will start a new activity, like calling
     * {@link Context#startActivity(Intent) Context.startActivity(Intent)}.
     * Note that the activity will be started outside of the context of an
     * existing activity, so you must use the {@link Intent#FLAG_ACTIVITY_NEW_TASK
     * Intent.FLAG_ACTIVITY_NEW_TASK} launch flag in the Intent.
     *
     * <p class="note">For security reasons, the {@link android.content.Intent}
     * you supply here should almost always be an <em>explicit intent</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender
     * @param intent Intent of the activity to be launched.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     * @param options Additional options for how the Activity should be started.
     * May be null if there are no options.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public static PendingIntent getActivity(Context context, int requestCode,
            @NonNull Intent intent, @Flags int flags, @Nullable Bundle options) {
        // Some tests only mock Context.getUserId(), so fallback to the id Context.getUser() is null
        final UserHandle user = context.getUser();
        return getActivityAsUser(context, requestCode, intent, flags, options,
                user != null ? user : UserHandle.of(context.getUserId()));
    }

    /**
     * @hide
     * Note that UserHandle.CURRENT will be interpreted at the time the
     * activity is started, not when the pending intent is created.
     */
    @UnsupportedAppUsage
    public static PendingIntent getActivityAsUser(Context context, int requestCode,
            @NonNull Intent intent, int flags, Bundle options, UserHandle user) {
        String packageName = context.getPackageName();
        String resolvedType = intent.resolveTypeIfNeeded(context.getContentResolver());
        checkPendingIntent(flags, intent, context, /* isActivityResultType */ false);
        try {
            intent.migrateExtraStreamToClipData(context);
            intent.prepareToLeaveProcess(context);
            IIntentSender target =
                ActivityManager.getService().getIntentSenderWithFeature(
                    INTENT_SENDER_ACTIVITY, packageName,
                    context.getAttributionTag(), null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null,
                    flags, options, user.getIdentifier());
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #getActivity(Context, int, Intent, int)}, but allows an
     * array of Intents to be supplied.  The last Intent in the array is
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
     * <p class="note">For security reasons, the {@link android.content.Intent} objects
     * you supply here should almost always be <em>explicit intents</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender
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
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public static PendingIntent getActivities(Context context, int requestCode,
            @NonNull Intent[] intents, @Flags int flags) {
        return getActivities(context, requestCode, intents, flags, null);
    }

    /**
     * Like {@link #getActivity(Context, int, Intent, int)}, but allows an
     * array of Intents to be supplied.  The last Intent in the array is
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
     * <p class="note">For security reasons, the {@link android.content.Intent} objects
     * you supply here should almost always be <em>explicit intents</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the activity.
     * @param requestCode Private request code for the sender
     * @param intents Array of Intents of the activities to be launched.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * {@link #FLAG_IMMUTABLE} or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public static PendingIntent getActivities(Context context, int requestCode,
            @NonNull Intent[] intents, @Flags int flags, @Nullable Bundle options) {
        // Some tests only mock Context.getUserId(), so fallback to the id Context.getUser() is null
        final UserHandle user = context.getUser();
        return getActivitiesAsUser(context, requestCode, intents, flags, options,
                user != null ? user : UserHandle.of(context.getUserId()));
    }

    /**
     * @hide
     * Note that UserHandle.CURRENT will be interpreted at the time the
     * activity is started, not when the pending intent is created.
     */
    public static PendingIntent getActivitiesAsUser(Context context, int requestCode,
            @NonNull Intent[] intents, int flags, Bundle options, UserHandle user) {
        String packageName = context.getPackageName();
        String[] resolvedTypes = new String[intents.length];
        for (int i=0; i<intents.length; i++) {
            intents[i].migrateExtraStreamToClipData(context);
            intents[i].prepareToLeaveProcess(context);
            resolvedTypes[i] = intents[i].resolveTypeIfNeeded(context.getContentResolver());
            checkPendingIntent(flags, intents[i], context, /* isActivityResultType */ false);
        }
        try {
            IIntentSender target =
                ActivityManager.getService().getIntentSenderWithFeature(
                    INTENT_SENDER_ACTIVITY, packageName,
                    context.getAttributionTag(), null, null, requestCode, intents, resolvedTypes,
                    flags, options, user.getIdentifier());
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve a PendingIntent that will perform a broadcast, like calling
     * {@link Context#sendBroadcast(Intent) Context.sendBroadcast()}.
     *
     * <p class="note">For security reasons, the {@link android.content.Intent}
     * you supply here should almost always be an <em>explicit intent</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should perform
     * the broadcast.
     * @param requestCode Private request code for the sender
     * @param intent The Intent to be broadcast.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * {@link #FLAG_IMMUTABLE} or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    @SuppressWarnings("AndroidFrameworkPendingIntentMutability")
    public static PendingIntent getBroadcast(Context context, int requestCode,
            @NonNull Intent intent, @Flags int flags) {
        return getBroadcastAsUser(context, requestCode, intent, flags, context.getUser());
    }

    /**
     * @hide
     * Note that UserHandle.CURRENT will be interpreted at the time the
     * broadcast is sent, not when the pending intent is created.
     */
    @UnsupportedAppUsage
    public static PendingIntent getBroadcastAsUser(Context context, int requestCode,
            Intent intent, int flags, UserHandle userHandle) {
        String packageName = context.getPackageName();
        String resolvedType = intent.resolveTypeIfNeeded(context.getContentResolver());
        checkPendingIntent(flags, intent, context, /* isActivityResultType */ false);
        try {
            intent.prepareToLeaveProcess(context);
            IIntentSender target =
                ActivityManager.getService().getIntentSenderWithFeature(
                    INTENT_SENDER_BROADCAST, packageName,
                    context.getAttributionTag(), null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null,
                    flags, null, userHandle.getIdentifier());
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve a PendingIntent that will start a service, like calling
     * {@link Context#startService Context.startService()}.  The start
     * arguments given to the service will come from the extras of the Intent.
     *
     * <p class="note">For security reasons, the {@link android.content.Intent}
     * you supply here should almost always be an <em>explicit intent</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the service.
     * @param requestCode Private request code for the sender
     * @param intent An Intent describing the service to be started.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * {@link #FLAG_IMMUTABLE} or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getService(Context context, int requestCode,
            @NonNull Intent intent, @Flags int flags) {
        return buildServicePendingIntent(context, requestCode, intent, flags,
                INTENT_SENDER_SERVICE);
    }

    /**
     * Retrieve a PendingIntent that will start a foreground service, like calling
     * {@link Context#startForegroundService Context.startForegroundService()}.  The start
     * arguments given to the service will come from the extras of the Intent.
     *
     * <p class="note">For security reasons, the {@link android.content.Intent}
     * you supply here should almost always be an <em>explicit intent</em>,
     * that is specify an explicit component to be delivered to through
     * {@link Intent#setClass(android.content.Context, Class) Intent.setClass}</p>
     *
     * @param context The Context in which this PendingIntent should start
     * the service.
     * @param requestCode Private request code for the sender
     * @param intent An Intent describing the service to be started.
     * @param flags May be {@link #FLAG_ONE_SHOT}, {@link #FLAG_NO_CREATE},
     * {@link #FLAG_CANCEL_CURRENT}, {@link #FLAG_UPDATE_CURRENT},
     * {@link #FLAG_IMMUTABLE} or any of the flags as supported by
     * {@link Intent#fillIn Intent.fillIn()} to control which unspecified parts
     * of the intent that can be supplied when the actual send happens.
     *
     * @return Returns an existing or new PendingIntent matching the given
     * parameters.  May return null only if {@link #FLAG_NO_CREATE} has been
     * supplied.
     */
    public static PendingIntent getForegroundService(Context context, int requestCode,
            @NonNull Intent intent, @Flags int flags) {
        return buildServicePendingIntent(context, requestCode, intent, flags,
                INTENT_SENDER_FOREGROUND_SERVICE);
    }

    private static PendingIntent buildServicePendingIntent(Context context, int requestCode,
            Intent intent, int flags, int serviceKind) {
        String packageName = context.getPackageName();
        String resolvedType = intent.resolveTypeIfNeeded(context.getContentResolver());
        checkPendingIntent(flags, intent, context, /* isActivityResultType */ false);
        try {
            intent.prepareToLeaveProcess(context);
            IIntentSender target =
                ActivityManager.getService().getIntentSenderWithFeature(
                    serviceKind, packageName, context.getAttributionTag(),
                    null, null, requestCode, new Intent[] { intent },
                    resolvedType != null ? new String[] { resolvedType } : null,
                    flags, null, context.getUserId());
            return target != null ? new PendingIntent(target) : null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve a IntentSender object that wraps the existing sender of the PendingIntent
     *
     * @return Returns a IntentSender object that wraps the sender of PendingIntent
     *
     */
    @NonNull
    public IntentSender getIntentSender() {
        return new IntentSender(mTarget, mWhitelistToken);
    }

    /**
     * Cancel a currently active PendingIntent.  Only the original application
     * owning a PendingIntent can cancel it.
     */
    public void cancel() {
        try {
            ActivityManager.getService().cancelIntentSender(mTarget);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Perform the operation associated with this PendingIntent.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send() throws CanceledException {
        send(null, 0, null, null, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent.
     *
     * @param code Result code to supply back to the PendingIntent's target.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(int code) throws CanceledException {
        send(null, code, null, null, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, allowing the
     * caller to specify information about the Intent to use.
     *
     * @param context The Context of the caller.
     * @param code Result code to supply back to the PendingIntent's target.
     * @param intent Additional Intent data.  See {@link Intent#fillIn
     * Intent.fillIn()} for information on how this is applied to the
     * original Intent. If flag {@link #FLAG_IMMUTABLE} was set when this
     * pending intent was created, this argument will be ignored.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, @Nullable Intent intent)
            throws CanceledException {
        send(context, code, intent, null, null, null, null);
    }

    /**
     * Perform the operation associated with this PendingIntent, supplying additional
     * options for the operation.
     *
     * @param options Additional options the caller would like to provide to modify the
     * sending behavior.  May be built from an {@link ActivityOptions} to apply to an
     * activity start.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(@Nullable Bundle options) throws CanceledException {
        send(null, 0, null, null, null, null, options);
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
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(int code, @Nullable OnFinished onFinished, @Nullable Handler handler)
            throws CanceledException {
        send(null, code, null, onFinished, handler, null, null);
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
     * If flag {@link #FLAG_IMMUTABLE} was set when this pending intent was
     * created, this argument will be ignored.
     * @param onFinished The object to call back on when the send has
     * completed, or null for no callback.
     * @param handler Handler identifying the thread on which the callback
     * should happen.  If null, the callback will happen from the thread
     * pool of the process.
     *
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, @Nullable Intent intent,
            @Nullable OnFinished onFinished, @Nullable Handler handler) throws CanceledException {
        send(context, code, intent, onFinished, handler, null, null);
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
     * If flag {@link #FLAG_IMMUTABLE} was set when this pending intent was
     * created, this argument will be ignored.
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
     * @see #send(Context, int, Intent, android.app.PendingIntent.OnFinished, Handler, String,
     *          Bundle)
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, @Nullable Intent intent,
            @Nullable OnFinished onFinished, @Nullable Handler handler,
            @Nullable String requiredPermission)
            throws CanceledException {
        send(context, code, intent, onFinished, handler, requiredPermission, null);
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
     * If flag {@link #FLAG_IMMUTABLE} was set when this pending intent was
     * created, this argument will be ignored.
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
     * @param options Additional options the caller would like to provide to modify the sending
     * behavior.  May be built from an {@link ActivityOptions} to apply to an activity start.
     *
     * @throws CanceledException Throws CanceledException if the PendingIntent
     * is no longer allowing more intents to be sent through it.
     */
    public void send(Context context, int code, @Nullable Intent intent,
            @Nullable OnFinished onFinished, @Nullable Handler handler,
            @Nullable String requiredPermission, @Nullable Bundle options)
            throws CanceledException {
        if (sendAndReturnResult(context, code, intent, onFinished, handler, requiredPermission,
                options) < 0) {
            throw new CanceledException();
        }
    }

    /**
     * Like {@link #send}, but returns the result
     * @hide
     */
    public int sendAndReturnResult(Context context, int code, @Nullable Intent intent,
            @Nullable OnFinished onFinished, @Nullable Handler handler,
            @Nullable String requiredPermission, @Nullable Bundle options)
            throws CanceledException {
        try {
            String resolvedType = intent != null ?
                    intent.resolveTypeIfNeeded(context.getContentResolver())
                    : null;

            if (context != null && isActivity()) {
                // Set the context display id as preferred for this activity launches, so that it
                // can land on caller's display. Or just brought the task to front at the display
                // where it was on since it has higher preference.
                ActivityOptions activityOptions = options != null ? new ActivityOptions(options)
                        : ActivityOptions.makeBasic();
                activityOptions.setCallerDisplayId(context.getDisplayId());
                options = activityOptions.toBundle();
            }

            final IApplicationThread app = ActivityThread.currentActivityThread()
                    .getApplicationThread();
            return ActivityManager.getService().sendIntentSender(app,
                    mTarget, mWhitelistToken, code, intent, resolvedType,
                    onFinished != null
                            ? new FinishedDispatcher(this, onFinished, handler)
                            : null,
                    requiredPermission, options);
        } catch (RemoteException e) {
            throw new CanceledException(e);
        }
    }

    /**
     * @deprecated Renamed to {@link #getCreatorPackage()}.
     */
    @Deprecated
    @Nullable
    public String getTargetPackage() {
        return getCreatorPackage();
    }

    /**
     * Return the package name of the application that created this
     * PendingIntent, that is the identity under which you will actually be
     * sending the Intent.  The returned string is supplied by the system, so
     * that an application can not spoof its package.
     *
     * <p class="note">Be careful about how you use this.  All this tells you is
     * who created the PendingIntent.  It does <strong>not</strong> tell you who
     * handed the PendingIntent to you: that is, PendingIntent objects are intended to be
     * passed between applications, so the PendingIntent you receive from an application
     * could actually be one it received from another application, meaning the result
     * you get here will identify the original application.  Because of this, you should
     * only use this information to identify who you expect to be interacting with
     * through a {@link #send} call, not who gave you the PendingIntent.</p>
     *
     * @return The package name of the PendingIntent.
     */
    @Nullable
    public String getCreatorPackage() {
        return getCachedInfo().getCreatorPackage();
    }

    /**
     * Return the uid of the application that created this
     * PendingIntent, that is the identity under which you will actually be
     * sending the Intent.  The returned integer is supplied by the system, so
     * that an application can not spoof its uid.
     *
     * <p class="note">Be careful about how you use this.  All this tells you is
     * who created the PendingIntent.  It does <strong>not</strong> tell you who
     * handed the PendingIntent to you: that is, PendingIntent objects are intended to be
     * passed between applications, so the PendingIntent you receive from an application
     * could actually be one it received from another application, meaning the result
     * you get here will identify the original application.  Because of this, you should
     * only use this information to identify who you expect to be interacting with
     * through a {@link #send} call, not who gave you the PendingIntent.</p>
     *
     * @return The uid of the PendingIntent, or -1 if there is
     * none associated with it.
     */
    public int getCreatorUid() {
        return getCachedInfo().getCreatorUid();
    }

    /**
     * @hide
     * @deprecated use {@link #addCancelListener(Executor, CancelListener)} instead.
     */
    @Deprecated
    public void registerCancelListener(@NonNull CancelListener cancelListener) {
        if (!addCancelListener(Runnable::run, cancelListener)) {
            // Call the callback right away synchronously, if the PI has been canceled already.
            cancelListener.onCanceled(this);
        }
    }

    /**
     * Register a listener to when this pendingIntent is canceled.
     *
     * @return true if the listener has been set successfully. false if the {@link PendingIntent}
     * has already been canceled.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public boolean addCancelListener(@NonNull Executor executor,
            @NonNull CancelListener cancelListener) {
        synchronized (mTarget) {
            if (mCancelListerInfo != null && mCancelListerInfo.mCanceled) {
                return false;
            }
            if (mCancelListerInfo == null) {
                mCancelListerInfo = new CancelListerInfo();
            }
            final CancelListerInfo cli = mCancelListerInfo;

            boolean wasEmpty = cli.mCancelListeners.isEmpty();
            cli.mCancelListeners.add(Pair.create(executor, cancelListener));
            if (wasEmpty) {
                boolean success;
                try {
                    success = ActivityManager.getService().registerIntentSenderCancelListenerEx(
                            mTarget, cli);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                if (!success) {
                    cli.mCanceled = true;
                }
                return success;
            } else {
                return !cli.mCanceled;
            }
        }
    }

    private void notifyCancelListeners() {
        ArraySet<Pair<Executor, CancelListener>> cancelListeners;
        synchronized (mTarget) {
            // When notifyCancelListeners() is called, mCancelListerInfo must always be non-null.
            final CancelListerInfo cli = mCancelListerInfo;
            cli.mCanceled = true;
            cancelListeners = new ArraySet<>(cli.mCancelListeners);
            cli.mCancelListeners.clear();
        }
        int size = cancelListeners.size();
        for (int i = 0; i < size; i++) {
            final Pair<Executor, CancelListener> pair = cancelListeners.valueAt(i);
            pair.first.execute(() -> pair.second.onCanceled(this));
        }
    }

    /**
     * @hide
     * @deprecated use {@link #removeCancelListener(CancelListener)} instead.
     */
    @Deprecated
    public void unregisterCancelListener(CancelListener cancelListener) {
        removeCancelListener(cancelListener);
    }

    /**
     * Un-register a listener to when this pendingIntent is canceled.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public void removeCancelListener(@NonNull CancelListener cancelListener) {
        synchronized (mTarget) {
            final CancelListerInfo cli = mCancelListerInfo;
            if (cli == null || cli.mCancelListeners.size() == 0) {
                return;
            }
            for (int i = cli.mCancelListeners.size() - 1; i >= 0; i--) {
                if (cli.mCancelListeners.valueAt(i).second == cancelListener) {
                    cli.mCancelListeners.removeAt(i);
                }
            }
            if (cli.mCancelListeners.isEmpty()) {
                try {
                    ActivityManager.getService().unregisterIntentSenderCancelListener(mTarget,
                            cli);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Return the user handle of the application that created this
     * PendingIntent, that is the user under which you will actually be
     * sending the Intent.  The returned UserHandle is supplied by the system, so
     * that an application can not spoof its user.  See
     * {@link android.os.Process#myUserHandle() Process.myUserHandle()} for
     * more explanation of user handles.
     *
     * <p class="note">Be careful about how you use this.  All this tells you is
     * who created the PendingIntent.  It does <strong>not</strong> tell you who
     * handed the PendingIntent to you: that is, PendingIntent objects are intended to be
     * passed between applications, so the PendingIntent you receive from an application
     * could actually be one it received from another application, meaning the result
     * you get here will identify the original application.  Because of this, you should
     * only use this information to identify who you expect to be interacting with
     * through a {@link #send} call, not who gave you the PendingIntent.</p>
     *
     * @return The user handle of the PendingIntent
     */
    @NonNull
    public UserHandle getCreatorUserHandle() {
        int uid = getCachedInfo().getCreatorUid();
        return UserHandle.getUserHandleForUid(uid);
    }

    /**
     * @hide
     * Check to verify that this PendingIntent targets a specific package.
     */
    public boolean isTargetedToPackage() {
        try {
            return ActivityManager.getService()
                .isIntentSenderTargetedToPackage(mTarget);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if this PendingIntent is marked with {@link #FLAG_IMMUTABLE}.
     */
    public boolean isImmutable() {
        return getCachedInfo().isImmutable();
    }

    /**
     * @return TRUE if this {@link PendingIntent} was created with
     * {@link #getActivity} or {@link #getActivities}.
     */
    public boolean isActivity() {
        return getCachedInfo().getIntentSenderType() == INTENT_SENDER_ACTIVITY;
    }

    /**
     * @return TRUE if this {@link PendingIntent} was created with {@link #getForegroundService}.
     */
    public boolean isForegroundService() {
        return getCachedInfo().getIntentSenderType() == INTENT_SENDER_FOREGROUND_SERVICE;
    }

    /**
     * @return TRUE if this {@link PendingIntent} was created with {@link #getService}.
     */
    public boolean isService() {
        return getCachedInfo().getIntentSenderType() == INTENT_SENDER_SERVICE;
    }

    /**
     * @return TRUE if this {@link PendingIntent} was created with {@link #getBroadcast}.
     */
    public boolean isBroadcast() {
        return getCachedInfo().getIntentSenderType() == INTENT_SENDER_BROADCAST;
    }

    /**
     * @hide
     * Return the Intent of this PendingIntent.
     */
    @UnsupportedAppUsage
    public Intent getIntent() {
        try {
            return ActivityManager.getService()
                .getIntentForIntentSender(mTarget);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return descriptive tag for this PendingIntent.
     */
    @UnsupportedAppUsage
    public String getTag(String prefix) {
        try {
            return ActivityManager.getService()
                .getTagForIntentSender(mTarget, prefix);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resolve the intent set in this {@link PendingIntent}. Note if the pending intent is
     * generated for another user, the resulting component may not exist on the calling user.
     * Use {@link android.content.pm.ApplicationInfo#uid} of the resulting
     * {@link android.content.pm.ComponentInfo} with
     * {@link android.os.UserHandle#getUserHandleForUid(int)} to see which user will receive
     * the intent.
     *
     * @param flags MATCH_* flags from {@link android.content.pm.PackageManager}.
     * @hide
     */
    @RequiresPermission(permission.GET_INTENT_SENDER_INTENT)
    @SystemApi(client = Client.MODULE_LIBRARIES)
    @TestApi
    public @NonNull List<ResolveInfo> queryIntentComponents(@ResolveInfoFlagsBits int flags) {
        try {
            ParceledListSlice<ResolveInfo> parceledList = ActivityManager.getService()
                    .queryIntentComponentsForIntentSender(mTarget, flags);
            if (parceledList == null) {
                return Collections.emptyList();
            }
            return parceledList.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Comparison operator on two PendingIntent objects, such that true is returned when they
     * represent {@link Intent}s that are equal as per {@link Intent#filterEquals}.
     *
     * @param other The other PendingIntent to compare against.
     * @return True if action, data, type, class, and categories on two intents are the same.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @RequiresPermission(android.Manifest.permission.GET_INTENT_SENDER_INTENT)
    public boolean intentFilterEquals(@Nullable PendingIntent other) {
        if (other == null) {
            return false;
        }
        try {
            return ActivityManager.getService().getIntentForIntentSender(other.mTarget)
                    .filterEquals(getIntent());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
    public boolean equals(@Nullable Object otherObj) {
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
        sb.append(mTarget.asBinder());
        sb.append('}');
        return sb.toString();
    }

    /**
     * See {@link Intent#visitUris(Consumer)}.
     *
     * @hide
     */
    public void visitUris(@NonNull Consumer<Uri> visitor) {
        if (android.app.Flags.visitRiskyUris()) {
            Intent intent = Binder.withCleanCallingIdentity(this::getIntent);

            if (intent != null) {
                intent.visitUris(visitor);
            }
        }
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(PendingIntentProto.TARGET, mTarget.asBinder().toString());
        proto.end(token);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mTarget.asBinder());
        final List<OnMarshaledListener> listeners = sOnMarshaledListener.get();
        final int numListeners = listeners.size();
        for (int i = 0; i < numListeners; i++) {
            listeners.get(i).onMarshaled(this, out, flags);
        }
    }

    public static final @NonNull Creator<PendingIntent> CREATOR = new Creator<PendingIntent>() {
        public PendingIntent createFromParcel(Parcel in) {
            IBinder target = in.readStrongBinder();
            return target != null
                    ? new PendingIntent(target, in.getClassCookie(PendingIntent.class))
                    : null;
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
    public static void writePendingIntentOrNullToParcel(@Nullable PendingIntent sender,
            @NonNull Parcel out) {
        out.writeStrongBinder(sender != null ? sender.mTarget.asBinder() : null);
        if (sender != null) {
            final List<OnMarshaledListener> listeners = sOnMarshaledListener.get();
            final int numListeners = listeners.size();
            for (int i = 0; i < numListeners; i++) {
                listeners.get(i).onMarshaled(sender, out, 0 /* flags */);
            }
        }
    }

    /**
     * Convenience function for reading either a PendingIntent or null pointer from
     * a Parcel.  You must have previously written the PendingIntent with
     * {@link #writePendingIntentOrNullToParcel}.
     *
     * @param in The Parcel containing the written PendingIntent.
     *
     * @return Returns the PendingIntent read from the Parcel, or null if null had
     * been written.
     */
    @Nullable
    public static PendingIntent readPendingIntentOrNullFromParcel(@NonNull Parcel in) {
        IBinder b = in.readStrongBinder();
        return b != null ? new PendingIntent(b, in.getClassCookie(PendingIntent.class)) : null;
    }

    /**
     * Creates a PendingIntent with the given target.
     * @param target the backing IIntentSender
     * @hide
     */
    public PendingIntent(IIntentSender target) {
        mTarget = Objects.requireNonNull(target);
    }

    /*package*/ PendingIntent(IBinder target, Object cookie) {
        mTarget = Objects.requireNonNull(IIntentSender.Stub.asInterface(target));
        if (cookie != null) {
            mWhitelistToken = (IBinder)cookie;
        }
    }

    /** @hide */
    public IIntentSender getTarget() {
        return mTarget;
    }

    /** @hide */
    public IBinder getWhitelistToken() {
        return mWhitelistToken;
    }

    /**
     * A listener to when a pending intent is canceled
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public interface CancelListener {
        /**
         * Called when a Pending Intent is canceled.
         *
         * @param intent The intent that was canceled.
         */
        void onCanceled(@NonNull PendingIntent intent);
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
}
