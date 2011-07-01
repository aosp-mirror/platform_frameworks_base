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
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.net.VpnConfig;
import com.android.server.ConnectivityService.VpnCallback;

import java.io.OutputStream;
import java.nio.charset.Charsets;

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

    private LegacyVpnRunner mLegacyVpnRunner;

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
            jniResetInterface(mInterfaceName);
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
            jniProtectSocket(socket.getFd(), name);
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
     * @param config The parameters to configure the interface.
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

        // Load the label.
        String label = app.loadLabel(pm).toString();

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

        // Create the interface and abort if any of the following steps fails.
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.adoptFd(jniCreateInterface(config.mtu));
        try {
            String name = jniGetInterfaceName(descriptor.getFd());
            if (jniSetAddresses(name, config.addresses) < 1) {
                throw new IllegalArgumentException("At least one address must be specified");
            }
            if (config.routes != null) {
                jniSetRoutes(name, config.routes);
            }
            if (mInterfaceName != null && !mInterfaceName.equals(name)) {
                jniResetInterface(mInterfaceName);
            }
            mInterfaceName = name;
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
        showNotification(config, label, bitmap);
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
        if (name.equals(mInterfaceName) && jniCheckInterface(name) == 0) {
            hideNotification();
            mCallback.restore();
            mInterfaceName = null;
        }
    }

    private void showNotification(VpnConfig config, String label, Bitmap icon) {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            String title = (label == null) ? mContext.getString(R.string.vpn_title) :
                    mContext.getString(R.string.vpn_title_long, label);
            String text = (config.sessionName == null) ? mContext.getString(R.string.vpn_text) :
                    mContext.getString(R.string.vpn_text_long, config.sessionName);

            long identity = Binder.clearCallingIdentity();
            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.vpn_connected)
                    .setLargeIcon(icon)
                    .setContentTitle(title)
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

    private native int jniCreateInterface(int mtu);
    private native String jniGetInterfaceName(int fd);
    private native int jniSetAddresses(String name, String addresses);
    private native int jniSetRoutes(String name, String routes);
    private native void jniResetInterface(String name);
    private native int jniCheckInterface(String name);
    private native void jniProtectSocket(int fd, String name);

    /**
     * Handle legacy VPN requests. This method stops the services and restart
     * them if their arguments are not null. Heavy things are offloaded to
     * another thread, so callers will not be blocked too long.
     *
     * @param raoocn The arguments to be passed to racoon.
     * @param mtpd The arguments to be passed to mtpd.
     */
    public synchronized void doLegacyVpn(String[] racoon, String[] mtpd) {
        // Currently only system user is allowed.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }

        // If the previous runner is still alive, interrupt it.
        if (mLegacyVpnRunner != null && mLegacyVpnRunner.isAlive()) {
            mLegacyVpnRunner.interrupt();
        }

        // Start a new runner and we are done!
        mLegacyVpnRunner = new LegacyVpnRunner(
                new String[] {"racoon", "mtpd"}, racoon, mtpd);
        mLegacyVpnRunner.start();
    }

    /**
     * Bringing up a VPN connection takes time, and that is all this thread
     * does. Here we have plenty of time. The only thing we need to take
     * care of is responding to interruptions as soon as possible. Otherwise
     * requests will be piled up. This can be done in a Handler as a state
     * machine, but it is much easier to read in the current form.
     */
    private class LegacyVpnRunner extends Thread {
        private static final String TAG = "LegacyVpnRunner";

        private static final String NONE = "--";

        private final String[] mServices;
        private final String[][] mArguments;
        private long mTimer = -1;

        public LegacyVpnRunner(String[] services, String[]... arguments) {
            super(TAG);
            mServices = services;
            mArguments = arguments;
        }

        @Override
        public void run() {
            // Wait for the previous thread since it has been interrupted.
            Log.v(TAG, "wait");
            synchronized (TAG) {
                Log.v(TAG, "run");
                execute();
                Log.v(TAG, "exit");
            }
        }

        private void checkpoint(boolean yield) throws InterruptedException {
            long now = SystemClock.elapsedRealtime();
            if (mTimer == -1) {
                mTimer = now;
                Thread.sleep(1);
            } else if (now - mTimer <= 30000) {
                Thread.sleep(yield ? 200 : 1);
            } else {
                throw new InterruptedException("timeout");
            }
        }

        private void execute() {
            // Catch all exceptions so we can clean up few things.
            try {
                // Initialize the timer.
                checkpoint(false);

                // First stop the services.
                for (String service : mServices) {
                    SystemProperties.set("ctl.stop", service);
                }

                // Wait for the services to stop.
                for (String service : mServices) {
                    String key = "init.svc." + service;
                    while (!"stopped".equals(SystemProperties.get(key))) {
                        checkpoint(true);
                    }
                }

                // Reset the properties.
                SystemProperties.set("vpn.dns", NONE);
                SystemProperties.set("vpn.via", NONE);
                while (!NONE.equals(SystemProperties.get("vpn.dns")) ||
                        !NONE.equals(SystemProperties.get("vpn.via"))) {
                    checkpoint(true);
                }

                // Check if we need to restart some services.
                boolean restart = false;
                for (String[] arguments : mArguments) {
                    restart = restart || (arguments != null);
                }
                if (!restart) {
                    return;
                }

                // Start the service with arguments.
                for (int i = 0; i < mServices.length; ++i) {
                    String[] arguments = mArguments[i];
                    if (arguments == null) {
                        continue;
                    }

                    // Start the service.
                    String service = mServices[i];
                    SystemProperties.set("ctl.start", service);

                    // Wait for the service to start.
                    String key = "init.svc." + service;
                    while (!"running".equals(SystemProperties.get(key))) {
                        checkpoint(true);
                    }

                    // Create the control socket.
                    LocalSocket socket = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            service, LocalSocketAddress.Namespace.RESERVED);

                    // Wait for the socket to connect.
                    while (true) {
                        try {
                            socket.connect(address);
                            break;
                        } catch (Exception e) {
                            // ignore
                        }
                        checkpoint(true);
                    }
                    socket.setSoTimeout(500);

                    // Send over the arguments.
                    OutputStream output = socket.getOutputStream();
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(Charsets.UTF_8);
                        if (bytes.length >= 0xFFFF) {
                            throw new IllegalArgumentException("argument too large");
                        }
                        output.write(bytes.length >> 8);
                        output.write(bytes.length);
                        output.write(bytes);
                        checkpoint(false);
                    }

                    // Send End-Of-Arguments.
                    output.write(0xFF);
                    output.write(0xFF);
                    output.flush();
                    socket.close();
                }

                // Now here is the beast from the old days. We check few
                // properties to figure out the current status. Ideally we
                // can read things back from the sockets and get rid of the
                // properties, but we have no time...
                while (NONE.equals(SystemProperties.get("vpn.dns")) ||
                        NONE.equals(SystemProperties.get("vpn.via"))) {

                    // Check if a running service is dead.
                    for (int i = 0; i < mServices.length; ++i) {
                        String service = mServices[i];
                        if (mArguments[i] != null && !"running".equals(
                                SystemProperties.get("init.svc." + service))) {
                            throw new IllegalArgumentException(service + " is dead");
                        }
                    }
                    checkpoint(true);
                }

                // Great! Now we are connected!
                Log.i(TAG, "connected!");
                // TODO:

            } catch (Exception e) {
                Log.i(TAG, e.getMessage());
                for (String service : mServices) {
                    SystemProperties.set("ctl.stop", service);
                }
            }
        }
    }
}
