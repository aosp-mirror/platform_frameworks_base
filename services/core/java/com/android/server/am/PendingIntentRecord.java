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

package com.android.server.am;

import static android.app.ActivityManager.START_SUCCESS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.IIntentSender;
import android.content.IIntentReceiver;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.wm.SafeActivityOptions;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

public final class PendingIntentRecord extends IIntentSender.Stub {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "PendingIntentRecord" : TAG_AM;

    public static final int FLAG_ACTIVITY_SENDER = 1 << 0;
    public static final int FLAG_BROADCAST_SENDER = 1 << 1;
    public static final int FLAG_SERVICE_SENDER = 1 << 2;

    final PendingIntentController controller;
    final Key key;
    final int uid;
    public final WeakReference<PendingIntentRecord> ref;
    boolean sent = false;
    boolean canceled = false;
    private ArrayMap<IBinder, Long> whitelistDuration;
    private RemoteCallbackList<IResultReceiver> mCancelCallbacks;
    private ArraySet<IBinder> mAllowBgActivityStartsForActivitySender = new ArraySet<>();
    private ArraySet<IBinder> mAllowBgActivityStartsForBroadcastSender = new ArraySet<>();
    private ArraySet<IBinder> mAllowBgActivityStartsForServiceSender = new ArraySet<>();

    String stringName;
    String lastTagPrefix;
    String lastTag;

    final static class Key {
        final int type;
        final String packageName;
        final IBinder activity;
        final String who;
        final int requestCode;
        final Intent requestIntent;
        final String requestResolvedType;
        final SafeActivityOptions options;
        Intent[] allIntents;
        String[] allResolvedTypes;
        final int flags;
        final int hashCode;
        final int userId;

        private static final int ODD_PRIME_NUMBER = 37;

        Key(int _t, String _p, IBinder _a, String _w,
                int _r, Intent[] _i, String[] _it, int _f, SafeActivityOptions _o, int _userId) {
            type = _t;
            packageName = _p;
            activity = _a;
            who = _w;
            requestCode = _r;
            requestIntent = _i != null ? _i[_i.length-1] : null;
            requestResolvedType = _it != null ? _it[_it.length-1] : null;
            allIntents = _i;
            allResolvedTypes = _it;
            flags = _f;
            options = _o;
            userId = _userId;

            int hash = 23;
            hash = (ODD_PRIME_NUMBER*hash) + _f;
            hash = (ODD_PRIME_NUMBER*hash) + _r;
            hash = (ODD_PRIME_NUMBER*hash) + _userId;
            if (_w != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _w.hashCode();
            }
            if (_a != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _a.hashCode();
            }
            if (requestIntent != null) {
                hash = (ODD_PRIME_NUMBER*hash) + requestIntent.filterHashCode();
            }
            if (requestResolvedType != null) {
                hash = (ODD_PRIME_NUMBER*hash) + requestResolvedType.hashCode();
            }
            hash = (ODD_PRIME_NUMBER*hash) + (_p != null ? _p.hashCode() : 0);
            hash = (ODD_PRIME_NUMBER*hash) + _t;
            hashCode = hash;
            //Slog.i(ActivityManagerService.TAG, this + " hashCode=0x"
            //        + Integer.toHexString(hashCode));
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == null) {
                return false;
            }
            try {
                Key other = (Key)otherObj;
                if (type != other.type) {
                    return false;
                }
                if (userId != other.userId){
                    return false;
                }
                if (!Objects.equals(packageName, other.packageName)) {
                    return false;
                }
                if (activity != other.activity) {
                    return false;
                }
                if (!Objects.equals(who, other.who)) {
                    return false;
                }
                if (requestCode != other.requestCode) {
                    return false;
                }
                if (requestIntent != other.requestIntent) {
                    if (requestIntent != null) {
                        if (!requestIntent.filterEquals(other.requestIntent)) {
                            return false;
                        }
                    } else if (other.requestIntent != null) {
                        return false;
                    }
                }
                if (!Objects.equals(requestResolvedType, other.requestResolvedType)) {
                    return false;
                }
                if (flags != other.flags) {
                    return false;
                }
                return true;
            } catch (ClassCastException e) {
            }
            return false;
        }

        public int hashCode() {
            return hashCode;
        }

        public String toString() {
            return "Key{" + typeName() + " pkg=" + packageName
                + " intent="
                + (requestIntent != null
                        ? requestIntent.toShortString(false, true, false, false) : "<null>")
                + " flags=0x" + Integer.toHexString(flags) + " u=" + userId + "}";
        }

