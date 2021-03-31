/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import static android.Manifest.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.Manifest.permission.HIDE_OVERLAY_WINDOWS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StyleRes;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.Pair;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.List;

/**
 * Abstract base class for a top-level window look and behavior policy.  An
 * instance of this class should be used as the top-level view added to the
 * window manager. It provides standard UI policies such as a background, title
 * area, default key processing, etc.
 *
 * <p>The only existing implementation of this abstract class is
 * android.view.PhoneWindow, which you should instantiate when needing a
 * Window.
 */
public abstract class Window {
    /** Flag for the "options panel" feature.  This is enabled by default. */
    public static final int FEATURE_OPTIONS_PANEL = 0;
    /** Flag for the "no title" feature, turning off the title at the top
     *  of the screen. */
    public static final int FEATURE_NO_TITLE = 1;

    /**
     * Flag for the progress indicator feature.
     *
     * @deprecated No longer supported starting in API 21.
     */
    @Deprecated
    public static final int FEATURE_PROGRESS = 2;

    /** Flag for having an icon on the left side of the title bar */
    public static final int FEATURE_LEFT_ICON = 3;
    /** Flag for having an icon on the right side of the title bar */
    public static final int FEATURE_RIGHT_ICON = 4;

    /**
     * Flag for indeterminate progress.
     *
     * @deprecated No longer supported starting in API 21.
     */
    @Deprecated
    public static final int FEATURE_INDETERMINATE_PROGRESS = 5;

    /** Flag for the context menu.  This is enabled by default. */
    public static final int FEATURE_CONTEXT_MENU = 6;
    /** Flag for custom title. You cannot combine this feature with other title features. */
    public static final int FEATURE_CUSTOM_TITLE = 7;
    /**
     * Flag for enabling the Action Bar.
     * This is enabled by default for some devices. The Action Bar
     * replaces the title bar and provides an alternate location
     * for an on-screen menu button on some devices.
     */
    public static final int FEATURE_ACTION_BAR = 8;
    /**
     * Flag for requesting an Action Bar that overlays window content.
     * Normally an Action Bar will sit in the space above window content, but if this
     * feature is requested along with {@link #FEATURE_ACTION_BAR} it will be layered over
     * the window content itself. This is useful if you would like your app to have more control
     * over how the Action Bar is displayed, such as letting application content scroll beneath
     * an Action Bar with a transparent background or otherwise displaying a transparent/translucent
     * Action Bar over application content.
     *
     * <p>This mode is especially useful with {@link View#SYSTEM_UI_FLAG_FULLSCREEN
     * View.SYSTEM_UI_FLAG_FULLSCREEN}, which allows you to seamlessly hide the
     * action bar in conjunction with other screen decorations.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#JELLY_BEAN}, when an
     * ActionBar is in this mode it will adjust the insets provided to
     * {@link View#fitSystemWindows(android.graphics.Rect) View.fitSystemWindows(Rect)}
     * to include the content covered by the action bar, so you can do layout within
     * that space.
     */
    public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
    /**
     * Flag for specifying the behavior of action modes when an Action Bar is not present.
     * If overlay is enabled, the action mode UI will be allowed to cover existing window content.
     */
    public static final int FEATURE_ACTION_MODE_OVERLAY = 10;
    /**
     * Flag for requesting a decoration-free window that is dismissed by swiping from the left.
     *
     * @deprecated Swipe-to-dismiss isn't functional anymore.
     */
    @Deprecated
    public static final int FEATURE_SWIPE_TO_DISMISS = 11;
    /**
     * Flag for requesting that window content changes should be animated using a
     * TransitionManager.
     *
     * <p>The TransitionManager is set using
     * {@link #setTransitionManager(android.transition.TransitionManager)}. If none is set,
     * a default TransitionManager will be used.</p>
     *
     * @see #setContentView
     */
    public static final int FEATURE_CONTENT_TRANSITIONS = 12;

    /**
     * Enables Activities to run Activity Transitions either through sending or receiving
     * ActivityOptions bundle created with
     * {@link android.app.ActivityOptions#makeSceneTransitionAnimation(android.app.Activity,
     * android.util.Pair[])} or {@link android.app.ActivityOptions#makeSceneTransitionAnimation(
     * android.app.Activity, View, String)}.
     */
    public static final int FEATURE_ACTIVITY_TRANSITIONS = 13;

    /**
     * Max value used as a feature ID
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int FEATURE_MAX = FEATURE_ACTIVITY_TRANSITIONS;

    /**
     * Flag for setting the progress bar's visibility to VISIBLE.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_VISIBILITY_ON = -1;

    /**
     * Flag for setting the progress bar's visibility to GONE.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_VISIBILITY_OFF = -2;

    /**
     * Flag for setting the progress bar's indeterminate mode on.
     *
     * @deprecated {@link #FEATURE_INDETERMINATE_PROGRESS} and related methods
     *             are no longer supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_INDETERMINATE_ON = -3;

    /**
     * Flag for setting the progress bar's indeterminate mode off.
     *
     * @deprecated {@link #FEATURE_INDETERMINATE_PROGRESS} and related methods
     *             are no longer supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_INDETERMINATE_OFF = -4;

    /**
     * Starting value for the (primary) progress.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_START = 0;

    /**
     * Ending value for the (primary) progress.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_END = 10000;

    /**
     * Lowest possible value for the secondary progress.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_SECONDARY_START = 20000;

    /**
     * Highest possible value for the secondary progress.
     *
     * @deprecated {@link #FEATURE_PROGRESS} and related methods are no longer
     *             supported starting in API 21.
     */
    @Deprecated
    public static final int PROGRESS_SECONDARY_END = 30000;

    /**
     * The transitionName for the status bar background View when a custom background is used.
     * @see android.view.Window#setStatusBarColor(int)
     */
    public static final String STATUS_BAR_BACKGROUND_TRANSITION_NAME = "android:status:background";

    /**
     * The transitionName for the navigation bar background View when a custom background is used.
     * @see android.view.Window#setNavigationBarColor(int)
     */
    public static final String NAVIGATION_BAR_BACKGROUND_TRANSITION_NAME =
            "android:navigation:background";

    /**
     * The default features enabled.
     * @deprecated use {@link #getDefaultFeatures(android.content.Context)} instead.
     */
    @Deprecated
    @SuppressWarnings({"PointlessBitwiseExpression"})
    protected static final int DEFAULT_FEATURES = (1 << FEATURE_OPTIONS_PANEL) |
            (1 << FEATURE_CONTEXT_MENU);

    /**
     * The ID that the main layout in the XML layout file should have.
     */
    public static final int ID_ANDROID_CONTENT = com.android.internal.R.id.content;

    /**
     * Flag for letting the theme drive the color of the window caption controls. Use with
     * {@link #setDecorCaptionShade(int)}. This is the default value.
     */
    public static final int DECOR_CAPTION_SHADE_AUTO = 0;
    /**
     * Flag for setting light-color controls on the window caption. Use with
     * {@link #setDecorCaptionShade(int)}.
     */
    public static final int DECOR_CAPTION_SHADE_LIGHT = 1;
    /**
     * Flag for setting dark-color controls on the window caption. Use with
     * {@link #setDecorCaptionShade(int)}.
     */
    public static final int DECOR_CAPTION_SHADE_DARK = 2;

    @UnsupportedAppUsage
    @UiContext
    private final Context mContext;

    @UnsupportedAppUsage
    private TypedArray mWindowStyle;
    @UnsupportedAppUsage
    private Callback mCallback;
    private OnWindowDismissedCallback mOnWindowDismissedCallback;
    private OnWindowSwipeDismissedCallback mOnWindowSwipeDismissedCallback;
    private WindowControllerCallback mWindowControllerCallback;
    private OnRestrictedCaptionAreaChangedListener mOnRestrictedCaptionAreaChangedListener;
    private Rect mRestrictedCaptionAreaRect;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private WindowManager mWindowManager;
    @UnsupportedAppUsage
    private IBinder mAppToken;
    @UnsupportedAppUsage
    private String mAppName;
    @UnsupportedAppUsage
    private boolean mHardwareAccelerated;
    private Window mContainer;
    private Window mActiveChild;
    private boolean mIsActive = false;
    private boolean mHasChildren = false;
    private boolean mCloseOnTouchOutside = false;
    private boolean mSetCloseOnTouchOutside = false;
    private int mForcedWindowFlags = 0;

    @UnsupportedAppUsage
    private int mFeatures;
    @UnsupportedAppUsage
    private int mLocalFeatures;

    private boolean mHaveWindowFormat = false;
    private boolean mHaveDimAmount = false;
    private int mDefaultWindowFormat = PixelFormat.OPAQUE;

    private boolean mHasSoftInputMode = false;

    @UnsupportedAppUsage
    private boolean mDestroyed;

    private boolean mOverlayWithDecorCaptionEnabled = true;
    private boolean mCloseOnSwipeEnabled = false;

    // The current window attributes.
    @UnsupportedAppUsage
    private final WindowManager.LayoutParams mWindowAttributes =
        new WindowManager.LayoutParams();

    /**
     * API from a Window back to its caller.  This allows the client to
     * intercept key dispatching, panels and menus, etc.
     */
    public interface Callback {
        /**
         * Called to process key events.  At the very least your
         * implementation must call
         * {@link android.view.Window#superDispatchKeyEvent} to do the
         * standard key processing.
         *
         * @param event The key event.
         *
         * @return boolean Return true if this event was consumed.
         */
        public boolean dispatchKeyEvent(KeyEvent event);

        /**
         * Called to process a key shortcut event.
         * At the very least your implementation must call
         * {@link android.view.Window#superDispatchKeyShortcutEvent} to do the
         * standard key shortcut processing.
         *
         * @param event The key shortcut event.
         * @return True if this event was consumed.
         */
        public boolean dispatchKeyShortcutEvent(KeyEvent event);

        /**
         * Called to process touch screen events.  At the very least your
         * implementation must call
         * {@link android.view.Window#superDispatchTouchEvent} to do the
         * standard touch screen processing.
         *
         * @param event The touch screen event.
         *
         * @return boolean Return true if this event was consumed.
         */
        public boolean dispatchTouchEvent(MotionEvent event);

        /**
         * Called to process trackball events.  At the very least your
         * implementation must call
         * {@link android.view.Window#superDispatchTrackballEvent} to do the
         * standard trackball processing.
         *
         * @param event The trackball event.
         *
         * @return boolean Return true if this event was consumed.
         */
        public boolean dispatchTrackballEvent(MotionEvent event);

        /**
         * Called to process generic motion events.  At the very least your
         * implementation must call
         * {@link android.view.Window#superDispatchGenericMotionEvent} to do the
         * standard processing.
         *
         * @param event The generic motion event.
         *
         * @return boolean Return true if this event was consumed.
         */
        public boolean dispatchGenericMotionEvent(MotionEvent event);

