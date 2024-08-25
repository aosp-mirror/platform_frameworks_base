/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

import static com.android.systemui.accessibility.AccessibilityLogger.MagnificationSettingsEvent;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IMagnificationConnection;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.window.InputTransferToken;

import androidx.annotation.NonNull;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.Flags;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.inject.Inject;

/**
 * Class to handle the interaction with
 * {@link com.android.server.accessibility.AccessibilityManagerService}. It invokes
 * {@link AccessibilityManager#setMagnificationConnection(IMagnificationConnection)}
 * when {@code IStatusBar#requestWindowMagnificationConnection(boolean)} is called.
 */
@SysUISingleton
public class MagnificationImpl implements Magnification, CommandQueue.Callbacks {
    private static final String TAG = "Magnification";

    @VisibleForTesting static final int DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS = 300;
    private static final int MSG_SHOW_MAGNIFICATION_BUTTON_INTERNAL = 1;

    private final ModeSwitchesController mModeSwitchesController;
    private final Handler mHandler;
    private final Executor mExecutor;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;
    private final OverviewProxyService mOverviewProxyService;
    private final DisplayTracker mDisplayTracker;
    private final AccessibilityLogger mA11yLogger;

    private MagnificationConnectionImpl mMagnificationConnectionImpl;
    private SysUiState mSysUiState;

    @VisibleForTesting
    SparseArray<SparseArray<Float>> mUsersScales = new SparseArray();

    private static class WindowMagnificationControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final WindowMagnifierCallback mWindowMagnifierCallback;
        private final SysUiState mSysUiState;
        private final SecureSettings mSecureSettings;
        private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;

        WindowMagnificationControllerSupplier(Context context, Handler handler,
                WindowMagnifierCallback windowMagnifierCallback,
                DisplayManager displayManager, SysUiState sysUiState,
                SecureSettings secureSettings,
                ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mWindowMagnifierCallback = windowMagnifierCallback;
            mSysUiState = sysUiState;
            mSecureSettings = secureSettings;
            mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;
        }

