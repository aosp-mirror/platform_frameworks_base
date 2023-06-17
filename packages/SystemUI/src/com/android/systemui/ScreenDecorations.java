/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_LENGTH;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.util.DumpUtilsKt.asIndenting;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.graphics.common.AlphaInterpretation;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings.Secure;
import android.util.DisplayUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayCutout.BoundsPosition;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.Preconditions;
import com.android.settingslib.Utils;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.decor.CutoutDecorProviderFactory;
import com.android.systemui.decor.DebugRoundedCornerDelegate;
import com.android.systemui.decor.DecorProvider;
import com.android.systemui.decor.DecorProviderFactory;
import com.android.systemui.decor.DecorProviderKt;
import com.android.systemui.decor.FaceScanningProviderFactory;
import com.android.systemui.decor.OverlayWindow;
import com.android.systemui.decor.PrivacyDotDecorProviderFactory;
import com.android.systemui.decor.RoundedCornerDecorProviderFactory;
import com.android.systemui.decor.RoundedCornerResDelegateImpl;
import com.android.systemui.log.ScreenDecorationsLogger;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.ThreadFactory;
import com.android.systemui.util.settings.SecureSettings;

import kotlin.Pair;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
@SysUISingleton
public class ScreenDecorations implements CoreStartable, Dumpable {
    private static final boolean DEBUG_LOGGING = false;
    private static final String TAG = "ScreenDecorations";