        /**
         * Called to process population of {@link AccessibilityEvent}s.
         *
         * @param event The event.
         *
         * @return boolean Return true if event population was completed.
         */
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event);

        /**
         * Instantiate the view to display in the panel for 'featureId'.
         * You can return null, in which case the default content (typically
         * a menu) will be created for you.
         *
         * @param featureId Which panel is being created.
         *
         * @return view The top-level view to place in the panel.
         *
         * @see #onPreparePanel
         */
        @Nullable
        public View onCreatePanelView(int featureId);

        /**
         * Initialize the contents of the menu for panel 'featureId'.  This is
         * called if onCreatePanelView() returns null, giving you a standard
         * menu in which you can place your items.  It is only called once for
         * the panel, the first time it is shown.
         *
         * <p>You can safely hold on to <var>menu</var> (and any items created
         * from it), making modifications to it as desired, until the next
         * time onCreatePanelMenu() is called for this feature.
         *
         * @param featureId The panel being created.
         * @param menu The menu inside the panel.
         *
         * @return boolean You must return true for the panel to be displayed;
         *         if you return false it will not be shown.
         */
        boolean onCreatePanelMenu(int featureId, @NonNull Menu menu);

        /**
         * Prepare a panel to be displayed.  This is called right before the
         * panel window is shown, every time it is shown.
         *
         * @param featureId The panel that is being displayed.
         * @param view The View that was returned by onCreatePanelView().
         * @param menu If onCreatePanelView() returned null, this is the Menu
         *             being displayed in the panel.
         *
         * @return boolean You must return true for the panel to be displayed;
         *         if you return false it will not be shown.
         *
         * @see #onCreatePanelView
         */
        boolean onPreparePanel(int featureId, @Nullable View view, @NonNull Menu menu);

        /**
         * Called when a panel's menu is opened by the user. This may also be
         * called when the menu is changing from one type to another (for
         * example, from the icon menu to the expanded menu).
         *
         * @param featureId The panel that the menu is in.
         * @param menu The menu that is opened.
         * @return Return true to allow the menu to open, or false to prevent
         *         the menu from opening.
         */
        boolean onMenuOpened(int featureId, @NonNull Menu menu);

        /**
         * Called when a panel's menu item has been selected by the user.
         *
         * @param featureId The panel that the menu is in.
         * @param item The menu item that was selected.
         *
         * @return boolean Return true to finish processing of selection, or
         *         false to perform the normal menu handling (calling its
         *         Runnable or sending a Message to its target Handler).
         */
        boolean onMenuItemSelected(int featureId, @NonNull MenuItem item);

