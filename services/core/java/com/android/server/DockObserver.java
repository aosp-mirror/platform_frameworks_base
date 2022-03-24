/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.ExtconUEventObserver.ExtconInfo;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DockObserver monitors for a docking station.
 */
final class DockObserver extends SystemService {
    private static final String TAG = "DockObserver";

    private static final int MSG_DOCK_STATE_CHANGED = 0;

    private final PowerManager mPowerManager;
    private final PowerManager.WakeLock mWakeLock;

    private final Object mLock = new Object();

    private boolean mSystemReady;

    private int mActualDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mReportedDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mPreviousDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private boolean mUpdatesStopped;

    private final boolean mAllowTheaterModeWakeFromDock;

    private final List<ExtconStateConfig> mExtconStateConfigs;

    static final class ExtconStateProvider {
        private final Map<String, String> mState;

        ExtconStateProvider(Map<String, String> state) {
            mState = state;
        }

        String getValue(String key) {
            return mState.get(key);
        }


        static ExtconStateProvider fromString(String stateString) {
            Map<String, String> states = new HashMap<>();
            String[] lines = stateString.split("\n");
            for (String line : lines) {
                String[] fields = line.split("=");
                if (fields.length == 2) {
                    states.put(fields[0], fields[1]);
                } else {
                    Slog.e(TAG, "Invalid line: " + line);
                }
            }
            return new ExtconStateProvider(states);
        }

        static ExtconStateProvider fromFile(String stateFilePath) {
            char[] buffer = new char[1024];
            try (FileReader file = new FileReader(stateFilePath)) {
                int len = file.read(buffer, 0, 1024);
                String stateString = (new String(buffer, 0, len)).trim();
                return ExtconStateProvider.fromString(stateString);
            } catch (FileNotFoundException e) {
                Slog.w(TAG, "No state file found at: " + stateFilePath);
                return new ExtconStateProvider(new HashMap<>());
            } catch (Exception e) {
                Slog.e(TAG, "" , e);
                return new ExtconStateProvider(new HashMap<>());
            }
        }
    }

    /**
     * Represents a mapping from extcon state to EXTRA_DOCK_STATE value. Each
     * instance corresponds to an entry in config_dockExtconStateMapping.
     */
    private static final class ExtconStateConfig {

        // The EXTRA_DOCK_STATE that will be used if the extcon key-value pairs match
        public final int extraStateValue;

        // A list of key-value pairs that must be present in the extcon state for a match
        // to be considered. An empty list is considered a matching wildcard.
        public final List<Pair<String, String>> keyValuePairs = new ArrayList<>();

        ExtconStateConfig(int extraStateValue) {
            this.extraStateValue = extraStateValue;
        }
    }

    private static List<ExtconStateConfig> loadExtconStateConfigs(Context context) {
        String[] rows = context.getResources().getStringArray(
            com.android.internal.R.array.config_dockExtconStateMapping);
        try {
            ArrayList<ExtconStateConfig> configs = new ArrayList<>();
            for (String row : rows) {
                String[] rowFields = row.split(",");
                ExtconStateConfig config = new ExtconStateConfig(Integer.parseInt(rowFields[0]));
                for (int i = 1; i < rowFields.length; i++) {
                    String[] keyValueFields = rowFields[i].split("=");
                    if (keyValueFields.length != 2) {
                        throw new IllegalArgumentException("Invalid key-value: " + rowFields[i]);
                    }
                    config.keyValuePairs.add(Pair.create(keyValueFields[0], keyValueFields[1]));
                }
                configs.add(config);
            }
            return configs;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            Slog.e(TAG, "Could not parse extcon state config", e);
            return new ArrayList<>();
        }
    }

    public DockObserver(Context context) {
        super(context);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mAllowTheaterModeWakeFromDock = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowTheaterModeWakeFromDock);

        mExtconStateConfigs = loadExtconStateConfigs(context);

