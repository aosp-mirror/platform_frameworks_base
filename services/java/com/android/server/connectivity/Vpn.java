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
import java.util.Arrays;

/**
 * @hide
 */
public class Vpn extends INetworkManagementEventObserver.Stub {

    private final static String TAG = "Vpn";
    private final static String VPN = android.Manifest.permission.VPN;

    private final Context mContext;
    private final VpnCallback mCallback;

    private String mPackageName = VpnConfig.LEGACY_VPN;
    private String mInterfaceName;
    private LegacyVpnRunner mLegacyVpnRunner;

    public Vpn(Context context, VpnCallback callback) {
        mContext = context;
        mCallback = callback;
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
     * Prepare for a VPN application. If the new application is valid,
     * the previous prepared application is revoked. Since legacy VPN
     * is not a real application, it uses {@link VpnConfig#LEGACY_VPN}
     * as its package name. Note that this method does not check if
     * the applications are the same.
     *
     * @param packageName The package name of the VPN application.
     * @return The package name of the current prepared application.
     */
    public synchronized String prepare(String packageName) {
        // Return the current prepared application if the new one is null.
        if (packageName == null) {
            return mPackageName;
        }

        // Only system user can call this method.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }

        // Check the permission of the given package.
        PackageManager pm = mContext.getPackageManager();
        if (!packageName.equals(VpnConfig.LEGACY_VPN) &&
                pm.checkPermission(VPN, packageName) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(packageName + " does not have " + VPN);
        }

        // Reset the interface and hide the notification.
        if (mInterfaceName != null) {
            jniResetInterface(mInterfaceName);
            mCallback.restore();
            hideNotification();
            mInterfaceName = null;
        }

        // Send out the broadcast or stop LegacyVpnRunner.
        if (!mPackageName.equals(VpnConfig.LEGACY_VPN)) {
            Intent intent = new Intent(VpnConfig.ACTION_VPN_REVOKED);
            intent.setPackage(mPackageName);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcast(intent);
        } else if (mLegacyVpnRunner != null) {
            mLegacyVpnRunner.exit();
            mLegacyVpnRunner = null;
        }

        Log.i(TAG, "Switched from " + mPackageName + " to " + packageName);
        mPackageName = packageName;
        return mPackageName;
    }

    /**
     * Establish a VPN network and return the file descriptor of the VPN
     * interface. This methods returns {@code null} if the application is
     * not prepared or revoked.
     *
     * @param config The parameters to configure the network.
     * @return The file descriptor of the VPN interface.
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

        // Configure the interface. Abort if any of these steps fails.
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(
                jniConfigure(config.mtu, config.addresses, config.routes));
        try {
            String name = jniGetInterfaceName(descriptor.getFd());
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

        // Override DNS servers and search domains.
        mCallback.override(config.dnsServers, config.searchDomains);

        // Fill more values.
        config.packageName = mPackageName;
        config.interfaceName = mInterfaceName;

        // Show the notification!
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
            mCallback.restore();
            hideNotification();
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

    private native int jniConfigure(int mtu, String addresses, String routes);
    private native String jniGetInterfaceName(int fd);
    private native void jniResetInterface(String name);
    private native int jniCheckInterface(String name);
    private native void jniProtectSocket(int fd, String name);

    /**
     * Handle a legacy VPN request. This method stops the daemons and restart
     * them if arguments are not null. Heavy things are offloaded to another
     * thread, so callers will not be blocked for a long time.
     *
     * @param config The parameters to configure the network.
     * @param raoocn The arguments to be passed to racoon.
     * @param mtpd The arguments to be passed to mtpd.
     */
    public synchronized void doLegacyVpn(VpnConfig config, String[] racoon, String[] mtpd) {
        // There is nothing to stop if another VPN application is prepared.
        if (config == null && !mPackageName.equals(VpnConfig.LEGACY_VPN)) {
            return;
        }

        // Reset everything. This also checks the caller.
        prepare(VpnConfig.LEGACY_VPN);

        // Start a new runner and we are done!
        if (config != null) {
            mLegacyVpnRunner = new LegacyVpnRunner(config, racoon, mtpd);
            mLegacyVpnRunner.start();
        }
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

        private final VpnConfig mConfig;
        private final String[] mDaemons;
        private final String[][] mArguments;
        private long mTimer = -1;

        public LegacyVpnRunner(VpnConfig config, String[] racoon, String[] mtpd) {
            super(TAG);
            mConfig = config;
            mDaemons = new String[] {"racoon", "mtpd"};
            mArguments = new String[][] {racoon, mtpd};

            mConfig.packageName = VpnConfig.LEGACY_VPN;
        }

        public void exit() {
            // We assume that everything is reset after the daemons die.
            for (String daemon : mDaemons) {
                SystemProperties.set("ctl.stop", daemon);
            }
            interrupt();
        }

        @Override
        public void run() {
            // Wait for the previous thread since it has been interrupted.
            Log.v(TAG, "wait");
            synchronized (TAG) {
                Log.v(TAG, "begin");
                execute();
                Log.v(TAG, "end");
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
                throw new InterruptedException("time is up");
            }
        }

        private void execute() {
            // Catch all exceptions so we can clean up few things.
            try {
                // Initialize the timer.
                checkpoint(false);

                // First stop the daemons.
                for (String daemon : mDaemons) {
                    SystemProperties.set("ctl.stop", daemon);
                }

                // Wait for the daemons to stop.
                for (String daemon : mDaemons) {
                    String key = "init.svc." + daemon;
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

                // Check if we need to restart any of the daemons.
                boolean restart = false;
                for (String[] arguments : mArguments) {
                    restart = restart || (arguments != null);
                }
                if (!restart) {
                    return;
                }

                // Start the daemon with arguments.
                for (int i = 0; i < mDaemons.length; ++i) {
                    String[] arguments = mArguments[i];
                    if (arguments == null) {
                        continue;
                    }

                    // Start the daemon.
                    String daemon = mDaemons[i];
                    SystemProperties.set("ctl.start", daemon);

                    // Wait for the daemon to start.
                    String key = "init.svc." + daemon;
                    while (!"running".equals(SystemProperties.get(key))) {
                        checkpoint(true);
                    }

                    // Create the control socket.
                    LocalSocket socket = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            daemon, LocalSocketAddress.Namespace.RESERVED);

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
                    OutputStream out = socket.getOutputStream();
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(Charsets.UTF_8);
                        if (bytes.length >= 0xFFFF) {
                            throw new IllegalArgumentException("argument is too large");
                        }
                        out.write(bytes.length >> 8);
                        out.write(bytes.length);
                        out.write(bytes);
                        checkpoint(false);
                    }

                    // Send End-Of-Arguments.
                    out.write(0xFF);
                    out.write(0xFF);
                    out.flush();
                    socket.close();
                }

                // Now here is the beast from the old days. We check few
                // properties to figure out the current status. Ideally we
                // can read things back from the sockets and get rid of the
                // properties, but we have no time...
                while (NONE.equals(SystemProperties.get("vpn.dns")) ||
                        NONE.equals(SystemProperties.get("vpn.via"))) {

                    // Check if a running daemon is dead.
                    for (int i = 0; i < mDaemons.length; ++i) {
                        String daemon = mDaemons[i];
                        if (mArguments[i] != null && !"running".equals(
                                SystemProperties.get("init.svc." + daemon))) {
                            throw new IllegalArgumentException(daemon + " is dead");
                        }
                    }
                    checkpoint(true);
                }

                // Now we are connected. Get the interface.
                mConfig.interfaceName = SystemProperties.get("vpn.via");

                // Get the DNS servers if they are not set in config.
                if (mConfig.dnsServers == null || mConfig.dnsServers.size() == 0) {
                    String dnsServers = SystemProperties.get("vpn.dns").trim();
                    if (!dnsServers.isEmpty()) {
                        mConfig.dnsServers = Arrays.asList(dnsServers.split(" "));
                    }
                }

                // TODO: support search domains from ISAKMP mode config.

                // The final step must be synchronized.
                synchronized (Vpn.this) {
                    // Check if the thread is interrupted while we are waiting.
                    checkpoint(false);

                    // Check if the interface is gone while we are waiting.
                    if (jniCheckInterface(mConfig.interfaceName) == 0) {
                        throw new IllegalStateException(mConfig.interfaceName + " is gone");
                    }

                    // Now INetworkManagementEventObserver is watching our back.
                    mInterfaceName = mConfig.interfaceName;
                    mCallback.override(mConfig.dnsServers, mConfig.searchDomains);
                    showNotification(mConfig, null, null);
                }
                Log.i(TAG, "Connected!");
            } catch (Exception e) {
                Log.i(TAG, "Abort because " + e.getMessage());
                exit();
            }
        }
    }
}
