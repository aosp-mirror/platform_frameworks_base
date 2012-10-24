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

import android.app.Presentation;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;


/**
 * The interface that apps use to talk to the window manager.
 * <p>
 * Use <code>Context.getSystemService(Context.WINDOW_SERVICE)</code> to get one of these.
 * </p><p>
 * Each window manager instance is bound to a particular {@link Display}.
 * To obtain a {@link WindowManager} for a different display, use
 * {@link Context#createDisplayContext} to obtain a {@link Context} for that
 * display, then use <code>Context.getSystemService(Context.WINDOW_SERVICE)</code>
 * to get the WindowManager.
 * </p><p>
 * The simplest way to show a window on another display is to create a
 * {@link Presentation}.  The presentation will automatically obtain a
 * {@link WindowManager} and {@link Context} for that display.
 * </p>
 *
 * @see android.content.Context#getSystemService
 * @see android.content.Context#WINDOW_SERVICE
 */
public interface WindowManager extends ViewManager {
    /**
     * Exception that is thrown when trying to add view whose
     * {@link WindowManager.LayoutParams} {@link WindowManager.LayoutParams#token}
     * is invalid.
     */
    public static class BadTokenException extends RuntimeException {
        public BadTokenException() {
        }

        public BadTokenException(String name) {
            super(name);
        }
    }

    /**
     * Exception that is thrown when calling {@link #addView} to a secondary display that cannot
     * be found. See {@link android.app.Presentation} for more information on secondary displays.
     */
    public static class InvalidDisplayException extends RuntimeException {
        public InvalidDisplayException() {
        }

        public InvalidDisplayException(String name) {
            super(name);
        }
    }

    /**
     * Returns the {@link Display} upon which this {@link WindowManager} instance
     * will create new windows.
     * <p>
     * Despite the name of this method, the display that is returned is not
     * necessarily the primary display of the system (see {@link Display#DEFAULT_DISPLAY}).
     * The returned display could instead be a secondary display that this
     * window manager instance is managing.  Think of it as the display that
     * this {@link WindowManager} instance uses by default.
     * </p><p>
     * To create windows on a different display, you need to obtain a
     * {@link WindowManager} for that {@link Display}.  (See the {@link WindowManager}
     * class documentation for more information.)
     * </p>
     *
     * @return The display that this window manager is managing.
     */
    public Display getDefaultDisplay();

    /**
     * Special variation of {@link #removeView} that immediately invokes
     * the given view hierarchy's {@link View#onDetachedFromWindow()
     * View.onDetachedFromWindow()} methods before returning.  This is not
     * for normal applications; using it correctly requires great care.
     * 
     * @param view The view to be removed.
     */
    public void removeViewImmediate(View view);

