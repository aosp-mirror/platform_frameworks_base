/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.PendingIntentRecord.CANCEL_REASON_OWNER_CANCELED;
import static com.android.server.am.PendingIntentRecord.CANCEL_REASON_SUPERSEDED;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.app.PendingIntentStats;
import android.app.compat.CompatChanges;
import android.content.IIntentSender;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerWhitelistManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.RingBuffer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.PendingIntentRecord.CancellationReason;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.SafeActivityOptions;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Helper class for {@link ActivityManagerService} responsible for managing pending intents.
 *
 * <p>This class uses {@link #mLock} to synchronize access to internal state and doesn't make use of
 * {@link ActivityManagerService} lock since there can be direct calls into this class from outside
 * AM. This helps avoid deadlocks.
 */
public class PendingIntentController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "PendingIntentController" : TAG_AM;
    private static final String TAG_MU = TAG + POSTFIX_MU;

    /** @see {@link #mRecentIntentsPerUid}.  */
    private static final int RECENT_N = 10;

    /** Lock for internal state. */
    final Object mLock = new Object();
    final Handler mH;
    ActivityManagerInternal mAmInternal;
    final UserController mUserController;
    final ActivityTaskManagerInternal mAtmInternal;

    /** Set of IntentSenderRecord objects that are currently active. */
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords
            = new HashMap<>();

    /** The number of PendingIntentRecord per uid */
    @GuardedBy("mLock")
    private final SparseIntArray mIntentsPerUid = new SparseIntArray();

    /** The recent PendingIntentRecord, up to {@link #RECENT_N} per uid */
    @GuardedBy("mLock")
    private final SparseArray<RingBuffer<String>> mRecentIntentsPerUid = new SparseArray<>();

    private final ActivityManagerConstants mConstants;

    PendingIntentController(Looper looper, UserController userController,
            ActivityManagerConstants constants) {
        mH = new Handler(looper);
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mUserController = userController;
        mConstants = constants;
    }

    void onActivityManagerInternalAdded() {
        synchronized (mLock) {
            mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        }
    }

    public PendingIntentRecord getIntentSender(int type, String packageName,
            @Nullable String featureId, int callingUid, int userId, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions) {
        synchronized (mLock) {
            if (DEBUG_MU) Slog.v(TAG_MU, "getIntentSender(): uid=" + callingUid);

            // We're going to be splicing together extras before sending, so we're
            // okay poking into any contained extras.
            if (intents != null) {
                for (int i = 0; i < intents.length; i++) {
                    intents[i].setDefusable(true);
                }
            }
            Bundle.setDefusable(bOptions, true);
            ActivityOptions opts = ActivityOptions.fromBundle(bOptions);
            if (opts != null && opts.getPendingIntentBackgroundActivityStartMode()
                    != ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
                Slog.wtf(TAG, "Resetting option setPendingIntentBackgroundActivityStartMode("
                        + opts.getPendingIntentBackgroundActivityStartMode()
                        + ") to SYSTEM_DEFINED from the options provided by the pending "
                        + "intent creator ("
                        + packageName
                        + ") because this option is meant for the pending intent sender");
                if (CompatChanges.isChangeEnabled(PendingIntent.PENDING_INTENT_OPTIONS_CHECK,
                        callingUid)) {
                    throw new IllegalArgumentException("pendingIntentBackgroundActivityStartMode "
                            + "must not be set when creating a PendingIntent");
                }
                opts.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
            }

            final boolean noCreate = (flags & PendingIntent.FLAG_NO_CREATE) != 0;
            final boolean cancelCurrent = (flags & PendingIntent.FLAG_CANCEL_CURRENT) != 0;
            final boolean updateCurrent = (flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0;
            flags &= ~(PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_CANCEL_CURRENT
                    | PendingIntent.FLAG_UPDATE_CURRENT);

            PendingIntentRecord.Key key = new PendingIntentRecord.Key(type, packageName, featureId,
                    token, resultWho, requestCode, intents, resolvedTypes, flags,
                    new SafeActivityOptions(opts, Binder.getCallingPid(), Binder.getCallingUid()),
                    userId);
            WeakReference<PendingIntentRecord> ref;
            ref = mIntentSenderRecords.get(key);
            PendingIntentRecord rec = ref != null ? ref.get() : null;
            if (rec != null) {
                if (!cancelCurrent) {
                    if (updateCurrent) {
                        if (rec.key.requestIntent != null) {
                            rec.key.requestIntent.replaceExtras(intents != null ?
                                    intents[intents.length - 1] : null);
                        }
                        if (intents != null) {
                            intents[intents.length - 1] = rec.key.requestIntent;
                            rec.key.allIntents = intents;
                            rec.key.allResolvedTypes = resolvedTypes;
                        } else {
                            rec.key.allIntents = null;
                            rec.key.allResolvedTypes = null;
                        }
                    }
                    return rec;
                }
                makeIntentSenderCanceled(rec, CANCEL_REASON_SUPERSEDED);
                mIntentSenderRecords.remove(key);
                decrementUidStatLocked(rec);
            }
            if (noCreate) {
                return rec;
            }
            rec = new PendingIntentRecord(this, key, callingUid);
            mIntentSenderRecords.put(key, rec.ref);
            incrementUidStatLocked(rec);
            return rec;
        }
    }

    boolean removePendingIntentsForPackage(String packageName, int userId, int appId,
            boolean doIt, @CancellationReason int cancelReason) {

        boolean didSomething = false;
        synchronized (mLock) {

            // Remove pending intents.  For now we only do this when force stopping users, because
            // we have some problems when doing this for packages -- app widgets are not currently
            // cleaned up for such packages, so they can be left with bad pending intents.
            if (mIntentSenderRecords.size() <= 0) {
                return false;
            }

            Iterator<WeakReference<PendingIntentRecord>> it
                    = mIntentSenderRecords.values().iterator();
            while (it.hasNext()) {
                WeakReference<PendingIntentRecord> wpir = it.next();
                if (wpir == null) {
                    it.remove();
                    continue;
                }
                PendingIntentRecord pir = wpir.get();
                if (pir == null) {
                    it.remove();
                    continue;
                }
                if (packageName == null) {
                    // Stopping user, remove all objects for the user.
                    if (pir.key.userId != userId) {
                        // Not the same user, skip it.
                        continue;
                    }
                } else {
                    if (UserHandle.getAppId(pir.uid) != appId) {
                        // Different app id, skip it.
                        continue;
                    }
                    if (userId != UserHandle.USER_ALL && pir.key.userId != userId) {
                        // Different user, skip it.
                        continue;
                    }
                    if (!pir.key.packageName.equals(packageName)) {
                        // Different package, skip it.
                        continue;
                    }
                }
                if (!doIt) {
                    return true;
                }
                didSomething = true;
                it.remove();
                makeIntentSenderCanceled(pir, cancelReason);
                decrementUidStatLocked(pir);
                if (pir.key.activity != null) {
                    final Message m = PooledLambda.obtainMessage(
                            PendingIntentController::clearPendingResultForActivity, this,
                            pir.key.activity, pir.ref);
                    mH.sendMessage(m);
                }
            }
        }

        return didSomething;
    }

    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized (mLock) {
            final PendingIntentRecord rec = (PendingIntentRecord) sender;
            try {
                final int uid = AppGlobals.getPackageManager().getPackageUid(rec.key.packageName,
                        MATCH_DEBUG_TRIAGED_MISSING, UserHandle.getCallingUserId());
                if (!UserHandle.isSameApp(uid, Binder.getCallingUid())) {
                    String msg = "Permission Denial: cancelIntentSender() from pid="
                            + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                            + " is not allowed to cancel package " + rec.key.packageName;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
            cancelIntentSender(rec, true, CANCEL_REASON_OWNER_CANCELED);
        }
    }

    public void cancelIntentSender(PendingIntentRecord rec, boolean cleanActivity,
            @CancellationReason int cancelReason) {
        synchronized (mLock) {
            makeIntentSenderCanceled(rec, cancelReason);
            mIntentSenderRecords.remove(rec.key);
            decrementUidStatLocked(rec);
            if (cleanActivity && rec.key.activity != null) {
                final Message m = PooledLambda.obtainMessage(
                        PendingIntentController::clearPendingResultForActivity, this,
                        rec.key.activity, rec.ref);
                mH.sendMessage(m);
            }
        }
    }

    boolean registerIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            Slog.w(TAG, "registerIntentSenderCancelListener called on non-PendingIntentRecord");
            // In this case, it's not "success", but we don't know if it's canceld either.
            return true;
        }
        boolean isCancelled;
        synchronized (mLock) {
            PendingIntentRecord pendingIntent = (PendingIntentRecord) sender;
            isCancelled = pendingIntent.canceled;
            if (!isCancelled) {
                pendingIntent.registerCancelListenerLocked(receiver);
                return true;
            } else {
                return false;
            }
        }
    }

    void unregisterIntentSenderCancelListener(IIntentSender sender,
            IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized (mLock) {
            ((PendingIntentRecord) sender).unregisterCancelListenerLocked(receiver);
        }
    }

    void setPendingIntentAllowlistDuration(IIntentSender target, IBinder allowlistToken,
            long duration, int type, @PowerWhitelistManager.ReasonCode int reasonCode,
            @Nullable String reason) {
        if (!(target instanceof PendingIntentRecord)) {
            Slog.w(TAG, "markAsSentFromNotification(): not a PendingIntentRecord: " + target);
            return;
        }
        synchronized (mLock) {
            ((PendingIntentRecord) target).setAllowlistDurationLocked(allowlistToken, duration,
                    type, reasonCode, reason);
        }
    }

    int getPendingIntentFlags(IIntentSender target) {
        if (!(target instanceof PendingIntentRecord)) {
            Slog.w(TAG, "markAsSentFromNotification(): not a PendingIntentRecord: " + target);
            return 0;
        }
        synchronized (mLock) {
            return ((PendingIntentRecord) target).key.flags;
        }
    }

    private void makeIntentSenderCanceled(PendingIntentRecord rec,
            @CancellationReason int cancelReason) {
        rec.canceled = true;
        rec.cancelReason = cancelReason;
        final RemoteCallbackList<IResultReceiver> callbacks = rec.detachCancelListenersLocked();
        if (callbacks != null) {
            final Message m = PooledLambda.obtainMessage(
                    PendingIntentController::handlePendingIntentCancelled, this, callbacks);
            mH.sendMessage(m);
        }
        final AlarmManagerInternal ami = LocalServices.getService(AlarmManagerInternal.class);
        ami.remove(new PendingIntent(rec));
    }

    private void handlePendingIntentCancelled(RemoteCallbackList<IResultReceiver> callbacks) {
        int N = callbacks.beginBroadcast();
        for (int i = 0; i < N; i++) {
            try {
                callbacks.getBroadcastItem(i).send(Activity.RESULT_CANCELED, null);
            } catch (RemoteException e) {
                // Process is not longer running...whatever.
            }
        }
        callbacks.finishBroadcast();
        // We have to clean up the RemoteCallbackList here, because otherwise it will
        // needlessly hold the enclosed callbacks until the remote process dies.
        callbacks.kill();
    }

    private void clearPendingResultForActivity(IBinder activityToken,
            WeakReference<PendingIntentRecord> pir) {
        mAtmInternal.clearPendingResultForActivity(activityToken, pir);
    }

    void dumpPendingIntents(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        synchronized (mLock) {
            boolean printed = false;

            pw.println("ACTIVITY MANAGER PENDING INTENTS (dumpsys activity intents)");

            if (mIntentSenderRecords.size() > 0) {
                // Organize these by package name, so they are easier to read.
                final ArrayMap<String, ArrayList<PendingIntentRecord>> byPackage = new ArrayMap<>();
                final ArrayList<WeakReference<PendingIntentRecord>> weakRefs = new ArrayList<>();
                final Iterator<WeakReference<PendingIntentRecord>> it
                        = mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> ref = it.next();
                    PendingIntentRecord rec = ref != null ? ref.get() : null;
                    if (rec == null) {
                        weakRefs.add(ref);
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(rec.key.packageName)) {
                        continue;
                    }
                    ArrayList<PendingIntentRecord> list = byPackage.get(rec.key.packageName);
                    if (list == null) {
                        list = new ArrayList<>();
                        byPackage.put(rec.key.packageName, list);
                    }
                    list.add(rec);
                }
                for (int i = 0; i < byPackage.size(); i++) {
                    ArrayList<PendingIntentRecord> intents = byPackage.valueAt(i);
                    printed = true;
                    pw.print("  * "); pw.print(byPackage.keyAt(i));
                    pw.print(": "); pw.print(intents.size()); pw.println(" items");
                    for (int j = 0; j < intents.size(); j++) {
                        pw.print("    #"); pw.print(j); pw.print(": "); pw.println(intents.get(j));
                        if (dumpAll) {
                            intents.get(j).dump(pw, "      ");
                        }
                    }
                }
                if (weakRefs.size() > 0) {
                    printed = true;
                    pw.println("  * WEAK REFS:");
                    for (int i = 0; i < weakRefs.size(); i++) {
                        pw.print("    #"); pw.print(i); pw.print(": "); pw.println(weakRefs.get(i));
                    }
                }
            }

            final int sizeOfIntentsPerUid = mIntentsPerUid.size();
            if (sizeOfIntentsPerUid > 0) {
                for (int i = 0; i < sizeOfIntentsPerUid; i++) {
                    pw.print("  * UID: ");
                    pw.print(mIntentsPerUid.keyAt(i));
                    pw.print(" total: ");
                    pw.println(mIntentsPerUid.valueAt(i));
                }
            }

            if (!printed) {
                pw.println("  (nothing)");
            }
        }
    }

    /**
     * Provides some stats tracking of the current state of the PendingIntent queue.
     *
     * Data about the pending intent queue is intended to be used for memory impact tracking.
     * Returned data (one per uid) will consist of instances of PendingIntentStats containing
     * (I) number of PendingIntents and (II) total size of all bundled extras in the PIs.
     *
     * @hide
     */
    public List<PendingIntentStats> dumpPendingIntentStatsForStatsd() {
        List<PendingIntentStats> pendingIntentStats = new ArrayList<>();

        synchronized (mLock) {
            if (mIntentSenderRecords.size() > 0) {
                // First, aggregate PendingIntent data by package uid.
                final SparseIntArray countsByUid = new SparseIntArray();
                final SparseIntArray bundleSizesByUid = new SparseIntArray();

                for (WeakReference<PendingIntentRecord> reference : mIntentSenderRecords.values()) {
                    if (reference == null || reference.get() == null) {
                        continue;
                    }
                    PendingIntentRecord record = reference.get();
                    int index = countsByUid.indexOfKey(record.uid);

                    if (index < 0) { // ie. the key was not found
                        countsByUid.put(record.uid, 1);
                        bundleSizesByUid.put(record.uid,
                                record.key.requestIntent.getExtrasTotalSize());
                    } else {
                        countsByUid.put(record.uid, countsByUid.valueAt(index) + 1);
                        bundleSizesByUid.put(record.uid,
                                bundleSizesByUid.valueAt(index)
                                + record.key.requestIntent.getExtrasTotalSize());
                    }
                }

                // Now generate the output.
                for (int i = 0, size = countsByUid.size(); i < size; i++) {
                    pendingIntentStats.add(new PendingIntentStats(
                            countsByUid.keyAt(i),
                            countsByUid.valueAt(i),
                            /* NB: int conversion here */ bundleSizesByUid.valueAt(i) / 1024));
                }
            }
        }
        return pendingIntentStats;
    }

    /**
     * Increment the number of the PendingIntentRecord for the given uid, log a warning
     * if there are too many for this uid already.
     */
    @GuardedBy("mLock")
    void incrementUidStatLocked(final PendingIntentRecord pir) {
        final int uid = pir.uid;
        final int idx = mIntentsPerUid.indexOfKey(uid);
        int newCount = 1;
        if (idx >= 0) {
            newCount = mIntentsPerUid.valueAt(idx) + 1;
            mIntentsPerUid.setValueAt(idx, newCount);
        } else {
            mIntentsPerUid.put(uid, newCount);
        }

        // If the number is within the range [threshold - N + 1, threshold], log it into buffer
        final int lowBound = mConstants.PENDINGINTENT_WARNING_THRESHOLD - RECENT_N + 1;
        RingBuffer<String> recentHistory = null;
        if (newCount == lowBound) {
            recentHistory = new RingBuffer(String.class, RECENT_N);
            mRecentIntentsPerUid.put(uid, recentHistory);
        } else if (newCount > lowBound && newCount <= mConstants.PENDINGINTENT_WARNING_THRESHOLD) {
            recentHistory = mRecentIntentsPerUid.get(uid);
        }
        if (recentHistory == null) {
            return;
        }

        recentHistory.append(pir.key.toString());

        // Output the log if we are hitting the threshold
        if (newCount == mConstants.PENDINGINTENT_WARNING_THRESHOLD) {
            Slog.wtf(TAG, "Too many PendingIntent created for uid " + uid
                    + ", recent " + RECENT_N + ": " + Arrays.toString(recentHistory.toArray()));
            // Clear the buffer, as we don't want to spam the log when the numbers
            // are jumping up and down around the threshold.
            mRecentIntentsPerUid.remove(uid);
        }
    }

    /**
     * Decrement the number of the PendingIntentRecord for the given uid.
     */
    @GuardedBy("mLock")
    void decrementUidStatLocked(final PendingIntentRecord pir) {
        final int uid = pir.uid;
        final int idx = mIntentsPerUid.indexOfKey(uid);
        if (idx >= 0) {
            final int newCount = mIntentsPerUid.valueAt(idx) - 1;
            // If we are going below the low threshold, no need to keep logs.
            if (newCount == mConstants.PENDINGINTENT_WARNING_THRESHOLD - RECENT_N) {
                mRecentIntentsPerUid.delete(uid);
            }
            if (newCount == 0) {
                mIntentsPerUid.removeAt(idx);
            } else {
                mIntentsPerUid.setValueAt(idx, newCount);
            }
        }
    }
}
