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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.INetworkManagementEventObserver;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.server.ConnectivityService.VpnCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charsets;
import java.util.Arrays;

/**
 * @hide
 */
public class Vpn extends INetworkManagementEventObserver.Stub {

    private final static String TAG = "Vpn";

    private final static String BIND_VPN_SERVICE =
            android.Manifest.permission.BIND_VPN_SERVICE;

    private final Context mContext;
    private final VpnCallback mCallback;

    private String mPackage = VpnConfig.LEGACY_VPN;
    private String mInterface;
    private Connection mConnection;
    private LegacyVpnRunner mLegacyVpnRunner;

    public Vpn(Context context, VpnCallback callback) {
        mContext = context;
        mCallback = callback;
    }

    /**
     * Prepare for a VPN application. This method is designed to solve
     * race conditions. It first compares the current prepared package
     * with {@code oldPackage}. If they are the same, the prepared
     * package is revoked and replaced with {@code newPackage}. If
     * {@code oldPackage} is {@code null}, the comparison is omitted.
     * If {@code newPackage} is the same package or {@code null}, the
     * revocation is omitted. This method returns {@code true} if the
     * operation is succeeded.
     *
     * Legacy VPN is handled specially since it is not a real package.
     * It uses {@link VpnConfig#LEGACY_VPN} as its package name, and
     * it can be revoked by itself.
     *
     * @param oldPackage The package name of the old VPN application.
     * @param newPackage The package name of the new VPN application.
     * @return true if the operation is succeeded.
     */
    public synchronized boolean prepare(String oldPackage, String newPackage) {
        // Return false if the package does not match.
        if (oldPackage != null && !oldPackage.equals(mPackage)) {
            return false;
        }

        // Return true if we do not need to revoke.
        if (newPackage == null ||
                (newPackage.equals(mPackage) && !newPackage.equals(VpnConfig.LEGACY_VPN))) {
            return true;
        }

        // Only system user can revoke a package.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }

        // Reset the interface and hide the notification.
        if (mInterface != null) {
            jniReset(mInterface);
            mCallback.restore();
            hideNotification();
            mInterface = null;
        }

        // Revoke the connection or stop LegacyVpnRunner.
        if (mConnection != null) {
            try {
                mConnection.mService.transact(IBinder.LAST_CALL_TRANSACTION,
                        Parcel.obtain(), null, IBinder.FLAG_ONEWAY);
            } catch (Exception e) {
                // ignore
            }
            mContext.unbindService(mConnection);
            mConnection = null;
        } else if (mLegacyVpnRunner != null) {
            mLegacyVpnRunner.exit();
            mLegacyVpnRunner = null;
        }

        Log.i(TAG, "Switched from " + mPackage + " to " + newPackage);
        mPackage = newPackage;
        return true;
    }

    /**
     * Protect a socket from routing changes by binding it to the given
     * interface. The socket is NOT closed by this method.
     *
     * @param socket The socket to be bound.
     * @param name The name of the interface.
     */
    public void protect(ParcelFileDescriptor socket, String interfaze) throws Exception {
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo app = pm.getApplicationInfo(mPackage, 0);
        if (Binder.getCallingUid() != app.uid) {
            throw new SecurityException("Unauthorized Caller");
        }
        jniProtect(socket.getFd(), interfaze);
    }

    /**
     * Establish a VPN network and return the file descriptor of the VPN
     * interface. This methods returns {@code null} if the application is
     * revoked or not prepared.
     *
     * @param config The parameters to configure the network.
     * @return The file descriptor of the VPN interface.
     */
    public synchronized ParcelFileDescriptor establish(VpnConfig config) {
        // Check if the caller is already prepared.
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(mPackage, 0);
        } catch (Exception e) {
            return null;
        }
        if (Binder.getCallingUid() != app.uid) {
            return null;
        }

        // Check if the service is properly declared.
        Intent intent = new Intent(VpnConfig.SERVICE_INTERFACE);
        intent.setClassName(mPackage, config.user);
        ResolveInfo info = pm.resolveService(intent, 0);
        if (info == null) {
            throw new SecurityException("Cannot find " + config.user);
        }
        if (!BIND_VPN_SERVICE.equals(info.serviceInfo.permission)) {
            throw new SecurityException(config.user + " does not require " + BIND_VPN_SERVICE);
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
            Canvas c = new Canvas(bitmap);
            icon.draw(c);
            c.setBitmap(null);
        }

