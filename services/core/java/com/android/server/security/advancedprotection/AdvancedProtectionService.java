/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.advancedprotection;

import static android.provider.Settings.Secure.ADVANCED_PROTECTION_MODE;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.provider.Settings;
import android.security.advancedprotection.AdvancedProtectionFeature;
import android.security.advancedprotection.IAdvancedProtectionCallback;
import android.security.advancedprotection.IAdvancedProtectionService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.security.advancedprotection.features.AdvancedProtectionHook;
import com.android.server.security.advancedprotection.features.AdvancedProtectionProvider;
import com.android.server.security.advancedprotection.features.DisallowCellular2GAdvancedProtectionHook;
import com.android.server.security.advancedprotection.features.DisallowInstallUnknownSourcesAdvancedProtectionHook;
import com.android.server.security.advancedprotection.features.MemoryTaggingExtensionHook;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

/** @hide */
public class AdvancedProtectionService extends IAdvancedProtectionService.Stub  {
    private static final String TAG = "AdvancedProtectionService";
    private static final int MODE_CHANGED = 0;
    private static final int CALLBACK_ADDED = 1;

    private final Context mContext;
    private final Handler mHandler;
    private final AdvancedProtectionStore mStore;

    // Features living with the service - their code will be executed when state changes
    private final ArrayList<AdvancedProtectionHook> mHooks = new ArrayList<>();
    // External features - they will be called on state change
    private final ArrayMap<IBinder, IAdvancedProtectionCallback> mCallbacks = new ArrayMap<>();
    // For tracking only - not called on state change
    private final ArrayList<AdvancedProtectionProvider> mProviders = new ArrayList<>();

    private AdvancedProtectionService(@NonNull Context context) {
        super(PermissionEnforcer.fromContext(context));
        mContext = context;
        mHandler = new AdvancedProtectionHandler(FgThread.get().getLooper());
        mStore = new AdvancedProtectionStore(context);
    }

