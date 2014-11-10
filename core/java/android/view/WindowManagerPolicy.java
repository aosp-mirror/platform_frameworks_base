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

import android.annotation.IntDef;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.view.animation.Animation;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
    public final static int FLAG_VIRTUAL = 0x00000002;

    public final static int FLAG_INJECTED = 0x01000000;
    public final static int FLAG_TRUSTED = 0x02000000;
    public final static int FLAG_FILTERED = 0x04000000;
    public final static int FLAG_DISABLE_KEY_REPEAT = 0x08000000;

    public final static int FLAG_INTERACTIVE = 0x20000000;
    public final static int FLAG_PASS_TO_USER = 0x40000000;

    // Flags used for indicating whether the internal and/or external input devices
    // of some type are available.
    public final static int PRESENCE_INTERNAL = 1 << 0;
    public final static int PRESENCE_EXTERNAL = 1 << 1;

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

    /**
     * Pass this event to the user / app.  To be returned from
     * {@link #interceptKeyBeforeQueueing}.
     */
    public final static int ACTION_PASS_TO_USER = 0x00000001;

    /**
     * Interface to the Window Manager state associated with a particular
     * window.  You can hold on to an instance of this interface from the call
     * to prepareAddWindow() until removeWindow().
     */
    public interface WindowState {
        /**
         * Return the uid of the app that owns this window.
         */
        int getOwningUid();

        /**
         * Return the package name of the app that owns this window.
         */
        String getOwningPackage();

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
         * @param overlayFrame The frame within the display that is inside
         * of the overlay region.
         * @param contentFrame The frame within the display in which we would
         * like active content to appear.  This will cause windows behind to
         * be resized to match the given content frame.
         * @param visibleFrame The frame within the display that the window
         * is actually visible, used for computing its visible insets to be
         * given to windows behind.
         * This can be used as a hint for scrolling (avoiding resizing)
         * the window to make certain that parts of its content
         * are visible.
         * @param decorFrame The decor frame specified by policy specific to this window,
         * to use for proper cropping during animation.
         * @param stableFrame The frame around which stable system decoration is positioned.
         */
        public void computeFrameLw(Rect parentFrame, Rect displayFrame,
                Rect overlayFrame, Rect contentFrame, Rect visibleFrame, Rect decorFrame,
                Rect stableFrame);

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
         * Retrieve the frame of the area inside the overscan region of the
         * display that this window was last laid out in.  Must be called with the
         * window manager lock held.
         *
         * @return Rect The rectangle holding the display overscan frame.
         */
        public Rect getOverscanFrameLw();

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
         * Return whether this window needs the menu key shown.  Must be called
         * with window lock held, because it may need to traverse down through
         * window list to determine the result.
         * @param bottom The bottom-most window to consider when determining this.
         */
        public boolean getNeedsMenuLw(WindowState bottom);

        /**
         * Retrieve the current system UI visibility flags associated with
         * this window.
         */
        public int getSystemUiVisibility();

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
         * Return true if this window is participating in voice interaction.
         */
        public boolean isVoiceInteraction();

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
         * Return true if this window (or a window it is attached to, but not
         * considering its app token) is currently animating.
         */
        public boolean isAnimatingLw();

        /**
         * Is this window considered to be gone for purposes of layout?
         */
        boolean isGoneForLayoutLw();

        /**
         * Returns true if this window has been shown on screen at some time in 
         * the past.  Must be called with the window manager lock held.
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

        /**
         * Check whether the process hosting this window is currently alive.
         */
        public boolean isAlive();

        /**
         * Check if window is on {@link Display#DEFAULT_DISPLAY}.
         * @return true if window is on default display.
         */
        public boolean isDefaultDisplay();
    }

    /**
     * Representation of a "fake window" that the policy has added to the
     * window manager to consume events.
     */
    public interface FakeWindow {
        /**
         * Remove the fake window from the window manager.
         */
        void dismiss();
    }

    /**
     * Interface for calling back in to the window manager that is private
     * between it and the policy.
     */
    public interface WindowManagerFuncs {
        public static final int LID_ABSENT = -1;
        public static final int LID_CLOSED = 0;
        public static final int LID_OPEN = 1;

        public static final int CAMERA_LENS_COVER_ABSENT = -1;
        public static final int CAMERA_LENS_UNCOVERED = 0;
        public static final int CAMERA_LENS_COVERED = 1;

        /**
         * Ask the window manager to re-evaluate the system UI flags.
         */
        public void reevaluateStatusBarVisibility();

        /**
         * Add a fake window to the window manager.  This window sits
         * at the top of the other windows and consumes events.
         */
        public FakeWindow addFakeWindow(Looper looper,
                InputEventReceiver.Factory inputEventReceiverFactory,
                String name, int windowType, int layoutParamsFlags, int layoutParamsPrivateFlags,
                boolean canReceiveKeys, boolean hasFocus, boolean touchFullscreen);

        /**
         * Returns a code that describes the current state of the lid switch.
         */
        public int getLidState();

        /**
         * Returns a code that descripbes whether the camera lens is covered or not.
         */
        public int getCameraLensCoverState();

        /**
         * Switch the keyboard layout for the given device.
         * Direction should be +1 or -1 to go to the next or previous keyboard layout.
         */
        public void switchKeyboardLayout(int deviceId, int direction);

        public void shutdown(boolean confirm);
        public void rebootSafeMode(boolean confirm);

        /**
         * Return the window manager lock needed to correctly call "Lw" methods.
         */
        public Object getWindowManagerLock();

        /** Register a system listener for touch events */
        void registerPointerEventListener(PointerEventListener listener);

        /** Unregister a system listener for touch events */
        void unregisterPointerEventListener(PointerEventListener listener);
    }

    public interface PointerEventListener {
        /**
         * 1. onPointerEvent will be called on the service.UiThread.
         * 2. motionEvent will be recycled after onPointerEvent returns so if it is needed later a
         * copy() must be made and the copy must be recycled.
         **/
        public void onPointerEvent(MotionEvent motionEvent);
    }

    /** Window has been added to the screen. */
    public static final int TRANSIT_ENTER = 1;
    /** Window has been removed from the screen. */
    public static final int TRANSIT_EXIT = 2;
    /** Window has been made visible. */
    public static final int TRANSIT_SHOW = 3;
    /** Window has been made invisible.
     * TODO: Consider removal as this is unused. */
    public static final int TRANSIT_HIDE = 4;
    /** The "application starting" preview window is no longer needed, and will
     * animate away to show the real window. */
    public static final int TRANSIT_PREVIEW_DONE = 5;

    // NOTE: screen off reasons are in order of significance, with more
    // important ones lower than less important ones.
    
    /** Screen turned off because of a device admin */
    public final int OFF_BECAUSE_OF_ADMIN = 1;
    /** Screen turned off because of power button */
    public final int OFF_BECAUSE_OF_USER = 2;
    /** Screen turned off because of timeout */
    public final int OFF_BECAUSE_OF_TIMEOUT = 3;

    /** @hide */
    @IntDef({USER_ROTATION_FREE, USER_ROTATION_LOCKED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserRotationMode {}

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
     */
    public void init(Context context, IWindowManager windowManager,
            WindowManagerFuncs windowManagerFuncs);

    /**
     * @return true if com.android.internal.R.bool#config_forceDefaultOrientation is true.
     */
    public boolean isDefaultOrientationForced();

    /**
     * Called by window manager once it has the initial, default native
     * display dimensions.
     */
    public void setInitialDisplaySize(Display display, int width, int height, int density);

    /**
     * Called by window manager to set the overscan region that should be used for the
     * given display.
     */
    public void setDisplayOverscan(Display display, int left, int top, int right, int bottom);

    /**
     * Check permissions when adding a window.
     * 
     * @param attrs The window's LayoutParams.
     * @param outAppOp First element will be filled with the app op corresponding to
     *                 this window, or OP_NONE.
     *  
     * @return {@link WindowManagerGlobal#ADD_OKAY} if the add can proceed;
     *      else an error code, usually
     *      {@link WindowManagerGlobal#ADD_PERMISSION_DENIED}, to abort the add.
     */
    public int checkAddPermission(WindowManager.LayoutParams attrs, int[] outAppOp);

    /**
     * Check permissions when adding a window.
     *
     * @param attrs The window's LayoutParams.
     *
     * @return True if the window may only be shown to the current user, false if the window can
     * be shown on all users' windows.
     */
    public boolean checkShowToOwnerOnly(WindowManager.LayoutParams attrs);

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
     * @param keyboardPresence Flags that indicate whether internal or external
     * keyboards are present.
     * @param navigationPresence Flags that indicate whether internal or external
     * navigation devices are present.
     */
    public void adjustConfigurationLw(Configuration config, int keyboardPresence,
            int navigationPresence);
    
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
     * Return the window layer at which windows appear above the normal
     * universe (that is no longer impacted by the universe background
     * transform).
     */
    public int getAboveUniverseLayer();

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
     * {@link #getNonDecorDisplayWidth(int, int, int)}; it may be smaller than
     * that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation);

    /**
     * Return the available screen height that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayHeight(int, int, int)}; it may be smaller than
     * that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation);

    /**
     * Return whether the given window is forcibly hiding all windows except windows with
     * FLAG_SHOW_WHEN_LOCKED set.  Typically returns true for the keyguard.
     */
    public boolean isForceHiding(WindowManager.LayoutParams attrs);


    /**
     * Return whether the given window can become one that passes isForceHiding() test.
     * Typically returns true for the StatusBar.
     */
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs);

    /**
     * Determine if a window that is behind one that is force hiding
     * (as determined by {@link #isForceHiding}) should actually be hidden.
     * For example, typically returns false for the status bar.  Be careful
     * to return false for any window that you may hide yourself, since this
     * will conflict with what you set.
     */
    public boolean canBeForceHidden(WindowState win, WindowManager.LayoutParams attrs);

    /**
     * Return the window that is hiding the keyguard, if such a thing exists.
     */
    public WindowState getWinShowWhenLockedLw();

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
            int labelRes, int icon, int logo, int windowFlags);

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
     * @return {@link WindowManagerGlobal#ADD_OKAY} if the add can proceed, else an
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
     * Determine the animation to run for a rotation transition based on the
     * top fullscreen windows {@link WindowManager.LayoutParams#rotationAnimation}
     * and whether it is currently fullscreen and frontmost.
     * 
     * @param anim The exiting animation resource id is stored in anim[0], the 
     * entering animation resource id is stored in anim[1].
     */
    public void selectRotationAnimationLw(int anim[]);

    /**
     * Validate whether the current top fullscreen has specified the same
     * {@link WindowManager.LayoutParams#rotationAnimation} value as that
     * being passed in from the previous top fullscreen window.
     *
     * @param exitAnimId exiting resource id from the previous window.
     * @param enterAnimId entering resource id from the previous window.
     * @param forceDefault For rotation animations only, if true ignore the
     * animation values and just return false.
     * @return true if the previous values are still valid, false if they
     * should be replaced with the default.
     */
    public boolean validateRotationAnimationLw(int exitAnimId, int enterAnimId,
            boolean forceDefault);

    /**
     * Create and return an animation to re-display a force hidden window.
     */
    public Animation createForceHideEnterAnimation(boolean onWallpaper,
            boolean goingToNotificationShade);

    /**
     * Create and return an animation to let the wallpaper disappear after being shown on a force
     * hiding window.
     */
    public Animation createForceHideWallpaperExitAnimation(boolean goingToNotificationShade);

    /**
     * Called from the input reader thread before a key is enqueued.
     *
     * <p>There are some actions that need to be handled here because they
     * affect the power state of the device, for example, the power keys.
     * Generally, it's best to keep as little as possible in the queue thread
     * because it's the most fragile.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     *
     * @return Actions flags: may be {@link #ACTION_PASS_TO_USER}.
     */
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags);

    /**
     * Called from the input reader thread before a motion is enqueued when the device is in a
     * non-interactive state.
     *
     * <p>There are some actions that need to be handled here because they
     * affect the power state of the device, for example, waking on motions.
     * Generally, it's best to keep as little as possible in the queue thread
     * because it's the most fragile.
     * @param policyFlags The policy flags associated with the motion.
     *
     * @return Actions flags: may be {@link #ACTION_PASS_TO_USER}.
     */
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags);

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
     * @return 0 if the key should be dispatched immediately, -1 if the key should
     * not be dispatched ever, or a positive value indicating the number of
     * milliseconds by which the key dispatch should be delayed before trying
     * again.
     */
    public long interceptKeyBeforeDispatching(WindowState win, KeyEvent event, int policyFlags);

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
     * @param isDefaultDisplay true if window is on {@link Display#DEFAULT_DISPLAY}.
     * @param displayWidth The current full width of the screen.
     * @param displayHeight The current full height of the screen.
     * @param displayRotation The current rotation being applied to the base
     * window.
     */
    public void beginLayoutLw(boolean isDefaultDisplay, int displayWidth, int displayHeight,
                              int displayRotation);

    /**
     * Returns the bottom-most layer of the system decor, above which no policy decor should
     * be applied.
     */
    public int getSystemDecorLayerLw();

    /**
     * Return the rectangle of the screen that is available for applications to run in.
     * This will be called immediately after {@link #beginLayoutLw}.
     *
     * @param r The rectangle to be filled with the boundaries available to applications.
     */
    public void getContentRectLw(Rect r);

    /**
     * Called for each window attached to the window manager as layout is
     * proceeding.  The implementation of this function must take care of
     * setting the window's frame, either here or in finishLayout().
     * 
     * @param win The window being positioned.
     * @param attached For sub-windows, the window it is attached to; this
     *                 window will already have had layoutWindow() called on it
     *                 so you can use its Rect.  Otherwise null.
     */
    public void layoutWindowLw(WindowState win, WindowState attached);

    
    /**
     * Return the insets for the areas covered by system windows. These values
     * are computed on the most recent layout, so they are not guaranteed to
     * be correct.
     *
     * @param attrs The LayoutParams of the window.
     * @param outContentInsets The areas covered by system windows, expressed as positive insets.
     * @param outStableInsets The areas covered by stable system windows irrespective of their
     *                        current visibility. Expressed as positive insets.
     *
     */
    public void getInsetHintLw(WindowManager.LayoutParams attrs, Rect outContentInsets,
            Rect outStableInsets);

    /**
     * Called when layout of the windows is finished.  After this function has
     * returned, all windows given to layoutWindow() <em>must</em> have had a
     * frame assigned.
     */
    public void finishLayoutLw();

    /** Layout state may have changed (so another layout will be performed) */
    static final int FINISH_LAYOUT_REDO_LAYOUT = 0x0001;
    /** Configuration state may have changed */
    static final int FINISH_LAYOUT_REDO_CONFIG = 0x0002;
    /** Wallpaper may need to move */
    static final int FINISH_LAYOUT_REDO_WALLPAPER = 0x0004;
    /** Need to recompute animations */
    static final int FINISH_LAYOUT_REDO_ANIM = 0x0008;
    
    /**
     * Called following layout of all windows before each window has policy applied.
     * 
     * @param displayWidth The current full width of the screen.
     * @param displayHeight The current full height of the screen.
     */
    public void beginPostLayoutPolicyLw(int displayWidth, int displayHeight);

    /**
     * Called following layout of all window to apply policy to each window.
     * 
     * @param win The window being positioned.
     * @param attrs The LayoutParams of the window. 
     */
    public void applyPostLayoutPolicyLw(WindowState win,
            WindowManager.LayoutParams attrs);

    /**
     * Called following layout of all windows and after policy has been applied
     * to each window. If in this function you do
     * something that may have modified the animation state of another window,
     * be sure to return non-zero in order to perform another pass through layout.
     *  
     * @return Return any bit set of {@link #FINISH_LAYOUT_REDO_LAYOUT},
     * {@link #FINISH_LAYOUT_REDO_CONFIG}, {@link #FINISH_LAYOUT_REDO_WALLPAPER},
     * or {@link #FINISH_LAYOUT_REDO_ANIM}.
     */
    public int finishPostLayoutPolicyLw();

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
    public int focusChangedLw(WindowState lastFocus, WindowState newFocus);

    /**
     * Called when the device is waking up.
     */
    public void wakingUp();

    /**
     * Called when the device is going to sleep.
     *
     * @param why {@link #OFF_BECAUSE_OF_USER} or
     * {@link #OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void goingToSleep(int why);

    /**
     * Called when the device is about to turn on the screen to show content.
     * When waking up, this method will be called once after the call to wakingUp().
     * When dozing, the method will be called sometime after the call to goingToSleep() and
     * may be called repeatedly in the case where the screen is pulsing on and off.
     *
     * Must call back on the listener to tell it when the higher-level system
     * is ready for the screen to go on (i.e. the lock screen is shown).
     */
    public void screenTurningOn(ScreenOnListener screenOnListener);

    /**
     * Called when the device has turned the screen off.
     */
    public void screenTurnedOff();

    public interface ScreenOnListener {
        void onScreenOn();
    }

    /**
     * Return whether the default display is on and not blocked by a black surface.
     */
    public boolean isScreenOn();

    /**
     * Tell the policy that the lid switch has changed state.
     * @param whenNanos The time when the change occurred in uptime nanoseconds.
     * @param lidOpen True if the lid is now open.
     */
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);

    /**
     * Tell the policy that the camera lens has been covered or uncovered.
     * @param whenNanos The time when the change occurred in uptime nanoseconds.
     * @param lensCovered True if the lens is covered.
     */
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered);

    /**
     * Tell the policy if anyone is requesting that keyguard not come on.
     *
     * @param enabled Whether keyguard can be on or not.  does not actually
     * turn it on, unless it was previously disabled with this function.
     *
     * @see android.app.KeyguardManager.KeyguardLock#disableKeyguard()
     * @see android.app.KeyguardManager.KeyguardLock#reenableKeyguard()
     */
    @SuppressWarnings("javadoc")
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
    @SuppressWarnings("javadoc")
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
     * Ask the policy to dismiss the keyguard, if it is currently shown.
     */
    public void dismissKeyguardLw();

    /**
     * Notifies the keyguard that the activity has drawn it was waiting for.
     */
    public void notifyActivityDrawnForKeyguardLw();

    /**
     * Ask the policy whether the Keyguard has drawn. If the Keyguard is disabled, this method
     * returns true as soon as we know that Keyguard is disabled.
     *
     * @return true if the keyguard has drawn.
     */
    public boolean isKeyguardDrawnLw();

    /**
     * Given an orientation constant, returns the appropriate surface rotation,
     * taking into account sensors, docking mode, rotation lock, and other factors.
     *
     * @param orientation An orientation constant, such as
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param lastRotation The most recently used rotation.
     * @return The surface rotation to use.
     */
    public int rotationForOrientationLw(@ActivityInfo.ScreenOrientation int orientation,
            int lastRotation);

    /**
     * Given an orientation constant and a rotation, returns true if the rotation
     * has compatible metrics to the requested orientation.  For example, if
     * the application requested landscape and got seascape, then the rotation
     * has compatible metrics; if the application requested portrait and got landscape,
     * then the rotation has incompatible metrics; if the application did not specify
     * a preference, then anything goes.
     *
     * @param orientation An orientation constant, such as
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param rotation The rotation to check.
     * @return True if the rotation is compatible with the requested orientation.
     */
    public boolean rotationHasCompatibleMetricsLw(@ActivityInfo.ScreenOrientation int orientation,
            int rotation);

    /**
     * Called by the window manager when the rotation changes.
     *
     * @param rotation The new rotation.
     */
    public void setRotationLw(int rotation);

    /**
     * Called when the system is mostly done booting to set whether
     * the system should go into safe mode.
     */
    public void setSafeMode(boolean safeMode);
    
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
     * screen to the user.  This will happen after systemReady(), and at
     * this point the display is active.
     */
    public void enableScreenAfterBoot();
    
    public void setCurrentOrientationLw(@ActivityInfo.ScreenOrientation int newOrientation);
    
    /**
     * Call from application to perform haptic feedback on its window.
     */
    public boolean performHapticFeedbackLw(WindowState win, int effectId, boolean always);
    
    /**
     * Called when we have started keeping the screen on because a window
     * requesting this has become visible.
     */
    public void keepScreenOnStartedLw();

    /**
     * Called when we have stopped keeping the screen on because the last window
     * requesting this is no longer visible.
     */
    public void keepScreenOnStoppedLw();

    /**
     * Gets the current user rotation mode. 
     *
     * @return The rotation mode.
     *
     * @see WindowManagerPolicy#USER_ROTATION_LOCKED
     * @see WindowManagerPolicy#USER_ROTATION_FREE 
     */
    @UserRotationMode
    public int getUserRotationMode();

    /**
     * Inform the policy that the user has chosen a preferred orientation ("rotation lock"). 
     *
     * @param mode One of {@link WindowManagerPolicy#USER_ROTATION_LOCKED} or
     *             {@link WindowManagerPolicy#USER_ROTATION_FREE}. 
     * @param rotation One of {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
     *                 {@link Surface#ROTATION_180}, {@link Surface#ROTATION_270}.
     */
    public void setUserRotationMode(@UserRotationMode int mode, @Surface.Rotation int rotation);

    /**
     * Called when a new system UI visibility is being reported, allowing
     * the policy to adjust what is actually reported.
     * @param visibility The raw visibility reported by the status bar.
     * @return The new desired visibility.
     */
    public int adjustSystemUiVisibilityLw(int visibility);

    /**
     * Specifies whether there is an on-screen navigation bar separate from the status bar.
     */
    public boolean hasNavigationBar();

    /**
     * Lock the device now.
     */
    public void lockNow(Bundle options);

    /**
     * Set the last used input method window state. This state is used to make IME transition
     * smooth.
     * @hide
     */
    public void setLastInputMethodWindowLw(WindowState ime, WindowState target);

    /**
     * Show the recents task list app.
     * @hide
     */
    public void showRecentApps();

    /**
     * Show the global actions dialog.
     * @hide
     */
    public void showGlobalActions();

    /**
     * @return The current height of the input method window.
     */
    public int getInputMethodWindowVisibleHeightLw();

    /**
     * Called when the current user changes. Guaranteed to be called before the broadcast
     * of the new user id is made to all listeners.
     *
     * @param newUserId The id of the incoming user.
     */
    public void setCurrentUserLw(int newUserId);

    /**
     * Print the WindowManagerPolicy's state into the given stream.
     *
     * @param prefix Text to print at the front of each line.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(String prefix, PrintWriter writer, String[] args);

    /**
     * Returns whether a given window type can be magnified.
     *
     * @param windowType The window type.
     * @return True if the window can be magnified.
     */
    public boolean canMagnifyWindow(int windowType);

    /**
     * Returns whether a given window type is considered a top level one.
     * A top level window does not have a container, i.e. attached window,
     * or if it has a container it is laid out as a top-level window, not
     * as a child of its container.
     *
     * @param windowType The window type.
     * @return True if the window is a top level one.
     */
    public boolean isTopLevelWindow(int windowType);

    /**
     * Notifies the keyguard to start fading out.
     *
     * @param startTime the start time of the animation in uptime milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration);
}
