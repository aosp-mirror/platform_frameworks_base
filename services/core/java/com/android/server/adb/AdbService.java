/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.adb;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.debug.AdbManagerInternal;
import android.debug.IAdbManager;
import android.debug.IAdbTransport;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The Android Debug Bridge (ADB) service. This controls the availability of ADB and authorization
 * of devices allowed to connect to ADB.
 */
public class AdbService extends IAdbManager.Stub {
    /**
     * Manages the service lifecycle for {@code AdbService} in {@code SystemServer}.
     */
    public static class Lifecycle extends SystemService {
        private AdbService mAdbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mAdbService = new AdbService(getContext());
            publishBinderService(Context.ADB_SERVICE, mAdbService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mAdbService.systemReady();
            }
        }
    }

    private class AdbManagerInternalImpl extends AdbManagerInternal {
        @Override
        public void registerTransport(IAdbTransport transport) {
            mTransports.put(transport.asBinder(), transport);
        }

        @Override
        public void unregisterTransport(IAdbTransport transport) {
            mTransports.remove(transport.asBinder());
        }

        @Override
        public boolean isAdbEnabled() {
            return mAdbEnabled;
        }
    }

    private final class AdbHandler extends Handler {
        AdbHandler(Looper looper) {
            super(looper);
            try {
                /*
                 * Use the normal bootmode persistent prop to maintain state of adb across
                 * all boot modes.
                 */
                mAdbEnabled = containsFunction(
                        SystemProperties.get(USB_PERSISTENT_CONFIG_PROPERTY, ""),
                        UsbManager.USB_FUNCTION_ADB);

                // register observer to listen for settings changes
                mContentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                        false, new AdbSettingsObserver());
            } catch (Exception e) {
                Slog.e(TAG, "Error initializing AdbHandler", e);
            }
        }

        private boolean containsFunction(String functions, String function) {
            int index = functions.indexOf(function);
            if (index < 0) return false;
            if (index > 0 && functions.charAt(index - 1) != ',') return false;
            int charAfter = index + function.length();
            if (charAfter < functions.length() && functions.charAt(charAfter) != ',') return false;
            return true;
        }

        public void sendMessage(int what, boolean arg) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.arg1 = (arg ? 1 : 0);
            sendMessage(m);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ENABLE_ADB:
                    setAdbEnabled(msg.arg1 == 1);
                    break;
            }
        }
    }

    private class AdbSettingsObserver extends ContentObserver {
        AdbSettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            boolean enable = (Settings.Global.getInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, 0) > 0);
            mHandler.sendMessage(MSG_ENABLE_ADB, enable);
        }
    }

    private static final String TAG = "AdbService";
    private static final boolean DEBUG = false;

    private static final int MSG_ENABLE_ADB = 1;

    /**
     * The persistent property which stores whether adb is enabled or not.
     * May also contain vendor-specific default functions for testing purposes.
     */
    private static final String USB_PERSISTENT_CONFIG_PROPERTY = "persist.sys.usb.config";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final AdbService.AdbHandler mHandler;
    private final ArrayMap<IBinder, IAdbTransport> mTransports = new ArrayMap<>();

    private boolean mAdbEnabled;

    private AdbService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        mHandler = new AdbHandler(FgThread.get().getLooper());

        LocalServices.addService(AdbManagerInternal.class, new AdbManagerInternalImpl());
    }

    /**
     * Called in response to {@code SystemService.PHASE_ACTIVITY_MANAGER_READY} from {@code
     * SystemServer}.
     */
    public void systemReady() {
        if (DEBUG) Slog.d(TAG, "systemReady");

        // make sure the ADB_ENABLED setting value matches the current state
        try {
            Settings.Global.putInt(mContentResolver,
                    Settings.Global.ADB_ENABLED, mAdbEnabled ? 1 : 0);
        } catch (SecurityException e) {
            // If UserManager.DISALLOW_DEBUGGING_FEATURES is on, that this setting can't be changed.
            Slog.d(TAG, "ADB_ENABLED is restricted.");
        }
    }

    private void setAdbEnabled(boolean enable) {
        if (DEBUG) Slog.d(TAG, "setAdbEnabled(" + enable + "), mAdbEnabled=" + mAdbEnabled);

        if (enable == mAdbEnabled) {
            return;
        }
        mAdbEnabled = enable;

        for (IAdbTransport transport : mTransports.values()) {
            try {
                transport.onAdbEnabled(enable);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to send onAdbEnabled to transport " + transport.toString());
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        final long ident = Binder.clearCallingIdentity();
        try {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("ADB Manager State:");
                pw.increaseIndent();
                pw.print("ADB enabled: ");
                pw.println(mAdbEnabled);
                pw.print("Number of registered transports: ");
                pw.println(mTransports.size());
            } else {
                pw.println("Dump current ADB state");
                pw.println("  No commands available");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
