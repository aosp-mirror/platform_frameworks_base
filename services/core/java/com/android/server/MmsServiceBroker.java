/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server;

import com.android.internal.telephony.IMms;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Slog;

/**
 * This class is a proxy for MmsService APIs. We need this because MmsService runs
 * in phone process and may crash anytime. This manages a connection to the actual
 * MmsService and bridges the public SMS/MMS APIs with MmsService implementation.
 */
public class MmsServiceBroker extends SystemService {
    private static final String TAG = "MmsServiceBroker";

    private static final ComponentName MMS_SERVICE_COMPONENT =
            new ComponentName("com.android.mms.service", "com.android.mms.service.MmsService");

    private static final int MSG_TRY_CONNECTING = 1;

    private static final Uri FAKE_SMS_SENT_URI = Uri.parse("content://sms/sent/0");
    private static final Uri FAKE_MMS_SENT_URI = Uri.parse("content://mms/sent/0");
    private static final Uri FAKE_SMS_DRAFT_URI = Uri.parse("content://sms/draft/0");
    private static final Uri FAKE_MMS_DRAFT_URI = Uri.parse("content://mms/draft/0");

    private static final long SERVICE_CONNECTION_WAIT_TIME_MS = 4 * 1000L; // 4 seconds
    private static final long RETRY_DELAY_ON_DISCONNECTION_MS = 3 * 1000L; // 3 seconds

    private Context mContext;
    // The actual MMS service instance to invoke
    private volatile IMms mService;

    // Cached system service instances
    private volatile AppOpsManager mAppOpsManager = null;
    private volatile PackageManager mPackageManager = null;
    private volatile TelephonyManager mTelephonyManager = null;