        // Configure the interface. Abort if any of these steps fails.
        ParcelFileDescriptor tun = ParcelFileDescriptor.adoptFd(jniCreate(config.mtu));
        try {
            String interfaze = jniGetName(tun.getFd());
            if (jniSetAddresses(interfaze, config.addresses) < 1) {
                throw new IllegalArgumentException("At least one address must be specified");
            }
            if (config.routes != null) {
                jniSetRoutes(interfaze, config.routes);
            }
            Connection connection = new Connection();
            if (!mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw new IllegalStateException("Cannot bind " + config.user);
            }
            if (mConnection != null) {
                mContext.unbindService(mConnection);
            }
            if (mInterface != null && !mInterface.equals(interfaze)) {
                jniReset(mInterface);
            }
            mConnection = connection;
            mInterface = interfaze;
        } catch (RuntimeException e) {
            try {
                tun.close();
            } catch (Exception ex) {
                // ignore
            }
            throw e;
        }
        Log.i(TAG, "Established by " + config.user + " on " + mInterface);

        // Fill more values.
        config.user = mPackage;
        config.interfaze = mInterface;

        // Override DNS servers and show the notification.
        long identity = Binder.clearCallingIdentity();
        mCallback.override(config.dnsServers, config.searchDomains);
        showNotification(config, label, bitmap);
        Binder.restoreCallingIdentity(identity);
        return tun;
    }

    // INetworkManagementEventObserver.Stub
    @Override
    public void interfaceAdded(String interfaze) {
    }

    // INetworkManagementEventObserver.Stub
    @Override
    public synchronized void interfaceStatusChanged(String interfaze, boolean up) {
        if (!up && mLegacyVpnRunner != null) {
            mLegacyVpnRunner.check(interfaze);
        }
    }

    // INetworkManagementEventObserver.Stub
    @Override
    public void interfaceLinkStateChanged(String interfaze, boolean up) {
    }

    // INetworkManagementEventObserver.Stub
    @Override
    public synchronized void interfaceRemoved(String interfaze) {
        if (interfaze.equals(mInterface) && jniCheck(interfaze) == 0) {
            long identity = Binder.clearCallingIdentity();
            mCallback.restore();
            hideNotification();
            Binder.restoreCallingIdentity(identity);
            mInterface = null;
            if (mConnection != null) {
                mContext.unbindService(mConnection);
                mConnection = null;
            } else if (mLegacyVpnRunner != null) {
                mLegacyVpnRunner.exit();
                mLegacyVpnRunner = null;
            }
        }
    }

    // INetworkManagementEventObserver.Stub
    @Override
    public void limitReached(String limit, String interfaze) {
    }

    private class Connection implements ServiceConnection {
        private IBinder mService;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    }

    private void showNotification(VpnConfig config, String label, Bitmap icon) {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            String title = (label == null) ? mContext.getString(R.string.vpn_title) :
                    mContext.getString(R.string.vpn_title_long, label);
            String text = (config.session == null) ? mContext.getString(R.string.vpn_text) :
                    mContext.getString(R.string.vpn_text_long, config.session);
            config.startTime = SystemClock.elapsedRealtime();

            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.vpn_connected)
                    .setLargeIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(VpnConfig.getIntentForStatusPanel(mContext, config))
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setOngoing(true)
                    .getNotification();
            nm.notify(R.drawable.vpn_connected, notification);
        }
    }

    private void hideNotification() {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            nm.cancel(R.drawable.vpn_connected);
        }
    }

    private native int jniCreate(int mtu);
    private native String jniGetName(int tun);
    private native int jniSetAddresses(String interfaze, String addresses);
    private native int jniSetRoutes(String interfaze, String routes);
    private native void jniReset(String interfaze);
    private native int jniCheck(String interfaze);
    private native void jniProtect(int socket, String interfaze);

    /**
     * Start legacy VPN. This method stops the daemons and restart them
     * if arguments are not null. Heavy things are offloaded to another
     * thread, so callers will not be blocked for a long time.
     *
     * @param config The parameters to configure the network.
     * @param raoocn The arguments to be passed to racoon.
     * @param mtpd The arguments to be passed to mtpd.
     */
    public synchronized void startLegacyVpn(VpnConfig config, String[] racoon, String[] mtpd) {
        // Prepare for the new request. This also checks the caller.
        prepare(null, VpnConfig.LEGACY_VPN);

        // Start a new LegacyVpnRunner and we are done!
        mLegacyVpnRunner = new LegacyVpnRunner(config, racoon, mtpd);
        mLegacyVpnRunner.start();
    }

    /**
     * Return the information of the current ongoing legacy VPN.
     */
    public synchronized LegacyVpnInfo getLegacyVpnInfo() {
        // Only system user can call this method.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }
        return (mLegacyVpnRunner == null) ? null : mLegacyVpnRunner.getInfo();
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

        private final VpnConfig mConfig;
        private final String[] mDaemons;
        private final String[][] mArguments;
        private final LocalSocket[] mSockets;
        private final String mOuterInterface;
        private final LegacyVpnInfo mInfo;

        private long mTimer = -1;

        public LegacyVpnRunner(VpnConfig config, String[] racoon, String[] mtpd) {
            super(TAG);
            mConfig = config;
            mDaemons = new String[] {"racoon", "mtpd"};
            mArguments = new String[][] {racoon, mtpd};
            mSockets = new LocalSocket[mDaemons.length];
            mInfo = new LegacyVpnInfo();

            // This is the interface which VPN is running on.
            mOuterInterface = mConfig.interfaze;

            // Legacy VPN is not a real package, so we use it to carry the key.
            mInfo.key = mConfig.user;
            mConfig.user = VpnConfig.LEGACY_VPN;
        }

        public void check(String interfaze) {
            if (interfaze.equals(mOuterInterface)) {
                Log.i(TAG, "Legacy VPN is going down with " + interfaze);
                exit();
            }
        }

        public void exit() {
            // We assume that everything is reset after stopping the daemons.
            interrupt();
            for (LocalSocket socket : mSockets) {
                try {
                    socket.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        public LegacyVpnInfo getInfo() {
            // Update the info when VPN is disconnected.
            if (mInfo.state == LegacyVpnInfo.STATE_CONNECTED && mInterface == null) {
                mInfo.state = LegacyVpnInfo.STATE_DISCONNECTED;
                mInfo.intent = null;
            }
            return mInfo;
        }

        @Override
        public void run() {
            // Wait for the previous thread since it has been interrupted.
            Log.v(TAG, "Waiting");
            synchronized (TAG) {
                Log.v(TAG, "Executing");
                execute();
            }
        }

        private void checkpoint(boolean yield) throws InterruptedException {
            long now = SystemClock.elapsedRealtime();
            if (mTimer == -1) {
                mTimer = now;
                Thread.sleep(1);
            } else if (now - mTimer <= 60000) {
                Thread.sleep(yield ? 200 : 1);
            } else {
                mInfo.state = LegacyVpnInfo.STATE_TIMEOUT;
                throw new IllegalStateException("Time is up");
            }
        }

        private void execute() {
            // Catch all exceptions so we can clean up few things.
            try {
                // Initialize the timer.
                checkpoint(false);
                mInfo.state = LegacyVpnInfo.STATE_INITIALIZING;

                // Wait for the daemons to stop.
                for (String daemon : mDaemons) {
                    String key = "init.svc." + daemon;
                    while (!"stopped".equals(SystemProperties.get(key, "stopped"))) {
                        checkpoint(true);
                    }
                }

                // Clear the previous state.
                File state = new File("/data/misc/vpn/state");
                state.delete();
                if (state.exists()) {
                    throw new IllegalStateException("Cannot delete the state");
                }

                // Check if we need to restart any of the daemons.
                boolean restart = false;
                for (String[] arguments : mArguments) {
                    restart = restart || (arguments != null);
                }
                if (!restart) {
                    mInfo.state = LegacyVpnInfo.STATE_DISCONNECTED;
                    return;
                }
                mInfo.state = LegacyVpnInfo.STATE_CONNECTING;

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
                    mSockets[i] = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            daemon, LocalSocketAddress.Namespace.RESERVED);

                    // Wait for the socket to connect.
                    while (true) {
                        try {
                            mSockets[i].connect(address);
                            break;
                        } catch (Exception e) {
                            // ignore
                        }
                        checkpoint(true);
                    }
                    mSockets[i].setSoTimeout(500);

                    // Send over the arguments.
                    OutputStream out = mSockets[i].getOutputStream();
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(Charsets.UTF_8);
                        if (bytes.length >= 0xFFFF) {
                            throw new IllegalArgumentException("Argument is too large");
                        }
                        out.write(bytes.length >> 8);
                        out.write(bytes.length);
                        out.write(bytes);
                        checkpoint(false);
                    }
                    out.write(0xFF);
                    out.write(0xFF);
                    out.flush();

                    // Wait for End-of-File.
                    InputStream in = mSockets[i].getInputStream();
                    while (true) {
                        try {
                            if (in.read() == -1) {
                                break;
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                        checkpoint(true);
                    }
                }

                // Wait for the daemons to create the new state.
                while (!state.exists()) {
                    // Check if a running daemon is dead.
                    for (int i = 0; i < mDaemons.length; ++i) {
                        String daemon = mDaemons[i];
                        if (mArguments[i] != null && !"running".equals(
                                SystemProperties.get("init.svc." + daemon))) {
                            throw new IllegalStateException(daemon + " is dead");
                        }
                    }
                    checkpoint(true);
                }

                // Now we are connected. Read and parse the new state.
                byte[] buffer = new byte[(int) state.length()];
                if (new FileInputStream(state).read(buffer) != buffer.length) {
                    throw new IllegalStateException("Cannot read the state");
                }
                String[] parameters = new String(buffer, Charsets.UTF_8).split("\n", -1);
                if (parameters.length != 6) {
                    throw new IllegalStateException("Cannot parse the state");
                }

                // Set the interface and the addresses in the config.
                mConfig.interfaze = parameters[0].trim();
                mConfig.addresses = parameters[1].trim();

                // Set the routes if they are not set in the config.
                if (mConfig.routes == null || mConfig.routes.isEmpty()) {
                    mConfig.routes = parameters[2].trim();
                }

                // Set the DNS servers if they are not set in the config.
                if (mConfig.dnsServers == null || mConfig.dnsServers.size() == 0) {
                    String dnsServers = parameters[3].trim();
                    if (!dnsServers.isEmpty()) {
                        mConfig.dnsServers = Arrays.asList(dnsServers.split(" "));
                    }
                }

                // Set the search domains if they are not set in the config.
                if (mConfig.searchDomains == null || mConfig.searchDomains.size() == 0) {
                    String searchDomains = parameters[4].trim();
                    if (!searchDomains.isEmpty()) {
                        mConfig.searchDomains = Arrays.asList(searchDomains.split(" "));
                    }
                }

                // Set the routes.
                jniSetRoutes(mConfig.interfaze, mConfig.routes);

                // Here is the last step and it must be done synchronously.
                synchronized (Vpn.this) {
                    // Check if the thread is interrupted while we are waiting.
                    checkpoint(false);

                    // Check if the interface is gone while we are waiting.
                    if (jniCheck(mConfig.interfaze) == 0) {
                        throw new IllegalStateException(mConfig.interfaze + " is gone");
                    }

                    // Now INetworkManagementEventObserver is watching our back.
                    mInterface = mConfig.interfaze;
                    mCallback.override(mConfig.dnsServers, mConfig.searchDomains);
                    showNotification(mConfig, null, null);

                    Log.i(TAG, "Connected!");
                    mInfo.state = LegacyVpnInfo.STATE_CONNECTED;
                    mInfo.intent = VpnConfig.getIntentForStatusPanel(mContext, null);
                }
            } catch (Exception e) {
                Log.i(TAG, "Aborting", e);
                exit();
            } finally {
                // Kill the daemons if they fail to stop.
                if (mInfo.state == LegacyVpnInfo.STATE_INITIALIZING) {
                    for (String daemon : mDaemons) {
                        SystemProperties.set("ctl.stop", daemon);
                    }
                }

                // Do not leave an unstable state.
                if (mInfo.state == LegacyVpnInfo.STATE_INITIALIZING ||
                        mInfo.state == LegacyVpnInfo.STATE_CONNECTING) {
                    mInfo.state = LegacyVpnInfo.STATE_FAILED;
                }
            }
        }
    }
}
