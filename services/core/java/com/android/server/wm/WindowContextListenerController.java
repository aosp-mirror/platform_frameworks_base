/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.isSuspendedState;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.window.WindowProviderService.isWindowProviderService;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.ProtoLogGroup.WM_ERROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.servertransaction.WindowContextInfoChangeItem;
import android.app.servertransaction.WindowContextWindowRemovalItem;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.view.View;
import android.view.WindowManager.LayoutParams.WindowType;
import android.window.WindowContext;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

import java.util.Objects;

/**
 * A controller to register/unregister {@link WindowContainerListener} for {@link WindowContext}.
 *
 * <ul>
 *   <li>When a {@link WindowContext} is created, it registers the listener via
 *     {@link WindowManagerService#attachWindowContextToDisplayArea
 *     automatically.</li>
 *   <li>When the {@link WindowContext} adds the first window to the screen via
 *     {@link android.view.WindowManager#addView(View, android.view.ViewGroup.LayoutParams)},
 *     {@link WindowManagerService} then updates the {@link WindowContextListenerImpl} to listen
 *     to corresponding {@link WindowToken} via this controller.</li>
 *   <li>When the {@link WindowContext} is GCed, it unregisters the previously
 *     registered listener via
 *     {@link WindowManagerService#detachWindowContext(IBinder)}.
 *     {@link WindowManagerService} is also responsible for removing the
 *     {@link WindowContext} created {@link WindowToken}.</li>
 * </ul>
 * <p>Note that the listener may be removed earlier than the
 * {@link #unregisterWindowContainerListener(IBinder)} if the listened {@link WindowContainer} was
 * removed. An example is that the {@link DisplayArea} is removed when users unfold the
 * foldable devices. Another example is that the associated external display is detached.</p>
 */
class WindowContextListenerController {
    @VisibleForTesting
    final ArrayMap<IBinder, WindowContextListenerImpl> mListeners = new ArrayMap<>();

    /**
     * @see #registerWindowContainerListener(WindowProcessController, IBinder, WindowContainer, int,
     * Bundle, boolean)
     */
    void registerWindowContainerListener(@NonNull WindowProcessController wpc,
            @NonNull IBinder clientToken, @NonNull WindowContainer<?> container,
            @WindowType int type, @Nullable Bundle options) {
        registerWindowContainerListener(wpc, clientToken, container, type, options,
                true /* shouldDispatchConfigWhenRegistering */);
    }

    /**
     * Registers the listener to a {@code container} which is associated with
     * a {@code clientToken}, which is a {@link WindowContext} representation. If the
     * listener associated with {@code clientToken} hasn't been initialized yet, create one
     * {@link WindowContextListenerImpl}. Otherwise, the listener associated with
     * {@code clientToken} switches to listen to the {@code container}.
     *
     * @param wpc the process that we should send the window configuration change to
     * @param clientToken the token to associate with the listener
     * @param container the {@link WindowContainer} which the listener is going to listen to.
     * @param type the window type
     * @param options a bundle used to pass window-related options.
     * @param shouldDispatchConfigWhenRegistering {@code true} to indicate the current
     *                {@code container}'s config will dispatch to the client side when
     *                registering the {@link WindowContextListenerImpl}
     */
    void registerWindowContainerListener(@NonNull WindowProcessController wpc,
            @NonNull IBinder clientToken, @NonNull WindowContainer<?> container,
            @WindowType int type, @Nullable Bundle options,
            boolean shouldDispatchConfigWhenRegistering) {
        WindowContextListenerImpl listener = mListeners.get(clientToken);
        if (listener == null) {
            listener = new WindowContextListenerImpl(wpc, clientToken, container, type,
                    options);
            listener.register(shouldDispatchConfigWhenRegistering);
        } else {
            updateContainerForWindowContextListener(clientToken, container);
        }
    }

