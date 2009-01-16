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
     * Use this method to get the default Display object.
     * 
     * @return default Display object
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
         * When using {@link Gravity#LEFT} or {@link Gravity#RIGHT} it provides
         * an offset from the given edge.
         */
        public int x;
        
        /**
         * Y position for this window.  With the default gravity it is ignored.
         * When using {@link Gravity#TOP} or {@link Gravity#BOTTOM} it provides
         * an offset from the given edge.
         */
        public int y;

        /**
         * Indicates how much of the extra space will be allocated horizontally
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
        public float horizontalWeight;

        /**
         * Indicates how much of the extra space will be allocated vertically
         * to the view associated with these LayoutParams. Specify 0 if the view
         * should not be stretched. Otherwise the extra pixels will be pro-rated
         * among all views whose weight is greater than 0.
         */
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
         * @see #TYPE_INPUT_METHOD
         * @see #TYPE_INPUT_METHOD_DIALOG
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
        public int type;
    
        /**
         * Start of window types that represent normal application windows.
         */
        public static final int FIRST_APPLICATION_WINDOW = 1;
        
        /**
         * Window type: an application window that serves as the "base" window
         * of the overall application; all other application windows will
         * appear on top of it.
         */
        public static final int TYPE_BASE_APPLICATION   = 1;
        
        /**
         * Window type: a normal application window.  The {@link #token} must be
         * an Activity token identifying who the window belongs to.
         */
        public static final int TYPE_APPLICATION        = 2;
    
        /**
         * Window type: special application window that is displayed while the
         * application is starting.  Not for use by applications themselves;
         * this is used by the system to display something until the
         * application can show its own windows.
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
         */
        public static final int TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW;
    
        /**
         * Window type: the search bar.  There can be only one search bar
         * window; it is placed at the top of the screen.
         */
        public static final int TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1;
    
        /**
         * Window type: phone.  These are non-application windows providing
         * user interaction with the phone (in particular incoming calls).
         * These windows are normally placed above all applications, but behind
         * the status bar.
         */
        public static final int TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2;
    
        /**
         * Window type: system window, such as low power alert. These windows
         * are always on top of application windows.
         */
        public static final int TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3;
        
        /**
         * Window type: keyguard window.
         */
        public static final int TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4;
        
        /**
         * Window type: transient notifications.
         */
        public static final int TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5;
        
        /**
         * Window type: system overlay windows, which need to be displayed
         * on top of everything else.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         */
        public static final int TYPE_SYSTEM_OVERLAY     = FIRST_SYSTEM_WINDOW+6;
        
        /**
         * Window type: priority phone UI, which needs to be displayed even if
         * the keyguard is active.  These windows must not take input
         * focus, or they will interfere with the keyguard.
         */
        public static final int TYPE_PRIORITY_PHONE     = FIRST_SYSTEM_WINDOW+7;
        
        /**
         * Window type: panel that slides out from the status bar
         */
        public static final int TYPE_STATUS_BAR_PANEL   = FIRST_SYSTEM_WINDOW+8;
        
        /**
         * Window type: panel that slides out from the status bar
         */
        public static final int TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8;
    
        /**
         * Window type: dialogs that the keyguard shows
         */
        public static final int TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9;
        
        /**
         * Window type: internal system error windows, appear on top of
         * everything they can.
         */
        public static final int TYPE_SYSTEM_ERROR       = FIRST_SYSTEM_WINDOW+10;
        
        /**
         * Window type: internal input methods windows, which appear above
         * the normal UI.  Application windows may be resized or panned to keep
         * the input focus visible while this window is displayed.
         */
        public static final int TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11;

        /**
         * Window type: internal input methods dialog windows, which appear above
         * the current input method window.
         */
        public static final int TYPE_INPUT_METHOD_DIALOG= FIRST_SYSTEM_WINDOW+12;

        /**
         * End of types of system windows.
         */
        public static final int LAST_SYSTEM_WINDOW      = 2999;
        
        /**
         * Specifies what type of memory buffers should be used by this window.
         * Default is normal.
         * 
         * @see #MEMORY_TYPE_NORMAL
         * @see #MEMORY_TYPE_HARDWARE
         * @see #MEMORY_TYPE_GPU
         * @see #MEMORY_TYPE_PUSH_BUFFERS
         */
        public int memoryType;

        /** Memory type: The window's surface is allocated in main memory. */
        public static final int MEMORY_TYPE_NORMAL = 0;
        /** Memory type: The window's surface is configured to be accessible
         * by DMA engines and hardware accelerators. */
        public static final int MEMORY_TYPE_HARDWARE = 1;
        /** Memory type: The window's surface is configured to be accessible
         * by graphics accelerators. */
        public static final int MEMORY_TYPE_GPU = 2;
        /** Memory type: The window's surface doesn't own its buffers and
         * therefore cannot be locked. Instead the buffers are pushed to
         * it through native binder calls. */
        public static final int MEMORY_TYPE_PUSH_BUFFERS = 3;

        /**
         * Various behavioral options/flags.  Default is none.
         * 
         * @see #FLAG_BLUR_BEHIND
         * @see #FLAG_DIM_BEHIND
         * @see #FLAG_NOT_FOCUSABLE
         * @see #FLAG_NOT_TOUCHABLE
         * @see #FLAG_NOT_TOUCH_MODAL
         * @see #FLAG_LAYOUT_IN_SCREEN
         * @see #FLAG_DITHER
         * @see #FLAG_KEEP_SCREEN_ON
         * @see #FLAG_FULLSCREEN
         * @see #FLAG_FORCE_NOT_FULLSCREEN
         * @see #FLAG_IGNORE_CHEEK_PRESSES
         */
        public int flags;
        
        /** Window flag: everything behind this window will be dimmed.
         *  Use {@link #dimAmount} to control the amount of dim. */
        public static final int FLAG_DIM_BEHIND        = 0x00000002;
        
        /** Window flag: blur everything behind this window. */
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
         *  the screen. */
        public static final int FLAG_DITHER             = 0x00001000;
        
        /** Window flag: don't allow screen shots while this window is
         * displayed. */
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
        
        /** Window flag: a special option intended for system dialogs.  When
         * this flag is set, the window will demand focus unconditionally when
         * it is created.
         * {@hide} */
        public static final int FLAG_SYSTEM_ERROR = 0x40000000;

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
         * area.
         */
        public static final int SOFT_INPUT_STATE_HIDDEN = 2;
        
        /**
         * Visibility state for {@link #softInputMode}: please show the soft
         * input area when normally appropriate (when the user is navigating
         * forward to your window).
         */
        public static final int SOFT_INPUT_STATE_VISIBLE = 3;
        
        /**
         * Visibility state for {@link #softInputMode}: please always make the
         * soft input area visible when this window receives input focus.
         */
        public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 4;
        
        /**
         * Mask for {@link #softInputMode} of the bits that determine the
         * way that the window should be adjusted to accomodate the soft
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
         * method.  This can <em>not<em> be combined with
         * {@link #SOFT_INPUT_ADJUST_PAN}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10;
        
        /** Adjustment option for {@link #softInputMode}: set to have a window
         * pan when an input method is
         * shown, so it doesn't need to deal with resizing but just panned
         * by the framework to ensure the current input focus is visible.  This
         * can <em>not<em> be combined with {@link #SOFT_INPUT_ADJUST_RESIZE}; if
         * neither of these are set, then the system will try to pick one or
         * the other depending on the contents of the window.
         */
        public static final int SOFT_INPUT_ADJUST_PAN = 0x20;
        
        /**
         * Bit for {@link #softInputMode}: set when the user has navigated
         * forward to the window.  This is normally set automatically for
         * you by the system, though you may want to set it in certain cases
         * when you are displaying a window yourself.  This flag will always
         * be cleared automatically after the window is displayed.
         */
        public static final int SOFT_INPUT_IS_FORWARD_NAVIGATION = 0x100;
        
        /**
         * Desired operating mode for any soft input area.  May any combination
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
         * Placement of window within the screen as per {@link Gravity}
         *
         * @see Gravity
         */
        public int gravity;
    
        /**
         * The horizontal margin, as a percentage of the container's width,
         * between the container and the widget.
         */
        public float horizontalMargin;
    
        /**
         * The vertical margin, as a percentage of the container's height,
         * between the container and the widget.
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
         * Identifier for this window.  This will usually be filled in for
         * you.
         */
        public IBinder token = null;
    
        /**
         * Name of the package owning this window.
         */
        public String packageName = null;
        
        public LayoutParams() {
            super(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
            type = TYPE_APPLICATION;
            format = PixelFormat.OPAQUE;
        }
        
        public LayoutParams(int _type) {
            super(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
            type = _type;
            format = PixelFormat.OPAQUE;
        }
    
        public LayoutParams(int _type, int _flags) {
            super(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
            type = _type;
            flags = _flags;
            format = PixelFormat.OPAQUE;
        }
    
        public LayoutParams(int _type, int _flags, int _format) {
            super(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
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
            out.writeInt(memoryType);
            out.writeInt(flags);
            out.writeInt(softInputMode);
            out.writeInt(gravity);
            out.writeFloat(horizontalMargin);
            out.writeFloat(verticalMargin);
            out.writeInt(format);
            out.writeInt(windowAnimations);
            out.writeFloat(alpha);
            out.writeFloat(dimAmount);
            out.writeStrongBinder(token);
            out.writeString(packageName);
            TextUtils.writeToParcel(mTitle, out, parcelableFlags);
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
            memoryType = in.readInt();
            flags = in.readInt();
            softInputMode = in.readInt();
            gravity = in.readInt();
            horizontalMargin = in.readFloat();
            verticalMargin = in.readFloat();
            format = in.readInt();
            windowAnimations = in.readInt();
            alpha = in.readFloat();
            dimAmount = in.readFloat();
            token = in.readStrongBinder();
            packageName = in.readString();
            mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        }
    
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
            if (memoryType != o.memoryType) {
                memoryType = o.memoryType;
                changes |= MEMORY_TYPE_CHANGED;
            }
            if (flags != o.flags) {
                flags = o.flags;
                changes |= FLAGS_CHANGED;
            }
            if (softInputMode != o.softInputMode) {
                softInputMode = o.softInputMode;
                changes |= SOFT_INPUT_MODE_CHANGED;
            }
            if (gravity != o.gravity) {
                gravity = o.gravity;
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
            sb.append((width==FILL_PARENT?"fill":(width==WRAP_CONTENT?"wrap":width)));
            sb.append('x');
            sb.append((height==FILL_PARENT?"fill":(height==WRAP_CONTENT?"wrap":height)));
            sb.append(")");
            if (softInputMode != 0) {
                sb.append(" sim=#");
                sb.append(Integer.toHexString(softInputMode));
            }
            if (gravity != 0) {
                sb.append(" gr=#");
                sb.append(Integer.toHexString(gravity));
            }
            sb.append(" ty=");
            sb.append(type);
            sb.append(" fl=#");
            sb.append(Integer.toHexString(flags));
            sb.append(" fmt=");
            sb.append(format);
            if (windowAnimations != 0) {
                sb.append(" wanim=0x");
                sb.append(Integer.toHexString(windowAnimations));
            }
            sb.append('}');
            return sb.toString();
        }
    
        private CharSequence mTitle = "";
    }
}
