/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.StatusBarManager.DISABLE2_NONE;
import static android.app.StatusBarManager.DISABLE_NONE;
import static android.inputmethodservice.InputMethodService.BACK_DISPOSITION_DEFAULT;
import static android.inputmethodservice.InputMethodService.IME_INVISIBLE;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.Nullable;
import android.app.ITransientNotificationCallback;
import android.app.StatusBarManager;
import android.app.StatusBarManager.Disable2Flags;
import android.app.StatusBarManager.DisableFlags;
import android.app.StatusBarManager.WindowType;
import android.app.StatusBarManager.WindowVisibleState;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.media.INearbyMediaDevicesProvider;
import android.media.MediaRoute2Info;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Pair;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.accessibility.Flags;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IUndoMediaTransferCallback;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.GcUtils;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.dump.DumpHandler;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.commandline.CommandRegistry;
import com.android.systemui.statusbar.policy.CallbackController;

import dagger.Lazy;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * This class takes the functions from IStatusBar that come in on
 * binder pool threads and posts messages to get them onto the main
 * thread, and calls onto Callbacks.  It also takes care of
 * coalescing these calls so they don't stack up.  For the calls
 * are coalesced, note that they are all idempotent.
 */
public class CommandQueue extends IStatusBar.Stub implements
        CallbackController<Callbacks> {
    private static final String TAG = CommandQueue.class.getSimpleName();

    private static final int INDEX_MASK = 0xffff;
    private static final int MSG_SHIFT  = 16;
    private static final int MSG_MASK   = 0xffff << MSG_SHIFT;

    private static final int OP_SET_ICON    = 1;
    private static final int OP_REMOVE_ICON = 2;

    private static final int MSG_ICON                              = 1 << MSG_SHIFT;
    private static final int MSG_DISABLE                           = 2 << MSG_SHIFT;
    private static final int MSG_EXPAND_NOTIFICATIONS              = 3 << MSG_SHIFT;
    private static final int MSG_COLLAPSE_PANELS                   = 4 << MSG_SHIFT;
    private static final int MSG_EXPAND_SETTINGS                   = 5 << MSG_SHIFT;
    private static final int MSG_SYSTEM_BAR_CHANGED                = 6 << MSG_SHIFT;
    private static final int MSG_DISPLAY_READY                     = 7 << MSG_SHIFT;
    private static final int MSG_SHOW_IME_BUTTON                   = 8 << MSG_SHIFT;
    private static final int MSG_TOGGLE_RECENT_APPS                = 9 << MSG_SHIFT;
    private static final int MSG_PRELOAD_RECENT_APPS               = 10 << MSG_SHIFT;
    private static final int MSG_CANCEL_PRELOAD_RECENT_APPS        = 11 << MSG_SHIFT;
    private static final int MSG_SET_WINDOW_STATE                  = 12 << MSG_SHIFT;
    private static final int MSG_SHOW_RECENT_APPS                  = 13 << MSG_SHIFT;
    private static final int MSG_HIDE_RECENT_APPS                  = 14 << MSG_SHIFT;
    private static final int MSG_SHOW_SCREEN_PIN_REQUEST           = 18 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_PENDING            = 19 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_CANCELLED          = 20 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_STARTING           = 21 << MSG_SHIFT;
    private static final int MSG_ASSIST_DISCLOSURE                 = 22 << MSG_SHIFT;
    private static final int MSG_START_ASSIST                      = 23 << MSG_SHIFT;
    private static final int MSG_CAMERA_LAUNCH_GESTURE             = 24 << MSG_SHIFT;
    private static final int MSG_TOGGLE_KEYBOARD_SHORTCUTS         = 25 << MSG_SHIFT;
    private static final int MSG_SHOW_PICTURE_IN_PICTURE_MENU      = 26 << MSG_SHIFT;
    private static final int MSG_ADD_QS_TILE                       = 27 << MSG_SHIFT;
    private static final int MSG_REMOVE_QS_TILE                    = 28 << MSG_SHIFT;
    private static final int MSG_CLICK_QS_TILE                     = 29 << MSG_SHIFT;
    private static final int MSG_TOGGLE_APP_SPLIT_SCREEN           = 30 << MSG_SHIFT;
    private static final int MSG_APP_TRANSITION_FINISHED           = 31 << MSG_SHIFT;
    private static final int MSG_DISMISS_KEYBOARD_SHORTCUTS        = 32 << MSG_SHIFT;
    private static final int MSG_HANDLE_SYSTEM_KEY                 = 33 << MSG_SHIFT;
    private static final int MSG_SHOW_GLOBAL_ACTIONS               = 34 << MSG_SHIFT;
    private static final int MSG_TOGGLE_PANEL                      = 35 << MSG_SHIFT;
    private static final int MSG_SHOW_SHUTDOWN_UI                  = 36 << MSG_SHIFT;
    private static final int MSG_SET_TOP_APP_HIDES_STATUS_BAR      = 37 << MSG_SHIFT;
    private static final int MSG_ROTATION_PROPOSAL                 = 38 << MSG_SHIFT;
    private static final int MSG_BIOMETRIC_SHOW                    = 39 << MSG_SHIFT;
    private static final int MSG_BIOMETRIC_AUTHENTICATED           = 40 << MSG_SHIFT;
    private static final int MSG_BIOMETRIC_HELP                    = 41 << MSG_SHIFT;
    private static final int MSG_BIOMETRIC_ERROR                   = 42 << MSG_SHIFT;
    private static final int MSG_BIOMETRIC_HIDE                    = 43 << MSG_SHIFT;
    private static final int MSG_SHOW_CHARGING_ANIMATION           = 44 << MSG_SHIFT;
    private static final int MSG_SHOW_PINNING_TOAST_ENTER_EXIT     = 45 << MSG_SHIFT;
    private static final int MSG_SHOW_PINNING_TOAST_ESCAPE         = 46 << MSG_SHIFT;
    private static final int MSG_RECENTS_ANIMATION_STATE_CHANGED   = 47 << MSG_SHIFT;
    private static final int MSG_SHOW_TRANSIENT                    = 48 << MSG_SHIFT;
    private static final int MSG_ABORT_TRANSIENT                   = 49 << MSG_SHIFT;
    private static final int MSG_SHOW_INATTENTIVE_SLEEP_WARNING    = 50 << MSG_SHIFT;
    private static final int MSG_DISMISS_INATTENTIVE_SLEEP_WARNING = 51 << MSG_SHIFT;
    private static final int MSG_SHOW_TOAST                        = 52 << MSG_SHIFT;
    private static final int MSG_HIDE_TOAST                        = 53 << MSG_SHIFT;
    private static final int MSG_TRACING_STATE_CHANGED             = 54 << MSG_SHIFT;
    private static final int MSG_SUPPRESS_AMBIENT_DISPLAY          = 55 << MSG_SHIFT;
    private static final int MSG_REQUEST_MAGNIFICATION_CONNECTION = 56 << MSG_SHIFT;
    //TODO(b/169175022) Update name and when feature name is locked.
    private static final int MSG_EMERGENCY_ACTION_LAUNCH_GESTURE      = 58 << MSG_SHIFT;
    private static final int MSG_SET_NAVIGATION_BAR_LUMA_SAMPLING_ENABLED = 59 << MSG_SHIFT;
    private static final int MSG_SET_UDFPS_REFRESH_RATE_CALLBACK = 60 << MSG_SHIFT;
    private static final int MSG_TILE_SERVICE_REQUEST_ADD = 61 << MSG_SHIFT;
    private static final int MSG_TILE_SERVICE_REQUEST_CANCEL = 62 << MSG_SHIFT;
    private static final int MSG_SET_BIOMETRICS_LISTENER = 63 << MSG_SHIFT;
    private static final int MSG_MEDIA_TRANSFER_SENDER_STATE = 64 << MSG_SHIFT;
    private static final int MSG_MEDIA_TRANSFER_RECEIVER_STATE = 65 << MSG_SHIFT;
    private static final int MSG_REGISTER_NEARBY_MEDIA_DEVICE_PROVIDER = 66 << MSG_SHIFT;
    private static final int MSG_UNREGISTER_NEARBY_MEDIA_DEVICE_PROVIDER = 67 << MSG_SHIFT;
    private static final int MSG_TILE_SERVICE_REQUEST_LISTENING_STATE = 68 << MSG_SHIFT;
    private static final int MSG_SHOW_REAR_DISPLAY_DIALOG = 69 << MSG_SHIFT;
    private static final int MSG_MOVE_FOCUSED_TASK_TO_FULLSCREEN = 70 << MSG_SHIFT;
    private static final int MSG_MOVE_FOCUSED_TASK_TO_STAGE_SPLIT = 71 << MSG_SHIFT;
    private static final int MSG_SHOW_MEDIA_OUTPUT_SWITCHER = 72 << MSG_SHIFT;
    private static final int MSG_TOGGLE_TASKBAR = 73 << MSG_SHIFT;
    private static final int MSG_SETTING_CHANGED = 74 << MSG_SHIFT;
    private static final int MSG_LOCK_TASK_MODE_CHANGED = 75 << MSG_SHIFT;
    private static final int MSG_CONFIRM_IMMERSIVE_PROMPT = 77 << MSG_SHIFT;
    private static final int MSG_IMMERSIVE_CHANGED = 78 << MSG_SHIFT;
    private static final int MSG_SET_QS_TILES = 79 << MSG_SHIFT;
    private static final int MSG_ENTER_DESKTOP = 80 << MSG_SHIFT;
    public static final int FLAG_EXCLUDE_NONE = 0;
    public static final int FLAG_EXCLUDE_SEARCH_PANEL = 1 << 0;
    public static final int FLAG_EXCLUDE_RECENTS_PANEL = 1 << 1;
    public static final int FLAG_EXCLUDE_NOTIFICATION_PANEL = 1 << 2;
    public static final int FLAG_EXCLUDE_INPUT_METHODS_PANEL = 1 << 3;
    public static final int FLAG_EXCLUDE_COMPAT_MODE_PANEL = 1 << 4;

    private static final String SHOW_IME_SWITCHER_KEY = "showImeSwitcherKey";

    private final Object mLock = new Object();
    private ArrayList<Callbacks> mCallbacks = new ArrayList<>();
    private Handler mHandler = new H(Looper.getMainLooper());
    /** A map of display id - disable flag pair */
    private SparseArray<Pair<Integer, Integer>> mDisplayDisabled = new SparseArray<>();
    /**
     * The last ID of the display where IME window for which we received setImeWindowStatus
     * event.
     */
    private int mLastUpdatedImeDisplayId = INVALID_DISPLAY;
    private final DisplayTracker mDisplayTracker;
    private final @Nullable CommandRegistry mRegistry;
    private final @Nullable DumpHandler mDumpHandler;
    private final @Nullable Lazy<PowerInteractor> mPowerInteractor;

    /**
     * These methods are called back on the main thread.
     */
    public interface Callbacks {
        default void setIcon(String slot, StatusBarIcon icon) { }
        default void removeIcon(String slot) { }

        /**
         * Called to notify that disable flags are updated.
         * @see IStatusBar#disable(int, int, int).
         *
         * @param displayId The id of the display to notify.
         * @param state1 The combination of following DISABLE_* flags:
         * @param state2 The combination of following DISABLE2_* flags:
         * @param animate {@code true} to show animations.
         */
        default void disable(int displayId, @DisableFlags int state1, @Disable2Flags int state2,
                boolean animate) { }
        default void animateExpandNotificationsPanel() { }
        default void animateCollapsePanels(int flags, boolean force) { }
        default void togglePanel() { }
        default void animateExpandSettingsPanel(String obj) { }

        /**
         * Called to notify IME window status changes.
         *
         * @param displayId The id of the display to notify.
         * @param token IME token.
         * @param vis IME visibility.
         * @param backDisposition Disposition mode of back button. It should be one of below flags:
         * @param showImeSwitcher {@code true} to show IME switch button.
         */
        default void setImeWindowStatus(int displayId, IBinder token,  int vis,
                @BackDispositionMode int backDisposition, boolean showImeSwitcher) { }
        default void showRecentApps(boolean triggeredFromAltTab) { }
        default void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) { }
        default void toggleTaskbar() { }
        default void toggleRecentApps() { }
        default void toggleSplitScreen() { }
        default void preloadRecentApps() { }
        default void dismissKeyboardShortcutsMenu() { }
        default void toggleKeyboardShortcutsMenu(int deviceId) { }
        default void cancelPreloadRecentApps() { }

        /**
         * Called to notify window state changes.
         * @see IStatusBar#setWindowState(int, int, int)
         *
         * @param displayId The id of the display to notify.
         * @param window Window type. It should be one of {@link StatusBarManager#WINDOW_STATUS_BAR}
         *               or {@link StatusBarManager#WINDOW_NAVIGATION_BAR}
         * @param state Window visible state.
         */
        default void setWindowState(int displayId, @WindowType int window,
                @WindowVisibleState int state) { }
        default void showScreenPinningRequest(int taskId) { }

        /**
         * Called to notify System UI that an application transition is pending.
         * @see IStatusBar#appTransitionPending(int).
         *
         * @param displayId The id of the display to notify.
         * @param forced {@code true} to force transition pending.
         */
        default void appTransitionPending(int displayId, boolean forced) { }

        /**
         * Called to notify System UI that an application transition is canceled.
         * @see IStatusBar#appTransitionCancelled(int).
         *
         * @param displayId The id of the display to notify.
         */
        default void appTransitionCancelled(int displayId) { }

        /**
         * Called to notify System UI that an application transition is starting.
         * @see IStatusBar#appTransitionStarting(int, long, long).
         *
         * @param displayId The id of the display to notify.
         * @param startTime Transition start time.
         * @param duration Transition duration.
         * @param forced {@code true} to force transition pending.
         */
        default void appTransitionStarting(
                int displayId, long startTime, long duration, boolean forced) { }

        /**
         * Called to notify System UI that an application transition is finished.
         * @see IStatusBar#appTransitionFinished(int)
         *
         * @param displayId The id of the display to notify.
         */
        default void appTransitionFinished(int displayId) { }
        default void showAssistDisclosure() { }
        default void startAssist(Bundle args) { }
        default void onCameraLaunchGestureDetected(int source) { }

        /**
         * Notifies SysUI that the emergency action gesture was detected.
         */
        default void onEmergencyActionLaunchGestureDetected() { }
        default void showPictureInPictureMenu() { }
        default void setTopAppHidesStatusBar(boolean topAppHidesStatusBar) { }

        default void addQsTile(ComponentName tile) { }

        /**
         * Add a tile to the Quick Settings Panel
         * @param tile the ComponentName of the {@link android.service.quicksettings.TileService}
         * @param end if true, the tile will be added at the end. If false, at the beginning.
         */
        default void addQsTileToFrontOrEnd(ComponentName tile, boolean end) { }
        default void remQsTile(ComponentName tile) { }

        default void setQsTiles(String[] tiles) {}
        default void clickTile(ComponentName tile) { }

        default void handleSystemKey(KeyEvent arg1) { }
        default void showPinningEnterExitToast(boolean entering) { }
        default void showPinningEscapeToast() { }
        default void handleShowGlobalActionsMenu() { }
        default void handleShowShutdownUi(boolean isReboot, String reason) { }

        default void showWirelessChargingAnimation(int batteryLevel) {  }

        default void onRotationProposal(int rotation, boolean isValid) { }

        default void showAuthenticationDialog(PromptInfo promptInfo,
                IBiometricSysuiReceiver receiver,
                int[] sensorIds, boolean credentialAllowed,
                boolean requireConfirmation, int userId, long operationId, String opPackageName,
                long requestId) {
        }

        /** @see IStatusBar#onBiometricAuthenticated(int) */
        default void onBiometricAuthenticated(@Modality int modality) {
        }

        /** @see IStatusBar#onBiometricHelp(int, String) */
        default void onBiometricHelp(@Modality int modality, String message) {
        }

        /** @see IStatusBar#onBiometricError(int, int, int) */
        default void onBiometricError(@Modality int modality, int error, int vendorCode) {
        }

        default void hideAuthenticationDialog(long requestId) {
        }

        /**
         * @see IStatusBar#setBiometicContextListener(IBiometricContextListener)
         */
        default void setBiometricContextListener(IBiometricContextListener listener) {
        }

        /**
         * @see IStatusBar#setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback)
         */
        default void setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback callback) {
        }

        /**
         * @see IStatusBar#onDisplayReady(int)
         */
        default void onDisplayReady(int displayId) {
        }

        /**
         * @see DisplayTracker.Callback#onDisplayRemoved(int)
         */
        default void onDisplayRemoved(int displayId) {
        }

        /**
         * @see IStatusBar#onRecentsAnimationStateChanged(boolean)
         */
        default void onRecentsAnimationStateChanged(boolean running) { }

        /**
         * @see IStatusBar#onSystemBarAttributesChanged
         */
        default void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
                @Behavior int behavior, @InsetsType int requestedVisibleTypes,
                String packageName, LetterboxDetails[] letterboxDetails) { }

        /**
         * @see IStatusBar#showTransient(int, int, boolean).
         */
        default void showTransient(int displayId, @InsetsType int types,
                boolean isGestureOnSystemBar) {}

        /**
         * @see IStatusBar#abortTransient(int, int).
         */
        default void abortTransient(int displayId, @InsetsType int types) { }

        /**
         * Called to notify System UI that a warning about the device going to sleep
         * due to prolonged user inactivity should be shown.
         */
        default void showInattentiveSleepWarning() { }

        /**
         * Called to notify System UI that the warning about the device going to sleep
         * due to prolonged user inactivity should be dismissed.
         */
        default void dismissInattentiveSleepWarning(boolean animated) { }

        /** Called to suppress ambient display. */
        default void suppressAmbientDisplay(boolean suppress) { }

        /**
         * @see IStatusBar#showToast(int, String, IBinder, CharSequence, IBinder, int,
         * ITransientNotificationCallback, int)
         */
        default void showToast(int uid, String packageName, IBinder token, CharSequence text,
                IBinder windowToken, int duration,
                @Nullable ITransientNotificationCallback callback, int displayId) { }

        /**
         * @see IStatusBar#hideToast(String, IBinder) (String, IBinder)
         */
        default void hideToast(String packageName, IBinder token) { }

        /**
         * @param enabled
         */
        default void onTracingStateChanged(boolean enabled) { }

        /**
         * Requests {@link com.android.systemui.accessibility.Magnification} to invoke
         * {@code android.view.accessibility.AccessibilityManager#
         * setMagnificationConnection(IMagnificationConnection)}
         *
         * @param connect {@code true} if needs connection, otherwise set the connection to null.
         */
        default void requestMagnificationConnection(boolean connect) { }

        /**
         * @see IStatusBar#setNavigationBarLumaSamplingEnabled(int, boolean)
         */
        default void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {}

        /**
         * @see IStatusBar#requestTileServiceListeningState
         */
        default void requestTileServiceListeningState(@NonNull ComponentName componentName) {}

        /**
         * @see IStatusBar#requestAddTile
         */
        default void requestAddTile(
                int callingUid,
                @NonNull ComponentName componentName,
                @NonNull CharSequence appName,
                @NonNull CharSequence label,
                @NonNull Icon icon,
                @NonNull IAddTileResultCallback callback) {}

        /**
         * @see IStatusBar#cancelRequestAddTile
         */
        default void cancelRequestAddTile(@NonNull String packageName) {}

        /** @see IStatusBar#updateMediaTapToTransferSenderDisplay */
        default void updateMediaTapToTransferSenderDisplay(
                @StatusBarManager.MediaTransferSenderState int displayState,
                @NonNull MediaRoute2Info routeInfo,
                @Nullable IUndoMediaTransferCallback undoCallback) {}

        /** @see IStatusBar#updateMediaTapToTransferReceiverDisplay */
        default void updateMediaTapToTransferReceiverDisplay(
                @StatusBarManager.MediaTransferReceiverState int displayState,
                @NonNull MediaRoute2Info routeInfo,
                @Nullable Icon appIcon,
                @Nullable CharSequence appName) {}

        /**
         * @see IStatusBar#registerNearbyMediaDevicesProvider
         */
        default void registerNearbyMediaDevicesProvider(
                @NonNull INearbyMediaDevicesProvider provider) {}

        /**
         * @see IStatusBar#unregisterNearbyMediaDevicesProvider
         */
        default void unregisterNearbyMediaDevicesProvider(
                @NonNull INearbyMediaDevicesProvider provider) {}

        /**
         * @see IStatusBar#showRearDisplayDialog
         */
        default void showRearDisplayDialog(int currentBaseState) {}

        /**
         * @see IStatusBar#moveFocusedTaskToFullscreen
         */
        default void moveFocusedTaskToFullscreen(int displayId) {}

        /**
         * @see IStatusBar#moveFocusedTaskToStageSplit
         */
        default void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop) {}

        /**
         * @see IStatusBar#showMediaOutputSwitcher
         */
        default void showMediaOutputSwitcher(String packageName) {}

        /**
         * @see IStatusBar#confirmImmersivePrompt
         */
        default void confirmImmersivePrompt() {}

        /**
         * @see IStatusBar#immersiveModeChanged
         */
        default void immersiveModeChanged(int rootDisplayAreaId, boolean isImmersiveMode) {}

        /**
         * @see IStatusBar#enterDesktop(int)
         */
        default void enterDesktop(int displayId) {}
    }

    @VisibleForTesting
    public CommandQueue(Context context, DisplayTracker displayTracker) {
        this(context, displayTracker, null, null, null);
    }

    public CommandQueue(
            Context context,
            DisplayTracker displayTracker,
            CommandRegistry registry,
            DumpHandler dumpHandler,
            Lazy<PowerInteractor> powerInteractor
    ) {
        mDisplayTracker = displayTracker;
        mRegistry = registry;
        mDumpHandler = dumpHandler;
        mDisplayTracker.addDisplayChangeCallback(new DisplayTracker.Callback() {
            @Override
            public void onDisplayRemoved(int displayId) {
                synchronized (mLock) {
                    mDisplayDisabled.remove(displayId);
                }
                // This callback is registered with {@link #mHandler} that already posts to run on
                // main thread, so it is safe to dispatch directly.
                for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                    mCallbacks.get(i).onDisplayRemoved(displayId);
                }
            }
        }, new HandlerExecutor(mHandler));
        // We always have default display.
        setDisabled(mDisplayTracker.getDefaultDisplayId(), DISABLE_NONE, DISABLE2_NONE);
        mPowerInteractor = powerInteractor;
    }

    // TODO(b/118592525): add multi-display support if needed.
    public boolean panelsEnabled() {
        final int disabled1 = getDisabled1(mDisplayTracker.getDefaultDisplayId());
        final int disabled2 = getDisabled2(mDisplayTracker.getDefaultDisplayId());
        return (disabled1 & StatusBarManager.DISABLE_EXPAND) == 0
                && (disabled2 & StatusBarManager.DISABLE2_NOTIFICATION_SHADE) == 0;
    }

    @Override
    public void addCallback(@NonNull Callbacks callbacks) {
        mCallbacks.add(callbacks);
        // TODO(b/117478341): find a better way to pass disable flags by display.
        for (int i = 0; i < mDisplayDisabled.size(); i++) {
            int displayId = mDisplayDisabled.keyAt(i);
            int disabled1 = getDisabled1(displayId);
            int disabled2 = getDisabled2(displayId);
            callbacks.disable(displayId, disabled1, disabled2, false /* animate */);
        }
    }

    @Override
    public void removeCallback(@NonNull Callbacks callbacks) {
        mCallbacks.remove(callbacks);
    }

    public void setIcon(String slot, StatusBarIcon icon) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_ICON, OP_SET_ICON, 0,
                    new Pair<String, StatusBarIcon>(slot, icon)).sendToTarget();
        }
    }

    public void removeIcon(String slot) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_ICON, OP_REMOVE_ICON, 0, slot).sendToTarget();
        }
    }

    /**
     * Called to notify that disable flags are updated.
     * @see Callbacks#disable(int, int, int, boolean).
     */
    public void disable(int displayId, @DisableFlags int state1, @Disable2Flags int state2,
            boolean animate) {
        synchronized (mLock) {
            setDisabled(displayId, state1, state2);
            mHandler.removeMessages(MSG_DISABLE);
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = state1;
            args.argi3 = state2;
            args.argi4 = animate ? 1 : 0;
            Message msg = mHandler.obtainMessage(MSG_DISABLE, args);
            if (Looper.myLooper() == mHandler.getLooper()) {
                // If its the right looper execute immediately so hides can be handled quickly.
                mHandler.handleMessage(msg);
                msg.recycle();
            } else {
                msg.sendToTarget();
            }
        }
    }

    @Override
    public void disable(int displayId, @DisableFlags int state1, @Disable2Flags int state2) {
        disable(displayId, state1, state2, true);
    }

    /**
     * Apply current disable flags by {@link CommandQueue#disable(int, int, int, boolean)}.
     *
     * @param displayId The id of the display to notify.
     * @param animate {@code true} to show animations.
     */
    public void recomputeDisableFlags(int displayId, boolean animate) {
        // This must update holding the lock otherwise it can clobber the disabled flags set on the
        // binder thread from the disable() call
        synchronized (mLock) {
            int disabled1 = getDisabled1(displayId);
            int disabled2 = getDisabled2(displayId);
            disable(displayId, disabled1, disabled2, animate);
        }
    }

    private void setDisabled(int displayId, int disabled1, int disabled2) {
        mDisplayDisabled.put(displayId, new Pair<>(disabled1, disabled2));
    }

    private int getDisabled1(int displayId) {
        return getDisabled(displayId).first;
    }

    private int getDisabled2(int displayId) {
        return getDisabled(displayId).second;
    }

    private Pair<Integer, Integer> getDisabled(int displayId) {
        Pair<Integer, Integer> disablePair = mDisplayDisabled.get(displayId);
        if (disablePair == null) {
            disablePair = new Pair<>(DISABLE_NONE, DISABLE2_NONE);
            mDisplayDisabled.put(displayId, disablePair);
        }
        return disablePair;
    }

    public void animateExpandNotificationsPanel() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_EXPAND_NOTIFICATIONS);
            mHandler.sendEmptyMessage(MSG_EXPAND_NOTIFICATIONS);
        }
    }

    public void animateCollapsePanels() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_COLLAPSE_PANELS);
            mHandler.obtainMessage(MSG_COLLAPSE_PANELS, 0, 0).sendToTarget();
        }
    }

    public void animateCollapsePanels(int flags, boolean force) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_COLLAPSE_PANELS);
            mHandler.obtainMessage(MSG_COLLAPSE_PANELS, flags, force ? 1 : 0).sendToTarget();
        }
    }

    public void togglePanel() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_PANEL);
            mHandler.obtainMessage(MSG_TOGGLE_PANEL, 0, 0).sendToTarget();
        }
    }

    public void animateExpandSettingsPanel(String subPanel) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_EXPAND_SETTINGS);
            mHandler.obtainMessage(MSG_EXPAND_SETTINGS, subPanel).sendToTarget();
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_IME_BUTTON);
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = vis;
            args.argi3 = backDisposition;
            args.argi4 = showImeSwitcher ? 1 : 0;
            args.arg1 = token;
            Message m = mHandler.obtainMessage(MSG_SHOW_IME_BUTTON, args);
            m.sendToTarget();
        }
    }

    public void showRecentApps(boolean triggeredFromAltTab) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_RECENT_APPS);
            mHandler.obtainMessage(MSG_SHOW_RECENT_APPS, triggeredFromAltTab ? 1 : 0, 0,
                    null).sendToTarget();
        }
    }

    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_HIDE_RECENT_APPS);
            mHandler.obtainMessage(MSG_HIDE_RECENT_APPS,
                    triggeredFromAltTab ? 1 : 0, triggeredFromHomeKey ? 1 : 0,
                    null).sendToTarget();
        }
    }

    public void toggleSplitScreen() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_APP_SPLIT_SCREEN);
            mHandler.obtainMessage(MSG_TOGGLE_APP_SPLIT_SCREEN, 0, 0, null).sendToTarget();
        }
    }

    public void toggleTaskbar() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_TASKBAR);
            mHandler.obtainMessage(MSG_TOGGLE_TASKBAR, 0, 0, null).sendToTarget();
        }
    }

    public void toggleRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_RECENT_APPS);
            Message msg = mHandler.obtainMessage(MSG_TOGGLE_RECENT_APPS, 0, 0, null);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }

    public void preloadRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_PRELOAD_RECENT_APPS);
            mHandler.obtainMessage(MSG_PRELOAD_RECENT_APPS, 0, 0, null).sendToTarget();
        }
    }

    public void cancelPreloadRecentApps() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_CANCEL_PRELOAD_RECENT_APPS);
            mHandler.obtainMessage(MSG_CANCEL_PRELOAD_RECENT_APPS, 0, 0, null).sendToTarget();
        }
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_DISMISS_KEYBOARD_SHORTCUTS);
            mHandler.obtainMessage(MSG_DISMISS_KEYBOARD_SHORTCUTS).sendToTarget();
        }
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_TOGGLE_KEYBOARD_SHORTCUTS);
            mHandler.obtainMessage(MSG_TOGGLE_KEYBOARD_SHORTCUTS, deviceId, 0).sendToTarget();
        }
    }

    @Override
    public void showPictureInPictureMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_PICTURE_IN_PICTURE_MENU);
            mHandler.obtainMessage(MSG_SHOW_PICTURE_IN_PICTURE_MENU).sendToTarget();
        }
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        synchronized (mLock) {
            // don't coalesce these
            mHandler.obtainMessage(MSG_SET_WINDOW_STATE, displayId, window, state).sendToTarget();
        }
    }

    public void showScreenPinningRequest(int taskId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_SCREEN_PIN_REQUEST, taskId, 0, null)
                    .sendToTarget();
        }
    }

    @Override
    public void confirmImmersivePrompt() {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_CONFIRM_IMMERSIVE_PROMPT).sendToTarget();
        }
    }

    @Override
    public void immersiveModeChanged(int rootDisplayAreaId, boolean isImmersiveMode) {
        synchronized (mLock) {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = rootDisplayAreaId;
            args.argi2 = isImmersiveMode ? 1 : 0;
            mHandler.obtainMessage(MSG_IMMERSIVE_CHANGED, args).sendToTarget();
        }
    }

    @Override
    public void appTransitionPending(int displayId) {
        appTransitionPending(displayId, false /* forced */);
    }

    /**
     * Called to notify System UI that an application transition is pending.
     * @see Callbacks#appTransitionPending(int, boolean)
     */
    public void appTransitionPending(int displayId, boolean forced) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_APP_TRANSITION_PENDING, displayId, forced ? 1 : 0)
                    .sendToTarget();
        }
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_APP_TRANSITION_CANCELLED, displayId, 0 /* unused */)
                    .sendToTarget();
        }
    }

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration) {
        appTransitionStarting(displayId, startTime, duration, false /* forced */);
    }

    /**
     * Called to notify System UI that an application transition is starting.
     * @see Callbacks#appTransitionStarting(int, long, long, boolean).
     */
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        synchronized (mLock) {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = forced ? 1 : 0;
            args.arg1 = startTime;
            args.arg2 = duration;
            mHandler.obtainMessage(MSG_APP_TRANSITION_STARTING, args).sendToTarget();
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_APP_TRANSITION_FINISHED, displayId, 0 /* unused */)
                    .sendToTarget();
        }
    }

    public void showAssistDisclosure() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_ASSIST_DISCLOSURE);
            mHandler.obtainMessage(MSG_ASSIST_DISCLOSURE).sendToTarget();
        }
    }

    public void startAssist(Bundle args) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_START_ASSIST);
            mHandler.obtainMessage(MSG_START_ASSIST, args).sendToTarget();
        }
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        synchronized (mLock) {
            if (mPowerInteractor != null) {
                mPowerInteractor.get().onCameraLaunchGestureDetected();
            }

            mHandler.removeMessages(MSG_CAMERA_LAUNCH_GESTURE);
            mHandler.obtainMessage(MSG_CAMERA_LAUNCH_GESTURE, source, 0).sendToTarget();
        }
    }

    @Override
    public void onEmergencyActionLaunchGestureDetected() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_EMERGENCY_ACTION_LAUNCH_GESTURE);
            mHandler.obtainMessage(MSG_EMERGENCY_ACTION_LAUNCH_GESTURE).sendToTarget();
        }
    }

    @Override
    public void addQsTile(ComponentName tile) {
        if (Flags.a11yQsShortcut()) {
            addQsTileToFrontOrEnd(tile, false);
        } else {
            synchronized (mLock) {
                mHandler.obtainMessage(MSG_ADD_QS_TILE, tile).sendToTarget();
            }
        }
    }

    /**
     * Add a tile to the Quick Settings Panel
     * @param tile the ComponentName of the {@link android.service.quicksettings.TileService}
     * @param end if true, the tile will be added at the end. If false, at the beginning.
     */
    @Override
    public void addQsTileToFrontOrEnd(ComponentName tile, boolean end) {
        if (Flags.a11yQsShortcut()) {
            synchronized (mLock) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = tile;
                args.arg2 = end;
                mHandler.obtainMessage(MSG_ADD_QS_TILE, args).sendToTarget();
            }
        }
    }

    @Override
    public void remQsTile(ComponentName tile) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_REMOVE_QS_TILE, tile).sendToTarget();
        }
    }

    @Override
    public void setQsTiles(String[] tiles) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SET_QS_TILES, tiles).sendToTarget();
        }
    }

    @Override
    public void clickQsTile(ComponentName tile) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_CLICK_QS_TILE, tile).sendToTarget();
        }
    }

    @Override
    public void handleSystemKey(KeyEvent key) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_HANDLE_SYSTEM_KEY, key).sendToTarget();
        }
    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_PINNING_TOAST_ENTER_EXIT, entering).sendToTarget();
        }
    }

    @Override
    public void showPinningEscapeToast() {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_PINNING_TOAST_ESCAPE).sendToTarget();
        }
    }


    @Override
    public void showGlobalActionsMenu() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_GLOBAL_ACTIONS);
            mHandler.obtainMessage(MSG_SHOW_GLOBAL_ACTIONS).sendToTarget();
        }
    }

    @Override
    public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        mHandler.removeMessages(MSG_SET_TOP_APP_HIDES_STATUS_BAR);
        mHandler.obtainMessage(MSG_SET_TOP_APP_HIDES_STATUS_BAR, hidesStatusBar ? 1 : 0, 0)
                .sendToTarget();
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_SHOW_SHUTDOWN_UI);
            mHandler.obtainMessage(MSG_SHOW_SHUTDOWN_UI, isReboot ? 1 : 0, 0, reason)
                    .sendToTarget();
        }
    }

    @Override
    public void showWirelessChargingAnimation(int batteryLevel) {
        mHandler.removeMessages(MSG_SHOW_CHARGING_ANIMATION);
        mHandler.obtainMessage(MSG_SHOW_CHARGING_ANIMATION, batteryLevel, 0)
                .sendToTarget();
    }

    @Override
    public void onProposedRotationChanged(int rotation, boolean isValid) {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_ROTATION_PROPOSAL);
            mHandler.obtainMessage(MSG_ROTATION_PROPOSAL, rotation, isValid ? 1 : 0,
                    null).sendToTarget();
        }
    }

    @Override
    public void showAuthenticationDialog(PromptInfo promptInfo, IBiometricSysuiReceiver receiver,
            int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, long operationId, String opPackageName, long requestId) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = promptInfo;
            args.arg2 = receiver;
            args.arg3 = sensorIds;
            args.arg4 = credentialAllowed;
            args.arg5 = requireConfirmation;
            args.argi1 = userId;
            args.arg6 = opPackageName;
            args.argl1 = operationId;
            args.argl2 = requestId;
            mHandler.obtainMessage(MSG_BIOMETRIC_SHOW, args)
                    .sendToTarget();
        }
    }

    @Override
    public void showToast(int uid, String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, @Nullable ITransientNotificationCallback callback,
            int displayId) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            args.arg2 = token;
            args.arg3 = text;
            args.arg4 = windowToken;
            args.arg5 = callback;
            args.argi1 = uid;
            args.argi2 = duration;
            args.argi3 = displayId;
            mHandler.obtainMessage(MSG_SHOW_TOAST, args).sendToTarget();
        }
    }

    @Override
    public void hideToast(String packageName, IBinder token) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            args.arg2 = token;
            mHandler.obtainMessage(MSG_HIDE_TOAST, args).sendToTarget();
        }
    }

    @Override
    public void onBiometricAuthenticated(@Modality int modality) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = modality;
            mHandler.obtainMessage(MSG_BIOMETRIC_AUTHENTICATED, args).sendToTarget();
        }
    }

    @Override
    public void onBiometricHelp(@Modality int modality, String message) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = modality;
            args.arg1 = message;
            mHandler.obtainMessage(MSG_BIOMETRIC_HELP, args).sendToTarget();
        }
    }

    @Override
    public void onBiometricError(int modality, int error, int vendorCode) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = modality;
            args.argi2 = error;
            args.argi3 = vendorCode;
            mHandler.obtainMessage(MSG_BIOMETRIC_ERROR, args).sendToTarget();
        }
    }

    @Override
    public void hideAuthenticationDialog(long requestId) {
        synchronized (mLock) {
            final SomeArgs args = SomeArgs.obtain();
            args.argl1 = requestId;
            mHandler.obtainMessage(MSG_BIOMETRIC_HIDE, args).sendToTarget();
        }
    }

    @Override
    public void setBiometicContextListener(IBiometricContextListener listener) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SET_BIOMETRICS_LISTENER, listener).sendToTarget();
        }
    }

    @Override
    public void setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback callback) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SET_UDFPS_REFRESH_RATE_CALLBACK, callback).sendToTarget();
        }
    }

    @Override
    public void onDisplayReady(int displayId) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_DISPLAY_READY, displayId, 0).sendToTarget();
        }
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_RECENTS_ANIMATION_STATE_CHANGED, running ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    @Override
    public void showInattentiveSleepWarning() {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_INATTENTIVE_SLEEP_WARNING)
                    .sendToTarget();
        }
    }

    @Override
    public void dismissInattentiveSleepWarning(boolean animated) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_DISMISS_INATTENTIVE_SLEEP_WARNING, animated)
                    .sendToTarget();
        }
    }

    @Override
    public void requestMagnificationConnection(boolean connect) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_REQUEST_MAGNIFICATION_CONNECTION, connect)
                    .sendToTarget();
        }
    }

    private void handleShowImeButton(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId == INVALID_DISPLAY) return;

        if (mLastUpdatedImeDisplayId != displayId
                && mLastUpdatedImeDisplayId != INVALID_DISPLAY) {
            // Set previous NavBar's IME window status as invisible when IME
            // window switched to another display for single-session IME case.
            sendImeInvisibleStatusForPrevNavBar();
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).setImeWindowStatus(displayId, token, vis, backDisposition,
                    showImeSwitcher);
        }
        mLastUpdatedImeDisplayId = displayId;
    }

    private void sendImeInvisibleStatusForPrevNavBar() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).setImeWindowStatus(mLastUpdatedImeDisplayId,
                    null /* token */, IME_INVISIBLE, BACK_DISPOSITION_DEFAULT,
                    false /* showImeSwitcher */);
        }
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, @InsetsType int requestedVisibleTypes, String packageName,
            LetterboxDetails[] letterboxDetails) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = appearance;
            args.argi3 = navbarColorManagedByIme ? 1 : 0;
            args.arg1 = appearanceRegions;
            args.argi4 = behavior;
            args.argi5 = requestedVisibleTypes;
            args.arg3 = packageName;
            args.arg4 = letterboxDetails;
            mHandler.obtainMessage(MSG_SYSTEM_BAR_CHANGED, args).sendToTarget();
        }
    }

    @Override
    public void showTransient(int displayId, int types, boolean isGestureOnSystemBar) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = types;
            args.argi3 = isGestureOnSystemBar ? 1 : 0;
            mHandler.obtainMessage(MSG_SHOW_TRANSIENT, args).sendToTarget();
        }
    }

    @Override
    public void abortTransient(int displayId, int types) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = types;
            mHandler.obtainMessage(MSG_ABORT_TRANSIENT, args).sendToTarget();
        }
    }

    @Override
    public void startTracing() {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_TRACING_STATE_CHANGED, true).sendToTarget();
        }
    }

    @Override
    public void stopTracing() {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_TRACING_STATE_CHANGED, false).sendToTarget();
        }
    }

    @Override
    public void suppressAmbientDisplay(boolean suppress) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SUPPRESS_AMBIENT_DISPLAY, suppress).sendToTarget();
        }
    }

    @Override
    public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SET_NAVIGATION_BAR_LUMA_SAMPLING_ENABLED, displayId,
                    enable ? 1 : 0).sendToTarget();
        }
    }

    @Override
    public void passThroughShellCommand(String[] args, ParcelFileDescriptor pfd) {
        final FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
        final PrintWriter pw = new PrintWriter(fos);
        // This is mimicking Binder#dumpAsync, but on this side of the binder. Might be possible
        // to just throw this work onto the handler just like the other messages
        Thread thr = new Thread("Sysui.passThroughShellCommand") {
            public void run() {
                try {
                    if (mRegistry == null) {
                        return;
                    }

                    // Registry blocks this thread until finished
                    mRegistry.onShellCommand(pw, args);
                } finally {
                    pw.flush();
                    try {
                        // Close the file descriptor so the TransferPipe finishes its thread
                        pfd.close();
                    } catch (Exception e) {
                    }
                }
            }
        };
        thr.start();
    }

    @Override
    public void dumpProto(String[] args, ParcelFileDescriptor pfd) {
        final FileDescriptor fd = pfd.getFileDescriptor();
        // This is mimicking Binder#dumpAsync, but on this side of the binder. Might be possible
        // to just throw this work onto the handler just like the other messages
        Thread thr = new Thread("Sysui.dumpProto") {
            public void run() {
                try {
                    if (mDumpHandler == null) {
                        return;
                    }
                    // We won't be using the PrintWriter.
                    OutputStream o = new OutputStream() {
                        @Override
                        public void write(int b) {}
                    };
                    mDumpHandler.dump(fd, new PrintWriter(o), args);
                } finally {
                    try {
                        // Close the file descriptor so the TransferPipe finishes its thread
                        pfd.close();
                    } catch (Exception e) {
                    }
                }
            }
        };
        thr.start();
    }

    @Override
    public void runGcForTest() {
        // Gc sysui
        GcUtils.runGcAndFinalizersSync();
    }

    @Override
    public void requestTileServiceListeningState(@NonNull ComponentName componentName) {
        mHandler.obtainMessage(MSG_TILE_SERVICE_REQUEST_LISTENING_STATE, componentName)
                .sendToTarget();
    }

    @Override
    public void showRearDisplayDialog(int currentBaseState) {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHOW_REAR_DISPLAY_DIALOG, currentBaseState).sendToTarget();
        }
    }

    @Override
    public void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop) {
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = displayId;
            args.argi2 = leftOrTop ? 1 : 0;
            mHandler.obtainMessage(MSG_MOVE_FOCUSED_TASK_TO_STAGE_SPLIT,
                    args).sendToTarget();
        }
    }

    @Override
    public void showMediaOutputSwitcher(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("Call only allowed from system server.");
        }
        synchronized (mLock) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            mHandler.obtainMessage(MSG_SHOW_MEDIA_OUTPUT_SWITCHER, args).sendToTarget();
        }
    }

    @Override
    public void requestAddTile(
            int callingUid,
            @NonNull ComponentName componentName,
            @NonNull CharSequence appName,
            @NonNull CharSequence label,
            @NonNull Icon icon,
            @NonNull IAddTileResultCallback callback
    ) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = componentName;
        args.arg2 = appName;
        args.arg3 = label;
        args.arg4 = icon;
        args.arg5 = callback;
        args.arg6 = callingUid;
        mHandler.obtainMessage(MSG_TILE_SERVICE_REQUEST_ADD, args).sendToTarget();
    }

    @Override
    public void cancelRequestAddTile(@NonNull String s) throws RemoteException {
        mHandler.obtainMessage(MSG_TILE_SERVICE_REQUEST_CANCEL, s).sendToTarget();
    }

    @Override
    public void updateMediaTapToTransferSenderDisplay(
            @StatusBarManager.MediaTransferSenderState int displayState,
            MediaRoute2Info routeInfo,
            IUndoMediaTransferCallback undoCallback
    ) throws RemoteException {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = displayState;
        args.arg2 = routeInfo;
        args.arg3 = undoCallback;
        mHandler.obtainMessage(MSG_MEDIA_TRANSFER_SENDER_STATE, args).sendToTarget();
    }

    @Override
    public void updateMediaTapToTransferReceiverDisplay(
            int displayState,
            @NonNull MediaRoute2Info routeInfo,
            @Nullable Icon appIcon,
            @Nullable CharSequence appName) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = displayState;
        args.arg2 = routeInfo;
        args.arg3 = appIcon;
        args.arg4 = appName;
        mHandler.obtainMessage(MSG_MEDIA_TRANSFER_RECEIVER_STATE, args).sendToTarget();
    }

    @Override
    public void registerNearbyMediaDevicesProvider(@NonNull INearbyMediaDevicesProvider provider) {
        mHandler.obtainMessage(MSG_REGISTER_NEARBY_MEDIA_DEVICE_PROVIDER, provider).sendToTarget();
    }

    @Override
    public void unregisterNearbyMediaDevicesProvider(
            @NonNull INearbyMediaDevicesProvider provider) {
        mHandler.obtainMessage(MSG_UNREGISTER_NEARBY_MEDIA_DEVICE_PROVIDER, provider)
                .sendToTarget();
    }

    @Override
    public void moveFocusedTaskToFullscreen(int displayId) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = displayId;
        mHandler.obtainMessage(MSG_MOVE_FOCUSED_TASK_TO_FULLSCREEN, args).sendToTarget();
    }

    @Override
    public void enterDesktop(int displayId) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = displayId;
        mHandler.obtainMessage(MSG_ENTER_DESKTOP, args).sendToTarget();
    }

    private final class H extends Handler {
        private H(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {
            final int what = msg.what & MSG_MASK;
            switch (what) {
                case MSG_ICON: {
                    switch (msg.arg1) {
                        case OP_SET_ICON: {
                            Pair<String, StatusBarIcon> p = (Pair<String, StatusBarIcon>) msg.obj;
                            for (int i = 0; i < mCallbacks.size(); i++) {
                                mCallbacks.get(i).setIcon(p.first, p.second);
                            }
                            break;
                        }
                        case OP_REMOVE_ICON:
                            for (int i = 0; i < mCallbacks.size(); i++) {
                                mCallbacks.get(i).removeIcon((String) msg.obj);
                            }
                            break;
                    }
                    break;
                }
                case MSG_DISABLE:
                    SomeArgs args = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).disable(args.argi1, args.argi2, args.argi3,
                                args.argi4 != 0 /* animate */);
                    }
                    break;
                case MSG_EXPAND_NOTIFICATIONS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateExpandNotificationsPanel();
                    }
                    break;
                case MSG_COLLAPSE_PANELS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateCollapsePanels(msg.arg1, msg.arg2 != 0);
                    }
                    break;
                case MSG_TOGGLE_PANEL:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).togglePanel();
                    }
                    break;
                case MSG_EXPAND_SETTINGS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).animateExpandSettingsPanel((String) msg.obj);
                    }
                    break;
                case MSG_SHOW_IME_BUTTON:
                    args = (SomeArgs) msg.obj;
                    handleShowImeButton(args.argi1 /* displayId */, (IBinder) args.arg1 /* token */,
                            args.argi2 /* vis */, args.argi3 /* backDisposition */,
                            args.argi4 != 0 /* showImeSwitcher */);
                    break;
                case MSG_SHOW_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showRecentApps(msg.arg1 != 0);
                    }
                    break;
                case MSG_HIDE_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).hideRecentApps(msg.arg1 != 0, msg.arg2 != 0);
                    }
                    break;
                case MSG_TOGGLE_TASKBAR:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleTaskbar();
                    }
                    break;
                case MSG_TOGGLE_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleRecentApps();
                    }
                    break;
                case MSG_PRELOAD_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).preloadRecentApps();
                    }
                    break;
                case MSG_CANCEL_PRELOAD_RECENT_APPS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).cancelPreloadRecentApps();
                    }
                    break;
                case MSG_DISMISS_KEYBOARD_SHORTCUTS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).dismissKeyboardShortcutsMenu();
                    }
                    break;
                case MSG_TOGGLE_KEYBOARD_SHORTCUTS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleKeyboardShortcutsMenu(msg.arg1);
                    }
                    break;
                case MSG_SET_WINDOW_STATE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setWindowState(msg.arg1, msg.arg2, (int) msg.obj);
                    }
                    break;
                case MSG_SHOW_SCREEN_PIN_REQUEST:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showScreenPinningRequest(msg.arg1);
                    }
                    break;
                case MSG_APP_TRANSITION_PENDING:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionPending(msg.arg1, msg.arg2 != 0);
                    }
                    break;
                case MSG_APP_TRANSITION_CANCELLED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionCancelled(msg.arg1);
                    }
                    break;
                case MSG_APP_TRANSITION_STARTING:
                    args = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionStarting(args.argi1, (long) args.arg1,
                                (long) args.arg2, args.argi2 != 0 /* forced */);
                    }
                    break;
                case MSG_APP_TRANSITION_FINISHED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).appTransitionFinished(msg.arg1);
                    }
                    break;
                case MSG_ASSIST_DISCLOSURE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showAssistDisclosure();
                    }
                    break;
                case MSG_START_ASSIST:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).startAssist((Bundle) msg.obj);
                    }
                    break;
                case MSG_CAMERA_LAUNCH_GESTURE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onCameraLaunchGestureDetected(msg.arg1);
                    }
                    break;
                case MSG_EMERGENCY_ACTION_LAUNCH_GESTURE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onEmergencyActionLaunchGestureDetected();
                    }
                    break;
                case MSG_SHOW_PICTURE_IN_PICTURE_MENU:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showPictureInPictureMenu();
                    }
                    break;
                case MSG_ADD_QS_TILE: {
                    if (Flags.a11yQsShortcut()) {
                        SomeArgs someArgs = (SomeArgs) msg.obj;
                        for (int i = 0; i < mCallbacks.size(); i++) {
                            mCallbacks.get(i).addQsTileToFrontOrEnd(
                                    (ComponentName) someArgs.arg1, (boolean) someArgs.arg2);
                        }
                        someArgs.recycle();
                    } else {
                        for (int i = 0; i < mCallbacks.size(); i++) {
                            mCallbacks.get(i).addQsTile((ComponentName) msg.obj);
                        }
                    }
                    break;
                }
                case MSG_REMOVE_QS_TILE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).remQsTile((ComponentName) msg.obj);
                    }
                    break;
                case MSG_SET_QS_TILES:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setQsTiles((String[]) msg.obj);
                    }
                    break;
                case MSG_CLICK_QS_TILE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).clickTile((ComponentName) msg.obj);
                    }
                    break;
                case MSG_TOGGLE_APP_SPLIT_SCREEN:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).toggleSplitScreen();
                    }
                    break;
                case MSG_HANDLE_SYSTEM_KEY:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleSystemKey((KeyEvent) msg.obj);
                    }
                    break;
                case MSG_SHOW_GLOBAL_ACTIONS:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleShowGlobalActionsMenu();
                    }
                    break;
                case MSG_SHOW_SHUTDOWN_UI:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).handleShowShutdownUi(msg.arg1 != 0, (String) msg.obj);
                    }
                    break;
                case MSG_SET_TOP_APP_HIDES_STATUS_BAR:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setTopAppHidesStatusBar(msg.arg1 != 0);
                    }
                    break;
                case MSG_ROTATION_PROPOSAL:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onRotationProposal(msg.arg1, msg.arg2 != 0);
                    }
                    break;
                case MSG_BIOMETRIC_SHOW: {
                    mHandler.removeMessages(MSG_BIOMETRIC_ERROR);
                    mHandler.removeMessages(MSG_BIOMETRIC_HELP);
                    mHandler.removeMessages(MSG_BIOMETRIC_AUTHENTICATED);
                    SomeArgs someArgs = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showAuthenticationDialog(
                                (PromptInfo) someArgs.arg1,
                                (IBiometricSysuiReceiver) someArgs.arg2,
                                (int[]) someArgs.arg3 /* sensorIds */,
                                (boolean) someArgs.arg4 /* credentialAllowed */,
                                (boolean) someArgs.arg5 /* requireConfirmation */,
                                someArgs.argi1 /* userId */,
                                someArgs.argl1 /* operationId */,
                                (String) someArgs.arg6 /* opPackageName */,
                                someArgs.argl2 /* requestId */);
                    }
                    someArgs.recycle();
                    break;
                }
                case MSG_BIOMETRIC_AUTHENTICATED: {
                    SomeArgs someArgs = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onBiometricAuthenticated(someArgs.argi1 /* modality */);
                    }
                    someArgs.recycle();
                    break;
                }
                case MSG_BIOMETRIC_HELP: {
                    SomeArgs someArgs = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onBiometricHelp(
                                someArgs.argi1 /* modality */,
                                (String) someArgs.arg1 /* message */);
                    }
                    someArgs.recycle();
                    break;
                }
                case MSG_BIOMETRIC_ERROR: {
                    SomeArgs someArgs = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onBiometricError(
                                someArgs.argi1 /* modality */,
                                someArgs.argi2 /* error */,
                                someArgs.argi3 /* vendorCode */
                        );
                    }
                    someArgs.recycle();
                    break;
                }
                case MSG_BIOMETRIC_HIDE: {
                    final SomeArgs someArgs = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).hideAuthenticationDialog(someArgs.argl1 /* requestId */);
                    }
                    someArgs.recycle();
                    break;
                }
                case MSG_SET_BIOMETRICS_LISTENER:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setBiometricContextListener(
                                (IBiometricContextListener) msg.obj);
                    }
                    break;
                case MSG_SET_UDFPS_REFRESH_RATE_CALLBACK:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setUdfpsRefreshRateCallback(
                                (IUdfpsRefreshRateRequestCallback) msg.obj);
                    }
                    break;
                case MSG_SHOW_CHARGING_ANIMATION:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showWirelessChargingAnimation(msg.arg1);
                    }
                    break;
                case MSG_SHOW_PINNING_TOAST_ENTER_EXIT:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showPinningEnterExitToast((Boolean) msg.obj);
                    }
                    break;
                case MSG_SHOW_PINNING_TOAST_ESCAPE:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showPinningEscapeToast();
                    }
                    break;
                case MSG_DISPLAY_READY:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onDisplayReady(msg.arg1);
                    }
                    break;
                case MSG_RECENTS_ANIMATION_STATE_CHANGED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onRecentsAnimationStateChanged(msg.arg1 > 0);
                    }
                    break;
                case MSG_SYSTEM_BAR_CHANGED:
                    args = (SomeArgs) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onSystemBarAttributesChanged(args.argi1, args.argi2,
                                (AppearanceRegion[]) args.arg1, args.argi3 == 1, args.argi4,
                                args.argi5, (String) args.arg3, (LetterboxDetails[]) args.arg4);
                    }
                    args.recycle();
                    break;
                case MSG_SHOW_TRANSIENT: {
                    args = (SomeArgs) msg.obj;
                    final int displayId = args.argi1;
                    final int types = args.argi2;
                    final boolean isGestureOnSystemBar = args.argi3 != 0;
                    args.recycle();
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showTransient(displayId, types, isGestureOnSystemBar);
                    }
                    break;
                }
                case MSG_ABORT_TRANSIENT: {
                    args = (SomeArgs) msg.obj;
                    final int displayId = args.argi1;
                    final int types = args.argi2;
                    args.recycle();
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).abortTransient(displayId, types);
                    }
                    break;
                }
                case MSG_SHOW_INATTENTIVE_SLEEP_WARNING:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showInattentiveSleepWarning();
                    }
                    break;
                case MSG_DISMISS_INATTENTIVE_SLEEP_WARNING:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).dismissInattentiveSleepWarning((Boolean) msg.obj);
                    }
                    break;
                case MSG_SHOW_TOAST: {
                    args = (SomeArgs) msg.obj;
                    String packageName = (String) args.arg1;
                    IBinder token = (IBinder) args.arg2;
                    CharSequence text = (CharSequence) args.arg3;
                    IBinder windowToken = (IBinder) args.arg4;
                    ITransientNotificationCallback callback =
                            (ITransientNotificationCallback) args.arg5;
                    int uid = args.argi1;
                    int duration = args.argi2;
                    int displayId = args.argi3;
                    for (Callbacks callbacks : mCallbacks) {
                        callbacks.showToast(uid, packageName, token, text, windowToken, duration,
                                callback, displayId);
                    }
                    break;
                }
                case MSG_HIDE_TOAST: {
                    args = (SomeArgs) msg.obj;
                    String packageName = (String) args.arg1;
                    IBinder token = (IBinder) args.arg2;
                    for (Callbacks callbacks : mCallbacks) {
                        callbacks.hideToast(packageName, token);
                    }
                    break;
                }
                case MSG_TRACING_STATE_CHANGED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).onTracingStateChanged((Boolean) msg.obj);
                    }
                    break;
                case MSG_SUPPRESS_AMBIENT_DISPLAY:
                    for (Callbacks callbacks: mCallbacks) {
                        callbacks.suppressAmbientDisplay((boolean) msg.obj);
                    }
                    break;
                case MSG_REQUEST_MAGNIFICATION_CONNECTION:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).requestMagnificationConnection((Boolean) msg.obj);
                    }
                    break;
                case MSG_SET_NAVIGATION_BAR_LUMA_SAMPLING_ENABLED:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).setNavigationBarLumaSamplingEnabled(msg.arg1,
                                msg.arg2 != 0);
                    }
                    break;
                case MSG_TILE_SERVICE_REQUEST_ADD:
                    args = (SomeArgs) msg.obj;
                    ComponentName componentName = (ComponentName) args.arg1;
                    CharSequence appName = (CharSequence) args.arg2;
                    CharSequence label = (CharSequence) args.arg3;
                    Icon icon = (Icon) args.arg4;
                    IAddTileResultCallback callback = (IAddTileResultCallback) args.arg5;
                    int callingUid = (int) args.arg6;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).requestAddTile(callingUid,
                                componentName, appName, label, icon, callback);
                    }
                    args.recycle();
                    break;
                case MSG_TILE_SERVICE_REQUEST_CANCEL:
                    String packageName = (String) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).cancelRequestAddTile(packageName);
                    }
                    break;
                case MSG_MEDIA_TRANSFER_SENDER_STATE:
                    args = (SomeArgs) msg.obj;
                    int displayState = (int) args.arg1;
                    MediaRoute2Info routeInfo = (MediaRoute2Info) args.arg2;
                    IUndoMediaTransferCallback undoCallback =
                            (IUndoMediaTransferCallback) args.arg3;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).updateMediaTapToTransferSenderDisplay(
                                displayState, routeInfo, undoCallback);
                    }
                    args.recycle();
                    break;
                case MSG_MEDIA_TRANSFER_RECEIVER_STATE:
                    args = (SomeArgs) msg.obj;
                    int receiverDisplayState = (int) args.arg1;
                    MediaRoute2Info receiverRouteInfo = (MediaRoute2Info) args.arg2;
                    Icon appIcon = (Icon) args.arg3;
                    appName = (CharSequence) args.arg4;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).updateMediaTapToTransferReceiverDisplay(
                                receiverDisplayState, receiverRouteInfo, appIcon, appName);
                    }
                    args.recycle();
                    break;
                case MSG_REGISTER_NEARBY_MEDIA_DEVICE_PROVIDER:
                    INearbyMediaDevicesProvider provider = (INearbyMediaDevicesProvider) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).registerNearbyMediaDevicesProvider(provider);
                    }
                    break;
                case MSG_UNREGISTER_NEARBY_MEDIA_DEVICE_PROVIDER:
                    provider = (INearbyMediaDevicesProvider) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).unregisterNearbyMediaDevicesProvider(provider);
                    }
                    break;
                case MSG_TILE_SERVICE_REQUEST_LISTENING_STATE:
                    ComponentName component = (ComponentName) msg.obj;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).requestTileServiceListeningState(component);
                    }
                    break;
                case MSG_SHOW_REAR_DISPLAY_DIALOG:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showRearDisplayDialog((Integer) msg.obj);
                    }
                    break;
                case MSG_MOVE_FOCUSED_TASK_TO_FULLSCREEN: {
                    args = (SomeArgs) msg.obj;
                    int displayId = args.argi1;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).moveFocusedTaskToFullscreen(displayId);
                    }
                    break;
                }
                case MSG_MOVE_FOCUSED_TASK_TO_STAGE_SPLIT: {
                    args = (SomeArgs) msg.obj;
                    int displayId = args.argi1;
                    boolean leftOrTop = args.argi2 != 0;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).moveFocusedTaskToStageSplit(displayId, leftOrTop);
                    }
                    break;
                }
                case MSG_SHOW_MEDIA_OUTPUT_SWITCHER:
                    args = (SomeArgs) msg.obj;
                    String clientPackageName = (String) args.arg1;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).showMediaOutputSwitcher(clientPackageName);
                    }
                    break;
                case MSG_CONFIRM_IMMERSIVE_PROMPT:
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).confirmImmersivePrompt();
                    }
                    break;
                case MSG_IMMERSIVE_CHANGED:
                    args = (SomeArgs) msg.obj;
                    int rootDisplayAreaId = args.argi1;
                    boolean isImmersiveMode = args.argi2 != 0;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).immersiveModeChanged(rootDisplayAreaId, isImmersiveMode);
                    }
                    break;
                case MSG_ENTER_DESKTOP: {
                    args = (SomeArgs) msg.obj;
                    int displayId = args.argi1;
                    for (int i = 0; i < mCallbacks.size(); i++) {
                        mCallbacks.get(i).enterDesktop(displayId);
                    }
                    break;
                }
            }
        }
    }
}