        String typeName() {
            switch (type) {
                case ActivityManager.INTENT_SENDER_ACTIVITY:
                    return "startActivity";
                case ActivityManager.INTENT_SENDER_BROADCAST:
                    return "broadcastIntent";
                case ActivityManager.INTENT_SENDER_SERVICE:
                    return "startService";
                case ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE:
                    return "startForegroundService";
                case ActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
                    return "activityResult";
            }
            return Integer.toString(type);
        }
    }

    PendingIntentRecord(PendingIntentController _controller, Key _k, int _u) {
        controller = _controller;
        key = _k;
        uid = _u;
        ref = new WeakReference<>(this);
    }

    void setWhitelistDurationLocked(IBinder whitelistToken, long duration) {
        if (duration > 0) {
            if (whitelistDuration == null) {
                whitelistDuration = new ArrayMap<>();
            }
            whitelistDuration.put(whitelistToken, duration);
        } else if (whitelistDuration != null) {
            whitelistDuration.remove(whitelistToken);
            if (whitelistDuration.size() <= 0) {
                whitelistDuration = null;
            }

        }
        this.stringName = null;
    }

    void setAllowBgActivityStarts(IBinder token, int flags) {
        if (token == null) return;
        if ((flags & FLAG_ACTIVITY_SENDER) != 0) {
            mAllowBgActivityStartsForActivitySender.add(token);
        }
        if ((flags & FLAG_BROADCAST_SENDER) != 0) {
            mAllowBgActivityStartsForBroadcastSender.add(token);
        }
        if ((flags & FLAG_SERVICE_SENDER) != 0) {
            mAllowBgActivityStartsForServiceSender.add(token);
        }
    }

    public void registerCancelListenerLocked(IResultReceiver receiver) {
        if (mCancelCallbacks == null) {
            mCancelCallbacks = new RemoteCallbackList<>();
        }
        mCancelCallbacks.register(receiver);
    }

    public void unregisterCancelListenerLocked(IResultReceiver receiver) {
        if (mCancelCallbacks == null) {
            return; // Already unregistered or detached.
        }
        mCancelCallbacks.unregister(receiver);
        if (mCancelCallbacks.getRegisteredCallbackCount() <= 0) {
            mCancelCallbacks = null;
        }
    }

    public RemoteCallbackList<IResultReceiver> detachCancelListenersLocked() {
        RemoteCallbackList<IResultReceiver> listeners = mCancelCallbacks;
        mCancelCallbacks = null;
        return listeners;
    }

    public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        sendInner(code, intent, resolvedType, whitelistToken, finishedReceiver,
                requiredPermission, null, null, 0, 0, 0, options);
    }

    public int sendWithResult(int code, Intent intent, String resolvedType, IBinder whitelistToken,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        return sendInner(code, intent, resolvedType, whitelistToken, finishedReceiver,
                requiredPermission, null, null, 0, 0, 0, options);
    }

    public int sendInner(int code, Intent intent, String resolvedType, IBinder whitelistToken,
            IIntentReceiver finishedReceiver, String requiredPermission, IBinder resultTo,
            String resultWho, int requestCode, int flagsMask, int flagsValues, Bundle options) {
        if (intent != null) intent.setDefusable(true);
        if (options != null) options.setDefusable(true);

        Long duration = null;
        Intent finalIntent = null;
        Intent[] allIntents = null;
        String[] allResolvedTypes = null;
        SafeActivityOptions mergedOptions = null;
        synchronized (controller.mLock) {
            if (canceled) {
                return ActivityManager.START_CANCELED;
            }

            sent = true;
            if ((key.flags & PendingIntent.FLAG_ONE_SHOT) != 0) {
                controller.cancelIntentSender(this, true);
            }

            finalIntent = key.requestIntent != null ? new Intent(key.requestIntent) : new Intent();

            final boolean immutable = (key.flags & PendingIntent.FLAG_IMMUTABLE) != 0;
            if (!immutable) {
                if (intent != null) {
                    int changes = finalIntent.fillIn(intent, key.flags);
                    if ((changes & Intent.FILL_IN_DATA) == 0) {
                        resolvedType = key.requestResolvedType;
                    }
                } else {
                    resolvedType = key.requestResolvedType;
                }
                flagsMask &= ~Intent.IMMUTABLE_FLAGS;
                flagsValues &= flagsMask;
                finalIntent.setFlags((finalIntent.getFlags() & ~flagsMask) | flagsValues);
            } else {
                resolvedType = key.requestResolvedType;
            }

            // Apply any launch flags from the ActivityOptions. This is to ensure that the caller
            // can specify a consistent launch mode even if the PendingIntent is immutable
            final ActivityOptions opts = ActivityOptions.fromBundle(options);
            if (opts != null) {
                finalIntent.addFlags(opts.getPendingIntentLaunchFlags());
            }

            // Extract options before clearing calling identity
            mergedOptions = key.options;
            if (mergedOptions == null) {
                mergedOptions = new SafeActivityOptions(opts);
            } else {
                mergedOptions.setCallerOptions(opts);
            }

            if (whitelistDuration != null) {
                duration = whitelistDuration.get(whitelistToken);
            }

            if (key.type == ActivityManager.INTENT_SENDER_ACTIVITY
                    && key.allIntents != null && key.allIntents.length > 1) {
                // Copy all intents and resolved types while we have the controller lock so we can
                // use it later when the lock isn't held.
                allIntents = new Intent[key.allIntents.length];
                allResolvedTypes = new String[key.allIntents.length];
                System.arraycopy(key.allIntents, 0, allIntents, 0, key.allIntents.length);
                if (key.allResolvedTypes != null) {
                    System.arraycopy(key.allResolvedTypes, 0, allResolvedTypes, 0,
                            key.allResolvedTypes.length);
                }
                allIntents[allIntents.length - 1] = finalIntent;
                allResolvedTypes[allResolvedTypes.length - 1] = resolvedType;
            }

        }
        // We don't hold the controller lock beyond this point as we will be calling into AM and WM.

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();

        int res = START_SUCCESS;
        try {
            if (duration != null) {
                int procState = controller.mAmInternal.getUidProcessState(callingUid);
                if (!ActivityManager.isProcStateBackground(procState)) {
                    StringBuilder tag = new StringBuilder(64);
                    tag.append("pendingintent:");
                    UserHandle.formatUid(tag, callingUid);
                    tag.append(":");
                    if (finalIntent.getAction() != null) {
                        tag.append(finalIntent.getAction());
                    } else if (finalIntent.getComponent() != null) {
                        finalIntent.getComponent().appendShortString(tag);
                    } else if (finalIntent.getData() != null) {
                        tag.append(finalIntent.getData().toSafeString());
                    }
                    controller.mAmInternal.tempWhitelistForPendingIntent(callingPid, callingUid,
                            uid, duration, tag.toString());
                } else {
                    Slog.w(TAG, "Not doing whitelist " + this + ": caller state=" + procState);
                }
            }

            boolean sendFinish = finishedReceiver != null;
            int userId = key.userId;
            if (userId == UserHandle.USER_CURRENT) {
                userId = controller.mUserController.getCurrentOrTargetUserId();
            }
            // temporarily allow receivers and services to open activities from background if the
            // PendingIntent.send() caller was foreground at the time of sendInner() call
            final boolean allowTrampoline = uid != callingUid
                    && controller.mAtmInternal.isUidForeground(callingUid);

            // note: we on purpose don't pass in the information about the PendingIntent's creator,
            // like pid or ProcessRecord, to the ActivityTaskManagerInternal calls below, because
            // it's not unusual for the creator's process to not be alive at this time
            switch (key.type) {
                case ActivityManager.INTENT_SENDER_ACTIVITY:
                    try {
                        // Note when someone has a pending intent, even from different
                        // users, then there's no need to ensure the calling user matches
                        // the target user, so validateIncomingUser is always false below.

                        if (key.allIntents != null && key.allIntents.length > 1) {
                            res = controller.mAtmInternal.startActivitiesInPackage(
                                    uid, callingPid, callingUid, key.packageName, allIntents,
                                    allResolvedTypes, resultTo, mergedOptions, userId,
                                    false /* validateIncomingUser */,
                                    this /* originatingPendingIntent */,
                                    mAllowBgActivityStartsForActivitySender.contains(whitelistToken));
                        } else {
                            res = controller.mAtmInternal.startActivityInPackage(
                                    uid, callingPid, callingUid, key.packageName, finalIntent,
                                    resolvedType, resultTo, resultWho, requestCode, 0,
                                    mergedOptions, userId, null, "PendingIntentRecord",
                                    false /* validateIncomingUser */,
                                    this /* originatingPendingIntent */,
                                    mAllowBgActivityStartsForActivitySender.contains(whitelistToken));
                        }
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Unable to send startActivity intent", e);
                    }
                    break;
                case ActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
                    controller.mAtmInternal.sendActivityResult(-1, key.activity, key.who,
                                key.requestCode, code, finalIntent);
                    break;
                case ActivityManager.INTENT_SENDER_BROADCAST:
                    try {
                        // If a completion callback has been requested, require
                        // that the broadcast be delivered synchronously
                        int sent = controller.mAmInternal.broadcastIntentInPackage(key.packageName,
                                uid, finalIntent, resolvedType, finishedReceiver, code, null, null,
                                requiredPermission, options, (finishedReceiver != null),
                                false, userId,
                                mAllowBgActivityStartsForBroadcastSender.contains(whitelistToken)
                                || allowTrampoline);
                        if (sent == ActivityManager.BROADCAST_SUCCESS) {
                            sendFinish = false;
                        }
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Unable to send startActivity intent", e);
                    }
                    break;
                case ActivityManager.INTENT_SENDER_SERVICE:
                case ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE:
                    try {
                        controller.mAmInternal.startServiceInPackage(uid, finalIntent, resolvedType,
                                key.type == ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE,
                                key.packageName, userId,
                                mAllowBgActivityStartsForServiceSender.contains(whitelistToken)
                                || allowTrampoline);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Unable to send startService intent", e);
                    } catch (TransactionTooLargeException e) {
                        res = ActivityManager.START_CANCELED;
                    }
                    break;
            }

            if (sendFinish && res != ActivityManager.START_CANCELED) {
                try {
                    finishedReceiver.performReceive(new Intent(finalIntent), 0,
                            null, null, false, false, key.userId);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return res;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!canceled) {
                controller.mH.sendMessage(PooledLambda.obtainMessage(
                        PendingIntentRecord::completeFinalize, this));
            }
        } finally {
            super.finalize();
        }
    }

    private void completeFinalize() {
        synchronized(controller.mLock) {
            WeakReference<PendingIntentRecord> current = controller.mIntentSenderRecords.get(key);
            if (current == ref) {
                controller.mIntentSenderRecords.remove(key);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("uid="); pw.print(uid);
                pw.print(" packageName="); pw.print(key.packageName);
                pw.print(" type="); pw.print(key.typeName());
                pw.print(" flags=0x"); pw.println(Integer.toHexString(key.flags));
        if (key.activity != null || key.who != null) {
            pw.print(prefix); pw.print("activity="); pw.print(key.activity);
                    pw.print(" who="); pw.println(key.who);
        }
        if (key.requestCode != 0 || key.requestResolvedType != null) {
            pw.print(prefix); pw.print("requestCode="); pw.print(key.requestCode);
                    pw.print(" requestResolvedType="); pw.println(key.requestResolvedType);
        }
        if (key.requestIntent != null) {
            pw.print(prefix); pw.print("requestIntent=");
                    pw.println(key.requestIntent.toShortString(false, true, true, true));
        }
        if (sent || canceled) {
            pw.print(prefix); pw.print("sent="); pw.print(sent);
                    pw.print(" canceled="); pw.println(canceled);
        }
        if (whitelistDuration != null) {
            pw.print(prefix);
            pw.print("whitelistDuration=");
            for (int i = 0; i < whitelistDuration.size(); i++) {
                if (i != 0) {
                    pw.print(", ");
                }
                pw.print(Integer.toHexString(System.identityHashCode(whitelistDuration.keyAt(i))));
                pw.print(":");
                TimeUtils.formatDuration(whitelistDuration.valueAt(i), pw);
            }
            pw.println();
        }
        if (mCancelCallbacks != null) {
            pw.print(prefix); pw.println("mCancelCallbacks:");
            for (int i = 0; i < mCancelCallbacks.getRegisteredCallbackCount(); i++) {
                pw.print(prefix); pw.print("  #"); pw.print(i); pw.print(": ");
                pw.println(mCancelCallbacks.getRegisteredCallbackItem(i));
            }
        }
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("PendingIntentRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(key.packageName);
        sb.append(' ');
        sb.append(key.typeName());
        if (whitelistDuration != null) {
            sb.append( " (whitelist: ");
            for (int i = 0; i < whitelistDuration.size(); i++) {
                if (i != 0) {
                    sb.append(",");
                }
                sb.append(Integer.toHexString(System.identityHashCode(whitelistDuration.keyAt(i))));
                sb.append(":");
                TimeUtils.formatDuration(whitelistDuration.valueAt(i), sb);
            }
            sb.append(")");
        }
        sb.append('}');
        return stringName = sb.toString();
    }
}
