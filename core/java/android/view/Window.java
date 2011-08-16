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

import android.app.Application;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;

/**
 * Abstract base class for a top-level window look and behavior policy.  An
 * instance of this class should be used as the top-level view added to the
 * window manager. It provides standard UI policies such as a background, title
 * area, default key processing, etc.
 *
 * <p>The only existing implementation of this abstract class is
 * android.policy.PhoneWindow, which you should instantiate when needing a
 * Window.  Eventually that class will be refactored and a factory method
 * added for creating Window instances without knowing about a particular
 * implementation.
 */
public abstract class Window {
    /** Flag for the "options panel" feature.  This is enabled by default. */
    public static final int FEATURE_OPTIONS_PANEL = 0;
    /** Flag for the "no title" feature, turning off the title at the top
     *  of the screen. */
    public static final int FEATURE_NO_TITLE = 1;
    /** Flag for the progress indicator feature */
    public static final int FEATURE_PROGRESS = 2;
    /** Flag for having an icon on the left side of the title bar */
    public static final int FEATURE_LEFT_ICON = 3;
    /** Flag for having an icon on the right side of the title bar */
    public static final int FEATURE_RIGHT_ICON = 4;
    /** Flag for indeterminate progress */
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
     */
    public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
    /**
     * Flag for specifying the behavior of action modes when an Action Bar is not present.
     * If overlay is enabled, the action mode UI will be allowed to cover existing window content.
     */
    public static final int FEATURE_ACTION_MODE_OVERLAY = 10;
    /** Flag for setting the progress bar's visibility to VISIBLE */
    public static final int PROGRESS_VISIBILITY_ON = -1;
    /** Flag for setting the progress bar's visibility to GONE */
    public static final int PROGRESS_VISIBILITY_OFF = -2;
    /** Flag for setting the progress bar's indeterminate mode on */
    public static final int PROGRESS_INDETERMINATE_ON = -3;
    /** Flag for setting the progress bar's indeterminate mode off */
    public static final int PROGRESS_INDETERMINATE_OFF = -4;
    /** Starting value for the (primary) progress */
    public static final int PROGRESS_START = 0;
    /** Ending value for the (primary) progress */
    public static final int PROGRESS_END = 10000;
    /** Lowest possible value for the secondary progress */
    public static final int PROGRESS_SECONDARY_START = 20000;
    /** Highest possible value for the secondary progress */
    public static final int PROGRESS_SECONDARY_END = 30000;
    
    /** The default features enabled */
    @SuppressWarnings({"PointlessBitwiseExpression"})
    protected static final int DEFAULT_FEATURES = (1 << FEATURE_OPTIONS_PANEL) |
            (1 << FEATURE_CONTEXT_MENU);

    /**
     * The ID that the main layout in the XML layout file should have.
     */
    public static final int ID_ANDROID_CONTENT = com.android.internal.R.id.content;

    private final Context mContext;
    
    private TypedArray mWindowStyle;
    private Callback mCallback;
    private WindowManager mWindowManager;
    private IBinder mAppToken;
    private String mAppName;
    private Window mContainer;
    private Window mActiveChild;
    private boolean mIsActive = false;
    private boolean mHasChildren = false;
    private boolean mCloseOnTouchOutside = false;
    private boolean mSetCloseOnTouchOutside = false;
    private int mForcedWindowFlags = 0;

    private int mFeatures = DEFAULT_FEATURES;
    private int mLocalFeatures = DEFAULT_FEATURES;

    private boolean mHaveWindowFormat = false;
    private int mDefaultWindowFormat = PixelFormat.OPAQUE;

    private boolean mHasSoftInputMode = false;
    
    private boolean mDestroyed;