    private final Handler mConnectionHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TRY_CONNECTING:
                    tryConnecting();
                    break;
                default:
                    Slog.e(TAG, "Unknown message");
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.i(TAG, "MmsService connected");
            synchronized (MmsServiceBroker.this) {
                mService = IMms.Stub.asInterface(service);
                MmsServiceBroker.this.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.i(TAG, "MmsService unexpectedly disconnected");
            synchronized (MmsServiceBroker.this) {
                mService = null;
                MmsServiceBroker.this.notifyAll();
            }
            // Retry connecting, but not too eager (with a delay)
            // since it may come back by itself.
            mConnectionHandler.sendMessageDelayed(
                    mConnectionHandler.obtainMessage(MSG_TRY_CONNECTING),
                    RETRY_DELAY_ON_DISCONNECTION_MS);
        }
    };

    public MmsServiceBroker(Context context) {
        super(context);
        mContext = context;
        mService = null;
    }

    @Override
    public void onStart() {
        publishBinderService("imms", new BinderService());
    }

    public void systemRunning() {
        tryConnecting();
    }

    private void tryConnecting() {
        Slog.i(TAG, "Connecting to MmsService");
        synchronized (this) {
            if (mService != null) {
                Slog.d(TAG, "Already connected");
                return;
            }
            final Intent intent = new Intent();
            intent.setComponent(MMS_SERVICE_COMPONENT);
            try {
                if (!mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                    Slog.e(TAG, "Failed to bind to MmsService");
                }
            } catch (SecurityException e) {
                Slog.e(TAG, "Forbidden to bind to MmsService", e);
            }
        }
    }

    private void ensureService() {
        synchronized (this) {
            if (mService == null) {
                // Service is not connected. Try blocking connecting.
                Slog.w(TAG, "MmsService not connected. Try connecting...");
                mConnectionHandler.sendMessage(
                        mConnectionHandler.obtainMessage(MSG_TRY_CONNECTING));
                final long shouldEnd =
                        SystemClock.elapsedRealtime() + SERVICE_CONNECTION_WAIT_TIME_MS;
                long waitTime = SERVICE_CONNECTION_WAIT_TIME_MS;
                while (waitTime > 0) {
                    try {
                        // TODO: consider using Java concurrent construct instead of raw object wait
                        this.wait(waitTime);
                    } catch (InterruptedException e) {
                        Slog.w(TAG, "Connection wait interrupted", e);
                    }
                    if (mService != null) {
                        // Success
                        return;
                    }
                    // Calculate remaining waiting time to make sure we wait the full timeout period
                    waitTime = shouldEnd - SystemClock.elapsedRealtime();
                }
                // Timed out. Something's really wrong.
                Slog.e(TAG, "Can not connect to MmsService (timed out)");
                throw new RuntimeException("Timed out in connecting to MmsService");
            }
        }
    }

    /**
     * Making sure when we obtain the mService instance it is always valid.
     * Throws {@link RuntimeException} when it is empty.
     */
    private IMms getServiceGuarded() {
        ensureService();
        return mService;
    }

    private AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        }
        return mAppOpsManager;
    }

    private PackageManager getPackageManager() {
        if (mPackageManager == null) {
            mPackageManager = mContext.getPackageManager();
        }
        return mPackageManager;
    }

    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }
        return mTelephonyManager;
    }

    /*
     * Throws a security exception unless the caller has carrier privilege.
     */
    private void enforceCarrierPrivilege() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        for (String pkg : packages) {
            if (getTelephonyManager().checkCarrierPrivilegesForPackage(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }
        throw new SecurityException("No carrier privilege");
    }

    // Service API calls implementation, proxied to the real MmsService in "com.android.mms.service"
    private final class BinderService extends IMms.Stub {
        @Override
        public void sendMessage(long subId, String callingPkg, Uri contentUri,
                String locationUrl, Bundle configOverrides, PendingIntent sentIntent)
                        throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS, "Send MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().sendMessage(subId, callingPkg, contentUri, locationUrl,
                    configOverrides, sentIntent);
        }

        @Override
        public void downloadMessage(long subId, String callingPkg, String locationUrl,
                Uri contentUri, Bundle configOverrides,
                PendingIntent downloadedIntent) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.RECEIVE_MMS,
                    "Download MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_RECEIVE_MMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().downloadMessage(subId, callingPkg, locationUrl, contentUri,
                    configOverrides, downloadedIntent);
        }

        @Override
        public void updateMmsSendStatus(int messageRef, byte[] pdu, int status)
                throws RemoteException {
            enforceCarrierPrivilege();
            getServiceGuarded().updateMmsSendStatus(messageRef, pdu, status);
        }

        @Override
        public void updateMmsDownloadStatus(int messageRef, int status) throws RemoteException {
            enforceCarrierPrivilege();
            getServiceGuarded().updateMmsDownloadStatus(messageRef, status);
        }

        @Override
        public Bundle getCarrierConfigValues(long subId) throws RemoteException {
            return getServiceGuarded().getCarrierConfigValues(subId);
        }

        @Override
        public Uri importTextMessage(String callingPkg, String address, int type, String text,
                long timestampMillis, boolean seen, boolean read) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Import SMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                // Silently fail AppOps failure due to not being the default SMS app
                // while writing the TelephonyProvider
                return FAKE_SMS_SENT_URI;
            }
            return getServiceGuarded().importTextMessage(
                    callingPkg, address, type, text, timestampMillis, seen, read);
        }

        @Override
        public Uri importMultimediaMessage(String callingPkg, Uri contentUri,
                String messageId, long timestampSecs, boolean seen, boolean read)
                        throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Import MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                // Silently fail AppOps failure due to not being the default SMS app
                // while writing the TelephonyProvider
                return FAKE_MMS_SENT_URI;
            }
            return getServiceGuarded().importMultimediaMessage(
                    callingPkg, contentUri, messageId, timestampSecs, seen, read);
        }

        @Override
        public boolean deleteStoredMessage(String callingPkg, Uri messageUri)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Delete SMS/MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            return getServiceGuarded().deleteStoredMessage(callingPkg, messageUri);
        }

        @Override
        public boolean deleteStoredConversation(String callingPkg, long conversationId)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Delete conversation");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            return getServiceGuarded().deleteStoredConversation(callingPkg, conversationId);
        }

        @Override
        public boolean updateStoredMessageStatus(String callingPkg, Uri messageUri,
                ContentValues statusValues) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Update SMS/MMS message");
            return getServiceGuarded()
                    .updateStoredMessageStatus(callingPkg, messageUri, statusValues);
        }

        @Override
        public boolean archiveStoredConversation(String callingPkg, long conversationId,
                boolean archived) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS,
                    "Update SMS/MMS message");
            return getServiceGuarded()
                    .archiveStoredConversation(callingPkg, conversationId, archived);
        }

        @Override
        public Uri addTextMessageDraft(String callingPkg, String address, String text)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Add SMS draft");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                // Silently fail AppOps failure due to not being the default SMS app
                // while writing the TelephonyProvider
                return FAKE_SMS_DRAFT_URI;
            }
            return getServiceGuarded().addTextMessageDraft(callingPkg, address, text);
        }

        @Override
        public Uri addMultimediaMessageDraft(String callingPkg, Uri contentUri)
                throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Add MMS draft");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                // Silently fail AppOps failure due to not being the default SMS app
                // while writing the TelephonyProvider
                return FAKE_MMS_DRAFT_URI;
            }
            return getServiceGuarded().addMultimediaMessageDraft(callingPkg, contentUri);
        }

        @Override
        public void sendStoredMessage(long subId, String callingPkg, Uri messageUri,
                Bundle configOverrides, PendingIntent sentIntent) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.SEND_SMS,
                    "Send stored MMS message");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().sendStoredMessage(subId, callingPkg, messageUri, configOverrides,
                    sentIntent);
        }

        @Override
        public void setAutoPersisting(String callingPkg, boolean enabled) throws RemoteException {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_SMS, "Set auto persist");
            if (getAppOpsManager().noteOp(AppOpsManager.OP_WRITE_SMS, Binder.getCallingUid(),
                    callingPkg) != AppOpsManager.MODE_ALLOWED) {
                return;
            }
            getServiceGuarded().setAutoPersisting(callingPkg, enabled);
        }

        @Override
        public boolean getAutoPersisting() throws RemoteException {
            return getServiceGuarded().getAutoPersisting();
        }
    }
}
