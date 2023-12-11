/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.broadcast.BroadcastDispatcher;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of a TileService.
 * <p>
 * Will keep track of all calls on the IQSTileService interface and will relay those calls to the
 * TileService as soon as it is bound.  It will only bind to the service when it is allowed to
 * ({@link #setBindService(boolean)}) and when the service is available.
 */
public class TileLifecycleManager extends BroadcastReceiver implements
        IQSTileService, ServiceConnection, IBinder.DeathRecipient {
    public static final boolean DEBUG = false;

    private static final String TAG = "TileLifecycleManager";

    private static final int META_DATA_QUERY_FLAGS =
            PackageManager.GET_META_DATA
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_DIRECT_BOOT_AWARE;

    private static final int MSG_ON_ADDED = 0;
    private static final int MSG_ON_REMOVED = 1;
    private static final int MSG_ON_CLICK = 2;
    private static final int MSG_ON_UNLOCK_COMPLETE = 3;

    // Bind retry control.
    private static final int MAX_BIND_RETRIES = 5;
    private static final int DEFAULT_BIND_RETRY_DELAY = 1000;

    // Shared prefs that hold tile lifecycle info.
    private static final String TILES = "tiles_prefs";

    private final Context mContext;
    private final Handler mHandler;
    private final Intent mIntent;
    private final UserHandle mUser;
    private final IBinder mToken = new Binder();
    private final PackageManagerAdapter mPackageManagerAdapter;
    private final BroadcastDispatcher mBroadcastDispatcher;

    private Set<Integer> mQueuedMessages = new ArraySet<>();
    private QSTileServiceWrapper mWrapper;
    private boolean mListening;
    private IBinder mClickBinder;

    private int mBindTryCount;
    private int mBindRetryDelay = DEFAULT_BIND_RETRY_DELAY;
    private boolean mBound;
    private AtomicBoolean mPackageReceiverRegistered = new AtomicBoolean(false);
    private AtomicBoolean mUserReceiverRegistered = new AtomicBoolean(false);
    private boolean mUnbindImmediate;
    private TileChangeListener mChangeListener;
    // Return value from bindServiceAsUser, determines whether safe to call unbind.
    private boolean mIsBound;

    public TileLifecycleManager(Handler handler, Context context, IQSService service, Tile tile,
            Intent intent, UserHandle user, BroadcastDispatcher broadcastDispatcher) {
        this(handler, context, service, tile, intent, user, new PackageManagerAdapter(context),
                broadcastDispatcher);
    }

    @VisibleForTesting
    TileLifecycleManager(Handler handler, Context context, IQSService service, Tile tile,
            Intent intent, UserHandle user, PackageManagerAdapter packageManagerAdapter,
            BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mHandler = handler;
        mIntent = intent;
        mIntent.putExtra(TileService.EXTRA_SERVICE, service.asBinder());
        mIntent.putExtra(TileService.EXTRA_TOKEN, mToken);
        mUser = user;
        mPackageManagerAdapter = packageManagerAdapter;
        mBroadcastDispatcher = broadcastDispatcher;
        if (DEBUG) Log.d(TAG, "Creating " + mIntent + " " + mUser);
    }

    public ComponentName getComponent() {
        return mIntent.getComponent();
    }

    public boolean hasPendingClick() {
        synchronized (mQueuedMessages) {
            return mQueuedMessages.contains(MSG_ON_CLICK);
        }
    }

    public void setBindRetryDelay(int delayMs) {
        mBindRetryDelay = delayMs;
    }

    public boolean isActiveTile() {
        try {
            ServiceInfo info = mPackageManagerAdapter.getServiceInfo(mIntent.getComponent(),
                    META_DATA_QUERY_FLAGS);
            return info.metaData != null
                    && info.metaData.getBoolean(TileService.META_DATA_ACTIVE_TILE, false);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Determines whether the associated TileService is a Boolean Tile.
     *
     * @return true if {@link TileService#META_DATA_TOGGLEABLE_TILE} is set to {@code true} for this
     *         tile
     * @see TileService#META_DATA_TOGGLEABLE_TILE
     */
    public boolean isToggleableTile() {
        try {
            ServiceInfo info = mPackageManagerAdapter.getServiceInfo(mIntent.getComponent(),
                    META_DATA_QUERY_FLAGS);
            return info.metaData != null
                    && info.metaData.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE, false);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Binds just long enough to send any queued messages, then unbinds.
     */
    public void flushMessagesAndUnbind() {
        mUnbindImmediate = true;
        setBindService(true);
    }

    public void setBindService(boolean bind) {
        if (mBound && mUnbindImmediate) {
            // If we are already bound and expecting to unbind, this means we should stay bound
            // because something else wants to hold the connection open.
            mUnbindImmediate = false;
            return;
        }
        mBound = bind;
        if (bind) {
            if (mBindTryCount == MAX_BIND_RETRIES) {
                // Too many failures, give up on this tile until an update.
                startPackageListening();
                return;
            }
            if (!checkComponentState()) {
                return;
            }
            if (DEBUG) Log.d(TAG, "Binding service " + mIntent + " " + mUser);
            mBindTryCount++;
            try {
                mIsBound = mContext.bindServiceAsUser(mIntent, this,
                        Context.BIND_AUTO_CREATE
                                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                                | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                                | Context.BIND_WAIVE_PRIORITY,
                        mUser);
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to bind to service", e);
                mIsBound = false;
            }
        } else {
            if (DEBUG) Log.d(TAG, "Unbinding service " + mIntent + " " + mUser);
            // Give it another chance next time it needs to be bound, out of kindness.
            mBindTryCount = 0;
            freeWrapper();
            if (mIsBound) {
                try {
                    mContext.unbindService(this);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to unbind service "
                            + mIntent.getComponent().flattenToShortString(), e);
                }
                mIsBound = false;
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) Log.d(TAG, "onServiceConnected " + name);
        // Got a connection, set the binding count to 0.
        mBindTryCount = 0;
        final QSTileServiceWrapper wrapper = new QSTileServiceWrapper(Stub.asInterface(service));
        try {
            service.linkToDeath(this, 0);
        } catch (RemoteException e) {
        }
        mWrapper = wrapper;
        handlePendingMessages();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        setBindService(false);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) Log.d(TAG, "onServiceDisconnected " + name);
        handleDeath();
    }

    private void handlePendingMessages() {
        // This ordering is laid out manually to make sure we preserve the TileService
        // lifecycle.
        ArraySet<Integer> queue;
        synchronized (mQueuedMessages) {
            queue = new ArraySet<>(mQueuedMessages);
            mQueuedMessages.clear();
        }
        if (queue.contains(MSG_ON_ADDED)) {
            if (DEBUG) Log.d(TAG, "Handling pending onAdded");
            onTileAdded();
        }
        if (mListening) {
            if (DEBUG) Log.d(TAG, "Handling pending onStartListening");
            onStartListening();
        }
        if (queue.contains(MSG_ON_CLICK)) {
            if (DEBUG) Log.d(TAG, "Handling pending onClick");
            if (!mListening) {
                Log.w(TAG, "Managed to get click on non-listening state...");
                // Skipping click since lost click privileges.
            } else {
                onClick(mClickBinder);
            }
        }
        if (queue.contains(MSG_ON_UNLOCK_COMPLETE)) {
            if (DEBUG) Log.d(TAG, "Handling pending onUnlockComplete");
            if (!mListening) {
                Log.w(TAG, "Managed to get unlock on non-listening state...");
                // Skipping unlock since lost click privileges.
            } else {
                onUnlockComplete();
            }
        }
        if (queue.contains(MSG_ON_REMOVED)) {
            if (DEBUG) Log.d(TAG, "Handling pending onRemoved");
            if (mListening) {
                Log.w(TAG, "Managed to get remove in listening state...");
                onStopListening();
            }
            onTileRemoved();
        }
        if (mUnbindImmediate) {
            mUnbindImmediate = false;
            setBindService(false);
        }
    }

    public void handleDestroy() {
        if (DEBUG) Log.d(TAG, "handleDestroy");
        if (mPackageReceiverRegistered.get() || mUserReceiverRegistered.get()) {
            stopPackageListening();
        }
        mChangeListener = null;
    }

    private void handleDeath() {
        if (mWrapper == null) return;
        freeWrapper();
        // Clearly not bound anymore
        mIsBound = false;
        if (!mBound) return;
        if (DEBUG) Log.d(TAG, "handleDeath");
        if (checkComponentState()) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mBound) {
                        // Retry binding.
                        setBindService(true);
                    }
                }
            }, mBindRetryDelay);
        }
    }

    private boolean checkComponentState() {
        if (!isPackageAvailable() || !isComponentAvailable()) {
            startPackageListening();
            return false;
        }
        return true;
    }

    private void startPackageListening() {
        if (DEBUG) Log.d(TAG, "startPackageListening");
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            mPackageReceiverRegistered.set(true);
            mContext.registerReceiverAsUser(this, mUser, filter, null, mHandler);
        } catch (Exception ex) {
            mPackageReceiverRegistered.set(false);
            Log.e(TAG, "Could not register package receiver", ex);
        }
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        try {
            mUserReceiverRegistered.set(true);
            mBroadcastDispatcher.registerReceiverWithHandler(this, filter, mHandler, mUser);
        } catch (Exception ex) {
            mUserReceiverRegistered.set(false);
            Log.e(TAG, "Could not register unlock receiver", ex);
        }
    }

    private void stopPackageListening() {
        if (DEBUG) Log.d(TAG, "stopPackageListening");
        if (mUserReceiverRegistered.compareAndSet(true, false)) {
            mBroadcastDispatcher.unregisterReceiver(this);
        }
        if (mPackageReceiverRegistered.compareAndSet(true, false)) {
            mContext.unregisterReceiver(this);
        }
    }

    public void setTileChangeListener(TileChangeListener changeListener) {
        mChangeListener = changeListener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) Log.d(TAG, "onReceive: " + intent);
        if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            Uri data = intent.getData();
            String pkgName = data.getEncodedSchemeSpecificPart();
            if (!Objects.equals(pkgName, mIntent.getComponent().getPackageName())) {
                return;
            }
        }
        if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction()) && mChangeListener != null) {
            mChangeListener.onTileChanged(mIntent.getComponent());
        }
        stopPackageListening();
        if (mBound) {
            // Trying to bind again will check the state of the package before bothering to bind.
            if (DEBUG) Log.d(TAG, "Trying to rebind");
            setBindService(true);
        }
    }

    private boolean isComponentAvailable() {
        String packageName = mIntent.getComponent().getPackageName();
        try {
            ServiceInfo si = mPackageManagerAdapter.getServiceInfo(mIntent.getComponent(),
                    0, mUser.getIdentifier());
            if (DEBUG && si == null) Log.d(TAG, "Can't find component " + mIntent.getComponent());
            return si != null;
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
        return false;
    }

    private boolean isPackageAvailable() {
        String packageName = mIntent.getComponent().getPackageName();
        try {
            mPackageManagerAdapter.getPackageInfoAsUser(packageName, 0, mUser.getIdentifier());
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) Log.d(TAG, "Package not available: " + packageName, e);
            else Log.d(TAG, "Package not available: " + packageName);
        }
        return false;
    }

    private void queueMessage(int message) {
        synchronized (mQueuedMessages) {
            mQueuedMessages.add(message);
        }
    }

    @Override
    public void onTileAdded() {
        if (DEBUG) Log.d(TAG, "onTileAdded");
        if (mWrapper == null || !mWrapper.onTileAdded()) {
            queueMessage(MSG_ON_ADDED);
            handleDeath();
        }
    }

    @Override
    public void onTileRemoved() {
        if (DEBUG) Log.d(TAG, "onTileRemoved");
        if (mWrapper == null || !mWrapper.onTileRemoved()) {
            queueMessage(MSG_ON_REMOVED);
            handleDeath();
        }
    }

    @Override
    public void onStartListening() {
        if (DEBUG) Log.d(TAG, "onStartListening");
        mListening = true;
        if (mWrapper != null && !mWrapper.onStartListening()) {
            handleDeath();
        }
    }

    @Override
    public void onStopListening() {
        if (DEBUG) Log.d(TAG, "onStopListening");
        mListening = false;
        if (mWrapper != null && !mWrapper.onStopListening()) {
            handleDeath();
        }
    }

    @Override
    public void onClick(IBinder iBinder) {
        if (DEBUG) Log.d(TAG, "onClick " + iBinder + " " + mUser);
        if (mWrapper == null || !mWrapper.onClick(iBinder)) {
            mClickBinder = iBinder;
            queueMessage(MSG_ON_CLICK);
            handleDeath();
        }
    }

    @Override
    public void onUnlockComplete() {
        if (DEBUG) Log.d(TAG, "onUnlockComplete");
        if (mWrapper == null || !mWrapper.onUnlockComplete()) {
            queueMessage(MSG_ON_UNLOCK_COMPLETE);
            handleDeath();
        }
    }

    @Override
    public IBinder asBinder() {
        return mWrapper != null ? mWrapper.asBinder() : null;
    }

    @Override
    public void binderDied() {
        if (DEBUG) Log.d(TAG, "binderDeath");
        handleDeath();
    }

    public IBinder getToken() {
        return mToken;
    }

    private void freeWrapper() {
        if (mWrapper != null) {
            try {
                mWrapper.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.w(TAG, "Trying to unlink not linked recipient for component"
                        + mIntent.getComponent().flattenToShortString());
            }
            mWrapper = null;
        }
    }

    public interface TileChangeListener {
        void onTileChanged(ComponentName tile);
    }

    public static boolean isTileAdded(Context context, ComponentName component) {
        return context.getSharedPreferences(TILES, 0).getBoolean(component.flattenToString(), false);
    }

    public static void setTileAdded(Context context, ComponentName component, boolean added) {
        context.getSharedPreferences(TILES, 0).edit().putBoolean(component.flattenToString(),
                added).commit();
    }
}
