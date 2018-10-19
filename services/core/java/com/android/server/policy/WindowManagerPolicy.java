/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAG;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.IApplicationToken;
import android.view.IWindowManager;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.wm.DisplayFrames;
import com.android.server.wm.utils.WmDisplayCutout;

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
 */
public interface WindowManagerPolicy extends WindowManagerPolicyConstants {
    @Retention(SOURCE)
    @IntDef({NAV_BAR_LEFT, NAV_BAR_RIGHT, NAV_BAR_BOTTOM})
    @interface NavigationBarPosition {}

    /**
     * Pass this event to the user / app.  To be returned from
     * {@link #interceptKeyBeforeQueueing}.
     */
    int ACTION_PASS_TO_USER = 0x00000001;
    /** Layout state may have changed (so another layout will be performed) */
    int FINISH_LAYOUT_REDO_LAYOUT = 0x0001;
    /** Configuration state may have changed */
    int FINISH_LAYOUT_REDO_CONFIG = 0x0002;
    /** Wallpaper may need to move */
    int FINISH_LAYOUT_REDO_WALLPAPER = 0x0004;
    /** Need to recompute animations */
    int FINISH_LAYOUT_REDO_ANIM = 0x0008;
    /** Layer for the screen off animation */
    int COLOR_FADE_LAYER = 0x40000001;

    /**
     * Register shortcuts for window manager to dispatch.
     * Shortcut code is packed as (metaState << Integer.SIZE) | keyCode
     * @hide
     */
    void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException;

    /**
     * Called when the Keyguard occluded state changed.
     * @param occluded Whether Keyguard is currently occluded or not.
     */
    void onKeyguardOccludedChangedLw(boolean occluded);

    /**
     * Called when the resource overlays change.
     */
    default void onOverlayChangedLw() {}

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
         * @param outsetFrame The frame that includes areas that aren't part of the surface but we
         * want to treat them as such.
         * @param displayCutout the display cutout
         * @param parentFrameWasClippedByDisplayCutout true if the parent frame would have been
         * different if there was no display cutout.
         */
        public void computeFrameLw(Rect parentFrame, Rect displayFrame,
                Rect overlayFrame, Rect contentFrame, Rect visibleFrame, Rect decorFrame,
                Rect stableFrame, @Nullable Rect outsetFrame, WmDisplayCutout displayCutout,
                boolean parentFrameWasClippedByDisplayCutout);

        /**
         * Retrieve the current frame of the window that has been assigned by
         * the window manager.  Must be called with the window manager lock held.
         *
         * @return Rect The rectangle holding the window frame.
         */
        public Rect getFrameLw();

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
         * Retrieve the type of the top-level window.
         *
         * @return the base type of the parent window if attached or its own type otherwise
         */
        public int getBaseType();

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
        boolean isAnimatingLw();

        /**
         * @return Whether the window can affect SystemUI flags, meaning that SystemUI (system bars,
         *         for example) will be  affected by the flags specified in this window. This is the
         *         case when the surface is on screen but not exiting.
         */
        boolean canAffectSystemUiFlags();

        /**
         * Is this window considered to be gone for purposes of layout?
         */
        boolean isGoneForLayoutLw();

        /**
         * Returns true if the window has a surface that it has drawn a
         * complete UI in to. Note that this is different from {@link #hasDrawnLw()}
         * in that it also returns true if the window is READY_TO_SHOW, but was not yet
         * promoted to HAS_DRAWN.
         */
        boolean isDrawnLw();

        /**
         * Returns true if this window has been shown on screen at some time in
         * the past.  Must be called with the window manager lock held.
         *
         * @deprecated Use {@link #isDrawnLw} or any of the other drawn/visibility methods.
         */
        @Deprecated
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

        /**
         * Check whether the window is currently dimming.
         */
        public boolean isDimming();

        /**
         * Returns true if the window is letterboxed for the display cutout.
         */
        default boolean isLetterboxedForDisplayCutoutLw() {
            return false;
        }

        /**
         * Returns true if the window has a letterbox and any part of that letterbox overlaps with
         * the given {@code rect}.
         */
        default boolean isLetterboxedOverlappingWith(Rect rect) {
            return false;
        }

