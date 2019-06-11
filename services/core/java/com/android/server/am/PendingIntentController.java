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

import android.app.Activity;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.IIntentSender;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.SafeActivityOptions;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

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

    /** Lock for internal state. */
    final Object mLock = new Object();
    final Handler mH;
    ActivityManagerInternal mAmInternal;
    final UserController mUserController;
    final ActivityTaskManagerInternal mAtmInternal;

    /** Set of IntentSenderRecord objects that are currently active. */
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords
            = new HashMap<>();

    PendingIntentController(Looper looper, UserController userController) {
        mH = new Handler(looper);
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mUserController = userController;
    }

    void onActivityManagerInternalAdded() {
        synchronized (mLock) {
            mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        }
    }

    public PendingIntentRecord getIntentSender(int type, String packageName, int callingUid,
            int userId, IBinder token, String resultWho, int requestCode, Intent[] intents,
            String[] resolvedTypes, int flags, Bundle bOptions) {
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

            final boolean noCreate = (flags & PendingIntent.FLAG_NO_CREATE) != 0;
            final boolean cancelCurrent = (flags & PendingIntent.FLAG_CANCEL_CURRENT) != 0;
            final boolean updateCurrent = (flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0;
            flags &= ~(PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_CANCEL_CURRENT
                    | PendingIntent.FLAG_UPDATE_CURRENT);

            PendingIntentRecord.Key key = new PendingIntentRecord.Key(type, packageName, token,
                    resultWho, requestCode, intents, resolvedTypes, flags,
                    SafeActivityOptions.fromBundle(bOptions), userId);
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
                makeIntentSenderCanceled(rec);
                mIntentSenderRecords.remove(key);
            }
            if (noCreate) {
                return rec;
            }
            rec = new PendingIntentRecord(this, key, callingUid);
            mIntentSenderRecords.put(key, rec.ref);
            return rec;
        }
    }

    boolean removePendingIntentsForPackage(String packageName, int userId, int appId,
            boolean doIt) {

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
                makeIntentSenderCanceled(pir);
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
            cancelIntentSender(rec, true);
        }
    }

    public void cancelIntentSender(PendingIntentRecord rec, boolean cleanActivity) {
        synchronized (mLock) {
            makeIntentSenderCanceled(rec);
            mIntentSenderRecords.remove(rec.key);
            if (cleanActivity && rec.key.activity != null) {
                final Message m = PooledLambda.obtainMessage(
                        PendingIntentController::clearPendingResultForActivity, this,
                        rec.key.activity, rec.ref);
                mH.sendMessage(m);
            }
        }
    }

    void registerIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        boolean isCancelled;
        synchronized (mLock) {
            PendingIntentRecord pendingIntent = (PendingIntentRecord) sender;
            isCancelled = pendingIntent.canceled;
            if (!isCancelled) {
                pendingIntent.registerCancelListenerLocked(receiver);
            }
        }
        if (isCancelled) {
            try {
                receiver.send(Activity.RESULT_CANCELED, null);
            } catch (RemoteException e) {
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

    void setPendingIntentWhitelistDuration(IIntentSender target, IBinder whitelistToken,
            long duration) {
        if (!(target instanceof PendingIntentRecord)) {
            Slog.w(TAG, "markAsSentFromNotification(): not a PendingIntentRecord: " + target);
            return;
        }
        synchronized (mLock) {
            ((PendingIntentRecord) target).setWhitelistDurationLocked(whitelistToken, duration);
        }
    }

    private void makeIntentSenderCanceled(PendingIntentRecord rec) {
        rec.canceled = true;
        final RemoteCallbackList<IResultReceiver> callbacks = rec.detachCancelListenersLocked();
        if (callbacks != null) {
            final Message m = PooledLambda.obtainMessage(
                    PendingIntentController::handlePendingIntentCancelled, this, callbacks);
            mH.sendMessage(m);
        }
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

            if (!printed) {
                pw.println("  (nothing)");
            }
        }
    }
}
