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

import android.app.WindowConfiguration;
import android.graphics.Outline;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Pair;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.internal.R;
import com.android.internal.policy.PhoneWindow.PanelFeatureState;
import com.android.internal.policy.PhoneWindow.PhoneWindowMenuCallback;
import com.android.internal.view.FloatingActionMode;
import com.android.internal.view.RootViewSurfaceTaker;
import com.android.internal.view.StandaloneActionMode;
import com.android.internal.view.menu.ContextMenuBuilder;
import com.android.internal.view.menu.MenuHelper;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.BackgroundFallback;
import com.android.internal.widget.DecorCaptionView;
import com.android.internal.widget.FloatingToolbar;

import java.util.List;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.DisplayListCanvas;
import android.view.Gravity;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowCallbacks;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import static android.app.WindowConfiguration.PINNED_WINDOWING_MODE_ELEVATION_IN_DIP;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;
import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getMode;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.Window.DECOR_CAPTION_SHADE_DARK;
import static android.view.Window.DECOR_CAPTION_SHADE_LIGHT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DRAWN_APPLICATION;
import static com.android.internal.policy.PhoneWindow.FEATURE_OPTIONS_PANEL;

/** @hide */
public class DecorView extends FrameLayout implements RootViewSurfaceTaker, WindowCallbacks {
    private static final String TAG = "DecorView";

    private static final boolean DEBUG_MEASURE = false;

    private static final boolean SWEEP_OPEN_MENU = false;

    // The height of a window which has focus in DIP.
    private final static int DECOR_SHADOW_FOCUSED_HEIGHT_IN_DIP = 20;
    // The height of a window which has not in DIP.
    private final static int DECOR_SHADOW_UNFOCUSED_HEIGHT_IN_DIP = 5;

    public static final ColorViewAttributes STATUS_BAR_COLOR_VIEW_ATTRIBUTES =
            new ColorViewAttributes(SYSTEM_UI_FLAG_FULLSCREEN, FLAG_TRANSLUCENT_STATUS,
                    Gravity.TOP, Gravity.LEFT, Gravity.RIGHT,
                    Window.STATUS_BAR_BACKGROUND_TRANSITION_NAME,
                    com.android.internal.R.id.statusBarBackground,
                    FLAG_FULLSCREEN);

    public static final ColorViewAttributes NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES =
            new ColorViewAttributes(
                    SYSTEM_UI_FLAG_HIDE_NAVIGATION, FLAG_TRANSLUCENT_NAVIGATION,
                    Gravity.BOTTOM, Gravity.RIGHT, Gravity.LEFT,
                    Window.NAVIGATION_BAR_BACKGROUND_TRANSITION_NAME,
                    com.android.internal.R.id.navigationBarBackground,
                    0 /* hideWindowFlag */);

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

    private boolean mHasCaption = false;

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
    final boolean mForceWindowDrawsStatusBarBackground;
    private final int mSemiTransparentStatusBarColor;

    private final BackgroundFallback mBackgroundFallback = new BackgroundFallback();

    private int mLastTopInset = 0;
    private int mLastBottomInset = 0;
    private int mLastRightInset = 0;
    private int mLastLeftInset = 0;
    private boolean mLastHasTopStableInset = false;
    private boolean mLastHasBottomStableInset = false;
    private boolean mLastHasRightStableInset = false;
    private boolean mLastHasLeftStableInset = false;
    private int mLastWindowFlags = 0;
    private boolean mLastShouldAlwaysConsumeNavBar = false;

    private int mRootScrollY = 0;

    private PhoneWindow mWindow;

    ViewGroup mContentRoot;

    private Rect mTempRect;
    private Rect mOutsets = new Rect();

    // This is the caption view for the window, containing the caption and window control
    // buttons. The visibility of this decor depends on the workspace and the window type.
    // If the window type does not require such a view, this member might be null.
    DecorCaptionView mDecorCaptionView;

    private boolean mWindowResizeCallbacksAdded = false;
    private Drawable.Callback mLastBackgroundDrawableCb = null;
    private BackdropFrameRenderer mBackdropFrameRenderer = null;
    private Drawable mResizingBackgroundDrawable;
    private Drawable mCaptionBackgroundDrawable;
    private Drawable mUserCaptionBackgroundDrawable;

    private float mAvailableWidth;

    String mLogTag = TAG;
    private final Rect mFloatingInsets = new Rect();
    private boolean mApplyFloatingVerticalInsets = false;
    private boolean mApplyFloatingHorizontalInsets = false;

