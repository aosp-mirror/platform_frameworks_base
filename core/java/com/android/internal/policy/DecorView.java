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

package com.android.internal.policy;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;
import static android.view.InsetsState.clearsCompatInsets;
import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getMode;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsetsController.APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.flags.Flags.customizableWindowHeaders;
import static android.window.flags.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION;
import static android.window.flags.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS;

import static com.android.internal.policy.PhoneWindow.FEATURE_OPTIONS_PANEL;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PendingInsetsController;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewRootImpl;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowCallbacks;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.policy.PhoneWindow.PanelFeatureState;
import com.android.internal.policy.PhoneWindow.PhoneWindowMenuCallback;
import com.android.internal.view.FloatingActionMode;
import com.android.internal.view.RootViewSurfaceTaker;
import com.android.internal.view.StandaloneActionMode;
import com.android.internal.view.menu.ContextMenuBuilder;
import com.android.internal.view.menu.MenuHelper;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.BackgroundFallback;
import com.android.internal.widget.floatingtoolbar.FloatingToolbar;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** @hide */
public class DecorView extends FrameLayout implements RootViewSurfaceTaker, WindowCallbacks {
    private static final String TAG = "DecorView";

    private static final boolean DEBUG_MEASURE = false;

    private static final boolean SWEEP_OPEN_MENU = false;

    // The height of a window which has focus in DIP.
    public static final int DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP = 20;
    // The height of a window which has not in DIP.
    public static final int DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP = 5;

    private static final int SCRIM_LIGHT = 0xe6ffffff; // 90% white

    private static final int SCRIM_ALPHA = 0xcc000000; // 80% alpha

    public static final ColorViewAttributes STATUS_BAR_COLOR_VIEW_ATTRIBUTES =
            new ColorViewAttributes(FLAG_TRANSLUCENT_STATUS,
                    Gravity.TOP, Gravity.LEFT, Gravity.RIGHT,
                    Window.STATUS_BAR_BACKGROUND_TRANSITION_NAME,
                    com.android.internal.R.id.statusBarBackground,
                    WindowInsets.Type.statusBars());

    public static final ColorViewAttributes NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES =
            new ColorViewAttributes(FLAG_TRANSLUCENT_NAVIGATION,
                    Gravity.BOTTOM, Gravity.RIGHT, Gravity.LEFT,
                    Window.NAVIGATION_BAR_BACKGROUND_TRANSITION_NAME,
                    com.android.internal.R.id.navigationBarBackground,
                    WindowInsets.Type.navigationBars());