        @Override
        protected WindowMagnificationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    Flags.createWindowlessWindowMagnifier()
                            ? TYPE_ACCESSIBILITY_OVERLAY
                            : TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                    /* options */ null);
            windowContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);

            Supplier<SurfaceControlViewHost> scvhSupplier = () ->
                    Flags.createWindowlessWindowMagnifier() ? new SurfaceControlViewHost(mContext,
                            mContext.getDisplay(), new InputTransferToken(), TAG) : null;

            return new WindowMagnificationController(
                    windowContext,
                    mHandler,
                    new WindowMagnificationAnimationController(windowContext),
                    /* mirrorWindowControl= */ null,
                    new SurfaceControl.Transaction(),
                    mWindowMagnifierCallback,
                    mSysUiState,
                    mSecureSettings,
                    scvhSupplier,
                    new SfVsyncFrameCallbackProvider(),
                    WindowManagerGlobal::getWindowSession,
                    mViewCaptureAwareWindowManager);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<WindowMagnificationController> mWindowMagnificationControllerSupplier;

    private static class FullscreenMagnificationControllerSupplier extends
            DisplayIdIndexSupplier<FullscreenMagnificationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final Executor mExecutor;
        private final DisplayManager mDisplayManager;
        private final IWindowManager mIWindowManager;

        FullscreenMagnificationControllerSupplier(Context context,
                DisplayManager displayManager,
                Handler handler,
                Executor executor, IWindowManager iWindowManager) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mExecutor = executor;
            mDisplayManager = displayManager;
            mIWindowManager = iWindowManager;
        }

        @Override
        protected FullscreenMagnificationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_OVERLAY, /* options */ null);
            Supplier<SurfaceControlViewHost> scvhSupplier = () -> new SurfaceControlViewHost(
                    mContext, mContext.getDisplay(), new InputTransferToken(), TAG);
            windowContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);
            return new FullscreenMagnificationController(
                    windowContext,
                    mHandler,
                    mExecutor,
                    mDisplayManager,
                    windowContext.getSystemService(AccessibilityManager.class),
                    windowContext.getSystemService(WindowManager.class),
                    mIWindowManager,
                    scvhSupplier);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<FullscreenMagnificationController>
            mFullscreenMagnificationControllerSupplier;

    private static class SettingsSupplier extends
            DisplayIdIndexSupplier<MagnificationSettingsController> {

        private final Context mContext;
        private final MagnificationSettingsController.Callback mSettingsControllerCallback;
        private final SecureSettings mSecureSettings;
        private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;

        SettingsSupplier(Context context,
                MagnificationSettingsController.Callback settingsControllerCallback,
                DisplayManager displayManager,
                SecureSettings secureSettings,
                ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
            super(displayManager);
            mContext = context;
            mSettingsControllerCallback = settingsControllerCallback;
            mSecureSettings = secureSettings;
            mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;
        }

        @Override
        protected MagnificationSettingsController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_OVERLAY, /* options */ null);
            windowContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);
            return new MagnificationSettingsController(
                    windowContext,
                    new SfVsyncFrameCallbackProvider(),
                    mSettingsControllerCallback,
                    mSecureSettings,
                    mViewCaptureAwareWindowManager);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<MagnificationSettingsController> mMagnificationSettingsSupplier;

    @Inject
    public MagnificationImpl(Context context,
            @Main Handler mainHandler, @Main Executor executor,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            SysUiState sysUiState, OverviewProxyService overviewProxyService,
            SecureSettings secureSettings, DisplayTracker displayTracker,
            DisplayManager displayManager, AccessibilityLogger a11yLogger,
            IWindowManager iWindowManager, AccessibilityManager accessibilityManager,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
        this(context, mainHandler.getLooper(), executor, commandQueue,
                modeSwitchesController, sysUiState, overviewProxyService, secureSettings,
                displayTracker, displayManager, a11yLogger, iWindowManager, accessibilityManager,
                viewCaptureAwareWindowManager);
    }

    @VisibleForTesting
    public MagnificationImpl(Context context, Looper looper, @Main Executor executor,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            SysUiState sysUiState, OverviewProxyService overviewProxyService,
            SecureSettings secureSettings, DisplayTracker displayTracker,
            DisplayManager displayManager, AccessibilityLogger a11yLogger,
            IWindowManager iWindowManager,
            AccessibilityManager accessibilityManager,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.what == MSG_SHOW_MAGNIFICATION_BUTTON_INTERNAL) {
                    showMagnificationButtonInternal(msg.arg1, msg.arg2);
                }
            }
        };
        mExecutor = executor;
        mAccessibilityManager = accessibilityManager;
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        mSysUiState = sysUiState;
        mOverviewProxyService = overviewProxyService;
        mDisplayTracker = displayTracker;
        mA11yLogger = a11yLogger;
        mWindowMagnificationControllerSupplier = new WindowMagnificationControllerSupplier(context,
                mHandler, mWindowMagnifierCallback,
                displayManager, sysUiState, secureSettings, viewCaptureAwareWindowManager);
        mFullscreenMagnificationControllerSupplier = new FullscreenMagnificationControllerSupplier(
                context, displayManager, mHandler, mExecutor, iWindowManager);
        mMagnificationSettingsSupplier = new SettingsSupplier(context,
                mMagnificationSettingsControllerCallback, displayManager, secureSettings,
                viewCaptureAwareWindowManager);

        mModeSwitchesController.setClickListenerDelegate(
                displayId -> mHandler.post(() -> {
                    toggleSettingsPanelVisibility(displayId);
                }));
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mOverviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean isConnected) {
                if (isConnected) {
                    updateSysUiStateFlag();
                }
            }
        });
    }

    private void updateSysUiStateFlag() {
        //TODO(b/187510533): support multi-display once SysuiState supports it.
        final WindowMagnificationController controller =
                mWindowMagnificationControllerSupplier.valueAt(
                        mDisplayTracker.getDefaultDisplayId());
        if (controller != null) {
            controller.updateSysUIStateFlag();
        } else {
            // The instance is initialized when there is an IPC request. Considering
            // self-crash cases, we need to reset the flag in such situation.
            mSysUiState.setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, false)
                    .commitUpdate(mDisplayTracker.getDefaultDisplayId());
        }
    }

    @Override
    @MainThread
    public void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.enableWindowMagnification(scale, centerX, centerY,
                    magnificationFrameOffsetRatioX, magnificationFrameOffsetRatioY, callback);
        }
    }

    @Override
    @MainThread
    public void setScaleForWindowMagnification(int displayId, float scale) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setScale(scale);
        }
    }

    @Override
    @MainThread
    public void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        final WindowMagnificationController windowMagnificationcontroller =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationcontroller != null) {
            windowMagnificationcontroller.moveWindowMagnifier(offsetX, offsetY);
        }
    }

    @Override
    @MainThread
    public void moveWindowMagnifierToPosition(int displayId, float positionX, float positionY,
            IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.moveWindowMagnifierToPosition(positionX, positionY,
                    callback);
        }
    }

    @Override
    @MainThread
    public void disableWindowMagnification(int displayId,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.deleteWindowMagnification(callback);
        }
    }

    @Override
    @MainThread
    public void onFullscreenMagnificationActivationChanged(int displayId, boolean activated) {
        final FullscreenMagnificationController fullscreenMagnificationController =
                mFullscreenMagnificationControllerSupplier.get(displayId);
        if (fullscreenMagnificationController != null) {
            fullscreenMagnificationController.onFullscreenMagnificationActivationChanged(activated);
        }
    }

    @MainThread
    void updateSettingsButtonStatus(int displayId,
            @WindowMagnificationSettings.MagnificationSize int index) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.updateSettingsButtonStatusOnRestore(index);
        }
    }

    @MainThread
    void toggleSettingsPanelVisibility(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.toggleSettingsPanelVisibility();
        }
    }

    @Override
    @MainThread
    public void hideMagnificationSettingsPanel(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            magnificationSettingsController.closeMagnificationSettings();
        }
    }

    boolean isMagnificationSettingsPanelShowing(int displayId) {
        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        if (magnificationSettingsController != null) {
            return magnificationSettingsController.isMagnificationSettingsShowing();
        }
        return false;
    }

    @Override
    @MainThread
    public void showMagnificationButton(int displayId, int magnificationMode) {
        if (mHandler.hasMessages(MSG_SHOW_MAGNIFICATION_BUTTON_INTERNAL)) {
            return;
        }
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(
                        MSG_SHOW_MAGNIFICATION_BUTTON_INTERNAL, displayId, magnificationMode),
                DELAY_SHOW_MAGNIFICATION_TIMEOUT_MS);
    }

    @MainThread
    private void showMagnificationButtonInternal(int displayId, int magnificationMode) {
        // not to show mode switch button if settings panel is already showing to
        // prevent settings panel be covered by the button.
        if (isMagnificationSettingsPanelShowing(displayId)) {
            return;
        }
        mModeSwitchesController.showButton(displayId, magnificationMode);
    }

    @Override
    @MainThread
    public void removeMagnificationButton(int displayId) {
        mHandler.removeMessages(MSG_SHOW_MAGNIFICATION_BUTTON_INTERNAL);
        mModeSwitchesController.removeButton(displayId);
    }

    @Override
    @MainThread
    public void setUserMagnificationScale(int userId, int displayId, float scale) {
        SparseArray<Float> scales = mUsersScales.get(userId);
        if (scales == null) {
            scales = new SparseArray<>();
            mUsersScales.put(userId, scales);
        }
        if (scales.contains(displayId) && scales.get(displayId) == scale) {
            return;
        }
        scales.put(displayId, scale);

        final MagnificationSettingsController magnificationSettingsController =
                mMagnificationSettingsSupplier.get(displayId);
        magnificationSettingsController.setMagnificationScale(scale);
    }

    @VisibleForTesting
    final WindowMagnifierCallback mWindowMagnifierCallback = new WindowMagnifierCallback() {
        @Override
        public void onWindowMagnifierBoundsRestored(int displayId, int index) {
            mHandler.post(() -> updateSettingsButtonStatus(displayId, index));
        }

        @Override
        public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
            }
        }

        @Override
        public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onSourceBoundsChanged(displayId, sourceBounds);
            }
        }

        @Override
        public void onPerformScaleAction(int displayId, float scale, boolean updatePersistence) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onPerformScaleAction(
                        displayId, scale, updatePersistence);
            }
        }

        @Override
        public void onAccessibilityActionPerformed(int displayId) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onAccessibilityActionPerformed(displayId);
            }
        }

        @Override
        public void onMove(int displayId) {
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onMove(displayId);
            }
        }

        @Override
        public void onClickSettingsButton(int displayId) {
            mHandler.post(() -> {
                toggleSettingsPanelVisibility(displayId);
            });
        }
    };

    @VisibleForTesting
    final MagnificationSettingsController.Callback mMagnificationSettingsControllerCallback =
            new MagnificationSettingsController.Callback() {
                @Override
                public void onSetMagnifierSize(int displayId, int index) {
                    mHandler.post(() -> onSetMagnifierSizeInternal(displayId, index));
                    mA11yLogger.logWithPosition(
                            MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED,
                            index
                    );
                }

                @Override
                public void onSetDiagonalScrolling(int displayId, boolean enable) {
                    mHandler.post(() -> onSetDiagonalScrollingInternal(displayId, enable));
                }

                @Override
                public void onEditMagnifierSizeMode(int displayId, boolean enable) {
                    mHandler.post(() -> onEditMagnifierSizeModeInternal(displayId, enable));
                    mA11yLogger.log(enable
                            ?
                            MagnificationSettingsEvent
                                    .MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED
                            : MagnificationSettingsEvent
                                    .MAGNIFICATION_SETTINGS_SIZE_EDITING_DEACTIVATED);
                }

                @Override
                public void onMagnifierScale(int displayId, float scale,
                        boolean updatePersistence) {
                    if (mMagnificationConnectionImpl != null) {
                        mMagnificationConnectionImpl.onPerformScaleAction(
                                displayId, scale, updatePersistence);
                    }
                    mA11yLogger.logThrottled(
                            MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_ZOOM_SLIDER_CHANGED
                    );
                }

                @Override
                public void onModeSwitch(int displayId, int newMode) {
                    mHandler.post(() -> onModeSwitchInternal(displayId, newMode));
                }

                @Override
                public void onSettingsPanelVisibilityChanged(int displayId, boolean shown) {
                    mHandler.post(() -> onSettingsPanelVisibilityChangedInternal(displayId, shown));
                }
            };

    @MainThread
    private void onSetMagnifierSizeInternal(int displayId, int index) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.changeMagnificationSize(index);
        }
    }

    @MainThread
    private void onSetDiagonalScrollingInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            windowMagnificationController.setDiagonalScrolling(enable);
        }
    }

    @MainThread
    private void onEditMagnifierSizeModeInternal(int displayId, boolean enable) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null && windowMagnificationController.isActivated()) {
            windowMagnificationController.setEditMagnifierSizeMode(enable);
        }
    }

    @MainThread
    private void onModeSwitchInternal(int displayId, int newMode) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        final boolean isWindowMagnifierActivated = windowMagnificationController.isActivated();
        final boolean isSwitchToWindowMode = (newMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        final boolean changed = isSwitchToWindowMode ^ isWindowMagnifierActivated;
        if (changed) {
            final MagnificationSettingsController magnificationSettingsController =
                    mMagnificationSettingsSupplier.get(displayId);
            if (magnificationSettingsController != null) {
                magnificationSettingsController.closeMagnificationSettings();
            }
            if (mMagnificationConnectionImpl != null) {
                mMagnificationConnectionImpl.onChangeMagnificationMode(displayId, newMode);
            }
        }
    }

    @MainThread
    private void onSettingsPanelVisibilityChangedInternal(int displayId, boolean shown) {
        final WindowMagnificationController windowMagnificationController =
                mWindowMagnificationControllerSupplier.get(displayId);
        if (windowMagnificationController != null) {
            boolean isWindowMagnifierActivated = windowMagnificationController.isActivated();
            if (isWindowMagnifierActivated) {
                windowMagnificationController.updateDragHandleResourcesIfNeeded(shown);
            }

            if (shown) {
                mA11yLogger.logWithPosition(
                        MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_OPENED,
                        isWindowMagnifierActivated
                                ? ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                                : ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
                );
            } else {
                mA11yLogger.log(MagnificationSettingsEvent.MAGNIFICATION_SETTINGS_PANEL_CLOSED);
            }
        }
    }

    @Override
    public void requestMagnificationConnection(boolean connect) {
        if (connect) {
            setMagnificationConnection();
        } else {
            clearMagnificationConnection();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG);
        mWindowMagnificationControllerSupplier.forEach(
                magnificationController -> magnificationController.dump(pw));
    }

    private void setMagnificationConnection() {
        if (mMagnificationConnectionImpl == null) {
            mMagnificationConnectionImpl = new MagnificationConnectionImpl(this,
                    mHandler);
        }
        mAccessibilityManager.setMagnificationConnection(
                mMagnificationConnectionImpl);
    }

    private void clearMagnificationConnection() {
        mAccessibilityManager.setMagnificationConnection(null);
        //TODO: destroy controllers.
    }
}