    private int mResizeMode = RESIZE_MODE_INVALID;
    private final int mResizeShadowSize;
    private final Paint mVerticalResizeShadowPaint = new Paint();
    private final Paint mHorizontalResizeShadowPaint = new Paint();

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
        mForceWindowDrawsStatusBarBackground = context.getResources().getBoolean(
                R.bool.config_forceWindowDrawsStatusBarBackground)
                && context.getApplicationInfo().targetSdkVersion >= N;
        mSemiTransparentStatusBarColor = context.getResources().getColor(
                R.color.system_bar_background_semi_transparent, null /* theme */);

        updateAvailableWidth();

        setWindow(window);

        updateLogTag(params);

        mResizeShadowSize = context.getResources().getDimensionPixelSize(
                R.dimen.resize_shadow_size);
        initResizingPaints();
    }

    void setBackgroundFallback(int resId) {
        mBackgroundFallback.setDrawable(resId != 0 ? getContext().getDrawable(resId) : null);
        setWillNotDraw(getBackground() == null && !mBackgroundFallback.hasFallback());
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        boolean statusOpaque = gatherTransparentRegion(mStatusColorViewState, region);
        boolean navOpaque = gatherTransparentRegion(mNavigationColorViewState, region);
        boolean decorOpaque = super.gatherTransparentRegion(region);

        // combine bools after computation, so each method above always executes
        return statusOpaque || navOpaque || decorOpaque;
    }

    boolean gatherTransparentRegion(ColorViewState colorViewState, Region region) {
        if (colorViewState.view != null && colorViewState.visible && isResizing()) {
            // If a visible ColorViewState is in a resizing host DecorView, forcibly register its
            // opaque area, since it's drawn by a different root RenderNode. It would otherwise be
            // rejected by ViewGroup#gatherTransparentRegion() for the view not being VISIBLE.
            return colorViewState.view.gatherTransparentRegion(region);
        }
        return false; // no opaque area added
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
        if (mHasCaption && isShowingCaption()) {
            // Don't dispatch ACTION_DOWN to the captionr if the window is resizable and the event
            // was (starting) outside the window. Window resizing events should be handled by
            // WindowManager.
            // TODO: Investigate how to handle the outside touch in window manager
            //       without generating these events.
            //       Currently we receive these because we need to enlarge the window's
            //       touch region so that the monitor channel receives the events
            //       in the outside touch area.
            if (action == MotionEvent.ACTION_DOWN) {
                final int x = (int) event.getX();
                final int y = (int) event.getY();
                if (isOutOfInnerBounds(x, y)) {
                    return true;
                }
            }
        }

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

            Drawable bg = getBackground();
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
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
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

        getOutsets(mOutsets);
        if (mOutsets.top > 0 || mOutsets.bottom > 0) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        height + mOutsets.top + mOutsets.bottom, mode);
            }
        }
        if (mOutsets.left > 0 || mOutsets.right > 0) {
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        width + mOutsets.left + mOutsets.right, mode);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        boolean measure = false;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, EXACTLY);

        if (!fixedWidth && widthMode == AT_MOST) {
            final TypedValue tv = isPortrait ? mWindow.mMinWidthMinor : mWindow.mMinWidthMajor;
            if (tv.type != TypedValue.TYPE_NULL) {
                final int min;
                if (tv.type == TypedValue.TYPE_DIMENSION) {
                    min = (int)tv.getDimension(metrics);
                } else if (tv.type == TypedValue.TYPE_FRACTION) {
                    min = (int)tv.getFraction(mAvailableWidth, mAvailableWidth);
                } else {
                    min = 0;
                }
                if (DEBUG_MEASURE) Log.d(mLogTag, "Adjust for min width: " + min + ", value::"
                        + tv.coerceToString() + ", mAvailableWidth=" + mAvailableWidth);

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
        getOutsets(mOutsets);
        if (mOutsets.left > 0) {
            offsetLeftAndRight(-mOutsets.left);
        }
        if (mOutsets.top > 0) {
            offsetTopAndBottom(-mOutsets.top);
        }
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

        if (changed && mResizeMode == RESIZE_MODE_DOCKED_DIVIDER) {
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
            helper = mWindow.mContextMenu.showPopup(getContext(), originalView, x, y);
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
        if (getBackground() != drawable) {
            setBackgroundDrawable(drawable);
            if (drawable != null) {
                mResizingBackgroundDrawable = enforceNonTranslucentBackground(drawable,
                        mWindow.isTranslucent() || mWindow.isShowingWallpaper());
            } else {
                mResizingBackgroundDrawable = getResizingBackgroundDrawable(
                        getContext(), 0, mWindow.mBackgroundFallbackResource,
                        mWindow.isTranslucent() || mWindow.isShowingWallpaper());
            }
            if (mResizingBackgroundDrawable != null) {
                mResizingBackgroundDrawable.getPadding(mBackgroundPadding);
            } else {
                mBackgroundPadding.setEmpty();
            }
            drawableChanged();
        }
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
        mFrameOffsets.set(insets.getSystemWindowInsets());
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

    public static int getColorViewTopInset(int stableTop, int systemTop) {
        return Math.min(stableTop, systemTop);
    }

    public static int getColorViewBottomInset(int stableBottom, int systemBottom) {
        return Math.min(stableBottom, systemBottom);
    }

    public static int getColorViewRightInset(int stableRight, int systemRight) {
        return Math.min(stableRight, systemRight);
    }

    public static int getColorViewLeftInset(int stableLeft, int systemLeft) {
        return Math.min(stableLeft, systemLeft);
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

    public static void getNavigationBarRect(int canvasWidth, int canvasHeight, Rect stableInsets,
            Rect contentInsets, Rect outRect) {
        final int bottomInset = getColorViewBottomInset(stableInsets.bottom, contentInsets.bottom);
        final int leftInset = getColorViewLeftInset(stableInsets.left, contentInsets.left);
        final int rightInset = getColorViewLeftInset(stableInsets.right, contentInsets.right);
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

        // IME is an exceptional floating window that requires color view.
        final boolean isImeWindow =
                mWindow.getAttributes().type == WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        if (!mWindow.mIsFloating || isImeWindow) {
            boolean disallowAnimate = !isLaidOut();
            disallowAnimate |= ((mLastWindowFlags ^ attrs.flags)
                    & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            mLastWindowFlags = attrs.flags;

            if (insets != null) {
                mLastTopInset = getColorViewTopInset(insets.getStableInsetTop(),
                        insets.getSystemWindowInsetTop());
                mLastBottomInset = getColorViewBottomInset(insets.getStableInsetBottom(),
                        insets.getSystemWindowInsetBottom());
                mLastRightInset = getColorViewRightInset(insets.getStableInsetRight(),
                        insets.getSystemWindowInsetRight());
                mLastLeftInset = getColorViewRightInset(insets.getStableInsetLeft(),
                        insets.getSystemWindowInsetLeft());

                // Don't animate if the presence of stable insets has changed, because that
                // indicates that the window was either just added and received them for the
                // first time, or the window size or position has changed.
                boolean hasTopStableInset = insets.getStableInsetTop() != 0;
                disallowAnimate |= (hasTopStableInset != mLastHasTopStableInset);
                mLastHasTopStableInset = hasTopStableInset;

                boolean hasBottomStableInset = insets.getStableInsetBottom() != 0;
                disallowAnimate |= (hasBottomStableInset != mLastHasBottomStableInset);
                mLastHasBottomStableInset = hasBottomStableInset;

                boolean hasRightStableInset = insets.getStableInsetRight() != 0;
                disallowAnimate |= (hasRightStableInset != mLastHasRightStableInset);
                mLastHasRightStableInset = hasRightStableInset;

                boolean hasLeftStableInset = insets.getStableInsetLeft() != 0;
                disallowAnimate |= (hasLeftStableInset != mLastHasLeftStableInset);
                mLastHasLeftStableInset = hasLeftStableInset;

                mLastShouldAlwaysConsumeNavBar = insets.shouldAlwaysConsumeNavBar();
            }

            boolean navBarToRightEdge = isNavBarToRightEdge(mLastBottomInset, mLastRightInset);
            boolean navBarToLeftEdge = isNavBarToLeftEdge(mLastBottomInset, mLastLeftInset);
            int navBarSize = getNavBarSize(mLastBottomInset, mLastRightInset, mLastLeftInset);
            updateColorViewInt(mNavigationColorViewState, sysUiVisibility,
                    mWindow.mNavigationBarColor, mWindow.mNavigationBarDividerColor, navBarSize,
                    navBarToRightEdge || navBarToLeftEdge, navBarToLeftEdge,
                    0 /* sideInset */, animate && !disallowAnimate, false /* force */);

            boolean statusBarNeedsRightInset = navBarToRightEdge
                    && mNavigationColorViewState.present;
            boolean statusBarNeedsLeftInset = navBarToLeftEdge
                    && mNavigationColorViewState.present;
            int statusBarSideInset = statusBarNeedsRightInset ? mLastRightInset
                    : statusBarNeedsLeftInset ? mLastLeftInset : 0;
            updateColorViewInt(mStatusColorViewState, sysUiVisibility,
                    calculateStatusBarColor(), 0, mLastTopInset,
                    false /* matchVertical */, statusBarNeedsLeftInset, statusBarSideInset,
                    animate && !disallowAnimate,
                    mForceWindowDrawsStatusBarBackground);
        }

        // When we expand the window with FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, we still need
        // to ensure that the rest of the view hierarchy doesn't notice it, unless they've
        // explicitly asked for it.
        boolean consumingNavBar =
                (attrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
                || mLastShouldAlwaysConsumeNavBar;

        // If we didn't request fullscreen layout, but we still got it because of the
        // mForceWindowDrawsStatusBarBackground flag, also consume top inset.
        boolean consumingStatusBar = (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) == 0
                && (sysUiVisibility & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) == 0
                && (attrs.flags & FLAG_LAYOUT_IN_SCREEN) == 0
                && (attrs.flags & FLAG_LAYOUT_INSET_DECOR) == 0
                && mForceWindowDrawsStatusBarBackground
                && mLastTopInset != 0;

        int consumedTop = consumingStatusBar ? mLastTopInset : 0;
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
            if (insets != null) {
                insets = insets.inset(consumedLeft, consumedTop, consumedRight, consumedBottom);
            }
        }

        if (insets != null) {
            insets = insets.consumeStableInsets();
        }
        return insets;
    }

    private int calculateStatusBarColor() {
        return calculateStatusBarColor(mWindow.getAttributes().flags,
                mSemiTransparentStatusBarColor, mWindow.mStatusBarColor);
    }

    public static int calculateStatusBarColor(int flags, int semiTransparentStatusBarColor,
            int statusBarColor) {
        return (flags & FLAG_TRANSLUCENT_STATUS) != 0 ? semiTransparentStatusBarColor
                : (flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0 ? statusBarColor
                : Color.BLACK;
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
     * @param sysUiVis the current systemUiVisibility to apply.
     * @param color the current color to apply.
     * @param dividerColor the current divider color to apply.
     * @param size the current size in the non-parent-matching dimension.
     * @param verticalBar if true the view is attached to a vertical edge, otherwise to a
     *                    horizontal edge,
     * @param sideMargin sideMargin for the color view.
     * @param animate if true, the change will be animated.
     */
    private void updateColorViewInt(final ColorViewState state, int sysUiVis, int color,
            int dividerColor, int size, boolean verticalBar, boolean seascape, int sideMargin,
            boolean animate, boolean force) {
        state.present = state.attributes.isPresent(sysUiVis, mWindow.getAttributes().flags, force);
        boolean show = state.attributes.isVisible(state.present, color,
                mWindow.getAttributes().flags, force);
        boolean showView = show && !isResizing() && size > 0;

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
            if (animate && !isResizing()) {
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

                    // If the parent doesn't consume the insets, manually
                    // apply the default system window insets.
                    mWindow.mContentParent.computeSystemWindowInsets(insets, rect);
                    final int newMargin = rect.top == 0 ? insets.getSystemWindowInsetTop() : 0;
                    if (mlp.topMargin != newMargin) {
                        mlpChanged = true;
                        mlp.topMargin = insets.getSystemWindowInsetTop();

                        if (mStatusGuard == null) {
                            mStatusGuard = new View(mContext);
                            mStatusGuard.setBackgroundColor(mContext.getColor(
                                    R.color.decor_view_status_guard));
                            addView(mStatusGuard, indexOfChild(mStatusColorViewState.view),
                                    new LayoutParams(LayoutParams.MATCH_PARENT,
                                            mlp.topMargin, Gravity.START | Gravity.TOP));
                        } else {
                            final LayoutParams lp = (LayoutParams)
                                    mStatusGuard.getLayoutParams();
                            if (lp.height != mlp.topMargin) {
                                lp.height = mlp.topMargin;
                                mStatusGuard.setLayoutParams(lp);
                            }
                        }
                    }

                    // The action mode's theme may differ from the app, so
                    // always show the status guard above it if we have one.
                    showStatusGuard = mStatusGuard != null;

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
                    if (mlp.topMargin != 0) {
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
            mStatusGuard.setVisibility(showStatusGuard ? View.VISIBLE : View.GONE);
        }
        return insets;
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

        setPadding(mFramePadding.left + mBackgroundPadding.left,
                mFramePadding.top + mBackgroundPadding.top,
                mFramePadding.right + mBackgroundPadding.right,
                mFramePadding.bottom + mBackgroundPadding.bottom);
        requestLayout();
        invalidate();

        int opacity = PixelFormat.OPAQUE;
        final WindowConfiguration winConfig = getResources().getConfiguration().windowConfiguration;
        if (winConfig.hasWindowShadow()) {
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
                } else if (mFramePadding.left <= 0 && mFramePadding.top <= 0
                        && mFramePadding.right <= 0 && mFramePadding.bottom <= 0) {
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
        } else if (mBackdropFrameRenderer != null) {
            // We are resizing and this call happened due to a configuration change. Tell the
            // renderer about it.
            mBackdropFrameRenderer.onConfigurationChange();
        }
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

        PhoneWindow.PanelFeatureState st = mWindow.getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
        if (st != null && st.menu != null && mFeatureId < 0) {
            st.menu.close();
        }

        releaseThreadedRenderer();

        if (mWindowResizeCallbacksAdded) {
            getViewRootImpl().removeWindowCallbacks(this);
            mWindowResizeCallbacksAdded = false;
        }
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

    /**
     * Informs the decor if the caption is attached and visible.
     * @param attachedAndVisible true when the decor is visible.
     * Note that this will even be called if there is no caption.
     **/
    void enableCaption(boolean attachedAndVisible) {
        if (mHasCaption != attachedAndVisible) {
            mHasCaption = attachedAndVisible;
            if (getForeground() != null) {
                drawableChanged();
            }
        }
    }

    void setWindow(PhoneWindow phoneWindow) {
        mWindow = phoneWindow;
        Context context = getContext();
        if (context instanceof DecorContext) {
            DecorContext decorContext = (DecorContext) context;
            decorContext.setPhoneWindow(mWindow);
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

        final boolean displayWindowDecor =
                newConfig.windowConfiguration.hasWindowDecorCaption();
        if (mDecorCaptionView == null && displayWindowDecor) {
            // Configuration now requires a caption.
            final LayoutInflater inflater = mWindow.getLayoutInflater();
            mDecorCaptionView = createDecorCaptionView(inflater);
            if (mDecorCaptionView != null) {
                if (mDecorCaptionView.getParent() == null) {
                    addView(mDecorCaptionView, 0,
                            new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
                }
                removeView(mContentRoot);
                mDecorCaptionView.addView(mContentRoot,
                        new ViewGroup.MarginLayoutParams(MATCH_PARENT, MATCH_PARENT));
            }
        } else if (mDecorCaptionView != null) {
            // We might have to change the kind of surface before we do anything else.
            mDecorCaptionView.onConfigurationChanged(displayWindowDecor);
            enableCaption(displayWindowDecor);
        }

        updateAvailableWidth();
        initializeElevation();
    }

    void onResourcesLoaded(LayoutInflater inflater, int layoutResource) {
        if (mBackdropFrameRenderer != null) {
            loadBackgroundDrawablesIfNeeded();
            mBackdropFrameRenderer.onResourcesLoaded(
                    this, mResizingBackgroundDrawable, mCaptionBackgroundDrawable,
                    mUserCaptionBackgroundDrawable, getCurrentColor(mStatusColorViewState),
                    getCurrentColor(mNavigationColorViewState));
        }

        mDecorCaptionView = createDecorCaptionView(inflater);
        final View root = inflater.inflate(layoutResource, null);
        if (mDecorCaptionView != null) {
            if (mDecorCaptionView.getParent() == null) {
                addView(mDecorCaptionView,
                        new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            }
            mDecorCaptionView.addView(root,
                    new ViewGroup.MarginLayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else {

            // Put it below the color views.
            addView(root, 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        }
        mContentRoot = (ViewGroup) root;
        initializeElevation();
    }

    private void loadBackgroundDrawablesIfNeeded() {
        if (mResizingBackgroundDrawable == null) {
            mResizingBackgroundDrawable = getResizingBackgroundDrawable(getContext(),
                    mWindow.mBackgroundResource, mWindow.mBackgroundFallbackResource,
                    mWindow.isTranslucent() || mWindow.isShowingWallpaper());
            if (mResizingBackgroundDrawable == null) {
                // We shouldn't really get here as the background fallback should be always
                // available since it is defaulted by the system.
                Log.w(mLogTag, "Failed to find background drawable for PhoneWindow=" + mWindow);
            }
        }
        if (mCaptionBackgroundDrawable == null) {
            mCaptionBackgroundDrawable = getContext().getDrawable(
                    R.drawable.decor_caption_title_focused);
        }
        if (mResizingBackgroundDrawable != null) {
            mLastBackgroundDrawableCb = mResizingBackgroundDrawable.getCallback();
            mResizingBackgroundDrawable.setCallback(null);
        }
    }

    // Free floating overlapping windows require a caption.
    private DecorCaptionView createDecorCaptionView(LayoutInflater inflater) {
        DecorCaptionView decorCaptionView = null;
        for (int i = getChildCount() - 1; i >= 0 && decorCaptionView == null; i--) {
            View view = getChildAt(i);
            if (view instanceof DecorCaptionView) {
                // The decor was most likely saved from a relaunch - so reuse it.
                decorCaptionView = (DecorCaptionView) view;
                removeViewAt(i);
            }
        }
        final WindowManager.LayoutParams attrs = mWindow.getAttributes();
        final boolean isApplication = attrs.type == TYPE_BASE_APPLICATION ||
                attrs.type == TYPE_APPLICATION || attrs.type == TYPE_DRAWN_APPLICATION;
        final WindowConfiguration winConfig = getResources().getConfiguration().windowConfiguration;
        // Only a non floating application window on one of the allowed workspaces can get a caption
        if (!mWindow.isFloating() && isApplication && winConfig.hasWindowDecorCaption()) {
            // Dependent on the brightness of the used title we either use the
            // dark or the light button frame.
            if (decorCaptionView == null) {
                decorCaptionView = inflateDecorCaptionView(inflater);
            }
            decorCaptionView.setPhoneWindow(mWindow, true /*showDecor*/);
        } else {
            decorCaptionView = null;
        }

        // Tell the decor if it has a visible caption.
        enableCaption(decorCaptionView != null);
        return decorCaptionView;
    }

    private DecorCaptionView inflateDecorCaptionView(LayoutInflater inflater) {
        final Context context = getContext();
        // We make a copy of the inflater, so it has the right context associated with it.
        inflater = inflater.from(context);
        final DecorCaptionView view = (DecorCaptionView) inflater.inflate(R.layout.decor_caption,
                null);
        setDecorCaptionShade(context, view);
        return view;
    }

    private void setDecorCaptionShade(Context context, DecorCaptionView view) {
        final int shade = mWindow.getDecorCaptionShade();
        switch (shade) {
            case DECOR_CAPTION_SHADE_LIGHT:
                setLightDecorCaptionShade(view);
                break;
            case DECOR_CAPTION_SHADE_DARK:
                setDarkDecorCaptionShade(view);
                break;
            default: {
                TypedValue value = new TypedValue();
                context.getTheme().resolveAttribute(R.attr.colorPrimary, value, true);
                // We invert the shade depending on brightness of the theme. Dark shade for light
                // theme and vice versa. Thanks to this the buttons should be visible on the
                // background.
                if (Color.luminance(value.data) < 0.5) {
                    setLightDecorCaptionShade(view);
                } else {
                    setDarkDecorCaptionShade(view);
                }
                break;
            }
        }
    }

    void updateDecorCaptionShade() {
        if (mDecorCaptionView != null) {
            setDecorCaptionShade(getContext(), mDecorCaptionView);
        }
    }

    private void setLightDecorCaptionShade(DecorCaptionView view) {
        view.findViewById(R.id.maximize_window).setBackgroundResource(
                R.drawable.decor_maximize_button_light);
        view.findViewById(R.id.close_window).setBackgroundResource(
                R.drawable.decor_close_button_light);
    }

    private void setDarkDecorCaptionShade(DecorCaptionView view) {
        view.findViewById(R.id.maximize_window).setBackgroundResource(
                R.drawable.decor_maximize_button_dark);
        view.findViewById(R.id.close_window).setBackgroundResource(
                R.drawable.decor_close_button_dark);
    }

    /**
     * Returns the color used to fill areas the app has not rendered content to yet when the
     * user is resizing the window of an activity in multi-window mode.
     */
    public static Drawable getResizingBackgroundDrawable(Context context, int backgroundRes,
            int backgroundFallbackRes, boolean windowTranslucent) {
        if (backgroundRes != 0) {
            final Drawable drawable = context.getDrawable(backgroundRes);
            if (drawable != null) {
                return enforceNonTranslucentBackground(drawable, windowTranslucent);
            }
        }

        if (backgroundFallbackRes != 0) {
            final Drawable fallbackDrawable = context.getDrawable(backgroundFallbackRes);
            if (fallbackDrawable != null) {
                return enforceNonTranslucentBackground(fallbackDrawable, windowTranslucent);
            }
        }
        return new ColorDrawable(Color.BLACK);
    }

    /**
     * Enforces a drawable to be non-translucent to act as a background if needed, i.e. if the
     * window is not translucent.
     */
    private static Drawable enforceNonTranslucentBackground(Drawable drawable,
            boolean windowTranslucent) {
        if (!windowTranslucent && drawable instanceof ColorDrawable) {
            ColorDrawable colorDrawable = (ColorDrawable) drawable;
            int color = colorDrawable.getColor();
            if (Color.alpha(color) != 255) {
                ColorDrawable copy = (ColorDrawable) colorDrawable.getConstantState().newDrawable()
                        .mutate();
                copy.setColor(
                        Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)));
                return copy;
            }
        }
        return drawable;
    }

    void clearContentView() {
        if (mDecorCaptionView != null) {
            mDecorCaptionView.removeContentView();
        } else {
            // This window doesn't have caption, so we need to remove everything except our views
            // we might have added.
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View v = getChildAt(i);
                if (v != mStatusColorViewState.view && v != mNavigationColorViewState.view
                        && v != mStatusGuard) {
                    removeViewAt(i);
                }
            }
        }
    }

    @Override
    public void onWindowSizeIsChanging(Rect newBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets) {
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.setTargetRect(newBounds, fullscreen, systemInsets, stableInsets);
        }
    }

    @Override
    public void onWindowDragResizeStart(Rect initialBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets, int resizeMode) {
        if (mWindow.isDestroyed()) {
            // If the owner's window is gone, we should not be able to come here anymore.
            releaseThreadedRenderer();
            return;
        }
        if (mBackdropFrameRenderer != null) {
            return;
        }
        final ThreadedRenderer renderer = getThreadedRenderer();
        if (renderer != null) {
            loadBackgroundDrawablesIfNeeded();
            mBackdropFrameRenderer = new BackdropFrameRenderer(this, renderer,
                    initialBounds, mResizingBackgroundDrawable, mCaptionBackgroundDrawable,
                    mUserCaptionBackgroundDrawable, getCurrentColor(mStatusColorViewState),
                    getCurrentColor(mNavigationColorViewState), fullscreen, systemInsets,
                    stableInsets, resizeMode);

            // Get rid of the shadow while we are resizing. Shadow drawing takes considerable time.
            // If we want to get the shadow shown while resizing, we would need to elevate a new
            // element which owns the caption and has the elevation.
            updateElevation();

            updateColorViews(null /* insets */, false);
        }
        mResizeMode = resizeMode;
        getViewRootImpl().requestInvalidateRootRenderNode();
    }

    @Override
    public void onWindowDragResizeEnd() {
        releaseThreadedRenderer();
        updateColorViews(null /* insets */, false);
        mResizeMode = RESIZE_MODE_INVALID;
        getViewRootImpl().requestInvalidateRootRenderNode();
    }

    @Override
    public boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY) {
        if (mBackdropFrameRenderer == null) {
            return false;
        }
        return mBackdropFrameRenderer.onContentDrawn(offsetX, offsetY, sizeX, sizeY);
    }

    @Override
    public void onRequestDraw(boolean reportNextDraw) {
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.onRequestDraw(reportNextDraw);
        } else if (reportNextDraw) {
            // If render thread is gone, just report immediately.
            if (isAttachedToWindow()) {
                getViewRootImpl().reportDrawFinish();
            }
        }
    }

    @Override
    public void onPostDraw(DisplayListCanvas canvas) {
        drawResizingShadowIfNeeded(canvas);
    }

    private void initResizingPaints() {
        final int startColor = mContext.getResources().getColor(
                R.color.resize_shadow_start_color, null);
        final int endColor = mContext.getResources().getColor(
                R.color.resize_shadow_end_color, null);
        final int middleColor = (startColor + endColor) / 2;
        mHorizontalResizeShadowPaint.setShader(new LinearGradient(
                0, 0, 0, mResizeShadowSize, new int[] { startColor, middleColor, endColor },
                new float[] { 0f, 0.3f, 1f }, Shader.TileMode.CLAMP));
        mVerticalResizeShadowPaint.setShader(new LinearGradient(
                0, 0, mResizeShadowSize, 0, new int[] { startColor, middleColor, endColor },
                new float[] { 0f, 0.3f, 1f }, Shader.TileMode.CLAMP));
    }

    private void drawResizingShadowIfNeeded(DisplayListCanvas canvas) {
        if (mResizeMode != RESIZE_MODE_DOCKED_DIVIDER || mWindow.mIsFloating
                || mWindow.isTranslucent()
                || mWindow.isShowingWallpaper()) {
            return;
        }
        canvas.save();
        canvas.translate(0, getHeight() - mFrameOffsets.bottom);
        canvas.drawRect(0, 0, getWidth(), mResizeShadowSize, mHorizontalResizeShadowPaint);
        canvas.restore();
        canvas.save();
        canvas.translate(getWidth() - mFrameOffsets.right, 0);
        canvas.drawRect(0, 0, mResizeShadowSize, getHeight(), mVerticalResizeShadowPaint);
        canvas.restore();
    }

    /** Release the renderer thread which is usually done when the user stops resizing. */
    private void releaseThreadedRenderer() {
        if (mResizingBackgroundDrawable != null && mLastBackgroundDrawableCb != null) {
            mResizingBackgroundDrawable.setCallback(mLastBackgroundDrawableCb);
            mLastBackgroundDrawableCb = null;
        }

        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.releaseRenderer();
            mBackdropFrameRenderer = null;
            // Bring the shadow back.
            updateElevation();
        }
    }

    private boolean isResizing() {
        return mBackdropFrameRenderer != null;
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
        float elevation = 0;
        final boolean wasAdjustedForStack = mElevationAdjustedForStack;
        // Do not use a shadow when we are in resizing mode (mBackdropFrameRenderer not null)
        // since the shadow is bound to the content size and not the target size.
        final int windowingMode =
                getResources().getConfiguration().windowConfiguration.getWindowingMode();
        if ((windowingMode == WINDOWING_MODE_FREEFORM) && !isResizing()) {
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
        } else if (windowingMode == WINDOWING_MODE_PINNED) {
            elevation = dipToPx(PINNED_WINDOWING_MODE_ELEVATION_IN_DIP);
            mElevationAdjustedForStack = true;
        } else {
            mElevationAdjustedForStack = false;
        }

        // Don't change the elevation if we didn't previously adjust it for the stack it was in
        // or it didn't change.
        if ((wasAdjustedForStack || mElevationAdjustedForStack)
                && getElevation() != elevation) {
            mWindow.setElevation(elevation);
        }
    }

    boolean isShowingCaption() {
        return mDecorCaptionView != null && mDecorCaptionView.isCaptionShowing();
    }

    int getCaptionHeight() {
        return isShowingCaption() ? mDecorCaptionView.getCaptionHeight() : 0;
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

    /**
     * Provide an override of the caption background drawable.
     */
    void setUserCaptionBackgroundDrawable(Drawable drawable) {
        mUserCaptionBackgroundDrawable = drawable;
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.setUserCaptionBackgroundDrawable(drawable);
        }
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

    private void updateAvailableWidth() {
        Resources res = getResources();
        mAvailableWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                res.getConfiguration().screenWidthDp, res.getDisplayMetrics());
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
    public String toString() {
        return "DecorView@" + Integer.toHexString(this.hashCode()) + "["
                + getTitleSuffix(mWindow.getAttributes()) + "]";
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
        final int systemUiHideFlag;
        final int translucentFlag;
        final int verticalGravity;
        final int horizontalGravity;
        final int seascapeGravity;
        final String transitionName;
        final int hideWindowFlag;

        private ColorViewAttributes(int systemUiHideFlag, int translucentFlag, int verticalGravity,
                int horizontalGravity, int seascapeGravity, String transitionName, int id,
                int hideWindowFlag) {
            this.id = id;
            this.systemUiHideFlag = systemUiHideFlag;
            this.translucentFlag = translucentFlag;
            this.verticalGravity = verticalGravity;
            this.horizontalGravity = horizontalGravity;
            this.seascapeGravity = seascapeGravity;
            this.transitionName = transitionName;
            this.hideWindowFlag = hideWindowFlag;
        }

        public boolean isPresent(int sysUiVis, int windowFlags, boolean force) {
            return (sysUiVis & systemUiHideFlag) == 0
                    && (windowFlags & hideWindowFlag) == 0
                    && ((windowFlags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                    || force);
        }

        public boolean isVisible(boolean present, int color, int windowFlags, boolean force) {
            return present
                    && (color & Color.BLACK) != 0
                    && ((windowFlags & translucentFlag) == 0  || force);
        }

        public boolean isVisible(int sysUiVis, int color, int windowFlags, boolean force) {
            final boolean present = isPresent(sysUiVis, windowFlags, force);
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
