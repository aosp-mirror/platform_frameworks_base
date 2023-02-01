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

package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyChain.KeyChainConnection;
import android.util.PluralsMessageFormatter;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.utils.Slogf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CertificateMonitor {
    protected static final int MONITORING_CERT_NOTIFICATION_ID = SystemMessage.NOTE_SSL_CERT_INFO;

    private final DevicePolicyManagerService mService;
    private final DevicePolicyManagerService.Injector mInjector;
    private final Handler mHandler;

    public CertificateMonitor(final DevicePolicyManagerService service,
            final DevicePolicyManagerService.Injector injector, final Handler handler) {
        mService = service;
        mInjector = injector;
        mHandler = handler;

        // Broadcast filter for changes to the trusted certificate store. Listens on the background
        // handler to avoid blocking time-critical tasks on the main handler thread.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_STARTED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(KeyChain.ACTION_TRUST_STORE_CHANGED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mInjector.mContext.registerReceiverAsUser(
                mRootCaReceiver, UserHandle.ALL, filter, null, mHandler);
    }

    public String installCaCert(final UserHandle userHandle, byte[] certBuffer) {
        // Convert certificate data from X509 format to PEM.
        byte[] pemCert;
        try {
            X509Certificate cert = parseCert(certBuffer);
            pemCert = Credentials.convertToPem(cert);
        } catch (CertificateException | IOException ce) {
            Slogf.e(LOG_TAG, "Problem converting cert", ce);
            return null;
        }

        try (KeyChainConnection keyChainConnection = mInjector.keyChainBindAsUser(userHandle)) {
            return keyChainConnection.getService().installCaCertificate(pemCert);
        } catch (RemoteException e) {
            Slogf.e(LOG_TAG, "installCaCertsToKeyChain(): ", e);
        } catch (InterruptedException e1) {
            Slogf.w(LOG_TAG, "installCaCertsToKeyChain(): ", e1);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public void uninstallCaCerts(final UserHandle userHandle, final String[] aliases) {
        try (KeyChainConnection keyChainConnection = mInjector.keyChainBindAsUser(userHandle)) {
            for (int i = 0 ; i < aliases.length; i++) {
                keyChainConnection.getService().deleteCaCertificate(aliases[i]);
            }
        } catch (RemoteException e) {
            Slogf.e(LOG_TAG, "from CaCertUninstaller: ", e);
        } catch (InterruptedException ie) {
            Slogf.w(LOG_TAG, "CaCertUninstaller: ", ie);
            Thread.currentThread().interrupt();
        }
    }

    private List<String> getInstalledCaCertificates(UserHandle userHandle)
            throws RemoteException, RuntimeException {
        try (KeyChainConnection conn = mInjector.keyChainBindAsUser(userHandle)) {
            return conn.getService().getUserCaAliases().getList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (AssertionError e) {
            throw new RuntimeException(e);
        }
    }

    public void onCertificateApprovalsChanged(int userId) {
        mHandler.post(() -> updateInstalledCertificates(UserHandle.of(userId)));
    }

    /**
     * Broadcast receiver for changes to the trusted certificate store.
     */
    private final BroadcastReceiver mRootCaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
            updateInstalledCertificates(UserHandle.of(userId));
        }
    };

    private void updateInstalledCertificates(final UserHandle userHandle) {
        final int userId = userHandle.getIdentifier();
        if (!mInjector.getUserManager().isUserUnlocked(userId)) {
            return;
        }

        final List<String> installedCerts;
        try {
            installedCerts = getInstalledCaCertificates(userHandle);
        } catch (RemoteException | RuntimeException e) {
            Slogf.e(LOG_TAG, e, "Could not retrieve certificates from KeyChain service for user %d",
                    userId);
            return;
        }
        mService.onInstalledCertificatesChanged(userHandle, installedCerts);

        final int pendingCertificateCount =
                installedCerts.size() - mService.getAcceptedCaCertificates(userHandle).size();
        if (pendingCertificateCount != 0) {
            final Notification noti = buildNotification(userHandle, pendingCertificateCount);
            mInjector.getNotificationManager().notifyAsUser(
                    LOG_TAG, MONITORING_CERT_NOTIFICATION_ID, noti, userHandle);
        } else {
            mInjector.getNotificationManager().cancelAsUser(
                    LOG_TAG, MONITORING_CERT_NOTIFICATION_ID, userHandle);
        }
    }

    private Notification buildNotification(UserHandle userHandle, int pendingCertificateCount) {
        final Context userContext;
        try {
            userContext = mInjector.createContextAsUser(userHandle);
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(LOG_TAG, e, "Create context as %s failed", userHandle);
            return null;
        }

        final Resources resources = mInjector.getResources();
        final int smallIconId;
        final String contentText;

        int parentUserId = userHandle.getIdentifier();

        if (mService.getProfileOwnerAsUser(userHandle.getIdentifier()) != null) {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_managed,
                    mService.getProfileOwnerName(userHandle.getIdentifier()));
            smallIconId = R.drawable.stat_sys_certificate_info;
            parentUserId = mService.getProfileParentId(userHandle.getIdentifier());
        } else if (mService.getDeviceOwnerUserId() == userHandle.getIdentifier()) {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_managed,
                    mService.getDeviceOwnerName());
            smallIconId = R.drawable.stat_sys_certificate_info;
        } else {
            contentText = resources.getString(R.string.ssl_ca_cert_noti_by_unknown);
            smallIconId = android.R.drawable.stat_sys_warning;
        }

        // Create an intent to launch an activity showing information about the certificate.
        Intent dialogIntent = new Intent(Settings.ACTION_MONITORING_CERT_INFO);
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        dialogIntent.putExtra(Settings.EXTRA_NUMBER_OF_CERTIFICATES, pendingCertificateCount);
        dialogIntent.putExtra(Intent.EXTRA_USER_ID, userHandle.getIdentifier());

        // The intent should only be allowed to resolve to a system app.
        ActivityInfo targetInfo = dialogIntent.resolveActivityInfo(
                mInjector.getPackageManager(), PackageManager.MATCH_SYSTEM_ONLY);
        if (targetInfo != null) {
            dialogIntent.setComponent(targetInfo.getComponentName());
        }

        // Simple notification clicks are immutable
        PendingIntent notifyIntent = mInjector.pendingIntentGetActivityAsUser(userContext, 0,
                dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                null, UserHandle.of(parentUserId));

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", pendingCertificateCount);

        return new Notification.Builder(userContext, SystemNotificationChannels.SECURITY)
                .setSmallIcon(smallIconId)
                .setContentTitle(PluralsMessageFormatter.format(
                        resources,
                        arguments,
                        R.string.ssl_ca_cert_warning))
                .setContentText(contentText)
                .setContentIntent(notifyIntent)
                .setShowWhen(false)
                .setColor(R.color.system_notification_accent_color)
                .build();
    }

    private static X509Certificate parseCert(byte[] certBuffer) throws CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(
                certBuffer));
    }
}