    // This is used to workaround an issue where the PiP shadow can be transparent if the window
    // background is transparent
    private static final ViewOutlineProvider PIP_OUTLINE_PROVIDER = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRect(0, 0, view.getWidth(), view.getHeight());
            outline.setAlpha(1f);
        }
    };

    // Cludge to address b/22668382: Set the shadow size to the maximum so that the layer
    // size calculation takes the shadow size into account. We set the elevation currently
    // to max until the first layout command has been executed.
    private boolean mAllowUpdateElevation = false;

    private boolean mElevationAdjustedForStack = false;

    // Keeps track of the picture-in-picture mode for the view shadow
    private boolean mIsInPictureInPictureMode;

    // Stores the previous outline provider prior to applying PIP_OUTLINE_PROVIDER
    private ViewOutlineProvider mLastOutlineProvider;

    int mDefaultOpacity = PixelFormat.OPAQUE;

    /** The feature ID of the panel, or -1 if this is the application's DecorView */
    private final int mFeatureId;

    private final Rect mDrawingBounds = new Rect();

    private final Rect mBackgroundPadding = new Rect();

    private final Rect mFramePadding = new Rect();

    private final Rect mFrameOffsets = new Rect();

    private boolean mChanging;

    private Drawable mMenuBackground;
    private boolean mWatchingForMenu;
    private int mDownY;

    ActionMode mPrimaryActionMode;
    private ActionMode mFloatingActionMode;
    private ActionBarContextView mPrimaryActionModeView;
    private PopupWindow mPrimaryActionModePopup;
    private Runnable mShowPrimaryActionModePopup;
    private ViewTreeObserver.OnPreDrawListener mFloatingToolbarPreDrawListener;
    private View mFloatingActionModeOriginatingView;
    private FloatingToolbar mFloatingToolbar;
    private ObjectAnimator mFadeAnim;

    // View added at runtime to draw under the status bar area
    private View mStatusGuard;

    private final ColorViewState mStatusColorViewState =
            new ColorViewState(STATUS_BAR_COLOR_VIEW_ATTRIBUTES);
    private final ColorViewState mNavigationColorViewState =
            new ColorViewState(NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES);

    private final Interpolator mShowInterpolator;
    private final Interpolator mHideInterpolator;
    private final int mBarEnterExitDuration;
    final boolean mForceWindowDrawsBarBackgrounds;
    private final int mSemiTransparentBarColor;

    private final BackgroundFallback mBackgroundFallback = new BackgroundFallback();

    private int mLastTopInset = 0;
    @UnsupportedAppUsage
    private int mLastBottomInset = 0;
    @UnsupportedAppUsage
    private int mLastRightInset = 0;
    @UnsupportedAppUsage
    private int mLastLeftInset = 0;
    private boolean mLastHasTopStableInset = false;
    private boolean mLastHasBottomStableInset = false;
    private boolean mLastHasRightStableInset = false;
    private boolean mLastHasLeftStableInset = false;
    private int mLastWindowFlags = 0;
    private @InsetsType int mLastForceConsumingTypes = 0;
    private boolean mLastForceConsumingOpaqueCaptionBar = false;
    private @InsetsType int mLastSuppressScrimTypes = 0;

    private int mRootScrollY = 0;

    @UnsupportedAppUsage
    private PhoneWindow mWindow;

    ViewGroup mContentRoot;

    private Rect mTempRect;

    private boolean mWindowResizeCallbacksAdded = false;
    private Drawable mOriginalBackgroundDrawable;
    private Drawable mLastOriginalBackgroundDrawable;
    private BackgroundBlurDrawable mBackgroundBlurDrawable;
    private BackgroundBlurDrawable mLastBackgroundBlurDrawable;

    /**
     * Temporary holder for a window background when it is set before {@link #mWindow} is
     * initialized. It will be set as the actual background once {@link #setWindow(PhoneWindow)} is
     * called.
     */
    @Nullable
    private Drawable mPendingWindowBackground;

    String mLogTag = TAG;
    private final Rect mFloatingInsets = new Rect();
    private boolean mApplyFloatingVerticalInsets = false;
    private boolean mApplyFloatingHorizontalInsets = false;

    private final Paint mLegacyNavigationBarBackgroundPaint = new Paint();
    private Insets mBackgroundInsets = Insets.NONE;
    private Insets mLastBackgroundInsets = Insets.NONE;
    private boolean mDrawLegacyNavigationBarBackground;
    private boolean mDrawLegacyNavigationBarBackgroundHandled;

    private PendingInsetsController mPendingInsetsController = new PendingInsetsController();

    private int mOriginalBackgroundBlurRadius = 0;
    private int mBackgroundBlurRadius = 0;
    private boolean mCrossWindowBlurEnabled;
    private final ViewTreeObserver.OnPreDrawListener mBackgroundBlurOnPreDrawListener = () -> {
        updateBackgroundBlurCorners();
        return true;
    };
    private Consumer<Boolean> mCrossWindowBlurEnabledListener;

    private final WearGestureInterceptionDetector mWearGestureInterceptionDetector;

    DecorView(Context context, int featureId, PhoneWindow window,
            WindowManager.LayoutParams params) {
        super(context);
        mFeatureId = featureId;
        mShowInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.linear_out_slow_in);
        mHideInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.fast_out_linear_in);

        mBarEnterExitDuration = context.getResources().getInteger(
                R.integer.dock_enter_exit_duration);
        mForceWindowDrawsBarBackgrounds = context.getResources().getBoolean(
                R.bool.config_forceWindowDrawsStatusBarBackground)
                && params.type != TYPE_INPUT_METHOD
                && context.getApplicationInfo().targetSdkVersion >= N;
        mSemiTransparentBarColor = context.getResources().getColor(
                R.color.system_bar_background_semi_transparent, null /* theme */);

        setWindow(window);

        updateLogTag(params);

        mLegacyNavigationBarBackgroundPaint.setColor(Color.BLACK);

        mWearGestureInterceptionDetector =
                WearGestureInterceptionDetector.isEnabled(context)
                        ? new WearGestureInterceptionDetector(context, this)
                        : null;
    }

    void setBackgroundFallback(@Nullable Drawable fallbackDrawable) {
        mBackgroundFallback.setDrawable(fallbackDrawable);
        setWillNotDraw(getBackground() == null && !mBackgroundFallback.hasFallback());
    }

    @TestApi
    public @Nullable Drawable getBackgroundFallback() {
        return mBackgroundFallback.getDrawable();
    }

    @Nullable View getStatusBarBackgroundView() {
        return mStatusColorViewState.view;
    }

    @Nullable View getNavigationBarBackgroundView() {
        return mNavigationColorViewState.view;
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);

        mBackgroundFallback.draw(this, mContentRoot, c, mWindow.mContentParent,
                mStatusColorViewState.view, mNavigationColorViewState.view);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int action = event.getAction();
        final boolean isDown = action == KeyEvent.ACTION_DOWN;

        if (isDown && (event.getRepeatCount() == 0)) {
            // First handle chording of panel key: if a panel key is held
            // but not released, try to execute a shortcut in it.
            if ((mWindow.mPanelChordingKey > 0) && (mWindow.mPanelChordingKey != keyCode)) {
                boolean handled = dispatchKeyShortcutEvent(event);
                if (handled) {
                    return true;
                }
            }

            // If a panel is open, perform a shortcut on it without the
            // chorded panel key
            if ((mWindow.mPreparedPanel != null) && mWindow.mPreparedPanel.isOpen) {
                if (mWindow.performPanelShortcut(mWindow.mPreparedPanel, keyCode, event, 0)) {
                    return true;
                }
            }
        }

        if (!mWindow.isDestroyed()) {
            final Window.Callback cb = mWindow.getCallback();
            final boolean handled = cb != null && mFeatureId < 0 ? cb.dispatchKeyEvent(event)
                    : super.dispatchKeyEvent(event);
            if (handled) {
                return true;
            }
        }

        return isDown ? mWindow.onKeyDown(mFeatureId, event.getKeyCode(), event)
                : mWindow.onKeyUp(mFeatureId, event.getKeyCode(), event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent ev) {
        // If the panel is already prepared, then perform the shortcut using it.
        boolean handled;
        if (mWindow.mPreparedPanel != null) {
            handled = mWindow.performPanelShortcut(mWindow.mPreparedPanel, ev.getKeyCode(), ev,
                    Menu.FLAG_PERFORM_NO_CLOSE);
            if (handled) {
                if (mWindow.mPreparedPanel != null) {
                    mWindow.mPreparedPanel.isHandled = true;
                }
                return true;
            }
        }

        // Shortcut not handled by the panel.  Dispatch to the view hierarchy.
        final Window.Callback cb = mWindow.getCallback();
        handled = cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchKeyShortcutEvent(ev) : super.dispatchKeyShortcutEvent(ev);
        if (handled) {
            return true;
        }

        // If the panel is not prepared, then we may be trying to handle a shortcut key
        // combination such as Control+C.  Temporarily prepare the panel then mark it
        // unprepared again when finished to ensure that the panel will again be prepared
        // the next time it is shown for real.
        PhoneWindow.PanelFeatureState st =
                mWindow.getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
        if (st != null && mWindow.mPreparedPanel == null) {
            mWindow.preparePanel(st, ev);
            handled = mWindow.performPanelShortcut(st, ev.getKeyCode(), ev,
                    Menu.FLAG_PERFORM_NO_CLOSE);
            st.isPrepared = false;
            if (handled) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchTouchEvent(ev) : super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchTrackballEvent(ev) : super.dispatchTrackballEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchGenericMotionEvent(ev) : super.dispatchGenericMotionEvent(ev);
    }

    public boolean superDispatchKeyEvent(KeyEvent event) {
        // Give priority to closing action modes if applicable.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            final int action = event.getAction();
            // Back cancels action modes first.
            if (mPrimaryActionMode != null) {
                if (action == KeyEvent.ACTION_UP) {
                    mPrimaryActionMode.finish();
                }
                return true;
            }
        }

        if (super.dispatchKeyEvent(event)) {
            return true;
        }

        return (getViewRootImpl() != null) && getViewRootImpl().dispatchUnhandledKeyEvent(event);
    }

    public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
        return super.dispatchKeyShortcutEvent(event);
    }

    public boolean superDispatchTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    public boolean superDispatchTrackballEvent(MotionEvent event) {
        return super.dispatchTrackballEvent(event);
    }

    public boolean superDispatchGenericMotionEvent(MotionEvent event) {
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return onInterceptTouchEvent(event);
    }

    private boolean isOutOfInnerBounds(int x, int y) {
        return x < 0 || y < 0 || x > getWidth() || y > getHeight();
    }

    private boolean isOutOfBounds(int x, int y) {
        return x < -5 || y < -5 || x > (getWidth() + 5)
                || y > (getHeight() + 5);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (mFeatureId >= 0) {
            if (action == MotionEvent.ACTION_DOWN) {
                int x = (int)event.getX();
                int y = (int)event.getY();
                if (isOutOfBounds(x, y)) {
                    mWindow.closePanel(mFeatureId);
                    return true;
                }
            }
        }

        ViewRootImpl viewRootImpl = getViewRootImpl();
        if (viewRootImpl != null) {
            viewRootImpl.getOnBackInvokedDispatcher().onMotionEvent(event);
            // Intercept touch if back gesture is in progress.
            if (viewRootImpl.getOnBackInvokedDispatcher().isBackGestureInProgress()) {
                return true;
            }
        }
        if (viewRootImpl != null && mWearGestureInterceptionDetector != null) {
            boolean wasIntercepting = mWearGestureInterceptionDetector.isIntercepting();
            boolean intercepting = mWearGestureInterceptionDetector.onInterceptTouchEvent(event);
            if (wasIntercepting != intercepting) {
                viewRootImpl.updateDecorViewGestureInterception(intercepting);
            }
            if (intercepting) {
                return true;
            }
        }

        if (!SWEEP_OPEN_MENU) {
            return false;
        }

        if (mFeatureId >= 0) {
            if (action == MotionEvent.ACTION_DOWN) {
                Log.i(mLogTag, "Watchiing!");
                mWatchingForMenu = true;
                mDownY = (int) event.getY();
                return false;
            }

            if (!mWatchingForMenu) {
                return false;
            }

            int y = (int)event.getY();
            if (action == MotionEvent.ACTION_MOVE) {
                if (y > (mDownY+30)) {
                    Log.i(mLogTag, "Closing!");
                    mWindow.closePanel(mFeatureId);
                    mWatchingForMenu = false;
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP) {
                mWatchingForMenu = false;
            }

            return false;
        }

        //Log.i(mLogTag, "Intercept: action=" + action + " y=" + event.getY()
        //        + " (in " + getHeight() + ")");

        if (action == MotionEvent.ACTION_DOWN) {
            int y = (int)event.getY();
            if (y >= (getHeight()-5) && !mWindow.hasChildren()) {
                Log.i(mLogTag, "Watching!");
                mWatchingForMenu = true;
            }
            return false;
        }

        if (!mWatchingForMenu) {
            return false;
        }

        int y = (int)event.getY();
        if (action == MotionEvent.ACTION_MOVE) {
            if (y < (getHeight()-30)) {
                Log.i(mLogTag, "Opening!");
                mWindow.openPanel(Window.FEATURE_OPTIONS_PANEL, new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU));
                mWatchingForMenu = false;
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            mWatchingForMenu = false;
        }

        return false;
    }

    @Override
    public void sendAccessibilityEvent(int eventType) {
        if (!AccessibilityManager.getInstance(mContext).isEnabled()) {
            return;
        }

        // if we are showing a feature that should be announced and one child
        // make this child the event source since this is the feature itself
        // otherwise the callback will take over and announce its client
        if ((mFeatureId == Window.FEATURE_OPTIONS_PANEL ||
                mFeatureId == Window.FEATURE_CONTEXT_MENU ||
                mFeatureId == Window.FEATURE_PROGRESS ||
                mFeatureId == Window.FEATURE_INDETERMINATE_PROGRESS)
                && getChildCount() == 1) {
            getChildAt(0).sendAccessibilityEvent(eventType);
        } else {
            super.sendAccessibilityEvent(eventType);
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed()) {
            if (cb.dispatchPopulateAccessibilityEvent(event)) {
                return true;
            }
        }
        return super.dispatchPopulateAccessibilityEventInternal(event);
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        if (changed) {
            final Rect drawingBounds = mDrawingBounds;
            getDrawingRect(drawingBounds);

            Drawable fg = getForeground();
            if (fg != null) {
                final Rect frameOffsets = mFrameOffsets;
                drawingBounds.left += frameOffsets.left;
                drawingBounds.top += frameOffsets.top;
                drawingBounds.right -= frameOffsets.right;
                drawingBounds.bottom -= frameOffsets.bottom;
                fg.setBounds(drawingBounds);
                final Rect framePadding = mFramePadding;
                drawingBounds.left += framePadding.left - frameOffsets.left;
                drawingBounds.top += framePadding.top - frameOffsets.top;
                drawingBounds.right -= framePadding.right - frameOffsets.right;
                drawingBounds.bottom -= framePadding.bottom - frameOffsets.bottom;
            }

            // Need to call super here as we pretend to be having the original background.
            Drawable bg = super.getBackground();
            if (bg != null) {
                bg.setBounds(drawingBounds);
            }

            if (SWEEP_OPEN_MENU) {
                if (mMenuBackground == null && mFeatureId < 0
                        && mWindow.getAttributes().height
                        == WindowManager.LayoutParams.MATCH_PARENT) {
                    mMenuBackground = getContext().getDrawable(
                            R.drawable.menu_background);
                }
                if (mMenuBackground != null) {
                    mMenuBackground.setBounds(drawingBounds.left,
                            drawingBounds.bottom-6, drawingBounds.right,
                            drawingBounds.bottom+20);
                }
            }
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final Resources res = getContext().getResources();
        final DisplayMetrics metrics = res.getDisplayMetrics();
        final boolean isPortrait =
                getResources().getConfiguration().orientation == ORIENTATION_PORTRAIT;

        final int widthMode = getMode(widthMeasureSpec);
        final int heightMode = getMode(heightMeasureSpec);

        boolean fixedWidth = false;
        mApplyFloatingHorizontalInsets = false;
        if (widthMode == AT_MOST) {
            final TypedValue tvw = isPortrait ? mWindow.mFixedWidthMinor : mWindow.mFixedWidthMajor;
            if (tvw != null && tvw.type != TypedValue.TYPE_NULL) {
                final int w;
                if (tvw.type == TypedValue.TYPE_DIMENSION) {
                    w = (int) tvw.getDimension(metrics);
                } else if (tvw.type == TypedValue.TYPE_FRACTION) {
                    w = (int) tvw.getFraction(metrics.widthPixels, metrics.widthPixels);
                } else {
                    w = 0;
                }
                if (DEBUG_MEASURE) Log.d(mLogTag, "Fixed width: " + w);
                final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
                if (w > 0) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(w, widthSize), EXACTLY);
                    fixedWidth = true;
                } else {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            widthSize - mFloatingInsets.left - mFloatingInsets.right,
                            AT_MOST);
                    mApplyFloatingHorizontalInsets = true;
                }
            }
        }

        mApplyFloatingVerticalInsets = false;
        if (heightMode == AT_MOST) {
            final TypedValue tvh = isPortrait ? mWindow.mFixedHeightMajor
                    : mWindow.mFixedHeightMinor;
            if (tvh != null && tvh.type != TypedValue.TYPE_NULL) {
                final int h;
                if (tvh.type == TypedValue.TYPE_DIMENSION) {
                    h = (int) tvh.getDimension(metrics);
                } else if (tvh.type == TypedValue.TYPE_FRACTION) {
                    h = (int) tvh.getFraction(metrics.heightPixels, metrics.heightPixels);
                } else {
                    h = 0;
                }
                if (DEBUG_MEASURE) Log.d(mLogTag, "Fixed height: " + h);
                final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
                if (h > 0) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(h, heightSize), EXACTLY);
                } else if ((mWindow.getAttributes().flags & FLAG_LAYOUT_IN_SCREEN) == 0) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                            heightSize - mFloatingInsets.top - mFloatingInsets.bottom, AT_MOST);
                    mApplyFloatingVerticalInsets = true;
                }
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        boolean measure = false;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, EXACTLY);

        if (!fixedWidth && widthMode == AT_MOST) {
            final TypedValue tv = isPortrait ? mWindow.mMinWidthMinor : mWindow.mMinWidthMajor;
            final float availableWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    res.getConfiguration().screenWidthDp, metrics);
            if (tv.type != TypedValue.TYPE_NULL) {
                final int min;
                if (tv.type == TypedValue.TYPE_DIMENSION) {
                    min = (int) tv.getDimension(metrics);
                } else if (tv.type == TypedValue.TYPE_FRACTION) {
                    min = (int) tv.getFraction(availableWidth, availableWidth);
                } else {
                    min = 0;
                }
                if (DEBUG_MEASURE) Log.d(mLogTag, "Adjust for min width: " + min + ", value::"
                        + tv.coerceToString() + ", mAvailableWidth=" + availableWidth);

                if (width < min) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(min, EXACTLY);
                    measure = true;
                }
            }
        }

        // TODO: Support height?

        if (measure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mApplyFloatingVerticalInsets) {
            offsetTopAndBottom(mFloatingInsets.top);
        }
        if (mApplyFloatingHorizontalInsets) {
            offsetLeftAndRight(mFloatingInsets.left);
        }

        // If the application changed its SystemUI metrics, we might also have to adapt
        // our shadow elevation.
        updateElevation();
        mAllowUpdateElevation = true;

        if (changed && mDrawLegacyNavigationBarBackground) {
            getViewRootImpl().requestInvalidateRootRenderNode();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (mMenuBackground != null) {
            mMenuBackground.draw(canvas);
        }
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        return showContextMenuForChildInternal(originalView, Float.NaN, Float.NaN);
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return showContextMenuForChildInternal(originalView, x, y);
    }

    private boolean showContextMenuForChildInternal(View originalView,
            float x, float y) {
        // Only allow one context menu at a time.
        if (mWindow.mContextMenuHelper != null) {
            mWindow.mContextMenuHelper.dismiss();
            mWindow.mContextMenuHelper = null;
        }

        // Reuse the context menu builder.
        final PhoneWindowMenuCallback callback = mWindow.mContextMenuCallback;
        if (mWindow.mContextMenu == null) {
            mWindow.mContextMenu = new ContextMenuBuilder(getContext());
            mWindow.mContextMenu.setCallback(callback);
        } else {
            mWindow.mContextMenu.clearAll();
        }

        final MenuHelper helper;
        final boolean isPopup = !Float.isNaN(x) && !Float.isNaN(y);
        if (isPopup) {
            helper = mWindow.mContextMenu.showPopup(originalView.getContext(), originalView, x, y);
        } else {
            helper = mWindow.mContextMenu.showDialog(originalView, originalView.getWindowToken());
        }

        if (helper != null) {
            // If it's a dialog, the callback needs to handle showing
            // sub-menus. Either way, the callback is required for propagating
            // selection to Context.onContextMenuItemSelected().
            callback.setShowDialogForSubmenu(!isPopup);
            helper.setPresenterCallback(callback);
        }

        mWindow.mContextMenuHelper = helper;
        return helper != null;
    }

    @Override
    public ActionMode startActionModeForChild(View originalView,
            ActionMode.Callback callback) {
        return startActionModeForChild(originalView, callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionModeForChild(
            View child, ActionMode.Callback callback, int type) {
        return startActionMode(child, callback, type);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        return startActionMode(this, callback, type);
    }

    private ActionMode startActionMode(
            View originatingView, ActionMode.Callback callback, int type) {
        ActionMode.Callback2 wrappedCallback = new ActionModeCallback2Wrapper(callback);
        ActionMode mode = null;
        if (mWindow.getCallback() != null && !mWindow.isDestroyed()) {
            try {
                mode = mWindow.getCallback().onWindowStartingActionMode(wrappedCallback, type);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement the typed version of this method.
                if (type == ActionMode.TYPE_PRIMARY) {
                    try {
                        mode = mWindow.getCallback().onWindowStartingActionMode(
                                wrappedCallback);
                    } catch (AbstractMethodError ame2) {
                        // Older apps might not implement this callback method at all.
                    }
                }
            }
        }
        if (mode != null) {
            if (mode.getType() == ActionMode.TYPE_PRIMARY) {
                cleanupPrimaryActionMode();
                mPrimaryActionMode = mode;
            } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
                if (mFloatingActionMode != null) {
                    mFloatingActionMode.finish();
                }
                mFloatingActionMode = mode;
            }
        } else {
            mode = createActionMode(type, wrappedCallback, originatingView);
            if (mode != null && wrappedCallback.onCreateActionMode(mode, mode.getMenu())) {
                setHandledActionMode(mode);
            } else {
                mode = null;
            }
        }
        if (mode != null && mWindow.getCallback() != null && !mWindow.isDestroyed()) {
            try {
                mWindow.getCallback().onActionModeStarted(mode);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement this callback method.
            }
        }
        return mode;
    }

    private void cleanupPrimaryActionMode() {
        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.finish();
            mPrimaryActionMode = null;
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
        }
    }

    private void cleanupFloatingActionModeViews() {
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }
        if (mFloatingActionModeOriginatingView != null) {
            if (mFloatingToolbarPreDrawListener != null) {
                mFloatingActionModeOriginatingView.getViewTreeObserver()
                    .removeOnPreDrawListener(mFloatingToolbarPreDrawListener);
                mFloatingToolbarPreDrawListener = null;
            }
            mFloatingActionModeOriginatingView = null;
        }
    }

    void startChanging() {
        mChanging = true;
    }

    void finishChanging() {
        mChanging = false;
        drawableChanged();
    }

    public void setWindowBackground(Drawable drawable) {
        if (mWindow == null) {
            mPendingWindowBackground = drawable;
            return;
        }
        if (mOriginalBackgroundDrawable != drawable) {
            mOriginalBackgroundDrawable = drawable;
            updateBackgroundDrawable();
            if (mWindow.mEdgeToEdgeEnforced && !mWindow.mNavigationBarColorSpecified
                    && drawable instanceof ColorDrawable) {
                final int color = ((ColorDrawable) drawable).getColor();
                final boolean lightBar = Color.valueOf(color).luminance() > 0.5f;
                getWindowInsetsController().setSystemBarsAppearance(
                        lightBar ? APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS : 0,
                        APPEARANCE_FORCE_LIGHT_NAVIGATION_BARS);
                mWindow.mNavigationBarColor = color;
                updateColorViews(null /* insets */, false /* animate */);
            }
            if (drawable != null) {
                drawable.getPadding(mBackgroundPadding);
            } else if (mWindow.mBackgroundDrawable != null) {
                mWindow.mBackgroundDrawable.getPadding(mBackgroundPadding);
            } else if (mWindow.mBackgroundFallbackDrawable != null) {
                mWindow.mBackgroundFallbackDrawable.getPadding(mBackgroundPadding);
            } else {
                mBackgroundPadding.setEmpty();
            }
            if (!View.sBrokenWindowBackground) {
                drawableChanged();
            }
        }
    }

    @Override
    public void setBackgroundDrawable(Drawable background) {
        setWindowBackground(background);
    }

    public void setWindowFrame(Drawable drawable) {
        if (getForeground() != drawable) {
            setForeground(drawable);
            if (drawable != null) {
                drawable.getPadding(mFramePadding);
            } else {
                mFramePadding.setEmpty();
            }
            drawableChanged();
        }
    }

    @Override
    public void onWindowSystemUiVisibilityChanged(int visible) {
        updateColorViews(null /* insets */, true /* animate */);

        if (mStatusGuard != null && mStatusGuard.getVisibility() == VISIBLE) {
            updateStatusGuardColor();
        }
    }

    @Override
    public void onSystemBarAppearanceChanged(@WindowInsetsController.Appearance int appearance) {
        updateColorViews(null /* insets */, true /* animate */);
        if (mWindow != null) {
            mWindow.dispatchOnSystemBarAppearanceChanged(appearance);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        final WindowManager.LayoutParams attrs = mWindow.getAttributes();
        mFloatingInsets.setEmpty();
        if ((attrs.flags & FLAG_LAYOUT_IN_SCREEN) == 0) {
            // For dialog windows we want to make sure they don't go over the status bar or nav bar.
            // We consume the system insets and we will reuse them later during the measure phase.
            // We allow the app to ignore this and handle insets itself by using
            // FLAG_LAYOUT_IN_SCREEN.
            if (attrs.height == WindowManager.LayoutParams.WRAP_CONTENT) {
                mFloatingInsets.top = insets.getSystemWindowInsetTop();
                mFloatingInsets.bottom = insets.getSystemWindowInsetBottom();
                insets = insets.inset(0, insets.getSystemWindowInsetTop(),
                        0, insets.getSystemWindowInsetBottom());
            }
            if (mWindow.getAttributes().width == WindowManager.LayoutParams.WRAP_CONTENT) {
                mFloatingInsets.left = insets.getSystemWindowInsetTop();
                mFloatingInsets.right = insets.getSystemWindowInsetBottom();
                insets = insets.inset(insets.getSystemWindowInsetLeft(), 0,
                        insets.getSystemWindowInsetRight(), 0);
            }
        }
        mFrameOffsets.set(insets.getSystemWindowInsetsAsRect());
        insets = updateColorViews(insets, true /* animate */);
        insets = updateStatusGuard(insets);
        if (getForeground() != null) {
            drawableChanged();
        }
        return insets;
    }

    @Override
    public boolean isTransitionGroup() {
        return false;
    }

    public static boolean isNavBarToRightEdge(int bottomInset, int rightInset) {
        return bottomInset == 0 && rightInset > 0;
    }

    public static boolean isNavBarToLeftEdge(int bottomInset, int leftInset) {
        return bottomInset == 0 && leftInset > 0;
    }

    public static int getNavBarSize(int bottomInset, int rightInset, int leftInset) {
        return isNavBarToRightEdge(bottomInset, rightInset) ? rightInset
                : isNavBarToLeftEdge(bottomInset, leftInset) ? leftInset : bottomInset;
    }

    public static void getNavigationBarRect(int canvasWidth, int canvasHeight, Rect systemBarInsets,
            Rect outRect, float scale) {
        final int bottomInset = (int) (systemBarInsets.bottom * scale);
        final int leftInset = (int) (systemBarInsets.left * scale);
        final int rightInset = (int) (systemBarInsets.right * scale);
        final int size = getNavBarSize(bottomInset, rightInset, leftInset);
        if (isNavBarToRightEdge(bottomInset, rightInset)) {
            outRect.set(canvasWidth - size, 0, canvasWidth, canvasHeight);
        } else if (isNavBarToLeftEdge(bottomInset, leftInset)) {
            outRect.set(0, 0, size, canvasHeight);
        } else {
            outRect.set(0, canvasHeight - size, canvasWidth, canvasHeight);
        }
    }

    WindowInsets updateColorViews(WindowInsets insets, boolean animate) {
        WindowManager.LayoutParams attrs = mWindow.getAttributes();
        int sysUiVisibility = attrs.systemUiVisibility | getWindowSystemUiVisibility();

        final ViewRootImpl viewRoot = getViewRootImpl();
        final WindowInsetsController controller = getWindowInsetsController();
        final @InsetsType int requestedVisibleTypes = controller.getRequestedVisibleTypes();
        final @Appearance int appearance = viewRoot != null
                ? viewRoot.mWindowAttributes.insetsFlags.appearance
                : controller.getSystemBarsAppearance();

        // IME is an exceptional floating window that requires color view.
        final boolean isImeWindow =
                mWindow.getAttributes().type == WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        if (!mWindow.mIsFloating || isImeWindow) {
            boolean disallowAnimate = !isLaidOut();
            disallowAnimate |= ((mLastWindowFlags ^ attrs.flags)
                    & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            mLastWindowFlags = attrs.flags;

            if (insets != null) {
                mLastForceConsumingTypes = insets.getForceConsumingTypes();
                mLastForceConsumingOpaqueCaptionBar = insets.isForceConsumingOpaqueCaptionBar();

                final boolean clearsCompatInsets = clearsCompatInsets(attrs.type, attrs.flags,
                        getResources().getConfiguration().windowConfiguration.getActivityType(),
                        mLastForceConsumingTypes);
                final @InsetsType int compatInsetsTypes =
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                final Insets stableBarInsets = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars());
                final Insets systemInsets = clearsCompatInsets
                        ? Insets.NONE
                        : Insets.min(insets.getInsets(compatInsetsTypes), stableBarInsets);
                mLastTopInset = systemInsets.top;
                mLastBottomInset = systemInsets.bottom;
                mLastRightInset = systemInsets.right;
                mLastLeftInset = systemInsets.left;

                // Don't animate if the presence of stable insets has changed, because that
                // indicates that the window was either just added and received them for the
                // first time, or the window size or position has changed.
                boolean hasTopStableInset = stableBarInsets.top != 0;
                disallowAnimate |= (hasTopStableInset != mLastHasTopStableInset);
                mLastHasTopStableInset = hasTopStableInset;

                boolean hasBottomStableInset = stableBarInsets.bottom != 0;
                disallowAnimate |= (hasBottomStableInset != mLastHasBottomStableInset);
                mLastHasBottomStableInset = hasBottomStableInset;

                boolean hasRightStableInset = stableBarInsets.right != 0;
                disallowAnimate |= (hasRightStableInset != mLastHasRightStableInset);
                mLastHasRightStableInset = hasRightStableInset;

                boolean hasLeftStableInset = stableBarInsets.left != 0;
                disallowAnimate |= (hasLeftStableInset != mLastHasLeftStableInset);
                mLastHasLeftStableInset = hasLeftStableInset;

                mLastSuppressScrimTypes = insets.getSuppressScrimTypes();
            }

            boolean navBarToRightEdge = isNavBarToRightEdge(mLastBottomInset, mLastRightInset);
            boolean navBarToLeftEdge = isNavBarToLeftEdge(mLastBottomInset, mLastLeftInset);
            int navBarSize = getNavBarSize(mLastBottomInset, mLastRightInset, mLastLeftInset);
            updateColorViewInt(mNavigationColorViewState, calculateNavigationBarColor(appearance),
                    mWindow.mNavigationBarDividerColor, navBarSize,
                    navBarToRightEdge || navBarToLeftEdge, navBarToLeftEdge,
                    0 /* sideInset */, animate && !disallowAnimate,
                    mForceWindowDrawsBarBackgrounds, requestedVisibleTypes);
            boolean oldDrawLegacy = mDrawLegacyNavigationBarBackground;
            mDrawLegacyNavigationBarBackground =
                    ((requestedVisibleTypes | mLastForceConsumingTypes)
                            & WindowInsets.Type.navigationBars()) != 0
                    && (mWindow.getAttributes().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                    && navBarSize > 0;
            if (oldDrawLegacy != mDrawLegacyNavigationBarBackground) {
                mDrawLegacyNavigationBarBackgroundHandled =
                        mWindow.onDrawLegacyNavigationBarBackgroundChanged(
                                mDrawLegacyNavigationBarBackground);
                if (viewRoot != null) {
                    viewRoot.requestInvalidateRootRenderNode();
                }
            }

            boolean statusBarNeedsRightInset = navBarToRightEdge
                    && mNavigationColorViewState.present;
            boolean statusBarNeedsLeftInset = navBarToLeftEdge
                    && mNavigationColorViewState.present;
            int statusBarSideInset = statusBarNeedsRightInset ? mLastRightInset
                    : statusBarNeedsLeftInset ? mLastLeftInset : 0;
            int statusBarColor = calculateStatusBarColor(appearance);
            updateColorViewInt(mStatusColorViewState, statusBarColor, 0,
                    mLastTopInset, false /* matchVertical */, statusBarNeedsLeftInset,
                    statusBarSideInset, animate && !disallowAnimate,
                    mForceWindowDrawsBarBackgrounds, requestedVisibleTypes);
        }

        // When we expand the window with FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
        // mForceWindowDrawsBarBackgrounds, we still need to ensure that the rest of the view
        // hierarchy doesn't notice it, unless they've explicitly asked for it.
        //
        // Note: We don't need to check for IN_SCREEN or INSET_DECOR because unlike the status bar,
        // these flags wouldn't make the window draw behind the navigation bar, unless
        // LAYOUT_HIDE_NAVIGATION was set.
        //
        // Note: Once the app uses the R+ Window.setDecorFitsSystemWindows(false) API we no longer
        // consume insets because they might no longer set SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.
        boolean hideNavigation = (sysUiVisibility & SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
                || (requestedVisibleTypes & WindowInsets.Type.navigationBars()) == 0;
        boolean decorFitsSystemWindows = mWindow.mDecorFitsSystemWindows;
        boolean forceConsumingNavBar =
                ((mForceWindowDrawsBarBackgrounds || mDrawLegacyNavigationBarBackgroundHandled)
                        && (attrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0
                        && decorFitsSystemWindows
                        && !hideNavigation)
                || ((mLastForceConsumingTypes & WindowInsets.Type.navigationBars()) != 0
                        && hideNavigation);

        boolean consumingNavBar =
                ((attrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0
                        && decorFitsSystemWindows
                        && !hideNavigation)
                || forceConsumingNavBar;

        // If we didn't request fullscreen layout, but we still got it because of the
        // mForceWindowDrawsBarBackgrounds flag, also consume top inset.
        // If we should always consume system bars, only consume that if the app wanted to go to
        // fullscreen, as otherwise we can expect the app to handle it.
        boolean fullscreen = (sysUiVisibility & SYSTEM_UI_FLAG_FULLSCREEN) != 0
                || (attrs.flags & FLAG_FULLSCREEN) != 0;
        final boolean hideStatusBar = fullscreen
                || (requestedVisibleTypes & WindowInsets.Type.statusBars()) == 0;
        boolean consumingStatusBar =
                ((sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) == 0
                        && decorFitsSystemWindows
                        && (attrs.flags & FLAG_LAYOUT_IN_SCREEN) == 0
                        && (attrs.flags & FLAG_LAYOUT_INSET_DECOR) == 0
                        && mForceWindowDrawsBarBackgrounds
                        && mLastTopInset != 0)
                || ((mLastForceConsumingTypes & WindowInsets.Type.statusBars()) != 0
                        && hideStatusBar);

        final boolean hideCaptionBar = fullscreen
                || (requestedVisibleTypes & WindowInsets.Type.captionBar()) == 0;
        final boolean consumingCaptionBar =
                ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION.isTrue()
                        && ((mLastForceConsumingTypes & WindowInsets.Type.captionBar()) != 0
                        && hideCaptionBar);

        final boolean isOpaqueCaptionBar = customizableWindowHeaders()
                && (appearance & APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND) == 0;
        final boolean consumingOpaqueCaptionBar =
                ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS.isTrue()
                        && mLastForceConsumingOpaqueCaptionBar
                        && isOpaqueCaptionBar;

        final int consumedTop =
                (consumingStatusBar || consumingCaptionBar || consumingOpaqueCaptionBar)
                        ? mLastTopInset : 0;
        int consumedRight = consumingNavBar ? mLastRightInset : 0;
        int consumedBottom = consumingNavBar ? mLastBottomInset : 0;
        int consumedLeft = consumingNavBar ? mLastLeftInset : 0;

        if (mContentRoot != null
                && mContentRoot.getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams lp = (MarginLayoutParams) mContentRoot.getLayoutParams();
            if (lp.topMargin != consumedTop || lp.rightMargin != consumedRight
                    || lp.bottomMargin != consumedBottom || lp.leftMargin != consumedLeft) {
                lp.topMargin = consumedTop;
                lp.rightMargin = consumedRight;
                lp.bottomMargin = consumedBottom;
                lp.leftMargin = consumedLeft;
                mContentRoot.setLayoutParams(lp);

                if (insets == null) {
                    // The insets have changed, but we're not currently in the process
                    // of dispatching them.
                    requestApplyInsets();
                }
            }
            if (insets != null && (consumedLeft > 0
                    || consumedTop > 0
                    || consumedRight > 0
                    || consumedBottom > 0)) {
                insets = insets.inset(consumedLeft, consumedTop, consumedRight, consumedBottom);
            }
        }

        if (forceConsumingNavBar && !hideNavigation && !mDrawLegacyNavigationBarBackgroundHandled) {
            mBackgroundInsets = Insets.of(mLastLeftInset, 0, mLastRightInset, mLastBottomInset);
        } else {
            mBackgroundInsets = Insets.NONE;
        }
        updateBackgroundDrawable();

        return insets;
    }

    /**
     * Updates the background drawable, applying padding to it in case we {@link #mBackgroundInsets}
     * are set.
     */
    private void updateBackgroundDrawable() {
        // Background insets can be null if super constructor calls setBackgroundDrawable.
        if (mBackgroundInsets == null) {
            mBackgroundInsets = Insets.NONE;
        }

        if (mBackgroundInsets.equals(mLastBackgroundInsets)
                && mBackgroundBlurDrawable == mLastBackgroundBlurDrawable
                && mLastOriginalBackgroundDrawable == mOriginalBackgroundDrawable) {
            return;
        }

        Drawable destDrawable = mOriginalBackgroundDrawable;
        if (mBackgroundBlurDrawable != null) {
            destDrawable = new LayerDrawable(new Drawable[] {mBackgroundBlurDrawable,
                                                             mOriginalBackgroundDrawable});
        }

        if (destDrawable != null && !mBackgroundInsets.equals(Insets.NONE)) {
            destDrawable = new InsetDrawable(destDrawable,
                    mBackgroundInsets.left, mBackgroundInsets.top,
                    mBackgroundInsets.right, mBackgroundInsets.bottom) {

                /**
                 * Return inner padding so we don't apply the padding again in
                 * {@link DecorView#drawableChanged()}
                 */
                @Override
                public boolean getPadding(Rect padding) {
                    return getDrawable().getPadding(padding);
                }
            };
        }

        // Call super since we are intercepting setBackground on this class.
        super.setBackgroundDrawable(destDrawable);

        mLastBackgroundInsets = mBackgroundInsets;
        mLastBackgroundBlurDrawable = mBackgroundBlurDrawable;
        mLastOriginalBackgroundDrawable = mOriginalBackgroundDrawable;
    }

    private void updateBackgroundBlurCorners() {
        if (mBackgroundBlurDrawable == null) return;

        float cornerRadius = 0;
        // If the blur radius is 0, the blur region won't be sent to surface flinger, so we don't
        // need to calculate the corner radius.
        if (mBackgroundBlurRadius != 0 && mOriginalBackgroundDrawable != null) {
            final Outline outline = new Outline();
            mOriginalBackgroundDrawable.getOutline(outline);
            cornerRadius = outline.mMode == Outline.MODE_ROUND_RECT ? outline.getRadius() : 0;
        }
        mBackgroundBlurDrawable.setCornerRadius(cornerRadius);
    }

    private void updateBackgroundBlurRadius() {
        if (getViewRootImpl() == null) return;

        mBackgroundBlurRadius = mCrossWindowBlurEnabled && mWindow.isTranslucent()
                ? mOriginalBackgroundBlurRadius : 0;
        if (mBackgroundBlurDrawable == null && mBackgroundBlurRadius > 0) {
            mBackgroundBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
            updateBackgroundDrawable();
        }

        if (mBackgroundBlurDrawable != null) {
            mBackgroundBlurDrawable.setBlurRadius(mBackgroundBlurRadius);
        }
    }

    void setBackgroundBlurRadius(int blurRadius) {
        mOriginalBackgroundBlurRadius = blurRadius;
        if (blurRadius > 0) {
            if (mCrossWindowBlurEnabledListener == null) {
                mCrossWindowBlurEnabledListener = enabled -> {
                    mCrossWindowBlurEnabled = enabled;
                    updateBackgroundBlurRadius();
                };
                // The executor to receive callback {@link mCrossWindowBlurEnabledListener}. It
                // should be the executor for this {@link DecorView}'s ui thread (not necessarily
                // the main thread).
                final Executor executor = Looper.myLooper() == Looper.getMainLooper()
                        ? getContext().getMainExecutor()
                        : new HandlerExecutor(new Handler(Looper.myLooper()));
                getContext().getSystemService(WindowManager.class)
                        .addCrossWindowBlurEnabledListener(
                                executor, mCrossWindowBlurEnabledListener);
                getViewTreeObserver().addOnPreDrawListener(mBackgroundBlurOnPreDrawListener);
            } else {
                updateBackgroundBlurRadius();
            }
        } else if (mCrossWindowBlurEnabledListener != null) {
            updateBackgroundBlurRadius();
            removeBackgroundBlurDrawable();
        }
    }

    void removeBackgroundBlurDrawable() {
        if (mCrossWindowBlurEnabledListener != null) {
            getContext().getSystemService(WindowManager.class)
                    .removeCrossWindowBlurEnabledListener(mCrossWindowBlurEnabledListener);
            mCrossWindowBlurEnabledListener = null;
        }
        getViewTreeObserver().removeOnPreDrawListener(mBackgroundBlurOnPreDrawListener);
        mBackgroundBlurDrawable = null;
        updateBackgroundDrawable();
    }

    @Override
    public Drawable getBackground() {
        return mOriginalBackgroundDrawable;
    }

    private int calculateStatusBarColor(@Appearance int appearance) {
        return calculateBarColor(mWindow.getAttributes().flags, FLAG_TRANSLUCENT_STATUS,
                mSemiTransparentBarColor, mWindow.mStatusBarColor,
                appearance, APPEARANCE_LIGHT_STATUS_BARS,
                mWindow.mEnsureStatusBarContrastWhenTransparent
                        && (mLastSuppressScrimTypes & WindowInsets.Type.statusBars()) == 0,
                false /* movesBarColorToScrim */);
    }

    private int calculateNavigationBarColor(@Appearance int appearance) {
        return calculateBarColor(mWindow.getAttributes().flags, FLAG_TRANSLUCENT_NAVIGATION,
                mSemiTransparentBarColor, mWindow.mNavigationBarColor,
                appearance, APPEARANCE_LIGHT_NAVIGATION_BARS,
                mWindow.mEnsureNavigationBarContrastWhenTransparent
                        && (mLastSuppressScrimTypes & WindowInsets.Type.navigationBars()) == 0,
                mWindow.mEdgeToEdgeEnforced);
    }

    public static int calculateBarColor(int flags, int translucentFlag, int semiTransparentBarColor,
            int barColor, @Appearance int appearance, @Appearance int lightAppearanceFlag,
            boolean ensuresContrast, boolean movesBarColorToScrim) {
        if ((flags & translucentFlag) != 0) {
            return semiTransparentBarColor;
        } else if ((flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0) {
            return Color.BLACK;
        } else if (ensuresContrast) {
            final int alpha = Color.alpha(barColor);
            if (alpha == 0) {
                boolean light = (appearance & lightAppearanceFlag) != 0;
                return light ? SCRIM_LIGHT : semiTransparentBarColor;
            } else if (movesBarColorToScrim) {
                return (barColor & 0xffffff) | SCRIM_ALPHA;
            }
        } else if (movesBarColorToScrim) {
            return Color.TRANSPARENT;
        }
        return barColor;
    }

    private int getCurrentColor(ColorViewState state) {
        if (state.visible) {
            return state.color;
        } else {
            return 0;
        }
    }

    /**
     * Update a color view
     *
     * @param state the color view to update.
     * @param color the current color to apply.
     * @param dividerColor the current divider color to apply.
     * @param size the current size in the non-parent-matching dimension.
     * @param verticalBar if true the view is attached to a vertical edge, otherwise to a
     *                    horizontal edge,
     * @param sideMargin sideMargin for the color view.
     * @param animate if true, the change will be animated.
     */
    private void updateColorViewInt(final ColorViewState state, int color, int dividerColor,
            int size, boolean verticalBar, boolean seascape, int sideMargin, boolean animate,
            boolean force, @InsetsType int requestedVisibleTypes) {
        final @InsetsType int type = state.attributes.insetsType;
        state.present = state.attributes.isPresent(
                (requestedVisibleTypes & type) != 0 || (mLastForceConsumingTypes & type) != 0,
                mWindow.getAttributes().flags, force);
        boolean show = state.attributes.isVisible(state.present, color,
                mWindow.getAttributes().flags, force);
        boolean showView = show && size > 0;

        boolean visibilityChanged = false;
        View view = state.view;

        int resolvedHeight = verticalBar ? LayoutParams.MATCH_PARENT : size;
        int resolvedWidth = verticalBar ? size : LayoutParams.MATCH_PARENT;
        int resolvedGravity = verticalBar
                ? (seascape ? state.attributes.seascapeGravity : state.attributes.horizontalGravity)
                : state.attributes.verticalGravity;

        if (view == null) {
            if (showView) {
                state.view = view = new View(mContext);
                setColor(view, color, dividerColor, verticalBar, seascape);
                view.setTransitionName(state.attributes.transitionName);
                view.setId(state.attributes.id);
                visibilityChanged = true;
                view.setVisibility(INVISIBLE);
                state.targetVisibility = VISIBLE;

                LayoutParams lp = new LayoutParams(resolvedWidth, resolvedHeight,
                        resolvedGravity);
                if (seascape) {
                    lp.leftMargin = sideMargin;
                } else {
                    lp.rightMargin = sideMargin;
                }
                addView(view, lp);
                updateColorViewTranslations();
            }
        } else {
            int vis = showView ? VISIBLE : INVISIBLE;
            visibilityChanged = state.targetVisibility != vis;
            state.targetVisibility = vis;
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            int rightMargin = seascape ? 0 : sideMargin;
            int leftMargin = seascape ? sideMargin : 0;
            if (lp.height != resolvedHeight || lp.width != resolvedWidth
                    || lp.gravity != resolvedGravity || lp.rightMargin != rightMargin
                    || lp.leftMargin != leftMargin) {
                lp.height = resolvedHeight;
                lp.width = resolvedWidth;
                lp.gravity = resolvedGravity;
                lp.rightMargin = rightMargin;
                lp.leftMargin = leftMargin;
                view.setLayoutParams(lp);
            }
            if (showView) {
                setColor(view, color, dividerColor, verticalBar, seascape);
            }
        }
        if (visibilityChanged) {
            view.animate().cancel();
            if (animate) {
                if (showView) {
                    if (view.getVisibility() != VISIBLE) {
                        view.setVisibility(VISIBLE);
                        view.setAlpha(0.0f);
                    }
                    view.animate().alpha(1.0f).setInterpolator(mShowInterpolator).
                            setDuration(mBarEnterExitDuration);
                } else {
                    view.animate().alpha(0.0f).setInterpolator(mHideInterpolator)
                            .setDuration(mBarEnterExitDuration)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    state.view.setAlpha(1.0f);
                                    state.view.setVisibility(INVISIBLE);
                                }
                            });
                }
            } else {
                view.setAlpha(1.0f);
                view.setVisibility(showView ? VISIBLE : INVISIBLE);
            }
        }
        state.visible = show;
        state.color = color;
    }

    private static void setColor(View v, int color, int dividerColor, boolean verticalBar,
            boolean seascape) {
        if (dividerColor != 0) {
            final Pair<Boolean, Boolean> dir = (Pair<Boolean, Boolean>) v.getTag();
            if (dir == null || dir.first != verticalBar || dir.second != seascape) {
                final int size = Math.round(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                                v.getContext().getResources().getDisplayMetrics()));
                // Use an inset to make the divider line on the side that faces the app.
                final InsetDrawable d = new InsetDrawable(new ColorDrawable(color),
                        verticalBar && !seascape ? size : 0,
                        !verticalBar ? size : 0,
                        verticalBar && seascape ? size : 0, 0);
                v.setBackground(new LayerDrawable(new Drawable[] {
                        new ColorDrawable(dividerColor), d }));
                v.setTag(new Pair<>(verticalBar, seascape));
            } else {
                final LayerDrawable d = (LayerDrawable) v.getBackground();
                final InsetDrawable inset = ((InsetDrawable) d.getDrawable(1));
                ((ColorDrawable) inset.getDrawable()).setColor(color);
                ((ColorDrawable) d.getDrawable(0)).setColor(dividerColor);
            }
        } else {
            v.setTag(null);
            v.setBackgroundColor(color);
        }
    }

    private void updateColorViewTranslations() {
        // Put the color views back in place when they get moved off the screen
        // due to the the ViewRootImpl panning.
        int rootScrollY = mRootScrollY;
        if (mStatusColorViewState.view != null) {
            mStatusColorViewState.view.setTranslationY(rootScrollY > 0 ? rootScrollY : 0);
        }
        if (mNavigationColorViewState.view != null) {
            mNavigationColorViewState.view.setTranslationY(rootScrollY < 0 ? rootScrollY : 0);
        }
    }

    private WindowInsets updateStatusGuard(WindowInsets insets) {
        boolean showStatusGuard = false;
        // Show the status guard when the non-overlay contextual action bar is showing
        if (mPrimaryActionModeView != null) {
            if (mPrimaryActionModeView.getLayoutParams() instanceof MarginLayoutParams) {
                // Insets are magic!
                final MarginLayoutParams mlp = (MarginLayoutParams)
                        mPrimaryActionModeView.getLayoutParams();
                boolean mlpChanged = false;
                if (mPrimaryActionModeView.isShown()) {
                    if (mTempRect == null) {
                        mTempRect = new Rect();
                    }
                    final Rect rect = mTempRect;

                    // Apply the insets that have not been applied by the contentParent yet.
                    WindowInsets innerInsets =
                            mWindow.mContentParent.computeSystemWindowInsets(insets, rect);
                    int newTopMargin = innerInsets.getSystemWindowInsetTop();
                    int newLeftMargin = innerInsets.getSystemWindowInsetLeft();
                    int newRightMargin = innerInsets.getSystemWindowInsetRight();

                    // Must use root window insets for the guard, because the color views consume
                    // the navigation bar inset if the window does not request LAYOUT_HIDE_NAV - but
                    // the status guard is attached at the root.
                    WindowInsets rootInsets = getRootWindowInsets();
                    int newGuardLeftMargin = rootInsets.getSystemWindowInsetLeft();
                    int newGuardRightMargin = rootInsets.getSystemWindowInsetRight();

                    if (mlp.topMargin != newTopMargin || mlp.leftMargin != newLeftMargin
                            || mlp.rightMargin != newRightMargin) {
                        mlpChanged = true;
                        mlp.topMargin = newTopMargin;
                        mlp.leftMargin = newLeftMargin;
                        mlp.rightMargin = newRightMargin;
                    }

                    if (newTopMargin > 0 && mStatusGuard == null) {
                        mStatusGuard = new View(mContext);
                        mStatusGuard.setVisibility(GONE);
                        final LayoutParams lp = new LayoutParams(MATCH_PARENT,
                                mlp.topMargin, Gravity.LEFT | Gravity.TOP);
                        lp.leftMargin = newGuardLeftMargin;
                        lp.rightMargin = newGuardRightMargin;
                        addView(mStatusGuard, indexOfChild(mStatusColorViewState.view), lp);
                    } else if (mStatusGuard != null) {
                        final LayoutParams lp = (LayoutParams)
                                mStatusGuard.getLayoutParams();
                        if (lp.height != mlp.topMargin || lp.leftMargin != newGuardLeftMargin
                                || lp.rightMargin != newGuardRightMargin) {
                            lp.height = mlp.topMargin;
                            lp.leftMargin = newGuardLeftMargin;
                            lp.rightMargin = newGuardRightMargin;
                            mStatusGuard.setLayoutParams(lp);
                        }
                    }

                    // The action mode's theme may differ from the app, so
                    // always show the status guard above it if we have one.
                    showStatusGuard = mStatusGuard != null;

                    if (showStatusGuard && mStatusGuard.getVisibility() != VISIBLE) {
                        // If it wasn't previously shown, the color may be stale
                        updateStatusGuardColor();
                    }

                    // We only need to consume the insets if the action
                    // mode is overlaid on the app content (e.g. it's
                    // sitting in a FrameLayout, see
                    // screen_simple_overlay_action_mode.xml).
                    final boolean nonOverlay = (mWindow.getLocalFeaturesPrivate()
                            & (1 << Window.FEATURE_ACTION_MODE_OVERLAY)) == 0;
                    if (nonOverlay && showStatusGuard) {
                        insets = insets.inset(0, insets.getSystemWindowInsetTop(), 0, 0);
                    }
                } else {
                    // reset top margin
                    if (mlp.topMargin != 0 || mlp.leftMargin != 0 || mlp.rightMargin != 0) {
                        mlpChanged = true;
                        mlp.topMargin = 0;
                    }
                }
                if (mlpChanged) {
                    mPrimaryActionModeView.setLayoutParams(mlp);
                }
            }
        }
        if (mStatusGuard != null) {
            mStatusGuard.setVisibility(showStatusGuard ? VISIBLE : GONE);
        }
        return insets;
    }

    private void updateStatusGuardColor() {
        boolean lightStatusBar =
                (getWindowSystemUiVisibility() & SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
        mStatusGuard.setBackgroundColor(lightStatusBar
                ? mContext.getColor(R.color.decor_view_status_guard_light)
                : mContext.getColor(R.color.decor_view_status_guard));
    }

    /**
     * Overrides the view outline when the activity enters picture-in-picture to ensure that it has
     * an opaque shadow even if the window background is completely transparent. This only applies
     * to activities that are currently the task root.
     */
    public void updatePictureInPictureOutlineProvider(boolean isInPictureInPictureMode) {
        if (mIsInPictureInPictureMode == isInPictureInPictureMode) {
            return;
        }

        if (isInPictureInPictureMode) {
            final Window.WindowControllerCallback callback =
                    mWindow.getWindowControllerCallback();
            if (callback != null && callback.isTaskRoot()) {
                // Call super implementation directly as we don't want to save the PIP outline
                // provider to be restored
                super.setOutlineProvider(PIP_OUTLINE_PROVIDER);
            }
        } else {
            // Restore the previous outline provider
            if (getOutlineProvider() != mLastOutlineProvider) {
                setOutlineProvider(mLastOutlineProvider);
            }
        }
        mIsInPictureInPictureMode = isInPictureInPictureMode;
    }

    @Override
    public void setOutlineProvider(ViewOutlineProvider provider) {
        super.setOutlineProvider(provider);

        // Save the outline provider set to ensure that we can restore when the activity leaves PiP
        mLastOutlineProvider = provider;
    }

    private void drawableChanged() {
        if (mChanging) {
            return;
        }

        // Fields can be null if super constructor calls setBackgroundDrawable.
        Rect framePadding = mFramePadding != null ? mFramePadding : new Rect();
        Rect backgroundPadding = mBackgroundPadding != null ? mBackgroundPadding : new Rect();

        setPadding(framePadding.left + backgroundPadding.left,
                framePadding.top + backgroundPadding.top,
                framePadding.right + backgroundPadding.right,
                framePadding.bottom + backgroundPadding.bottom);
        requestLayout();
        invalidate();

        int opacity = PixelFormat.OPAQUE;
        final WindowConfiguration winConfig = getResources().getConfiguration().windowConfiguration;
        final boolean renderShadowsInCompositor = mWindow.mRenderShadowsInCompositor;
        // If we draw shadows in the compositor we don't need to force the surface to be
        // translucent.
        if (winConfig.hasWindowShadow() && !renderShadowsInCompositor) {
            // If the window has a shadow, it must be translucent.
            opacity = PixelFormat.TRANSLUCENT;
        } else{
            // Note: If there is no background, we will assume opaque. The
            // common case seems to be that an application sets there to be
            // no background so it can draw everything itself. For that,
            // we would like to assume OPAQUE and let the app force it to
            // the slower TRANSLUCENT mode if that is really what it wants.
            Drawable bg = getBackground();
            Drawable fg = getForeground();
            if (bg != null) {
                if (fg == null) {
                    opacity = bg.getOpacity();
                } else if (framePadding.left <= 0 && framePadding.top <= 0
                        && framePadding.right <= 0 && framePadding.bottom <= 0) {
                    // If the frame padding is zero, then we can be opaque
                    // if either the frame -or- the background is opaque.
                    int fop = fg.getOpacity();
                    int bop = bg.getOpacity();
                    if (false)
                        Log.v(mLogTag, "Background opacity: " + bop + ", Frame opacity: " + fop);
                    if (fop == PixelFormat.OPAQUE || bop == PixelFormat.OPAQUE) {
                        opacity = PixelFormat.OPAQUE;
                    } else if (fop == PixelFormat.UNKNOWN) {
                        opacity = bop;
                    } else if (bop == PixelFormat.UNKNOWN) {
                        opacity = fop;
                    } else {
                        opacity = Drawable.resolveOpacity(fop, bop);
                    }
                } else {
                    // For now we have to assume translucent if there is a
                    // frame with padding... there is no way to tell if the
                    // frame and background together will draw all pixels.
                    if (false)
                        Log.v(mLogTag, "Padding: " + mFramePadding);
                    opacity = PixelFormat.TRANSLUCENT;
                }
            }
            if (false)
                Log.v(mLogTag, "Background: " + bg + ", Frame: " + fg);
        }

        if (false)
            Log.v(mLogTag, "Selected default opacity: " + opacity);

        mDefaultOpacity = opacity;
        if (mFeatureId < 0) {
            mWindow.setDefaultWindowFormat(opacity);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        // If the user is chording a menu shortcut, release the chord since
        // this window lost focus
        if (mWindow.hasFeature(Window.FEATURE_OPTIONS_PANEL) && !hasWindowFocus
                && mWindow.mPanelChordingKey != 0) {
            mWindow.closePanel(Window.FEATURE_OPTIONS_PANEL);
        }

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed() && mFeatureId < 0) {
            cb.onWindowFocusChanged(hasWindowFocus);
        }

        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.onWindowFocusChanged(hasWindowFocus);
        }
        if (mFloatingActionMode != null) {
            mFloatingActionMode.onWindowFocusChanged(hasWindowFocus);
        }

        updateElevation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed() && mFeatureId < 0) {
            cb.onAttachedToWindow();
        }

        if (mFeatureId == -1) {
            /*
             * The main window has been attached, try to restore any panels
             * that may have been open before. This is called in cases where
             * an activity is being killed for configuration change and the
             * menu was open. When the activity is recreated, the menu
             * should be shown again.
             */
            mWindow.openPanelsAfterRestore();
        }

        if (!mWindowResizeCallbacksAdded) {
            // If there is no window callback installed there was no window set before. Set it now.
            // Note that our ViewRootImpl object will not change.
            getViewRootImpl().addWindowCallbacks(this);
            mWindowResizeCallbacksAdded = true;
        }

        updateBackgroundBlurRadius();

        mWindow.onViewRootImplSet(getViewRootImpl());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && mFeatureId < 0) {
            cb.onDetachedFromWindow();
        }

        if (mWindow.mDecorContentParent != null) {
            mWindow.mDecorContentParent.dismissPopups();
        }

        if (mPrimaryActionModePopup != null) {
            removeCallbacks(mShowPrimaryActionModePopup);
            if (mPrimaryActionModePopup.isShowing()) {
                mPrimaryActionModePopup.dismiss();
            }
            mPrimaryActionModePopup = null;
        }
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }

        removeBackgroundBlurDrawable();

        PhoneWindow.PanelFeatureState st = mWindow.getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
        if (st != null && st.menu != null && mFeatureId < 0) {
            st.menu.close();
        }

        if (mWindowResizeCallbacksAdded) {
            getViewRootImpl().removeWindowCallbacks(this);
            mWindowResizeCallbacksAdded = false;
        }

        mPendingInsetsController.detach();
    }

    @Override
    public void onCloseSystemDialogs(String reason) {
        if (mFeatureId >= 0) {
            mWindow.closeAllPanels();
        }
    }

    public android.view.SurfaceHolder.Callback2 willYouTakeTheSurface() {
        return mFeatureId < 0 ? mWindow.mTakeSurfaceCallback : null;
    }

    public InputQueue.Callback willYouTakeTheInputQueue() {
        return mFeatureId < 0 ? mWindow.mTakeInputQueueCallback : null;
    }

    public void setSurfaceType(int type) {
        mWindow.setType(type);
    }

    public void setSurfaceFormat(int format) {
        mWindow.setFormat(format);
    }

    public void setSurfaceKeepScreenOn(boolean keepOn) {
        if (keepOn) mWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRootViewScrollYChanged(int rootScrollY) {
        mRootScrollY = rootScrollY;
        updateColorViewTranslations();
    }

    @Override
    public PendingInsetsController providePendingInsetsController() {
        return mPendingInsetsController;
    }

    private ActionMode createActionMode(
            int type, ActionMode.Callback2 callback, View originatingView) {
        switch (type) {
            case ActionMode.TYPE_PRIMARY:
            default:
                return createStandaloneActionMode(callback);
            case ActionMode.TYPE_FLOATING:
                return createFloatingActionMode(originatingView, callback);
        }
    }

    private void setHandledActionMode(ActionMode mode) {
        if (mode.getType() == ActionMode.TYPE_PRIMARY) {
            setHandledPrimaryActionMode(mode);
        } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
            setHandledFloatingActionMode(mode);
        }
    }

    private ActionMode createStandaloneActionMode(ActionMode.Callback callback) {
        endOnGoingFadeAnimation();
        cleanupPrimaryActionMode();
        // We want to create new mPrimaryActionModeView in two cases: if there is no existing
        // instance at all, or if there is one, but it is detached from window. The latter case
        // might happen when app is resized in multi-window mode and decor view is preserved
        // along with the main app window. Keeping mPrimaryActionModeView reference doesn't cause
        // app memory leaks because killMode() is called when the dismiss animation ends and from
        // cleanupPrimaryActionMode() invocation above.
        if (mPrimaryActionModeView == null || !mPrimaryActionModeView.isAttachedToWindow()) {
            if (mWindow.isFloating()) {
                // Use the action bar theme.
                final TypedValue outValue = new TypedValue();
                final Resources.Theme baseTheme = mContext.getTheme();
                baseTheme.resolveAttribute(R.attr.actionBarTheme, outValue, true);

                final Context actionBarContext;
                if (outValue.resourceId != 0) {
                    final Resources.Theme actionBarTheme = mContext.getResources().newTheme();
                    actionBarTheme.setTo(baseTheme);
                    actionBarTheme.applyStyle(outValue.resourceId, true);

                    actionBarContext = new ContextThemeWrapper(mContext, 0);
                    actionBarContext.getTheme().setTo(actionBarTheme);
                } else {
                    actionBarContext = mContext;
                }

                mPrimaryActionModeView = new ActionBarContextView(actionBarContext);
                mPrimaryActionModePopup = new PopupWindow(actionBarContext, null,
                        R.attr.actionModePopupWindowStyle);
                mPrimaryActionModePopup.setWindowLayoutType(
                        WindowManager.LayoutParams.TYPE_APPLICATION);
                mPrimaryActionModePopup.setContentView(mPrimaryActionModeView);
                mPrimaryActionModePopup.setWidth(MATCH_PARENT);

                actionBarContext.getTheme().resolveAttribute(
                        R.attr.actionBarSize, outValue, true);
                final int height = TypedValue.complexToDimensionPixelSize(outValue.data,
                        actionBarContext.getResources().getDisplayMetrics());
                mPrimaryActionModeView.setContentHeight(height);
                mPrimaryActionModePopup.setHeight(WRAP_CONTENT);
                mShowPrimaryActionModePopup = new Runnable() {
                    public void run() {
                        mPrimaryActionModePopup.showAtLocation(
                                mPrimaryActionModeView.getApplicationWindowToken(),
                                Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 0);
                        endOnGoingFadeAnimation();

                        if (shouldAnimatePrimaryActionModeView()) {
                            mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                                    0f, 1f);
                            mFadeAnim.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationStart(Animator animation) {
                                    mPrimaryActionModeView.setVisibility(VISIBLE);
                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    mPrimaryActionModeView.setAlpha(1f);
                                    mFadeAnim = null;
                                }
                            });
                            mFadeAnim.start();
                        } else {
                            mPrimaryActionModeView.setAlpha(1f);
                            mPrimaryActionModeView.setVisibility(VISIBLE);
                        }
                    }
                };
            } else {
                ViewStub stub = findViewById(R.id.action_mode_bar_stub);
                if (stub != null) {
                    mPrimaryActionModeView = (ActionBarContextView) stub.inflate();
                    mPrimaryActionModePopup = null;
                }
            }
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
            ActionMode mode = new StandaloneActionMode(
                    mPrimaryActionModeView.getContext(), mPrimaryActionModeView,
                    callback, mPrimaryActionModePopup == null);
            return mode;
        }
        return null;
    }

    private void endOnGoingFadeAnimation() {
        if (mFadeAnim != null) {
            mFadeAnim.end();
        }
    }

    private void setHandledPrimaryActionMode(ActionMode mode) {
        endOnGoingFadeAnimation();
        mPrimaryActionMode = mode;
        mPrimaryActionMode.invalidate();
        mPrimaryActionModeView.initForMode(mPrimaryActionMode);
        if (mPrimaryActionModePopup != null) {
            post(mShowPrimaryActionModePopup);
        } else {
            if (shouldAnimatePrimaryActionModeView()) {
                mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA, 0f, 1f);
                mFadeAnim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        mPrimaryActionModeView.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPrimaryActionModeView.setAlpha(1f);
                        mFadeAnim = null;
                    }
                });
                mFadeAnim.start();
            } else {
                mPrimaryActionModeView.setAlpha(1f);
                mPrimaryActionModeView.setVisibility(View.VISIBLE);
            }
        }
        mPrimaryActionModeView.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    boolean shouldAnimatePrimaryActionModeView() {
        // We only to animate the action mode in if the decor has already been laid out.
        // If it hasn't been laid out, it hasn't been drawn to screen yet.
        return isLaidOut();
    }

    private ActionMode createFloatingActionMode(
            View originatingView, ActionMode.Callback2 callback) {
        if (mFloatingActionMode != null) {
            mFloatingActionMode.finish();
        }
        cleanupFloatingActionModeViews();
        mFloatingToolbar = new FloatingToolbar(mWindow);
        final FloatingActionMode mode =
                new FloatingActionMode(mContext, callback, originatingView, mFloatingToolbar);
        mFloatingActionModeOriginatingView = originatingView;
        mFloatingToolbarPreDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    mode.updateViewLocationInWindow();
                    return true;
                }
            };
        return mode;
    }

    private void setHandledFloatingActionMode(ActionMode mode) {
        mFloatingActionMode = mode;
        mFloatingActionMode.invalidate();  // Will show the floating toolbar if necessary.
        mFloatingActionModeOriginatingView.getViewTreeObserver()
            .addOnPreDrawListener(mFloatingToolbarPreDrawListener);
    }

    void setWindow(PhoneWindow phoneWindow) {
        mWindow = phoneWindow;
        Context context = getContext();
        if (context instanceof DecorContext) {
            DecorContext decorContext = (DecorContext) context;
            decorContext.setPhoneWindow(mWindow);
        }
        if (mPendingWindowBackground != null) {
            Drawable background = mPendingWindowBackground;
            mPendingWindowBackground = null;
            setWindowBackground(background);
        }
    }

    @Override
    public Resources getResources() {
        // Make sure the Resources object is propogated from the Context since it can be updated in
        // the Context object.
        return getContext().getResources();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        initializeElevation();

        ViewRootImpl viewRootImpl = getViewRootImpl();
        if (viewRootImpl != null) {
            viewRootImpl.getOnBackInvokedDispatcher().onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void onMovedToDisplay(int displayId, Configuration config) {
        super.onMovedToDisplay(displayId, config);
        // Have to explicitly update displayId because it may use DecorContext
        getContext().updateDisplay(displayId);
    }

    /**
     * Determines if the workspace is entirely covered by the window.
     * @return {@code true} when the window is filling the entire screen/workspace.
     **/
    private boolean isFillingScreen(Configuration config) {
        final boolean isFullscreen = config.windowConfiguration.getWindowingMode()
                == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        return isFullscreen && (0 != ((getWindowSystemUiVisibility() | getSystemUiVisibility())
                & View.SYSTEM_UI_FLAG_FULLSCREEN));
    }

    void onResourcesLoaded(LayoutInflater inflater, int layoutResource) {
        final View root = inflater.inflate(layoutResource, null);

        // Put it below the color views.
        addView(root, 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        mContentRoot = (ViewGroup) root;
        initializeElevation();
    }

    void clearContentView() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (v != mStatusColorViewState.view && v != mNavigationColorViewState.view
                    && v != mStatusGuard) {
                removeViewAt(i);
            }
        }
    }

    @Override
    public void onWindowSizeIsChanging(Rect newBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets) {}

    @Override
    public void onWindowDragResizeStart(Rect initialBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets) {
        if (mWindow.isDestroyed()) {
            // If the owner's window is gone, we should not be able to come here anymore.
            return;
        }
        getViewRootImpl().requestInvalidateRootRenderNode();
    }

    @Override
    public void onWindowDragResizeEnd() {
        updateColorViews(null /* insets */, false);
        getViewRootImpl().requestInvalidateRootRenderNode();
    }

    @Override
    public boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY) {
        return false;
    }

    @Override
    public void onRequestDraw(boolean reportNextDraw) {
        if (!reportNextDraw) {
            return;
        }
        // If render thread is gone, just report immediately.
        if (isAttachedToWindow()) {
            getViewRootImpl().reportDrawFinish();
        }
    }

    @Override
    public void onPostDraw(RecordingCanvas canvas) {
        drawLegacyNavigationBarBackground(canvas);
    }

    private void drawLegacyNavigationBarBackground(RecordingCanvas canvas) {
        if (!mDrawLegacyNavigationBarBackground || mDrawLegacyNavigationBarBackgroundHandled) {
            return;
        }
        View v = mNavigationColorViewState.view;
        if (v == null) {
            return;
        }
        canvas.drawRect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom(),
                mLegacyNavigationBarBackgroundPaint);
    }

    /**
     * The elevation gets set for the first time and the framework needs to be informed that
     * the surface layer gets created with the shadow size in mind.
     */
    private void initializeElevation() {
        // TODO(skuhne): Call setMaxElevation here accordingly after b/22668382 got fixed.
        mAllowUpdateElevation = false;
        updateElevation();
    }

    private void updateElevation() {
        final int windowingMode =
                getResources().getConfiguration().windowConfiguration.getWindowingMode();
        final boolean renderShadowsInCompositor = mWindow.mRenderShadowsInCompositor;
        // If rendering shadows in the compositor, don't set an elevation on the view
        if (renderShadowsInCompositor) {
            return;
        }
        float elevation = 0;
        final boolean wasAdjustedForStack = mElevationAdjustedForStack;
        // Do not use a shadow when we are in resizing mode (mBackdropFrameRenderer not null)
        // since the shadow is bound to the content size and not the target size.
        if (windowingMode == WINDOWING_MODE_FREEFORM) {
            elevation = hasWindowFocus() ?
                    DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP : DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP;
            // Add a maximum shadow height value to the top level view.
            // Note that pinned stack doesn't have focus
            // so maximum shadow height adjustment isn't needed.
            // TODO(skuhne): Remove this if clause once b/22668382 got fixed.
            if (!mAllowUpdateElevation) {
                elevation = DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP;
            }
            // Convert the DP elevation into physical pixels.
            elevation = dipToPx(elevation);
            mElevationAdjustedForStack = true;
        } else {
            mElevationAdjustedForStack = false;
        }

        // Don't change the elevation if we didn't previously adjust it for the stack it was in
        // or it didn't change.
        if ((wasAdjustedForStack || mElevationAdjustedForStack) && getElevation() != elevation) {
            mWindow.setElevation(elevation);
        }
    }

    /**
     * Converts a DIP measure into physical pixels.
     * @param dip The dip value.
     * @return Returns the number of pixels.
     */
    private float dipToPx(float dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip,
                getResources().getDisplayMetrics());
    }

    private static String getTitleSuffix(WindowManager.LayoutParams params) {
        if (params == null) {
            return "";
        }
        final String[] split = params.getTitle().toString().split("\\.");
        if (split.length > 0) {
            return split[split.length - 1];
        } else {
            return "";
        }
    }

    void updateLogTag(WindowManager.LayoutParams params) {
        mLogTag = TAG + "[" + getTitleSuffix(params) + "]";
    }

    /**
     * @hide
     */
    @Override
    public void requestKeyboardShortcuts(List<KeyboardShortcutGroup> list, int deviceId) {
        final PanelFeatureState st = mWindow.getPanelState(FEATURE_OPTIONS_PANEL, false);
        final Menu menu = st != null ? st.menu : null;
        if (!mWindow.isDestroyed() && mWindow.getCallback() != null) {
            mWindow.getCallback().onProvideKeyboardShortcuts(list, menu, deviceId);
        }
    }

    @Override
    public void dispatchPointerCaptureChanged(boolean hasCapture) {
        super.dispatchPointerCaptureChanged(hasCapture);
        if (!mWindow.isDestroyed() && mWindow.getCallback() != null) {
            mWindow.getCallback().onPointerCaptureChanged(hasCapture);
        }
    }

    @Override
    public int getAccessibilityViewId() {
        return AccessibilityNodeInfo.ROOT_ITEM_ID;
    }

    @Override
    public WindowInsetsController getWindowInsetsController() {
        if (isAttachedToWindow()) {
            return super.getWindowInsetsController();
        } else {
            return mPendingInsetsController;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "[" + getTitleSuffix(mWindow.getAttributes()) + "]";
    }

    private static class ColorViewState {
        View view = null;
        int targetVisibility = View.INVISIBLE;
        boolean present = false;
        boolean visible;
        int color;

        final ColorViewAttributes attributes;

        ColorViewState(ColorViewAttributes attributes) {
            this.attributes = attributes;
        }
    }

    public static class ColorViewAttributes {

        final int id;
        final int translucentFlag;
        final int verticalGravity;
        final int horizontalGravity;
        final int seascapeGravity;
        final String transitionName;
        final @InsetsType int insetsType;

        private ColorViewAttributes(int translucentFlag, int verticalGravity, int horizontalGravity,
                int seascapeGravity, String transitionName, int id, @InsetsType int insetsType) {
            this.id = id;
            this.translucentFlag = translucentFlag;
            this.verticalGravity = verticalGravity;
            this.horizontalGravity = horizontalGravity;
            this.seascapeGravity = seascapeGravity;
            this.transitionName = transitionName;
            this.insetsType = insetsType;
        }

        public boolean isPresent(boolean requestedVisible, int windowFlags, boolean force) {
            return requestedVisible
                    && ((windowFlags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0 || force);
        }

        public boolean isVisible(boolean present, int color, int windowFlags, boolean force) {
            return present
                    && Color.alpha(color) != 0
                    && ((windowFlags & translucentFlag) == 0 || force);
        }

        public boolean isVisible(@InsetsType int requestedVisibleTypes, int color, int windowFlags,
                boolean force) {
            final boolean requestedVisible = (requestedVisibleTypes & insetsType) != 0;
            final boolean present = isPresent(requestedVisible, windowFlags, force);
            return isVisible(present, color, windowFlags, force);
        }
    }

    /**
     * Clears out internal references when the action mode is destroyed.
     */
    private class ActionModeCallback2Wrapper extends ActionMode.Callback2 {
        private final ActionMode.Callback mWrapped;

        public ActionModeCallback2Wrapper(ActionMode.Callback wrapped) {
            mWrapped = wrapped;
        }

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return mWrapped.onCreateActionMode(mode, menu);
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            requestFitSystemWindows();
            return mWrapped.onPrepareActionMode(mode, menu);
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return mWrapped.onActionItemClicked(mode, item);
        }

        public void onDestroyActionMode(ActionMode mode) {
            mWrapped.onDestroyActionMode(mode);
            final boolean isMncApp = mContext.getApplicationInfo().targetSdkVersion
                    >= M;
            final boolean isPrimary;
            final boolean isFloating;
            if (isMncApp) {
                isPrimary = mode == mPrimaryActionMode;
                isFloating = mode == mFloatingActionMode;
                if (!isPrimary && mode.getType() == ActionMode.TYPE_PRIMARY) {
                    Log.e(mLogTag, "Destroying unexpected ActionMode instance of TYPE_PRIMARY; "
                            + mode + " was not the current primary action mode! Expected "
                            + mPrimaryActionMode);
                }
                if (!isFloating && mode.getType() == ActionMode.TYPE_FLOATING) {
                    Log.e(mLogTag, "Destroying unexpected ActionMode instance of TYPE_FLOATING; "
                            + mode + " was not the current floating action mode! Expected "
                            + mFloatingActionMode);
                }
            } else {
                isPrimary = mode.getType() == ActionMode.TYPE_PRIMARY;
                isFloating = mode.getType() == ActionMode.TYPE_FLOATING;
            }
            if (isPrimary) {
                if (mPrimaryActionModePopup != null) {
                    removeCallbacks(mShowPrimaryActionModePopup);
                }
                if (mPrimaryActionModeView != null) {
                    endOnGoingFadeAnimation();
                    // Store action mode view reference, so we can access it safely when animation
                    // ends. mPrimaryActionModePopup is set together with mPrimaryActionModeView,
                    // so no need to store reference to it in separate variable.
                    final ActionBarContextView lastActionModeView = mPrimaryActionModeView;
                    mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                            1f, 0f);
                    mFadeAnim.addListener(new Animator.AnimatorListener() {

                                @Override
                                public void onAnimationStart(Animator animation) {

                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    // If mPrimaryActionModeView has changed - it means that we've
                                    // cleared the content while preserving decor view. We don't
                                    // want to change the state of new instances accidentally here.
                                    if (lastActionModeView == mPrimaryActionModeView) {
                                        lastActionModeView.setVisibility(GONE);
                                        if (mPrimaryActionModePopup != null) {
                                            mPrimaryActionModePopup.dismiss();
                                        }
                                        lastActionModeView.killMode();
                                        mFadeAnim = null;
                                        requestApplyInsets();
                                    }
                                }

                                @Override
                                public void onAnimationCancel(Animator animation) {

                                }

                                @Override
                                public void onAnimationRepeat(Animator animation) {

                                }
                            });
                    mFadeAnim.start();
                }

                mPrimaryActionMode = null;
            } else if (isFloating) {
                cleanupFloatingActionModeViews();
                mFloatingActionMode = null;
            }
            if (mWindow.getCallback() != null && !mWindow.isDestroyed()) {
                try {
                    mWindow.getCallback().onActionModeFinished(mode);
                } catch (AbstractMethodError ame) {
                    // Older apps might not implement this callback method.
                }
            }
            requestFitSystemWindows();
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (mWrapped instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) mWrapped).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }
}
