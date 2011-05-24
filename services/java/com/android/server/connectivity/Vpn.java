/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.INetworkManagementEventObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;
import com.android.server.ConnectivityService.VpnCallback;

/**
 * @hide
 */
public class Vpn extends INetworkManagementEventObserver.Stub {

    private final static String TAG = "Vpn";
    private final static String VPN = android.Manifest.permission.VPN;

    private final Context mContext;
    private final VpnCallback mCallback;

    private String mPackageName;
    private String mInterfaceName;
    private String mDnsPropertyPrefix;

    public Vpn(Context context, VpnCallback callback) {
        mContext = context;
        mCallback = callback;
    }

    /**
     * Prepare for a VPN application.
     *
     * @param packageName The package name of the new VPN application.
     * @return The name of the current prepared package.
     */
    public synchronized String prepare(String packageName) {

        // TODO: Check if the caller is VpnDialogs.

        if (packageName == null) {
            return mPackageName;
        }

        // Check the permission of the given application.
        PackageManager pm = mContext.getPackageManager();
        if (pm.checkPermission(VPN, packageName) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(packageName + " does not have " + VPN);
        }

        // Reset the interface and hide the notification.
        if (mInterfaceName != null) {
            nativeReset(mInterfaceName);
            mInterfaceName = null;
            hideNotification();
            // TODO: Send out a broadcast.
        }

        mPackageName = packageName;
        Log.i(TAG, "Prepared for " + packageName);
        return mPackageName;
    }

    /**
     * Protect a socket from routing changes by binding it to the given
     * interface. The socket is NOT closed by this method.
     *
     * @param socket The socket to be bound.
     * @param name The name of the interface.
     */
    public void protect(ParcelFileDescriptor socket, String name) {
        mContext.enforceCallingPermission(VPN, "protect");
        nativeProtect(socket.getFd(), name);
    }

    /**
     * Configure a TUN interface and return its file descriptor.
     *
     * @param configuration The parameters to configure the interface.
     * @return The file descriptor of the interface.
     */
    public synchronized ParcelFileDescriptor establish(Bundle config) {
        // Check the permission of the caller.
        mContext.enforceCallingPermission(VPN, "establish");

        // Check if the caller is already prepared.
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(mPackageName, 0);
        } catch (Exception e) {
            throw new SecurityException("Not prepared");
        }
        if (Binder.getCallingUid() != app.uid) {
            throw new SecurityException("Not prepared");
        }

        // Unpack the config.
        // TODO: move constants into VpnBuilder.
        String session = config.getString("session");
        String addresses = config.getString("addresses");
        String routes = config.getString("routes");
        String dnsServers = config.getString("dnsServers");

        // Create interface and configure addresses and routes.
        ParcelFileDescriptor descriptor = nativeConfigure(addresses, routes);

        // Replace the interface and abort if it fails.
        try {
            String interfaceName = nativeGetName(descriptor.getFd());

            if (mInterfaceName != null && !mInterfaceName.equals(interfaceName)) {
                nativeReset(mInterfaceName);
            }
            mInterfaceName = interfaceName;
        } catch (RuntimeException e) {
            try {
                descriptor.close();
            } catch (Exception ex) {
                // ignore
            }
            throw e;
        }

        dnsServers = (dnsServers == null) ? "" : dnsServers.trim();
        mCallback.override(dnsServers.isEmpty() ? null : dnsServers.split(" "));

        showNotification(pm, app, session);
        return descriptor;
    }

    public synchronized boolean onInterfaceRemoved(String name) {
        if (name.equals(mInterfaceName) && nativeCheck(name) == 0) {
            hideNotification();
            mInterfaceName = null;
            return true;
        }
        return false;
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceLinkStatusChanged(String name, boolean up) {
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceAdded(String name) {
    }

    // INetworkManagementEventObserver.Stub
    public synchronized void interfaceRemoved(String name) {
        if (name.equals(mInterfaceName) && nativeCheck(name) == 0) {
            hideNotification();
            mInterfaceName = null;
            mCallback.restore();
        }
    }

    private void showNotification(PackageManager pm, ApplicationInfo app, String session) {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            // Load the icon and convert it into a bitmap.
            Drawable icon = app.loadIcon(pm);
            Bitmap bitmap = null;
            if (icon.getIntrinsicWidth() > 0 && icon.getIntrinsicHeight() > 0) {
                int width = mContext.getResources().getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_width);
                int height = mContext.getResources().getDimensionPixelSize(
                        android.R.dimen.notification_large_icon_height);
                icon.setBounds(0, 0, width, height);
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                icon.draw(new Canvas(bitmap));
            }

            // Load the label.
            String label = app.loadLabel(pm).toString();

            // If session is null, use the application name instead.
            if (session == null) {
                session = label;
            }

            // Build the intent.
            // TODO: move these into VpnBuilder.
            Intent intent = new Intent();
            intent.setClassName("com.android.vpndialogs",
                    "com.android.vpndialogs.ManageDialog");
            intent.putExtra("packageName", mPackageName);
            intent.putExtra("interfaceName", mInterfaceName);
            intent.putExtra("session", session);
            intent.putExtra("startTime", android.os.SystemClock.elapsedRealtime());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            // Build the notification.
            long identity = Binder.clearCallingIdentity();
            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.vpn_connected)
                    .setLargeIcon(bitmap)
                    .setTicker(mContext.getString(R.string.vpn_ticker, label))
                    .setContentTitle(mContext.getString(R.string.vpn_title, label))
                    .setContentText(mContext.getString(R.string.vpn_text, session))
                    .setContentIntent(PendingIntent.getActivity(mContext, 0, intent, 0))
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setOngoing(true)
                    .getNotification();

            nm.notify(R.drawable.vpn_connected, notification);
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void hideNotification() {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            long identity = Binder.clearCallingIdentity();
            nm.cancel(R.drawable.vpn_connected);
            Binder.restoreCallingIdentity(identity);
        }
    }

    private native ParcelFileDescriptor nativeConfigure(String addresses, String routes);
    private native String nativeGetName(int fd);
    private native void nativeReset(String name);
    private native int nativeCheck(String name);
    private native void nativeProtect(int fd, String name);
}