    // The current window attributes.
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
        public boolean onCreatePanelMenu(int featureId, Menu menu);

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
        public boolean onPreparePanel(int featureId, View view, Menu menu);

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
        public boolean onMenuOpened(int featureId, Menu menu);
        
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
        public boolean onMenuItemSelected(int featureId, MenuItem item);

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
         * View.onWindowFocusChanged(boolean)} for more information.
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
         * Called when the window has been attached to the window manager.
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
        public void onPanelClosed(int featureId, Menu menu);
        
        /**
         * Called when the user signals the desire to start a search.
         * 
         * @return true if search launched, false if activity refuses (blocks)
         * 
         * @see android.app.Activity#onSearchRequested() 
         */
        public boolean onSearchRequested();

        /**
         * Called when an action mode is being started for this window. Gives the
         * callback an opportunity to handle the action mode in its own unique and
         * beautiful way. If this method returns null the system can choose a way
         * to present the mode or choose not to start the mode at all.
         *
         * @param callback Callback to control the lifecycle of this action mode
         * @return The ActionMode that was started, or null if the system should present it
         */
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback);

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
    }

    public Window(Context context) {
        mContext = context;
    }

    /**
     * Return the Context this window policy is running in, for retrieving
     * resources and other information.
     *
     * @return Context The Context that was supplied to the constructor.
     */
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
    public final boolean isDestroyed() {
        return mDestroyed;
    }

    /**
     * Set the window manager for use by this Window to, for example,
     * display panels.  This is <em>not</em> used for displaying the
     * Window itself -- that must be done by the client.
     *
     * @param wm The ViewManager for adding new windows.
     */
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName) {
        setWindowManager(wm, appToken, appName, false);
    }

    /**
     * Set the window manager for use by this Window to, for example,
     * display panels.  This is <em>not</em> used for displaying the
     * Window itself -- that must be done by the client.
     *
     * @param wm The ViewManager for adding new windows.
     */
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName,
            boolean hardwareAccelerated) {
        mAppToken = appToken;
        mAppName = appName;
        if (wm == null) {
            wm = WindowManagerImpl.getDefault();
        }
        mWindowManager = new LocalWindowManager(wm, hardwareAccelerated);
    }

    static CompatibilityInfoHolder getCompatInfo(Context context) {
        Application app = (Application)context.getApplicationContext();
        return app != null ? app.mLoadedApk.mCompatibilityInfo : new CompatibilityInfoHolder();
    }

    private class LocalWindowManager extends WindowManagerImpl.CompatModeWrapper {
        private final boolean mHardwareAccelerated;

        LocalWindowManager(WindowManager wm, boolean hardwareAccelerated) {
            super(wm, getCompatInfo(mContext));
            mHardwareAccelerated = hardwareAccelerated;
        }

        public boolean isHardwareAccelerated() {
            return mHardwareAccelerated;
        }
        
        public final void addView(View view, ViewGroup.LayoutParams params) {
            // Let this throw an exception on a bad params.
            WindowManager.LayoutParams wp = (WindowManager.LayoutParams)params;
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
                    String title;
                    if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA) {
                        title="Media";
                    } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY) {
                        title="MediaOvr";
                    } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
                        title="Panel";
                    } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) {
                        title="SubPanel";
                    } else if (wp.type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) {
                        title="AtchDlg";
                    } else {
                        title=Integer.toString(wp.type);
                    }
                    if (mAppName != null) {
                        title += ":" + mAppName;
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
            if (mHardwareAccelerated) {
                wp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
            super.addView(view, params);
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
    }

    /**
     * Specify custom animations to use for the window, as per
     * {@link WindowManager.LayoutParams#windowAnimations
     * WindowManager.LayoutParams.windowAnimations}.  Providing anything besides
     * 0 here will override the animations the window would
     * normally retrieve from its theme.
     */
    public void setWindowAnimations(int resId) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.windowAnimations = resId;
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
    }
    
    /**
     * Convenience function to set the flag bits as specified in flags, as
     * per {@link #setFlags}.
     * @param flags The flag bits to be set.
     * @see #setFlags
     */
    public void addFlags(int flags) {
        setFlags(flags, flags);
    }
    
    /**
     * Convenience function to clear the flag bits as specified in flags, as
     * per {@link #setFlags}.
     * @param flags The flag bits to be cleared.
     * @see #setFlags
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
     */
    public void setFlags(int flags, int mask) {
        final WindowManager.LayoutParams attrs = getAttributes();
        attrs.flags = (attrs.flags&~mask) | (flags&mask);
        mForcedWindowFlags |= mask;
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(attrs);
        }
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
        if (mCallback != null) {
            mCallback.onWindowAttributesChanged(mWindowAttributes);
        }
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
    public void setCloseOnTouchOutside(boolean close) {
        mCloseOnTouchOutside = close;
        mSetCloseOnTouchOutside = true;
    }
    
    /** @hide */
    public void setCloseOnTouchOutsideIfNotSet(boolean close) {
        if (!mSetCloseOnTouchOutside) {
            mCloseOnTouchOutside = close;
            mSetCloseOnTouchOutside = true;
        }
    }
    
    /** @hide */
    public abstract void alwaysReadCloseOnTouchAttr();
    
    /** @hide */
    public boolean shouldCloseOnTouch(Context context, MotionEvent event) {
        if (mCloseOnTouchOutside && event.getAction() == MotionEvent.ACTION_DOWN
                && isOutOfBounds(context, event) && peekDecorView() != null) {
            return true;
        }
        return false;
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
     * Finds a view that was identified by the id attribute from the XML that
     * was processed in {@link android.app.Activity#onCreate}.  This will
     * implicitly call {@link #getDecorView} for you, with all of the
     * associated side-effects.
     *
     * @return The view if found or null otherwise.
     */
    public View findViewById(int id) {
        return getDecorView().findViewById(id);
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
    public abstract void setContentView(int layoutResID);

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
     * and certain window flags as described in {@link #setFlags(int, int)}.
     * 
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
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
     * Return the view in this Window that currently has focus, or null if
     * there are none.  Note that this does not look in any containing
     * Window.
     *
     * @return View The current View with focus or null.
     */
    public abstract View getCurrentFocus();

    /**
     * Quick access to the {@link LayoutInflater} instance that this Window
     * retrieved from its Context.
     *
     * @return LayoutInflater The shared LayoutInflater.
     */
    public abstract LayoutInflater getLayoutInflater();

    public abstract void setTitle(CharSequence title);

    public abstract void setTitleColor(int textColor);

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
     * Change the background of this window to a Drawable resource. Setting the
     * background to null will make the window be opaque. To make the window
     * transparent, you can use an empty drawable (for instance a ColorDrawable
     * with the color 0 or the system drawable android:drawable/empty.)
     * 
     * @param resid The resource identifier of a drawable resource which will be
     *              installed as the new background.
     */
    public void setBackgroundDrawableResource(int resid)
    {
        setBackgroundDrawable(mContext.getResources().getDrawable(resid));
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
     * Set the value for a drawable feature of this window, from a resource
     * identifier.  You must have called requestFeauture(featureId) before
     * calling this function.
     *
     * @see android.content.res.Resources#getDrawable(int)
     *
     * @param featureId The desired drawable feature to change, defined as a
     * constant by Window.
     * @param resId Resource identifier of the desired image.
     */
    public abstract void setFeatureDrawableResource(int featureId, int resId);

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
     * @param featureId The desired drawable feature to change.
     * Features are constants defined by Window.
     * @param drawable A Drawable object to display.
     */
    public abstract void setFeatureDrawable(int featureId, Drawable drawable);

    /**
     * Set a custom alpha value for the given drawale feature, controlling how
     * much the background is visible through it.
     *
     * @param featureId The desired drawable feature to change.
     * Features are constants defined by Window.
     * @param alpha The alpha amount, 0 is completely transparent and 255 is
     *              completely opaque.
     */
    public abstract void setFeatureDrawableAlpha(int featureId, int alpha);

    /**
     * Set the integer value for a feature.  The range of the value depends on
     * the feature being set.  For FEATURE_PROGRESSS, it should go from 0 to
     * 10000. At 10000 the progress is complete and the indicator hidden.
     *
     * @param featureId The desired feature to change.
     * Features are constants defined by Window.
     * @param value The value for the feature.  The interpretation of this
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
    public abstract View getDecorView();

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
            if (mCallback != null) {
                mCallback.onWindowAttributesChanged(attrs);
            }
        }
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
}