    public static class LayoutParams extends ViewGroup.LayoutParams
            implements Parcelable {
        /**
         * X position for this window.  With the default gravity it is ignored.
         * When using {@link Gravity#LEFT} or {@link Gravity#START} or {@link Gravity#RIGHT} or
         * {@link Gravity#END} it provides an offset from the given edge.
         */
        @ViewDebug.ExportedProperty
        public int x;
        
        /**
         * Y position for this window.  With the default gravity it is ignored.
         * When using {@link Gravity#TOP} or {@link Gravity#BOTTOM} it provides
         * an offset from the given edge.
         */
        @ViewDebug.ExportedProperty
        public int y;

        /**
         * Indicates how much of the extra space will be allocated horizontally
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
        @ViewDebug.ExportedProperty
        public float horizontalWeight;

        /**
         * Indicates how much of the extra space will be allocated vertically
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
        @ViewDebug.ExportedProperty
        public float verticalWeight;

        /**
         * The general type of window.  There are three main classes of
         * window types:
         * <ul>
         * <li> <strong>Application windows</strong> (ranging from
         * {@link #FIRST_APPLICATION_WINDOW} to
         * {@link #LAST_APPLICATION_WINDOW}) are normal top-level application
         * windows.  For these types of windows, the {@link #token} must be
         * set to the token of the activity they are a part of (this will
         * normally be done for you if {@link #token} is null).
         * <li> <strong>Sub-windows</strong> (ranging from
         * {@link #FIRST_SUB_WINDOW} to
         * {@link #LAST_SUB_WINDOW}) are associated with another top-level
         * window.  For these types of windows, the {@link #token} must be
         * the token of the window it is attached to.
         * <li> <strong>System windows</strong> (ranging from
         * {@link #FIRST_SYSTEM_WINDOW} to
         * {@link #LAST_SYSTEM_WINDOW}) are special types of windows for
         * use by the system for specific purposes.  They should not normally
         * be used by applications, and a special permission is required
         * to use them.
         * </ul>
         * 
         * @see #TYPE_BASE_APPLICATION
         * @see #TYPE_APPLICATION
         * @see #TYPE_APPLICATION_STARTING
         * @see #TYPE_APPLICATION_PANEL
         * @see #TYPE_APPLICATION_MEDIA
         * @see #TYPE_APPLICATION_SUB_PANEL
         * @see #TYPE_APPLICATION_ATTACHED_DIALOG
         * @see #TYPE_STATUS_BAR
         * @see #TYPE_SEARCH_BAR
         * @see #TYPE_PHONE
         * @see #TYPE_SYSTEM_ALERT
         * @see #TYPE_KEYGUARD
         * @see #TYPE_TOAST
         * @see #TYPE_SYSTEM_OVERLAY
         * @see #TYPE_PRIORITY_PHONE
         * @see #TYPE_STATUS_BAR_PANEL
         * @see #TYPE_SYSTEM_DIALOG
         * @see #TYPE_KEYGUARD_DIALOG
         * @see #TYPE_SYSTEM_ERROR
         * @see #TYPE_INPUT_METHOD
         * @see #TYPE_INPUT_METHOD_DIALOG
         */
        @ViewDebug.ExportedProperty(mapping = {
            @ViewDebug.IntToString(from = TYPE_BASE_APPLICATION, to = "TYPE_BASE_APPLICATION"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION, to = "TYPE_APPLICATION"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_STARTING, to = "TYPE_APPLICATION_STARTING"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_PANEL, to = "TYPE_APPLICATION_PANEL"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_MEDIA, to = "TYPE_APPLICATION_MEDIA"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_SUB_PANEL, to = "TYPE_APPLICATION_SUB_PANEL"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_ATTACHED_DIALOG, to = "TYPE_APPLICATION_ATTACHED_DIALOG"),
            @ViewDebug.IntToString(from = TYPE_APPLICATION_MEDIA_OVERLAY, to = "TYPE_APPLICATION_MEDIA_OVERLAY"),
            @ViewDebug.IntToString(from = TYPE_STATUS_BAR, to = "TYPE_STATUS_BAR"),
            @ViewDebug.IntToString(from = TYPE_SEARCH_BAR, to = "TYPE_SEARCH_BAR"),
            @ViewDebug.IntToString(from = TYPE_PHONE, to = "TYPE_PHONE"),
            @ViewDebug.IntToString(from = TYPE_SYSTEM_ALERT, to = "TYPE_SYSTEM_ALERT"),
            @ViewDebug.IntToString(from = TYPE_KEYGUARD, to = "TYPE_KEYGUARD"),
            @ViewDebug.IntToString(from = TYPE_TOAST, to = "TYPE_TOAST"),
            @ViewDebug.IntToString(from = TYPE_SYSTEM_OVERLAY, to = "TYPE_SYSTEM_OVERLAY"),
            @ViewDebug.IntToString(from = TYPE_PRIORITY_PHONE, to = "TYPE_PRIORITY_PHONE"),
            @ViewDebug.IntToString(from = TYPE_SYSTEM_DIALOG, to = "TYPE_SYSTEM_DIALOG"),
            @ViewDebug.IntToString(from = TYPE_KEYGUARD_DIALOG, to = "TYPE_KEYGUARD_DIALOG"),
            @ViewDebug.IntToString(from = TYPE_SYSTEM_ERROR, to = "TYPE_SYSTEM_ERROR"),
            @ViewDebug.IntToString(from = TYPE_INPUT_METHOD, to = "TYPE_INPUT_METHOD"),
            @ViewDebug.IntToString(from = TYPE_INPUT_METHOD_DIALOG, to = "TYPE_INPUT_METHOD_DIALOG"),
            @ViewDebug.IntToString(from = TYPE_WALLPAPER, to = "TYPE_WALLPAPER"),
            @ViewDebug.IntToString(from = TYPE_STATUS_BAR_PANEL, to = "TYPE_STATUS_BAR_PANEL"),
            @ViewDebug.IntToString(from = TYPE_SECURE_SYSTEM_OVERLAY, to = "TYPE_SECURE_SYSTEM_OVERLAY"),
            @ViewDebug.IntToString(from = TYPE_DRAG, to = "TYPE_DRAG"),
            @ViewDebug.IntToString(from = TYPE_STATUS_BAR_SUB_PANEL, to = "TYPE_STATUS_BAR_SUB_PANEL"),
            @ViewDebug.IntToString(from = TYPE_POINTER, to = "TYPE_POINTER"),
            @ViewDebug.IntToString(from = TYPE_NAVIGATION_BAR, to = "TYPE_NAVIGATION_BAR"),
            @ViewDebug.IntToString(from = TYPE_VOLUME_OVERLAY, to = "TYPE_VOLUME_OVERLAY"),
            @ViewDebug.IntToString(from = TYPE_BOOT_PROGRESS, to = "TYPE_BOOT_PROGRESS"),
            @ViewDebug.IntToString(from = TYPE_HIDDEN_NAV_CONSUMER, to = "TYPE_HIDDEN_NAV_CONSUMER"),
            @ViewDebug.IntToString(from = TYPE_DREAM, to = "TYPE_DREAM"),
            @ViewDebug.IntToString(from = TYPE_NAVIGATION_BAR_PANEL, to = "TYPE_NAVIGATION_BAR_PANEL"),
            @ViewDebug.IntToString(from = TYPE_DISPLAY_OVERLAY, to = "TYPE_DISPLAY_OVERLAY"),
            @ViewDebug.IntToString(from = TYPE_MAGNIFICATION_OVERLAY, to = "TYPE_MAGNIFICATION_OVERLAY")
        })
        public int type;
    
        /**
         * Start of window types that represent normal application windows.
         */
        public static final int FIRST_APPLICATION_WINDOW = 1;
        
        /**
         * Window type: an application window that serves as the "base" window
         * of the overall application; all other application windows will
         * appear on top of it.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_BASE_APPLICATION   = 1;
        
        /**
         * Window type: a normal application window.  The {@link #token} must be
         * an Activity token identifying who the window belongs to.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_APPLICATION        = 2;
    
        /**
         * Window type: special application window that is displayed while the
         * application is starting.  Not for use by applications themselves;
         * this is used by the system to display something until the
         * application can show its own windows.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_APPLICATION_STARTING = 3;
    
        /**
         * End of types of application windows.
         */
        public static final int LAST_APPLICATION_WINDOW = 99;
    
        /**
         * Start of types of sub-windows.  The {@link #token} of these windows
         * must be set to the window they are attached to.  These types of
         * windows are kept next to their attached window in Z-order, and their
         * coordinate space is relative to their attached window.
         */
        public static final int FIRST_SUB_WINDOW        = 1000;
    
        /**
         * Window type: a panel on top of an application window.  These windows
         * appear on top of their attached window.
         */
        public static final int TYPE_APPLICATION_PANEL  = FIRST_SUB_WINDOW;
    
        /**
         * Window type: window for showing media (e.g. video).  These windows
         * are displayed behind their attached window.
         */
        public static final int TYPE_APPLICATION_MEDIA  = FIRST_SUB_WINDOW+1;
    
        /**
         * Window type: a sub-panel on top of an application window.  These
         * windows are displayed on top their attached window and any
         * {@link #TYPE_APPLICATION_PANEL} panels.
         */
        public static final int TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW+2;

        /** Window type: like {@link #TYPE_APPLICATION_PANEL}, but layout
         * of the window happens as that of a top-level window, <em>not</em>
         * as a child of its container.
         */
        public static final int TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW+3;
        
        /**
         * Window type: window for showing overlays on top of media windows.
         * These windows are displayed between TYPE_APPLICATION_MEDIA and the
         * application window.  They should be translucent to be useful.  This
         * is a big ugly hack so:
         * @hide
         */
        public static final int TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW+4;
    
        /**
         * End of types of sub-windows.
         */
        public static final int LAST_SUB_WINDOW         = 1999;
        
        /**
         * Start of system-specific window types.  These are not normally
         * created by applications.
         */
        public static final int FIRST_SYSTEM_WINDOW     = 2000;
    
        /**
         * Window type: the status bar.  There can be only one status bar
         * window; it is placed at the top of the screen, and all other
         * windows are shifted down so they are below it.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW;
    
        /**
         * Window type: the search bar.  There can be only one search bar
         * window; it is placed at the top of the screen.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1;
    
        /**
         * Window type: phone.  These are non-application windows providing
         * user interaction with the phone (in particular incoming calls).
         * These windows are normally placed above all applications, but behind
         * the status bar.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2;
    
        /**
         * Window type: system window, such as low power alert. These windows
         * are always on top of application windows.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3;
        
        /**
         * Window type: keyguard window.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4;
        
        /**
         * Window type: transient notifications.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5;
        
        /**
         * Window type: system overlay windows, which need to be displayed
         * on top of everything else.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6;
        
        /**
         * Window type: priority phone UI, which needs to be displayed even if
         * the keyguard is active.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7;
        
        /**
         * Window type: panel that slides out from the status bar
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8;
    
        /**
         * Window type: dialogs that the keyguard shows
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9;
        
        /**
         * Window type: internal system error windows, appear on top of
         * everything they can.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10;
        
        /**
         * Window type: internal input methods windows, which appear above
         * the normal UI.  Application windows may be resized or panned to keep
         * the input focus visible while this window is displayed.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11;

        /**
         * Window type: internal input methods dialog windows, which appear above
         * the current input method window.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12;

        /**
         * Window type: wallpaper window, placed behind any window that wants
         * to sit on top of the wallpaper.
         * In multiuser systems shows only on the owning user's window.
         */
        public static final int TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13;

        /**
         * Window type: panel that slides out from over the status bar
         * In multiuser systems shows on all users' windows.
         */
        public static final int TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+14;

        /**
         * Window type: secure system overlay windows, which need to be displayed
         * on top of everything else.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         *
         * This is exactly like {@link #TYPE_SYSTEM_OVERLAY} except that only the
         * system itself is allowed to create these overlays.  Applications cannot
         * obtain permission to create secure system overlays.
         *
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_SECURE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+15;

        /**
         * Window type: the drag-and-drop pseudowindow.  There is only one
         * drag layer (at most), and it is placed on top of all other windows.
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_DRAG               = FIRST_SYSTEM_WINDOW+16;

        /**
         * Window type: panel that slides out from under the status bar
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_STATUS_BAR_SUB_PANEL = FIRST_SYSTEM_WINDOW+17;

        /**
         * Window type: (mouse) pointer
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_POINTER = FIRST_SYSTEM_WINDOW+18;

        /**
         * Window type: Navigation bar (when distinct from status bar)
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_NAVIGATION_BAR = FIRST_SYSTEM_WINDOW+19;

        /**
         * Window type: The volume level overlay/dialog shown when the user
         * changes the system volume.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_VOLUME_OVERLAY = FIRST_SYSTEM_WINDOW+20;

        /**
         * Window type: The boot progress dialog, goes on top of everything
         * in the world.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_BOOT_PROGRESS = FIRST_SYSTEM_WINDOW+21;

        /**
         * Window type: Fake window to consume touch events when the navigation
         * bar is hidden.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_HIDDEN_NAV_CONSUMER = FIRST_SYSTEM_WINDOW+22;

        /**
         * Window type: Dreams (screen saver) window, just above keyguard.
         * In multiuser systems shows only on the owning user's window.
         * @hide
         */
        public static final int TYPE_DREAM = FIRST_SYSTEM_WINDOW+23;

        /**
         * Window type: Navigation bar panel (when navigation bar is distinct from status bar)
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_NAVIGATION_BAR_PANEL = FIRST_SYSTEM_WINDOW+24;

        /**
         * Window type: Behind the universe of the real windows.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_UNIVERSE_BACKGROUND = FIRST_SYSTEM_WINDOW+25;

        /**
         * Window type: Display overlay window.  Used to simulate secondary display devices.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_DISPLAY_OVERLAY = FIRST_SYSTEM_WINDOW+26;

        /**
         * Window type: Magnification overlay window. Used to highlight the magnified
         * portion of a display when accessibility magnification is enabled.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_MAGNIFICATION_OVERLAY = FIRST_SYSTEM_WINDOW+27;

        /**
         * Window type: Recents. Same layer as {@link #TYPE_SYSTEM_DIALOG} but only appears on
         * one user's screen.
         * In multiuser systems shows on all users' windows.
         * @hide
         */
        public static final int TYPE_RECENTS_OVERLAY = FIRST_SYSTEM_WINDOW+28;

        /**
         * End of types of system windows.
         */
        public static final int LAST_SYSTEM_WINDOW      = 2999;

        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_NORMAL = 0;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_HARDWARE = 1;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_GPU = 2;
        /** @deprecated this is ignored, this value is set automatically when needed. */
        @Deprecated
        public static final int MEMORY_TYPE_PUSH_BUFFERS = 3;
        
        /**
         * @deprecated this is ignored
         */
        @Deprecated
        public int memoryType;
        
        /** Window flag: as long as this window is visible to the user, allow
         *  the lock screen to activate while the screen is on. 
         *  This can be used independently, or in combination with 
         *  {@link #FLAG_KEEP_SCREEN_ON} and/or {@link #FLAG_SHOW_WHEN_LOCKED} */
        public static final int FLAG_ALLOW_LOCK_WHILE_SCREEN_ON     = 0x00000001;

        /** Window flag: everything behind this window will be dimmed.
         *  Use {@link #dimAmount} to control the amount of dim. */
        public static final int FLAG_DIM_BEHIND        = 0x00000002;

        /** Window flag: blur everything behind this window.
         * @deprecated Blurring is no longer supported. */
        @Deprecated
        public static final int FLAG_BLUR_BEHIND        = 0x00000004;

        /** Window flag: this window won't ever get key input focus, so the
         * user can not send key or other button events to it.  Those will
         * instead go to whatever focusable window is behind it.  This flag
         * will also enable {@link #FLAG_NOT_TOUCH_MODAL} whether or not that
         * is explicitly set.
         * 
         * <p>Setting this flag also implies that the window will not need to
         * interact with
         * a soft input method, so it will be Z-ordered and positioned 
         * independently of any active input method (typically this means it
         * gets Z-ordered on top of the input method, so it can use the full
         * screen for its content and cover the input method if needed.  You
         * can use {@link #FLAG_ALT_FOCUSABLE_IM} to modify this behavior. */
        public static final int FLAG_NOT_FOCUSABLE      = 0x00000008;
        
        /** Window flag: this window can never receive touch events. */
        public static final int FLAG_NOT_TOUCHABLE      = 0x00000010;
        
        /** Window flag: Even when this window is focusable (its
         * {@link #FLAG_NOT_FOCUSABLE is not set), allow any pointer events
         * outside of the window to be sent to the windows behind it.  Otherwise
         * it will consume all pointer events itself, regardless of whether they
         * are inside of the window. */
        public static final int FLAG_NOT_TOUCH_MODAL    = 0x00000020;
        
        /** Window flag: When set, if the device is asleep when the touch
         * screen is pressed, you will receive this first touch event.  Usually
         * the first touch event is consumed by the system since the user can
         * not see what they are pressing on.
         */
        public static final int FLAG_TOUCHABLE_WHEN_WAKING = 0x00000040;
        
        /** Window flag: as long as this window is visible to the user, keep
         *  the device's screen turned on and bright. */
        public static final int FLAG_KEEP_SCREEN_ON     = 0x00000080;
        
        /** Window flag: place the window within the entire screen, ignoring
         *  decorations around the border (a.k.a. the status bar).  The
         *  window must correctly position its contents to take the screen
         *  decoration into account.  This flag is normally set for you
         *  by Window as described in {@link Window#setFlags}. */
        public static final int FLAG_LAYOUT_IN_SCREEN   = 0x00000100;
        
        /** Window flag: allow window to extend outside of the screen. */
        public static final int FLAG_LAYOUT_NO_LIMITS   = 0x00000200;
        
        /** Window flag: Hide all screen decorations (e.g. status bar) while
         * this window is displayed.  This allows the window to use the entire
         * display space for itself -- the status bar will be hidden when
         * an app window with this flag set is on the top layer. */
        public static final int FLAG_FULLSCREEN      = 0x00000400;
        
        /** Window flag: Override {@link #FLAG_FULLSCREEN and force the
         *  screen decorations (such as status bar) to be shown. */
        public static final int FLAG_FORCE_NOT_FULLSCREEN   = 0x00000800;
        
        /** Window flag: turn on dithering when compositing this window to
         *  the screen.
         * @deprecated This flag is no longer used. */
        @Deprecated
        public static final int FLAG_DITHER             = 0x00001000;
        
        /** Window flag: Treat the content of the window as secure, preventing
         * it from appearing in screenshots or from being viewed on non-secure
         * displays.
         *
         * <p>See {@link android.view.Display#FLAG_SECURE} for more details about
         * secure surfaces and secure displays.
         */
        public static final int FLAG_SECURE             = 0x00002000;
        
        /** Window flag: a special mode where the layout parameters are used
         * to perform scaling of the surface when it is composited to the
         * screen. */
        public static final int FLAG_SCALED             = 0x00004000;
        
        /** Window flag: intended for windows that will often be used when the user is
         * holding the screen against their face, it will aggressively filter the event
         * stream to prevent unintended presses in this situation that may not be
         * desired for a particular window, when such an event stream is detected, the 
         * application will receive a CANCEL motion event to indicate this so applications
         * can handle this accordingly by taking no action on the event 
         * until the finger is released. */
        public static final int FLAG_IGNORE_CHEEK_PRESSES    = 0x00008000;
        
        /** Window flag: a special option only for use in combination with
         * {@link #FLAG_LAYOUT_IN_SCREEN}.  When requesting layout in the
         * screen your window may appear on top of or behind screen decorations
         * such as the status bar.  By also including this flag, the window
         * manager will report the inset rectangle needed to ensure your
         * content is not covered by screen decorations.  This flag is normally
         * set for you by Window as described in {@link Window#setFlags}.*/
        public static final int FLAG_LAYOUT_INSET_DECOR = 0x00010000;
        
        /** Window flag: invert the state of {@link #FLAG_NOT_FOCUSABLE} with
         * respect to how this window interacts with the current method.  That
         * is, if FLAG_NOT_FOCUSABLE is set and this flag is set, then the
         * window will behave as if it needs to interact with the input method
         * and thus be placed behind/away from it; if FLAG_NOT_FOCUSABLE is
         * not set and this flag is set, then the window will behave as if it
         * doesn't need to interact with the input method and can be placed
         * to use more space and cover the input method.
         */
        public static final int FLAG_ALT_FOCUSABLE_IM = 0x00020000;
        
        /** Window flag: if you have set {@link #FLAG_NOT_TOUCH_MODAL}, you
         * can set this flag to receive a single special MotionEvent with
         * the action
         * {@link MotionEvent#ACTION_OUTSIDE MotionEvent.ACTION_OUTSIDE} for
         * touches that occur outside of your window.  Note that you will not
         * receive the full down/move/up gesture, only the location of the
         * first down as an ACTION_OUTSIDE.
         */
        public static final int FLAG_WATCH_OUTSIDE_TOUCH = 0x00040000;
        
        /** Window flag: special flag to let windows be shown when the screen
         * is locked. This will let application windows take precedence over
         * key guard or any other lock screens. Can be used with
         * {@link #FLAG_KEEP_SCREEN_ON} to turn screen on and display windows
         * directly before showing the key guard window.  Can be used with
         * {@link #FLAG_DISMISS_KEYGUARD} to automatically fully dismisss
         * non-secure keyguards.  This flag only applies to the top-most
         * full-screen window.
         */
        public static final int FLAG_SHOW_WHEN_LOCKED = 0x00080000;

        /** Window flag: ask that the system wallpaper be shown behind
         * your window.  The window surface must be translucent to be able
         * to actually see the wallpaper behind it; this flag just ensures
         * that the wallpaper surface will be there if this window actually
         * has translucent regions.
         */
        public static final int FLAG_SHOW_WALLPAPER = 0x00100000;
        
        /** Window flag: when set as a window is being added or made
         * visible, once the window has been shown then the system will
         * poke the power manager's user activity (as if the user had woken
         * up the device) to turn the screen on. */
        public static final int FLAG_TURN_SCREEN_ON = 0x00200000;
        
        /** Window flag: when set the window will cause the keyguard to
         * be dismissed, only if it is not a secure lock keyguard.  Because such
         * a keyguard is not needed for security, it will never re-appear if
         * the user navigates to another window (in contrast to
         * {@link #FLAG_SHOW_WHEN_LOCKED}, which will only temporarily
         * hide both secure and non-secure keyguards but ensure they reappear
         * when the user moves to another UI that doesn't hide them).
         * If the keyguard is currently active and is secure (requires an
         * unlock pattern) than the user will still need to confirm it before
         * seeing this window, unless {@link #FLAG_SHOW_WHEN_LOCKED} has
         * also been set.
         */
        public static final int FLAG_DISMISS_KEYGUARD = 0x00400000;
        
        /** Window flag: when set the window will accept for touch events
         * outside of its bounds to be sent to other windows that also
         * support split touch.  When this flag is not set, the first pointer
         * that goes down determines the window to which all subsequent touches
         * go until all pointers go up.  When this flag is set, each pointer
         * (not necessarily the first) that goes down determines the window
         * to which all subsequent touches of that pointer will go until that
         * pointer goes up thereby enabling touches with multiple pointers
         * to be split across multiple windows.
         */
        public static final int FLAG_SPLIT_TOUCH = 0x00800000;
        
        /**
         * <p>Indicates whether this window should be hardware accelerated.
         * Requesting hardware acceleration does not guarantee it will happen.</p>
         * 
         * <p>This flag can be controlled programmatically <em>only</em> to enable
         * hardware acceleration. To enable hardware acceleration for a given
         * window programmatically, do the following:</p>
         * 
         * <pre>
         * Window w = activity.getWindow(); // in Activity's onCreate() for instance
         * w.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
         *         WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
         * </pre>
         * 
         * <p>It is important to remember that this flag <strong>must</strong>
         * be set before setting the content view of your activity or dialog.</p>
         * 
         * <p>This flag cannot be used to disable hardware acceleration after it
         * was enabled in your manifest using
         * {@link android.R.attr#hardwareAccelerated}. If you need to selectively
         * and programmatically disable hardware acceleration (for automated testing
         * for instance), make sure it is turned off in your manifest and enable it
         * on your activity or dialog when you need it instead, using the method
         * described above.</p>
         * 
         * <p>This flag is automatically set by the system if the
         * {@link android.R.attr#hardwareAccelerated android:hardwareAccelerated}
         * XML attribute is set to true on an activity or on the application.</p>
         */
        public static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;

        // ----- HIDDEN FLAGS.
        // These start at the high bit and go down.

        /** Window flag: Enable touches to slide out of a window into neighboring
         * windows in mid-gesture instead of being captured for the duration of
         * the gesture.
         *
         * This flag changes the behavior of touch focus for this window only.
         * Touches can slide out of the window but they cannot necessarily slide
         * back in (unless the other window with touch focus permits it).
         *
         * {@hide}
         */
        public static final int FLAG_SLIPPERY = 0x04000000;

        /**
         * Flag for a window belonging to an activity that responds to {@link KeyEvent#KEYCODE_MENU}
         * and therefore needs a Menu key. For devices where Menu is a physical button this flag is
         * ignored, but on devices where the Menu key is drawn in software it may be hidden unless
         * this flag is set.
         *
         * (Note that Action Bars, when available, are the preferred way to offer additional
         * functions otherwise accessed via an options menu.)
         *
         * {@hide}
         */
        public static final int FLAG_NEEDS_MENU_KEY = 0x08000000;

        /** Window flag: special flag to limit the size of the window to be
         * original size ([320x480] x density). Used to create window for applications
         * running under compatibility mode.
         *
         * {@hide} */
        public static final int FLAG_COMPATIBLE_WINDOW = 0x20000000;

        /** Window flag: a special option intended for system dialogs.  When
         * this flag is set, the window will demand focus unconditionally when
         * it is created.
         * {@hide} */
        public static final int FLAG_SYSTEM_ERROR = 0x40000000;

        /**
         * Various behavioral options/flags.  Default is none.
         * 
         * @see #FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
         * @see #FLAG_DIM_BEHIND
         * @see #FLAG_NOT_FOCUSABLE
         * @see #FLAG_NOT_TOUCHABLE
         * @see #FLAG_NOT_TOUCH_MODAL
         * @see #FLAG_TOUCHABLE_WHEN_WAKING
         * @see #FLAG_KEEP_SCREEN_ON
         * @see #FLAG_LAYOUT_IN_SCREEN
         * @see #FLAG_LAYOUT_NO_LIMITS
         * @see #FLAG_FULLSCREEN
         * @see #FLAG_FORCE_NOT_FULLSCREEN
         * @see #FLAG_SECURE
         * @see #FLAG_SCALED
         * @see #FLAG_IGNORE_CHEEK_PRESSES
         * @see #FLAG_LAYOUT_INSET_DECOR
         * @see #FLAG_ALT_FOCUSABLE_IM
         * @see #FLAG_WATCH_OUTSIDE_TOUCH
         * @see #FLAG_SHOW_WHEN_LOCKED
         * @see #FLAG_SHOW_WALLPAPER
         * @see #FLAG_TURN_SCREEN_ON
         * @see #FLAG_DISMISS_KEYGUARD
         * @see #FLAG_SPLIT_TOUCH
         * @see #FLAG_HARDWARE_ACCELERATED
         */
        @ViewDebug.ExportedProperty(flagMapping = {
            @ViewDebug.FlagToString(mask = FLAG_ALLOW_LOCK_WHILE_SCREEN_ON, equals = FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
                    name = "FLAG_ALLOW_LOCK_WHILE_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_DIM_BEHIND, equals = FLAG_DIM_BEHIND,
                    name = "FLAG_DIM_BEHIND"),
            @ViewDebug.FlagToString(mask = FLAG_BLUR_BEHIND, equals = FLAG_BLUR_BEHIND,
                    name = "FLAG_BLUR_BEHIND"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_FOCUSABLE, equals = FLAG_NOT_FOCUSABLE,
                    name = "FLAG_NOT_FOCUSABLE"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_TOUCHABLE, equals = FLAG_NOT_TOUCHABLE,
                    name = "FLAG_NOT_TOUCHABLE"),
            @ViewDebug.FlagToString(mask = FLAG_NOT_TOUCH_MODAL, equals = FLAG_NOT_TOUCH_MODAL,
                    name = "FLAG_NOT_TOUCH_MODAL"),
            @ViewDebug.FlagToString(mask = FLAG_TOUCHABLE_WHEN_WAKING, equals = FLAG_TOUCHABLE_WHEN_WAKING,
                    name = "FLAG_TOUCHABLE_WHEN_WAKING"),
            @ViewDebug.FlagToString(mask = FLAG_KEEP_SCREEN_ON, equals = FLAG_KEEP_SCREEN_ON,
                    name = "FLAG_KEEP_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_IN_SCREEN, equals = FLAG_LAYOUT_IN_SCREEN,
                    name = "FLAG_LAYOUT_IN_SCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_NO_LIMITS, equals = FLAG_LAYOUT_NO_LIMITS,
                    name = "FLAG_LAYOUT_NO_LIMITS"),
            @ViewDebug.FlagToString(mask = FLAG_FULLSCREEN, equals = FLAG_FULLSCREEN,
                    name = "FLAG_FULLSCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_FORCE_NOT_FULLSCREEN, equals = FLAG_FORCE_NOT_FULLSCREEN,
                    name = "FLAG_FORCE_NOT_FULLSCREEN"),
            @ViewDebug.FlagToString(mask = FLAG_DITHER, equals = FLAG_DITHER,
                    name = "FLAG_DITHER"),
            @ViewDebug.FlagToString(mask = FLAG_SECURE, equals = FLAG_SECURE,
                    name = "FLAG_SECURE"),
            @ViewDebug.FlagToString(mask = FLAG_SCALED, equals = FLAG_SCALED,
                    name = "FLAG_SCALED"),
            @ViewDebug.FlagToString(mask = FLAG_IGNORE_CHEEK_PRESSES, equals = FLAG_IGNORE_CHEEK_PRESSES,
                    name = "FLAG_IGNORE_CHEEK_PRESSES"),
            @ViewDebug.FlagToString(mask = FLAG_LAYOUT_INSET_DECOR, equals = FLAG_LAYOUT_INSET_DECOR,
                    name = "FLAG_LAYOUT_INSET_DECOR"),
            @ViewDebug.FlagToString(mask = FLAG_ALT_FOCUSABLE_IM, equals = FLAG_ALT_FOCUSABLE_IM,
                    name = "FLAG_ALT_FOCUSABLE_IM"),
            @ViewDebug.FlagToString(mask = FLAG_WATCH_OUTSIDE_TOUCH, equals = FLAG_WATCH_OUTSIDE_TOUCH,
                    name = "FLAG_WATCH_OUTSIDE_TOUCH"),
            @ViewDebug.FlagToString(mask = FLAG_SHOW_WHEN_LOCKED, equals = FLAG_SHOW_WHEN_LOCKED,
                    name = "FLAG_SHOW_WHEN_LOCKED"),
            @ViewDebug.FlagToString(mask = FLAG_SHOW_WALLPAPER, equals = FLAG_SHOW_WALLPAPER,
                    name = "FLAG_SHOW_WALLPAPER"),
            @ViewDebug.FlagToString(mask = FLAG_TURN_SCREEN_ON, equals = FLAG_TURN_SCREEN_ON,
                    name = "FLAG_TURN_SCREEN_ON"),
            @ViewDebug.FlagToString(mask = FLAG_DISMISS_KEYGUARD, equals = FLAG_DISMISS_KEYGUARD,
                    name = "FLAG_DISMISS_KEYGUARD"),
            @ViewDebug.FlagToString(mask = FLAG_SPLIT_TOUCH, equals = FLAG_SPLIT_TOUCH,
                    name = "FLAG_SPLIT_TOUCH"),
            @ViewDebug.FlagToString(mask = FLAG_HARDWARE_ACCELERATED, equals = FLAG_HARDWARE_ACCELERATED,
                    name = "FLAG_HARDWARE_ACCELERATED")
        })
        public int flags;

        /**
         * If the window has requested hardware acceleration, but this is not
         * allowed in the process it is in, then still render it as if it is
         * hardware accelerated.  This is used for the starting preview windows
         * in the system process, which don't need to have the overhead of
         * hardware acceleration (they are just a static rendering), but should
         * be rendered as such to match the actual window of the app even if it
         * is hardware accelerated.
         * Even if the window isn't hardware accelerated, still do its rendering
         * as if it was.
         * Like {@link #FLAG_HARDWARE_ACCELERATED} except for trusted system windows
         * that need hardware acceleration (e.g. LockScreen), where hardware acceleration
         * is generally disabled. This flag must be specified in addition to 
         * {@link #FLAG_HARDWARE_ACCELERATED} to enable hardware acceleration for system
         * windows.
         * 
         * @hide
         */
        public static final int PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED = 0x00000001;

        /**
         * In the system process, we globally do not use hardware acceleration
         * because there are many threads doing UI there and they conflict.
         * If certain parts of the UI that really do want to use hardware
         * acceleration, this flag can be set to force it.  This is basically
         * for the lock screen.  Anyone else using it, you are probably wrong.
         * 
         * @hide
         */
        public static final int PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED = 0x00000002;

        /**
         * By default, wallpapers are sent new offsets when the wallpaper is scrolled. Wallpapers
         * may elect to skip these notifications if they are not doing anything productive with
         * them (they do not affect the wallpaper scrolling operation) by calling
         * {@link
         * android.service.wallpaper.WallpaperService.Engine#setOffsetNotificationsEnabled(boolean)}.
         *
         * @hide
         */
        public static final int PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS = 0x00000004;

        /**
         * This is set for a window that has explicitly specified its
         * FLAG_NEEDS_MENU_KEY, so we know the value on this window is the
         * appropriate one to use.  If this is not set, we should look at
         * windows behind it to determine the appropriate value.
         *
         * @hide
         */
        public static final int PRIVATE_FLAG_SET_NEEDS_MENU_KEY = 0x00000008;

        /** In a multiuser system if this flag is set and the owner is a system process then this
         * window will appear on all user screens. This overrides the default behavior of window
         * types that normally only appear on the owning user's screen. Refer to each window type
         * to determine its default behavior.
         *
         * {@hide} */
        public static final int PRIVATE_FLAG_SHOW_FOR_ALL_USERS = 0x00000010;

        /**
         * Control flags that are private to the platform.
         * @hide
         */
        public int privateFlags;

        /**
         * Given a particular set of window manager flags, determine whether
         * such a window may be a target for an input method when it has
         * focus.  In particular, this checks the
         * {@link #FLAG_NOT_FOCUSABLE} and {@link #FLAG_ALT_FOCUSABLE_IM}
         * flags and returns true if the combination of the two corresponds
         * to a window that needs to be behind the input method so that the
         * user can type into it.
         * 
         * @param flags The current window manager flags.
         * 
         * @return Returns true if such a window should be behind/interact
         * with an input method, false if not.
         */
        public static boolean mayUseInputMethod(int flags) {
            switch (flags&(FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM)) {
                case 0:
                case FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM:
                    return true;
            }
            return false;
        }
        
        /**
         * Mask for {@link #softInputMode} of the bits that determine the
         * desired visibility state of the soft input area for this window.
         */
        public static final int SOFT_INPUT_MASK_STATE = 0x0f;
        
        /**
         * Visibility state for {@link #softInputMode}: no state has been specified.
         */
        public static final int SOFT_INPUT_STATE_UNSPECIFIED = 0;
        
        /**
         * Visibility state for {@link #softInputMode}: please don't change the state of
         * the soft input area.
         */
        public static final int SOFT_INPUT_STATE_UNCHANGED = 1;
        
        /**
         * Visibility state for {@link #softInputMode}: please hide any soft input
         * area when normally appropriate (when the user is navigating
         * forward to your window).
         */
        public static final int SOFT_INPUT_STATE_HIDDEN = 2;
        
        /**
         * Visibility state for {@link #softInputMode}: please always hide any
         * soft input area when this window receives focus.
         */
        public static final int SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3;
        
        /**
         * Visibility state for {@link #softInputMode}: please show the soft
         * input area when normally appropriate (when the user is navigating
         * forward to your window).
         */
        public static final int SOFT_INPUT_STATE_VISIBLE = 4;
        
        /**
         * Visibility state for {@link #softInputMode}: please always make the
         * soft input area visible when this window receives input focus.
         */
        public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 5;
        
        /**
         * Mask for {@link #softInputMode} of the bits that determine the
         * way that the window should be adjusted to accommodate the soft
         * input window.
         */
        public static final int SOFT_INPUT_MASK_ADJUST = 0xf0;
        
        /** Adjustment option for {@link #softInputMode}: nothing specified.
         * The system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_UNSPECIFIED = 0x00;
        
        /** Adjustment option for {@link #softInputMode}: set to allow the
         * window to be resized when an input
         * method is shown, so that its contents are not covered by the input
         * method.  This can <em>not</em> be combined with
         * {@link #SOFT_INPUT_ADJUST_PAN}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10;
        
        /** Adjustment option for {@link #softInputMode}: set to have a window
         * pan when an input method is
         * shown, so it doesn't need to deal with resizing but just panned
         * by the framework to ensure the current input focus is visible.  This
         * can <em>not</em> be combined with {@link #SOFT_INPUT_ADJUST_RESIZE}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_PAN = 0x20;
        
        /** Adjustment option for {@link #softInputMode}: set to have a window
         * not adjust for a shown input method.  The window will not be resized,
         * and it will not be panned to make its focus visible.
         */
        public static final int SOFT_INPUT_ADJUST_NOTHING = 0x30;

        /**
         * Bit for {@link #softInputMode}: set when the user has navigated
         * forward to the window.  This is normally set automatically for
         * you by the system, though you may want to set it in certain cases
         * when you are displaying a window yourself.  This flag will always
         * be cleared automatically after the window is displayed.
         */
        public static final int SOFT_INPUT_IS_FORWARD_NAVIGATION = 0x100;

        /**
         * Desired operating mode for any soft input area.  May be any combination
         * of:
         * 
         * <ul>
         * <li> One of the visibility states
         * {@link #SOFT_INPUT_STATE_UNSPECIFIED}, {@link #SOFT_INPUT_STATE_UNCHANGED},
         * {@link #SOFT_INPUT_STATE_HIDDEN}, {@link #SOFT_INPUT_STATE_ALWAYS_VISIBLE}, or
         * {@link #SOFT_INPUT_STATE_VISIBLE}.
         * <li> One of the adjustment options
         * {@link #SOFT_INPUT_ADJUST_UNSPECIFIED},
         * {@link #SOFT_INPUT_ADJUST_RESIZE}, or
         * {@link #SOFT_INPUT_ADJUST_PAN}.
         */
        public int softInputMode;
        
        /**
         * Placement of window within the screen as per {@link Gravity}.  Both
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} and
         * {@link Gravity#applyDisplay(int, android.graphics.Rect, android.graphics.Rect)
         * Gravity.applyDisplay} are used during window layout, with this value
         * given as the desired gravity.  For example you can specify
         * {@link Gravity#DISPLAY_CLIP_HORIZONTAL Gravity.DISPLAY_CLIP_HORIZONTAL} and
         * {@link Gravity#DISPLAY_CLIP_VERTICAL Gravity.DISPLAY_CLIP_VERTICAL} here
         * to control the behavior of
         * {@link Gravity#applyDisplay(int, android.graphics.Rect, android.graphics.Rect)
         * Gravity.applyDisplay}.
         *
         * @see Gravity
         */
        public int gravity;
    
        /**
         * The horizontal margin, as a percentage of the container's width,
         * between the container and the widget.  See
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} for how this is used.  This
         * field is added with {@link #x} to supply the <var>xAdj</var> parameter.
         */
        public float horizontalMargin;
    
        /**
         * The vertical margin, as a percentage of the container's height,
         * between the container and the widget.  See
         * {@link Gravity#apply(int, int, int, android.graphics.Rect, int, int,
         * android.graphics.Rect) Gravity.apply} for how this is used.  This
         * field is added with {@link #y} to supply the <var>yAdj</var> parameter.
         */
        public float verticalMargin;
    
        /**
         * The desired bitmap format.  May be one of the constants in
         * {@link android.graphics.PixelFormat}.  Default is OPAQUE.
         */
        public int format;
    
        /**
         * A style resource defining the animations to use for this window.
         * This must be a system resource; it can not be an application resource
         * because the window manager does not have access to applications.
         */
        public int windowAnimations;
    
        /**
         * An alpha value to apply to this entire window.
         * An alpha of 1.0 means fully opaque and 0.0 means fully transparent
         */
        public float alpha = 1.0f;
    
        /**
         * When {@link #FLAG_DIM_BEHIND} is set, this is the amount of dimming
         * to apply.  Range is from 1.0 for completely opaque to 0.0 for no
         * dim.
         */
        public float dimAmount = 1.0f;

        /**
         * Default value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the brightness value is not overridden for this window
         * and normal brightness policy should be used.
         */
        public static final float BRIGHTNESS_OVERRIDE_NONE = -1.0f;

        /**
         * Value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the screen or button backlight brightness should be set
         * to the lowest value when this window is in front.
         */
        public static final float BRIGHTNESS_OVERRIDE_OFF = 0.0f;

        /**
         * Value for {@link #screenBrightness} and {@link #buttonBrightness}
         * indicating that the screen or button backlight brightness should be set
         * to the hightest value when this window is in front.
         */
        public static final float BRIGHTNESS_OVERRIDE_FULL = 1.0f;
    
        /**
         * This can be used to override the user's preferred brightness of
         * the screen.  A value of less than 0, the default, means to use the
         * preferred screen brightness.  0 to 1 adjusts the brightness from
         * dark to full bright.
         */
        public float screenBrightness = BRIGHTNESS_OVERRIDE_NONE;
        
        /**
         * This can be used to override the standard behavior of the button and
         * keyboard backlights.  A value of less than 0, the default, means to
         * use the standard backlight behavior.  0 to 1 adjusts the brightness
         * from dark to full bright.
         */
        public float buttonBrightness = BRIGHTNESS_OVERRIDE_NONE;

        /**
         * Identifier for this window.  This will usually be filled in for
         * you.
         */
        public IBinder token = null;
    
        /**
         * Name of the package owning this window.
         */
        public String packageName = null;
        
        /**
         * Specific orientation value for a window.
         * May be any of the same values allowed
         * for {@link android.content.pm.ActivityInfo#screenOrientation}. 
         * If not set, a default value of 
         * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} 
         * will be used.
         */
        public int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

        /**
         * Control the visibility of the status bar.
         *
         * @see View#STATUS_BAR_VISIBLE
         * @see View#STATUS_BAR_HIDDEN
         */
        public int systemUiVisibility;

        /**
         * @hide
         * The ui visibility as requested by the views in this hierarchy.
         * the combined value should be systemUiVisibility | subtreeSystemUiVisibility.
         */
        public int subtreeSystemUiVisibility;

        /**
         * Get callbacks about the system ui visibility changing.
         * 
         * TODO: Maybe there should be a bitfield of optional callbacks that we need.
         *
         * @hide
         */
        public boolean hasSystemUiListeners;

        /**
         * When this window has focus, disable touch pad pointer gesture processing.
         * The window will receive raw position updates from the touch pad instead
         * of pointer movements and synthetic touch events.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_DISABLE_POINTER_GESTURES = 0x00000001;

        /**
         * Does not construct an input channel for this window.  The channel will therefore
         * be incapable of receiving input.
         *
         * @hide
         */
        public static final int INPUT_FEATURE_NO_INPUT_CHANNEL = 0x00000002;

        /**
         * When this window has focus, does not call user activity for all input events so
         * the application will have to do it itself.  Should only be used by
         * the keyguard and phone app.
         * <p>
         * Should only be used by the keyguard and phone app.
         * </p>
         *
         * @hide
         */
        public static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 0x00000004;

        /**
         * Control special features of the input subsystem.
         *
         * @see #INPUT_FEATURE_DISABLE_TOUCH_PAD_GESTURES
         * @see #INPUT_FEATURE_NO_INPUT_CHANNEL
         * @see #INPUT_FEATURE_DISABLE_USER_ACTIVITY
         * @hide
         */
        public int inputFeatures;

        /**
         * Sets the number of milliseconds before the user activity timeout occurs
         * when this window has focus.  A value of -1 uses the standard timeout.
         * A value of 0 uses the minimum support display timeout.
         * <p>
         * This property can only be used to reduce the user specified display timeout;
         * it can never make the timeout longer than it normally would be.
         * </p><p>
         * Should only be used by the keyguard and phone app.
         * </p>
         *
         * @hide
         */
        public long userActivityTimeout = -1;

        public LayoutParams() {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = TYPE_APPLICATION;
            format = PixelFormat.OPAQUE;
        }
        
        public LayoutParams(int _type) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            format = PixelFormat.OPAQUE;
        }
    
