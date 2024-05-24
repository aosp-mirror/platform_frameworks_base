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

import static android.service.quicksettings.TileService.START_ACTIVITY_NEEDS_PENDING_INTENT;

import android.app.ActivityManager;
import android.app.compat.CompatChanges;
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
import android.service.quicksettings.TileService;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Manages the lifecycle of a TileService.
 * <p>
 * Will keep track of all calls on the IQSTileService interface and will relay those calls to the
 * TileService as soon as it is bound.  It will only bind to the service when it is allowed to
 * ({@link #setBindService(boolean)}) and when the service is available.
 */
public class TileLifecycleManager extends BroadcastReceiver implements
        IQSTileService, ServiceConnection, IBinder.DeathRecipient {

    private final boolean mDebug = Log.isLoggable(TAG, Log.DEBUG);

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
    private static final long DEFAULT_BIND_RETRY_DELAY = 5 * DateUtils.SECOND_IN_MILLIS;
    private static final long LOW_MEMORY_BIND_RETRY_DELAY = 20 * DateUtils.SECOND_IN_MILLIS;

    // Shared prefs that hold tile lifecycle info.
    private static final String TILES = "tiles_prefs";

    private final Context mContext;
    private final Handler mHandler;
    private final Intent mIntent;
    private final UserHandle mUser;
    private final DelayableExecutor mExecutor;
    private final IBinder mToken = new Binder();
    private final PackageManagerAdapter mPackageManagerAdapter;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ActivityManager mActivityManager;

    private Set<Integer> mQueuedMessages = new ArraySet<>();
    @NonNull
    private volatile Optional<QSTileServiceWrapper> mOptionalWrapper = Optional.empty();
    private boolean mListening;
    private IBinder mClickBinder;

    private int mBindTryCount;
    private long mBindRetryDelay = DEFAULT_BIND_RETRY_DELAY;
    private AtomicBoolean isDeathRebindScheduled = new AtomicBoolean(false);
    private AtomicBoolean mBound = new AtomicBoolean(false);
    private AtomicBoolean mPackageReceiverRegistered = new AtomicBoolean(false);
    private AtomicBoolean mUserReceiverRegistered = new AtomicBoolean(false);
    private AtomicBoolean mUnbindImmediate = new AtomicBoolean(false);
    @Nullable
    private TileChangeListener mChangeListener;
    // Return value from bindServiceAsUser, determines whether safe to call unbind.
    private AtomicBoolean mIsBound = new AtomicBoolean(false);

    @AssistedInject
    TileLifecycleManager(@Main Handler handler, Context context, IQSService service,
            PackageManagerAdapter packageManagerAdapter, BroadcastDispatcher broadcastDispatcher,
            @Assisted Intent intent, @Assisted UserHandle user, ActivityManager activityManager,
            @Background DelayableExecutor executor) {
        mContext = context;
        mHandler = handler;
        mIntent = intent;
        mIntent.putExtra(TileService.EXTRA_SERVICE, service.asBinder());
        mIntent.putExtra(TileService.EXTRA_TOKEN, mToken);
        mUser = user;
        mExecutor = executor;
        mPackageManagerAdapter = packageManagerAdapter;
        mBroadcastDispatcher = broadcastDispatcher;
        mActivityManager = activityManager;
        if (mDebug) Log.d(TAG, "Creating " + mIntent + " " + mUser);
    }

    /** Injectable factory for TileLifecycleManager. */
    @AssistedFactory
    public interface Factory {
        /**
         *
         */
        TileLifecycleManager create(Intent intent, UserHandle userHandle);
    }

    public int getUserId() {
        return mUser.getIdentifier();
    }

    public ComponentName getComponent() {
        return mIntent.getComponent();
    }

    public boolean hasPendingClick() {
        synchronized (mQueuedMessages) {
            return mQueuedMessages.contains(MSG_ON_CLICK);
        }
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
     * tile
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
        mExecutor.execute(() -> {
            mUnbindImmediate.set(true);
            setBindService(true);
        });
    }

    @WorkerThread
    private void setBindService(boolean bind) {
        if (mBound.get() && mUnbindImmediate.get()) {
            // If we are already bound and expecting to unbind, this means we should stay bound
            // because something else wants to hold the connection open.
            mUnbindImmediate.set(false);
            return;
        }
        mBound.set(bind);
        if (bind) {
            if (mBindTryCount == MAX_BIND_RETRIES) {
                // Too many failures, give up on this tile until an update.
                startPackageListening();
                return;
            }
            if (!checkComponentState()) {
                return;
            }
            if (mDebug) Log.d(TAG, "Binding service " + mIntent + " " + mUser);
            mBindTryCount++;
            try {
                // Only try a new binding if we are not currently bound.
                mIsBound.compareAndSet(false, bindServices());
                if (!mIsBound.get()) {
                    Log.d(TAG, "Failed to bind to service");
                    mContext.unbindService(this);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to bind to service", e);
                mIsBound.set(false);
            }
        } else {
            unbindService();
        }
    }

    /**
     * Binds or unbinds to IQSService
     */
    public void executeSetBindService(boolean bind) {
        mExecutor.execute(() -> setBindService(bind));
    }

    private boolean bindServices() {
        String packageName = mIntent.getComponent().getPackageName();
        int flags = Context.BIND_AUTO_CREATE
                | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                | Context.BIND_WAIVE_PRIORITY;
        if (CompatChanges.isChangeEnabled(START_ACTIVITY_NEEDS_PENDING_INTENT, packageName,
                mUser)) {
            return mContext.bindServiceAsUser(mIntent, this, flags, mUser);
        }
        return mContext.bindServiceAsUser(mIntent, this,
                flags | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                mUser);
    }

    @WorkerThread
    private void unbindService() {
        if (mDebug) Log.d(TAG, "Unbinding service " + mIntent + " " + mUser);
        // Give it another chance next time it needs to be bound, out of kindness.
        mBindTryCount = 0;
        freeWrapper();
        if (mIsBound.get()) {
            try {
                mContext.unbindService(this);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unbind service "
                        + mIntent.getComponent().flattenToShortString(), e);
            }
            mIsBound.set(false);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (mDebug) Log.d(TAG, "onServiceConnected " + name);
        // Got a connection, set the binding count to 0.
        mBindTryCount = 0;
        final QSTileServiceWrapper wrapper = new QSTileServiceWrapper(Stub.asInterface(service));
        try {
            service.linkToDeath(this, 0);
        } catch (RemoteException e) {
        }
        mOptionalWrapper = Optional.of(wrapper);
        handlePendingMessages();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        executeSetBindService(false);
    }

    @Override
    public void onBindingDied(ComponentName name) {
        if (mDebug) Log.d(TAG, "onBindingDied " + name);
        handleDeath();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (mDebug) Log.d(TAG, "onServiceDisconnected " + name);
        freeWrapper();
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
            if (mDebug) Log.d(TAG, "Handling pending onAdded " + getComponent());
            onTileAdded();
        }
        if (mListening) {
            if (mDebug) Log.d(TAG, "Handling pending onStartListening " + getComponent());
            onStartListening();
        }
        if (queue.contains(MSG_ON_CLICK)) {
            if (mDebug) Log.d(TAG, "Handling pending onClick " + getComponent());
            if (!mListening) {
                Log.w(TAG, "Managed to get click on non-listening state... " + getComponent());
                // Skipping click since lost click privileges.
            } else {
                onClick(mClickBinder);
            }
        }
        if (queue.contains(MSG_ON_UNLOCK_COMPLETE)) {
            if (mDebug) Log.d(TAG, "Handling pending onUnlockComplete " + getComponent());
            if (!mListening) {
                Log.w(TAG,
                        "Managed to get unlock on non-listening state... " + getComponent());
                // Skipping unlock since lost click privileges.
            } else {
                onUnlockComplete();
            }
        }
        if (queue.contains(MSG_ON_REMOVED)) {
            if (mDebug) Log.d(TAG, "Handling pending onRemoved " + getComponent());
            if (mListening) {
                Log.w(TAG, "Managed to get remove in listening state... " + getComponent());
                onStopListening();
            }
            onTileRemoved();
        }
        mExecutor.execute(() -> {
            if (mUnbindImmediate.get()) {
                mUnbindImmediate.set(false);
                setBindService(false);
            }
        });
    }

    public void handleDestroy() {
        if (mDebug) Log.d(TAG, "handleDestroy");
        if (mPackageReceiverRegistered.get() || mUserReceiverRegistered.get()) {
            stopPackageListening();
        }
        mChangeListener = null;
    }

    /**
     * Handles a dead binder.
     *
     * It means that we need to clean up the binding (calling unbindService). After that, if we
     * are supposed to be bound, we will try to bind after some amount of time.
     */
    private void handleDeath() {
        if (!mIsBound.get()) {
            // If we are already not bound, don't do anything else.
            return;
        }
        mExecutor.execute(() -> {
            if (!mIsBound.get()) {
                // If we are already not bound, don't do anything else.
                return;
            }
            // Clearly we shouldn't be bound anymore
            if (mDebug) Log.d(TAG, "handleDeath " + getComponent());
            // Binder died, make sure that we unbind. However, we don't want to call setBindService
            // as we still may want to rebind.
            unbindService();
            // If mBound is true (meaning that we should be bound), then reschedule binding for
            // later.
            if (mBound.get() && checkComponentState()) {
                if (isDeathRebindScheduled.compareAndSet(false, true)) {
                    mExecutor.executeDelayed(() -> {
                        // Only rebind if we are supposed to, but remove the scheduling anyway.
                        if (mBound.get()) {
                            setBindService(true);
                        }
                        isDeathRebindScheduled.set(false);
                    }, getRebindDelay());
                }
            }
        });
    }

    /**
     * @return the delay to automatically rebind after a service died. It provides a longer delay if
     * the device is a low memory state because the service is likely to get killed again by the
     * system. In this case we want to rebind later and not to cause a loop of a frequent rebinds.
     */
    private long getRebindDelay() {
        final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        mActivityManager.getMemoryInfo(info);

        final long delay;
        if (info.lowMemory) {
            delay = LOW_MEMORY_BIND_RETRY_DELAY;
        } else {
            delay = mBindRetryDelay;
        }
        if (mDebug) Log.i(TAG, "Rebinding with a delay=" + delay + " - " + getComponent());
        return delay;
    }

    private boolean checkComponentState() {
        if (!isPackageAvailable() || !isComponentAvailable()) {
            startPackageListening();
            return false;
        }
        return true;
    }

    private void startPackageListening() {
        if (mDebug) Log.d(TAG, "startPackageListening " + getComponent());
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            mPackageReceiverRegistered.set(true);
            mContext.registerReceiverAsUser(
                    this, mUser, filter, null, mHandler, Context.RECEIVER_EXPORTED);
        } catch (Exception ex) {
            mPackageReceiverRegistered.set(false);
            Log.e(TAG, "Could not register package receiver " + getComponent(), ex);
        }
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        try {
            mUserReceiverRegistered.set(true);
            mBroadcastDispatcher.registerReceiverWithHandler(this, filter, mHandler, mUser);
        } catch (Exception ex) {
            mUserReceiverRegistered.set(false);
            Log.e(TAG, "Could not register unlock receiver " + getComponent(), ex);
        }
    }

    private void stopPackageListening() {
        if (mDebug) Log.d(TAG, "stopPackageListening " + getComponent());
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
        if (mDebug) Log.d(TAG, "onReceive: " + intent);
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
        mExecutor.execute(() -> {
            if (mBound.get()) {
                // Trying to bind again will check the state of the package before bothering to
                // bind.
                if (mDebug) Log.d(TAG, "Trying to rebind " + getComponent());
                setBindService(true);
            }

        });
    }

    private boolean isComponentAvailable() {
        String packageName = mIntent.getComponent().getPackageName();
        try {
            ServiceInfo si = mPackageManagerAdapter.getServiceInfo(mIntent.getComponent(),
                    0, mUser.getIdentifier());
            if (mDebug && si == null) {
                Log.d(TAG, "Can't find component " + mIntent.getComponent());
            }
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
            if (mDebug) {
                Log.d(TAG, "Package not available: " + packageName, e);
            } else {
                Log.d(TAG, "Package not available: " + packageName);
            }
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
        if (mDebug) Log.d(TAG, "onTileAdded " + getComponent());
        if (isNullOrFailedAction(mOptionalWrapper, QSTileServiceWrapper::onTileAdded)) {
            queueMessage(MSG_ON_ADDED);
            handleDeath();
        }
    }

    @Override
    public void onTileRemoved() {
        if (mDebug) Log.d(TAG, "onTileRemoved " + getComponent());
        if (isNullOrFailedAction(mOptionalWrapper, QSTileServiceWrapper::onTileRemoved)) {
            queueMessage(MSG_ON_REMOVED);
            handleDeath();
        }
    }

    @Override
    public void onStartListening() {
        if (mDebug) Log.d(TAG, "onStartListening " + getComponent());
        mListening = true;
        if (isNotNullAndFailedAction(mOptionalWrapper, QSTileServiceWrapper::onStartListening)) {
            handleDeath();
        }
    }

    @Override
    public void onStopListening() {
        if (mDebug) Log.d(TAG, "onStopListening " + getComponent());
        mListening = false;
        if (isNotNullAndFailedAction(mOptionalWrapper, QSTileServiceWrapper::onStopListening)) {
            handleDeath();
        }
    }

    @Override
    public void onClick(IBinder iBinder) {
        if (mDebug) Log.d(TAG, "onClick " + iBinder + " " + getComponent() + " " + mUser);
        if (isNullOrFailedAction(mOptionalWrapper, (wrapper) -> wrapper.onClick(iBinder))) {
            mClickBinder = iBinder;
            queueMessage(MSG_ON_CLICK);
            handleDeath();
        }
    }

    @Override
    public void onUnlockComplete() {
        if (mDebug) Log.d(TAG, "onUnlockComplete " + getComponent());
        if (isNullOrFailedAction(mOptionalWrapper, QSTileServiceWrapper::onUnlockComplete)) {
            queueMessage(MSG_ON_UNLOCK_COMPLETE);
            handleDeath();
        }
    }

    @Nullable
    @Override
    public IBinder asBinder() {
        return mOptionalWrapper.map(QSTileServiceWrapper::asBinder).orElse(null);
    }

    @Override
    public void binderDied() {
        if (mDebug) Log.d(TAG, "binderDeath " + getComponent());
        handleDeath();
    }

    public IBinder getToken() {
        return mToken;
    }

    private void freeWrapper() {
        if (mOptionalWrapper.isPresent()) {
            try {
                mOptionalWrapper.ifPresent(
                        (wrapper) -> wrapper.asBinder().unlinkToDeath(this, 0)
                );
            } catch (NoSuchElementException e) {
                Log.w(TAG, "Trying to unlink not linked recipient for component"
                        + mIntent.getComponent().flattenToShortString());
            }
            mOptionalWrapper = Optional.empty();
        }
    }

    public interface TileChangeListener {
        void onTileChanged(ComponentName tile);
    }

    /**
     * Returns true if the Optional is empty OR performing the action on the content of the Optional
     * (when not empty) fails.
     */
    private static boolean isNullOrFailedAction(
            Optional<QSTileServiceWrapper> optionalWrapper,
            Predicate<QSTileServiceWrapper> action
    ) {
        return !optionalWrapper.map(action::test).orElse(false);
    }

    /**
     * Returns true if the Optional is not empty AND performing the action on the content of
     * the Optional fails.
     */
    private static boolean isNotNullAndFailedAction(
            Optional<QSTileServiceWrapper> optionalWrapper,
            Predicate<QSTileServiceWrapper> action
    ) {
        return  !optionalWrapper.map(action::test).orElse(true);
    }
}