        /**
         * This is called whenever the current window attributes change.
         *
         */
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs);

        /**
         * This hook is called whenever the content view of the screen changes
         * (due to a call to
         * {@link Window#setContentView(View, android.view.ViewGroup.LayoutParams)
         * Window.setContentView} or
         * {@link Window#addContentView(View, android.view.ViewGroup.LayoutParams)
         * Window.addContentView}).
         */
        public void onContentChanged();

        /**
         * This hook is called whenever the window focus changes.  See
         * {@link View#onWindowFocusChanged(boolean)
         * View.onWindowFocusChangedNotLocked(boolean)} for more information.
         *
         * @param hasFocus Whether the window now has focus.
         */
        public void onWindowFocusChanged(boolean hasFocus);

        /**
         * Called when the window has been attached to the window manager.
         * See {@link View#onAttachedToWindow() View.onAttachedToWindow()}
         * for more information.
         */
        public void onAttachedToWindow();

        /**
         * Called when the window has been detached from the window manager.
         * See {@link View#onDetachedFromWindow() View.onDetachedFromWindow()}
         * for more information.
         */
        public void onDetachedFromWindow();

        /**
         * Called when a panel is being closed.  If another logical subsequent
         * panel is being opened (and this panel is being closed to make room for the subsequent
         * panel), this method will NOT be called.
         *
         * @param featureId The panel that is being displayed.
         * @param menu If onCreatePanelView() returned null, this is the Menu
         *            being displayed in the panel.
         */
        void onPanelClosed(int featureId, @NonNull Menu menu);

        /**
         * Called when the user signals the desire to start a search.
         *
         * @return true if search launched, false if activity refuses (blocks)
         *
         * @see android.app.Activity#onSearchRequested()
         */
        public boolean onSearchRequested();

        /**
         * Called when the user signals the desire to start a search.
         *
         * @param searchEvent A {@link SearchEvent} describing the signal to
         *                   start a search.
         * @return true if search launched, false if activity refuses (blocks)
         */
        public boolean onSearchRequested(SearchEvent searchEvent);

        /**
         * Called when an action mode is being started for this window. Gives the
         * callback an opportunity to handle the action mode in its own unique and
         * beautiful way. If this method returns null the system can choose a way
         * to present the mode or choose not to start the mode at all. This is equivalent
         * to {@link #onWindowStartingActionMode(android.view.ActionMode.Callback, int)}
         * with type {@link ActionMode#TYPE_PRIMARY}.
         *
         * @param callback Callback to control the lifecycle of this action mode
         * @return The ActionMode that was started, or null if the system should present it
         */
        @Nullable
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback);

        /**
         * Called when an action mode is being started for this window. Gives the
         * callback an opportunity to handle the action mode in its own unique and
         * beautiful way. If this method returns null the system can choose a way
         * to present the mode or choose not to start the mode at all.
         *
         * @param callback Callback to control the lifecycle of this action mode
         * @param type One of {@link ActionMode#TYPE_PRIMARY} or {@link ActionMode#TYPE_FLOATING}.
         * @return The ActionMode that was started, or null if the system should present it
         */
        @Nullable
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type);

        /**
         * Called when an action mode has been started. The appropriate mode callback
         * method will have already been invoked.
         *
         * @param mode The new mode that has just been started.
         */
        public void onActionModeStarted(ActionMode mode);

        /**
         * Called when an action mode has been finished. The appropriate mode callback
         * method will have already been invoked.
         *
         * @param mode The mode that was just finished.
         */
        public void onActionModeFinished(ActionMode mode);

        /**
         * Called when Keyboard Shortcuts are requested for the current window.
         *
         * @param data The data list to populate with shortcuts.
         * @param menu The current menu, which may be null.
         * @param deviceId The id for the connected device the shortcuts should be provided for.
         */
        default public void onProvideKeyboardShortcuts(
                List<KeyboardShortcutGroup> data, @Nullable Menu menu, int deviceId) { };

        /**
         * Called when pointer capture is enabled or disabled for the current window.
         *
         * @param hasCapture True if the window has pointer capture.
         */
        default public void onPointerCaptureChanged(boolean hasCapture) { };
    }

    /** @hide */
    public interface OnWindowDismissedCallback {
        /**
         * Called when a window is dismissed. This informs the callback that the
         * window is gone, and it should finish itself.
         * @param finishTask True if the task should also be finished.
         * @param suppressWindowTransition True if the resulting exit and enter window transition
         * animations should be suppressed.
         */
        void onWindowDismissed(boolean finishTask, boolean suppressWindowTransition);
    }

    /** @hide */
    public interface OnWindowSwipeDismissedCallback {
        /**
         * Called when a window is swipe dismissed. This informs the callback that the
         * window is gone, and it should finish itself.
         * @param finishTask True if the task should also be finished.
         * @param suppressWindowTransition True if the resulting exit and enter window transition
         * animations should be suppressed.
         */
        void onWindowSwipeDismissed();
    }

    /** @hide */
    public interface WindowControllerCallback {
        /**
         * Moves the activity between {@link WindowConfiguration#WINDOWING_MODE_FREEFORM} windowing
         * mode and {@link WindowConfiguration#WINDOWING_MODE_FULLSCREEN}.
         */
        void toggleFreeformWindowingMode();

        /**
         * Puts the activity in picture-in-picture mode if the activity supports.
         * @see android.R.attr#supportsPictureInPicture
         */
        void enterPictureInPictureModeIfPossible();

        /** Returns whether the window belongs to the task root. */
        boolean isTaskRoot();

        /**
         * Update the status bar color to a forced one.
         */
        void updateStatusBarColor(int color);

        /**
         * Update the navigation bar color to a forced one.
         */
        void updateNavigationBarColor(int color);
    }

    /**
     * Callback for clients that want to be aware of where caption draws content.
     */
    public interface OnRestrictedCaptionAreaChangedListener {
        /**
         * Called when the area where caption draws content changes.
         *
         * @param rect The area where caption content is positioned, relative to the top view.
         */
        void onRestrictedCaptionAreaChanged(Rect rect);
    }

    /**
     * Callback for clients that want frame timing information for each
     * frame rendered by the Window.
     */
    public interface OnFrameMetricsAvailableListener {
        /**
         * Called when information is available for the previously rendered frame.
         *
         * Reports can be dropped if this callback takes too
         * long to execute, as the report producer cannot wait for the consumer to
         * complete.
         *
         * It is highly recommended that clients copy the passed in FrameMetrics
         * via {@link FrameMetrics#FrameMetrics(FrameMetrics)} within this method and defer
         * additional computation or storage to another thread to avoid unnecessarily
         * dropping reports.
         *
         * @param window The {@link Window} on which the frame was displayed.
         * @param frameMetrics the available metrics. This object is reused on every call
         * and thus <strong>this reference is not valid outside the scope of this method</strong>.
         * @param dropCountSinceLastInvocation the number of reports dropped since the last time
         * this callback was invoked.
         */
        void onFrameMetricsAvailable(Window window, FrameMetrics frameMetrics,
                int dropCountSinceLastInvocation);
    }

    /**
     * Listener for applying window insets on the content of a window. Used only by the framework to
     * fit content according to legacy SystemUI flags.
     *
     * @hide
     */
    public interface OnContentApplyWindowInsetsListener {

        /**
         * Called when the window needs to apply insets on the container of its content view which
         * are set by calling {@link #setContentView}. The method should determine what insets to
         * apply on the container of the root level content view and what should be dispatched to
         * the content view's
         * {@link View#setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener)} through the view
         * hierarchy.
         *
         * @param view The view for which to apply insets. Must not be directly modified.
         * @param insets The root level insets that are about to be dispatched
         * @return A pair, with the first element containing the insets to apply as margin to the
         * root-level content views, and the second element determining what should be
         * dispatched to the content view.
         */
        @NonNull
        Pair<Insets, WindowInsets> onContentApplyWindowInsets(@NonNull View view,
                @NonNull WindowInsets insets);
    }


    public Window(@UiContext Context context) {
        mContext = context;
        mFeatures = mLocalFeatures = getDefaultFeatures(context);
    }

    /**
     * Return the Context this window policy is running in, for retrieving
     * resources and other information.
     *
     * @return Context The Context that was supplied to the constructor.
     */
    @UiContext
    public final Context getContext() {
        return mContext;
    }

    /**
     * Return the {@link android.R.styleable#Window} attributes from this
     * window's theme.
     */
    public final TypedArray getWindowStyle() {
        synchronized (this) {
            if (mWindowStyle == null) {
                mWindowStyle = mContext.obtainStyledAttributes(
                        com.android.internal.R.styleable.Window);
            }
            return mWindowStyle;
        }
    }

    /**
     * Set the container for this window.  If not set, the DecorWindow
     * operates as a top-level window; otherwise, it negotiates with the
     * container to display itself appropriately.
     *
     * @param container The desired containing Window.
     */
    public void setContainer(Window container) {
        mContainer = container;
        if (container != null) {
            // Embedded screens never have a title.
            mFeatures |= 1<<FEATURE_NO_TITLE;
            mLocalFeatures |= 1<<FEATURE_NO_TITLE;
            container.mHasChildren = true;
        }
    }

    /**
     * Return the container for this Window.
     *
     * @return Window The containing window, or null if this is a
     *         top-level window.
     */
    public final Window getContainer() {
        return mContainer;
    }

    public final boolean hasChildren() {
        return mHasChildren;
    }

    /** @hide */
    public final void destroy() {
        mDestroyed = true;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Set the window manager for use by this Window to, for example,
     * display panels.  This is <em>not</em> used for displaying the
     * Window itself -- that must be done by the client.
     *
     * @param wm The window manager for adding new windows.
     */
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName) {
        setWindowManager(wm, appToken, appName, false);
    }

    /**
     * Set the window manager for use by this Window to, for example,
     * display panels.  This is <em>not</em> used for displaying the
     * Window itself -- that must be done by the client.
     *
     * @param wm The window manager for adding new windows.
     */
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName,
            boolean hardwareAccelerated) {
        mAppToken = appToken;
        mAppName = appName;
        mHardwareAccelerated = hardwareAccelerated;
        if (wm == null) {
            wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        }
        mWindowManager = ((WindowManagerImpl)wm).createLocalWindowManager(this);
    }

    void adjustLayoutParamsForSubWindow(WindowManager.LayoutParams wp) {
        CharSequence curTitle = wp.getTitle();
        if (wp.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                wp.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
            if (wp.token == null) {
                View decor = peekDecorView();
                if (decor != null) {
                    wp.token = decor.getWindowToken();
                }
            }
            if (curTitle == null || curTitle.length() == 0) {
                final StringBuilder title = new StringBuilder(32);
                if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA) {
                    title.append("Media");
                } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY) {
                    title.append("MediaOvr");
                } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                    title.append("Panel");
                } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) {
                    title.append("SubPanel");
                } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL) {
                    title.append("AboveSubPanel");
                } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) {
                    title.append("AtchDlg");
                } else {
                    title.append(wp.type);
                }
                if (mAppName != null) {
                    title.append(":").append(mAppName);
                }
                wp.setTitle(title);
            }
        } else if (wp.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW &&
                wp.type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
            // We don't set the app token to this system window because the life cycles should be
            // independent. If an app creates a system window and then the app goes to the stopped
            // state, the system window should not be affected (can still show and receive input
            // events).
            if (curTitle == null || curTitle.length() == 0) {
                final StringBuilder title = new StringBuilder(32);
                title.append("Sys").append(wp.type);
                if (mAppName != null) {
                    title.append(":").append(mAppName);
                }
                wp.setTitle(title);
            }
        } else {
            if (wp.token == null) {
                wp.token = mContainer == null ? mAppToken : mContainer.mAppToken;
            }
            if ((curTitle == null || curTitle.length() == 0)
                    && mAppName != null) {
                wp.setTitle(mAppName);
            }
        }
        if (wp.packageName == null) {
            wp.packageName = mContext.getPackageName();
        }
        if (mHardwareAccelerated ||
                (mWindowAttributes.flags & FLAG_HARDWARE_ACCELERATED) != 0) {
            wp.flags |= FLAG_HARDWARE_ACCELERATED;
        }
    }

    /**
     * Return the window manager allowing this Window to display its own
     * windows.
     *
     * @return WindowManager The ViewManager.
     */
    public WindowManager getWindowManager() {
        return mWindowManager;
    }

    /**
     * Set the Callback interface for this window, used to intercept key
     * events and other dynamic operations in the window.
     *
     * @param callback The desired Callback interface.
     */
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * Return the current Callback interface for this window.
     */
    public final Callback getCallback() {
        return mCallback;
    }

    /**
     * Set an observer to collect frame stats for each frame rendered in this window.
     *
     * Must be in hardware rendering mode.
     */
    public final void addOnFrameMetricsAvailableListener(
            @NonNull OnFrameMetricsAvailableListener listener,
            Handler handler) {
        final View decorView = getDecorView();
        if (decorView == null) {
            throw new IllegalStateException("can't observe a Window without an attached view");
        }

        if (listener == null) {
            throw new NullPointerException("listener cannot be null");
        }

        decorView.addFrameMetricsListener(this, listener, handler);
    }

    /**
     * Remove observer and stop listening to frame stats for this window.
     */
    public final void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {
        final View decorView = getDecorView();
        if (decorView != null) {
            getDecorView().removeFrameMetricsListener(listener);
        }
    }

    /** @hide */
    public final void setOnWindowDismissedCallback(OnWindowDismissedCallback dcb) {
        mOnWindowDismissedCallback = dcb;
    }

    /** @hide */
    public final void dispatchOnWindowDismissed(
            boolean finishTask, boolean suppressWindowTransition) {
        if (mOnWindowDismissedCallback != null) {
            mOnWindowDismissedCallback.onWindowDismissed(finishTask, suppressWindowTransition);
        }
    }

    /** @hide */
    public final void setOnWindowSwipeDismissedCallback(OnWindowSwipeDismissedCallback sdcb) {
        mOnWindowSwipeDismissedCallback = sdcb;
    }

    /** @hide */
    public final void dispatchOnWindowSwipeDismissed() {
        if (mOnWindowSwipeDismissedCallback != null) {
            mOnWindowSwipeDismissedCallback.onWindowSwipeDismissed();
        }
    }

    /** @hide */
    public final void setWindowControllerCallback(WindowControllerCallback wccb) {
        mWindowControllerCallback = wccb;
    }

    /** @hide */
    public final WindowControllerCallback getWindowControllerCallback() {
        return mWindowControllerCallback;
    }

    /**
     * Set a callback for changes of area where caption will draw its content.
     *
     * @param listener Callback that will be called when the area changes.
     */
    public final void setRestrictedCaptionAreaListener(OnRestrictedCaptionAreaChangedListener listener) {
        mOnRestrictedCaptionAreaChangedListener = listener;
        mRestrictedCaptionAreaRect = listener != null ? new Rect() : null;
    }

    /**
     * Prevent non-system overlay windows from being drawn on top of this window.
     *
     * @param hide whether non-system overlay windows should be hidden.
     */
    @RequiresPermission(HIDE_OVERLAY_WINDOWS)
    public final void setHideOverlayWindows(boolean hide) {
        // This permission check is here to throw early and let the developer know that they need
        // to hold HIDE_OVERLAY_WINDOWS for the flag to have any effect. The WM verifies that the
        // owner of the window has the permission before applying the flag, but this is done
        // asynchronously.
        if (mContext.checkSelfPermission(HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != PERMISSION_GRANTED
                && mContext.checkSelfPermission(HIDE_OVERLAY_WINDOWS) != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Permission denial: setHideOverlayWindows: HIDE_OVERLAY_WINDOWS");
        }
        setPrivateFlags(hide ? SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS : 0,
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    /**
     * Take ownership of this window's surface.  The window's view hierarchy
     * will no longer draw into the surface, though it will otherwise continue
     * to operate (such as for receiving input events).  The given SurfaceHolder
     * callback will be used to tell you about state changes to the surface.
     */
    public abstract void takeSurface(SurfaceHolder.Callback2 callback);

    /**
     * Take ownership of this window's InputQueue.  The window will no
     * longer read and dispatch input events from the queue; it is your
     * responsibility to do so.
     */
    public abstract void takeInputQueue(InputQueue.Callback callback);

    /**
     * Return whether this window is being displayed with a floating style
     * (based on the {@link android.R.attr#windowIsFloating} attribute in
     * the style/theme).
     *
     * @return Returns true if the window is configured to be displayed floating
     * on top of whatever is behind it.
     */
    public abstract boolean isFloating();

    /**
     * Set the width and height layout parameters of the window.  The default
     * for both of these is MATCH_PARENT; you can change them to WRAP_CONTENT
     * or an absolute value to make a window that is not full-screen.
     *
     * @param width The desired layout width of the window.
     * @param height The desired layout height of the window.
     *
     * @see ViewGroup.LayoutParams#height
     * @see ViewGroup.LayoutParams#width
     */
    public void setLayout(int width, int height) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.width = width;
        attrs.height = height;
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Set the gravity of the window, as per the Gravity constants.  This
     * controls how the window manager is positioned in the overall window; it
     * is only useful when using WRAP_CONTENT for the layout width or height.
     *
     * @param gravity The desired gravity constant.
     *
     * @see Gravity
     * @see #setLayout
     */
    public void setGravity(int gravity)
    {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.gravity = gravity;
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Set the type of the window, as per the WindowManager.LayoutParams
     * types.
     *
     * @param type The new window type (see WindowManager.LayoutParams).
     */
    public void setType(int type) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.type = type;
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Set the format of window, as per the PixelFormat types.  This overrides
     * the default format that is selected by the Window based on its
     * window decorations.
     *
     * @param format The new window format (see PixelFormat).  Use
     *               PixelFormat.UNKNOWN to allow the Window to select
     *               the format.
     *
     * @see PixelFormat
     */
    public void setFormat(int format) {
        final WindowManager.LayoutParams attrs = getAttributes();
        if (format != PixelFormat.UNKNOWN) {
            attrs.format = format;
            mHaveWindowFormat = true;
        } else {
            attrs.format = mDefaultWindowFormat;
            mHaveWindowFormat = false;
        }
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Specify custom animations to use for the window, as per
     * {@link WindowManager.LayoutParams#windowAnimations
     * WindowManager.LayoutParams.windowAnimations}.  Providing anything besides
     * 0 here will override the animations the window would
     * normally retrieve from its theme.
     */
    public void setWindowAnimations(@StyleRes int resId) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.windowAnimations = resId;
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Specify an explicit soft input mode to use for the window, as per
     * {@link WindowManager.LayoutParams#softInputMode
     * WindowManager.LayoutParams.softInputMode}.  Providing anything besides
     * "unspecified" here will override the input mode the window would
     * normally retrieve from its theme.
     */
    public void setSoftInputMode(int mode) {
        final WindowManager.LayoutParams attrs = getAttributes();
        if (mode != WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) {
            attrs.softInputMode = mode;
            mHasSoftInputMode = true;
        } else {
            mHasSoftInputMode = false;
        }
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Convenience function to set the flag bits as specified in flags, as
     * per {@link #setFlags}.
     * @param flags The flag bits to be set.
     * @see #setFlags
     * @see #clearFlags
     */
    public void addFlags(int flags) {
        setFlags(flags, flags);
    }

    /**
     * Add private flag bits.
     *
     * <p>Refer to the individual flags for the permissions needed.
     *
     * @param flags The flag bits to add.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void addPrivateFlags(int flags) {
        setPrivateFlags(flags, flags);
    }

    /**
     * Add system flag bits.
     *
     * <p>Refer to the individual flags for the permissions needed.
     *
     * <p>Note: Only for updateable system components (aka. mainline modules)
     *
     * @param flags The flag bits to add.
     *
     * @hide
     */
    @SystemApi
    public void addSystemFlags(@WindowManager.LayoutParams.SystemFlags int flags) {
        addPrivateFlags(flags);
    }

    /**
     * Convenience function to clear the flag bits as specified in flags, as
     * per {@link #setFlags}.
     * @param flags The flag bits to be cleared.
     * @see #setFlags
     * @see #addFlags
     */
    public void clearFlags(int flags) {
        setFlags(0, flags);
    }

    /**
     * Set the flags of the window, as per the
     * {@link WindowManager.LayoutParams WindowManager.LayoutParams}
     * flags.
     *
     * <p>Note that some flags must be set before the window decoration is
     * created (by the first call to
     * {@link #setContentView(View, android.view.ViewGroup.LayoutParams)} or
     * {@link #getDecorView()}:
     * {@link WindowManager.LayoutParams#FLAG_LAYOUT_IN_SCREEN} and
     * {@link WindowManager.LayoutParams#FLAG_LAYOUT_INSET_DECOR}.  These
     * will be set for you based on the {@link android.R.attr#windowIsFloating}
     * attribute.
     *
     * @param flags The new window flags (see WindowManager.LayoutParams).
     * @param mask Which of the window flag bits to modify.
     * @see #addFlags
     * @see #clearFlags
     */
    public void setFlags(int flags, int mask) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.flags = (attrs.flags&~mask) | (flags&mask);
        mForcedWindowFlags |= mask;
        dispatchWindowAttributesChanged(attrs);
    }

    private void setPrivateFlags(int flags, int mask) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.privateFlags = (attrs.privateFlags & ~mask) | (flags & mask);
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * {@hide}
     */
    protected void dispatchWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
    }

    /**
     * <p>Sets the requested color mode of the window. The requested the color mode might
     * override the window's pixel {@link WindowManager.LayoutParams#format format}.</p>
     *
     * <p>The requested color mode must be one of {@link ActivityInfo#COLOR_MODE_DEFAULT},
     * {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT} or {@link ActivityInfo#COLOR_MODE_HDR}.</p>
     *
     * <p>The requested color mode is not guaranteed to be honored. Please refer to
     * {@link #getColorMode()} for more information.</p>
     *
     * @see #getColorMode()
     * @see Display#isWideColorGamut()
     * @see Configuration#isScreenWideColorGamut()
     */
    public void setColorMode(@ActivityInfo.ColorMode int colorMode) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.setColorMode(colorMode);
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * If {@code isPreferred} is true, this method requests that the connected display does minimal
     * post processing when this window is visible on the screen. Otherwise, it requests that the
     * display switches back to standard image processing.
     *
     * <p> By default, the display does not do minimal post processing and if this is desired, this
     * method should not be used. It should be used with {@code isPreferred=true} when low
     * latency has a higher priority than image enhancement processing (e.g. for games or video
     * conferencing). The display will automatically go back into standard image processing mode
     * when no window requesting minimal posst processing is visible on screen anymore.
     * {@code setPreferMinimalPostProcessing(false)} can be used if
     * {@code setPreferMinimalPostProcessing(true)} was previously called for this window and
     * minimal post processing is no longer required.
     *
     * <p>If the Display sink is connected via HDMI, the device will begin to send infoframes with
     * Auto Low Latency Mode enabled and Game Content Type. This will switch the connected display
     * to a minimal image processing mode (if available), which reduces latency, improving the user
     * experience for gaming or video conferencing applications. For more information, see HDMI 2.1
     * specification.
     *
     * <p>If the Display sink has an internal connection or uses some other protocol than HDMI,
     * effects may be similar but implementation-defined.
     *
     * <p>The ability to switch to a mode with minimal post proessing may be disabled by a user
     * setting in the system settings menu. In that case, this method does nothing.
     *
     * @see android.content.pm.ActivityInfo#FLAG_PREFER_MINIMAL_POST_PROCESSING
     * @see android.view.Display#isMinimalPostProcessingSupported
     * @see android.view.WindowManager.LayoutParams#preferMinimalPostProcessing
     *
     * @param isPreferred Indicates whether minimal post processing is preferred for this window
     *                    ({@code isPreferred=true}) or not ({@code isPreferred=false}).
     */
    public void setPreferMinimalPostProcessing(boolean isPreferred) {
        mWindowAttributes.preferMinimalPostProcessing = isPreferred;
        dispatchWindowAttributesChanged(mWindowAttributes);
    }

    /**
     * Returns the requested color mode of the window, one of
     * {@link ActivityInfo#COLOR_MODE_DEFAULT}, {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT}
     * or {@link ActivityInfo#COLOR_MODE_HDR}. If {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT}
     * was requested it is possible the window will not be put in wide color gamut mode depending
     * on device and display support for that mode. Use {@link #isWideColorGamut} to determine
     * if the window is currently in wide color gamut mode.
     *
     * @see #setColorMode(int)
     * @see Display#isWideColorGamut()
     * @see Configuration#isScreenWideColorGamut()
     */
    @ActivityInfo.ColorMode
    public int getColorMode() {
        return getAttributes().getColorMode();
    }

    /**
     * Returns true if this window's color mode is {@link ActivityInfo#COLOR_MODE_WIDE_COLOR_GAMUT},
     * the display has a wide color gamut and this device supports wide color gamut rendering.
     *
     * @see Display#isWideColorGamut()
     * @see Configuration#isScreenWideColorGamut()
     */
    public boolean isWideColorGamut() {
        return getColorMode() == ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                && getContext().getResources().getConfiguration().isScreenWideColorGamut();
    }

    /**
     * Set the amount of dim behind the window when using
     * {@link WindowManager.LayoutParams#FLAG_DIM_BEHIND}.  This overrides
     * the default dim amount of that is selected by the Window based on
     * its theme.
     *
     * @param amount The new dim amount, from 0 for no dim to 1 for full dim.
     */
    public void setDimAmount(float amount) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.dimAmount = amount;
        mHaveDimAmount = true;
        dispatchWindowAttributesChanged(attrs);
    }

    /**
     * Sets whether the decor view should fit root-level content views for {@link WindowInsets}.
     * <p>
     * If set to {@code true}, the framework will inspect the now deprecated
     * {@link View#SYSTEM_UI_LAYOUT_FLAGS} as well the
     * {@link WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE} flag and fits content according
     * to these flags.
     * </p>
     * <p>
     * If set to {@code false}, the framework will not fit the content view to the insets and will
     * just pass through the {@link WindowInsets} to the content view.
     * </p>
     * @param decorFitsSystemWindows Whether the decor view should fit root-level content views for
     *                               insets.
     */
    public void setDecorFitsSystemWindows(boolean decorFitsSystemWindows) {
    }

    /**
     * Specify custom window attributes.  <strong>PLEASE NOTE:</strong> the
     * layout params you give here should generally be from values previously
     * retrieved with {@link #getAttributes()}; you probably do not want to
     * blindly create and apply your own, since this will blow away any values
     * set by the framework that you are not interested in.
     *
     * @param a The new window attributes, which will completely override any
     *          current values.
     */
    public void setAttributes(WindowManager.LayoutParams a) {
        mWindowAttributes.copyFrom(a);
        dispatchWindowAttributesChanged(mWindowAttributes);
    }

    /**
     * Retrieve the current window attributes associated with this panel.
     *
     * @return WindowManager.LayoutParams Either the existing window
     *         attributes object, or a freshly created one if there is none.
     */
    public final WindowManager.LayoutParams getAttributes() {
        return mWindowAttributes;
    }

    /**
     * Return the window flags that have been explicitly set by the client,
     * so will not be modified by {@link #getDecorView}.
     */
    protected final int getForcedWindowFlags() {
        return mForcedWindowFlags;
    }

    /**
     * Has the app specified their own soft input mode?
     */
    protected final boolean hasSoftInputMode() {
        return mHasSoftInputMode;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setCloseOnTouchOutside(boolean close) {
        mCloseOnTouchOutside = close;
        mSetCloseOnTouchOutside = true;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setCloseOnTouchOutsideIfNotSet(boolean close) {
        if (!mSetCloseOnTouchOutside) {
            mCloseOnTouchOutside = close;
            mSetCloseOnTouchOutside = true;
        }
    }

    /** @hide */
    @SuppressWarnings("HiddenAbstractMethod")
    @UnsupportedAppUsage
    public abstract void alwaysReadCloseOnTouchAttr();

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean shouldCloseOnTouch(Context context, MotionEvent event) {
        final boolean isOutside =
                event.getAction() == MotionEvent.ACTION_UP && isOutOfBounds(context, event)
                || event.getAction() == MotionEvent.ACTION_OUTSIDE;
        if (mCloseOnTouchOutside && peekDecorView() != null && isOutside) {
            return true;
        }
        return false;
    }

    /* Sets the Sustained Performance requirement for the calling window.
     * @param enable disables or enables the mode.
     */
    public void setSustainedPerformanceMode(boolean enable) {
        setPrivateFlags(enable
                ? WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE : 0,
                WindowManager.LayoutParams.PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE);
    }

    private boolean isOutOfBounds(Context context, MotionEvent event) {
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        final int slop = ViewConfiguration.get(context).getScaledWindowTouchSlop();
        final View decorView = getDecorView();
        return (x < -slop) || (y < -slop)
                || (x > (decorView.getWidth()+slop))
                || (y > (decorView.getHeight()+slop));
    }

    /**
     * Enable extended screen features.  This must be called before
     * setContentView().  May be called as many times as desired as long as it
     * is before setContentView().  If not called, no extended features
     * will be available.  You can not turn off a feature once it is requested.
     * You canot use other title features with {@link #FEATURE_CUSTOM_TITLE}.
     *
     * @param featureId The desired features, defined as constants by Window.
     * @return The features that are now set.
     */
    public boolean requestFeature(int featureId) {
        final int flag = 1<<featureId;
        mFeatures |= flag;
        mLocalFeatures |= mContainer != null ? (flag&~mContainer.mFeatures) : flag;
        return (mFeatures&flag) != 0;
    }

    /**
     * @hide Used internally to help resolve conflicting features.
     */
    protected void removeFeature(int featureId) {
        final int flag = 1<<featureId;
        mFeatures &= ~flag;
        mLocalFeatures &= ~(mContainer != null ? (flag&~mContainer.mFeatures) : flag);
    }

    public final void makeActive() {
        if (mContainer != null) {
            if (mContainer.mActiveChild != null) {
                mContainer.mActiveChild.mIsActive = false;
            }
            mContainer.mActiveChild = this;
        }
        mIsActive = true;
        onActive();
    }

    public final boolean isActive()
    {
        return mIsActive;
    }

    /**
     * Finds a view that was identified by the {@code android:id} XML attribute
     * that was processed in {@link android.app.Activity#onCreate}.
     * <p>
     * This will implicitly call {@link #getDecorView} with all of the associated side-effects.
     * <p>
     * <strong>Note:</strong> In most cases -- depending on compiler support --
     * the resulting view is automatically cast to the target class type. If
     * the target class type is unconstrained, an explicit cast may be
     * necessary.
     *
     * @param id the ID to search for
     * @return a view with given ID if found, or {@code null} otherwise
     * @see View#findViewById(int)
     * @see Window#requireViewById(int)
     */
    @Nullable
    public <T extends View> T findViewById(@IdRes int id) {
        return getDecorView().findViewById(id);
    }
    /**
     * Finds a view that was identified by the {@code android:id} XML attribute
     * that was processed in {@link android.app.Activity#onCreate}, or throws an
     * IllegalArgumentException if the ID is invalid, or there is no matching view in the hierarchy.
     * <p>
     * <strong>Note:</strong> In most cases -- depending on compiler support --
     * the resulting view is automatically cast to the target class type. If
     * the target class type is unconstrained, an explicit cast may be
     * necessary.
     *
     * @param id the ID to search for
     * @return a view with given ID
     * @see View#requireViewById(int)
     * @see Window#findViewById(int)
     */
    @NonNull
    public final <T extends View> T requireViewById(@IdRes int id) {
        T view = findViewById(id);
        if (view == null) {
            throw new IllegalArgumentException("ID does not reference a View inside this Window");
        }
        return view;
    }

    /**
     * Convenience for
     * {@link #setContentView(View, android.view.ViewGroup.LayoutParams)}
     * to set the screen content from a layout resource.  The resource will be
     * inflated, adding all top-level views to the screen.
     *
     * @param layoutResID Resource ID to be inflated.
     * @see #setContentView(View, android.view.ViewGroup.LayoutParams)
     */
    public abstract void setContentView(@LayoutRes int layoutResID);

    /**
     * Convenience for
     * {@link #setContentView(View, android.view.ViewGroup.LayoutParams)}
     * set the screen content to an explicit view.  This view is placed
     * directly into the screen's view hierarchy.  It can itself be a complex
     * view hierarhcy.
     *
     * @param view The desired content to display.
     * @see #setContentView(View, android.view.ViewGroup.LayoutParams)
     */
    public abstract void setContentView(View view);

    /**
     * Set the screen content to an explicit view.  This view is placed
     * directly into the screen's view hierarchy.  It can itself be a complex
     * view hierarchy.
     *
     * <p>Note that calling this function "locks in" various characteristics
     * of the window that can not, from this point forward, be changed: the
     * features that have been requested with {@link #requestFeature(int)},
     * and certain window flags as described in {@link #setFlags(int, int)}.</p>
     *
     * <p>If {@link #FEATURE_CONTENT_TRANSITIONS} is set, the window's
     * TransitionManager will be used to animate content from the current
     * content View to view.</p>
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     * @see #getTransitionManager()
     * @see #setTransitionManager(android.transition.TransitionManager)
     */
    public abstract void setContentView(View view, ViewGroup.LayoutParams params);

    /**
     * Variation on
     * {@link #setContentView(View, android.view.ViewGroup.LayoutParams)}
     * to add an additional content view to the screen.  Added after any existing
     * ones in the screen -- existing views are NOT removed.
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public abstract void addContentView(View view, ViewGroup.LayoutParams params);

    /**
     * Remove the view that was used as the screen content.
     *
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void clearContentView();

    /**
     * Return the view in this Window that currently has focus, or null if
     * there are none.  Note that this does not look in any containing
     * Window.
     *
     * @return View The current View with focus or null.
     */
    @Nullable
    public abstract View getCurrentFocus();

    /**
     * Quick access to the {@link LayoutInflater} instance that this Window
     * retrieved from its Context.
     *
     * @return LayoutInflater The shared LayoutInflater.
     */
    @NonNull
    public abstract LayoutInflater getLayoutInflater();

    public abstract void setTitle(CharSequence title);

    @Deprecated
    public abstract void setTitleColor(@ColorInt int textColor);

    public abstract void openPanel(int featureId, KeyEvent event);

    public abstract void closePanel(int featureId);

    public abstract void togglePanel(int featureId, KeyEvent event);

    public abstract void invalidatePanelMenu(int featureId);

    public abstract boolean performPanelShortcut(int featureId,
                                                 int keyCode,
                                                 KeyEvent event,
                                                 int flags);
    public abstract boolean performPanelIdentifierAction(int featureId,
                                                 int id,
                                                 int flags);

    public abstract void closeAllPanels();

    public abstract boolean performContextMenuIdentifierAction(int id, int flags);

    /**
     * Should be called when the configuration is changed.
     *
     * @param newConfig The new configuration.
     */
    public abstract void onConfigurationChanged(Configuration newConfig);

    /**
     * Sets the window elevation.
     * <p>
     * Changes to this property take effect immediately and will cause the
     * window surface to be recreated. This is an expensive operation and as a
     * result, this property should not be animated.
     *
     * @param elevation The window elevation.
     * @see View#setElevation(float)
     * @see android.R.styleable#Window_windowElevation
     */
    public void setElevation(float elevation) {}

    /**
     * Gets the window elevation.
     *
     * @hide
     */
    public float getElevation() {
        return 0.0f;
    }

    /**
     * Sets whether window content should be clipped to the outline of the
     * window background.
     *
     * @param clipToOutline Whether window content should be clipped to the
     *                      outline of the window background.
     * @see View#setClipToOutline(boolean)
     * @see android.R.styleable#Window_windowClipToOutline
     */
    public void setClipToOutline(boolean clipToOutline) {}

    /**
     * Change the background of this window to a Drawable resource. Setting the
     * background to null will make the window be opaque. To make the window
     * transparent, you can use an empty drawable (for instance a ColorDrawable
     * with the color 0 or the system drawable android:drawable/empty.)
     *
     * @param resId The resource identifier of a drawable resource which will
     *              be installed as the new background.
     */
    public void setBackgroundDrawableResource(@DrawableRes int resId) {
        setBackgroundDrawable(mContext.getDrawable(resId));
    }

    /**
     * Change the background of this window to a custom Drawable. Setting the
     * background to null will make the window be opaque. To make the window
     * transparent, you can use an empty drawable (for instance a ColorDrawable
     * with the color 0 or the system drawable android:drawable/empty.)
     *
     * @param drawable The new Drawable to use for this window's background.
     */
    public abstract void setBackgroundDrawable(Drawable drawable);

    /**
     * <p>
     * Blurs the screen behind the window within the bounds of the window.
     * </p><p>
     * The density of the blur is set by the blur radius. The radius defines the size
     * of the neighbouring area, from which pixels will be averaged to form the final
     * color for each pixel. The operation approximates a Gaussian blur.
     * A radius of 0 means no blur. The higher the radius, the denser the blur.
     * </p><p>
     * The window background drawable is drawn on top of the blurred region. The blur
     * region bounds and rounded corners will mimic those of the background drawable.
     * </p><p>
     * For the blur region to be visible, the window has to be translucent. See
     * {@link android.R.styleable#Window_windowIsTranslucent}.
     * </p><p>
     * Note the difference with {@link WindowManager.LayoutParams#setBlurBehindRadius},
     * which blurs the whole screen behind the window. Background blur blurs the screen behind
     * only within the bounds of the window.
     * </p><p>
     * Some devices might not support cross-window blur due to GPU limitations. It can also be
     * disabled at runtime, e.g. during battery saving mode, when multimedia tunneling is used or
     * when minimal post processing is requested. In such situations, no blur will be computed or
     * drawn, resulting in a transparent window background. To avoid this, the app might want to
     * change its theme to one that does not use blurs. To listen for cross-window blur
     * enabled/disabled events, use {@link WindowManager#addCrossWindowBlurEnabledListener}.
     * </p>
     *
     * @param blurRadius The blur radius to use for window background blur in pixels
     *
     * @see android.R.styleable#Window_windowBackgroundBlurRadius
     * @see WindowManager.LayoutParams#setBlurBehindRadius
     * @see WindowManager#addCrossWindowBlurEnabledListener
     */
    public void setBackgroundBlurRadius(int blurRadius) {}

    /**
     * Set the value for a drawable feature of this window, from a resource
     * identifier.  You must have called requestFeature(featureId) before
     * calling this function.
     *
     * @see android.content.res.Resources#getDrawable(int)
     *
     * @param featureId The desired drawable feature to change, defined as a
     * constant by Window.
     * @param resId Resource identifier of the desired image.
     */
    public abstract void setFeatureDrawableResource(int featureId, @DrawableRes int resId);

    /**
     * Set the value for a drawable feature of this window, from a URI. You
     * must have called requestFeature(featureId) before calling this
     * function.
     *
     * <p>The only URI currently supported is "content:", specifying an image
     * in a content provider.
     *
     * @see android.widget.ImageView#setImageURI
     *
     * @param featureId The desired drawable feature to change. Features are
     * constants defined by Window.
     * @param uri The desired URI.
     */
    public abstract void setFeatureDrawableUri(int featureId, Uri uri);

    /**
     * Set an explicit Drawable value for feature of this window. You must
     * have called requestFeature(featureId) before calling this function.
     *
     * @param featureId The desired drawable feature to change. Features are
     *                  constants defined by Window.
     * @param drawable A Drawable object to display.
     */
    public abstract void setFeatureDrawable(int featureId, Drawable drawable);

    /**
     * Set a custom alpha value for the given drawable feature, controlling how
     * much the background is visible through it.
     *
     * @param featureId The desired drawable feature to change. Features are
     *                  constants defined by Window.
     * @param alpha The alpha amount, 0 is completely transparent and 255 is
     *              completely opaque.
     */
    public abstract void setFeatureDrawableAlpha(int featureId, int alpha);

    /**
     * Set the integer value for a feature. The range of the value depends on
     * the feature being set. For {@link #FEATURE_PROGRESS}, it should go from
     * 0 to 10000. At 10000 the progress is complete and the indicator hidden.
     *
     * @param featureId The desired feature to change. Features are constants
     *                  defined by Window.
     * @param value The value for the feature. The interpretation of this
     *              value is feature-specific.
     */
    public abstract void setFeatureInt(int featureId, int value);

    /**
     * Request that key events come to this activity. Use this if your
     * activity has no views with focus, but the activity still wants
     * a chance to process key events.
     */
    public abstract void takeKeyEvents(boolean get);

    /**
     * Used by custom windows, such as Dialog, to pass the key press event
     * further down the view hierarchy. Application developers should
     * not need to implement or call this.
     *
     */
    public abstract boolean superDispatchKeyEvent(KeyEvent event);

    /**
     * Used by custom windows, such as Dialog, to pass the key shortcut press event
     * further down the view hierarchy. Application developers should
     * not need to implement or call this.
     *
     */
    public abstract boolean superDispatchKeyShortcutEvent(KeyEvent event);

    /**
     * Used by custom windows, such as Dialog, to pass the touch screen event
     * further down the view hierarchy. Application developers should
     * not need to implement or call this.
     *
     */
    public abstract boolean superDispatchTouchEvent(MotionEvent event);

    /**
     * Used by custom windows, such as Dialog, to pass the trackball event
     * further down the view hierarchy. Application developers should
     * not need to implement or call this.
     *
     */
    public abstract boolean superDispatchTrackballEvent(MotionEvent event);

    /**
     * Used by custom windows, such as Dialog, to pass the generic motion event
     * further down the view hierarchy. Application developers should
     * not need to implement or call this.
     *
     */
    public abstract boolean superDispatchGenericMotionEvent(MotionEvent event);

    /**
     * Retrieve the top-level window decor view (containing the standard
     * window frame/decorations and the client's content inside of that), which
     * can be added as a window to the window manager.
     *
     * <p><em>Note that calling this function for the first time "locks in"
     * various window characteristics as described in
     * {@link #setContentView(View, android.view.ViewGroup.LayoutParams)}.</em></p>
     *
     * @return Returns the top-level window decor view.
     */
    public abstract @NonNull View getDecorView();

    /**
     * @return the status bar background view or null.
     * @hide
     */
    @TestApi
    public @Nullable View getStatusBarBackgroundView() {
        return null;
    }

    /**
     * @return the navigation bar background view or null.
     * @hide
     */
    @TestApi
    public @Nullable View getNavigationBarBackgroundView() {
        return null;
    }

    /**
     * Retrieve the current decor view, but only if it has already been created;
     * otherwise returns null.
     *
     * @return Returns the top-level window decor or null.
     * @see #getDecorView
     */
    public abstract View peekDecorView();

    public abstract Bundle saveHierarchyState();

    public abstract void restoreHierarchyState(Bundle savedInstanceState);

    protected abstract void onActive();

    /**
     * Return the feature bits that are enabled.  This is the set of features
     * that were given to requestFeature(), and are being handled by this
     * Window itself or its container.  That is, it is the set of
     * requested features that you can actually use.
     *
     * <p>To do: add a public version of this API that allows you to check for
     * features by their feature ID.
     *
     * @return int The feature bits.
     */
    protected final int getFeatures()
    {
        return mFeatures;
    }

    /**
     * Return the feature bits set by default on a window.
     * @param context The context used to access resources
     */
    public static int getDefaultFeatures(Context context) {
        int features = 0;

        final Resources res = context.getResources();
        if (res.getBoolean(com.android.internal.R.bool.config_defaultWindowFeatureOptionsPanel)) {
            features |= 1 << FEATURE_OPTIONS_PANEL;
        }

        if (res.getBoolean(com.android.internal.R.bool.config_defaultWindowFeatureContextMenu)) {
            features |= 1 << FEATURE_CONTEXT_MENU;
        }

        return features;
    }

    /**
     * Query for the availability of a certain feature.
     *
     * @param feature The feature ID to check
     * @return true if the feature is enabled, false otherwise.
     */
    public boolean hasFeature(int feature) {
        return (getFeatures() & (1 << feature)) != 0;
    }

    /**
     * Return the feature bits that are being implemented by this Window.
     * This is the set of features that were given to requestFeature(), and are
     * being handled by only this Window itself, not by its containers.
     *
     * @return int The feature bits.
     */
    protected final int getLocalFeatures()
    {
        return mLocalFeatures;
    }

    /**
     * Set the default format of window, as per the PixelFormat types.  This
     * is the format that will be used unless the client specifies in explicit
     * format with setFormat();
     *
     * @param format The new window format (see PixelFormat).
     *
     * @see #setFormat
     * @see PixelFormat
     */
    protected void setDefaultWindowFormat(int format) {
        mDefaultWindowFormat = format;
        if (!mHaveWindowFormat) {
            final WindowManager.LayoutParams attrs = getAttributes();
            attrs.format = format;
            dispatchWindowAttributesChanged(attrs);
        }
    }

    /** @hide */
    protected boolean haveDimAmount() {
        return mHaveDimAmount;
    }

    public abstract void setChildDrawable(int featureId, Drawable drawable);

    public abstract void setChildInt(int featureId, int value);

    /**
     * Is a keypress one of the defined shortcut keys for this window.
     * @param keyCode the key code from {@link android.view.KeyEvent} to check.
     * @param event the {@link android.view.KeyEvent} to use to help check.
     */
    public abstract boolean isShortcutKey(int keyCode, KeyEvent event);

    /**
     * @see android.app.Activity#setVolumeControlStream(int)
     */
    public abstract void setVolumeControlStream(int streamType);

    /**
     * @see android.app.Activity#getVolumeControlStream()
     */
    public abstract int getVolumeControlStream();

    /**
     * Sets a {@link MediaController} to send media keys and volume changes to.
     * If set, this should be preferred for all media keys and volume requests
     * sent to this window.
     *
     * @param controller The controller for the session which should receive
     *            media keys and volume changes.
     * @see android.app.Activity#setMediaController(android.media.session.MediaController)
     */
    public void setMediaController(MediaController controller) {
    }

    /**
     * Gets the {@link MediaController} that was previously set.
     *
     * @return The controller which should receive events.
     * @see #setMediaController(android.media.session.MediaController)
     * @see android.app.Activity#getMediaController()
     */
    public MediaController getMediaController() {
        return null;
    }

    /**
     * Set extra options that will influence the UI for this window.
     * @param uiOptions Flags specifying extra options for this window.
     */
    public void setUiOptions(int uiOptions) { }

    /**
     * Set extra options that will influence the UI for this window.
     * Only the bits filtered by mask will be modified.
     * @param uiOptions Flags specifying extra options for this window.
     * @param mask Flags specifying which options should be modified. Others will remain unchanged.
     */
    public void setUiOptions(int uiOptions, int mask) { }

    /**
     * Set the primary icon for this window.
     *
     * @param resId resource ID of a drawable to set
     */
    public void setIcon(@DrawableRes int resId) { }

    /**
     * Set the default icon for this window.
     * This will be overridden by any other icon set operation which could come from the
     * theme or another explicit set.
     *
     * @hide
     */
    public void setDefaultIcon(@DrawableRes int resId) { }

    /**
     * Set the logo for this window. A logo is often shown in place of an
     * {@link #setIcon(int) icon} but is generally wider and communicates window title information
     * as well.
     *
     * @param resId resource ID of a drawable to set
     */
    public void setLogo(@DrawableRes int resId) { }

    /**
     * Set the default logo for this window.
     * This will be overridden by any other logo set operation which could come from the
     * theme or another explicit set.
     *
     * @hide
     */
    public void setDefaultLogo(@DrawableRes int resId) { }

    /**
     * Set focus locally. The window should have the
     * {@link WindowManager.LayoutParams#FLAG_LOCAL_FOCUS_MODE} flag set already.
     * @param hasFocus Whether this window has focus or not.
     * @param inTouchMode Whether this window is in touch mode or not.
     */
    public void setLocalFocus(boolean hasFocus, boolean inTouchMode) { }

    /**
     * Inject an event to window locally.
     * @param event A key or touch event to inject to this window.
     */
    public void injectInputEvent(InputEvent event) { }

    /**
     * Retrieve the {@link TransitionManager} responsible for  for default transitions
     * in this window. Requires {@link #FEATURE_CONTENT_TRANSITIONS}.
     *
     * <p>This method will return non-null after content has been initialized (e.g. by using
     * {@link #setContentView}) if {@link #FEATURE_CONTENT_TRANSITIONS} has been granted.</p>
     *
     * @return This window's content TransitionManager or null if none is set.
     * @attr ref android.R.styleable#Window_windowContentTransitionManager
     */
    public TransitionManager getTransitionManager() {
        return null;
    }

    /**
     * Set the {@link TransitionManager} to use for default transitions in this window.
     * Requires {@link #FEATURE_CONTENT_TRANSITIONS}.
     *
     * @param tm The TransitionManager to use for scene changes.
     * @attr ref android.R.styleable#Window_windowContentTransitionManager
     */
    public void setTransitionManager(TransitionManager tm) {
        throw new UnsupportedOperationException();
    }

    /**
     * Retrieve the {@link Scene} representing this window's current content.
     * Requires {@link #FEATURE_CONTENT_TRANSITIONS}.
     *
     * <p>This method will return null if the current content is not represented by a Scene.</p>
     *
     * @return Current Scene being shown or null
     */
    public Scene getContentScene() {
        return null;
    }

    /**
     * Sets the Transition that will be used to move Views into the initial scene. The entering
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#INVISIBLE} to {@link View#VISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected.
     *
     * @param transition The Transition to use to move Views into the initial Scene.
     * @attr ref android.R.styleable#Window_windowEnterTransition
     */
    public void setEnterTransition(Transition transition) {}

    /**
     * Sets the Transition that will be used to move Views out of the scene when the Window is
     * preparing to close, for example after a call to
     * {@link android.app.Activity#finishAfterTransition()}. The exiting
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#VISIBLE} to {@link View#INVISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected. If nothing is set, the default will be to
     * use the same value as set in {@link #setEnterTransition(android.transition.Transition)}.
     *
     * @param transition The Transition to use to move Views out of the Scene when the Window
     *                   is preparing to close.
     * @attr ref android.R.styleable#Window_windowReturnTransition
     */
    public void setReturnTransition(Transition transition) {}

    /**
     * Sets the Transition that will be used to move Views out of the scene when starting a
     * new Activity. The exiting Views will be those that are regular Views or ViewGroups that
     * have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as exiting is governed by changing visibility
     * from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null, the views will
     * remain unaffected. Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use to move Views out of the scene when calling a
     *                   new Activity.
     * @attr ref android.R.styleable#Window_windowExitTransition
     */
    public void setExitTransition(Transition transition) {}

    /**
     * Sets the Transition that will be used to move Views in to the scene when returning from
     * a previously-started Activity. The entering Views will be those that are regular Views
     * or ViewGroups that have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions
     * will extend {@link android.transition.Visibility} as exiting is governed by changing
     * visibility from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null,
     * the views will remain unaffected. If nothing is set, the default will be to use the same
     * transition as {@link #setExitTransition(android.transition.Transition)}.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use to move Views into the scene when reentering from a
     *                   previously-started Activity.
     * @attr ref android.R.styleable#Window_windowReenterTransition
     */
    public void setReenterTransition(Transition transition) {}

    /**
     * Returns the transition used to move Views into the initial scene. The entering
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#INVISIBLE} to {@link View#VISIBLE}. If <code>transition</code> is null,
     * entering Views will remain unaffected.  Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return the Transition to use to move Views into the initial Scene.
     * @attr ref android.R.styleable#Window_windowEnterTransition
     */
    public Transition getEnterTransition() { return null; }

    /**
     * Returns the Transition that will be used to move Views out of the scene when the Window is
     * preparing to close, for example after a call to
     * {@link android.app.Activity#finishAfterTransition()}. The exiting
     * Views will be those that are regular Views or ViewGroups that have
     * {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as entering is governed by changing visibility from
     * {@link View#VISIBLE} to {@link View#INVISIBLE}.
     *
     * @return The Transition to use to move Views out of the Scene when the Window
     *         is preparing to close.
     * @attr ref android.R.styleable#Window_windowReturnTransition
     */
    public Transition getReturnTransition() { return null; }

    /**
     * Returns the Transition that will be used to move Views out of the scene when starting a
     * new Activity. The exiting Views will be those that are regular Views or ViewGroups that
     * have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions will extend
     * {@link android.transition.Visibility} as exiting is governed by changing visibility
     * from {@link View#VISIBLE} to {@link View#INVISIBLE}. If transition is null, the views will
     * remain unaffected. Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return the Transition to use to move Views out of the scene when calling a
     * new Activity.
     * @attr ref android.R.styleable#Window_windowExitTransition
     */
    public Transition getExitTransition() { return null; }

    /**
     * Returns the Transition that will be used to move Views in to the scene when returning from
     * a previously-started Activity. The entering Views will be those that are regular Views
     * or ViewGroups that have {@link ViewGroup#isTransitionGroup} return true. Typical Transitions
     * will extend {@link android.transition.Visibility} as exiting is governed by changing
     * visibility from {@link View#VISIBLE} to {@link View#INVISIBLE}.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return The Transition to use to move Views into the scene when reentering from a
     *         previously-started Activity.
     * @attr ref android.R.styleable#Window_windowReenterTransition
     */
    public Transition getReenterTransition() { return null; }

    /**
     * Sets the Transition that will be used for shared elements transferred into the content
     * Scene. Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use for shared elements transferred into the content
     *                   Scene.
     * @attr ref android.R.styleable#Window_windowSharedElementEnterTransition
     */
    public void setSharedElementEnterTransition(Transition transition) {}

    /**
     * Sets the Transition that will be used for shared elements transferred back to a
     * calling Activity. Typical Transitions will affect size and location, such as
     * {@link android.transition.ChangeBounds}. A null
     * value will cause transferred shared elements to blink to the final position.
     * If no value is set, the default will be to use the same value as
     * {@link #setSharedElementEnterTransition(android.transition.Transition)}.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use for shared elements transferred out of the content
     *                   Scene.
     * @attr ref android.R.styleable#Window_windowSharedElementReturnTransition
     */
    public void setSharedElementReturnTransition(Transition transition) {}

    /**
     * Returns the Transition that will be used for shared elements transferred into the content
     * Scene. Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return Transition to use for sharend elements transferred into the content Scene.
     * @attr ref android.R.styleable#Window_windowSharedElementEnterTransition
     */
    public Transition getSharedElementEnterTransition() { return null; }

    /**
     * Returns the Transition that will be used for shared elements transferred back to a
     * calling Activity. Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return Transition to use for sharend elements transferred into the content Scene.
     * @attr ref android.R.styleable#Window_windowSharedElementReturnTransition
     */
    public Transition getSharedElementReturnTransition() { return null; }

    /**
     * Sets the Transition that will be used for shared elements after starting a new Activity
     * before the shared elements are transferred to the called Activity. If the shared elements
     * must animate during the exit transition, this Transition should be used. Upon completion,
     * the shared elements may be transferred to the started Activity.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use for shared elements in the launching Window
     *                   prior to transferring to the launched Activity's Window.
     * @attr ref android.R.styleable#Window_windowSharedElementExitTransition
     */
    public void setSharedElementExitTransition(Transition transition) {}

    /**
     * Sets the Transition that will be used for shared elements reentering from a started
     * Activity after it has returned the shared element to it start location. If no value
     * is set, this will default to
     * {@link #setSharedElementExitTransition(android.transition.Transition)}.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @param transition The Transition to use for shared elements in the launching Window
     *                   after the shared element has returned to the Window.
     * @attr ref android.R.styleable#Window_windowSharedElementReenterTransition
     */
    public void setSharedElementReenterTransition(Transition transition) {}

    /**
     * Returns the Transition to use for shared elements in the launching Window prior
     * to transferring to the launched Activity's Window.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return the Transition to use for shared elements in the launching Window prior
     * to transferring to the launched Activity's Window.
     * @attr ref android.R.styleable#Window_windowSharedElementExitTransition
     */
    public Transition getSharedElementExitTransition() { return null; }

    /**
     * Returns the Transition that will be used for shared elements reentering from a started
     * Activity after it has returned the shared element to it start location.
     * Requires {@link #FEATURE_ACTIVITY_TRANSITIONS}.
     *
     * @return the Transition that will be used for shared elements reentering from a started
     * Activity after it has returned the shared element to it start location.
     * @attr ref android.R.styleable#Window_windowSharedElementReenterTransition
     */
    public Transition getSharedElementReenterTransition() { return null; }

    /**
     * Controls how the transition set in
     * {@link #setEnterTransition(android.transition.Transition)} overlaps with the exit
     * transition of the calling Activity. When true, the transition will start as soon as possible.
     * When false, the transition will wait until the remote exiting transition completes before
     * starting. The default value is true.
     *
     * @param allow true to start the enter transition when possible or false to
     *              wait until the exiting transition completes.
     * @attr ref android.R.styleable#Window_windowAllowEnterTransitionOverlap
     */
    public void setAllowEnterTransitionOverlap(boolean allow) {}

    /**
     * Returns how the transition set in
     * {@link #setEnterTransition(android.transition.Transition)} overlaps with the exit
     * transition of the calling Activity. When true, the transition will start as soon as possible.
     * When false, the transition will wait until the remote exiting transition completes before
     * starting. The default value is true.
     *
     * @return true when the enter transition should start as soon as possible or false to
     * when it should wait until the exiting transition completes.
     * @attr ref android.R.styleable#Window_windowAllowEnterTransitionOverlap
     */
    public boolean getAllowEnterTransitionOverlap() { return true; }

    /**
     * Controls how the transition set in
     * {@link #setExitTransition(android.transition.Transition)} overlaps with the exit
     * transition of the called Activity when reentering after if finishes. When true,
     * the transition will start as soon as possible. When false, the transition will wait
     * until the called Activity's exiting transition completes before starting.
     * The default value is true.
     *
     * @param allow true to start the transition when possible or false to wait until the
     *              called Activity's exiting transition completes.
     * @attr ref android.R.styleable#Window_windowAllowReturnTransitionOverlap
     */
    public void setAllowReturnTransitionOverlap(boolean allow) {}

    /**
     * Returns how the transition set in
     * {@link #setExitTransition(android.transition.Transition)} overlaps with the exit
     * transition of the called Activity when reentering after if finishes. When true,
     * the transition will start as soon as possible. When false, the transition will wait
     * until the called Activity's exiting transition completes before starting.
     * The default value is true.
     *
     * @return true when the transition should start when possible or false when it should wait
     * until the called Activity's exiting transition completes.
     * @attr ref android.R.styleable#Window_windowAllowReturnTransitionOverlap
     */
    public boolean getAllowReturnTransitionOverlap() { return true; }

    /**
     * Returns the duration, in milliseconds, of the window background fade
     * when transitioning into or away from an Activity when called with an Activity Transition.
     * <p>When executing the enter transition, the background starts transparent
     * and fades in. This requires {@link #FEATURE_ACTIVITY_TRANSITIONS}. The default is
     * 300 milliseconds.</p>
     *
     * @return The duration of the window background fade to opaque during enter transition.
     * @see #getEnterTransition()
     * @attr ref android.R.styleable#Window_windowTransitionBackgroundFadeDuration
     */
    public long getTransitionBackgroundFadeDuration() { return 0; }

    /**
     * Sets the duration, in milliseconds, of the window background fade
     * when transitioning into or away from an Activity when called with an Activity Transition.
     * <p>When executing the enter transition, the background starts transparent
     * and fades in. This requires {@link #FEATURE_ACTIVITY_TRANSITIONS}. The default is
     * 300 milliseconds.</p>
     *
     * @param fadeDurationMillis The duration of the window background fade to or from opaque
     *                           during enter transition.
     * @see #setEnterTransition(android.transition.Transition)
     * @attr ref android.R.styleable#Window_windowTransitionBackgroundFadeDuration
     */
    public void setTransitionBackgroundFadeDuration(long fadeDurationMillis) { }

    /**
     * Returns <code>true</code> when shared elements should use an Overlay during
     * shared element transitions or <code>false</code> when they should animate as
     * part of the normal View hierarchy. The default value is true.
     *
     * @return <code>true</code> when shared elements should use an Overlay during
     * shared element transitions or <code>false</code> when they should animate as
     * part of the normal View hierarchy.
     * @attr ref android.R.styleable#Window_windowSharedElementsUseOverlay
     */
    public boolean getSharedElementsUseOverlay() { return true; }

    /**
     * Sets whether or not shared elements should use an Overlay during shared element transitions.
     * The default value is true.
     *
     * @param sharedElementsUseOverlay <code>true</code> indicates that shared elements should
     *                                 be transitioned with an Overlay or <code>false</code>
     *                                 to transition within the normal View hierarchy.
     * @attr ref android.R.styleable#Window_windowSharedElementsUseOverlay
     */
    public void setSharedElementsUseOverlay(boolean sharedElementsUseOverlay) { }

    /**
     * @return the color of the status bar.
     */
    @ColorInt
    public abstract int getStatusBarColor();

    /**
     * Sets the color of the status bar to {@code color}.
     *
     * For this to take effect,
     * the window must be drawing the system bar backgrounds with
     * {@link android.view.WindowManager.LayoutParams#FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS} and
     * {@link android.view.WindowManager.LayoutParams#FLAG_TRANSLUCENT_STATUS} must not be set.
     *
     * If {@code color} is not opaque, consider setting
     * {@link android.view.View#SYSTEM_UI_FLAG_LAYOUT_STABLE} and
     * {@link android.view.View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN}.
     * <p>
     * The transitionName for the view background will be "android:status:background".
     * </p>
     */
    public abstract void setStatusBarColor(@ColorInt int color);

    /**
     * @return the color of the navigation bar.
     */
    @ColorInt
    public abstract int getNavigationBarColor();

    /**
     * Sets the color of the navigation bar to {@param color}.
     *
     * For this to take effect,
     * the window must be drawing the system bar backgrounds with
     * {@link android.view.WindowManager.LayoutParams#FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS} and
     * {@link android.view.WindowManager.LayoutParams#FLAG_TRANSLUCENT_NAVIGATION} must not be set.
     *
     * If {@param color} is not opaque, consider setting
     * {@link android.view.View#SYSTEM_UI_FLAG_LAYOUT_STABLE} and
     * {@link android.view.View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.
     * <p>
     * The transitionName for the view background will be "android:navigation:background".
     * </p>
     * @attr ref android.R.styleable#Window_navigationBarColor
     */
    public abstract void setNavigationBarColor(@ColorInt int color);

    /**
     * Shows a thin line of the specified color between the navigation bar and the app
     * content.
     * <p>
     * For this to take effect,
     * the window must be drawing the system bar backgrounds with
     * {@link android.view.WindowManager.LayoutParams#FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS} and
     * {@link android.view.WindowManager.LayoutParams#FLAG_TRANSLUCENT_NAVIGATION} must not be set.
     *
     * @param dividerColor The color of the thin line.
     * @attr ref android.R.styleable#Window_navigationBarDividerColor
     */
    public void setNavigationBarDividerColor(@ColorInt int dividerColor) {
    }

    /**
     * Retrieves the color of the navigation bar divider.
     *
     * @return The color of the navigation bar divider color.
     * @see #setNavigationBarColor(int)
     * @attr ref android.R.styleable#Window_navigationBarDividerColor
     */
    public @ColorInt int getNavigationBarDividerColor() {
        return 0;
    }

    /**
     * Sets whether the system should ensure that the status bar has enough
     * contrast when a fully transparent background is requested.
     *
     * <p>If set to this value, the system will determine whether a scrim is necessary
     * to ensure that the status bar has enough contrast with the contents of
     * this app, and set an appropriate effective bar background color accordingly.
     *
     * <p>When the status bar color has a non-zero alpha value, the value of this
     * property has no effect.
     *
     * @see android.R.attr#enforceStatusBarContrast
     * @see #isStatusBarContrastEnforced
     * @see #setStatusBarColor
     */
    public void setStatusBarContrastEnforced(boolean ensureContrast) {
    }

    /**
     * Returns whether the system is ensuring that the status bar has enough contrast when a
     * fully transparent background is requested.
     *
     * <p>When the status bar color has a non-zero alpha value, the value of this
     * property has no effect.
     *
     * @return true, if the system is ensuring contrast, false otherwise.
     * @see android.R.attr#enforceStatusBarContrast
     * @see #setStatusBarContrastEnforced
     * @see #setStatusBarColor
     */
    public boolean isStatusBarContrastEnforced() {
        return false;
    }

    /**
     * Sets whether the system should ensure that the navigation bar has enough
     * contrast when a fully transparent background is requested.
     *
     * <p>If set to this value, the system will determine whether a scrim is necessary
     * to ensure that the navigation bar has enough contrast with the contents of
     * this app, and set an appropriate effective bar background color accordingly.
     *
     * <p>When the navigation bar color has a non-zero alpha value, the value of this
     * property has no effect.
     *
     * @see android.R.attr#enforceNavigationBarContrast
     * @see #isNavigationBarContrastEnforced
     * @see #setNavigationBarColor
     */
    public void setNavigationBarContrastEnforced(boolean enforceContrast) {
    }

    /**
     * Returns whether the system is ensuring that the navigation bar has enough contrast when a
     * fully transparent background is requested.
     *
     * <p>When the navigation bar color has a non-zero alpha value, the value of this
     * property has no effect.
     *
     * @return true, if the system is ensuring contrast, false otherwise.
     * @see android.R.attr#enforceNavigationBarContrast
     * @see #setNavigationBarContrastEnforced
     * @see #setNavigationBarColor
     */
    public boolean isNavigationBarContrastEnforced() {
        return false;
    }

    /**
     * Sets a list of areas within this window's coordinate space where the system should not
     * intercept touch or other pointing device gestures.
     *
     * <p>This method should be used by apps that make use of
     * {@link #takeSurface(SurfaceHolder.Callback2)} and do not have a view hierarchy available.
     * Apps that do have a view hierarchy should use
     * {@link View#setSystemGestureExclusionRects(List)} instead. This method does not modify or
     * replace the gesture exclusion rects populated by individual views in this window's view
     * hierarchy using {@link View#setSystemGestureExclusionRects(List)}.</p>
     *
     * <p>Use this to tell the system which specific sub-areas of a view need to receive gesture
     * input in order to function correctly in the presence of global system gestures that may
     * conflict. For example, if the system wishes to capture swipe-in-from-screen-edge gestures
     * to provide system-level navigation functionality, a view such as a navigation drawer
     * container can mark the left (or starting) edge of itself as requiring gesture capture
     * priority using this API. The system may then choose to relax its own gesture recognition
     * to allow the app to consume the user's gesture. It is not necessary for an app to register
     * exclusion rects for broadly spanning regions such as the entirety of a
     * <code>ScrollView</code> or for simple press and release click targets such as
     * <code>Button</code>. Mark an exclusion rect when interacting with a view requires
     * a precision touch gesture in a small area in either the X or Y dimension, such as
     * an edge swipe or dragging a <code>SeekBar</code> thumb.</p>
     *
     * <p>Do not modify the provided list after this method is called.</p>
     *
     * @param rects A list of precision gesture regions that this window needs to function correctly
     */
    @SuppressWarnings("unused")
    public void setSystemGestureExclusionRects(@NonNull List<Rect> rects) {
        throw new UnsupportedOperationException("window does not support gesture exclusion rects");
    }

    /**
     * Retrieve the list of areas within this window's coordinate space where the system should not
     * intercept touch or other pointing device gestures. This is the list as set by
     * {@link #setSystemGestureExclusionRects(List)} or an empty list if
     * {@link #setSystemGestureExclusionRects(List)} has not been called. It does not include
     * exclusion rects set by this window's view hierarchy.
     *
     * @return a list of system gesture exclusion rects specific to this window
     */
    @NonNull
    public List<Rect> getSystemGestureExclusionRects() {
        return Collections.emptyList();
    }

    /**
     * System request to begin scroll capture.
     *
     * @param listener to receive the response
     * @hide
     */
    public void requestScrollCapture(IScrollCaptureResponseListener listener) {
    }

    /**
     * Used to provide scroll capture support for an arbitrary window. This registeres the given
     * callback with the root view of the window.
     *
     * @param callback the callback to add
     */
    public void registerScrollCaptureCallback(@NonNull ScrollCaptureCallback callback) {
    }

    /**
     * Unregisters a {@link ScrollCaptureCallback} previously registered with this window.
     *
     * @param callback the callback to remove
     */
    public void unregisterScrollCaptureCallback(@NonNull ScrollCaptureCallback callback) {
    }

    /** @hide */
    public void setTheme(int resId) {
    }

    /**
     * Whether the caption should be displayed directly on the content rather than push the content
     * down. This affects only freeform windows since they display the caption.
     * @hide
     */
    public void setOverlayWithDecorCaptionEnabled(boolean enabled) {
        mOverlayWithDecorCaptionEnabled = enabled;
    }

    /** @hide */
    public boolean isOverlayWithDecorCaptionEnabled() {
        return mOverlayWithDecorCaptionEnabled;
    }

    /** @hide */
    public void notifyRestrictedCaptionAreaCallback(int left, int top, int right, int bottom) {
        if (mOnRestrictedCaptionAreaChangedListener != null) {
            mRestrictedCaptionAreaRect.set(left, top, right, bottom);
            mOnRestrictedCaptionAreaChangedListener.onRestrictedCaptionAreaChanged(
                    mRestrictedCaptionAreaRect);
        }
    }

    /**
     * Set what color should the caption controls be. By default the system will try to determine
     * the color from the theme. You can overwrite this by using {@link #DECOR_CAPTION_SHADE_DARK},
     * {@link #DECOR_CAPTION_SHADE_LIGHT}, or {@link #DECOR_CAPTION_SHADE_AUTO}.
     * @see #DECOR_CAPTION_SHADE_DARK
     * @see #DECOR_CAPTION_SHADE_LIGHT
     * @see #DECOR_CAPTION_SHADE_AUTO
     */
    public abstract void setDecorCaptionShade(int decorCaptionShade);

    /**
     * Set the drawable that is drawn underneath the caption during the resizing.
     *
     * During the resizing the caption might not be drawn fast enough to match the new dimensions.
     * There is a second caption drawn underneath it that will be fast enough. By default the
     * caption is constructed from the theme. You can provide a drawable, that will be drawn instead
     * to better match your application.
     */
    public abstract void setResizingCaptionDrawable(Drawable drawable);

    /**
     * Called when the activity changes from fullscreen mode to multi-window mode and visa-versa.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void onMultiWindowModeChanged();

    /**
     * Called when the activity changes to/from picture-in-picture mode.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void onPictureInPictureModeChanged(boolean isInPictureInPictureMode);

    /**
     * Called when the activity just relaunched.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract void reportActivityRelaunched();

    /**
     * @return The {@link WindowInsetsController} associated with this window
     * @see View#getWindowInsetsController()
     */
    public @Nullable WindowInsetsController getInsetsController() {
        return null;
    }

    /**
     * This will be null before a content view is added, e.g. via
     * {@link #setContentView} or {@link #addContentView}.
     *
     * @return The {@link android.view.ViewRoot} interface for this Window
     */
    public @Nullable ViewRoot getViewRoot() {
        return null;
    }
}
