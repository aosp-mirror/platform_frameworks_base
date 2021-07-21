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
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
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
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
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
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.IApplicationToken;
import android.view.IDisplayFoldListener;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.wm.DisplayRotation;

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

    @Retention(SOURCE)
    @IntDef({ALT_BAR_UNKNOWN, ALT_BAR_LEFT, ALT_BAR_RIGHT, ALT_BAR_BOTTOM, ALT_BAR_TOP})
    @interface AltBarPosition {}

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

    /** Applies a keyguard occlusion change if one happened. */
    int applyKeyguardOcclusionChange();

    /**
     * Interface to the Window Manager state associated with a particular
     * window. You can hold on to an instance of this interface from the call
     * to prepareAddWindow() until removeWindow().
     */
    public interface WindowState {
        /**
         * Return the package name of the app that owns this window.
         */
        String getOwningPackage();

        /**
         * Retrieve the current LayoutParams of the window.
         *
         * @return WindowManager.LayoutParams The window's internal LayoutParams
         *         instance.
         */
        public WindowManager.LayoutParams getAttrs();

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
         * Return true if this window (or a window it is attached to, but not
         * considering its app token) is currently animating.
         */
        boolean isAnimatingLw();

        /**
         * Returns true if the window owner can add internal system windows.
         * That is, they have {@link Manifest.permission#INTERNAL_SYSTEM_WINDOW}.
         */
        default boolean canAddInternalSystemWindow() {
            return false;
        }

        /** @return true if the window can show over keyguard. */
        boolean canShowWhenLocked();
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
         * @param animate Whether need to play the default exit animation for starting window.
         */
        void remove(boolean animate);
    }

    /**
     * Interface for calling back in to the window manager that is private
     * between it and the policy.
     */
    public interface WindowManagerFuncs {
        public static final int LID_ABSENT = -1;
        public static final int LID_CLOSED = 0;
        public static final int LID_OPEN = 1;

        public static final int LID_BEHAVIOR_NONE = 0;
        public static final int LID_BEHAVIOR_SLEEP = 1;
        public static final int LID_BEHAVIOR_LOCK = 2;

        public static final int CAMERA_LENS_COVER_ABSENT = -1;
        public static final int CAMERA_LENS_UNCOVERED = 0;
        public static final int CAMERA_LENS_COVERED = 1;

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

        public void shutdown(boolean confirm);
        public void reboot(boolean confirm);
        public void rebootSafeMode(boolean confirm);

        /**
         * Return the window manager lock needed to correctly call "Lw" methods.
         */
        public Object getWindowManagerLock();

        /** Register a system listener for touch events */
        void registerPointerEventListener(PointerEventListener listener, int displayId);

        /** Unregister a system listener for touch events */
        void unregisterPointerEventListener(PointerEventListener listener, int displayId);

        /**
         * Notifies window manager that {@link #isKeyguardTrustedLw} has changed.
         */
        void notifyKeyguardTrustedChanged();

        /**
         * Notifies the window manager that screen is being turned off.
         *
         * @param displayId the ID of the display which is turning off
         * @param listener callback to call when display can be turned off
         */
        void screenTurningOff(int displayId, ScreenOffListener listener);

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

        /**
         * Notifies window manager that power key is being pressed.
         */
        void onPowerKeyDown(boolean isScreenOn);

        /**
         * Notifies window manager that user is switched.
         */
        void onUserSwitched();

        /**
         * Hint to window manager that the user is interacting with a display that should be treated
         * as the top display.
         */
        void moveDisplayToTop(int displayId);
    }

    /**
     * Interface to get public information of a display content.
     */
    public interface DisplayContentInfo {
        DisplayRotation getDisplayRotation();
        Display getDisplay();
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
     * Set the default display content to provide basic functions for the policy.
     */
    public void setDefaultDisplay(DisplayContentInfo displayContentInfo);

    /**
     * Perform initialization of the policy.
     *
     * @param context The system context we are running in.
     */
    public void init(Context context, IWindowManager windowManager,
            WindowManagerFuncs windowManagerFuncs);

    /**
     * Check permissions when adding a window.
     *
     * @param type The window type
     * @param isRoundedCornerOverlay {@code true} to indicate the adding window is
     *                                           round corner overlay.
     * @param packageName package name
     * @param outAppOp First element will be filled with the app op corresponding to
     *                 this window, or OP_NONE.
     *
     * @return {@link WindowManagerGlobal#ADD_OKAY} if the add can proceed;
     *      else an error code, usually
     *      {@link WindowManagerGlobal#ADD_PERMISSION_DENIED}, to abort the add.
     *
     * @see WindowManager.LayoutParams#PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
     */
    int checkAddPermission(int type, boolean isRoundedCornerOverlay, String packageName,
            int[] outAppOp);

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
        return getWindowLayerFromTypeLw(type, canAddInternalSystemWindow,
                false /* roundedCornerOverlay */);
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
     * @param roundedCornerOverlay {#code true} to indicate that the owner window is rounded corner
     *                             overlay.
     * @return int An arbitrary integer used to order windows, with lower numbers below higher ones.
     */
    default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow,
            boolean roundedCornerOverlay) {
        // Always put the rounded corner layer to the top most.
        if (roundedCornerOverlay && canAddInternalSystemWindow) {
            return getMaxWindowLayer();
        }
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }

        switch (type) {
            case TYPE_WALLPAPER:
                // wallpaper is at the bottom, though the window manager may move it.
                return  1;
            case TYPE_PRESENTATION:
            case TYPE_PRIVATE_PRESENTATION:
            case TYPE_DOCK_DIVIDER:
            case TYPE_QS_DIALOG:
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
                // Type is deprecated for non-system apps. For system apps, this type should be
                // in a higher layer than TYPE_APPLICATION_OVERLAY.
                return  canAddInternalSystemWindow ? 13 : 10;
            case TYPE_APPLICATION_OVERLAY:
                return  12;
            case TYPE_INPUT_METHOD:
                // on-screen keyboards and other such input method user interfaces go here.
                return  15;
            case TYPE_INPUT_METHOD_DIALOG:
                // on-screen keyboards and other such input method user interfaces go here.
                return  16;
            case TYPE_STATUS_BAR:
                return  17;
            case TYPE_STATUS_BAR_ADDITIONAL:
                return  18;
            case TYPE_NOTIFICATION_SHADE:
                return  19;
            case TYPE_STATUS_BAR_SUB_PANEL:
                return  20;
            case TYPE_KEYGUARD_DIALOG:
                return  21;
            case TYPE_VOLUME_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return  22;
            case TYPE_SYSTEM_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return  canAddInternalSystemWindow ? 23 : 11;
            case TYPE_NAVIGATION_BAR:
                // the navigation bar, if available, shows atop most things
                return  24;
            case TYPE_NAVIGATION_BAR_PANEL:
                // some panels (e.g. search) need to show on top of the navigation bar
                return  25;
            case TYPE_SCREENSHOT:
                // screenshot selection layer shouldn't go above system error, but it should cover
                // navigation bars at the very least.
                return  26;
            case TYPE_SYSTEM_ERROR:
                // system-level error dialogs
                return  canAddInternalSystemWindow ? 27 : 10;
            case TYPE_MAGNIFICATION_OVERLAY:
                // used to highlight the magnified portion of a display
                return  28;
            case TYPE_DISPLAY_OVERLAY:
                // used to simulate secondary display devices
                return  29;
            case TYPE_DRAG:
                // the drag layer: input for drag-and-drop is associated with this window,
                // which sits above all other focusable windows
                return  30;
            case TYPE_ACCESSIBILITY_OVERLAY:
                // overlay put by accessibility services to intercept user interaction
                return  31;
            case TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY:
                return 32;
            case TYPE_SECURE_SYSTEM_OVERLAY:
                return  33;
            case TYPE_BOOT_PROGRESS:
                return  34;
            case TYPE_POINTER:
                // the (mouse) pointer layer
                return  35;
            default:
                Slog.e("WindowManager", "Unknown window type: " + type);
                return 3;
        }
    }

    // TODO(b/155340867): consider to remove the logic after using pure Surface for rounded corner
    //  overlay.
    /**
     * Returns the max window layer.
     * <p>Note that the max window layer should be higher that the maximum value which reported
     * by {@link #getWindowLayerFromTypeLw(int, boolean)} to contain rounded corner overlay.</p>
     *
     * @see WindowManager.LayoutParams#PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
     */
    default int getMaxWindowLayer() {
        return 36;
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
     * Return whether the given window can become the Keyguard window. Typically returns true for
     * the StatusBar.
     */
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs);

    /**
     * @return whether {@param win} can be hidden by Keyguard
     */
    default boolean canBeHiddenByKeyguardLw(WindowState win) {
        // Keyguard visibility of window from activities are determined over activity visibility.
        if (win.getAppToken() != null) {
            return false;
        }
        switch (win.getAttrs().type) {
            case TYPE_NOTIFICATION_SHADE:
            case TYPE_STATUS_BAR:
            case TYPE_NAVIGATION_BAR:
            case TYPE_WALLPAPER:
                return false;
            default:
                // Hide only windows below the keyguard host window.
                return getWindowLayerLw(win) < getWindowLayerFromTypeLw(TYPE_NOTIFICATION_SHADE);
        }
    }

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
    StartingSurface addSplashScreen(IBinder appToken, int userId, String packageName,
            int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int logo, int windowFlags, Configuration overrideConfig, int displayId);

    /**
     * Set or clear a window which can behave as the keyguard.
     *
     * @param win The window which can behave as the keyguard.
     */
    void setKeyguardCandidateLw(@Nullable WindowState win);

    /**
     * Create and return an animation to re-display a window that was force hidden by Keyguard.
     */
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade, boolean subtleAnimation);

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
     * @param displayId The display ID of the motion event.
     * @param policyFlags The policy flags associated with the motion.
     *
     * @return Actions flags: may be {@link #ACTION_PASS_TO_USER}.
     */
    int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos,
            int policyFlags);

    /**
     * Called from the input dispatcher thread before a key is dispatched to a window.
     *
     * <p>Allows you to define
     * behavior for keys that can not be overridden by applications.
     * This method is called from the input thread, with no locks held.
     *
     * @param focusedToken Client window token that currently has focus. This is where the key
     *            event will normally go.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     * @return 0 if the key should be dispatched immediately, -1 if the key should
     * not be dispatched ever, or a positive value indicating the number of
     * milliseconds by which the key dispatch should be delayed before trying
     * again.
     */
    long interceptKeyBeforeDispatching(IBinder focusedToken, KeyEvent event, int policyFlags);

    /**
     * Called from the input dispatcher thread when an application did not handle
     * a key that was dispatched to it.
     *
     * <p>Allows you to define default global behavior for keys that were not handled
     * by applications.  This method is called from the input thread, with no locks held.
     *
     * @param focusedToken Client window token that currently has focus. This is where the key
     *            event will normally go.
     * @param event The key event.
     * @param policyFlags The policy flags associated with the key.
     * @return Returns an alternate key event to redispatch as a fallback, or null to give up.
     * The caller is responsible for recycling the key event.
     */
    KeyEvent dispatchUnhandledKey(IBinder focusedToken, KeyEvent event, int policyFlags);

    /**
     * Called when the top focused display is changed.
     *
     * @param displayId The ID of the top focused display.
     */
    void setTopFocusedDisplay(int displayId);

    /**
     * Called when the state of allow-lockscreen-when-on of the display is changed. See
     * {@link WindowManager.LayoutParams#FLAG_ALLOW_LOCK_WHILE_SCREEN_ON}
     *
     * @param displayId The ID of the display.
     * @param allow Whether the display allows showing lockscreen when it is on.
     */
    void setAllowLockscreenWhenOn(int displayId, boolean allow);

    /**
     * Called when the device has started waking up.
     *
     * @param pmWakeReason One of PowerManager.WAKE_REASON_*, detailing the specific reason we're
     * waking up, such as WAKE_REASON_POWER_BUTTON or WAKE_REASON_GESTURE.
     */
    void startedWakingUp(@PowerManager.WakeReason int pmWakeReason);

    /**
     * Called when the device has finished waking up.
     *
     * @param pmWakeReason One of PowerManager.WAKE_REASON_*, detailing the specific reason we're
     * waking up, such as WAKE_REASON_POWER_BUTTON or WAKE_REASON_GESTURE.
     */
    void finishedWakingUp(@PowerManager.WakeReason int pmWakeReason);

    /**
     * Called when the device has started going to sleep.
     *
     * @param pmSleepReason One of PowerManager.GO_TO_SLEEP_REASON_*, detailing the specific reason
     * we're going to sleep, such as GO_TO_SLEEP_REASON_POWER_BUTTON or GO_TO_SLEEP_REASON_TIMEOUT.
     */
    public void startedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason);

    /**
     * Called when the device has finished going to sleep.
     *
     * @param pmSleepReason One of PowerManager.GO_TO_SLEEP_REASON_*, detailing the specific reason
     * we're going to sleep, such as GO_TO_SLEEP_REASON_POWER_BUTTON or GO_TO_SLEEP_REASON_TIMEOUT.
     */
    public void finishedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason);

    /**
     * Called when the display is about to turn on to show content.
     * When waking up, this method will be called once after the call to wakingUp().
     * When dozing, the method will be called sometime after the call to goingToSleep() and
     * may be called repeatedly in the case where the screen is pulsing on and off.
     *
     * Must call back on the listener to tell it when the higher-level system
     * is ready for the screen to go on (i.e. the lock screen is shown).
     */
    public void screenTurningOn(int displayId, ScreenOnListener screenOnListener);

    /**
     * Called when the display has actually turned on, i.e. the display power state has been set to
     * ON and the screen is unblocked.
     */
    public void screenTurnedOn(int displayId);

    /**
     * Called when the display would like to be turned off. This gives policy a chance to do some
     * things before the display power state is actually changed to off.
     *
     * @param screenOffListener Must be called to tell that the display power state can actually be
     *                          changed now after policy has done its work.
     */
    public void screenTurningOff(int displayId, ScreenOffListener screenOffListener);

    /**
     * Called when the display has turned off.
     */
    public void screenTurnedOff(int displayId);

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
     * @param ignoreScreenOn {@code true} if screen state should be ignored.
     * @return whether the device is currently allowed to animate.
     *
     * Note: this can be true even if it is not appropriate to animate for reasons that are outside
     *       of the policy's authority.
     */
    boolean okToAnimate(boolean ignoreScreenOn);

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
     * @return true if in keyguard is on.
     */
    boolean isKeyguardShowing();

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

    /**
     * Call from application to perform haptic feedback on its window.
     */
    public boolean performHapticFeedback(int uid, String packageName, int effectId,
            boolean always, String reason);

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
     * Returns whether the user setup is complete.
     */
    boolean isUserSetupComplete();

    /**
     * Returns the current UI mode.
     */
    int getUiMode();

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
    void dumpDebug(ProtoOutputStream proto, long fieldId);

    /**
     * Notifies the keyguard to start fading out.
     *
     * @param startTime the start time of the animation in uptime milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    void startKeyguardExitAnimation(long startTime, long fadeoutDuration);

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
     * Registers an IDisplayFoldListener.
     */
    default void registerDisplayFoldListener(IDisplayFoldListener listener) {}

    /**
     * Unregisters an IDisplayFoldListener.
     */
    default void unregisterDisplayFoldListener(IDisplayFoldListener listener) {}

    /**
     * Overrides the folded area.
     *
     * @param area the overriding folded area or an empty {@code Rect} to clear the override.
     */
    default void setOverrideFoldedArea(@NonNull Rect area) {}

    /**
     * Get the display folded area.
     */
    default @NonNull Rect getFoldedArea() {
        return new Rect();
    }

    /**
     * A new window on default display has been focused.
     */
    default void onDefaultDisplayFocusChangedLw(WindowState newFocus) {}
}
