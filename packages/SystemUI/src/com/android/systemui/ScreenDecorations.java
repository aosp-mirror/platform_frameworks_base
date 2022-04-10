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
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.graphics.common.AlphaInterpretation;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.util.Log;
import android.util.Size;
import android.view.DisplayCutout;
import android.view.DisplayCutout.BoundsPosition;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.Preconditions;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.decor.DecorProvider;
import com.android.systemui.decor.DecorProviderFactory;
import com.android.systemui.decor.DecorProviderKt;
import com.android.systemui.decor.OverlayWindow;
import com.android.systemui.decor.PrivacyDotDecorProviderFactory;
import com.android.systemui.decor.RoundedCornerResDelegate;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.events.PrivacyDotViewController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.ThreadFactory;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import kotlin.Pair;

/**
 * An overlay that draws screen decorations in software (e.g for rounded corners or display cutout)
 * for antialiasing and emulation purposes.
 */
@SysUISingleton
public class ScreenDecorations extends CoreStartable implements Tunable , Dumpable {
    private static final boolean DEBUG = false;
    private static final String TAG = "ScreenDecorations";

    public static final String SIZE = "sysui_rounded_size";
    public static final String PADDING = "sysui_rounded_content_padding";
    // Provide a way for factory to disable ScreenDecorations to run the Display tests.
    private static final boolean DEBUG_DISABLE_SCREEN_DECORATIONS =
            SystemProperties.getBoolean("debug.disable_screen_decorations", false);
    private static final boolean DEBUG_SCREENSHOT_ROUNDED_CORNERS =
            SystemProperties.getBoolean("debug.screenshot_rounded_corners", false);
    private static final boolean VERBOSE = false;
    static final boolean DEBUG_COLOR = DEBUG_SCREENSHOT_ROUNDED_CORNERS;

    private DisplayManager mDisplayManager;
    @VisibleForTesting
    protected boolean mIsRegistered;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mMainExecutor;
    private final TunerService mTunerService;
    private final SecureSettings mSecureSettings;
    @VisibleForTesting
    DisplayManager.DisplayListener mDisplayListener;
    private CameraAvailabilityListener mCameraListener;
    private final UserTracker mUserTracker;
    private final PrivacyDotViewController mDotViewController;
    private final ThreadFactory mThreadFactory;
    private final DecorProviderFactory mDotFactory;

    //TODO: These are piecemeal being updated to Points for now to support non-square rounded
    // corners. for now it is only supposed when reading the intrinsic size from the drawables with
    // mIsRoundedCornerMultipleRadius is set
    @VisibleForTesting
    protected RoundedCornerResDelegate mRoundedCornerResDelegate;
    @VisibleForTesting
    protected OverlayWindow[] mOverlays = null;
    @VisibleForTesting
    @Nullable
    DisplayCutoutView[] mCutoutViews;
    @VisibleForTesting
    ViewGroup mScreenDecorHwcWindow;
    @VisibleForTesting
    ScreenDecorHwcLayer mScreenDecorHwcLayer;
    private WindowManager mWindowManager;
    private int mRotation;
    private SettingObserver mColorInversionSetting;
    private DelayableExecutor mExecutor;
    private Handler mHandler;
    boolean mPendingRotationChange;
    @VisibleForTesting
    String mDisplayUniqueId;
    private int mTintColor = Color.BLACK;
    @VisibleForTesting
    protected DisplayDecorationSupport mHwcScreenDecorationSupport;

    private CameraAvailabilityListener.CameraTransitionCallback mCameraTransitionCallback =
            new CameraAvailabilityListener.CameraTransitionCallback() {
        @Override
        public void onApplyCameraProtection(@NonNull Path protectionPath, @NonNull Rect bounds) {
            if (mScreenDecorHwcLayer != null) {
                mScreenDecorHwcLayer.setProtection(protectionPath, bounds);
                mScreenDecorHwcLayer.enableShowProtection(true);
                return;
            }
            if (mCutoutViews == null) {
                Log.w(TAG, "DisplayCutoutView do not initialized");
                return;
            }
            // Show the extra protection around the front facing camera if necessary
            for (DisplayCutoutView dcv : mCutoutViews) {
                // Check Null since not all mCutoutViews[pos] be inflated at the meanwhile
                if (dcv != null) {
                    dcv.setProtection(protectionPath, bounds);
                    dcv.enableShowProtection(true);
                }
            }
        }

        @Override
        public void onHideCameraProtection() {
            if (mScreenDecorHwcLayer != null) {
                mScreenDecorHwcLayer.enableShowProtection(false);
                return;
            }
            if (mCutoutViews == null) {
                Log.w(TAG, "DisplayCutoutView do not initialized");
                return;
            }
            // Go back to the regular anti-aliasing
            for (DisplayCutoutView dcv : mCutoutViews) {
                // Check Null since not all mCutoutViews[pos] be inflated at the meanwhile
                if (dcv != null) {
                    dcv.enableShowProtection(false);
                }
            }
        }
    };