        /** @return the current windowing mode of this window. */
        int getWindowingMode();

        /**
         * Returns true if the window is current in multi-windowing mode. i.e. it shares the
         * screen with other application windows.
         */
        public boolean isInMultiWindowMode();

        public int getRotationAnimationHint();

        public boolean isInputMethodWindow();

        public boolean isInputMethodTarget();

        public int getDisplayId();

        /**
         * Returns true if the window owner can add internal system windows.
         * That is, they have {@link Manifest.permission#INTERNAL_SYSTEM_WINDOW}.
         */
        default boolean canAddInternalSystemWindow() {
            return false;
        }

        /**
         * Returns true if the window owner has the permission to acquire a sleep token when it's
         * visible. That is, they have the permission {@link Manifest.permission#DEVICE_POWER}.
         */
        boolean canAcquireSleepToken();

        /**
         * Writes {@link com.android.server.wm.IdentifierProto} to stream.
         */
        void writeIdentifierToProto(ProtoOutputStream proto, long fieldId);
    }

    /**
     * Representation of a input consumer that the policy has added to the
     * window manager to consume input events going to windows below it.
     */
    public interface InputConsumer {
        /**
         * Remove the input consumer from the window manager.
         */
        void dismiss();
    }

    /**
     * Holds the contents of a starting window. {@link #addSplashScreen} needs to wrap the
     * contents of the starting window into an class implementing this interface, which then will be
     * held by WM and released with {@link #remove} when no longer needed.
     */
    interface StartingSurface {

        /**
         * Removes the starting window surface. Do not hold the window manager lock when calling
         * this method!
         */
        void remove();
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
         * Add a input consumer which will consume all input events going to any window below it.
         */
        public InputConsumer createInputConsumer(Looper looper, String name,
                InputEventReceiver.Factory inputEventReceiverFactory);

        /**
         * Returns a code that describes the current state of the lid switch.
         */
        public int getLidState();

        /**
         * Lock the device now.
         */
        public void lockDeviceNow();

        /**
         * Returns a code that descripbes whether the camera lens is covered or not.
         */
        public int getCameraLensCoverState();

        /**
         * Switch the keyboard layout for the given device.
         * Direction should be +1 or -1 to go to the next or previous keyboard layout.
         */
        public void switchKeyboardLayout(int deviceId, int direction);

        /**
         * Switch the input method, to be precise, input method subtype.
         *
         * @param forwardDirection {@code true} to rotate in a forward direction.
         */
        public void switchInputMethod(boolean forwardDirection);

        public void shutdown(boolean confirm);
        public void reboot(boolean confirm);
        public void rebootSafeMode(boolean confirm);

        /**
         * Return the window manager lock needed to correctly call "Lw" methods.
         */
        public Object getWindowManagerLock();

        /** Register a system listener for touch events */
        void registerPointerEventListener(PointerEventListener listener);

        /** Unregister a system listener for touch events */
        void unregisterPointerEventListener(PointerEventListener listener);

        /**
         * @return The content insets of the docked divider window.
         */
        int getDockedDividerInsetsLw();

        /**
         * Retrieves the {@param outBounds} from the stack matching the {@param windowingMode} and
         * {@param activityType}.
         */
        void getStackBounds(int windowingMode, int activityType, Rect outBounds);

        /**
         * Notifies window manager that {@link #isShowingDreamLw} has changed.
         */
        void notifyShowingDreamChanged();

        /**
         * @return The currently active input method window.
         */
        WindowState getInputMethodWindowLw();

        /**
         * Notifies window manager that {@link #isKeyguardTrustedLw} has changed.
         */
        void notifyKeyguardTrustedChanged();

        /**
         * Notifies the window manager that screen is being turned off.
         *
         * @param listener callback to call when display can be turned off
         */
        void screenTurningOff(ScreenOffListener listener);

        /**
         * Convert the lid state to a human readable format.
         */
        static String lidStateToString(int lid) {
            switch (lid) {
                case LID_ABSENT:
                    return "LID_ABSENT";
                case LID_CLOSED:
                    return "LID_CLOSED";
                case LID_OPEN:
                    return "LID_OPEN";
                default:
                    return Integer.toString(lid);
            }
        }

        /**
         * Convert the camera lens state to a human readable format.
         */
        static String cameraLensStateToString(int lens) {
            switch (lens) {
                case CAMERA_LENS_COVER_ABSENT:
                    return "CAMERA_LENS_COVER_ABSENT";
                case CAMERA_LENS_UNCOVERED:
                    return "CAMERA_LENS_UNCOVERED";
                case CAMERA_LENS_COVERED:
                    return "CAMERA_LENS_COVERED";
                default:
                    return Integer.toString(lens);
            }
        }

        /**
         * Hint to window manager that the user has started a navigation action that should
         * abort animations that have no timeout, in case they got stuck.
         */
        void triggerAnimationFailsafe();

        /**
         * The keyguard showing state has changed
         */
        void onKeyguardShowingAndNotOccludedChanged();
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
    public void adjustWindowParamsLw(WindowState win, WindowManager.LayoutParams attrs,
            boolean hasStatusBarServicePermission);

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
     * Returns the layer assignment for the window state. Allows you to control how different
     * kinds of windows are ordered on-screen.
     *
     * @param win The window state
     * @return int An arbitrary integer used to order windows, with lower numbers below higher ones.
     */
    default int getWindowLayerLw(WindowState win) {
        return getWindowLayerFromTypeLw(win.getBaseType(), win.canAddInternalSystemWindow());
    }

    /**
     * Returns the layer assignment for the window type. Allows you to control how different
     * kinds of windows are ordered on-screen.
     *
     * @param type The type of window being assigned.
     * @return int An arbitrary integer used to order windows, with lower numbers below higher ones.
     */
    default int getWindowLayerFromTypeLw(int type) {
        if (isSystemAlertWindowType(type)) {
            throw new IllegalArgumentException("Use getWindowLayerFromTypeLw() or"
                    + " getWindowLayerLw() for alert window types");
        }
        return getWindowLayerFromTypeLw(type, false /* canAddInternalSystemWindow */);
    }

    /**
     * Returns the layer assignment for the window type. Allows you to control how different
     * kinds of windows are ordered on-screen.
     *
     * @param type The type of window being assigned.
     * @param canAddInternalSystemWindow If the owner window associated with the type we are
     *        evaluating can add internal system windows. I.e they have
     *        {@link Manifest.permission#INTERNAL_SYSTEM_WINDOW}. If true, alert window
     *        types {@link android.view.WindowManager.LayoutParams#isSystemAlertWindowType(int)}
     *        can be assigned layers greater than the layer for
     *        {@link android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} Else, their
     *        layers would be lesser.
     * @return int An arbitrary integer used to order windows, with lower numbers below higher ones.
     */
    default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow) {
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }

        switch (type) {
            case TYPE_WALLPAPER:
                // wallpaper is at the bottom, though the window manager may move it.
                return  1;
            case TYPE_PRESENTATION:
            case TYPE_PRIVATE_PRESENTATION:
                return  APPLICATION_LAYER;
            case TYPE_DOCK_DIVIDER:
                return  APPLICATION_LAYER;
            case TYPE_QS_DIALOG:
                return  APPLICATION_LAYER;
            case TYPE_PHONE:
                return  3;
            case TYPE_SEARCH_BAR:
            case TYPE_VOICE_INTERACTION_STARTING:
                return  4;
            case TYPE_VOICE_INTERACTION:
                // voice interaction layer is almost immediately above apps.
                return  5;
            case TYPE_INPUT_CONSUMER:
                return  6;
            case TYPE_SYSTEM_DIALOG:
                return  7;
            case TYPE_TOAST:
                // toasts and the plugged-in battery thing
                return  8;
            case TYPE_PRIORITY_PHONE:
                // SIM errors and unlock.  Not sure if this really should be in a high layer.
                return  9;
            case TYPE_SYSTEM_ALERT:
                // like the ANR / app crashed dialogs
                return  canAddInternalSystemWindow ? 11 : 10;
            case TYPE_APPLICATION_OVERLAY:
                return  12;
            case TYPE_DREAM:
                // used for Dreams (screensavers with TYPE_DREAM windows)
                return  13;
            case TYPE_INPUT_METHOD:
                // on-screen keyboards and other such input method user interfaces go here.
                return  14;
            case TYPE_INPUT_METHOD_DIALOG:
                // on-screen keyboards and other such input method user interfaces go here.
                return  15;
            case TYPE_STATUS_BAR:
                return  17;
            case TYPE_STATUS_BAR_PANEL:
                return  18;
            case TYPE_STATUS_BAR_SUB_PANEL:
                return  19;
            case TYPE_KEYGUARD_DIALOG:
                return  20;
            case TYPE_VOLUME_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return  21;
            case TYPE_SYSTEM_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return  canAddInternalSystemWindow ? 22 : 11;
            case TYPE_NAVIGATION_BAR:
                // the navigation bar, if available, shows atop most things
                return  23;
            case TYPE_NAVIGATION_BAR_PANEL:
                // some panels (e.g. search) need to show on top of the navigation bar
                return  24;
            case TYPE_SCREENSHOT:
                // screenshot selection layer shouldn't go above system error, but it should cover
                // navigation bars at the very least.
                return  25;
            case TYPE_SYSTEM_ERROR:
                // system-level error dialogs
                return  canAddInternalSystemWindow ? 26 : 10;
            case TYPE_MAGNIFICATION_OVERLAY:
                // used to highlight the magnified portion of a display
                return  27;
            case TYPE_DISPLAY_OVERLAY:
                // used to simulate secondary display devices
                return  28;
            case TYPE_DRAG:
                // the drag layer: input for drag-and-drop is associated with this window,
                // which sits above all other focusable windows
                return  29;
            case TYPE_ACCESSIBILITY_OVERLAY:
                // overlay put by accessibility services to intercept user interaction
                return  30;
            case TYPE_SECURE_SYSTEM_OVERLAY:
                return  31;
            case TYPE_BOOT_PROGRESS:
                return  32;
            case TYPE_POINTER:
                // the (mouse) pointer layer
                return  33;
            default:
                Slog.e("WindowManager", "Unknown window type: " + type);
                return APPLICATION_LAYER;
        }
    }

    /**
     * Return how to Z-order sub-windows in relation to the window they are attached to.
     * Return positive to have them ordered in front, negative for behind.
     *
     * @param type The sub-window type code.
     *
     * @return int Layer in relation to the attached window, where positive is
     *         above and negative is below.
     */
    default int getSubWindowLayerFromTypeLw(int type) {
        switch (type) {
            case TYPE_APPLICATION_PANEL:
            case TYPE_APPLICATION_ATTACHED_DIALOG:
                return APPLICATION_PANEL_SUBLAYER;
            case TYPE_APPLICATION_MEDIA:
                return APPLICATION_MEDIA_SUBLAYER;
            case TYPE_APPLICATION_MEDIA_OVERLAY:
                return APPLICATION_MEDIA_OVERLAY_SUBLAYER;
            case TYPE_APPLICATION_SUB_PANEL:
                return APPLICATION_SUB_PANEL_SUBLAYER;
            case TYPE_APPLICATION_ABOVE_SUB_PANEL:
                return APPLICATION_ABOVE_SUB_PANEL_SUBLAYER;
        }
        Slog.e("WindowManager", "Unknown sub-window type: " + type);
        return 0;
    }

    /**
     * Get the highest layer (actually one more than) that the wallpaper is
     * allowed to be in.
     */
    public int getMaxWallpaperLayer();

    /**
     * Return the display width available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation,
            int uiMode, int displayId, DisplayCutout displayCutout);

    /**
     * Return the display height available after excluding any screen
     * decorations that could never be removed in Honeycomb. That is, system bar or
     * button bar.
     */
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation,
            int uiMode, int displayId, DisplayCutout displayCutout);

    /**
     * Return the available screen width that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayWidth(int, int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation,
            int uiMode, int displayId, DisplayCutout displayCutout);

    /**
     * Return the available screen height that we should report for the
     * configuration.  This must be no larger than
     * {@link #getNonDecorDisplayHeight(int, int, int, int, int, DisplayCutout)}; it may be smaller
     * than that to account for more transient decoration like a status bar.
     */
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation,
            int uiMode, int displayId, DisplayCutout displayCutout);

    /**
     * Return whether the given window can become the Keyguard window. Typically returns true for
     * the StatusBar.
     */
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs);

    /**
     * @return whether {@param win} can be hidden by Keyguard
     */
    public boolean canBeHiddenByKeyguardLw(WindowState win);

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
     * @param overrideConfig override configuration to consider when generating
     *        context to for resources.
     * @param displayId Id of the display to show the splash screen at.
     *
     * @return The starting surface.
     *
     */
    public StartingSurface addSplashScreen(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration overrideConfig, int displayId);

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
     * Create and return an animation to re-display a window that was force hidden by Keyguard.
     */
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade);

    /**
     * Create and return an animation to let the wallpaper disappear after being shown behind
     * Keyguard.
     */
    public Animation createKeyguardWallpaperExit(boolean goingToNotificationShade);

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
     * @param displayFrames frames of the display we are doing layout on.
     * @param uiMode The current uiMode in configuration.
     */
    default void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {}

    /**
     * Returns the bottom-most layer of the system decor, above which no policy decor should
     * be applied.
     */
    public int getSystemDecorLayerLw();

    /**
     * Called for each window attached to the window manager as layout is proceeding. The
     * implementation of this function must take care of setting the window's frame, either here or
     * in finishLayout().
     *
     * @param win The window being positioned.
     * @param attached For sub-windows, the window it is attached to; this
     *                 window will already have had layoutWindow() called on it
     *                 so you can use its Rect.  Otherwise null.
     * @param displayFrames The display frames.
     */
    default void layoutWindowLw(
            WindowState win, WindowState attached, DisplayFrames displayFrames) {}


    /**
     * Return the layout hints for a newly added window. These values are computed on the
     * most recent layout, so they are not guaranteed to be correct.
     *
     * @param attrs The LayoutParams of the window.
     * @param taskBounds The bounds of the task this window is on or {@code null} if no task is
     *                   associated with the window.
     * @param displayFrames display frames.
     * @param outFrame The frame of the window.
     * @param outContentInsets The areas covered by system windows, expressed as positive insets.
     * @param outStableInsets The areas covered by stable system windows irrespective of their
     *                        current visibility. Expressed as positive insets.
     * @param outOutsets The areas that are not real display, but we would like to treat as such.
     * @param outDisplayCutout The area that has been cut away from the display.
     * @return Whether to always consume the navigation bar.
     *         See {@link #isNavBarForcedShownLw(WindowState)}.
     */
    default boolean getLayoutHintLw(WindowManager.LayoutParams attrs, Rect taskBounds,
            DisplayFrames displayFrames, Rect outFrame, Rect outContentInsets,
            Rect outStableInsets, Rect outOutsets,
            DisplayCutout.ParcelableWrapper outDisplayCutout) {
        return false;
    }

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
     * @param attached For sub-windows, the window it is attached to. Otherwise null.
     */
    public void applyPostLayoutPolicyLw(WindowState win,
            WindowManager.LayoutParams attrs, WindowState attached, WindowState imeTarget);

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
     * that is about to occur. You may return false for this if, for example,
     * the dream window is currently displayed so the switch should happen
     * immediately.
     */
    public boolean allowAppAnimationsLw();

    /**
     * A new window has been focused.
     */
    public int focusChangedLw(WindowState lastFocus, WindowState newFocus);

    /**
     * Called when the device has started waking up.
     */
    public void startedWakingUp();

    /**
     * Called when the device has finished waking up.
     */
    public void finishedWakingUp();

    /**
     * Called when the device has started going to sleep.
     *
     * @param why {@link #OFF_BECAUSE_OF_USER}, {@link #OFF_BECAUSE_OF_ADMIN},
     * or {@link #OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void startedGoingToSleep(int why);

    /**
     * Called when the device has finished going to sleep.
     *
     * @param why {@link #OFF_BECAUSE_OF_USER}, {@link #OFF_BECAUSE_OF_ADMIN},
     * or {@link #OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void finishedGoingToSleep(int why);

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
     * Called when the device has actually turned on the screen, i.e. the display power state has
     * been set to ON and the screen is unblocked.
     */
    public void screenTurnedOn();

    /**
     * Called when the display would like to be turned off. This gives policy a chance to do some
     * things before the display power state is actually changed to off.
     *
     * @param screenOffListener Must be called to tell that the display power state can actually be
     *                          changed now after policy has done its work.
     */
    public void screenTurningOff(ScreenOffListener screenOffListener);

    /**
     * Called when the device has turned the screen off.
     */
    public void screenTurnedOff();

    public interface ScreenOnListener {
        void onScreenOn();
    }

    /**
     * See {@link #screenTurnedOff}
     */
    public interface ScreenOffListener {
        void onScreenOff();
    }

    /**
     * Return whether the default display is on and not blocked by a black surface.
     */
    public boolean isScreenOn();

    /**
     * @return whether the device is currently allowed to animate.
     *
     * Note: this can be true even if it is not appropriate to animate for reasons that are outside
     *       of the policy's authority.
     */
    boolean okToAnimate();

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
     * Callback used by {@link #exitKeyguardSecurely}
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
     * @param userId
     *
     * @return true if in keyguard is secure.
     */
    public boolean isKeyguardSecure(int userId);

    /**
     * Return whether the keyguard is currently occluded.
     *
     * @return true if in keyguard is occluded, false otherwise
     */
    public boolean isKeyguardOccluded();

    /**
     * @return true if in keyguard is on and not occluded.
     */
    public boolean isKeyguardShowingAndNotOccluded();

    /**
     * @return whether Keyguard is in trusted state and can be dismissed without credentials
     */
    public boolean isKeyguardTrustedLw();

    /**
     * inKeyguardRestrictedKeyInputMode
     *
     * If keyguard screen is showing or in restricted key input mode (i.e. in
     * keyguard password emergency screen). When in such mode, certain keys,
     * such as the Home key and the right soft keys, don't work.
     *
     * @return true if in keyguard restricted input mode.
     */
    public boolean inKeyguardRestrictedKeyInputMode();

    /**
     * Ask the policy to dismiss the keyguard, if it is currently shown.
     *
     * @param callback Callback to be informed about the result.
     * @param message A message that should be displayed in the keyguard.
     */
    public void dismissKeyguardLw(@Nullable IKeyguardDismissCallback callback,
            CharSequence message);

    /**
     * Ask the policy whether the Keyguard has drawn. If the Keyguard is disabled, this method
     * returns true as soon as we know that Keyguard is disabled.
     *
     * @return true if the keyguard has drawn.
     */
    public boolean isKeyguardDrawnLw();

    public boolean isShowingDreamLw();

    /**
     * Given an orientation constant, returns the appropriate surface rotation,
     * taking into account sensors, docking mode, rotation lock, and other factors.
     *
     * @param orientation An orientation constant, such as
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param lastRotation The most recently used rotation.
     * @param defaultDisplay Flag indicating whether the rotation is computed for the default
     *                       display. Currently for all non-default displays sensors, docking mode,
     *                       rotation lock and other factors are ignored.
     * @return The surface rotation to use.
     */
    public int rotationForOrientationLw(@ActivityInfo.ScreenOrientation int orientation,
            int lastRotation, boolean defaultDisplay);

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
     * @see #USER_ROTATION_LOCKED
     * @see #USER_ROTATION_FREE
     */
    @UserRotationMode
    public int getUserRotationMode();

    /**
     * Inform the policy that the user has chosen a preferred orientation ("rotation lock").
     *
     * @param mode One of {@link #USER_ROTATION_LOCKED} or {@link #USER_ROTATION_FREE}.
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
     * Called by System UI to notify of changes to the visibility of Recents.
     */
    public void setRecentsVisibilityLw(boolean visible);

    /**
     * Called by System UI to notify of changes to the visibility of PIP.
     */
    void setPipVisibilityLw(boolean visible);

    /**
     * Called by System UI to enable or disable haptic feedback on the navigation bar buttons.
     */
    void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean enabled);

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
     * An internal callback (from InputMethodManagerService) to notify a state change regarding
     * whether the back key should dismiss the software keyboard (IME) or not.
     *
     * @param newValue {@code true} if the software keyboard is shown and the back key is expected
     *                 to dismiss the software keyboard.
     * @hide
     */
    default void setDismissImeOnBackKeyPressed(boolean newValue) {
        // Default implementation does nothing.
    }

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
     * Called when the current user changes. Guaranteed to be called before the broadcast
     * of the new user id is made to all listeners.
     *
     * @param newUserId The id of the incoming user.
     */
    public void setCurrentUserLw(int newUserId);

    /**
     * For a given user-switch operation, this will be called once with switching=true before the
     * user-switch and once with switching=false afterwards (or if the user-switch was cancelled).
     * This gives the policy a chance to alter its behavior for the duration of a user-switch.
     *
     * @param switching true if a user-switch is in progress
     */
    void setSwitchingUser(boolean switching);

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
     * Write the WindowManagerPolicy's state into the protocol buffer.
     * The message is described in {@link com.android.server.wm.WindowManagerPolicyProto}
     *
     * @param proto The protocol buffer output stream to write to.
     */
    void writeToProto(ProtoOutputStream proto, long fieldId);

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

    /**
     * Calculates the stable insets without running a layout.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets);


    /**
     * @return true if the navigation bar is forced to stay visible
     */
    public boolean isNavBarForcedShownLw(WindowState win);

    /**
     * @return The side of the screen where navigation bar is positioned.
     * @see #NAV_BAR_LEFT
     * @see #NAV_BAR_RIGHT
     * @see #NAV_BAR_BOTTOM
     */
    @NavigationBarPosition
    int getNavBarPosition();

    /**
     * Calculates the insets for the areas that could never be removed in Honeycomb, i.e. system
     * bar or button bar. See {@link #getNonDecorDisplayWidth}.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            DisplayCutout displayCutout, Rect outInsets);

    /**
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param dockSide the dockside asking if allowed
     * @param originalDockSide the side that was original docked to in split screen
     * @return True if a specified {@param dockSide} is allowed on the current device, or false
     *         otherwise. It is guaranteed that at least one dock side for a particular orientation
     *         is allowed, so for example, if DOCKED_RIGHT is not allowed, DOCKED_LEFT is allowed.
     *         If navigation bar is movable then the docked side would bias towards the
     *         {@param originalDockSide}.
     */
    public boolean isDockSideAllowed(int dockSide, int originalDockSide, int displayWidth,
            int displayHeight, int displayRotation);

    /**
     * Called when the configuration has changed, and it's safe to load new values from resources.
     */
    public void onConfigurationChanged();

    public boolean shouldRotateSeamlessly(int oldRotation, int newRotation);

    /**
     * Called when System UI has been started.
     */
    void onSystemUiStarted();

    /**
     * Checks whether the policy is ready for dismissing the boot animation and completing the boot.
     *
     * @return true if ready; false otherwise.
     */
    boolean canDismissBootAnimation();

    /**
     * Convert the user rotation mode to a human readable format.
     */
    static String userRotationModeToString(int mode) {
        switch(mode) {
            case USER_ROTATION_FREE:
                return "USER_ROTATION_FREE";
            case USER_ROTATION_LOCKED:
                return "USER_ROTATION_LOCKED";
            default:
                return Integer.toString(mode);
        }
    }

    /**
     * Requests that the WindowManager sends WindowManagerPolicy#ACTION_USER_ACTIVITY_NOTIFICATION
     * on the next user activity.
     */
    public void requestUserActivityNotification();

    /**
     * Called when the state of lock task mode changes. This should be used to disable immersive
     * mode confirmation.
     *
     * @param lockTaskState the new lock task mode state. One of
     *                      {@link ActivityManager#LOCK_TASK_MODE_NONE},
     *                      {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     *                      {@link ActivityManager#LOCK_TASK_MODE_PINNED}.
     */
    void onLockTaskStateChangedLw(int lockTaskState);

    /**
     * Updates the flag about whether AOD is showing.
     *
     * @return whether the value was changed.
     */
    boolean setAodShowing(boolean aodShowing);
}
