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

import android.app.ActivityManager;
import android.content.IIntentSender;
import android.content.IIntentReceiver;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

class PendingIntentRecord extends IIntentSender.Stub {
    final ActivityManagerService owner;
    final Key key;
    final int uid;
    final WeakReference<PendingIntentRecord> ref;
    boolean sent = false;
    boolean canceled = false;

    String stringName;
    
    final static class Key {
        final int type;
        final String packageName;
        final ActivityRecord activity;
        final String who;
        final int requestCode;
        final Intent requestIntent;
        final String requestResolvedType;
        final Bundle options;
        Intent[] allIntents;
        String[] allResolvedTypes;
        final int flags;
        final int hashCode;
        final int userId;
        
        private static final int ODD_PRIME_NUMBER = 37;
        
        Key(int _t, String _p, ActivityRecord _a, String _w,
                int _r, Intent[] _i, String[] _it, int _f, Bundle _o, int _userId) {
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
            hash = (ODD_PRIME_NUMBER*hash) + _p.hashCode();
            hash = (ODD_PRIME_NUMBER*hash) + _t;
            hashCode = hash;
            //Slog.i(ActivityManagerService.TAG, this + " hashCode=0x"
            //        + Integer.toHexString(hashCode));
        }
        
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
                if (!packageName.equals(other.packageName)) {
                    return false;
                }
                if (activity != other.activity) {
                    return false;
                }
                if (who != other.who) {
                    if (who != null) {
                        if (!who.equals(other.who)) {
                            return false;
                        }
                    } else if (other.who != null) {
                        return false;
                    }
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
                if (requestResolvedType != other.requestResolvedType) {
                    if (requestResolvedType != null) {
                        if (!requestResolvedType.equals(other.requestResolvedType)) {
                            return false;
                        }
                    } else if (other.requestResolvedType != null) {
                        return false;
                    }
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
                case ActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
                    return "activityResult";
            }
            return Integer.toString(type);
        }
    }
    
    PendingIntentRecord(ActivityManagerService _owner, Key _k, int _u) {
        owner = _owner;
        key = _k;
        uid = _u;
        ref = new WeakReference<PendingIntentRecord>(this);
    }

    public int send(int code, Intent intent, String resolvedType,
            IIntentReceiver finishedReceiver, String requiredPermission) {
        return sendInner(code, intent, resolvedType, finishedReceiver,
                requiredPermission, null, null, 0, 0, 0, null);
    }
    
    int sendInner(int code, Intent intent, String resolvedType,
            IIntentReceiver finishedReceiver, String requiredPermission,
            IBinder resultTo, String resultWho, int requestCode,
            int flagsMask, int flagsValues, Bundle options) {
        synchronized(owner) {
            if (!canceled) {
                sent = true;
                if ((key.flags&PendingIntent.FLAG_ONE_SHOT) != 0) {
                    owner.cancelIntentSenderLocked(this, true);
                    canceled = true;
                }
                Intent finalIntent = key.requestIntent != null
                        ? new Intent(key.requestIntent) : new Intent();
                if (intent != null) {
                    int changes = finalIntent.fillIn(intent, key.flags);
                    if ((changes&Intent.FILL_IN_DATA) == 0) {
                        resolvedType = key.requestResolvedType;
                    }
                } else {
                    resolvedType = key.requestResolvedType;
                }
                flagsMask &= ~Intent.IMMUTABLE_FLAGS;
                flagsValues &= flagsMask;
                finalIntent.setFlags((finalIntent.getFlags()&~flagsMask) | flagsValues);
                
                final long origId = Binder.clearCallingIdentity();
                
                boolean sendFinish = finishedReceiver != null;
                int userId = key.userId;
                if (userId == UserHandle.USER_CURRENT) {
                    userId = owner.getCurrentUserIdLocked();
                }
                switch (key.type) {
                    case ActivityManager.INTENT_SENDER_ACTIVITY:
                        if (options == null) {
                            options = key.options;
                        } else if (key.options != null) {
                            Bundle opts = new Bundle(key.options);
                            opts.putAll(options);
                            options = opts;
                        }
                        try {
                            if (key.allIntents != null && key.allIntents.length > 1) {
                                Intent[] allIntents = new Intent[key.allIntents.length];
                                String[] allResolvedTypes = new String[key.allIntents.length];
                                System.arraycopy(key.allIntents, 0, allIntents, 0,
                                        key.allIntents.length);
                                if (key.allResolvedTypes != null) {
                                    System.arraycopy(key.allResolvedTypes, 0, allResolvedTypes, 0,
                                            key.allResolvedTypes.length);
                                }
                                allIntents[allIntents.length-1] = finalIntent;
                                allResolvedTypes[allResolvedTypes.length-1] = resolvedType;
                                owner.startActivitiesInPackage(uid, allIntents,
                                        allResolvedTypes, resultTo, options, userId);
                            } else {
                                owner.startActivityInPackage(uid, finalIntent, resolvedType,
                                        resultTo, resultWho, requestCode, 0, options, userId);
                            }
                        } catch (RuntimeException e) {
                            Slog.w(ActivityManagerService.TAG,
                                    "Unable to send startActivity intent", e);
                        }
                        break;
                    case ActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
                        key.activity.stack.sendActivityResultLocked(-1, key.activity,
                                key.who, key.requestCode, code, finalIntent);
                        break;
                    case ActivityManager.INTENT_SENDER_BROADCAST:
                        try {
                            // If a completion callback has been requested, require
                            // that the broadcast be delivered synchronously
                            owner.broadcastIntentInPackage(key.packageName, uid,
                                    finalIntent, resolvedType,
                                    finishedReceiver, code, null, null,
                                requiredPermission, (finishedReceiver != null), false, userId);
                            sendFinish = false;
                        } catch (RuntimeException e) {
                            Slog.w(ActivityManagerService.TAG,
                                    "Unable to send startActivity intent", e);
                        }
                        break;
                    case ActivityManager.INTENT_SENDER_SERVICE:
                        try {
                            owner.startServiceInPackage(uid,
                                    finalIntent, resolvedType, userId);
                        } catch (RuntimeException e) {
                            Slog.w(ActivityManagerService.TAG,
                                    "Unable to send startService intent", e);
                        }
                        break;
                }
                
                if (sendFinish) {
                    try {
                        finishedReceiver.performReceive(new Intent(finalIntent), 0,
                                null, null, false, false, key.userId);
                    } catch (RemoteException e) {
                    }
                }
                
                Binder.restoreCallingIdentity(origId);
                
                return 0;
            }
        }
        return ActivityManager.START_CANCELED;
    }
    
    protected void finalize() throws Throwable {
        try {
            if (!canceled) {
                owner.mHandler.sendMessage(owner.mHandler.obtainMessage(
                        ActivityManagerService.FINALIZE_PENDING_INTENT_MSG, this));
            }
        } finally {
            super.finalize();
        }
    }

    public void completeFinalize() {
        synchronized(owner) {
            WeakReference<PendingIntentRecord> current =
                    owner.mIntentSenderRecords.get(key);
            if (current == ref) {
                owner.mIntentSenderRecords.remove(key);
            }
        }
    }
    
    void dump(PrintWriter pw, String prefix) {
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
        sb.append('}');
        return stringName = sb.toString();
    }
}