    /**
     * Updates the {@link WindowContainer} that an existing {@link WindowContext} is listening to.
     */
    void updateContainerForWindowContextListener(@NonNull IBinder clientToken,
            @NonNull WindowContainer<?> container) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        if (listener == null) {
            throw new IllegalArgumentException("Can't find listener for " + clientToken);
        }
        listener.updateContainer(container);
    }

    void unregisterWindowContainerListener(IBinder clientToken) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        // Listeners may be removed earlier. An example is the display where the listener is
        // located is detached. In this case, all window containers on the display, as well as
        // their listeners will be removed before their listeners are unregistered.
        if (listener == null) {
            return;
        }
        listener.unregister();
        if (listener.mDeathRecipient != null) {
            listener.mDeathRecipient.unlinkToDeath();
        }
    }

    void dispatchPendingConfigurationIfNeeded(int displayId) {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final WindowContextListenerImpl listener = mListeners.valueAt(i);
            if (listener.getWindowContainer().getDisplayContent().getDisplayId() == displayId
                    && listener.mHasPendingConfiguration) {
                listener.dispatchWindowContextInfoChange();
            }
        }
    }

    /**
     * Verifies if the caller is allowed to do the operation to the listener specified by
     * {@code clientToken}.
     */
    boolean assertCallerCanModifyListener(IBinder clientToken, boolean callerCanManageAppTokens,
            int callingUid) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        if (listener == null) {
            ProtoLog.i(WM_DEBUG_ADD_REMOVE, "The listener does not exist.");
            return false;
        }
        if (callerCanManageAppTokens) {
            return true;
        }
        if (callingUid != listener.getUid()) {
            throw new UnsupportedOperationException("Uid mismatch. Caller uid is " + callingUid
                    + ", while the listener's owner is from " + listener.getUid());
        }
        return true;
    }

    boolean hasListener(IBinder clientToken) {
        return mListeners.containsKey(clientToken);
    }

    @WindowType int getWindowType(IBinder clientToken) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        return listener != null ? listener.mType : INVALID_WINDOW_TYPE;
    }

    @Nullable Bundle getOptions(IBinder clientToken) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        return listener != null ? listener.mOptions : null;
    }

    @Nullable WindowContainer<?> getContainer(IBinder clientToken) {
        final WindowContextListenerImpl listener = mListeners.get(clientToken);
        return listener != null ? listener.mContainer : null;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("WindowContextListenerController{");
        builder.append("mListeners=[");

        final int size = mListeners.values().size();
        for (int i = 0; i < size; i++) {
            builder.append(mListeners.valueAt(i));
            if (i != size - 1) {
                builder.append(", ");
            }
        }
        builder.append("]}");
        return builder.toString();
    }

    @VisibleForTesting
    class WindowContextListenerImpl implements WindowContainerListener {
        @NonNull
        private final WindowProcessController mWpc;
        @NonNull
        private final IBinder mClientToken;
        @NonNull
        private WindowContainer<?> mContainer;
        /**
         * The options from {@link Context#createWindowContext(int, Bundle)}.
         * <p>It can be used for choosing the {@link DisplayArea} where the window context
         * is located. </p>
         */
        @Nullable private final Bundle mOptions;
        @WindowType private final int mType;

        private DeathRecipient mDeathRecipient;

        private int mLastReportedDisplay = INVALID_DISPLAY;
        private Configuration mLastReportedConfig;

        private boolean mHasPendingConfiguration;

        private WindowContextListenerImpl(@NonNull WindowProcessController wpc,
                @NonNull IBinder clientToken, @NonNull WindowContainer<?> container,
                @WindowType int type, @Nullable Bundle options) {
            mWpc = Objects.requireNonNull(wpc);
            mClientToken = clientToken;
            mContainer = Objects.requireNonNull(container);
            mType = type;
            mOptions = options;

            final DeathRecipient deathRecipient = new DeathRecipient();
            try {
                deathRecipient.linkToDeath();
                mDeathRecipient = deathRecipient;
            } catch (RemoteException e) {
                ProtoLog.e(WM_ERROR, "Could not register window container listener token=%s, "
                        + "container=%s", clientToken, mContainer);
            }
        }

        /** TEST ONLY: returns the {@link WindowContainer} of the listener */
        @VisibleForTesting
        WindowContainer<?> getWindowContainer() {
            return mContainer;
        }

        int getUid() {
            return mWpc.mUid;
        }

        private void updateContainer(@NonNull WindowContainer<?> newContainer) {
            Objects.requireNonNull(newContainer);

            if (mContainer.equals(newContainer)) {
                return;
            }
            mContainer.unregisterWindowContainerListener(this);
            mContainer = newContainer;
            clear();
            register();
        }

        private void register() {
            register(true /* shouldDispatchConfig */);
        }

        private void register(boolean shouldDispatchConfig) {
            final IBinder token = mClientToken;
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + token);
            }
            mListeners.putIfAbsent(token, this);
            mContainer.registerWindowContainerListener(this, shouldDispatchConfig);
        }

        private void unregister() {
            mContainer.unregisterWindowContainerListener(this);
            mListeners.remove(mClientToken);
        }

        private void clear() {
            mLastReportedConfig = null;
            mLastReportedDisplay = INVALID_DISPLAY;
        }

        @Override
        public void onMergedOverrideConfigurationChanged(Configuration mergedOverrideConfig) {
            dispatchWindowContextInfoChange();
        }

        @Override
        public void onDisplayChanged(DisplayContent dc) {
            dispatchWindowContextInfoChange();
        }

        private void dispatchWindowContextInfoChange() {
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + mClientToken);
            }
            final DisplayContent dc = mContainer.getDisplayContent();
            if (!dc.isReady()) {
                // Do not report configuration when booting. The latest configuration will be sent
                // when WindowManagerService#displayReady().
                return;
            }
            // If the display of window context associated window container is suspended, don't
            // report the configuration update. Note that we still dispatch the configuration update
            // to WindowProviderService to make it compatible with Service#onConfigurationChanged.
            // Service always receives #onConfigurationChanged callback regardless of display state.
            if (!isWindowProviderService(mOptions) && isSuspendedState(dc.getDisplayInfo().state)) {
                mHasPendingConfiguration = true;
                return;
            }
            final Configuration config = mContainer.getConfiguration();
            final int displayId = dc.getDisplayId();
            if (mLastReportedConfig == null) {
                mLastReportedConfig = new Configuration();
            }
            if (config.equals(mLastReportedConfig) && displayId == mLastReportedDisplay) {
                // No changes since last reported time.
                return;
            }

            mLastReportedConfig.setTo(config);
            mLastReportedDisplay = displayId;

            mWpc.scheduleClientTransactionItem(
                    new WindowContextInfoChangeItem(mClientToken, config, displayId));
            mHasPendingConfiguration = false;
        }

        @Override
        public void onRemoved() {
            if (mDeathRecipient == null) {
                throw new IllegalStateException("Invalid client token: " + mClientToken);
            }
            final WindowToken windowToken = mContainer.asWindowToken();
            if (windowToken != null && windowToken.isFromClient()) {
                // If the WindowContext created WindowToken is removed by
                // WMS#postWindowRemoveCleanupLocked, the WindowContext should switch back to
                // listen to previous associated DisplayArea.
                final DisplayContent dc = windowToken.mWmService.mRoot
                        .getDisplayContent(mLastReportedDisplay);
                // If we cannot obtain the DisplayContent, the DisplayContent may also be removed.
                // We should proceed the removal process.
                if (dc != null) {
                    final DisplayArea<?> da = dc.findAreaForToken(windowToken);
                    updateContainer(da);
                    return;
                }
            }
            mDeathRecipient.unlinkToDeath();
            mWpc.scheduleClientTransactionItem(new WindowContextWindowRemovalItem(mClientToken));
            unregister();
        }

        @Override
        public String toString() {
            return "WindowContextListenerImpl{clientToken=" + mClientToken + ", "
                    + "container=" + mContainer + "}";
        }

        private class DeathRecipient implements IBinder.DeathRecipient {
            @Override
            public void binderDied() {
                synchronized (mContainer.mWmService.mGlobalLock) {
                    mDeathRecipient = null;
                    unregister();
                }
            }

            void linkToDeath() throws RemoteException {
                mClientToken.linkToDeath(this, 0);
            }

            void unlinkToDeath() {
                mClientToken.unlinkToDeath(this, 0);
            }
        }
    }
}
