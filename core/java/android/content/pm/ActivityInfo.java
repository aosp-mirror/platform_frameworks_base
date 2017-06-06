/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content.pm;

import android.annotation.IntDef;
import android.content.res.Configuration;
import android.content.res.Configuration.NativeConfig;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Printer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information you can retrieve about a particular application
 * activity or receiver. This corresponds to information collected
 * from the AndroidManifest.xml's &lt;activity&gt; and
 * &lt;receiver&gt; tags.
 */
public class ActivityInfo extends ComponentInfo
        implements Parcelable {

     // NOTE: When adding new data members be sure to update the copy-constructor, Parcel
     // constructor, and writeToParcel.

    /**
     * A style resource identifier (in the package's resources) of this
     * activity's theme.  From the "theme" attribute or, if not set, 0.
     */
    public int theme;

    /**
     * Constant corresponding to <code>standard</code> in
     * the {@link android.R.attr#launchMode} attribute.
     */
    public static final int LAUNCH_MULTIPLE = 0;
    /**
     * Constant corresponding to <code>singleTop</code> in
     * the {@link android.R.attr#launchMode} attribute.
     */
    public static final int LAUNCH_SINGLE_TOP = 1;
    /**
     * Constant corresponding to <code>singleTask</code> in
     * the {@link android.R.attr#launchMode} attribute.
     */
    public static final int LAUNCH_SINGLE_TASK = 2;
    /**
     * Constant corresponding to <code>singleInstance</code> in
     * the {@link android.R.attr#launchMode} attribute.
     */
    public static final int LAUNCH_SINGLE_INSTANCE = 3;
    /**
     * The launch mode style requested by the activity.  From the
     * {@link android.R.attr#launchMode} attribute, one of
     * {@link #LAUNCH_MULTIPLE},
     * {@link #LAUNCH_SINGLE_TOP}, {@link #LAUNCH_SINGLE_TASK}, or
     * {@link #LAUNCH_SINGLE_INSTANCE}.
     */
    public int launchMode;

    /**
     * Constant corresponding to <code>none</code> in
     * the {@link android.R.attr#documentLaunchMode} attribute.
     */
    public static final int DOCUMENT_LAUNCH_NONE = 0;
    /**
     * Constant corresponding to <code>intoExisting</code> in
     * the {@link android.R.attr#documentLaunchMode} attribute.
     */
    public static final int DOCUMENT_LAUNCH_INTO_EXISTING = 1;
    /**
     * Constant corresponding to <code>always</code> in
     * the {@link android.R.attr#documentLaunchMode} attribute.
     */
    public static final int DOCUMENT_LAUNCH_ALWAYS = 2;
    /**
     * Constant corresponding to <code>never</code> in
     * the {@link android.R.attr#documentLaunchMode} attribute.
     */
    public static final int DOCUMENT_LAUNCH_NEVER = 3;
    /**
     * The document launch mode style requested by the activity. From the
     * {@link android.R.attr#documentLaunchMode} attribute, one of
     * {@link #DOCUMENT_LAUNCH_NONE}, {@link #DOCUMENT_LAUNCH_INTO_EXISTING},
     * {@link #DOCUMENT_LAUNCH_ALWAYS}.
     *
     * <p>Modes DOCUMENT_LAUNCH_ALWAYS
     * and DOCUMENT_LAUNCH_INTO_EXISTING are equivalent to {@link
     * android.content.Intent#FLAG_ACTIVITY_NEW_DOCUMENT
     * Intent.FLAG_ACTIVITY_NEW_DOCUMENT} with and without {@link
     * android.content.Intent#FLAG_ACTIVITY_MULTIPLE_TASK
     * Intent.FLAG_ACTIVITY_MULTIPLE_TASK} respectively.
     */
    public int documentLaunchMode;

    /**
     * Constant corresponding to <code>persistRootOnly</code> in
     * the {@link android.R.attr#persistableMode} attribute.
     */
    public static final int PERSIST_ROOT_ONLY = 0;
    /**
     * Constant corresponding to <code>doNotPersist</code> in
     * the {@link android.R.attr#persistableMode} attribute.
     */
    public static final int PERSIST_NEVER = 1;
    /**
     * Constant corresponding to <code>persistAcrossReboots</code> in
     * the {@link android.R.attr#persistableMode} attribute.
     */
    public static final int PERSIST_ACROSS_REBOOTS = 2;
    /**
     * Value indicating how this activity is to be persisted across
     * reboots for restoring in the Recents list.
     * {@link android.R.attr#persistableMode}
     */
    public int persistableMode;

    /**
     * The maximum number of tasks rooted at this activity that can be in the recent task list.
     * Refer to {@link android.R.attr#maxRecents}.
     */
    public int maxRecents;

    /**
     * Optional name of a permission required to be able to access this
     * Activity.  From the "permission" attribute.
     */
    public String permission;

    /**
     * The affinity this activity has for another task in the system.  The
     * string here is the name of the task, often the package name of the
     * overall package.  If null, the activity has no affinity.  Set from the
     * {@link android.R.attr#taskAffinity} attribute.
     */
    public String taskAffinity;

    /**
     * If this is an activity alias, this is the real activity class to run
     * for it.  Otherwise, this is null.
     */
    public String targetActivity;

    /**
     * Activity can not be resized and always occupies the fullscreen area with all windows fully
     * visible.
     * @hide
     */
    public static final int RESIZE_MODE_UNRESIZEABLE = 0;
    /**
     * Activity can not be resized and always occupies the fullscreen area with all windows cropped
     * to either the task or stack bounds.
     * @hide
     */
    public static final int RESIZE_MODE_CROP_WINDOWS = 1;
    /**
     * Activity is resizeable.
     * @hide
     */
    public static final int RESIZE_MODE_RESIZEABLE = 2;
    /**
     * Activity is resizeable and supported picture-in-picture mode.
     * @hide
     */
    public static final int RESIZE_MODE_RESIZEABLE_AND_PIPABLE = 3;
    /**
     * Activity is does not support resizing, but we are forcing it to be resizeable.
     * @hide
     */
    public static final int RESIZE_MODE_FORCE_RESIZEABLE = 4;
    /**
     * Value indicating if the resizing mode the activity supports.
     * See {@link android.R.attr#resizeableActivity}.
     * @hide
     */
    public int resizeMode = RESIZE_MODE_RESIZEABLE;

    /**
     * Name of the VrListenerService component to run for this activity.
     * @see android.R.attr#enableVrMode
     * @hide
     */
    public String requestedVrComponent;

    /**
     * Bit in {@link #flags} indicating whether this activity is able to
     * run in multiple processes.  If
     * true, the system may instantiate it in the some process as the
     * process starting it in order to conserve resources.  If false, the
     * default, it always runs in {@link #processName}.  Set from the
     * {@link android.R.attr#multiprocess} attribute.
     */
    public static final int FLAG_MULTIPROCESS = 0x0001;
    /**
     * Bit in {@link #flags} indicating that, when the activity's task is
     * relaunched from home, this activity should be finished.
     * Set from the
     * {@link android.R.attr#finishOnTaskLaunch} attribute.
     */
    public static final int FLAG_FINISH_ON_TASK_LAUNCH = 0x0002;
    /**
     * Bit in {@link #flags} indicating that, when the activity is the root
     * of a task, that task's stack should be cleared each time the user
     * re-launches it from home.  As a result, the user will always
     * return to the original activity at the top of the task.
     * This flag only applies to activities that
     * are used to start the root of a new task.  Set from the
     * {@link android.R.attr#clearTaskOnLaunch} attribute.
     */
    public static final int FLAG_CLEAR_TASK_ON_LAUNCH = 0x0004;
    /**
     * Bit in {@link #flags} indicating that, when the activity is the root
     * of a task, that task's stack should never be cleared when it is
     * relaunched from home.  Set from the
     * {@link android.R.attr#alwaysRetainTaskState} attribute.
     */
    public static final int FLAG_ALWAYS_RETAIN_TASK_STATE = 0x0008;
    /**
     * Bit in {@link #flags} indicating that the activity's state
     * is not required to be saved, so that if there is a failure the
     * activity will not be removed from the activity stack.  Set from the
     * {@link android.R.attr#stateNotNeeded} attribute.
     */
    public static final int FLAG_STATE_NOT_NEEDED = 0x0010;
    /**
     * Bit in {@link #flags} that indicates that the activity should not
     * appear in the list of recently launched activities.  Set from the
     * {@link android.R.attr#excludeFromRecents} attribute.
     */
    public static final int FLAG_EXCLUDE_FROM_RECENTS = 0x0020;
    /**
     * Bit in {@link #flags} that indicates that the activity can be moved
     * between tasks based on its task affinity.  Set from the
     * {@link android.R.attr#allowTaskReparenting} attribute.
     */
    public static final int FLAG_ALLOW_TASK_REPARENTING = 0x0040;
    /**
     * Bit in {@link #flags} indicating that, when the user navigates away
     * from an activity, it should be finished.
     * Set from the
     * {@link android.R.attr#noHistory} attribute.
     */
    public static final int FLAG_NO_HISTORY = 0x0080;
    /**
     * Bit in {@link #flags} indicating that, when a request to close system
     * windows happens, this activity is finished.
     * Set from the
     * {@link android.R.attr#finishOnCloseSystemDialogs} attribute.
     */
    public static final int FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS = 0x0100;
    /**
     * Value for {@link #flags}: true when the application's rendering should
     * be hardware accelerated.
     */
    public static final int FLAG_HARDWARE_ACCELERATED = 0x0200;
    /**
     * Value for {@link #flags}: true when the application can be displayed for all users
     * regardless of if the user of the application is the current user. Set from the
     * {@link android.R.attr#showForAllUsers} attribute.
     * @hide
     */
    public static final int FLAG_SHOW_FOR_ALL_USERS = 0x0400;
    /**
     * Bit in {@link #flags} corresponding to an immersive activity
     * that wishes not to be interrupted by notifications.
     * Applications that hide the system notification bar with
     * {@link android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN}
     * may still be interrupted by high-priority notifications; for example, an
     * incoming phone call may use
     * {@link android.app.Notification#fullScreenIntent fullScreenIntent}
     * to present a full-screen in-call activity to the user, pausing the
     * current activity as a side-effect. An activity with
     * {@link #FLAG_IMMERSIVE} set, however, will not be interrupted; the
     * notification may be shown in some other way (such as a small floating
     * "toast" window).
     *
     * Note that this flag will always reflect the Activity's
     * <code>android:immersive</code> manifest definition, even if the Activity's
     * immersive state is changed at runtime via
     * {@link android.app.Activity#setImmersive(boolean)}.
     *
     * @see android.app.Notification#FLAG_HIGH_PRIORITY
     * @see android.app.Activity#setImmersive(boolean)
     */
    public static final int FLAG_IMMERSIVE = 0x0800;
    /**
     * Bit in {@link #flags}: If set, a task rooted at this activity will have its
     * baseIntent replaced by the activity immediately above this. Each activity may further
     * relinquish its identity to the activity above it using this flag. Set from the
     * {@link android.R.attr#relinquishTaskIdentity} attribute.
     */
    public static final int FLAG_RELINQUISH_TASK_IDENTITY = 0x1000;
    /**
     * Bit in {@link #flags} indicating that tasks started with this activity are to be
     * removed from the recent list of tasks when the last activity in the task is finished.
     * Corresponds to {@link android.R.attr#autoRemoveFromRecents}
     */
    public static final int FLAG_AUTO_REMOVE_FROM_RECENTS = 0x2000;
    /**
     * Bit in {@link #flags} indicating that this activity can start is creation/resume
     * while the previous activity is still pausing.  Corresponds to
     * {@link android.R.attr#resumeWhilePausing}
     */
    public static final int FLAG_RESUME_WHILE_PAUSING = 0x4000;
    /**
     * Bit in {@link #flags} indicating that this activity should be run with VR mode enabled.
     *
     * {@see android.app.Activity#setVrMode(boolean)}.
     */
    public static final int FLAG_ENABLE_VR_MODE = 0x8000;

    /**
     * Bit in {@link #flags} indicating if the activity is always focusable regardless of if it is
     * in a task/stack whose activities are normally not focusable.
     * See android.R.attr#alwaysFocusable.
     * @hide
     */
    public static final int FLAG_ALWAYS_FOCUSABLE = 0x40000;

    /**
     * @hide Bit in {@link #flags}: If set, this component will only be seen
     * by the system user.  Only works with broadcast receivers.  Set from the
     * android.R.attr#systemUserOnly attribute.
     */
    public static final int FLAG_SYSTEM_USER_ONLY = 0x20000000;
    /**
     * Bit in {@link #flags}: If set, a single instance of the receiver will
     * run for all users on the device.  Set from the
     * {@link android.R.attr#singleUser} attribute.  Note that this flag is
     * only relevant for ActivityInfo structures that are describing receiver
     * components; it is not applied to activities.
     */
    public static final int FLAG_SINGLE_USER = 0x40000000;
    /**
     * @hide Bit in {@link #flags}: If set, this activity may be launched into an
     * owned ActivityContainer such as that within an ActivityView. If not set and
     * this activity is launched into such a container a SecurityException will be
     * thrown. Set from the {@link android.R.attr#allowEmbedded} attribute.
     */
    public static final int FLAG_ALLOW_EMBEDDED = 0x80000000;
    /**
     * Options that have been set in the activity declaration in the
     * manifest.
     * These include:
     * {@link #FLAG_MULTIPROCESS},
     * {@link #FLAG_FINISH_ON_TASK_LAUNCH}, {@link #FLAG_CLEAR_TASK_ON_LAUNCH},
     * {@link #FLAG_ALWAYS_RETAIN_TASK_STATE},
     * {@link #FLAG_STATE_NOT_NEEDED}, {@link #FLAG_EXCLUDE_FROM_RECENTS},
     * {@link #FLAG_ALLOW_TASK_REPARENTING}, {@link #FLAG_NO_HISTORY},
     * {@link #FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS},
     * {@link #FLAG_HARDWARE_ACCELERATED}, {@link #FLAG_SINGLE_USER}.
     */
    public int flags;

    /** @hide */
    @IntDef({
            SCREEN_ORIENTATION_UNSPECIFIED,
            SCREEN_ORIENTATION_LANDSCAPE,
            SCREEN_ORIENTATION_PORTRAIT,
            SCREEN_ORIENTATION_USER,
            SCREEN_ORIENTATION_BEHIND,
            SCREEN_ORIENTATION_SENSOR,
            SCREEN_ORIENTATION_NOSENSOR,
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            SCREEN_ORIENTATION_FULL_SENSOR,
            SCREEN_ORIENTATION_USER_LANDSCAPE,
            SCREEN_ORIENTATION_USER_PORTRAIT,
            SCREEN_ORIENTATION_FULL_USER,
            SCREEN_ORIENTATION_LOCKED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScreenOrientation {}

    /**
     * Constant corresponding to <code>unspecified</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_UNSPECIFIED = -1;
    /**
     * Constant corresponding to <code>landscape</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_LANDSCAPE = 0;
    /**
     * Constant corresponding to <code>portrait</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_PORTRAIT = 1;
    /**
     * Constant corresponding to <code>user</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_USER = 2;
    /**
     * Constant corresponding to <code>behind</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_BEHIND = 3;
    /**
     * Constant corresponding to <code>sensor</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_SENSOR = 4;

    /**
     * Constant corresponding to <code>nosensor</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_NOSENSOR = 5;

    /**
     * Constant corresponding to <code>sensorLandscape</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6;

    /**
     * Constant corresponding to <code>sensorPortrait</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7;

    /**
     * Constant corresponding to <code>reverseLandscape</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;

    /**
     * Constant corresponding to <code>reversePortrait</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;

    /**
     * Constant corresponding to <code>fullSensor</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_FULL_SENSOR = 10;

    /**
     * Constant corresponding to <code>userLandscape</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_USER_LANDSCAPE = 11;

    /**
     * Constant corresponding to <code>userPortrait</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_USER_PORTRAIT = 12;

    /**
     * Constant corresponding to <code>fullUser</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_FULL_USER = 13;

    /**
     * Constant corresponding to <code>locked</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_LOCKED = 14;

    /**
     * The preferred screen orientation this activity would like to run in.
     * From the {@link android.R.attr#screenOrientation} attribute, one of
     * {@link #SCREEN_ORIENTATION_UNSPECIFIED},
     * {@link #SCREEN_ORIENTATION_LANDSCAPE},
     * {@link #SCREEN_ORIENTATION_PORTRAIT},
     * {@link #SCREEN_ORIENTATION_USER},
     * {@link #SCREEN_ORIENTATION_BEHIND},
     * {@link #SCREEN_ORIENTATION_SENSOR},
     * {@link #SCREEN_ORIENTATION_NOSENSOR},
     * {@link #SCREEN_ORIENTATION_SENSOR_LANDSCAPE},
     * {@link #SCREEN_ORIENTATION_SENSOR_PORTRAIT},
     * {@link #SCREEN_ORIENTATION_REVERSE_LANDSCAPE},
     * {@link #SCREEN_ORIENTATION_REVERSE_PORTRAIT},
     * {@link #SCREEN_ORIENTATION_FULL_SENSOR},
     * {@link #SCREEN_ORIENTATION_USER_LANDSCAPE},
     * {@link #SCREEN_ORIENTATION_USER_PORTRAIT},
     * {@link #SCREEN_ORIENTATION_FULL_USER},
     * {@link #SCREEN_ORIENTATION_LOCKED},
     */
    @ScreenOrientation
    public int screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    CONFIG_MCC,
                    CONFIG_MNC,
                    CONFIG_LOCALE,
                    CONFIG_TOUCHSCREEN,
                    CONFIG_KEYBOARD,
                    CONFIG_KEYBOARD_HIDDEN,
                    CONFIG_NAVIGATION,
                    CONFIG_ORIENTATION,
                    CONFIG_SCREEN_LAYOUT,
                    CONFIG_UI_MODE,
                    CONFIG_SCREEN_SIZE,
                    CONFIG_SMALLEST_SCREEN_SIZE,
                    CONFIG_DENSITY,
                    CONFIG_LAYOUT_DIRECTION,
                    CONFIG_FONT_SCALE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Config {}

    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the IMSI MCC.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_MCC = 0x0001;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the IMSI MNC.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_MNC = 0x0002;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the locale.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_LOCALE = 0x0004;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the touchscreen type.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_TOUCHSCREEN = 0x0008;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the keyboard type.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_KEYBOARD = 0x0010;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the keyboard or navigation being hidden/exposed.
     * Note that inspite of the name, this applies to the changes to any
     * hidden states: keyboard or navigation.
     * Set from the {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_KEYBOARD_HIDDEN = 0x0020;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the navigation type.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_NAVIGATION = 0x0040;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the screen orientation.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_ORIENTATION = 0x0080;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the screen layout.  Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_SCREEN_LAYOUT = 0x0100;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle the ui mode. Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_UI_MODE = 0x0200;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle the screen size. Set from the
     * {@link android.R.attr#configChanges} attribute.  This will be
     * set by default for applications that target an earlier version
     * than {@link android.os.Build.VERSION_CODES#HONEYCOMB_MR2}...
     * <b>however</b>, you will not see the bit set here becomes some
     * applications incorrectly compare {@link #configChanges} against
     * an absolute value rather than correctly masking out the bits
     * they are interested in.  Please don't do that, thanks.
     */
    public static final int CONFIG_SCREEN_SIZE = 0x0400;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle the smallest screen size. Set from the
     * {@link android.R.attr#configChanges} attribute.  This will be
     * set by default for applications that target an earlier version
     * than {@link android.os.Build.VERSION_CODES#HONEYCOMB_MR2}...
     * <b>however</b>, you will not see the bit set here becomes some
     * applications incorrectly compare {@link #configChanges} against
     * an absolute value rather than correctly masking out the bits
     * they are interested in.  Please don't do that, thanks.
     */
    public static final int CONFIG_SMALLEST_SCREEN_SIZE = 0x0800;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle density changes. Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_DENSITY = 0x1000;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle the change to layout direction. Set from the
     * {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_LAYOUT_DIRECTION = 0x2000;
    /**
     * Bit in {@link #configChanges} that indicates a font change occurred
     * @hide
     */
    public static final int CONFIG_THEME_FONT = 0x200000;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the font scaling factor.  Set from the
     * {@link android.R.attr#configChanges} attribute.  This is
     * not a core resource configuration, but a higher-level value, so its
     * constant starts at the high bits.
     */
    public static final int CONFIG_FONT_SCALE = 0x40000000;

    /** @hide
     * Unfortunately the constants for config changes in native code are
     * different from ActivityInfo. :(  Here are the values we should use for the
     * native side given the bit we have assigned in ActivityInfo.
     */
    public static int[] CONFIG_NATIVE_BITS = new int[] {
        Configuration.NATIVE_CONFIG_MNC,                    // MNC
        Configuration.NATIVE_CONFIG_MCC,                    // MCC
        Configuration.NATIVE_CONFIG_LOCALE,                 // LOCALE
        Configuration.NATIVE_CONFIG_TOUCHSCREEN,            // TOUCH SCREEN
        Configuration.NATIVE_CONFIG_KEYBOARD,               // KEYBOARD
        Configuration.NATIVE_CONFIG_KEYBOARD_HIDDEN,        // KEYBOARD HIDDEN
        Configuration.NATIVE_CONFIG_NAVIGATION,             // NAVIGATION
        Configuration.NATIVE_CONFIG_ORIENTATION,            // ORIENTATION
        Configuration.NATIVE_CONFIG_SCREEN_LAYOUT,          // SCREEN LAYOUT
        Configuration.NATIVE_CONFIG_UI_MODE,                // UI MODE
        Configuration.NATIVE_CONFIG_SCREEN_SIZE,            // SCREEN SIZE
        Configuration.NATIVE_CONFIG_SMALLEST_SCREEN_SIZE,   // SMALLEST SCREEN SIZE
        Configuration.NATIVE_CONFIG_DENSITY,                // DENSITY
        Configuration.NATIVE_CONFIG_LAYOUTDIR,              // LAYOUT DIRECTION
    };

    /**
     * Convert Java change bits to native.
     *
     * @hide
     */
    public static @NativeConfig int activityInfoConfigJavaToNative(@Config int input) {
        int output = 0;
        for (int i = 0; i < CONFIG_NATIVE_BITS.length; i++) {
            if ((input & (1 << i)) != 0) {
                output |= CONFIG_NATIVE_BITS[i];
            }
        }
        return output;
    }

    /**
     * Convert native change bits to Java.
     *
     * @hide
     */
    public static @Config int activityInfoConfigNativeToJava(@NativeConfig int input) {
        int output = 0;
        for (int i = 0; i < CONFIG_NATIVE_BITS.length; i++) {
            if ((input & CONFIG_NATIVE_BITS[i]) != 0) {
                output |= (1 << i);
            }
        }
        return output;
    }

    /**
     * @hide
     * Unfortunately some developers (OpenFeint I am looking at you) have
     * compared the configChanges bit field against absolute values, so if we
     * introduce a new bit they break.  To deal with that, we will make sure
     * the public field will not have a value that breaks them, and let the
     * framework call here to get the real value.
     */
    public int getRealConfigChanged() {
        return applicationInfo.targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB_MR2
                ? (configChanges | ActivityInfo.CONFIG_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE)
                : configChanges;
    }

    /**
     * Bit mask of kinds of configuration changes that this activity
     * can handle itself (without being restarted by the system).
     * Contains any combination of {@link #CONFIG_FONT_SCALE},
     * {@link #CONFIG_MCC}, {@link #CONFIG_MNC},
     * {@link #CONFIG_LOCALE}, {@link #CONFIG_TOUCHSCREEN},
     * {@link #CONFIG_KEYBOARD}, {@link #CONFIG_NAVIGATION},
     * {@link #CONFIG_ORIENTATION}, {@link #CONFIG_SCREEN_LAYOUT},
     * {@link #CONFIG_DENSITY}, and {@link #CONFIG_LAYOUT_DIRECTION}.
     * Set from the {@link android.R.attr#configChanges} attribute.
     */
    public int configChanges;

    /**
     * The desired soft input mode for this activity's main window.
     * Set from the {@link android.R.attr#windowSoftInputMode} attribute
     * in the activity's manifest.  May be any of the same values allowed
     * for {@link android.view.WindowManager.LayoutParams#softInputMode
     * WindowManager.LayoutParams.softInputMode}.  If 0 (unspecified),
     * the mode from the theme will be used.
     */
    public int softInputMode;

    /**
     * The desired extra UI options for this activity and its main window.
     * Set from the {@link android.R.attr#uiOptions} attribute in the
     * activity's manifest.
     */
    public int uiOptions = 0;

    /**
     * Flag for use with {@link #uiOptions}.
     * Indicates that the action bar should put all action items in a separate bar when
     * the screen is narrow.
     * <p>This value corresponds to "splitActionBarWhenNarrow" for the {@link #uiOptions} XML
     * attribute.
     */
    public static final int UIOPTION_SPLIT_ACTION_BAR_WHEN_NARROW = 1;

    /**
     * If defined, the activity named here is the logical parent of this activity.
     */
    public String parentActivityName;

    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_DEFAULT = 0;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_NEVER = 1;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_ALWAYS = 2;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED = 3;

    /** @hide */
    public static final String lockTaskLaunchModeToString(int lockTaskLaunchMode) {
        switch (lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_DEFAULT:
                return "LOCK_TASK_LAUNCH_MODE_DEFAULT";
            case LOCK_TASK_LAUNCH_MODE_NEVER:
                return "LOCK_TASK_LAUNCH_MODE_NEVER";
            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                return "LOCK_TASK_LAUNCH_MODE_ALWAYS";
            case LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED:
                return "LOCK_TASK_LAUNCH_MODE_IF_WHITELISTED";
            default:
                return "unknown=" + lockTaskLaunchMode;
        }
    }
    /**
     * Value indicating if the activity is to be locked at startup. Takes on the values from
     * {@link android.R.attr#lockTaskMode}.
     * @hide
     */
    public int lockTaskLaunchMode;

    /**
     * Information about desired position and size of activity on the display when
     * it is first started.
     */
    public WindowLayout windowLayout;

    public ActivityInfo() {
    }

    public ActivityInfo(ActivityInfo orig) {
        super(orig);
        theme = orig.theme;
        launchMode = orig.launchMode;
        documentLaunchMode = orig.documentLaunchMode;
        permission = orig.permission;
        taskAffinity = orig.taskAffinity;
        targetActivity = orig.targetActivity;
        flags = orig.flags;
        screenOrientation = orig.screenOrientation;
        configChanges = orig.configChanges;
        softInputMode = orig.softInputMode;
        uiOptions = orig.uiOptions;
        parentActivityName = orig.parentActivityName;
        maxRecents = orig.maxRecents;
        lockTaskLaunchMode = orig.lockTaskLaunchMode;
        windowLayout = orig.windowLayout;
        resizeMode = orig.resizeMode;
        requestedVrComponent = orig.requestedVrComponent;
    }

    /**
     * Return the theme resource identifier to use for this activity.  If
     * the activity defines a theme, that is used; else, the application
     * theme is used.
     *
     * @return The theme associated with this activity.
     */
    public final int getThemeResource() {
        return theme != 0 ? theme : applicationInfo.theme;
    }

    private String persistableModeToString() {
        switch(persistableMode) {
            case PERSIST_ROOT_ONLY: return "PERSIST_ROOT_ONLY";
            case PERSIST_NEVER: return "PERSIST_NEVER";
            case PERSIST_ACROSS_REBOOTS: return "PERSIST_ACROSS_REBOOTS";
            default: return "UNKNOWN=" + persistableMode;
        }
    }

    /**
     * Returns true if the activity's orientation is fixed.
     * @hide
     */
    boolean isFixedOrientation() {
        return screenOrientation == SCREEN_ORIENTATION_LANDSCAPE
                || screenOrientation == SCREEN_ORIENTATION_PORTRAIT
                || screenOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || screenOrientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || screenOrientation == SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || screenOrientation == SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || screenOrientation == SCREEN_ORIENTATION_USER_LANDSCAPE
                || screenOrientation == SCREEN_ORIENTATION_USER_PORTRAIT
                || screenOrientation == SCREEN_ORIENTATION_LOCKED;
    }

    /** @hide */
    public static boolean isResizeableMode(int mode) {
        return mode == RESIZE_MODE_RESIZEABLE
                || mode == RESIZE_MODE_RESIZEABLE_AND_PIPABLE
                || mode == RESIZE_MODE_FORCE_RESIZEABLE;
    }

    /** @hide */
    public static String resizeModeToString(int mode) {
        switch (mode) {
            case RESIZE_MODE_UNRESIZEABLE:
                return "RESIZE_MODE_UNRESIZEABLE";
            case RESIZE_MODE_CROP_WINDOWS:
                return "RESIZE_MODE_CROP_WINDOWS";
            case RESIZE_MODE_RESIZEABLE:
                return "RESIZE_MODE_RESIZEABLE";
            case RESIZE_MODE_RESIZEABLE_AND_PIPABLE:
                return "RESIZE_MODE_RESIZEABLE_AND_PIPABLE";
            case RESIZE_MODE_FORCE_RESIZEABLE:
                return "RESIZE_MODE_FORCE_RESIZEABLE";
            default:
                return "unknown=" + mode;
        }
    }

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int flags) {
        super.dumpFront(pw, prefix);
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        if ((flags&DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "taskAffinity=" + taskAffinity
                    + " targetActivity=" + targetActivity
                    + " persistableMode=" + persistableModeToString());
        }
        if (launchMode != 0 || flags != 0 || theme != 0) {
            pw.println(prefix + "launchMode=" + launchMode
                    + " flags=0x" + Integer.toHexString(flags)
                    + " theme=0x" + Integer.toHexString(theme));
        }
        if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED
                || configChanges != 0 || softInputMode != 0) {
            pw.println(prefix + "screenOrientation=" + screenOrientation
                    + " configChanges=0x" + Integer.toHexString(configChanges)
                    + " softInputMode=0x" + Integer.toHexString(softInputMode));
        }
        if (uiOptions != 0) {
            pw.println(prefix + " uiOptions=0x" + Integer.toHexString(uiOptions));
        }
        if ((flags&DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "lockTaskLaunchMode="
                    + lockTaskLaunchModeToString(lockTaskLaunchMode));
        }
        if (windowLayout != null) {
            pw.println(prefix + "windowLayout=" + windowLayout.width + "|"
                    + windowLayout.widthFraction + ", " + windowLayout.height + "|"
                    + windowLayout.heightFraction + ", " + windowLayout.gravity);
        }
        pw.println(prefix + "resizeMode=" + resizeModeToString(resizeMode));
        if (requestedVrComponent != null) {
            pw.println(prefix + "requestedVrComponent=" + requestedVrComponent);
        }
        super.dumpBack(pw, prefix, flags);
    }

    public String toString() {
        return "ActivityInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeInt(theme);
        dest.writeInt(launchMode);
        dest.writeInt(documentLaunchMode);
        dest.writeString(permission);
        dest.writeString(taskAffinity);
        dest.writeString(targetActivity);
        dest.writeInt(flags);
        dest.writeInt(screenOrientation);
        dest.writeInt(configChanges);
        dest.writeInt(softInputMode);
        dest.writeInt(uiOptions);
        dest.writeString(parentActivityName);
        dest.writeInt(persistableMode);
        dest.writeInt(maxRecents);
        dest.writeInt(lockTaskLaunchMode);
        if (windowLayout != null) {
            dest.writeInt(1);
            dest.writeInt(windowLayout.width);
            dest.writeFloat(windowLayout.widthFraction);
            dest.writeInt(windowLayout.height);
            dest.writeFloat(windowLayout.heightFraction);
            dest.writeInt(windowLayout.gravity);
            dest.writeInt(windowLayout.minWidth);
            dest.writeInt(windowLayout.minHeight);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(resizeMode);
        dest.writeString(requestedVrComponent);
    }

    public static final Parcelable.Creator<ActivityInfo> CREATOR
            = new Parcelable.Creator<ActivityInfo>() {
        public ActivityInfo createFromParcel(Parcel source) {
            return new ActivityInfo(source);
        }
        public ActivityInfo[] newArray(int size) {
            return new ActivityInfo[size];
        }
    };

    private ActivityInfo(Parcel source) {
        super(source);
        theme = source.readInt();
        launchMode = source.readInt();
        documentLaunchMode = source.readInt();
        permission = source.readString();
        taskAffinity = source.readString();
        targetActivity = source.readString();
        flags = source.readInt();
        screenOrientation = source.readInt();
        configChanges = source.readInt();
        softInputMode = source.readInt();
        uiOptions = source.readInt();
        parentActivityName = source.readString();
        persistableMode = source.readInt();
        maxRecents = source.readInt();
        lockTaskLaunchMode = source.readInt();
        if (source.readInt() == 1) {
            windowLayout = new WindowLayout(source);
        }
        resizeMode = source.readInt();
        requestedVrComponent = source.readString();
    }

    /**
     * Contains information about position and size of the activity on the display.
     *
     * Used in freeform mode to set desired position when activity is first launched.
     * It describes how big the activity wants to be in both width and height,
     * the minimal allowed size, and the gravity to be applied.
     *
     * @attr ref android.R.styleable#AndroidManifestLayout_defaultWidth
     * @attr ref android.R.styleable#AndroidManifestLayout_defaultHeight
     * @attr ref android.R.styleable#AndroidManifestLayout_gravity
     * @attr ref android.R.styleable#AndroidManifestLayout_minWidth
     * @attr ref android.R.styleable#AndroidManifestLayout_minHeight
     */
    public static final class WindowLayout {
        public WindowLayout(int width, float widthFraction, int height, float heightFraction, int gravity,
                int minWidth, int minHeight) {
            this.width = width;
            this.widthFraction = widthFraction;
            this.height = height;
            this.heightFraction = heightFraction;
            this.gravity = gravity;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
        }

        WindowLayout(Parcel source) {
            width = source.readInt();
            widthFraction = source.readFloat();
            height = source.readInt();
            heightFraction = source.readFloat();
            gravity = source.readInt();
            minWidth = source.readInt();
            minHeight = source.readInt();
        }

        /**
         * Width of activity in pixels.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_defaultWidth
         */
        public final int width;

        /**
         * Width of activity as a fraction of available display width.
         * If both {@link #width} and this value are set this one will be preferred.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_defaultWidth
         */
        public final float widthFraction;

        /**
         * Height of activity in pixels.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_defaultHeight
         */
        public final int height;

        /**
         * Height of activity as a fraction of available display height.
         * If both {@link #height} and this value are set this one will be preferred.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_defaultHeight
         */
        public final float heightFraction;

        /**
         * Gravity of activity.
         * Currently {@link android.view.Gravity#TOP}, {@link android.view.Gravity#BOTTOM},
         * {@link android.view.Gravity#LEFT} and {@link android.view.Gravity#RIGHT} are supported.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_gravity
         */
        public final int gravity;

        /**
         * Minimal width of activity in pixels to be able to display its content.
         *
         * <p><strong>NOTE:</strong> A task's root activity value is applied to all additional
         * activities launched in the task. That is if the root activity of a task set minimal
         * width, then the system will set the same minimal width on all other activities in the
         * task. It will also ignore any other minimal width attributes of non-root activities.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_minWidth
         */
        public final int minWidth;

        /**
         * Minimal height of activity in pixels to be able to display its content.
         *
         * <p><strong>NOTE:</strong> A task's root activity value is applied to all additional
         * activities launched in the task. That is if the root activity of a task set minimal
         * height, then the system will set the same minimal height on all other activities in the
         * task. It will also ignore any other minimal height attributes of non-root activities.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_minHeight
         */
        public final int minHeight;
    }
}