        List<ExtconInfo> infos = ExtconInfo.getExtconInfoForTypes(new String[] {
                ExtconInfo.EXTCON_DOCK
        });

        if (!infos.isEmpty()) {
            ExtconInfo info = infos.get(0);
            Slog.i(TAG, "Found extcon info devPath: " + info.getDevicePath()
                        + ", statePath: " + info.getStatePath());

            // set initial status
            setDockStateFromProviderLocked(ExtconStateProvider.fromFile(info.getStatePath()));
            mPreviousDockState = mActualDockState;

            mExtconUEventObserver.startObserving(info);
        } else {
            Slog.i(TAG, "No extcon dock device found in this kernel.");
        }
    }

    @Override
    public void onStart() {
        publishBinderService(TAG, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            synchronized (mLock) {
                mSystemReady = true;

                // don't bother broadcasting undocked here
                if (mReportedDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                    updateLocked();
                }
            }
        }
    }

    private void setActualDockStateLocked(int newState) {
        mActualDockState = newState;
        if (!mUpdatesStopped) {
            setDockStateLocked(newState);
        }
    }

    private void setDockStateLocked(int newState) {
        if (newState != mReportedDockState) {
            mReportedDockState = newState;
            if (mSystemReady) {
                // Wake up immediately when docked or undocked except in theater mode.
                if (mAllowTheaterModeWakeFromDock
                        || Settings.Global.getInt(getContext().getContentResolver(),
                            Settings.Global.THEATER_MODE_ON, 0) == 0) {
                    mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                            "android.server:DOCK");
                }
                updateLocked();
            }
        }
    }

    private void updateLocked() {
        mWakeLock.acquire();
        mHandler.sendEmptyMessage(MSG_DOCK_STATE_CHANGED);
    }

    private void handleDockStateChange() {
        synchronized (mLock) {
            Slog.i(TAG, "Dock state changed from " + mPreviousDockState + " to "
                    + mReportedDockState);
            final int previousDockState = mPreviousDockState;
            mPreviousDockState = mReportedDockState;

            // Skip the dock intent if not yet provisioned.
            final ContentResolver cr = getContext().getContentResolver();
            if (Settings.Global.getInt(cr,
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
                Slog.i(TAG, "Device not provisioned, skipping dock broadcast");
                return;
            }

            // Pack up the values and broadcast them to everyone
            Intent intent = new Intent(Intent.ACTION_DOCK_EVENT);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(Intent.EXTRA_DOCK_STATE, mReportedDockState);

            boolean dockSoundsEnabled = Settings.Global.getInt(cr,
                    Settings.Global.DOCK_SOUNDS_ENABLED, 1) == 1;
            boolean dockSoundsEnabledWhenAccessibility = Settings.Global.getInt(cr,
                    Settings.Global.DOCK_SOUNDS_ENABLED_WHEN_ACCESSIBILITY, 1) == 1;
            boolean accessibilityEnabled = Settings.Secure.getInt(cr,
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;

            // Play a sound to provide feedback to confirm dock connection.
            // Particularly useful for flaky contact pins...
            if ((dockSoundsEnabled) ||
                   (accessibilityEnabled && dockSoundsEnabledWhenAccessibility)) {
                String whichSound = null;
                if (mReportedDockState == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                    if ((previousDockState == Intent.EXTRA_DOCK_STATE_DESK) ||
                        (previousDockState == Intent.EXTRA_DOCK_STATE_LE_DESK) ||
                        (previousDockState == Intent.EXTRA_DOCK_STATE_HE_DESK)) {
                        whichSound = Settings.Global.DESK_UNDOCK_SOUND;
                    } else if (previousDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                        whichSound = Settings.Global.CAR_UNDOCK_SOUND;
                    }
                } else {
                    if ((mReportedDockState == Intent.EXTRA_DOCK_STATE_DESK) ||
                        (mReportedDockState == Intent.EXTRA_DOCK_STATE_LE_DESK) ||
                        (mReportedDockState == Intent.EXTRA_DOCK_STATE_HE_DESK)) {
                        whichSound = Settings.Global.DESK_DOCK_SOUND;
                    } else if (mReportedDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                        whichSound = Settings.Global.CAR_DOCK_SOUND;
                    }
                }

                if (whichSound != null) {
                    final String soundPath = Settings.Global.getString(cr, whichSound);
                    if (soundPath != null) {
                        final Uri soundUri = Uri.parse("file://" + soundPath);
                        if (soundUri != null) {
                            final Ringtone sfx = RingtoneManager.getRingtone(
                                    getContext(), soundUri);
                            if (sfx != null) {
                                sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                                sfx.play();
                            }
                        }
                    }
                }
            }

            // Send the dock event intent.
            // There are many components in the system watching for this so as to
            // adjust audio routing, screen orientation, etc.
            getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    private final Handler mHandler = new Handler(true /*async*/) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DOCK_STATE_CHANGED:
                    handleDockStateChange();
                    mWakeLock.release();
                    break;
            }
        }
    };

    private int getDockedStateExtraValue(ExtconStateProvider state) {
        for (ExtconStateConfig config : mExtconStateConfigs) {
            boolean match = true;
            for (Pair<String, String> keyValue : config.keyValuePairs) {
                String stateValue = state.getValue(keyValue.first);
                match = match && keyValue.second.equals(stateValue);
                if (!match) {
                    break;
                }
            }

            if (match) {
                return config.extraStateValue;
            }
        }

        return Intent.EXTRA_DOCK_STATE_DESK;
    }

    @VisibleForTesting
    void setDockStateFromProviderForTesting(ExtconStateProvider provider) {
        synchronized (mLock) {
            setDockStateFromProviderLocked(provider);
        }
    }

    private void setDockStateFromProviderLocked(ExtconStateProvider provider) {
        int state = Intent.EXTRA_DOCK_STATE_UNDOCKED;
        if ("1".equals(provider.getValue("DOCK"))) {
            state = getDockedStateExtraValue(provider);
        }
        setActualDockStateLocked(state);
    }

    private final ExtconUEventObserver mExtconUEventObserver = new ExtconUEventObserver() {
        @Override
        public void onUEvent(ExtconInfo extconInfo, UEventObserver.UEvent event) {
            synchronized (mLock) {
                String stateString = event.get("STATE");
                if (stateString != null) {
                    setDockStateFromProviderLocked(ExtconStateProvider.fromString(stateString));
                } else {
                    Slog.e(TAG, "Extcon event missing STATE: " + event);
                }
            }
        }
    };

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (args == null || args.length == 0 || "-a".equals(args[0])) {
                        pw.println("Current Dock Observer Service state:");
                        if (mUpdatesStopped) {
                            pw.println("  (UPDATES STOPPED -- use 'reset' to restart)");
                        }
                        pw.println("  reported state: " + mReportedDockState);
                        pw.println("  previous state: " + mPreviousDockState);
                        pw.println("  actual state: " + mActualDockState);
                    } else if (args.length == 3 && "set".equals(args[0])) {
                        String key = args[1];
                        String value = args[2];
                        try {
                            if ("state".equals(key)) {
                                mUpdatesStopped = true;
                                setDockStateLocked(Integer.parseInt(value));
                            } else {
                                pw.println("Unknown set option: " + key);
                            }
                        } catch (NumberFormatException ex) {
                            pw.println("Bad value: " + value);
                        }
                    } else if (args.length == 1 && "reset".equals(args[0])) {
                        mUpdatesStopped = false;
                        setDockStateLocked(mActualDockState);
                    } else {
                        pw.println("Dump current dock state, or:");
                        pw.println("  set state <value>");
                        pw.println("  reset");
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