    // Provide a way for factory to disable ScreenDecorations to run the Display tests.
    private static final boolean DEBUG_DISABLE_SCREEN_DECORATIONS =
            SystemProperties.getBoolean("debug.disable_screen_decorations", false);
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);
    private boolean mDebug = DEBUG_SCREENSHOT_ROUNDED_CORNERS;
    private int mDebugColor = Color.RED;

    private static final int[] DISPLAY_CUTOUT_IDS = {
            R.id.display_cutout,
            R.id.display_cutout_left,
            R.id.display_cutout_right,
            R.id.display_cutout_bottom
    };
    private final ScreenDecorationsLogger mLogger;

    private final AuthController mAuthController;

    private DisplayTracker mDisplayTracker;
    @VisibleForTesting
    protected boolean mIsRegistered;
    private final Context mContext;
    private final Executor mMainExecutor;
    private final SecureSettings mSecureSettings;
    @VisibleForTesting
    DisplayTracker.Callback mDisplayListener;
    private CameraAvailabilityListener mCameraListener;
    private final UserTracker mUserTracker;
    private final PrivacyDotViewController mDotViewController;
    private final ThreadFactory mThreadFactory;
    private final DecorProviderFactory mDotFactory;
    private final FaceScanningProviderFactory mFaceScanningFactory;
    public final int mFaceScanningViewId;

    @VisibleForTesting
    protected RoundedCornerResDelegateImpl mRoundedCornerResDelegate;
    @VisibleForTesting
    protected DecorProviderFactory mRoundedCornerFactory;
    @VisibleForTesting
    protected DebugRoundedCornerDelegate mDebugRoundedCornerDelegate =
            new DebugRoundedCornerDelegate();
    protected DecorProviderFactory mDebugRoundedCornerFactory;
    private CutoutDecorProviderFactory mCutoutFactory;
    private int mProviderRefreshToken = 0;
    @VisibleForTesting
    protected OverlayWindow[] mOverlays = null;
    @VisibleForTesting
    ViewGroup mScreenDecorHwcWindow;
    @VisibleForTesting
    ScreenDecorHwcLayer mScreenDecorHwcLayer;
    private WindowManager mWindowManager;
    private int mRotation;
    private SettingObserver mColorInversionSetting;
    @Nullable
    private DelayableExecutor mExecutor;
    private Handler mHandler;
    boolean mPendingConfigChange;
    @VisibleForTesting
    String mDisplayUniqueId;
    private int mTintColor = Color.BLACK;
    @VisibleForTesting
    protected DisplayDecorationSupport mHwcScreenDecorationSupport;
    private Display.Mode mDisplayMode;
    @VisibleForTesting
    protected DisplayInfo mDisplayInfo = new DisplayInfo();
    private DisplayCutout mDisplayCutout;

    @VisibleForTesting
    protected void showCameraProtection(@NonNull Path protectionPath, @NonNull Rect bounds) {
        if (mFaceScanningFactory.shouldShowFaceScanningAnim()) {
            DisplayCutoutView overlay = (DisplayCutoutView) getOverlayView(
                    mFaceScanningViewId);
            if (overlay != null) {
                mLogger.cameraProtectionBoundsForScanningOverlay(bounds);
                overlay.setProtection(protectionPath, bounds);
                overlay.enableShowProtection(true);
                updateOverlayWindowVisibilityIfViewExists(
                        overlay.findViewById(mFaceScanningViewId));
                // immediately return, bc FaceScanningOverlay also renders the camera
                // protection, so we don't need to show the camera protection in
                // mScreenDecorHwcLayer or mCutoutViews
                return;
            }
        }

        if (mScreenDecorHwcLayer != null) {
            mLogger.hwcLayerCameraProtectionBounds(bounds);
            mScreenDecorHwcLayer.setProtection(protectionPath, bounds);
            mScreenDecorHwcLayer.enableShowProtection(true);
            return;
        }

        int setProtectionCnt = 0;
        for (int id: DISPLAY_CUTOUT_IDS) {
            final View view = getOverlayView(id);
            if (!(view instanceof DisplayCutoutView)) {
                continue;
            }
            ++setProtectionCnt;
            final DisplayCutoutView dcv = (DisplayCutoutView) view;
            mLogger.dcvCameraBounds(id, bounds);
            dcv.setProtection(protectionPath, bounds);
            dcv.enableShowProtection(true);
        }
        if (setProtectionCnt == 0) {
            mLogger.cutoutViewNotInitialized();
        }
    }

    @VisibleForTesting
    protected void hideCameraProtection() {
        FaceScanningOverlay faceScanningOverlay =
                (FaceScanningOverlay) getOverlayView(mFaceScanningViewId);
        if (faceScanningOverlay != null) {
            faceScanningOverlay.setHideOverlayRunnable(() -> {
                Trace.beginSection("ScreenDecorations#hideOverlayRunnable");
                updateOverlayWindowVisibilityIfViewExists(
                        faceScanningOverlay.findViewById(mFaceScanningViewId));
                Trace.endSection();
            });
            faceScanningOverlay.enableShowProtection(false);
        }

        if (mScreenDecorHwcLayer != null) {
            mScreenDecorHwcLayer.enableShowProtection(false);
            return;
        }

        int setProtectionCnt = 0;
        for (int id: DISPLAY_CUTOUT_IDS) {
            final View view = getOverlayView(id);
            if (!(view instanceof DisplayCutoutView)) {
                continue;
            }
            ++setProtectionCnt;
            ((DisplayCutoutView) view).enableShowProtection(false);
        }
        if (setProtectionCnt == 0) {
            Log.e(TAG, "CutoutView not initialized hideCameraProtection");
        }
    }

    private CameraAvailabilityListener.CameraTransitionCallback mCameraTransitionCallback =
            new CameraAvailabilityListener.CameraTransitionCallback() {
        @Override
        public void onApplyCameraProtection(@NonNull Path protectionPath, @NonNull Rect bounds) {
            mLogger.cameraProtectionEvent("onApplyCameraProtection");
            showCameraProtection(protectionPath, bounds);
        }

        @Override
        public void onHideCameraProtection() {
            mLogger.cameraProtectionEvent("onHideCameraProtection");
            hideCameraProtection();
        }
    };

    @VisibleForTesting
    PrivacyDotViewController.ShowingListener mPrivacyDotShowingListener =
            new PrivacyDotViewController.ShowingListener() {
        @Override
        public void onPrivacyDotShown(@Nullable View v) {
            updateOverlayWindowVisibilityIfViewExists(v);
        }

        @Override
        public void onPrivacyDotHidden(@Nullable View v) {
            updateOverlayWindowVisibilityIfViewExists(v);
        }
    };

    @VisibleForTesting
    protected void updateOverlayWindowVisibilityIfViewExists(@Nullable View view) {
        if (view == null) {
            return;
        }
        mExecutor.execute(() -> {
            // We don't need to control the window visibility if rounded corners or cutout is drawn
            // on sw layer since the overlay windows are always visible in this case.
            if (mOverlays == null || !shouldOptimizeVisibility()) {
                return;
            }
            Trace.beginSection("ScreenDecorations#updateOverlayWindowVisibilityIfViewExists");
            for (final OverlayWindow overlay : mOverlays) {
                if (overlay == null) {
                    continue;
                }
                if (overlay.getView(view.getId()) != null) {
                    overlay.getRootView().setVisibility(getWindowVisibility(overlay, true));
                    Trace.endSection();
                    return;
                }
            }
            Trace.endSection();
        });
    }

    private static boolean eq(DisplayDecorationSupport a, DisplayDecorationSupport b) {
        if (a == null) return (b == null);
        if (b == null) return false;
        return a.format == b.format && a.alphaInterpretation == b.alphaInterpretation;
    }

    @Inject
    public ScreenDecorations(Context context,
            @Main Executor mainExecutor,
            SecureSettings secureSettings,
            UserTracker userTracker,
            DisplayTracker displayTracker,
            PrivacyDotViewController dotViewController,
            ThreadFactory threadFactory,
            PrivacyDotDecorProviderFactory dotFactory,
            FaceScanningProviderFactory faceScanningFactory,
            ScreenDecorationsLogger logger,
            AuthController authController) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mSecureSettings = secureSettings;
        mUserTracker = userTracker;
        mDisplayTracker = displayTracker;
        mDotViewController = dotViewController;
        mThreadFactory = threadFactory;
        mDotFactory = dotFactory;
        mFaceScanningFactory = faceScanningFactory;
        mFaceScanningViewId = com.android.systemui.R.id.face_scanning_anim;
        mLogger = logger;
        mAuthController = authController;
    }


    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onFaceSensorLocationChanged() {
            mLogger.onSensorLocationChanged();
            if (mExecutor != null) {
                mExecutor.execute(
                        () -> updateOverlayProviderViews(
                                new Integer[]{mFaceScanningViewId}));
            }
        }
    };

    @Override
    public void start() {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }
        mHandler = mThreadFactory.buildHandlerOnNewThread("ScreenDecorations");
        mExecutor = mThreadFactory.buildDelayableExecutorOnHandler(mHandler);
        mExecutor.execute(this::startOnScreenDecorationsThread);
        mDotViewController.setUiExecutor(mExecutor);
        mAuthController.addCallback(mAuthControllerCallback);
    }

    /**
     * Change the value of {@link ScreenDecorations#mDebug}. This operation is heavyweight, since
     * it requires essentially re-init-ing this screen decorations process with the debug
     * information taken into account.
     */
    @VisibleForTesting
    protected void setDebug(boolean debug) {
        if (mDebug == debug) {
            return;
        }

        mDebug = debug;
        if (!mDebug) {
            mDebugRoundedCornerDelegate.removeDebugState();
        }

        mExecutor.execute(() -> {
            // Re-trigger all of the screen decorations setup here so that the debug values
            // can be picked up
            removeAllOverlays();
            removeHwcOverlay();
            startOnScreenDecorationsThread();
            updateColorInversionDefault();
        });
    }

    private boolean isPrivacyDotEnabled() {
        return mDotFactory.getHasProviders();
    }

    @NonNull
    @VisibleForTesting
    protected List<DecorProvider> getProviders(boolean hasHwLayer) {
        List<DecorProvider> decorProviders = new ArrayList<>(mDotFactory.getProviders());
        decorProviders.addAll(mFaceScanningFactory.getProviders());
        if (!hasHwLayer) {
            if (mDebug && mDebugRoundedCornerFactory.getHasProviders()) {
                decorProviders.addAll(mDebugRoundedCornerFactory.getProviders());
            } else {
                decorProviders.addAll(mRoundedCornerFactory.getProviders());
            }
            decorProviders.addAll(mCutoutFactory.getProviders());
        }
        return decorProviders;
    }

    /**
     * Check that newProviders is the same list with decorProviders inside mOverlay.
     * @param newProviders expected comparing DecorProviders
     * @return true if same provider list
     */
    @VisibleForTesting
    boolean hasSameProviders(@NonNull List<DecorProvider> newProviders) {
        final ArrayList<Integer> overlayViewIds = new ArrayList<>();
        if (mOverlays != null) {
            for (OverlayWindow overlay : mOverlays) {
                if (overlay == null) {
                    continue;
                }
                overlayViewIds.addAll(overlay.getViewIds());
            }
        }
        if (overlayViewIds.size() != newProviders.size()) {
            return false;
        }

        for (DecorProvider provider: newProviders) {
            if (!overlayViewIds.contains(provider.getViewId())) {
                return false;
            }
        }
        return true;
    }

    private void startOnScreenDecorationsThread() {
        Trace.beginSection("ScreenDecorations#startOnScreenDecorationsThread");
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mContext.getDisplay().getDisplayInfo(mDisplayInfo);
        mRotation = mDisplayInfo.rotation;
        mDisplayMode = mDisplayInfo.getMode();
        mDisplayUniqueId = mDisplayInfo.uniqueId;
        mDisplayCutout = mDisplayInfo.displayCutout;
        mRoundedCornerResDelegate =
                new RoundedCornerResDelegateImpl(mContext.getResources(), mDisplayUniqueId);
        mRoundedCornerResDelegate.setPhysicalPixelDisplaySizeRatio(
                getPhysicalPixelDisplaySizeRatio());
        mRoundedCornerFactory = new RoundedCornerDecorProviderFactory(mRoundedCornerResDelegate);
        mDebugRoundedCornerFactory =
                new RoundedCornerDecorProviderFactory(mDebugRoundedCornerDelegate);
        mCutoutFactory = getCutoutFactory();
        mHwcScreenDecorationSupport = mContext.getDisplay().getDisplayDecorationSupport();
        updateHwLayerRoundedCornerDrawable();
        setupDecorations();
        setupCameraListener();

        mDisplayListener = new DisplayTracker.Callback() {
            @Override
            public void onDisplayChanged(int displayId) {
                mContext.getDisplay().getDisplayInfo(mDisplayInfo);
                final int newRotation = mDisplayInfo.rotation;
                final Display.Mode newDisplayMode = mDisplayInfo.getMode();
                if ((mOverlays != null || mScreenDecorHwcWindow != null)
                        && (mRotation != newRotation
                        || displayModeChanged(mDisplayMode, newDisplayMode))) {
                    // We cannot immediately update the orientation. Otherwise
                    // WindowManager is still deferring layout until it has finished dispatching
                    // the config changes, which may cause divergence between what we draw
                    // (new orientation), and where we are placed on the screen (old orientation).
                    // Instead we wait until either:
                    // - we are trying to redraw. This because WM resized our window and told us to.
                    // - the config change has been dispatched, so WM is no longer deferring layout.
                    mPendingConfigChange = true;
                    if (mRotation != newRotation) {
                        mLogger.logRotationChangeDeferred(mRotation, newRotation);
                    }
                    if (displayModeChanged(mDisplayMode, newDisplayMode)) {
                        mLogger.logDisplayModeChanged(
                                newDisplayMode.getModeId(), mDisplayMode.getModeId());
                    }

                    if (mOverlays != null) {
                        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                            if (mOverlays[i] != null) {
                                final ViewGroup overlayView = mOverlays[i].getRootView();
                                overlayView.getViewTreeObserver().addOnPreDrawListener(
                                        new RestartingPreDrawListener(
                                                overlayView, i, newRotation, newDisplayMode));
                            }
                        }
                    }

                    if (mScreenDecorHwcWindow != null) {
                        mScreenDecorHwcWindow.getViewTreeObserver().addOnPreDrawListener(
                                new RestartingPreDrawListener(
                                        mScreenDecorHwcWindow,
                                        -1, // Pass -1 for views with no specific position.
                                        newRotation, newDisplayMode));
                    }
                    if (mScreenDecorHwcLayer != null) {
                        mScreenDecorHwcLayer.pendingConfigChange = true;
                    }
                }

                final String newUniqueId = mDisplayInfo.uniqueId;
                if (!Objects.equals(newUniqueId, mDisplayUniqueId)) {
                    mDisplayUniqueId = newUniqueId;
                    final DisplayDecorationSupport newScreenDecorationSupport =
                            mContext.getDisplay().getDisplayDecorationSupport();

                    mRoundedCornerResDelegate.updateDisplayUniqueId(newUniqueId, null);

                    // When providers or the value of mSupportHwcScreenDecoration is changed,
                    // re-setup the whole screen decoration.
                    if (!hasSameProviders(getProviders(newScreenDecorationSupport != null))
                            || !eq(newScreenDecorationSupport, mHwcScreenDecorationSupport)) {
                        mHwcScreenDecorationSupport = newScreenDecorationSupport;
                        removeAllOverlays();
                        setupDecorations();
                        return;
                    }
                }
            }
        };
        mDisplayTracker.addDisplayChangeCallback(mDisplayListener, new HandlerExecutor(mHandler));
        updateConfiguration();
        Trace.endSection();
    }

    @VisibleForTesting
    @Nullable
    View getOverlayView(@IdRes int id) {
        if (mOverlays == null) {
            return null;
        }

        for (final OverlayWindow overlay : mOverlays) {
            if (overlay == null) {
                continue;
            }
            final View view = overlay.getView(id);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private void removeRedundantOverlayViews(@NonNull List<DecorProvider> decorProviders) {
        if (mOverlays == null) {
            return;
        }
        int[] viewIds = decorProviders.stream().mapToInt(DecorProvider::getViewId).toArray();
        for (final OverlayWindow overlay : mOverlays) {
            if (overlay == null) {
                continue;
            }
            overlay.removeRedundantViews(viewIds);
        }
    }

    private void removeOverlayView(@IdRes int id) {
        if (mOverlays == null) {
            return;
        }

        for (final OverlayWindow overlay : mOverlays) {
            if (overlay == null) {
                continue;
            }

            overlay.removeView(id);
        }
    }

    private void setupDecorations() {
        Trace.beginSection("ScreenDecorations#setupDecorations");
        setupDecorationsInner();
        Trace.endSection();
    }

    private void setupDecorationsInner() {
        if (hasRoundedCorners() || shouldDrawCutout() || isPrivacyDotEnabled()
                || mFaceScanningFactory.getHasProviders()) {

            List<DecorProvider> decorProviders = getProviders(mHwcScreenDecorationSupport != null);
            removeRedundantOverlayViews(decorProviders);

            if (mHwcScreenDecorationSupport != null) {
                createHwcOverlay();
            } else {
                removeHwcOverlay();
            }

            boolean[] hasCreatedOverlay = new boolean[BOUNDS_POSITION_LENGTH];
            final boolean shouldOptimizeVisibility = shouldOptimizeVisibility();
            Integer bound;
            while ((bound = DecorProviderKt.getProperBound(decorProviders)) != null) {
                hasCreatedOverlay[bound] = true;
                Pair<List<DecorProvider>, List<DecorProvider>> pair =
                        DecorProviderKt.partitionAlignedBound(decorProviders, bound);
                decorProviders = pair.getSecond();
                createOverlay(bound, pair.getFirst(), shouldOptimizeVisibility);
            }
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                if (!hasCreatedOverlay[i]) {
                    removeOverlay(i);
                }
            }

            if (shouldOptimizeVisibility) {
                mDotViewController.setShowingListener(mPrivacyDotShowingListener);
            } else {
                mDotViewController.setShowingListener(null);
            }
            final View tl, tr, bl, br;
            if ((tl = getOverlayView(R.id.privacy_dot_top_left_container)) != null
                    && (tr = getOverlayView(R.id.privacy_dot_top_right_container)) != null
                    && (bl = getOverlayView(R.id.privacy_dot_bottom_left_container)) != null
                    && (br = getOverlayView(R.id.privacy_dot_bottom_right_container)) != null) {
                // Overlays have been created, send the dots to the controller
                //TODO: need a better way to do this
                mDotViewController.initialize(tl, tr, bl, br);
            }
        } else {
            removeAllOverlays();
            removeHwcOverlay();
        }

        if (hasOverlays() || hasHwcOverlay()) {
            if (mIsRegistered) {
                return;
            }

            // Watch color inversion and invert the overlay as needed.
            if (mColorInversionSetting == null) {
                mColorInversionSetting = new SettingObserver(mSecureSettings, mHandler,
                        Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
                        mUserTracker.getUserId()) {
                    @Override
                    protected void handleValueChanged(int value, boolean observedChange) {
                        updateColorInversion(value);
                    }
                };
            }
            mColorInversionSetting.setListening(true);
            mColorInversionSetting.onChange(false);
            updateColorInversion(mColorInversionSetting.getValue());

            mUserTracker.addCallback(mUserChangedCallback, mExecutor);
            mIsRegistered = true;
        } else {
            if (mColorInversionSetting != null) {
                mColorInversionSetting.setListening(false);
            }

            mUserTracker.removeCallback(mUserChangedCallback);
            mIsRegistered = false;
        }
    }

    // For unit test to override
    protected CutoutDecorProviderFactory getCutoutFactory() {
        return new CutoutDecorProviderFactory(mContext.getResources(),
                mContext.getDisplay());
    }

    @VisibleForTesting
    boolean hasOverlays() {
        if (mOverlays == null) {
            return false;
        }

        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] != null) {
                return true;
            }
        }
        mOverlays = null;
        return false;
    }

    private void removeAllOverlays() {
        if (mOverlays == null) {
            return;
        }

        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] != null) {
                removeOverlay(i);
            }
        }
        mOverlays = null;
    }

    private void removeOverlay(@BoundsPosition int pos) {
        if (mOverlays == null || mOverlays[pos] == null) {
            return;
        }
        mWindowManager.removeViewImmediate(mOverlays[pos].getRootView());
        mOverlays[pos] = null;
    }

    @View.Visibility
    private int getWindowVisibility(@NonNull OverlayWindow overlay,
            boolean shouldOptimizeVisibility) {
        if (!shouldOptimizeVisibility) {
            // All overlays have visible views so there's no need to optimize visibility.
            // For example, the rounded corners could exist in each overlay and since the rounded
            // corners are always visible, there's no need to optimize visibility.
            return View.VISIBLE;
        }

        // Optimize if it's just the privacy dot & face scanning animation, since the privacy
        // dot and face scanning overlay aren't always visible.
        int[] ids = {
                R.id.privacy_dot_top_left_container,
                R.id.privacy_dot_top_right_container,
                R.id.privacy_dot_bottom_left_container,
                R.id.privacy_dot_bottom_right_container,
                mFaceScanningViewId
        };
        for (int id: ids) {
            final View notAlwaysVisibleViews = overlay.getView(id);
            if (notAlwaysVisibleViews != null
                    && notAlwaysVisibleViews.getVisibility() == View.VISIBLE) {
                // Overlay is VISIBLE if one the views inside this overlay is VISIBLE
                return View.VISIBLE;
            }
        }

        // Only non-visible views in this overlay, so set overlay to INVISIBLE
        return View.INVISIBLE;
    }

    private void createOverlay(
            @BoundsPosition int pos,
            @NonNull List<DecorProvider> decorProviders,
            boolean shouldOptimizeVisibility) {
        if (mOverlays == null) {
            mOverlays = new OverlayWindow[BOUNDS_POSITION_LENGTH];
        }

        if (mOverlays[pos] != null) {
            initOverlay(mOverlays[pos], decorProviders, shouldOptimizeVisibility);
            return;
        }
        mOverlays[pos] = new OverlayWindow(mContext);
        initOverlay(mOverlays[pos], decorProviders, shouldOptimizeVisibility);
        final ViewGroup overlayView = mOverlays[pos].getRootView();
        overlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        overlayView.setAlpha(0);
        overlayView.setForceDarkAllowed(false);

        mWindowManager.addView(overlayView, getWindowLayoutParams(pos));

        overlayView.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                overlayView.removeOnLayoutChangeListener(this);
                overlayView.animate()
                        .alpha(1)
                        .setDuration(1000)
                        .start();
            }
        });

        overlayView.getRootView().getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(overlayView.getRootView()));
    }

    private boolean hasHwcOverlay() {
        return mScreenDecorHwcWindow != null;
    }

    private void removeHwcOverlay() {
        if (mScreenDecorHwcWindow == null) {
            return;
        }
        mWindowManager.removeViewImmediate(mScreenDecorHwcWindow);
        mScreenDecorHwcWindow = null;
        mScreenDecorHwcLayer = null;
    }

    private void createHwcOverlay() {
        if (mScreenDecorHwcWindow != null) {
            return;
        }
        mScreenDecorHwcWindow = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.screen_decor_hwc_layer, null);
        mScreenDecorHwcLayer =
                new ScreenDecorHwcLayer(mContext, mHwcScreenDecorationSupport, mDebug);
        mScreenDecorHwcWindow.addView(mScreenDecorHwcLayer, new FrameLayout.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, Gravity.TOP | Gravity.START));
        mWindowManager.addView(mScreenDecorHwcWindow, getHwcWindowLayoutParams());
        updateHwLayerRoundedCornerExistAndSize();
        updateHwLayerRoundedCornerDrawable();
        mScreenDecorHwcWindow.getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(mScreenDecorHwcWindow));
    }

    /**
     * Init OverlayWindow with decorProviders
     */
    private void initOverlay(
            @NonNull OverlayWindow overlay,
            @NonNull List<DecorProvider> decorProviders,
            boolean shouldOptimizeVisibility) {
        if (!overlay.hasSameProviders(decorProviders)) {
            decorProviders.forEach(provider -> {
                if (overlay.getView(provider.getViewId()) != null) {
                    return;
                }
                removeOverlayView(provider.getViewId());
                overlay.addDecorProvider(provider, mRotation, mTintColor);
            });
        }
        // Use visibility of privacy dot views & face scanning view to determine the overlay's
        // visibility if the screen decoration SW layer overlay isn't persistently showing
        // (ie: rounded corners always showing in SW layer)
        overlay.getRootView().setVisibility(getWindowVisibility(overlay, shouldOptimizeVisibility));
    }

    @VisibleForTesting
    WindowManager.LayoutParams getWindowLayoutParams(@BoundsPosition int pos) {
        final WindowManager.LayoutParams lp = getWindowLayoutBaseParams();
        lp.width = getWidthLayoutParamByPos(pos);
        lp.height = getHeightLayoutParamByPos(pos);
        lp.setTitle(getWindowTitleByPos(pos));
        lp.gravity = getOverlayWindowGravity(pos);
        return lp;
    }

    private WindowManager.LayoutParams getHwcWindowLayoutParams() {
        final WindowManager.LayoutParams lp = getWindowLayoutBaseParams();
        lp.width = MATCH_PARENT;
        lp.height = MATCH_PARENT;
        lp.setTitle("ScreenDecorHwcOverlay");
        lp.gravity = Gravity.TOP | Gravity.START;
        if (!mDebug) {
            lp.setColorMode(ActivityInfo.COLOR_MODE_A8);
        }
        return lp;
    }

    private WindowManager.LayoutParams getWindowLayoutBaseParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;

        // FLAG_SLIPPERY can only be set by trusted overlays
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        if (!DEBUG_SCREENSHOT_ROUNDED_CORNERS) {
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
        }

        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0 /* types */);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        return lp;
    }

    private int getWidthLayoutParamByPos(@BoundsPosition int pos) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        return rotatedPos == BOUNDS_POSITION_TOP || rotatedPos == BOUNDS_POSITION_BOTTOM
                ? MATCH_PARENT : WRAP_CONTENT;
    }

    private int getHeightLayoutParamByPos(@BoundsPosition int pos) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        return rotatedPos == BOUNDS_POSITION_TOP || rotatedPos == BOUNDS_POSITION_BOTTOM
                ? WRAP_CONTENT : MATCH_PARENT;
    }

    private static String getWindowTitleByPos(@BoundsPosition int pos) {
        switch (pos) {
            case BOUNDS_POSITION_LEFT:
                return "ScreenDecorOverlayLeft";
            case BOUNDS_POSITION_TOP:
                return "ScreenDecorOverlay";
            case BOUNDS_POSITION_RIGHT:
                return "ScreenDecorOverlayRight";
            case BOUNDS_POSITION_BOTTOM:
                return "ScreenDecorOverlayBottom";
            default:
                throw new IllegalArgumentException("unknown bound position: " + pos);
        }
    }

    private static boolean displayModeChanged(Display.Mode oldMode, Display.Mode newMode) {
        if (oldMode == null) {
            return true;
        }

        // We purposely ignore refresh rate and id changes here, because we don't need to
        // invalidate for those, and they can trigger the refresh rate to increase
        return oldMode.getPhysicalWidth() != newMode.getPhysicalWidth()
                || oldMode.getPhysicalHeight() != newMode.getPhysicalHeight();
    }

    private int getOverlayWindowGravity(@BoundsPosition int pos) {
        final int rotated = getBoundPositionFromRotation(pos, mRotation);
        switch (rotated) {
            case BOUNDS_POSITION_TOP:
                return Gravity.TOP;
            case BOUNDS_POSITION_BOTTOM:
                return Gravity.BOTTOM;
            case BOUNDS_POSITION_LEFT:
                return Gravity.LEFT;
            case BOUNDS_POSITION_RIGHT:
                return Gravity.RIGHT;
            default:
                throw new IllegalArgumentException("unknown bound position: " + pos);
        }
    }

    @VisibleForTesting
    static int getBoundPositionFromRotation(@BoundsPosition int pos, int rotation) {
        return (pos - rotation) < 0
                ? pos - rotation + DisplayCutout.BOUNDS_POSITION_LENGTH
                : pos - rotation;
    }

    private void setupCameraListener() {
        // TODO(b/238143614) Support dual screen camera protection
        Resources res = mContext.getResources();
        boolean enabled = res.getBoolean(R.bool.config_enableDisplayCutoutProtection);
        if (enabled) {
            mCameraListener = CameraAvailabilityListener.Factory.build(mContext, mExecutor);
            mCameraListener.addTransitionCallback(mCameraTransitionCallback);
            mCameraListener.startListening();
        }
    }

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mLogger.logUserSwitched(newUser);
                    // update color inversion setting to the new user
                    mColorInversionSetting.setUserId(newUser);
                    updateColorInversion(mColorInversionSetting.getValue());
                }
            };

    /**
     * Use the current value of {@link ScreenDecorations#mColorInversionSetting} and passes it
     * to {@link ScreenDecorations#updateColorInversion}
     */
    private void updateColorInversionDefault() {
        int inversion = 0;
        if (mColorInversionSetting != null) {
            inversion = mColorInversionSetting.getValue();
        }

        updateColorInversion(inversion);
    }

    /**
     * Update the tint color of screen decoration assets. Defaults to Color.BLACK. In the case of
     * a color inversion being set, use Color.WHITE (which inverts to black).
     *
     * When {@link ScreenDecorations#mDebug} is {@code true}, this value is updated to use
     * {@link ScreenDecorations#mDebugColor}, and does not handle inversion.
     *
     * @param colorsInvertedValue if non-zero, assume that colors are inverted, and use Color.WHITE
     *                            for screen decoration tint
     */
    private void updateColorInversion(int colorsInvertedValue) {
        mTintColor = colorsInvertedValue != 0 ? Color.WHITE : Color.BLACK;
        if (mDebug) {
            mTintColor = mDebugColor;
            mDebugRoundedCornerDelegate.setColor(mTintColor);
            //TODO(b/285941724): update the hwc layer color here too (or disable it in debug mode)
        }

        updateOverlayProviderViews(new Integer[] {
                mFaceScanningViewId,
                R.id.display_cutout,
                R.id.display_cutout_left,
                R.id.display_cutout_right,
                R.id.display_cutout_bottom,
                R.id.rounded_corner_top_left,
                R.id.rounded_corner_top_right,
                R.id.rounded_corner_bottom_left,
                R.id.rounded_corner_bottom_right
        });
    }

    @VisibleForTesting
    float getPhysicalPixelDisplaySizeRatio() {
        mContext.getDisplay().getDisplayInfo(mDisplayInfo);
        final Display.Mode maxDisplayMode =
                DisplayUtils.getMaximumResolutionDisplayMode(mDisplayInfo.supportedModes);
        if (maxDisplayMode == null) {
            return 1f;
        }
        return DisplayUtils.getPhysicalPixelDisplaySizeRatio(
                maxDisplayMode.getPhysicalWidth(), maxDisplayMode.getPhysicalHeight(),
                mDisplayInfo.getNaturalWidth(), mDisplayInfo.getNaturalHeight());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }

        mExecutor.execute(() -> {
            Trace.beginSection("ScreenDecorations#onConfigurationChanged");
            int oldRotation = mRotation;
            mPendingConfigChange = false;
            updateConfiguration();
            if (oldRotation != mRotation) {
                mLogger.logRotationChanged(oldRotation, mRotation);
            }
            setupDecorations();
            if (mOverlays != null) {
                // Updating the layout params ensures that ViewRootImpl will call relayoutWindow(),
                // which ensures that the forced seamless rotation will end, even if we updated
                // the rotation before window manager was ready (and was still waiting for sending
                // the updated rotation).
                updateLayoutParams();
            }
            Trace.endSection();
        });
    }

    private static String alphaInterpretationToString(int alpha) {
        switch (alpha) {
            case AlphaInterpretation.COVERAGE: return "COVERAGE";
            case AlphaInterpretation.MASK:     return "MASK";
            default:                           return "Unknown: " + alpha;
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("ScreenDecorations state:");
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();
        ipw.println("DEBUG_DISABLE_SCREEN_DECORATIONS:" + DEBUG_DISABLE_SCREEN_DECORATIONS);
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            return;
        }
        ipw.println("mDebug:" + mDebug);

        ipw.println("mIsPrivacyDotEnabled:" + isPrivacyDotEnabled());
        ipw.println("shouldOptimizeOverlayVisibility:" + shouldOptimizeVisibility());
        final boolean supportsShowingFaceScanningAnim = mFaceScanningFactory.getHasProviders();
        ipw.println("supportsShowingFaceScanningAnim:" + supportsShowingFaceScanningAnim);
        if (supportsShowingFaceScanningAnim) {
            ipw.increaseIndent();
            ipw.println("canShowFaceScanningAnim:"
                    + mFaceScanningFactory.canShowFaceScanningAnim());
            ipw.println("shouldShowFaceScanningAnim (at time dump was taken):"
                    + mFaceScanningFactory.shouldShowFaceScanningAnim());
            ipw.decreaseIndent();
        }
        FaceScanningOverlay faceScanningOverlay =
                (FaceScanningOverlay) getOverlayView(mFaceScanningViewId);
        if (faceScanningOverlay != null) {
            faceScanningOverlay.dump(ipw);
        }
        ipw.println("mPendingConfigChange:" + mPendingConfigChange);
        if (mHwcScreenDecorationSupport != null) {
            ipw.increaseIndent();
            ipw.println("mHwcScreenDecorationSupport:");
            ipw.increaseIndent();
            ipw.println("format="
                    + PixelFormat.formatToString(mHwcScreenDecorationSupport.format));
            ipw.println("alphaInterpretation="
                    + alphaInterpretationToString(mHwcScreenDecorationSupport.alphaInterpretation));
            ipw.decreaseIndent();
            ipw.decreaseIndent();
        } else {
            ipw.increaseIndent();
            pw.println("mHwcScreenDecorationSupport: null");
            ipw.decreaseIndent();
        }
        if (mScreenDecorHwcLayer != null) {
            ipw.increaseIndent();
            mScreenDecorHwcLayer.dump(ipw);
            ipw.decreaseIndent();
        } else {
            ipw.println("mScreenDecorHwcLayer: null");
        }
        if (mOverlays != null) {
            ipw.println("mOverlays(left,top,right,bottom)=("
                    + (mOverlays[BOUNDS_POSITION_LEFT] != null) + ","
                    + (mOverlays[BOUNDS_POSITION_TOP] != null) + ","
                    + (mOverlays[BOUNDS_POSITION_RIGHT] != null) + ","
                    + (mOverlays[BOUNDS_POSITION_BOTTOM] != null) + ")");

            for (int i = BOUNDS_POSITION_LEFT; i < BOUNDS_POSITION_LENGTH; i++) {
                if (mOverlays[i] != null) {
                    mOverlays[i].dump(pw, getWindowTitleByPos(i));
                }
            }
        }
        mRoundedCornerResDelegate.dump(pw, args);
        mDebugRoundedCornerDelegate.dump(pw);
    }

    @VisibleForTesting
    void updateConfiguration() {
        Preconditions.checkState(mHandler.getLooper().getThread() == Thread.currentThread(),
                "must call on " + mHandler.getLooper().getThread()
                        + ", but was " + Thread.currentThread());

        mContext.getDisplay().getDisplayInfo(mDisplayInfo);
        final int newRotation = mDisplayInfo.rotation;
        if (mRotation != newRotation) {
            mDotViewController.setNewRotation(newRotation);
        }
        final Display.Mode newMod = mDisplayInfo.getMode();
        final DisplayCutout newCutout = mDisplayInfo.displayCutout;

        if (!mPendingConfigChange
                && (newRotation != mRotation || displayModeChanged(mDisplayMode, newMod)
                || !Objects.equals(newCutout, mDisplayCutout))) {
            mRotation = newRotation;
            mDisplayMode = newMod;
            mDisplayCutout = newCutout;
            float ratio = getPhysicalPixelDisplaySizeRatio();
            mRoundedCornerResDelegate.setPhysicalPixelDisplaySizeRatio(ratio);
            mDebugRoundedCornerDelegate.setPhysicalPixelDisplaySizeRatio(ratio);
            if (mScreenDecorHwcLayer != null) {
                mScreenDecorHwcLayer.pendingConfigChange = false;
                mScreenDecorHwcLayer.updateConfiguration(mDisplayUniqueId);
                updateHwLayerRoundedCornerExistAndSize();
                updateHwLayerRoundedCornerDrawable();
            }
            updateLayoutParams();

            // update all provider views inside overlay
            updateOverlayProviderViews(null);
        }

        FaceScanningOverlay faceScanningOverlay =
                (FaceScanningOverlay) getOverlayView(mFaceScanningViewId);
        if (faceScanningOverlay != null) {
            faceScanningOverlay.setFaceScanningAnimColor(
                    Utils.getColorAttrDefaultColor(faceScanningOverlay.getContext(),
                            com.android.systemui.R.attr.wallpaperTextColorAccent));
        }
    }

    private boolean hasRoundedCorners() {
        return mRoundedCornerFactory.getHasProviders()
                || mDebugRoundedCornerFactory.getHasProviders();
    }

    private boolean shouldOptimizeVisibility() {
        return (isPrivacyDotEnabled() || mFaceScanningFactory.getHasProviders())
                && (mHwcScreenDecorationSupport != null
                    || (!hasRoundedCorners() && !shouldDrawCutout())
                );
    }

    private boolean shouldDrawCutout() {
        return mCutoutFactory.getHasProviders();
    }

    static boolean shouldDrawCutout(Context context) {
        return DisplayCutout.getFillBuiltInDisplayCutout(
                context.getResources(), context.getDisplay().getUniqueId());
    }

    @VisibleForTesting
    void updateOverlayProviderViews(@Nullable Integer[] filterIds) {
        if (mOverlays == null) {
            return;
        }
        ++mProviderRefreshToken;
        for (final OverlayWindow overlay: mOverlays) {
            if (overlay == null) {
                continue;
            }
            overlay.onReloadResAndMeasure(filterIds, mProviderRefreshToken, mRotation, mTintColor,
                    mDisplayUniqueId);
        }
    }

    private void updateLayoutParams() {
        //ToDo: We should skip unnecessary call to update view layout.
        Trace.beginSection("ScreenDecorations#updateLayoutParams");
        if (mScreenDecorHwcWindow != null) {
            mWindowManager.updateViewLayout(mScreenDecorHwcWindow, getHwcWindowLayoutParams());
        }

        if (mOverlays != null) {
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                if (mOverlays[i] == null) {
                    continue;
                }
                mWindowManager.updateViewLayout(
                        mOverlays[i].getRootView(), getWindowLayoutParams(i));
            }
        }
        Trace.endSection();
    }

    private void updateHwLayerRoundedCornerDrawable() {
        if (mScreenDecorHwcLayer == null) {
            return;
        }

        Drawable topDrawable = mRoundedCornerResDelegate.getTopRoundedDrawable();
        Drawable bottomDrawable = mRoundedCornerResDelegate.getBottomRoundedDrawable();
        if (mDebug && (mDebugRoundedCornerFactory.getHasProviders())) {
            topDrawable = mDebugRoundedCornerDelegate.getTopRoundedDrawable();
            bottomDrawable = mDebugRoundedCornerDelegate.getBottomRoundedDrawable();
        }

        if (topDrawable == null || bottomDrawable == null) {
            return;
        }
        mScreenDecorHwcLayer.updateRoundedCornerDrawable(topDrawable, bottomDrawable);
    }

    private void updateHwLayerRoundedCornerExistAndSize() {
        if (mScreenDecorHwcLayer == null) {
            return;
        }
        if (mDebug && mDebugRoundedCornerFactory.getHasProviders()) {
            mScreenDecorHwcLayer.updateRoundedCornerExistenceAndSize(
                    mDebugRoundedCornerDelegate.getHasTop(),
                    mDebugRoundedCornerDelegate.getHasBottom(),
                    mDebugRoundedCornerDelegate.getTopRoundedSize().getWidth(),
                    mDebugRoundedCornerDelegate.getBottomRoundedSize().getWidth());
        } else {
            mScreenDecorHwcLayer.updateRoundedCornerExistenceAndSize(
                    mRoundedCornerResDelegate.getHasTop(),
                    mRoundedCornerResDelegate.getHasBottom(),
                    mRoundedCornerResDelegate.getTopRoundedSize().getWidth(),
                    mRoundedCornerResDelegate.getBottomRoundedSize().getWidth());
        }
    }

    @VisibleForTesting
    protected void setSize(View view, Size pixelSize) {
        LayoutParams params = view.getLayoutParams();
        params.width = pixelSize.getWidth();
        params.height = pixelSize.getHeight();
        view.setLayoutParams(params);
    }

    public static class DisplayCutoutView extends DisplayCutoutBaseView {
        final List<Rect> mBounds = new ArrayList();
        final Rect mBoundingRect = new Rect();
        Rect mTotalBounds = new Rect();

        private int mColor = Color.BLACK;
        private int mRotation;
        private int mInitialPosition;
        private int mPosition;

        public DisplayCutoutView(Context context, @BoundsPosition int pos) {
            super(context);
            mInitialPosition = pos;

            paint.setColor(mColor);
            paint.setStyle(Paint.Style.FILL);
            if (DEBUG_LOGGING) {
                getViewTreeObserver().addOnDrawListener(() -> Log.i(TAG,
                        getWindowTitleByPos(pos) + " drawn in rot " + mRotation));
            }
        }

        public void setColor(int color) {
            if (color == mColor) {
                return;
            }
            mColor = color;
            paint.setColor(mColor);
            invalidate();
        }

        @Override
        public void updateRotation(int rotation) {
            // updateRotation() is called inside CutoutDecorProviderImpl::onReloadResAndMeasure()
            // during onDisplayChanged. In order to prevent reloading cutout info in super class,
            // check mRotation at first
            if (rotation == mRotation) {
                return;
            }
            mRotation = rotation;
            super.updateRotation(rotation);
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        @Override
        public void updateCutout() {
            if (!isAttachedToWindow() || pendingConfigChange) {
                return;
            }
            mPosition = getBoundPositionFromRotation(mInitialPosition, mRotation);
            requestLayout();
            getDisplay().getDisplayInfo(displayInfo);
            mBounds.clear();
            mBoundingRect.setEmpty();
            cutoutPath.reset();
            int newVisible;
            if (shouldDrawCutout(getContext()) && hasCutout()) {
                mBounds.addAll(displayInfo.displayCutout.getBoundingRects());
                localBounds(mBoundingRect);
                updateGravity();
                updateBoundingPath();
                invalidate();
                newVisible = VISIBLE;
            } else {
                newVisible = GONE;
            }
            if (updateVisOnUpdateCutout() && newVisible != getVisibility()) {
                setVisibility(newVisible);
            }
        }

        protected boolean updateVisOnUpdateCutout() {
            return true;
        }

        private void updateBoundingPath() {
            final Path path = displayInfo.displayCutout.getCutoutPath();
            if (path != null) {
                cutoutPath.set(path);
            } else {
                cutoutPath.reset();
            }
        }

        private void updateGravity() {
            LayoutParams lp = getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                int newGravity = getGravity(displayInfo.displayCutout);
                if (flp.gravity != newGravity) {
                    flp.gravity = newGravity;
                    setLayoutParams(flp);
                }
            }
        }

        private boolean hasCutout() {
            final DisplayCutout displayCutout = displayInfo.displayCutout;
            if (displayCutout == null) {
                return false;
            }

            if (mPosition == BOUNDS_POSITION_LEFT) {
                return !displayCutout.getBoundingRectLeft().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_TOP) {
                return !displayCutout.getBoundingRectTop().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_BOTTOM) {
                return !displayCutout.getBoundingRectBottom().isEmpty();
            } else if (mPosition == BOUNDS_POSITION_RIGHT) {
                return !displayCutout.getBoundingRectRight().isEmpty();
            }
            return false;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (mBounds.isEmpty()) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }

            if (showProtection) {
                // Make sure that our measured height encompasses the protection
                mTotalBounds.set(mBoundingRect);
                mTotalBounds.union((int) protectionRect.left, (int) protectionRect.top,
                        (int) protectionRect.right, (int) protectionRect.bottom);
                setMeasuredDimension(
                        resolveSizeAndState(mTotalBounds.width(), widthMeasureSpec, 0),
                        resolveSizeAndState(mTotalBounds.height(), heightMeasureSpec, 0));
            } else {
                setMeasuredDimension(
                        resolveSizeAndState(mBoundingRect.width(), widthMeasureSpec, 0),
                        resolveSizeAndState(mBoundingRect.height(), heightMeasureSpec, 0));
            }
        }

        public static void boundsFromDirection(DisplayCutout displayCutout, int gravity,
                Rect out) {
            switch (gravity) {
                case Gravity.TOP:
                    out.set(displayCutout.getBoundingRectTop());
                    break;
                case Gravity.LEFT:
                    out.set(displayCutout.getBoundingRectLeft());
                    break;
                case Gravity.BOTTOM:
                    out.set(displayCutout.getBoundingRectBottom());
                    break;
                case Gravity.RIGHT:
                    out.set(displayCutout.getBoundingRectRight());
                    break;
                default:
                    out.setEmpty();
            }
        }

        private void localBounds(Rect out) {
            DisplayCutout displayCutout = displayInfo.displayCutout;
            boundsFromDirection(displayCutout, getGravity(displayCutout), out);
        }

        private int getGravity(DisplayCutout displayCutout) {
            if (mPosition == BOUNDS_POSITION_LEFT) {
                if (!displayCutout.getBoundingRectLeft().isEmpty()) {
                    return Gravity.LEFT;
                }
            } else if (mPosition == BOUNDS_POSITION_TOP) {
                if (!displayCutout.getBoundingRectTop().isEmpty()) {
                    return Gravity.TOP;
                }
            } else if (mPosition == BOUNDS_POSITION_BOTTOM) {
                if (!displayCutout.getBoundingRectBottom().isEmpty()) {
                    return Gravity.BOTTOM;
                }
            } else if (mPosition == BOUNDS_POSITION_RIGHT) {
                if (!displayCutout.getBoundingRectRight().isEmpty()) {
                    return Gravity.RIGHT;
                }
            }
            return Gravity.NO_GRAVITY;
        }
    }

    /**
     * A pre-draw listener, that cancels the draw and restarts the traversal with the updated
     * window attributes.
     */
    private class RestartingPreDrawListener implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;
        private final int mTargetRotation;
        private final Display.Mode mTargetDisplayMode;
        // Pass -1 for ScreenDecorHwcLayer since it's a fullscreen window and has no specific
        // position.
        private final int mPosition;

        private RestartingPreDrawListener(View view, @BoundsPosition int position,
                int targetRotation, Display.Mode targetDisplayMode) {
            mView = view;
            mTargetRotation = targetRotation;
            mTargetDisplayMode = targetDisplayMode;
            mPosition = position;
        }

        @Override
        public boolean onPreDraw() {
            mView.getViewTreeObserver().removeOnPreDrawListener(this);
            if (mTargetRotation == mRotation
                    && !displayModeChanged(mDisplayMode, mTargetDisplayMode)) {
                if (DEBUG_LOGGING) {
                    final String title = mPosition < 0 ? "ScreenDecorHwcLayer"
                            : getWindowTitleByPos(mPosition);
                    Log.i(TAG, title + " already in target rot "
                            + mTargetRotation + " and in target resolution "
                            + mTargetDisplayMode.getPhysicalWidth() + "x"
                            + mTargetDisplayMode.getPhysicalHeight()
                            + ", allow draw without restarting it");
                }
                return true;
            }

            mPendingConfigChange = false;
            // This changes the window attributes - we need to restart the traversal for them to
            // take effect.
            updateConfiguration();
            if (DEBUG_LOGGING) {
                final String title = mPosition < 0 ? "ScreenDecorHwcLayer"
                        : getWindowTitleByPos(mPosition);
                Log.i(TAG, title
                        + " restarting listener fired, restarting draw for rot " + mRotation
                        + ", resolution " + mDisplayMode.getPhysicalWidth() + "x"
                        + mDisplayMode.getPhysicalHeight());
            }
            mView.invalidate();
            return false;
        }
    }

    /**
     * A pre-draw listener, that validates that the rotation and display resolution we draw in
     * matches the display's rotation and resolution before continuing the draw.
     *
     * This is to prevent a race condition, where we have not received the display changed event
     * yet, and would thus draw in an old orientation.
     */
    private class ValidatingPreDrawListener implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;

        public ValidatingPreDrawListener(View view) {
            mView = view;
        }

        @Override
        public boolean onPreDraw() {
            mContext.getDisplay().getDisplayInfo(mDisplayInfo);
            final int displayRotation = mDisplayInfo.rotation;
            final Display.Mode displayMode = mDisplayInfo.getMode();
            if ((displayRotation != mRotation || displayModeChanged(mDisplayMode, displayMode))
                    && !mPendingConfigChange) {
                if (DEBUG_LOGGING) {
                    if (displayRotation != mRotation) {
                        Log.i(TAG, "Drawing rot " + mRotation + ", but display is at rot "
                                + displayRotation + ". Restarting draw");
                    }
                    if (displayModeChanged(mDisplayMode, displayMode)) {
                        Log.i(TAG, "Drawing at " + mDisplayMode.getPhysicalWidth()
                                + "x" + mDisplayMode.getPhysicalHeight() + ", but display is at "
                                + displayMode.getPhysicalWidth() + "x"
                                + displayMode.getPhysicalHeight() + ". Restarting draw");
                    }
                }
                mView.invalidate();
                return false;
            }
            return true;
        }
    }
}
