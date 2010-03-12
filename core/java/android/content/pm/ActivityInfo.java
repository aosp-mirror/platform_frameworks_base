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
     * Options that have been set in the activity declaration in the
     * manifest: {@link #FLAG_MULTIPROCESS},
     * {@link #FLAG_FINISH_ON_TASK_LAUNCH}, {@link #FLAG_CLEAR_TASK_ON_LAUNCH},
     * {@link #FLAG_ALWAYS_RETAIN_TASK_STATE},
     * {@link #FLAG_STATE_NOT_NEEDED}, {@link #FLAG_EXCLUDE_FROM_RECENTS},
     * {@link #FLAG_ALLOW_TASK_REPARENTING}, {@link #FLAG_NO_HISTORY},
     * {@link #FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS}.
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
     * Constant corresponding to <code>sensor</code> in
     * the {@link android.R.attr#screenOrientation} attribute.
     */
    public static final int SCREEN_ORIENTATION_NOSENSOR = 5;
    /**
     * The preferred screen orientation this activity would like to run in.
     * From the {@link android.R.attr#screenOrientation} attribute, one of
     * {@link #SCREEN_ORIENTATION_UNSPECIFIED},
     * {@link #SCREEN_ORIENTATION_LANDSCAPE}, 
     * {@link #SCREEN_ORIENTATION_PORTRAIT},
     * {@link #SCREEN_ORIENTATION_USER},
     * {@link #SCREEN_ORIENTATION_BEHIND},
     * {@link #SCREEN_ORIENTATION_SENSOR},
     * {@link #SCREEN_ORIENTATION_NOSENSOR}.
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
     * can itself handle changes to the font scaling factor.  Set from the
     * {@link android.R.attr#configChanges} attribute.  This is
     * not a core resource configutation, but a higher-level value, so its
     * constant starts at the high bits.
     */
    public static final int CONFIG_FONT_SCALE = 0x40000000;
    
    /**
     * Bit mask of kinds of configuration changes that this activity
     * can handle itself (without being restarted by the system).
     * Contains any combination of {@link #CONFIG_FONT_SCALE},
     * {@link #CONFIG_MCC}, {@link #CONFIG_MNC},
     * {@link #CONFIG_LOCALE}, {@link #CONFIG_TOUCHSCREEN},
     * {@link #CONFIG_KEYBOARD}, {@link #CONFIG_NAVIGATION},
     * {@link #CONFIG_ORIENTATION}, and {@link #CONFIG_SCREEN_LAYOUT}.  Set from the
     * {@link android.R.attr#configChanges} attribute.
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
    }
}