    private void initFeatures(boolean enabled) {
        if (android.security.Flags.aapmFeatureDisableInstallUnknownSources()) {
          try {
            mHooks.add(new DisallowInstallUnknownSourcesAdvancedProtectionHook(mContext, enabled));
          } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize DisallowInstallUnknownSources", e);
          }
        }
        if (android.security.Flags.aapmFeatureMemoryTaggingExtension()) {
          try {
            mHooks.add(new MemoryTaggingExtensionHook(mContext, enabled));
          } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize MemoryTaggingExtension", e);
          }
        }
        if (android.security.Flags.aapmFeatureDisableCellular2g()) {
          try {
            mHooks.add(new DisallowCellular2GAdvancedProtectionHook(mContext, enabled));
          } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize DisallowCellular2g", e);
          }
        }
    }

    // Only for tests
    @VisibleForTesting
    AdvancedProtectionService(@NonNull Context context, @NonNull AdvancedProtectionStore store,
            @NonNull Looper looper, @NonNull PermissionEnforcer permissionEnforcer,
            @Nullable AdvancedProtectionHook hook, @Nullable AdvancedProtectionProvider provider) {
        super(permissionEnforcer);
        mContext = context;
        mStore = store;
        mHandler = new AdvancedProtectionHandler(looper);
        if (hook != null) {
            mHooks.add(hook);
        }

        if (provider != null) {
            mProviders.add(provider);
        }
    }

    @Override
    @EnforcePermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public boolean isAdvancedProtectionEnabled() {
        isAdvancedProtectionEnabled_enforcePermission();
        final long identity = Binder.clearCallingIdentity();
        try {
            return isAdvancedProtectionEnabledInternal();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Without permission check
    private boolean isAdvancedProtectionEnabledInternal() {
        return mStore.retrieve();
    }

    @Override
    @EnforcePermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void registerAdvancedProtectionCallback(@NonNull IAdvancedProtectionCallback callback)
            throws RemoteException {
        registerAdvancedProtectionCallback_enforcePermission();
        IBinder b = callback.asBinder();
        b.linkToDeath(new DeathRecipient(b), 0);
        synchronized (mCallbacks) {
            mCallbacks.put(b, callback);
            sendCallbackAdded(isAdvancedProtectionEnabledInternal(), callback);
        }
    }

    @Override
    @EnforcePermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void unregisterAdvancedProtectionCallback(
            @NonNull IAdvancedProtectionCallback callback) {
        unregisterAdvancedProtectionCallback_enforcePermission();
        synchronized (mCallbacks) {
            mCallbacks.remove(callback.asBinder());
        }
    }

    @Override
    @EnforcePermission(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE)
    public void setAdvancedProtectionEnabled(boolean enabled) {
        setAdvancedProtectionEnabled_enforcePermission();
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mCallbacks) {
                if (enabled != isAdvancedProtectionEnabledInternal()) {
                    mStore.store(enabled);
                    sendModeChanged(enabled);
                    Slog.i(TAG, "Advanced protection is " + (enabled ? "enabled" : "disabled"));
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    @EnforcePermission(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE)
    public List<AdvancedProtectionFeature> getAdvancedProtectionFeatures() {
        getAdvancedProtectionFeatures_enforcePermission();
        List<AdvancedProtectionFeature> features = new ArrayList<>();
        for (int i = 0; i < mProviders.size(); i++) {
            features.addAll(mProviders.get(i).getFeatures());
        }

        for (int i = 0; i < mHooks.size(); i++) {
            AdvancedProtectionHook hook = mHooks.get(i);
            if (hook.isAvailable()) {
                features.add(hook.getFeature());
            }
        }

        return features;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, @NonNull String[] args, ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) {
        (new AdvancedProtectionShellCommand(this))
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    void sendModeChanged(boolean enabled) {
        Message.obtain(mHandler, MODE_CHANGED, /*enabled*/ enabled ? 1 : 0, /*unused */ -1)
                .sendToTarget();
    }

    void sendCallbackAdded(boolean enabled, IAdvancedProtectionCallback callback) {
        Message.obtain(mHandler, CALLBACK_ADDED, /*enabled*/ enabled ? 1 : 0, /*unused*/ -1,
                        /*callback*/ callback)
                .sendToTarget();
    }

    public static final class Lifecycle extends SystemService {
        private final AdvancedProtectionService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new AdvancedProtectionService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.ADVANCED_PROTECTION_SERVICE, mService);
        }

        @Override
        public void onBootPhase(@BootPhase int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                boolean enabled = mService.isAdvancedProtectionEnabledInternal();
                if (enabled) {
                    Slog.i(TAG, "Advanced protection is enabled");
                }
                mService.initFeatures(enabled);
            }
        }
    }

    @VisibleForTesting
    static class AdvancedProtectionStore {
        private final Context mContext;
        private static final int APM_ON = 1;
        private static final int APM_OFF = 0;
        private final UserManagerInternal mUserManager;

        AdvancedProtectionStore(@NonNull Context context) {
            mContext = context;
            mUserManager = LocalServices.getService(UserManagerInternal.class);
        }

        void store(boolean enabled) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    ADVANCED_PROTECTION_MODE, enabled ? APM_ON : APM_OFF,
                    mUserManager.getMainUserId());
        }

        boolean retrieve() {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    ADVANCED_PROTECTION_MODE, APM_OFF, mUserManager.getMainUserId()) == APM_ON;
        }
    }

    private class AdvancedProtectionHandler extends Handler {
        private AdvancedProtectionHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                //arg1 == enabled
                case MODE_CHANGED:
                    handleAllCallbacks(msg.arg1 == 1);
                    break;
                //arg1 == enabled
                //obj == callback
                case CALLBACK_ADDED:
                    handleSingleCallback(msg.arg1 == 1, (IAdvancedProtectionCallback) msg.obj);
                    break;
            }
        }

        private void handleAllCallbacks(boolean enabled) {
            ArrayList<IAdvancedProtectionCallback> deadObjects = new ArrayList<>();

            for (int i = 0; i < mHooks.size(); i++) {
                AdvancedProtectionHook feature = mHooks.get(i);
                try {
                    if (feature.isAvailable()) {
                        feature.onAdvancedProtectionChanged(enabled);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to call hook for feature "
                            + feature.getFeature().getId(), e);
                }
            }
            synchronized (mCallbacks) {
                for (int i = 0; i < mCallbacks.size(); i++) {
                    IAdvancedProtectionCallback callback = mCallbacks.valueAt(i);
                    try {
                        callback.onAdvancedProtectionChanged(enabled);
                    } catch (RemoteException e) {
                        deadObjects.add(callback);
                    }
                }

                for (int i = 0; i < deadObjects.size(); i++) {
                    mCallbacks.remove(deadObjects.get(i).asBinder());
                }
            }
        }

        private void handleSingleCallback(boolean enabled, IAdvancedProtectionCallback callback) {
            try {
                callback.onAdvancedProtectionChanged(enabled);
            } catch (RemoteException e) {
                mCallbacks.remove(callback.asBinder());
            }
        }
    }

    private final class DeathRecipient implements IBinder.DeathRecipient {
        private final IBinder mBinder;

        DeathRecipient(IBinder  binder) {
            mBinder = binder;
        }

        @Override
        public void binderDied() {
            synchronized (mCallbacks) {
                mCallbacks.remove(mBinder);
            }
        }
    }
}
