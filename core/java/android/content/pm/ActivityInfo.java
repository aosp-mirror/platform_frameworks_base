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

import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Printer;

/**
 * Information you can retrieve about a particular application
 * activity or receiver. This corresponds to information collected
 * from the AndroidManifest.xml's &lt;activity&gt; and
 * &lt;receiver&gt; tags.
 */
public class ActivityInfo extends ComponentInfo
        implements Parcelable {
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
     * Value for {@link #flags}: true when the application can be displayed over the lockscreen
     * and consequently over all users' windows.
     * @hide
     */
    public static final int FLAG_SHOW_ON_LOCK_SCREEN = 0x0400;
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
     * @hide Bit in {@link #flags}: If set, this component will only be seen
     * by the primary user.  Only works with broadcast receivers.  Set from the
     * {@link android.R.attr#primaryUserOnly} attribute.
     */
    public static final int FLAG_PRIMARY_USER_ONLY = 0x20000000;
    /**
     * Bit in {@link #flags}: If set, a single instance of the receiver will
     * run for all users on the device.  Set from the
     * {@link android.R.attr#singleUser} attribute.  Note that this flag is
     * only relevant for ActivityInfo structures that are describing receiver
     * components; it is not applied to activities.
     */
    public static final int FLAG_SINGLE_USER = 0x40000000;
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
    public int screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
    
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
    /** @hide
     * Convert Java change bits to native.
     */
    public static int activityInfoConfigToNative(int input) {
        int output = 0;
        for (int i=0; i<CONFIG_NATIVE_BITS.length; i++) {
            if ((input&(1<<i)) != 0) {
                output |= CONFIG_NATIVE_BITS[i];
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
     * {@link #CONFIG_ORIENTATION}, {@link #CONFIG_SCREEN_LAYOUT} and
     * {@link #CONFIG_LAYOUT_DIRECTION}.  Set from the {@link android.R.attr#configChanges}
     * attribute.
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

    public ActivityInfo() {
    }

    public ActivityInfo(ActivityInfo orig) {
        super(orig);
        theme = orig.theme;
        launchMode = orig.launchMode;
        permission = orig.permission;
        taskAffinity = orig.taskAffinity;
        targetActivity = orig.targetActivity;
        flags = orig.flags;
        screenOrientation = orig.screenOrientation;
        configChanges = orig.configChanges;
        softInputMode = orig.softInputMode;
        uiOptions = orig.uiOptions;
        parentActivityName = orig.parentActivityName;
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

    public void dump(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        pw.println(prefix + "taskAffinity=" + taskAffinity
                + " targetActivity=" + targetActivity);
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
        super.dumpBack(pw, prefix);
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
        dest.writeString(permission);
        dest.writeString(taskAffinity);
        dest.writeString(targetActivity);
        dest.writeInt(flags);
        dest.writeInt(screenOrientation);
        dest.writeInt(configChanges);
        dest.writeInt(softInputMode);
        dest.writeInt(uiOptions);
        dest.writeString(parentActivityName);
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
        permission = source.readString();
        taskAffinity = source.readString();
        targetActivity = source.readString();
        flags = source.readInt();
        screenOrientation = source.readInt();
        configChanges = source.readInt();
        softInputMode = source.readInt();
        uiOptions = source.readInt();
        parentActivityName = source.readString();
    }
}
