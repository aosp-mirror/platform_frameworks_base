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

import android.app.IActivityManager;
import android.app.IIntentSender;
import android.app.IIntentReceiver;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

class PendingIntentRecord extends IIntentSender.Stub {
    final ActivityManagerService owner;
    final Key key;
    final int uid;
    final WeakReference<PendingIntentRecord> ref;
    boolean sent = false;
    boolean canceled = false;

    final static class Key {
        final int type;
        final String packageName;
        final HistoryRecord activity;
        final String who;
        final int requestCode;
        final Intent requestIntent;
        final String requestResolvedType;
        final int flags;
        final int hashCode;
        
        private static final int ODD_PRIME_NUMBER = 37;
        
        Key(int _t, String _p, HistoryRecord _a, String _w,
                int _r, Intent _i, String _it, int _f) {
            type = _t;
            packageName = _p;
            activity = _a;
            who = _w;
            requestCode = _r;
            requestIntent = _i;
            requestResolvedType = _it;
            flags = _f;
            
            int hash = 23;
            hash = (ODD_PRIME_NUMBER*hash) + _f;
            hash = (ODD_PRIME_NUMBER*hash) + _r;
            if (_w != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _w.hashCode();
            }
            if (_a != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _a.hashCode();
            }
            if (_i != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _i.filterHashCode();
            }
            if (_it != null) {
                hash = (ODD_PRIME_NUMBER*hash) + _it.hashCode();
            }
            hash = (ODD_PRIME_NUMBER*hash) + _p.hashCode();
            hash = (ODD_PRIME_NUMBER*hash) + _t;
            hashCode = hash;
            //Log.i(ActivityManagerService.TAG, this + " hashCode=0x"
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
                + " intent=" + requestIntent + " flags=0x"
                + Integer.toHexString(flags) + "}";
        }
        
        String typeName() {
            switch (type) {
                case IActivityManager.INTENT_SENDER_ACTIVITY:
                    return "startActivity";
                case IActivityManager.INTENT_SENDER_BROADCAST:
                    return "broadcastIntent";
                case IActivityManager.INTENT_SENDER_SERVICE:
                    return "startService";
                case IActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
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
            IIntentReceiver finishedReceiver) {
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
                
                final long origId = Binder.clearCallingIdentity();
                
                boolean sendFinish = finishedReceiver != null;
                switch (key.type) {
                    case IActivityManager.INTENT_SENDER_ACTIVITY:
                        try {
                            owner.startActivityInPackage(uid,
                                    finalIntent, resolvedType,
                                    null, null, 0, false);
                        } catch (RuntimeException e) {
                            Log.w(ActivityManagerService.TAG,
                                    "Unable to send startActivity intent", e);
                        }
                        break;
                    case IActivityManager.INTENT_SENDER_ACTIVITY_RESULT:
                        owner.sendActivityResultLocked(-1, key.activity,
                                key.who, key.requestCode, code, finalIntent);
                        break;
                    case IActivityManager.INTENT_SENDER_BROADCAST:
                        try {
                            // If a completion callback has been requested, require
                            // that the broadcast be delivered synchronously
                            owner.broadcastIntentInPackage(key.packageName, uid,
                                    finalIntent, resolvedType,
                                    finishedReceiver, code, null, null, null,
                                    (finishedReceiver != null), false);
                            sendFinish = false;
                        } catch (RuntimeException e) {
                            Log.w(ActivityManagerService.TAG,
                                    "Unable to send startActivity intent", e);
                        }
                        break;
                    case IActivityManager.INTENT_SENDER_SERVICE:
                        try {
                            owner.startServiceInPackage(uid,
                                    finalIntent, resolvedType);
                        } catch (RuntimeException e) {
                            Log.w(ActivityManagerService.TAG,
                                    "Unable to send startService intent", e);
                        }
                        break;
                }
                
                if (sendFinish) {
                    try {
                        finishedReceiver.performReceive(new Intent(finalIntent), 0,
                                null, null, false);
                    } catch (RemoteException e) {
                    }
                }
                
                Binder.restoreCallingIdentity(origId);
                
                return 0;
            }
        }
        return -1;
    }
    
    protected void finalize() throws Throwable {
        if (!canceled) {
            synchronized(owner) {
                WeakReference<PendingIntentRecord> current =
                        owner.mIntentSenderRecords.get(key);
                if (current == ref) {
                    owner.mIntentSenderRecords.remove(key);
                }
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "packageName=" + key.packageName
                + " type=" + key.typeName()
                + " flags=0x" + Integer.toHexString(key.flags));
        pw.println(prefix + "activity=" + key.activity + " who=" + key.who);
        pw.println(prefix + "requestCode=" + key.requestCode
                + " requestResolvedType=" + key.requestResolvedType);
        pw.println(prefix + "requestIntent=" + key.requestIntent);
        pw.println(prefix + "sent=" + sent + " canceled=" + canceled);
    }

    public String toString() {
        return "IntentSenderRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + key.packageName + " " + key.typeName() + "}";
    }
}
