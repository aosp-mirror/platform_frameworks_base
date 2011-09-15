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

import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.view.animation.Animation;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This interface supplies all UI-specific behavior of the window manager.  An
 * instance of it is created by the window manager when it starts up, and allows
 * customization of window layering, special window types, key dispatching, and
 * layout.
 * 
 * <p>Because this provides deep interaction with the system window manager,
 * specific methods on this interface can be called from a variety of contexts
 * with various restrictions on what they can do.  These are encoded through
 * a suffixes at the end of a method encoding the thread the method is called
 * from and any locks that are held when it is being called; if no suffix
 * is attached to a method, then it is not called with any locks and may be
 * called from the main window manager thread or another thread calling into
 * the window manager.
 * 
 * <p>The current suffixes are:
 * 
 * <dl>
 * <dt> Ti <dd> Called from the input thread.  This is the thread that
 * collects pending input events and dispatches them to the appropriate window.
 * It may block waiting for events to be processed, so that the input stream is
 * properly serialized.
 * <dt> Tq <dd> Called from the low-level input queue thread.  This is the
 * thread that reads events out of the raw input devices and places them
 * into the global input queue that is read by the <var>Ti</var> thread.
 * This thread should not block for a long period of time on anything but the
 * key driver.
 * <dt> Lw <dd> Called with the main window manager lock held.  Because the
 * window manager is a very low-level system service, there are few other
 * system services you can call with this lock held.  It is explicitly okay to
 * make calls into the package manager and power manager; it is explicitly not
 * okay to make calls into the activity manager or most other services.  Note that
 * {@link android.content.Context#checkPermission(String, int, int)} and
 * variations require calling into the activity manager.
 * <dt> Li <dd> Called with the input thread lock held.  This lock can be
 * acquired by the window manager while it holds the window lock, so this is
 * even more restrictive than <var>Lw</var>.
 * </dl>
 * 
 * @hide
 */
public interface WindowManagerPolicy {
    // Policy flags.  These flags are also defined in frameworks/base/include/ui/Input.h.
    public final static int FLAG_WAKE = 0x00000001;
    public final static int FLAG_WAKE_DROPPED = 0x00000002;
    public final static int FLAG_SHIFT = 0x00000004;
    public final static int FLAG_CAPS_LOCK = 0x00000008;
    public final static int FLAG_ALT = 0x00000010;
    public final static int FLAG_ALT_GR = 0x00000020;
    public final static int FLAG_MENU = 0x00000040;
    public final static int FLAG_LAUNCHER = 0x00000080;
    public final static int FLAG_VIRTUAL = 0x00000100;

    public final static int FLAG_INJECTED = 0x01000000;
    public final static int FLAG_TRUSTED = 0x02000000;
    public final static int FLAG_FILTERED = 0x04000000;
    public final static int FLAG_DISABLE_KEY_REPEAT = 0x08000000;

    public final static int FLAG_WOKE_HERE = 0x10000000;
    public final static int FLAG_BRIGHT_HERE = 0x20000000;
    public final static int FLAG_PASS_TO_USER = 0x40000000;

    public final static boolean WATCH_POINTER = false;

    /**
     * Sticky broadcast of the current HDMI plugged state.
     */
    public final static String ACTION_HDMI_PLUGGED = "android.intent.action.HDMI_PLUGGED";

    /**
     * Extra in {@link #ACTION_HDMI_PLUGGED} indicating the state: true if
     * plugged in to HDMI, false if not.
     */
    public final static String EXTRA_HDMI_PLUGGED_STATE = "state";

    // flags for interceptKeyTq
    /**
     * Pass this event to the user / app.  To be returned from {@link #interceptKeyTq}.
     */
    public final static int ACTION_PASS_TO_USER = 0x00000001;

    /**
     * This key event should extend the user activity timeout and turn the lights on.
     * To be returned from {@link #interceptKeyTq}. Do not return this and
     * {@link #ACTION_GO_TO_SLEEP} or {@link #ACTION_PASS_TO_USER}.
     */
    public final static int ACTION_POKE_USER_ACTIVITY = 0x00000002;

    /**
     * This key event should put the device to sleep (and engage keyguard if necessary)
     * To be returned from {@link #interceptKeyTq}.  Do not return this and
     * {@link #ACTION_POKE_USER_ACTIVITY} or {@link #ACTION_PASS_TO_USER}.
     */
    public final static int ACTION_GO_TO_SLEEP = 0x00000004;

    /**
     * Interface to the Window Manager state associated with a particular
     * window.  You can hold on to an instance of this interface from the call
     * to prepareAddWindow() until removeWindow().
     */
    public interface WindowState {
        /**
         * Perform standard frame computation.  The result can be obtained with
         * getFrame() if so desired.  Must be called with the window manager
         * lock held.
         * 
         * @param parentFrame The frame of the parent container this window
         * is in, used for computing its basic position.
         * @param displayFrame The frame of the overall display in which this
         * window can appear, used for constraining the overall dimensions
         * of the window.
         * @param contentFrame The frame within the display in which we would
         * like active content to appear.  This will cause windows behind to
         * be resized to match the given content frame.
         * @param visibleFrame The frame within the display that the window
         * is actually visible, used for computing its visible insets to be
         * given to windows behind.
         * This can be used as a hint for scrolling (avoiding resizing)
         * the window to make certain that parts of its content
         * are visible.
         */
        public void computeFrameLw(Rect parentFrame, Rect displayFrame,
                Rect contentFrame, Rect visibleFrame);

        /**
         * Retrieve the current frame of the window that has been assigned by
         * the window manager.  Must be called with the window manager lock held.
         * 
         * @return Rect The rectangle holding the window frame.
         */
        public Rect getFrameLw();

        /**
         * Retrieve the current frame of the window that is actually shown.
         * Must be called with the window manager lock held.
         * 
         * @return Rect The rectangle holding the shown window frame.
         */
        public RectF getShownFrameLw();

        /**
         * Retrieve the frame of the display that this window was last
         * laid out in.  Must be called with the
         * window manager lock held.
         * 
         * @return Rect The rectangle holding the display frame.
         */
        public Rect getDisplayFrameLw();

        /**
         * Retrieve the frame of the content area that this window was last
         * laid out in.  This is the area in which the content of the window
         * should be placed.  It will be smaller than the display frame to
         * account for screen decorations such as a status bar or soft
         * keyboard.  Must be called with the
         * window manager lock held.
         * 
         * @return Rect The rectangle holding the content frame.
         */
        public Rect getContentFrameLw();

        /**
         * Retrieve the frame of the visible area that this window was last
         * laid out in.  This is the area of the screen in which the window
         * will actually be fully visible.  It will be smaller than the
         * content frame to account for transient UI elements blocking it
         * such as an input method's candidates UI.  Must be called with the
         * window manager lock held.
         * 
         * @return Rect The rectangle holding the visible frame.
         */
        public Rect getVisibleFrameLw();

        /**
         * Returns true if this window is waiting to receive its given
         * internal insets from the client app, and so should not impact the
         * layout of other windows.
         */
        public boolean getGivenInsetsPendingLw();
        
        /**
         * Retrieve the insets given by this window's client for the content
         * area of windows behind it.  Must be called with the
         * window manager lock held.
         * 
         * @return Rect The left, top, right, and bottom insets, relative
         * to the window's frame, of the actual contents.
         */
        public Rect getGivenContentInsetsLw();

        /**
         * Retrieve the insets given by this window's client for the visible
         * area of windows behind it.  Must be called with the
         * window manager lock held.
         * 
         * @return Rect The left, top, right, and bottom insets, relative
         * to the window's frame, of the actual visible area.
         */
        public Rect getGivenVisibleInsetsLw();

        /**
         * Retrieve the current LayoutParams of the window.
         * 
         * @return WindowManager.LayoutParams The window's internal LayoutParams
         *         instance.
         */
        public WindowManager.LayoutParams getAttrs();

        /**
         * Get the layer at which this window's surface will be Z-ordered.
         */
        public int getSurfaceLayer();
        
        /**
         * Return the token for the application (actually activity) that owns 
         * this window.  May return null for system windows. 
         * 
         * @return An IApplicationToken identifying the owning activity.
         */
        public IApplicationToken getAppToken();

        /**
         * Return true if, at any point, the application token associated with 
         * this window has actually displayed any windows.  This is most useful 
         * with the "starting up" window to determine if any windows were 
         * displayed when it is closed. 
         * 
         * @return Returns true if one or more windows have been displayed, 
         *         else false.
         */
        public boolean hasAppShownWindows();

        /**
         * Is this window visible?  It is not visible if there is no
         * surface, or we are in the process of running an exit animation
         * that will remove the surface.
         */
        boolean isVisibleLw();
        
        /**
         * Like {@link #isVisibleLw}, but also counts a window that is currently
         * "hidden" behind the keyguard as visible.  This allows us to apply
         * things like window flags that impact the keyguard.
         */
        boolean isVisibleOrBehindKeyguardLw();
        
        /**
         * Is this window currently visible to the user on-screen?  It is 
         * displayed either if it is visible or it is currently running an 
         * animation before no longer being visible.  Must be called with the
         * window manager lock held.
         */
        boolean isDisplayedLw();

        /**
         * Returns true if this window has been shown on screen at some time in 
         * the past.  Must be called with the window manager lock held.
         * 
         * @return boolean
         */
        public boolean hasDrawnLw();

        /**
         * Can be called by the policy to force a window to be hidden,
         * regardless of whether the client or window manager would like
         * it shown.  Must be called with the window manager lock held.
         * Returns true if {@link #showLw} was last called for the window.
         */
        public boolean hideLw(boolean doAnimation);
        
        /**
         * Can be called to undo the effect of {@link #hideLw}, allowing a
         * window to be shown as long as the window manager and client would
         * also like it to be shown.  Must be called with the window manager
         * lock held.
         * Returns true if {@link #hideLw} was last called for the window.
         */
        public boolean showLw(boolean doAnimation);
    }

    /**
     * Bit mask that is set for all enter transition.
     */
    public final int TRANSIT_ENTER_MASK = 0x1000;
    
    /**
     * Bit mask that is set for all exit transitions.
     */
    public final int TRANSIT_EXIT_MASK = 0x2000;
    
    /** Not set up for a transition. */
    public final int TRANSIT_UNSET = -1;
    /** No animation for transition. */
    public final int TRANSIT_NONE = 0;
    /** Window has been added to the screen. */
    public final int TRANSIT_ENTER = 1 | TRANSIT_ENTER_MASK;
    /** Window has been removed from the screen. */
    public final int TRANSIT_EXIT = 2 | TRANSIT_EXIT_MASK;
    /** Window has been made visible. */
    public final int TRANSIT_SHOW = 3 | TRANSIT_ENTER_MASK;
    /** Window has been made invisible. */
    public final int TRANSIT_HIDE = 4 | TRANSIT_EXIT_MASK;
    /** The "application starting" preview window is no longer needed, and will
     * animate away to show the real window. */
    public final int TRANSIT_PREVIEW_DONE = 5;
    /** A window in a new activity is being opened on top of an existing one
     * in the same task. */
    public final int TRANSIT_ACTIVITY_OPEN = 6 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the
     * previous activity in the same task. */
    public final int TRANSIT_ACTIVITY_CLOSE = 7 | TRANSIT_EXIT_MASK;
    /** A window in a new task is being opened on top of an existing one
     * in another activity's task. */
    public final int TRANSIT_TASK_OPEN = 8 | TRANSIT_ENTER_MASK;
    /** A window in the top-most activity is being closed to reveal the
     * previous activity in a different task. */
    public final int TRANSIT_TASK_CLOSE = 9 | TRANSIT_EXIT_MASK;
    /** A window in an existing task is being displayed on top of an existing one
     * in another activity's task. */
    public final int TRANSIT_TASK_TO_FRONT = 10 | TRANSIT_ENTER_MASK;
    /** A window in an existing task is being put below all other tasks. */
    public final int TRANSIT_TASK_TO_BACK = 11 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that doesn't have a wallpaper is being
     * opened on top of one that does, effectively closing the wallpaper. */
    public final int TRANSIT_WALLPAPER_CLOSE = 12 | TRANSIT_EXIT_MASK;
    /** A window in a new activity that does have a wallpaper is being
     * opened on one that didn't, effectively opening the wallpaper. */
    public final int TRANSIT_WALLPAPER_OPEN = 13 | TRANSIT_ENTER_MASK;
    /** A window in a new activity is being opened on top of an existing one,
     * and both are on top of the wallpaper. */
    public final int TRANSIT_WALLPAPER_INTRA_OPEN = 14 | TRANSIT_ENTER_MASK;
    /** The window in the top-most activity is being closed to reveal the
     * previous activity, and both are on top of he wallpaper. */
    public final int TRANSIT_WALLPAPER_INTRA_CLOSE = 15 | TRANSIT_EXIT_MASK;
    
    // NOTE: screen off reasons are in order of significance, with more
    // important ones lower than less important ones.
    
    /** Screen turned off because of a device admin */
    public final int OFF_BECAUSE_OF_ADMIN = 1;
    /** Screen turned off because of power button */
    public final int OFF_BECAUSE_OF_USER = 2;
    /** Screen turned off because of timeout */
    public final int OFF_BECAUSE_OF_TIMEOUT = 3;
    /** Screen turned off because of proximity sensor */
    public final int OFF_BECAUSE_OF_PROX_SENSOR = 4;

    /**
     * Magic constant to {@link IWindowManager#setRotation} to not actually
     * modify the rotation.
     */
    public final int USE_LAST_ROTATION = -1000;

    /** When not otherwise specified by the activity's screenOrientation, rotation should be
     * determined by the system (that is, using sensors). */
    public final int USER_ROTATION_FREE = 0;
    /** When not otherwise specified by the activity's screenOrientation, rotation is set by
     * the user. */
    public final int USER_ROTATION_LOCKED = 1;
    
    /**
     * Perform initialization of the policy.
     * 
     * @param context The system context we are running in.
     * @param powerManager 
     */
    public void init(Context context, IWindowManager windowManager,
            LocalPowerManager powerManager);

    /**
     * Called by window manager once it has the initial, default native
     * display dimensions.
     */
    public void setInitialDisplaySize(int width, int height);

    /**
     * Check permissions when adding a window.
     * 
     * @param attrs The window's LayoutParams. 
     *  
     * @return {@link WindowManagerImpl#ADD_OKAY} if the add can proceed;
     *      else an error code, usually
     *      {@link WindowManagerImpl#ADD_PERMISSION_DENIED}, to abort the add.
     */
    public int checkAddPermission(WindowManager.LayoutParams attrs);

    /**
     * Sanitize the layout parameters coming from a client.  Allows the policy
     * to do things like ensure that windows of a specific type can't take
     * input focus.
     * 
     * @param attrs The window layout parameters to be modified.  These values
     * are modified in-place.
     */
    public void adjustWindowParamsLw(WindowManager.LayoutParams attrs);
    
    /**
     * After the window manager has computed the current configuration based
     * on its knowledge of the display and input devices, it gives the policy
     * a chance to adjust the information contained in it.  If you want to
     * leave it as-is, simply do nothing.
     * 
     * <p>This method may be called by any thread in the window manager, but
     * no internal locks in the window manager will be held.
     * 
     * @param config The Configuration being computed, for you to change as
     * desired.
     */
    public void adjustConfigurationLw(Configuration config);
    
    /**
     * Assign a window type to a layer.  Allows you to control how different
     * kinds of windows are ordered on-screen.
     * 
     * @param type The type of window being assigned.
     * 
     * @return int An arbitrary integer used to order windows, with lower
     *         numbers below higher ones.
     */
    public int windowTypeToLayerLw(int type);

    /**
     * Return how to Z-order sub-windows in relation to the window they are
     * attached to.  Return positive to have them ordered in front, negative for
     * behind.
     * 
     * @param type The sub-window type code.
     * 
     * @return int Layer in relation to the attached window, where positive is
     *         above and negative is below.
     */
    public int subWindowTypeToLayerLw(int type);

    /**
     * Get the highest layer (actually one more than) that the wallpaper is
     * allowed to be in.
     */
    public int getMaxWallpaperLayer();
    
    /**
     * Return true if the policy allows the status bar to hide.  Otherwise,
     * it is a tablet-style system bar.
     */
    public boolean canStatusBarHide();

    /**
     * Return the display width available after excluding any screen
     * decorations that can never be removed.  That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation);

    /**
     * Return the display height available after excluding any screen
     * decorations that can never be removed.  That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation);

    /**
     * Return the available screen width that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayWidth(int, int)}; it may be smaller than
     * that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation);

    /**
     * Return the available screen height that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayHeight(int, int)}; it may be smaller than
     * that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation);

    /**
     * Return whether the given window should forcibly hide everything
     * behind it.  Typically returns true for the keyguard.
     */
    public boolean doesForceHide(WindowState win, WindowManager.LayoutParams attrs);
    
    /**
     * Determine if a window that is behind one that is force hiding
     * (as determined by {@link #doesForceHide}) should actually be hidden.
     * For example, typically returns false for the status bar.  Be careful
     * to return false for any window that you may hide yourself, since this
     * will conflict with what you set.
     */
    public boolean canBeForceHidden(WindowState win, WindowManager.LayoutParams attrs);
    
    /**
     * Called when the system would like to show a UI to indicate that an
     * application is starting.  You can use this to add a
     * APPLICATION_STARTING_TYPE window with the given appToken to the window
     * manager (using the normal window manager APIs) that will be shown until
     * the application displays its own window.  This is called without the
     * window manager locked so that you can call back into it.
     * 
     * @param appToken Token of the application being started.
     * @param packageName The name of the application package being started. 
     * @param theme Resource defining the application's overall visual theme.
     * @param nonLocalizedLabel The default title label of the application if
     *        no data is found in the resource.
     * @param labelRes The resource ID the application would like to use as its name.
     * @param icon The resource ID the application would like to use as its icon.
     * @param windowFlags Window layout flags.
     * 
     * @return Optionally you can return the View that was used to create the
     *         window, for easy removal in removeStartingWindow.
     * 
     * @see #removeStartingWindow
     */
    public View addStartingWindow(IBinder appToken, String packageName,
            int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel,
            int labelRes, int icon, int windowFlags);

    /**
     * Called when the first window of an application has been displayed, while
     * {@link #addStartingWindow} has created a temporary initial window for
     * that application.  You should at this point remove the window from the
     * window manager.  This is called without the window manager locked so
     * that you can call back into it.
     * 
     * <p>Note: due to the nature of these functions not being called with the
     * window manager locked, you must be prepared for this function to be
     * called multiple times and/or an initial time with a null View window
     * even if you previously returned one.
     * 
     * @param appToken Token of the application that has started.
     * @param window Window View that was returned by createStartingWindow.
     * 
     * @see #addStartingWindow
     */
    public void removeStartingWindow(IBinder appToken, View window);

    /**
     * Prepare for a window being added to the window manager.  You can throw an
     * exception here to prevent the window being added, or do whatever setup
     * you need to keep track of the window.
     * 
     * @param win The window being added.
     * @param attrs The window's LayoutParams. 
     *  
     * @return {@link WindowManagerImpl#ADD_OKAY} if the add can proceed, else an 
     *         error code to abort the add.
     */
    public int prepareAddWindowLw(WindowState win,
            WindowManager.LayoutParams attrs);

    /**
     * Called when a window is being removed from a window manager.  Must not
     * throw an exception -- clean up as much as possible.
     * 
     * @param win The window being removed.
     */
    public void removeWindowLw(WindowState win);

    /**
     * Control the animation to run when a window's state changes.  Return a
     * non-0 number to force the animation to a specific resource ID, or 0
     * to use the default animation.
     * 
     * @param win The window that is changing.
     * @param transit What is happening to the window: {@link #TRANSIT_ENTER},
     *                {@link #TRANSIT_EXIT}, {@link #TRANSIT_SHOW}, or
     *                {@link #TRANSIT_HIDE}.
     * 
     * @return Resource ID of the actual animation to use, or 0 for none.
     */
    public int selectAnimationLw(WindowState win, int transit);

    /**
     * Create and return an animation to re-display a force hidden window.
     */
    public Animation createForceHideEnterAnimation();
    
    /**
     * Called from the input reader thread before a key is enqueued.
     *
     * <p>There are some actions that need to be handled here because they
     * affect the power state of the device, for example, the power keys.
     * Generally, it's best to keep as little as possible in the queue thread
     * because it's the most fragile.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     * @param isScreenOn True if the screen is already on
     *
     * @return The bitwise or of the {@link #ACTION_PASS_TO_USER},
     *          {@link #ACTION_POKE_USER_ACTIVITY} and {@link #ACTION_GO_TO_SLEEP} flags.
     */
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn);

    /**
     * Called from the input reader thread before a motion is enqueued when the screen is off.
     *
     * <p>There are some actions that need to be handled here because they
     * affect the power state of the device, for example, waking on motions.
     * Generally, it's best to keep as little as possible in the queue thread
     * because it's the most fragile.
     * @param policyFlags The policy flags associated with the motion.
     *
     * @return The bitwise or of the {@link #ACTION_PASS_TO_USER},
     *          {@link #ACTION_POKE_USER_ACTIVITY} and {@link #ACTION_GO_TO_SLEEP} flags.
     */
    public int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags);

    /**
     * Called from the input dispatcher thread before a key is dispatched to a window.
     *
     * <p>Allows you to define
     * behavior for keys that can not be overridden by applications.
     * This method is called from the input thread, with no locks held.
     * 
     * @param win The window that currently has focus.  This is where the key
     *            event will normally go.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     * @return Returns true if the policy consumed the event and it should
     * not be further dispatched.
     */
    public boolean interceptKeyBeforeDispatching(WindowState win, KeyEvent event, int policyFlags);

    /**
     * Called from the input dispatcher thread when an application did not handle
     * a key that was dispatched to it.
     *
     * <p>Allows you to define default global behavior for keys that were not handled
     * by applications.  This method is called from the input thread, with no locks held.
     * 
     * @param win The window that currently has focus.  This is where the key
     *            event will normally go.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     * @return Returns an alternate key event to redispatch as a fallback, or null to give up.
     * The caller is responsible for recycling the key event.
     */
    public KeyEvent dispatchUnhandledKey(WindowState win, KeyEvent event, int policyFlags);

    /**
     * Called when layout of the windows is about to start.
     * 
     * @param displayWidth The current full width of the screen.
     * @param displayHeight The current full height of the screen.
     * @param displayRotation The current rotation being applied to the base
     * window.
     */
    public void beginLayoutLw(int displayWidth, int displayHeight, int displayRotation);

    /**
     * Called for each window attached to the window manager as layout is
     * proceeding.  The implementation of this function must take care of
     * setting the window's frame, either here or in finishLayout().
     * 
     * @param win The window being positioned.
     * @param attrs The LayoutParams of the window.
     * @param attached For sub-windows, the window it is attached to; this
     *                 window will already have had layoutWindow() called on it
     *                 so you can use its Rect.  Otherwise null.
     */
    public void layoutWindowLw(WindowState win,
            WindowManager.LayoutParams attrs, WindowState attached);

    
    /**
     * Return the insets for the areas covered by system windows. These values
     * are computed on the most recent layout, so they are not guaranteed to
     * be correct.
     * 
     * @param attrs The LayoutParams of the window.
     * @param contentInset The areas covered by system windows, expressed as positive insets
     * 
     */
    public void getContentInsetHintLw(WindowManager.LayoutParams attrs, Rect contentInset);
    
    /**
     * Called when layout of the windows is finished.  After this function has
     * returned, all windows given to layoutWindow() <em>must</em> have had a
     * frame assigned.
     *  
     * @return Return any bit set of {@link #FINISH_LAYOUT_REDO_LAYOUT},
     * {@link #FINISH_LAYOUT_REDO_CONFIG}, {@link #FINISH_LAYOUT_REDO_WALLPAPER},
     * or {@link #FINISH_LAYOUT_REDO_ANIM}.
     */
    public int finishLayoutLw();

    /** Layout state may have changed (so another layout will be performed) */
    static final int FINISH_LAYOUT_REDO_LAYOUT = 0x0001;
    /** Configuration state may have changed */
    static final int FINISH_LAYOUT_REDO_CONFIG = 0x0002;
    /** Wallpaper may need to move */
    static final int FINISH_LAYOUT_REDO_WALLPAPER = 0x0004;
    /** Need to recompute animations */
    static final int FINISH_LAYOUT_REDO_ANIM = 0x0008;
    
    /**
     * Called when animation of the windows is about to start.
     * 
     * @param displayWidth The current full width of the screen.
     * @param displayHeight The current full height of the screen.
     */
    public void beginAnimationLw(int displayWidth, int displayHeight);

    /**
     * Called each time a window is animating.
     * 
     * @param win The window being positioned.
     * @param attrs The LayoutParams of the window. 
     */
    public void animatingWindowLw(WindowState win,
            WindowManager.LayoutParams attrs);

    /**
     * Called when animation of the windows is finished.  If in this function you do 
     * something that may have modified the animation state of another window, 
     * be sure to return true in order to perform another animation frame. 
     *  
     * @return Return any bit set of {@link #FINISH_LAYOUT_REDO_LAYOUT},
     * {@link #FINISH_LAYOUT_REDO_CONFIG}, {@link #FINISH_LAYOUT_REDO_WALLPAPER},
     * or {@link #FINISH_LAYOUT_REDO_ANIM}.
     */
    public int finishAnimationLw();

    /**
     * Return true if it is okay to perform animations for an app transition
     * that is about to occur.  You may return false for this if, for example,
     * the lock screen is currently displayed so the switch should happen
     * immediately.
     */
    public boolean allowAppAnimationsLw();


    /**
     * A new window has been focused.
     */
    public void focusChanged(WindowState lastFocus, WindowState newFocus);
    
    /**
     * Called after the screen turns off.
     *
     * @param why {@link #OFF_BECAUSE_OF_USER} or
     * {@link #OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void screenTurnedOff(int why);

    /**
     * Called after the screen turns on.
     */
    public void screenTurnedOn();

    /**
     * Return whether the screen is currently on.
     */
    public boolean isScreenOn();

    /**
     * Tell the policy that the lid switch has changed state.
     * @param whenNanos The time when the change occurred in uptime nanoseconds.
     * @param lidOpen True if the lid is now open.
     */
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);
    
    /**
     * Tell the policy if anyone is requesting that keyguard not come on.
     *
     * @param enabled Whether keyguard can be on or not.  does not actually
     * turn it on, unless it was previously disabled with this function.
     *
     * @see android.app.KeyguardManager.KeyguardLock#disableKeyguard()
     * @see android.app.KeyguardManager.KeyguardLock#reenableKeyguard()
     */
    public void enableKeyguard(boolean enabled);

    /**
     * Callback used by {@link WindowManagerPolicy#exitKeyguardSecurely}
     */
    interface OnKeyguardExitResult {
        void onKeyguardExitResult(boolean success);
    }

    /**
     * Tell the policy if anyone is requesting the keyguard to exit securely
     * (this would be called after the keyguard was disabled)
     * @param callback Callback to send the result back.
     * @see android.app.KeyguardManager#exitKeyguardSecurely(android.app.KeyguardManager.OnKeyguardExitResult)
     */
    void exitKeyguardSecurely(OnKeyguardExitResult callback);

    /**
     * isKeyguardLocked
     *
     * Return whether the keyguard is currently locked.
     *
     * @return true if in keyguard is locked.
     */
    public boolean isKeyguardLocked();

    /**
     * isKeyguardSecure
     *
     * Return whether the keyguard requires a password to unlock.
     *
     * @return true if in keyguard is secure.
     */
    public boolean isKeyguardSecure();

    /**
     * inKeyguardRestrictedKeyInputMode
     *
     * if keyguard screen is showing or in restricted key input mode (i.e. in
     * keyguard password emergency screen). When in such mode, certain keys,
     * such as the Home key and the right soft keys, don't work.
     *
     * @return true if in keyguard restricted input mode.
     */
    public boolean inKeyguardRestrictedKeyInputMode();

    /**
     * Given an orientation constant
     * ({@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE
     * ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE} or
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_PORTRAIT
     * ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}), return a surface
     * rotation.
     */
    public int rotationForOrientationLw(int orientation, int lastRotation,
            boolean displayEnabled);
    
    /**
     * Return the currently locked screen rotation, if any.  Return
     * Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, or
     * Surface.ROTATION_270 if locked; return -1 if not locked.
     */
    public int getLockedRotationLw();

    /**
     * Called when the system is mostly done booting to determine whether
     * the system should go into safe mode.
     */
    public boolean detectSafeMode();
    
    /**
     * Called when the system is mostly done booting.
     */
    public void systemReady();

    /**
     * Called when the system is done booting to the point where the
     * user can start interacting with it.
     */
    public void systemBooted();

    /**
     * Show boot time message to the user.
     */
    public void showBootMessage(final CharSequence msg, final boolean always);

    /**
     * Hide the UI for showing boot messages, never to be displayed again.
     */
    public void hideBootMessages();

    /**
     * Called when userActivity is signalled in the power manager.
     * This is safe to call from any thread, with any window manager locks held or not.
     */
    public void userActivity();

    /**
     * Called when we have finished booting and can now display the home
     * screen to the user.  This wilWl happen after systemReady(), and at
     * this point the display is active.
     */
    public void enableScreenAfterBoot();
    
    public void setCurrentOrientationLw(int newOrientation);
    
    /**
     * Call from application to perform haptic feedback on its window.
     */
    public boolean performHapticFeedbackLw(WindowState win, int effectId, boolean always);
    
    /**
     * Called when we have started keeping the screen on because a window
     * requesting this has become visible.
     */
    public void screenOnStartedLw();

    /**
     * Called when we have stopped keeping the screen on because the last window
     * requesting this is no longer visible.
     */
    public void screenOnStoppedLw();

    /**
     * Return false to disable key repeat events from being generated.
     */
    public boolean allowKeyRepeat();

    /**
     * Inform the policy that the user has chosen a preferred orientation ("rotation lock"). 
     *
     * @param mode One of {@link WindowManagerPolicy#USER_ROTATION_LOCKED} or
     *             {@link * WindowManagerPolicy#USER_ROTATION_FREE}. 
     * @param rotation One of {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
     *                 {@link Surface#ROTATION_180}, {@link Surface#ROTATION_270}. 
     */
    public void setUserRotationMode(int mode, int rotation);

    /**
     * Print the WindowManagerPolicy's state into the given stream.
     *
     * @param prefix Text to print at the front of each line.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args);
}