    @VisibleForTesting
    PrivacyDotViewController.ShowingListener mPrivacyDotShowingListener =
            new PrivacyDotViewController.ShowingListener() {
        @Override
        public void onPrivacyDotShown(@Nullable View v) {
            setOverlayWindowVisibilityIfViewExist(v, View.VISIBLE);
        }

        @Override
        public void onPrivacyDotHidden(@Nullable View v) {
            setOverlayWindowVisibilityIfViewExist(v, View.INVISIBLE);
        }
    };

    @VisibleForTesting
    protected void setOverlayWindowVisibilityIfViewExist(@Nullable View view,
            @View.Visibility int visibility) {
        if (view == null) {
            return;
        }
        mExecutor.execute(() -> {
            // We don't need to control the window visibility if rounded corners or cutout is drawn
            // on sw layer since the overlay windows are always visible in this case.
            if (mOverlays == null || !isOnlyPrivacyDotInSwLayer()) {
                return;
            }

            for (final OverlayWindow overlay : mOverlays) {
                if (overlay == null) {
                    continue;
                }
                if (overlay.getView(view.getId()) != null) {
                    overlay.getRootView().setVisibility(visibility);
                    return;
                }
            }
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
            BroadcastDispatcher broadcastDispatcher,
            TunerService tunerService,
            UserTracker userTracker,
            PrivacyDotViewController dotViewController,
            ThreadFactory threadFactory,
            PrivacyDotDecorProviderFactory dotFactory) {
        super(context);
        mMainExecutor = mainExecutor;
        mSecureSettings = secureSettings;
        mBroadcastDispatcher = broadcastDispatcher;
        mTunerService = tunerService;
        mUserTracker = userTracker;
        mDotViewController = dotViewController;
        mThreadFactory = threadFactory;
        mDotFactory = dotFactory;
    }

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
    }

    private boolean isPrivacyDotEnabled() {
        return mDotFactory.getHasProviders();
    }

    private void startOnScreenDecorationsThread() {
        mRotation = mContext.getDisplay().getRotation();
        mDisplayUniqueId = mContext.getDisplay().getUniqueId();
        mRoundedCornerResDelegate = new RoundedCornerResDelegate(mContext.getResources(),
                mDisplayUniqueId);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mHwcScreenDecorationSupport = mContext.getDisplay().getDisplayDecorationSupport();
        updateRoundedCornerDrawable();
        updateRoundedCornerRadii();
        setupDecorations();
        setupCameraListener();

        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                // do nothing
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // do nothing
            }

            @Override
            public void onDisplayChanged(int displayId) {
                final int newRotation = mContext.getDisplay().getRotation();
                if (mOverlays != null && mRotation != newRotation) {
                    // We cannot immediately update the orientation. Otherwise
                    // WindowManager is still deferring layout until it has finished dispatching
                    // the config changes, which may cause divergence between what we draw
                    // (new orientation), and where we are placed on the screen (old orientation).
                    // Instead we wait until either:
                    // - we are trying to redraw. This because WM resized our window and told us to.
                    // - the config change has been dispatched, so WM is no longer deferring layout.
                    mPendingRotationChange = true;
                    if (DEBUG) {
                        Log.i(TAG, "Rotation changed, deferring " + newRotation + ", staying at "
                                + mRotation);
                    }

                    for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                        if (mOverlays[i] != null) {
                            final ViewGroup overlayView = mOverlays[i].getRootView();
                            overlayView.getViewTreeObserver().addOnPreDrawListener(
                                    new RestartingPreDrawListener(overlayView, i, newRotation));
                        }
                    }

                    if (mScreenDecorHwcWindow != null) {
                        mScreenDecorHwcWindow.getViewTreeObserver().addOnPreDrawListener(
                                new RestartingPreDrawListener(
                                        mScreenDecorHwcWindow,
                                        -1, // Pass -1 for views with no specific position.
                                        newRotation));
                    }
                }

                final String newUniqueId = mContext.getDisplay().getUniqueId();
                if (!Objects.equals(newUniqueId, mDisplayUniqueId)) {
                    mDisplayUniqueId = newUniqueId;
                    mRoundedCornerResDelegate.reloadAll(newUniqueId);
                    final DisplayDecorationSupport newScreenDecorationSupport =
                            mContext.getDisplay().getDisplayDecorationSupport();
                    // When the value of mSupportHwcScreenDecoration is changed, re-setup the whole
                    // screen decoration.
                    if (!eq(newScreenDecorationSupport, mHwcScreenDecorationSupport)) {
                        mHwcScreenDecorationSupport = newScreenDecorationSupport;
                        removeAllOverlays();
                        setupDecorations();
                        return;
                    }
                    updateRoundedCornerDrawable();
                }
                if (mCutoutViews != null) {
                    final int size = mCutoutViews.length;
                    for (int i = 0; i < size; i++) {
                        final DisplayCutoutView cutoutView = mCutoutViews[i];
                        if (cutoutView == null) {
                            continue;
                        }
                        cutoutView.onDisplayChanged(displayId);
                    }
                }
                if (mScreenDecorHwcLayer != null) {
                    mScreenDecorHwcLayer.onDisplayChanged(displayId);
                }
                updateOrientation();
            }
        };

        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateOrientation();
    }

    @Nullable
    private View getOverlayView(@IdRes int id) {
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
        List<DecorProvider> decorProviders = mDotFactory.getProviders();

        if (hasRoundedCorners() || shouldDrawCutout() || !decorProviders.isEmpty()) {
            if (mHwcScreenDecorationSupport != null) {
                createHwcOverlay();
            } else {
                removeHwcOverlay();
            }
            final DisplayCutout cutout = getCutout();
            final boolean isOnlyPrivacyDotInSwLayer = isOnlyPrivacyDotInSwLayer();
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                if (shouldShowSwLayerCutout(i, cutout) || shouldShowSwLayerRoundedCorner(i, cutout)
                        || shouldShowSwLayerPrivacyDot(i, cutout)) {
                    Pair<List<DecorProvider>, List<DecorProvider>> pair =
                            DecorProviderKt.partitionAlignedBound(decorProviders, i);
                    decorProviders = pair.getSecond();
                    createOverlay(i, cutout, pair.getFirst(), isOnlyPrivacyDotInSwLayer);
                } else {
                    removeOverlay(i);
                }
            }

            if (isOnlyPrivacyDotInSwLayer) {
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

            mMainExecutor.execute(() -> mTunerService.addTunable(this, SIZE));

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

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            mBroadcastDispatcher.registerReceiver(mUserSwitchIntentReceiver, filter,
                    mExecutor, UserHandle.ALL);
            mIsRegistered = true;
        } else {
            mMainExecutor.execute(() -> mTunerService.removeTunable(this));

            if (mColorInversionSetting != null) {
                mColorInversionSetting.setListening(false);
            }

            mBroadcastDispatcher.unregisterReceiver(mUserSwitchIntentReceiver);
            mIsRegistered = false;
        }
    }

    @VisibleForTesting
    DisplayCutout getCutout() {
        return mContext.getDisplay().getCutout();
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
            boolean isOnlyPrivacyDotInSwLayer) {
        if (!isOnlyPrivacyDotInSwLayer) {
            // Multiple views inside overlay, no need to optimize
            return View.VISIBLE;
        }

        int[] ids = {
                R.id.privacy_dot_top_left_container,
                R.id.privacy_dot_top_right_container,
                R.id.privacy_dot_bottom_left_container,
                R.id.privacy_dot_bottom_right_container
        };
        for (int id: ids) {
            final View view = overlay.getView(id);
            if (view != null && view.getVisibility() == View.VISIBLE) {
                // Only privacy dot in sw layers, overlay shall be VISIBLE if one of privacy dot
                // views inside this overlay is VISIBLE
                return View.VISIBLE;
            }
        }
        // Only privacy dot in sw layers, overlay shall be INVISIBLE like default if no privacy dot
        // view inside this overlay is VISIBLE.
        return View.INVISIBLE;
    }

    private void createOverlay(
            @BoundsPosition int pos,
            @Nullable DisplayCutout cutout,
            @NonNull List<DecorProvider> decorProviders,
            boolean isOnlyPrivacyDotInSwLayer) {
        if (mOverlays == null) {
            mOverlays = new OverlayWindow[BOUNDS_POSITION_LENGTH];
        }

        if (mOverlays[pos] != null) {
            // When mOverlay[pos] is not null and only privacy dot in sw layer, use privacy dot
            // view's visibility
            mOverlays[pos].getRootView().setVisibility(
                    getWindowVisibility(mOverlays[pos], isOnlyPrivacyDotInSwLayer));
            return;
        }

        mOverlays[pos] = overlayForPosition(pos, decorProviders, isOnlyPrivacyDotInSwLayer);
        final ViewGroup overlayView = mOverlays[pos].getRootView();
        overlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        overlayView.setAlpha(0);
        overlayView.setForceDarkAllowed(false);

        // Only show cutout and rounded corners in mOverlays when hwc don't support screen
        // decoration.
        if (mHwcScreenDecorationSupport == null) {
            if (mCutoutViews == null) {
                mCutoutViews = new DisplayCutoutView[BOUNDS_POSITION_LENGTH];
            }
            mCutoutViews[pos] = new DisplayCutoutView(mContext, pos);
            mCutoutViews[pos].setColor(mTintColor);
            overlayView.addView(mCutoutViews[pos]);
            updateView(pos, cutout);
        }

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
        mScreenDecorHwcLayer = new ScreenDecorHwcLayer(mContext, mHwcScreenDecorationSupport);
        mScreenDecorHwcWindow.addView(mScreenDecorHwcLayer, new FrameLayout.LayoutParams(
                MATCH_PARENT, MATCH_PARENT, Gravity.TOP | Gravity.START));
        mWindowManager.addView(mScreenDecorHwcWindow, getHwcWindowLayoutParams());
        updateHwLayerRoundedCornerSize();
        updateHwLayerRoundedCornerDrawable();
        mScreenDecorHwcWindow.getViewTreeObserver().addOnPreDrawListener(
                new ValidatingPreDrawListener(mScreenDecorHwcWindow));
    }

    /**
     * Allow overrides for top/bottom positions
     */
    private OverlayWindow overlayForPosition(
            @BoundsPosition int pos,
            @NonNull List<DecorProvider> decorProviders,
            boolean isOnlyPrivacyDotInSwLayer) {
        final OverlayWindow currentOverlay = new OverlayWindow(LayoutInflater.from(mContext), pos);
        decorProviders.forEach(provider -> {
            removeOverlayView(provider.getViewId());
            currentOverlay.addDecorProvider(provider, mRotation);
        });
        // When only privacy dot in mOverlay, set the initial visibility of mOverlays to
        // INVISIBLE and set it to VISIBLE when the privacy dot is showing.
        if (isOnlyPrivacyDotInSwLayer) {
            currentOverlay.getRootView().setVisibility(View.INVISIBLE);
        }
        return currentOverlay;
    }

    private void updateView(@BoundsPosition int pos, @Nullable DisplayCutout cutout) {
        if (mOverlays == null || mOverlays[pos] == null || mHwcScreenDecorationSupport != null) {
            return;
        }

        // update rounded corner view rotation
        updateRoundedCornerView(pos, R.id.left, cutout);
        updateRoundedCornerView(pos, R.id.right, cutout);
        updateRoundedCornerSize(
                mRoundedCornerResDelegate.getTopRoundedSize(),
                mRoundedCornerResDelegate.getBottomRoundedSize());
        updateRoundedCornerImageView();

        // update cutout view rotation
        if (mCutoutViews != null && mCutoutViews[pos] != null) {
            mCutoutViews[pos].updateRotation(mRotation);
        }
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
        if (!DEBUG_COLOR) {
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        Resources res = mContext.getResources();
        boolean enabled = res.getBoolean(R.bool.config_enableDisplayCutoutProtection);
        if (enabled) {
            mCameraListener = CameraAvailabilityListener.Factory.build(mContext, mExecutor);
            mCameraListener.addTransitionCallback(mCameraTransitionCallback);
            mCameraListener.startListening();
        }
    }

    private final BroadcastReceiver mUserSwitchIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int newUserId = ActivityManager.getCurrentUser();
            if (DEBUG) {
                Log.d(TAG, "UserSwitched newUserId=" + newUserId);
            }
            // update color inversion setting to the new user
            mColorInversionSetting.setUserId(newUserId);
            updateColorInversion(mColorInversionSetting.getValue());
        }
    };

    private void updateColorInversion(int colorsInvertedValue) {
        mTintColor = colorsInvertedValue != 0 ? Color.WHITE : Color.BLACK;
        if (DEBUG_COLOR) {
            mTintColor = Color.RED;
        }

        // When the hwc supports screen decorations, the layer will use the A8 color mode which
        // won't be affected by the color inversion. If the composition goes the client composition
        // route, the color inversion will be handled by the RenderEngine.
        if (mOverlays == null || mHwcScreenDecorationSupport != null) {
            return;
        }

        ColorStateList tintList = ColorStateList.valueOf(mTintColor);
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            final ViewGroup overlayView = mOverlays[i].getRootView();
            final int size = overlayView.getChildCount();
            View child;
            for (int j = 0; j < size; j++) {
                child = overlayView.getChildAt(j);
                if (child.getId() == R.id.privacy_dot_top_left_container
                        || child.getId() == R.id.privacy_dot_top_right_container
                        || child.getId() == R.id.privacy_dot_bottom_left_container
                        || child.getId() == R.id.privacy_dot_bottom_right_container) {
                    // Exclude privacy dot from color inversion (for now?)
                    continue;
                }
                if (child instanceof ImageView) {
                    ((ImageView) child).setImageTintList(tintList);
                } else if (child instanceof DisplayCutoutView) {
                    ((DisplayCutoutView) child).setColor(mTintColor);
                }
            }
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }

        mExecutor.execute(() -> {
            int oldRotation = mRotation;
            mPendingRotationChange = false;
            updateOrientation();
            updateRoundedCornerRadii();
            if (DEBUG) Log.i(TAG, "onConfigChanged from rot " + oldRotation + " to " + mRotation);
            setupDecorations();
            if (mOverlays != null) {
                // Updating the layout params ensures that ViewRootImpl will call relayoutWindow(),
                // which ensures that the forced seamless rotation will end, even if we updated
                // the rotation before window manager was ready (and was still waiting for sending
                // the updated rotation).
                updateLayoutParams();
            }
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
        pw.println("  DEBUG_DISABLE_SCREEN_DECORATIONS:" + DEBUG_DISABLE_SCREEN_DECORATIONS);
        pw.println("  mIsPrivacyDotEnabled:" + isPrivacyDotEnabled());
        pw.println("  isOnlyPrivacyDotInSwLayer:" + isOnlyPrivacyDotInSwLayer());
        pw.println("  mPendingRotationChange:" + mPendingRotationChange);
        if (mHwcScreenDecorationSupport != null) {
            pw.println("  mHwcScreenDecorationSupport:");
            pw.println("    format="
                    + PixelFormat.formatToString(mHwcScreenDecorationSupport.format));
            pw.println("    alphaInterpretation="
                    + alphaInterpretationToString(mHwcScreenDecorationSupport.alphaInterpretation));
        } else {
            pw.println("  mHwcScreenDecorationSupport: null");
        }
        if (mScreenDecorHwcLayer != null) {
            pw.println("  mScreenDecorHwcLayer:");
            pw.println("    transparentRegion=" + mScreenDecorHwcLayer.transparentRect);
        } else {
            pw.println("  mScreenDecorHwcLayer: null");
        }
        pw.println("  mOverlays(left,top,right,bottom)=("
                + (mOverlays != null && mOverlays[BOUNDS_POSITION_LEFT] != null) + ","
                + (mOverlays != null && mOverlays[BOUNDS_POSITION_TOP] != null) + ","
                + (mOverlays != null && mOverlays[BOUNDS_POSITION_RIGHT] != null) + ","
                + (mOverlays != null && mOverlays[BOUNDS_POSITION_BOTTOM] != null) + ")");
        mRoundedCornerResDelegate.dump(pw, args);
    }

    private void updateOrientation() {
        Preconditions.checkState(mHandler.getLooper().getThread() == Thread.currentThread(),
                "must call on " + mHandler.getLooper().getThread()
                        + ", but was " + Thread.currentThread());

        int newRotation = mContext.getDisplay().getRotation();
        if (mRotation != newRotation) {
            mDotViewController.setNewRotation(newRotation);
        }

        if (mPendingRotationChange) {
            return;
        }
        if (newRotation != mRotation) {
            mRotation = newRotation;
            if (mScreenDecorHwcLayer != null) {
                mScreenDecorHwcLayer.pendingRotationChange = false;
                mScreenDecorHwcLayer.updateRotation(mRotation);
            }
            if (mOverlays != null) {
                updateLayoutParams();
                final DisplayCutout cutout = getCutout();
                for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
                    if (mOverlays[i] == null) {
                        continue;
                    }
                    updateView(i, cutout);
                }
            }
        }
    }

    private void updateRoundedCornerRadii() {
        // We should eventually move to just using the intrinsic size of the drawables since
        // they should be sized to the exact pixels they want to cover. Therefore I'm purposely not
        // upgrading all of the configs to contain (width, height) pairs. Instead assume that a
        // device configured using the single integer config value is okay with drawing the corners
        // as a square
        final Size oldRoundedDefaultTop = mRoundedCornerResDelegate.getTopRoundedSize();
        final Size oldRoundedDefaultBottom = mRoundedCornerResDelegate.getBottomRoundedSize();
        mRoundedCornerResDelegate.reloadAll(mDisplayUniqueId);
        final Size newRoundedDefaultTop = mRoundedCornerResDelegate.getTopRoundedSize();
        final Size newRoundedDefaultBottom = mRoundedCornerResDelegate.getBottomRoundedSize();

        if (oldRoundedDefaultTop.getWidth() != newRoundedDefaultTop.getWidth()
                || oldRoundedDefaultBottom.getWidth() != newRoundedDefaultBottom.getWidth()) {
            onTuningChanged(SIZE, null);
        }
    }

    private void updateRoundedCornerView(@BoundsPosition int pos, int id,
            @Nullable DisplayCutout cutout) {
        final View rounded = mOverlays[pos].getRootView().findViewById(id);
        if (rounded == null) {
            return;
        }
        rounded.setVisibility(View.GONE);
        if (shouldShowSwLayerRoundedCorner(pos, cutout)) {
            final int gravity = getRoundedCornerGravity(pos, id == R.id.left);
            ((FrameLayout.LayoutParams) rounded.getLayoutParams()).gravity = gravity;
            setRoundedCornerOrientation(rounded, gravity);
            rounded.setVisibility(View.VISIBLE);
        }
    }

    private int getRoundedCornerGravity(@BoundsPosition int pos, boolean isStart) {
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        switch (rotatedPos) {
            case BOUNDS_POSITION_LEFT:
                return isStart ? Gravity.TOP | Gravity.LEFT : Gravity.BOTTOM | Gravity.LEFT;
            case BOUNDS_POSITION_TOP:
                return isStart ? Gravity.TOP | Gravity.LEFT : Gravity.TOP | Gravity.RIGHT;
            case BOUNDS_POSITION_RIGHT:
                return isStart ? Gravity.TOP | Gravity.RIGHT : Gravity.BOTTOM | Gravity.RIGHT;
            case BOUNDS_POSITION_BOTTOM:
                return isStart ? Gravity.BOTTOM | Gravity.LEFT : Gravity.BOTTOM | Gravity.RIGHT;
            default:
                throw new IllegalArgumentException("Incorrect position: " + rotatedPos);
        }
    }

    /**
     * Configures the rounded corner drawable's view matrix based on the gravity.
     *
     * The gravity describes which corner to configure for, and the drawable we are rotating is
     * assumed to be oriented for the top-left corner of the device regardless of the target corner.
     * Therefore we need to rotate 180 degrees to get a bottom-left corner, and mirror in the x- or
     * y-axis for the top-right and bottom-left corners.
     */
    private void setRoundedCornerOrientation(View corner, int gravity) {
        corner.setRotation(0);
        corner.setScaleX(1);
        corner.setScaleY(1);
        switch (gravity) {
            case Gravity.TOP | Gravity.LEFT:
                return;
            case Gravity.TOP | Gravity.RIGHT:
                corner.setScaleX(-1); // flip X axis
                return;
            case Gravity.BOTTOM | Gravity.LEFT:
                corner.setScaleY(-1); // flip Y axis
                return;
            case Gravity.BOTTOM | Gravity.RIGHT:
                corner.setRotation(180);
                return;
            default:
                throw new IllegalArgumentException("Unsupported gravity: " + gravity);
        }
    }
    private boolean hasRoundedCorners() {
        return mRoundedCornerResDelegate.getBottomRoundedSize().getWidth() > 0
                || mRoundedCornerResDelegate.getTopRoundedSize().getWidth() > 0
                || mRoundedCornerResDelegate.isMultipleRadius();
    }

    private boolean isDefaultShownOverlayPos(@BoundsPosition int pos,
            @Nullable DisplayCutout cutout) {
        // for cutout is null or cutout with only waterfall.
        final boolean emptyBoundsOrWaterfall = cutout == null || cutout.isBoundsEmpty();
        // Shows rounded corner on left and right overlays only when there is no top or bottom
        // cutout.
        final int rotatedTop = getBoundPositionFromRotation(BOUNDS_POSITION_TOP, mRotation);
        final int rotatedBottom = getBoundPositionFromRotation(BOUNDS_POSITION_BOTTOM, mRotation);
        if (emptyBoundsOrWaterfall || !cutout.getBoundingRectsAll()[rotatedTop].isEmpty()
                || !cutout.getBoundingRectsAll()[rotatedBottom].isEmpty()) {
            return pos == BOUNDS_POSITION_TOP || pos == BOUNDS_POSITION_BOTTOM;
        } else {
            return pos == BOUNDS_POSITION_LEFT || pos == BOUNDS_POSITION_RIGHT;
        }
    }

    private boolean shouldShowSwLayerRoundedCorner(@BoundsPosition int pos,
            @Nullable DisplayCutout cutout) {
        return hasRoundedCorners() && isDefaultShownOverlayPos(pos, cutout)
                && mHwcScreenDecorationSupport == null;
    }

    private boolean shouldShowSwLayerPrivacyDot(@BoundsPosition int pos,
            @Nullable DisplayCutout cutout) {
        return isPrivacyDotEnabled() && isDefaultShownOverlayPos(pos, cutout);
    }

    private boolean shouldShowSwLayerCutout(@BoundsPosition int pos,
            @Nullable DisplayCutout cutout) {
        final Rect[] bounds = cutout == null ? null : cutout.getBoundingRectsAll();
        final int rotatedPos = getBoundPositionFromRotation(pos, mRotation);
        return (bounds != null && !bounds[rotatedPos].isEmpty()
                && mHwcScreenDecorationSupport == null);
    }

    private boolean isOnlyPrivacyDotInSwLayer() {
        return isPrivacyDotEnabled()
                && (mHwcScreenDecorationSupport != null
                    || (!hasRoundedCorners() && !shouldDrawCutout())
                );
    }

    private boolean shouldDrawCutout() {
        return shouldDrawCutout(mContext);
    }

    static boolean shouldDrawCutout(Context context) {
        return DisplayCutout.getFillBuiltInDisplayCutout(
                context.getResources(), context.getDisplay().getUniqueId());
    }

    private void updateLayoutParams() {
        if (mOverlays == null) {
            return;
        }
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            mWindowManager.updateViewLayout(mOverlays[i].getRootView(), getWindowLayoutParams(i));
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (DEBUG_DISABLE_SCREEN_DECORATIONS) {
            Log.i(TAG, "ScreenDecorations is disabled");
            return;
        }
        mExecutor.execute(() -> {
            if (mOverlays == null) return;
            if (SIZE.equals(key)) {
                boolean hasReloadRoundedCornerRes = false;
                if (newValue != null) {
                    try {
                        mRoundedCornerResDelegate.updateTuningSizeFactor(
                                Integer.parseInt(newValue));
                        hasReloadRoundedCornerRes = true;
                    } catch (Exception e) {
                    }
                }

                // When onTuningChanged() is not called through updateRoundedCornerRadii(),
                // we need to reload rounded corner res to prevent incorrect dimen
                if (!hasReloadRoundedCornerRes) {
                    mRoundedCornerResDelegate.reloadAll(mDisplayUniqueId);
                }

                updateRoundedCornerSize(
                        mRoundedCornerResDelegate.getTopRoundedSize(),
                        mRoundedCornerResDelegate.getBottomRoundedSize());
            }
        });
    }

    private void updateRoundedCornerDrawable() {
        mRoundedCornerResDelegate.reloadAll(mDisplayUniqueId);
        updateRoundedCornerImageView();
    }

    private void updateRoundedCornerImageView() {
        final Drawable top = mRoundedCornerResDelegate.getTopRoundedDrawable();
        final Drawable bottom = mRoundedCornerResDelegate.getBottomRoundedDrawable();

        if (mScreenDecorHwcLayer != null) {
            mScreenDecorHwcLayer.updateRoundedCornerDrawable(top, bottom);
            return;
        }

        if (mOverlays == null) {
            return;
        }
        final ColorStateList colorStateList = ColorStateList.valueOf(mTintColor);
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            final ViewGroup overlayView = mOverlays[i].getRootView();
            ((ImageView) overlayView.findViewById(R.id.left)).setImageTintList(colorStateList);
            ((ImageView) overlayView.findViewById(R.id.right)).setImageTintList(colorStateList);
            ((ImageView) overlayView.findViewById(R.id.left)).setImageDrawable(
                    isTopRoundedCorner(i, R.id.left) ? top : bottom);
            ((ImageView) overlayView.findViewById(R.id.right)).setImageDrawable(
                    isTopRoundedCorner(i, R.id.right) ? top : bottom);
        }
    }

    private void updateHwLayerRoundedCornerDrawable() {
        if (mScreenDecorHwcLayer == null) {
            return;
        }

        final Drawable topDrawable = mRoundedCornerResDelegate.getTopRoundedDrawable();
        final Drawable bottomDrawable = mRoundedCornerResDelegate.getBottomRoundedDrawable();

        if (topDrawable == null || bottomDrawable == null) {
            return;
        }
        mScreenDecorHwcLayer.updateRoundedCornerDrawable(topDrawable, bottomDrawable);
    }

    @VisibleForTesting
    boolean isTopRoundedCorner(@BoundsPosition int pos, int id) {
        switch (pos) {
            case BOUNDS_POSITION_LEFT:
            case BOUNDS_POSITION_RIGHT:
                if (mRotation == ROTATION_270) {
                    return id == R.id.left ? false : true;
                } else {
                    return id == R.id.left ? true : false;
                }
            case BOUNDS_POSITION_TOP:
                return true;
            case BOUNDS_POSITION_BOTTOM:
                return false;
            default:
                throw new IllegalArgumentException("Unknown bounds position");
        }
    }

    private void updateHwLayerRoundedCornerSize() {
        if (mScreenDecorHwcLayer == null) {
            return;
        }

        final int topWidth = mRoundedCornerResDelegate.getTopRoundedSize().getWidth();
        final int bottomWidth = mRoundedCornerResDelegate.getBottomRoundedSize().getWidth();

        mScreenDecorHwcLayer.updateRoundedCornerSize(topWidth, bottomWidth);
    }

    private void updateRoundedCornerSize(Size sizeTop, Size sizeBottom) {

        if (mScreenDecorHwcLayer != null) {
            mScreenDecorHwcLayer.updateRoundedCornerSize(sizeTop.getWidth(), sizeBottom.getWidth());
            return;
        }

        if (mOverlays == null) {
            return;
        }
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; i++) {
            if (mOverlays[i] == null) {
                continue;
            }
            final ViewGroup overlayView = mOverlays[i].getRootView();
            setSize(overlayView.findViewById(R.id.left),
                    isTopRoundedCorner(i, R.id.left) ? sizeTop : sizeBottom);
            setSize(overlayView.findViewById(R.id.right),
                    isTopRoundedCorner(i, R.id.right) ? sizeTop : sizeBottom);
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
        private final List<Rect> mBounds = new ArrayList();
        private final Rect mBoundingRect = new Rect();
        private Rect mTotalBounds = new Rect();

        private int mColor = Color.BLACK;
        private int mRotation;
        private int mInitialPosition;
        private int mPosition;

        public DisplayCutoutView(Context context, @BoundsPosition int pos) {
            super(context);
            mInitialPosition = pos;
            paint.setColor(mColor);
            paint.setStyle(Paint.Style.FILL);
            setId(R.id.display_cutout);
            if (DEBUG) {
                getViewTreeObserver().addOnDrawListener(() -> Log.i(TAG,
                        getWindowTitleByPos(pos) + " drawn in rot " + mRotation));
            }
        }

        public void setColor(int color) {
            mColor = color;
            paint.setColor(mColor);
            invalidate();
        }

        @Override
        public void updateRotation(int rotation) {
            mRotation = rotation;
            updateCutout();
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        @Override
        public void updateCutout() {
            if (!isAttachedToWindow() || pendingRotationChange) {
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
            if (newVisible != getVisibility()) {
                setVisibility(newVisible);
            }
        }

        private void updateBoundingPath() {
            int lw = displayInfo.logicalWidth;
            int lh = displayInfo.logicalHeight;

            boolean flipped = displayInfo.rotation == ROTATION_90
                    || displayInfo.rotation == ROTATION_270;

            int dw = flipped ? lh : lw;
            int dh = flipped ? lw : lh;

            Path path = DisplayCutout.pathFromResources(
                    getResources(), getDisplay().getUniqueId(), dw, dh);
            if (path != null) {
                cutoutPath.set(path);
            } else {
                cutoutPath.reset();
            }
            Matrix m = new Matrix();
            transformPhysicalToLogicalCoordinates(displayInfo.rotation, dw, dh, m);
            cutoutPath.transform(m);
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
                // Make sure that our measured height encompases the protection
                mTotalBounds.union(mBoundingRect);
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
        // Pass -1 for ScreenDecorHwcLayer since it's a fullscreen window and has no specific
        // position.
        private final int mPosition;

        private RestartingPreDrawListener(View view, @BoundsPosition int position,
                int targetRotation) {
            mView = view;
            mTargetRotation = targetRotation;
            mPosition = position;
        }

        @Override
        public boolean onPreDraw() {
            mView.getViewTreeObserver().removeOnPreDrawListener(this);

            if (mTargetRotation == mRotation) {
                if (DEBUG) {
                    final String title = mPosition < 0 ? "ScreenDecorHwcLayer"
                            : getWindowTitleByPos(mPosition);
                    Log.i(TAG, title + " already in target rot "
                            + mTargetRotation + ", allow draw without restarting it");
                }
                return true;
            }

            mPendingRotationChange = false;
            // This changes the window attributes - we need to restart the traversal for them to
            // take effect.
            updateOrientation();
            if (DEBUG) {
                final String title = mPosition < 0 ? "ScreenDecorHwcLayer"
                        : getWindowTitleByPos(mPosition);
                Log.i(TAG, title
                        + " restarting listener fired, restarting draw for rot " + mRotation);
            }
            mView.invalidate();
            return false;
        }
    }

    /**
     * A pre-draw listener, that validates that the rotation we draw in matches the displays
     * rotation before continuing the draw.
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
            final int displayRotation = mContext.getDisplay().getRotation();
            if (displayRotation != mRotation && !mPendingRotationChange) {
                if (DEBUG) {
                    Log.i(TAG, "Drawing rot " + mRotation + ", but display is at rot "
                            + displayRotation + ". Restarting draw");
                }
                mView.invalidate();
                return false;
            }
            return true;
        }
    }
}