        public LayoutParams(int _type, int _flags) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            flags = _flags;
            format = PixelFormat.OPAQUE;
        }
    
        public LayoutParams(int _type, int _flags, int _format) {
            super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
            type = _type;
            flags = _flags;
            format = _format;
        }
        
        public LayoutParams(int w, int h, int _type, int _flags, int _format) {
            super(w, h);
            type = _type;
            flags = _flags;
            format = _format;
        }
        
        public LayoutParams(int w, int h, int xpos, int ypos, int _type,
                int _flags, int _format) {
            super(w, h);
            x = xpos;
            y = ypos;
            type = _type;
            flags = _flags;
            format = _format;
        }
    
        public final void setTitle(CharSequence title) {
            if (null == title)
                title = "";
    
            mTitle = TextUtils.stringOrSpannedString(title);
        }
    
        public final CharSequence getTitle() {
            return mTitle;
        }
    
        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel out, int parcelableFlags) {
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(type);
            out.writeInt(flags);
            out.writeInt(privateFlags);
            out.writeInt(softInputMode);
            out.writeInt(gravity);
            out.writeFloat(horizontalMargin);
            out.writeFloat(verticalMargin);
            out.writeInt(format);
            out.writeInt(windowAnimations);
            out.writeFloat(alpha);
            out.writeFloat(dimAmount);
            out.writeFloat(screenBrightness);
            out.writeFloat(buttonBrightness);
            out.writeStrongBinder(token);
            out.writeString(packageName);
            TextUtils.writeToParcel(mTitle, out, parcelableFlags);
            out.writeInt(screenOrientation);
            out.writeInt(systemUiVisibility);
            out.writeInt(subtreeSystemUiVisibility);
            out.writeInt(hasSystemUiListeners ? 1 : 0);
            out.writeInt(inputFeatures);
            out.writeLong(userActivityTimeout);
        }
        
        public static final Parcelable.Creator<LayoutParams> CREATOR
                    = new Parcelable.Creator<LayoutParams>() {
            public LayoutParams createFromParcel(Parcel in) {
                return new LayoutParams(in);
            }
    
            public LayoutParams[] newArray(int size) {
                return new LayoutParams[size];
            }
        };
    
    
        public LayoutParams(Parcel in) {
            width = in.readInt();
            height = in.readInt();
            x = in.readInt();
            y = in.readInt();
            type = in.readInt();
            flags = in.readInt();
            privateFlags = in.readInt();
            softInputMode = in.readInt();
            gravity = in.readInt();
            horizontalMargin = in.readFloat();
            verticalMargin = in.readFloat();
            format = in.readInt();
            windowAnimations = in.readInt();
            alpha = in.readFloat();
            dimAmount = in.readFloat();
            screenBrightness = in.readFloat();
            buttonBrightness = in.readFloat();
            token = in.readStrongBinder();
            packageName = in.readString();
            mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            screenOrientation = in.readInt();
            systemUiVisibility = in.readInt();
            subtreeSystemUiVisibility = in.readInt();
            hasSystemUiListeners = in.readInt() != 0;
            inputFeatures = in.readInt();
            userActivityTimeout = in.readLong();
        }
    
        @SuppressWarnings({"PointlessBitwiseExpression"})
        public static final int LAYOUT_CHANGED = 1<<0;
        public static final int TYPE_CHANGED = 1<<1;
        public static final int FLAGS_CHANGED = 1<<2;
        public static final int FORMAT_CHANGED = 1<<3;
        public static final int ANIMATION_CHANGED = 1<<4;
        public static final int DIM_AMOUNT_CHANGED = 1<<5;
        public static final int TITLE_CHANGED = 1<<6;
        public static final int ALPHA_CHANGED = 1<<7;
        public static final int MEMORY_TYPE_CHANGED = 1<<8;
        public static final int SOFT_INPUT_MODE_CHANGED = 1<<9;
        public static final int SCREEN_ORIENTATION_CHANGED = 1<<10;
        public static final int SCREEN_BRIGHTNESS_CHANGED = 1<<11;
        /** {@hide} */
        public static final int BUTTON_BRIGHTNESS_CHANGED = 1<<12;
        /** {@hide} */
        public static final int SYSTEM_UI_VISIBILITY_CHANGED = 1<<13;
        /** {@hide} */
        public static final int SYSTEM_UI_LISTENER_CHANGED = 1<<14;
        /** {@hide} */
        public static final int INPUT_FEATURES_CHANGED = 1<<15;
        /** {@hide} */
        public static final int PRIVATE_FLAGS_CHANGED = 1<<16;
        /** {@hide} */
        public static final int USER_ACTIVITY_TIMEOUT_CHANGED = 1<<17;
        /** {@hide} */
        public static final int EVERYTHING_CHANGED = 0xffffffff;

        // internal buffer to backup/restore parameters under compatibility mode.
        private int[] mCompatibilityParamsBackup = null;
        
        public final int copyFrom(LayoutParams o) {
            int changes = 0;
    
            if (width != o.width) {
                width = o.width;
                changes |= LAYOUT_CHANGED;
            }
            if (height != o.height) {
                height = o.height;
                changes |= LAYOUT_CHANGED;
            }
            if (x != o.x) {
                x = o.x;
                changes |= LAYOUT_CHANGED;
            }
            if (y != o.y) {
                y = o.y;
                changes |= LAYOUT_CHANGED;
            }
            if (horizontalWeight != o.horizontalWeight) {
                horizontalWeight = o.horizontalWeight;
                changes |= LAYOUT_CHANGED;
            }
            if (verticalWeight != o.verticalWeight) {
                verticalWeight = o.verticalWeight;
                changes |= LAYOUT_CHANGED;
            }
            if (horizontalMargin != o.horizontalMargin) {
                horizontalMargin = o.horizontalMargin;
                changes |= LAYOUT_CHANGED;
            }
            if (verticalMargin != o.verticalMargin) {
                verticalMargin = o.verticalMargin;
                changes |= LAYOUT_CHANGED;
            }
            if (type != o.type) {
                type = o.type;
                changes |= TYPE_CHANGED;
            }
            if (flags != o.flags) {
                flags = o.flags;
                changes |= FLAGS_CHANGED;
            }
            if (privateFlags != o.privateFlags) {
                privateFlags = o.privateFlags;
                changes |= PRIVATE_FLAGS_CHANGED;
            }
            if (softInputMode != o.softInputMode) {
                softInputMode = o.softInputMode;
                changes |= SOFT_INPUT_MODE_CHANGED;
            }
            if (gravity != o.gravity) {
                gravity = o.gravity;
                changes |= LAYOUT_CHANGED;
            }
            if (format != o.format) {
                format = o.format;
                changes |= FORMAT_CHANGED;
            }
            if (windowAnimations != o.windowAnimations) {
                windowAnimations = o.windowAnimations;
                changes |= ANIMATION_CHANGED;
            }
            if (token == null) {
                // NOTE: token only copied if the recipient doesn't
                // already have one.
                token = o.token;
            }
            if (packageName == null) {
                // NOTE: packageName only copied if the recipient doesn't
                // already have one.
                packageName = o.packageName;
            }
            if (!mTitle.equals(o.mTitle)) {
                mTitle = o.mTitle;
                changes |= TITLE_CHANGED;
            }
            if (alpha != o.alpha) {
                alpha = o.alpha;
                changes |= ALPHA_CHANGED;
            }
            if (dimAmount != o.dimAmount) {
                dimAmount = o.dimAmount;
                changes |= DIM_AMOUNT_CHANGED;
            }
            if (screenBrightness != o.screenBrightness) {
                screenBrightness = o.screenBrightness;
                changes |= SCREEN_BRIGHTNESS_CHANGED;
            }
            if (buttonBrightness != o.buttonBrightness) {
                buttonBrightness = o.buttonBrightness;
                changes |= BUTTON_BRIGHTNESS_CHANGED;
            }
    
            if (screenOrientation != o.screenOrientation) {
                screenOrientation = o.screenOrientation;
                changes |= SCREEN_ORIENTATION_CHANGED;
            }

            if (systemUiVisibility != o.systemUiVisibility
                    || subtreeSystemUiVisibility != o.subtreeSystemUiVisibility) {
                systemUiVisibility = o.systemUiVisibility;
                subtreeSystemUiVisibility = o.subtreeSystemUiVisibility;
                changes |= SYSTEM_UI_VISIBILITY_CHANGED;
            }

            if (hasSystemUiListeners != o.hasSystemUiListeners) {
                hasSystemUiListeners = o.hasSystemUiListeners;
                changes |= SYSTEM_UI_LISTENER_CHANGED;
            }

            if (inputFeatures != o.inputFeatures) {
                inputFeatures = o.inputFeatures;
                changes |= INPUT_FEATURES_CHANGED;
            }

            if (userActivityTimeout != o.userActivityTimeout) {
                userActivityTimeout = o.userActivityTimeout;
                changes |= USER_ACTIVITY_TIMEOUT_CHANGED;
            }

            return changes;
        }
    
        @Override
        public String debug(String output) {
            output += "Contents of " + this + ":";
            Log.d("Debug", output);
            output = super.debug("");
            Log.d("Debug", output);
            Log.d("Debug", "");
            Log.d("Debug", "WindowManager.LayoutParams={title=" + mTitle + "}");
            return "";
        }
    
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("WM.LayoutParams{");
            sb.append("(");
            sb.append(x);
            sb.append(',');
            sb.append(y);
            sb.append(")(");
            sb.append((width== MATCH_PARENT ?"fill":(width==WRAP_CONTENT?"wrap":width)));
            sb.append('x');
            sb.append((height== MATCH_PARENT ?"fill":(height==WRAP_CONTENT?"wrap":height)));
            sb.append(")");
            if (horizontalMargin != 0) {
                sb.append(" hm=");
                sb.append(horizontalMargin);
            }
            if (verticalMargin != 0) {
                sb.append(" vm=");
                sb.append(verticalMargin);
            }
            if (gravity != 0) {
                sb.append(" gr=#");
                sb.append(Integer.toHexString(gravity));
            }
            if (softInputMode != 0) {
                sb.append(" sim=#");
                sb.append(Integer.toHexString(softInputMode));
            }
            sb.append(" ty=");
            sb.append(type);
            sb.append(" fl=#");
            sb.append(Integer.toHexString(flags));
            if (privateFlags != 0) {
                sb.append(" pfl=0x").append(Integer.toHexString(privateFlags));
            }
            if (format != PixelFormat.OPAQUE) {
                sb.append(" fmt=");
                sb.append(format);
            }
            if (windowAnimations != 0) {
                sb.append(" wanim=0x");
                sb.append(Integer.toHexString(windowAnimations));
            }
            if (screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                sb.append(" or=");
                sb.append(screenOrientation);
            }
            if (alpha != 1.0f) {
                sb.append(" alpha=");
                sb.append(alpha);
            }
            if (screenBrightness != BRIGHTNESS_OVERRIDE_NONE) {
                sb.append(" sbrt=");
                sb.append(screenBrightness);
            }
            if (buttonBrightness != BRIGHTNESS_OVERRIDE_NONE) {
                sb.append(" bbrt=");
                sb.append(buttonBrightness);
            }
            if ((flags & FLAG_COMPATIBLE_WINDOW) != 0) {
                sb.append(" compatible=true");
            }
            if (systemUiVisibility != 0) {
                sb.append(" sysui=0x");
                sb.append(Integer.toHexString(systemUiVisibility));
            }
            if (subtreeSystemUiVisibility != 0) {
                sb.append(" vsysui=0x");
                sb.append(Integer.toHexString(subtreeSystemUiVisibility));
            }
            if (hasSystemUiListeners) {
                sb.append(" sysuil=");
                sb.append(hasSystemUiListeners);
            }
            if (inputFeatures != 0) {
                sb.append(" if=0x").append(Integer.toHexString(inputFeatures));
            }
            if (userActivityTimeout >= 0) {
                sb.append(" userActivityTimeout=").append(userActivityTimeout);
            }
            sb.append('}');
            return sb.toString();
        }

        /**
         * Scale the layout params' coordinates and size.
         * @hide
         */
        public void scale(float scale) {
            x = (int) (x * scale + 0.5f);
            y = (int) (y * scale + 0.5f);
            if (width > 0) {
                width = (int) (width * scale + 0.5f);
            }
            if (height > 0) {
                height = (int) (height * scale + 0.5f);
            }
        }

        /**
         * Backup the layout parameters used in compatibility mode.
         * @see LayoutParams#restore()
         */
        void backup() {
            int[] backup = mCompatibilityParamsBackup;
            if (backup == null) {
                // we backup 4 elements, x, y, width, height
                backup = mCompatibilityParamsBackup = new int[4];
            }
            backup[0] = x;
            backup[1] = y;
            backup[2] = width;
            backup[3] = height;
        }

        /**
         * Restore the layout params' coordinates, size and gravity
         * @see LayoutParams#backup()
         */
        void restore() {
            int[] backup = mCompatibilityParamsBackup;
            if (backup != null) {
                x = backup[0];
                y = backup[1];
                width = backup[2];
                height = backup[3];
            }
        }

        private CharSequence mTitle = "";
    }
}
