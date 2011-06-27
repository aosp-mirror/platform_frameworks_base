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
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.net.VpnConfig;
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
        // Return the current prepared package if the new one is null.
        if (packageName == null) {
            return mPackageName;
        }

        // Check the permission of the caller.
        PackageManager pm = mContext.getPackageManager();
        VpnConfig.enforceCallingPackage(pm.getNameForUid(Binder.getCallingUid()));

        // Check the permission of the given package.
        if (packageName.isEmpty()) {
            packageName = null;
        } else if (pm.checkPermission(VPN, packageName) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(packageName + " does not have " + VPN);
        }

        // Reset the interface and hide the notification.
        if (mInterfaceName != null) {
            nativeReset(mInterfaceName);
            mCallback.restore();
            hideNotification();
            mInterfaceName = null;
        }

        // Notify the package being revoked.
        if (mPackageName != null) {
            Intent intent = new Intent(VpnConfig.ACTION_VPN_REVOKED);
            intent.setPackage(mPackageName);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcast(intent);
        }

        Log.i(TAG, "Switched from " + mPackageName + " to " + packageName);
        mPackageName = packageName;
        return mPackageName;
    }

    /**
     * Protect a socket from routing changes by binding it to the given
     * interface. The socket IS closed by this method.
     *
     * @param socket The socket to be bound.
     * @param name The name of the interface.
     */
    public void protect(ParcelFileDescriptor socket, String name) {
        try {
            mContext.enforceCallingPermission(VPN, "protect");
            nativeProtect(socket.getFd(), name);
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Configure a TUN interface and return its file descriptor.
     *
     * @param configuration The parameters to configure the interface.
     * @return The file descriptor of the interface.
     */
    public synchronized ParcelFileDescriptor establish(VpnConfig config) {
        // Check the permission of the caller.
        mContext.enforceCallingPermission(VPN, "establish");

        // Check if the caller is already prepared.
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(mPackageName, 0);
        } catch (Exception e) {
            return null;
        }
        if (Binder.getCallingUid() != app.uid) {
            return null;
        }

        // Create and configure the interface.
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(
                nativeEstablish(config.mtu, config.addresses, config.routes));

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

        String dnsServers = (config.dnsServers == null) ? "" : config.dnsServers.trim();
        mCallback.override(dnsServers.isEmpty() ? null : dnsServers.split(" "));

        config.packageName = mPackageName;
        config.interfaceName = mInterfaceName;
        showNotification(pm, app, config);
        return descriptor;
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceStatusChanged(String name, boolean up) {
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceLinkStateChanged(String name, boolean up) {
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

    private void showNotification(PackageManager pm, ApplicationInfo app, VpnConfig config) {
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

            // Build the notification.
            String text = (config.sessionName == null) ? mContext.getString(R.string.vpn_text) :
                    mContext.getString(R.string.vpn_text_long, config.sessionName);
            long identity = Binder.clearCallingIdentity();
            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.vpn_connected)
                    .setLargeIcon(bitmap)
                    .setTicker(mContext.getString(R.string.vpn_ticker, label))
                    .setContentTitle(mContext.getString(R.string.vpn_title, label))
                    .setContentText(text)
                    .setContentIntent(VpnConfig.getIntentForNotification(mContext, config))
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

    private native int nativeEstablish(int mtu, String addresses, String routes);
    private native String nativeGetName(int fd);
    private native void nativeReset(String name);
    private native int nativeCheck(String name);
    private native void nativeProtect(int fd, String name);
}
