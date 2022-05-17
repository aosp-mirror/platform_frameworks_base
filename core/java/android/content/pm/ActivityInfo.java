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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Configuration.NativeConfig;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Printer;

import com.android.internal.util.Parcelling;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Information you can retrieve about a particular application
 * activity or receiver. This corresponds to information collected
 * from the AndroidManifest.xml's &lt;activity&gt; and
 * &lt;receiver&gt; tags.
 */
public class ActivityInfo extends ComponentInfo implements Parcelable {

    private static final Parcelling.BuiltIn.ForStringSet sForStringSet =
            Parcelling.Cache.getOrCreate(Parcelling.BuiltIn.ForStringSet.class);

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
     * Constant corresponding to <code>singleInstancePerTask</code> in
     * the {@link android.R.attr#launchMode} attribute.
     */
    public static final int LAUNCH_SINGLE_INSTANCE_PER_TASK = 4;

    /** @hide */
    @IntDef(prefix = "LAUNCH_", value = {
            LAUNCH_MULTIPLE,
            LAUNCH_SINGLE_TOP,
            LAUNCH_SINGLE_TASK,
            LAUNCH_SINGLE_INSTANCE,
            LAUNCH_SINGLE_INSTANCE_PER_TASK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LaunchMode {
    }

    /**
     * The launch mode style requested by the activity.  From the
     * {@link android.R.attr#launchMode} attribute.
     */
    @LaunchMode
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
     * Token used to string together multiple events within a single launch action.
     * @hide
     */
    public String launchToken;

    /**
     * Activity can not be resized and always occupies the fullscreen area with all windows fully
     * visible.
     * @hide
     */
    public static final int RESIZE_MODE_UNRESIZEABLE = 0;
    /**
     * Activity didn't explicitly request to be resizeable, but we are making it resizeable because
     * of the SDK version it targets. Only affects apps with target SDK >= N where the app is
     * implied to be resizeable if it doesn't explicitly set the attribute to any value.
     * @hide
     */
    public static final int RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION = 1;
    /**
     * Activity explicitly requested to be resizeable.
     * @hide
     */
    @TestApi
    public static final int RESIZE_MODE_RESIZEABLE = 2;
    /**
     * Activity is resizeable and supported picture-in-picture mode.  This flag is now deprecated
     * since activities do not need to be resizeable to support picture-in-picture.
     * See {@link #FLAG_SUPPORTS_PICTURE_IN_PICTURE}.
     *
     * @hide
     * @deprecated
     */
    public static final int RESIZE_MODE_RESIZEABLE_AND_PIPABLE_DEPRECATED = 3;
    /**
     * Activity does not support resizing, but we are forcing it to be resizeable. Only affects
     * certain pre-N apps where we force them to be resizeable.
     * @hide
     */
    public static final int RESIZE_MODE_FORCE_RESIZEABLE = 4;
    /**
     * Activity does not support resizing, but we are forcing it to be resizeable as long
     * as the size remains landscape.
     * @hide
     */
    public static final int RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY = 5;
    /**
     * Activity does not support resizing, but we are forcing it to be resizeable as long
     * as the size remains portrait.
     * @hide
     */
    public static final int RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY = 6;
    /**
     * Activity does not support resizing, but we are forcing it to be resizeable as long
     * as the bounds remain in the same orientation as they are.
     * @hide
     */
    public static final int RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION = 7;
    /**
     * Value indicating if the resizing mode the activity supports.
     * See {@link android.R.attr#resizeableActivity}.
     * @hide
     */
    @UnsupportedAppUsage
    public int resizeMode = RESIZE_MODE_RESIZEABLE;

    /**
     * Value indicating the maximum aspect ratio the activity supports.
     * <p>
     * 0 means unset.
     * @See {@link android.R.attr#maxAspectRatio}.
     * @hide
     */
    private float mMaxAspectRatio;

    /**
     * Value indicating the minimum aspect ratio the activity supports.
     * <p>
     * 0 means unset.
     * @See {@link android.R.attr#minAspectRatio}.
     * @hide
     */
    private float mMinAspectRatio;

    /**
     * Indicates that the activity works well with size changes like display changing size.
     *
     * @hide
     */
    public boolean supportsSizeChanges;

    /**
     * Name of the VrListenerService component to run for this activity.
     * @see android.R.attr#enableVrMode
     * @hide
     */
    public String requestedVrComponent;

    /**
     * Value for {@link #colorMode} indicating that the activity should use the
     * default color mode (sRGB, low dynamic range).
     *
     * @see android.R.attr#colorMode
     */
    public static final int COLOR_MODE_DEFAULT = 0;
    /**
     * Value of {@link #colorMode} indicating that the activity should use a
     * wide color gamut if the presentation display supports it.
     *
     * @see android.R.attr#colorMode
     */
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT = 1;
    /**
     * Value of {@link #colorMode} indicating that the activity should use a
     * high dynamic range if the presentation display supports it.
     *
     * @see android.R.attr#colorMode
     */
    public static final int COLOR_MODE_HDR = 2;
    // 3 Corresponds to android::uirenderer::ColorMode::Hdr10.
    /**
     * Value of {@link #colorMode} indicating that the activity should use an
     * 8 bit alpha buffer if the presentation display supports it.
     *
     * @see android.R.attr#colorMode
     * @hide
     */
    public static final int COLOR_MODE_A8 = 4;


    /** @hide */
    @IntDef(prefix = { "COLOR_MODE_" }, value = {
            COLOR_MODE_DEFAULT,
            COLOR_MODE_WIDE_COLOR_GAMUT,
            COLOR_MODE_HDR,
            COLOR_MODE_A8,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ColorMode {}

    /**
     * The color mode requested by this activity. The target display may not be
     * able to honor the request.
     */
    @ColorMode
    public int colorMode = COLOR_MODE_DEFAULT;

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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * @see android.app.Activity#setVrModeEnabled(boolean, ComponentName)
     */
    public static final int FLAG_ENABLE_VR_MODE = 0x8000;
    /**
     * Bit in {@link #flags} indicating if the activity can be displayed on a remote device.
     * Corresponds to {@link android.R.attr#canDisplayOnRemoteDevices}
     * @hide
     */
    public static final int FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES = 0x10000;

    /**
     * Bit in {@link #flags} indicating if the activity is always focusable regardless of if it is
     * in a task/stack whose activities are normally not focusable.
     * See android.R.attr#alwaysFocusable.
     * @hide
     */
    public static final int FLAG_ALWAYS_FOCUSABLE = 0x40000;

    /**
     * Bit in {@link #flags} indicating if the activity is visible to instant
     * applications. The activity is visible if it's either implicitly or
     * explicitly exposed.
     * @hide
     */
    public static final int FLAG_VISIBLE_TO_INSTANT_APP = 0x100000;

    /**
     * Bit in {@link #flags} indicating if the activity is implicitly visible
     * to instant applications. Implicitly visible activities are those that
     * implement certain intent-filters:
     * <ul>
     * <li>action {@link Intent#CATEGORY_BROWSABLE}</li>
     * <li>action {@link Intent#ACTION_SEND}</li>
     * <li>action {@link Intent#ACTION_SENDTO}</li>
     * <li>action {@link Intent#ACTION_SEND_MULTIPLE}</li>
     * </ul>
     * @hide
     */
    public static final int FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP = 0x200000;

    /**
     * Bit in {@link #flags} indicating if the activity supports picture-in-picture mode.
     * See {@link android.R.attr#supportsPictureInPicture}.
     * @hide
     */
    public static final int FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000;

    /**
     * Bit in {@link #flags} indicating if the activity should be shown when locked.
     * See {@link android.R.attr#showWhenLocked}
     * @hide
     */
    public static final int FLAG_SHOW_WHEN_LOCKED = 0x800000;

    /**
     * Bit in {@link #flags} indicating if the screen should turn on when starting the activity.
     * See {@link android.R.attr#turnScreenOn}
     * @hide
     */
    public static final int FLAG_TURN_SCREEN_ON = 0x1000000;

    /**
     * Bit in {@link #flags} indicating whether the display should preferably be switched to a
     * minimal post processing mode.
     * See {@link android.R.attr#preferMinimalPostProcessing}
     */
    public static final int FLAG_PREFER_MINIMAL_POST_PROCESSING = 0x2000000;

    /**
     * Bit in {@link #flags}: If set, indicates that the activity can be embedded by untrusted
     * hosts. In this case the interactions with and visibility of the embedded activity may be
     * limited. Set from the {@link android.R.attr#allowUntrustedActivityEmbedding} attribute.
     */
    public static final int FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING = 0x10000000;

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
     *
     * @deprecated this flag is no longer needed since ActivityView is now fully removed
     * TODO(b/191165536): delete this flag since is no longer used
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
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
     * {@link #FLAG_HARDWARE_ACCELERATED}, {@link #FLAG_SINGLE_USER},
     * {@link #FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING}.
     */
    public int flags;

    /**
     * Bit in {@link #privateFlags} indicating if the activity should be shown when locked in case
     * an activity behind this can also be shown when locked.
     * See {@link android.R.attr#inheritShowWhenLocked}.
     * @hide
     */
    public static final int FLAG_INHERIT_SHOW_WHEN_LOCKED = 0x1;

    /**
     * Bit in {@link #privateFlags} indicating whether a home sound effect should be played if the
     * home app moves to front after the activity with this flag set.
     * Set from the {@link android.R.attr#playHomeTransitionSound} attribute.
     * @hide
     */
    public static final int PRIVATE_FLAG_HOME_TRANSITION_SOUND = 0x2;

    /**
     * Options that have been set in the activity declaration in the manifest.
     * These include:
     * {@link #FLAG_INHERIT_SHOW_WHEN_LOCKED},
     * {@link #PRIVATE_FLAG_HOME_TRANSITION_SOUND}.
     * @hide
     */
    public int privateFlags;

    /** @hide */
    @IntDef(prefix = { "SCREEN_ORIENTATION_" }, value = {
            SCREEN_ORIENTATION_UNSET,
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
     * Internal constant used to indicate that the app didn't set a specific orientation value.
     * Different from {@link #SCREEN_ORIENTATION_UNSPECIFIED} below as the app can set its
     * orientation to {@link #SCREEN_ORIENTATION_UNSPECIFIED} while this means that the app didn't
     * set anything. The system will mostly treat this similar to
     * {@link #SCREEN_ORIENTATION_UNSPECIFIED}.
     * @hide
     */
    public static final int SCREEN_ORIENTATION_UNSET = -2;
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
    @IntDef(flag = true, prefix = { "CONFIG_" }, value = {
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
            CONFIG_COLOR_MODE,
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
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle the change to the display color gamut or dynamic
     * range. Set from the {@link android.R.attr#configChanges} attribute.
     */
    public static final int CONFIG_COLOR_MODE = 0x4000;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle asset path changes.  Set from the {@link android.R.attr#configChanges}
     * attribute. This is not a core resource configuration, but a higher-level value, so its
     * constant starts at the high bits.
     * @hide We do not want apps handling this yet, but we do need some kind of bit for diffs.
     */
    public static final int CONFIG_ASSETS_PATHS = 0x80000000;
    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to the font scaling factor.  Set from the
     * {@link android.R.attr#configChanges} attribute.  This is
     * not a core resource configuration, but a higher-level value, so its
     * constant starts at the high bits.
     */
    public static final int CONFIG_FONT_SCALE = 0x40000000;
    /**
     * Bit indicating changes to window configuration that isn't exposed to apps.
     * This is for internal use only and apps don't handle it.
     * @hide
     * {@link Configuration}.
     */
    public static final int CONFIG_WINDOW_CONFIGURATION = 0x20000000;

    /**
     * Bit in {@link #configChanges} that indicates that the activity
     * can itself handle changes to font weight.  Set from the
     * {@link android.R.attr#configChanges} attribute.  This is
     * not a core resource configuration, but a higher-level value, so its
     * constant starts at the high bits.
     */

    public static final int CONFIG_FONT_WEIGHT_ADJUSTMENT = 0x10000000;

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
        Configuration.NATIVE_CONFIG_COLOR_MODE,             // COLOR_MODE
    };

    /**
     * This change id forces the packages it is applied to be resizable. It won't change whether
     * the app can be put into multi-windowing mode, but allow the app to resize when the window
     * container resizes, such as display size change.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long FORCE_RESIZE_APP = 174042936L; // buganizer id

    /**
     * This change id forces the packages it is applied to to be non-resizable.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long FORCE_NON_RESIZE_APP = 181136395L; // buganizer id

    /**
     * Return value for {@link #supportsSizeChanges()} indicating that this activity does not
     * support size changes due to the android.supports_size_changes metadata flag either being
     * unset or set to {@code false} on application or activity level.
     *
     * @hide
     */
    public static final int SIZE_CHANGES_UNSUPPORTED_METADATA = 0;

    /**
     * Return value for {@link #supportsSizeChanges()} indicating that this activity has been
     * overridden to not support size changes through the compat framework change id
     * {@link #FORCE_NON_RESIZE_APP}.
     * @hide
     */
    public static final int SIZE_CHANGES_UNSUPPORTED_OVERRIDE = 1;

    /**
     * Return value for {@link #supportsSizeChanges()} indicating that this activity supports size
     * changes due to the android.supports_size_changes metadata flag being set to {@code true}
     * either on application or activity level.
     * @hide
     */
    public static final int SIZE_CHANGES_SUPPORTED_METADATA = 2;

    /**
     * Return value for {@link #supportsSizeChanges()} indicating that this activity has been
     * overridden to support size changes through the compat framework change id
     * {@link #FORCE_RESIZE_APP}.
     * @hide
     */
    public static final int SIZE_CHANGES_SUPPORTED_OVERRIDE = 3;

    /** @hide */
    @IntDef(prefix = { "SIZE_CHANGES_" }, value = {
            SIZE_CHANGES_UNSUPPORTED_METADATA,
            SIZE_CHANGES_UNSUPPORTED_OVERRIDE,
            SIZE_CHANGES_SUPPORTED_METADATA,
            SIZE_CHANGES_SUPPORTED_OVERRIDE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SizeChangesSupportMode {}

    /**
     * This change id forces the packages it is applied to never have Display API sandboxing
     * applied for a letterbox or SCM activity. The Display APIs will continue to provide
     * DisplayArea bounds.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long NEVER_SANDBOX_DISPLAY_APIS = 184838306L; // buganizer id

    /**
     * This change id forces the packages it is applied to always have Display API sandboxing
     * applied, regardless of windowing mode. The Display APIs will always provide the app bounds.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long ALWAYS_SANDBOX_DISPLAY_APIS = 185004937L; // buganizer id

    /**
     * This change id is the gatekeeper for all treatments that force a given min aspect ratio.
     * Enabling this change will allow the following min aspect ratio treatments to be applied:
     * OVERRIDE_MIN_ASPECT_RATIO_MEDIUM
     * OVERRIDE_MIN_ASPECT_RATIO_LARGE
     *
     * If OVERRIDE_MIN_ASPECT_RATIO is applied, the min aspect ratio given in the app's manifest
     * will be overridden to the largest enabled aspect ratio treatment unless the app's manifest
     * value is higher.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long OVERRIDE_MIN_ASPECT_RATIO = 174042980L; // buganizer id

    /**
     * This change id restricts treatments that force a given min aspect ratio to activities
     * whose orientation is fixed to portrait.
     *
     * This treatment is enabled by default and only takes effect if OVERRIDE_MIN_ASPECT_RATIO is
     * also enabled.
     * @hide
     */
    @ChangeId
    @Overridable
    @TestApi
    public static final long OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY = 203647190L; // buganizer id

    /**
     * This change id sets the activity's min aspect ratio to a medium value as defined by
     * OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE.
     *
     * This treatment only takes effect if OVERRIDE_MIN_ASPECT_RATIO is also enabled.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long OVERRIDE_MIN_ASPECT_RATIO_MEDIUM = 180326845L; // buganizer id

    /** @hide Medium override aspect ratio, currently 3:2.  */
    @TestApi
    public static final float OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE = 3 / 2f;

    /**
     * This change id sets the activity's min aspect ratio to a large value as defined by
     * OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE.
     *
     * This treatment only takes effect if OVERRIDE_MIN_ASPECT_RATIO is also enabled.
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long OVERRIDE_MIN_ASPECT_RATIO_LARGE = 180326787L; // buganizer id

    /** @hide Large override aspect ratio, currently 16:9 */
    @TestApi
    public static final float OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE = 16 / 9f;

    /**
     * Compares activity window layout min width/height with require space for multi window to
     * determine if it can be put into multi window mode.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long CHECK_MIN_WIDTH_HEIGHT_FOR_MULTI_WINDOW = 197654537L;

    /**
     * Optional set of a certificates identifying apps that are allowed to embed this activity. From
     * the "knownActivityEmbeddingCerts" attribute.
     */
    @Nullable
    private Set<String> mKnownActivityEmbeddingCerts;

    /**
     * Convert Java change bits to native.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     * {@link #CONFIG_DENSITY}, {@link #CONFIG_LAYOUT_DIRECTION} and
     * {@link #CONFIG_COLOR_MODE}.
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
    @android.view.WindowManager.LayoutParams.SoftInputModeFlags
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

    /**
     * Screen rotation animation desired by the activity, with values as defined
     * for {@link android.view.WindowManager.LayoutParams#rotationAnimation}.
     *
     * -1 means to use the system default.
     *
     * @hide
     */
    public int rotationAnimation = -1;

    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_DEFAULT = 0;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_NEVER = 1;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_ALWAYS = 2;
    /** @hide */
    public static final int LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED = 3;

    /** @hide */
    public static final String lockTaskLaunchModeToString(int lockTaskLaunchMode) {
        switch (lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_DEFAULT:
                return "LOCK_TASK_LAUNCH_MODE_DEFAULT";
            case LOCK_TASK_LAUNCH_MODE_NEVER:
                return "LOCK_TASK_LAUNCH_MODE_NEVER";
            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                return "LOCK_TASK_LAUNCH_MODE_ALWAYS";
            case LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED:
                return "LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED";
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
        mKnownActivityEmbeddingCerts = orig.mKnownActivityEmbeddingCerts;
        taskAffinity = orig.taskAffinity;
        targetActivity = orig.targetActivity;
        flags = orig.flags;
        privateFlags = orig.privateFlags;
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
        rotationAnimation = orig.rotationAnimation;
        colorMode = orig.colorMode;
        mMaxAspectRatio = orig.mMaxAspectRatio;
        mMinAspectRatio = orig.mMinAspectRatio;
        supportsSizeChanges = orig.supportsSizeChanges;
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
     * Returns true if the activity has maximum or minimum aspect ratio.
     * @hide
     */
    public boolean hasFixedAspectRatio(@ScreenOrientation int orientation) {
        return getMaxAspectRatio() != 0 || getMinAspectRatio(orientation) != 0;
    }

    /**
     * Returns true if the activity's orientation is fixed.
     * @hide
     */
    public boolean isFixedOrientation() {
        return isFixedOrientationLandscape() || isFixedOrientationPortrait()
                || screenOrientation == SCREEN_ORIENTATION_LOCKED;
    }

    /**
     * Returns true if the activity's orientation is fixed to landscape.
     * @hide
     */
    boolean isFixedOrientationLandscape() {
        return isFixedOrientationLandscape(screenOrientation);
    }

    /**
     * Returns true if the activity's orientation is fixed to landscape.
     * @hide
     */
    public static boolean isFixedOrientationLandscape(@ScreenOrientation int orientation) {
        return orientation == SCREEN_ORIENTATION_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || orientation == SCREEN_ORIENTATION_USER_LANDSCAPE;
    }

    /**
     * Returns true if the activity's orientation is fixed to portrait.
     * @hide
     */
    boolean isFixedOrientationPortrait() {
        return isFixedOrientationPortrait(screenOrientation);
    }

    /**
     * Returns true if the activity's orientation is fixed to portrait.
     * @hide
     */
    public static boolean isFixedOrientationPortrait(@ScreenOrientation int orientation) {
        return orientation == SCREEN_ORIENTATION_PORTRAIT
                || orientation == SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || orientation == SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    /**
     * Returns the reversed orientation.
     * @hide
     */
    @ActivityInfo.ScreenOrientation
    public static int reverseOrientation(@ActivityInfo.ScreenOrientation int orientation) {
        switch (orientation) {
            case SCREEN_ORIENTATION_LANDSCAPE:
                return SCREEN_ORIENTATION_PORTRAIT;
            case SCREEN_ORIENTATION_PORTRAIT:
                return SCREEN_ORIENTATION_LANDSCAPE;
            case SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                return SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                return SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case SCREEN_ORIENTATION_USER_LANDSCAPE:
                return SCREEN_ORIENTATION_USER_PORTRAIT;
            case SCREEN_ORIENTATION_USER_PORTRAIT:
                return SCREEN_ORIENTATION_USER_LANDSCAPE;
            default:
                return orientation;
        }
    }

    /**
     * Returns true if the activity supports picture-in-picture.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean supportsPictureInPicture() {
        return (flags & FLAG_SUPPORTS_PICTURE_IN_PICTURE) != 0;
    }

    /**
     * Returns whether the activity supports size changes.
     * @hide
     */
    @SizeChangesSupportMode
    public int supportsSizeChanges() {
        if (isChangeEnabled(FORCE_NON_RESIZE_APP)) {
            return SIZE_CHANGES_UNSUPPORTED_OVERRIDE;
        }

        if (supportsSizeChanges) {
            return SIZE_CHANGES_SUPPORTED_METADATA;
        }

        if (isChangeEnabled(FORCE_RESIZE_APP)) {
            return SIZE_CHANGES_SUPPORTED_OVERRIDE;
        }

        return SIZE_CHANGES_UNSUPPORTED_METADATA;
    }

    /**
     * Returns if the activity should never be sandboxed to the activity window bounds.
     * @hide
     */
    public boolean neverSandboxDisplayApis(ConstrainDisplayApisConfig constrainDisplayApisConfig) {
        return isChangeEnabled(NEVER_SANDBOX_DISPLAY_APIS)
                || constrainDisplayApisConfig.getNeverConstrainDisplayApis(applicationInfo);
    }

    /**
     * Returns if the activity should always be sandboxed to the activity window bounds.
     * @hide
     */
    public boolean alwaysSandboxDisplayApis(ConstrainDisplayApisConfig constrainDisplayApisConfig) {
        return isChangeEnabled(ALWAYS_SANDBOX_DISPLAY_APIS)
                || constrainDisplayApisConfig.getAlwaysConstrainDisplayApis(applicationInfo);
    }

    /** @hide */
    public void setMaxAspectRatio(@FloatRange(from = 0f) float maxAspectRatio) {
        this.mMaxAspectRatio = maxAspectRatio >= 0f ? maxAspectRatio : 0f;
    }

    /** @hide */
    public float getMaxAspectRatio() {
        return mMaxAspectRatio;
    }

    /** @hide */
    public void setMinAspectRatio(@FloatRange(from = 0f) float minAspectRatio) {
        this.mMinAspectRatio = minAspectRatio >= 0f ? minAspectRatio : 0f;
    }

    /**
     * Returns the min aspect ratio of this activity.
     *
     * This takes into account the minimum aspect ratio as defined in the app's manifest and
     * possible overrides as per OVERRIDE_MIN_ASPECT_RATIO.
     *
     * In the rare cases where the manifest minimum aspect ratio is required, use
     * {@code getManifestMinAspectRatio}.
     * @hide
     */
    public float getMinAspectRatio(@ScreenOrientation int orientation) {
        if (applicationInfo == null || !isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO) || (
                isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY)
                        && !isFixedOrientationPortrait(orientation))) {
            return mMinAspectRatio;
        }

        if (isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_LARGE)) {
            return Math.max(OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE, mMinAspectRatio);
        }

        if (isChangeEnabled(OVERRIDE_MIN_ASPECT_RATIO_MEDIUM)) {
            return Math.max(OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE, mMinAspectRatio);
        }

        return mMinAspectRatio;
    }

    /**
     * Gets the trusted host certificate digests of apps that are allowed to embed this activity.
     * The digests are computed using the SHA-256 digest algorithm.
     * @see android.R.attr#knownActivityEmbeddingCerts
     */
    @NonNull
    public Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts == null ? Collections.emptySet()
                : mKnownActivityEmbeddingCerts;
    }

    /**
     * Sets the trusted host certificates of apps that are allowed to embed this activity.
     * @see #getKnownActivityEmbeddingCerts()
     * @hide
     */
    public void setKnownActivityEmbeddingCerts(@NonNull Set<String> knownActivityEmbeddingCerts) {
        // Convert the provided digest to upper case for consistent Set membership
        // checks when verifying the signing certificate digests of requesting apps.
        mKnownActivityEmbeddingCerts = new ArraySet<>();
        for (String knownCert : knownActivityEmbeddingCerts) {
            mKnownActivityEmbeddingCerts.add(knownCert.toUpperCase(Locale.US));
        }
    }

    private boolean isChangeEnabled(long changeId) {
        return CompatChanges.isChangeEnabled(changeId, applicationInfo.packageName,
                UserHandle.getUserHandleForUid(applicationInfo.uid));
    }

    /** @hide */
    public float getManifestMinAspectRatio() {
        return mMinAspectRatio;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isResizeableMode(int mode) {
        return mode == RESIZE_MODE_RESIZEABLE
                || mode == RESIZE_MODE_FORCE_RESIZEABLE
                || mode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION
                || mode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
    }

    /** @hide */
    public static boolean isPreserveOrientationMode(int mode) {
        return mode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    /** @hide */
    public static String resizeModeToString(int mode) {
        switch (mode) {
            case RESIZE_MODE_UNRESIZEABLE:
                return "RESIZE_MODE_UNRESIZEABLE";
            case RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION:
                return "RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION";
            case RESIZE_MODE_RESIZEABLE:
                return "RESIZE_MODE_RESIZEABLE";
            case RESIZE_MODE_FORCE_RESIZEABLE:
                return "RESIZE_MODE_FORCE_RESIZEABLE";
            case RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY:
                return "RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY";
            case RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY:
                return "RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY";
            case RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION:
                return "RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION";
            default:
                return "unknown=" + mode;
        }
    }

    /** @hide */
    public static String sizeChangesSupportModeToString(@SizeChangesSupportMode int mode) {
        switch (mode) {
            case SIZE_CHANGES_UNSUPPORTED_METADATA:
                return "SIZE_CHANGES_UNSUPPORTED_METADATA";
            case SIZE_CHANGES_UNSUPPORTED_OVERRIDE:
                return "SIZE_CHANGES_UNSUPPORTED_OVERRIDE";
            case SIZE_CHANGES_SUPPORTED_METADATA:
                return "SIZE_CHANGES_SUPPORTED_METADATA";
            case SIZE_CHANGES_SUPPORTED_OVERRIDE:
                return "SIZE_CHANGES_SUPPORTED_OVERRIDE";
            default:
                return "unknown=" + mode;
        }
    }

    /**
     * Whether we should compare activity window layout min width/height with require space for
     * multi window to determine if it can be put into multi window mode.
     * @hide
     */
    public boolean shouldCheckMinWidthHeightForMultiWindow() {
        return isChangeEnabled(CHECK_MIN_WIDTH_HEIGHT_FOR_MULTI_WINDOW);
    }

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "taskAffinity=" + taskAffinity
                    + " targetActivity=" + targetActivity
                    + " persistableMode=" + persistableModeToString());
        }
        if (launchMode != 0 || flags != 0 || privateFlags != 0 || theme != 0) {
            pw.println(prefix + "launchMode=" + launchMode
                    + " flags=0x" + Integer.toHexString(flags)
                    + " privateFlags=0x" + Integer.toHexString(privateFlags)
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
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
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
        if (getMaxAspectRatio() != 0) {
            pw.println(prefix + "maxAspectRatio=" + getMaxAspectRatio());
        }
        final float minAspectRatio = getMinAspectRatio(screenOrientation);
        if (minAspectRatio != 0) {
            pw.println(prefix + "minAspectRatio=" + minAspectRatio);
            if (getManifestMinAspectRatio() !=  minAspectRatio) {
                pw.println(prefix + "getManifestMinAspectRatio=" + getManifestMinAspectRatio());
            }
        }
        if (supportsSizeChanges) {
            pw.println(prefix + "supportsSizeChanges=true");
        }
        if (mKnownActivityEmbeddingCerts != null) {
            pw.println(prefix + "knownActivityEmbeddingCerts=" + mKnownActivityEmbeddingCerts);
        }
        super.dumpBack(pw, prefix, dumpFlags);
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
        dest.writeString8(permission);
        dest.writeString8(taskAffinity);
        dest.writeString8(targetActivity);
        dest.writeString8(launchToken);
        dest.writeInt(flags);
        dest.writeInt(privateFlags);
        dest.writeInt(screenOrientation);
        dest.writeInt(configChanges);
        dest.writeInt(softInputMode);
        dest.writeInt(uiOptions);
        dest.writeString8(parentActivityName);
        dest.writeInt(persistableMode);
        dest.writeInt(maxRecents);
        dest.writeInt(lockTaskLaunchMode);
        if (windowLayout != null) {
            dest.writeInt(1);
            windowLayout.writeToParcel(dest);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(resizeMode);
        dest.writeString8(requestedVrComponent);
        dest.writeInt(rotationAnimation);
        dest.writeInt(colorMode);
        dest.writeFloat(mMaxAspectRatio);
        dest.writeFloat(mMinAspectRatio);
        dest.writeBoolean(supportsSizeChanges);
        sForStringSet.parcel(mKnownActivityEmbeddingCerts, dest, flags);
    }

    /**
     * Determines whether the {@link Activity} is considered translucent or floating.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public static boolean isTranslucentOrFloating(TypedArray attributes) {
        final boolean isTranslucent =
                attributes.getBoolean(com.android.internal.R.styleable.Window_windowIsTranslucent,
                        false);
        final boolean isFloating =
                attributes.getBoolean(com.android.internal.R.styleable.Window_windowIsFloating,
                        false);

        return isFloating || isTranslucent;
    }

    /**
     * Convert the screen orientation constant to a human readable format.
     * @hide
     */
    public static String screenOrientationToString(int orientation) {
        switch (orientation) {
            case SCREEN_ORIENTATION_UNSET:
                return "SCREEN_ORIENTATION_UNSET";
            case SCREEN_ORIENTATION_UNSPECIFIED:
                return "SCREEN_ORIENTATION_UNSPECIFIED";
            case SCREEN_ORIENTATION_LANDSCAPE:
                return "SCREEN_ORIENTATION_LANDSCAPE";
            case SCREEN_ORIENTATION_PORTRAIT:
                return "SCREEN_ORIENTATION_PORTRAIT";
            case SCREEN_ORIENTATION_USER:
                return "SCREEN_ORIENTATION_USER";
            case SCREEN_ORIENTATION_BEHIND:
                return "SCREEN_ORIENTATION_BEHIND";
            case SCREEN_ORIENTATION_SENSOR:
                return "SCREEN_ORIENTATION_SENSOR";
            case SCREEN_ORIENTATION_NOSENSOR:
                return "SCREEN_ORIENTATION_NOSENSOR";
            case SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                return "SCREEN_ORIENTATION_SENSOR_LANDSCAPE";
            case SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                return "SCREEN_ORIENTATION_SENSOR_PORTRAIT";
            case SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                return "SCREEN_ORIENTATION_REVERSE_LANDSCAPE";
            case SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                return "SCREEN_ORIENTATION_REVERSE_PORTRAIT";
            case SCREEN_ORIENTATION_FULL_SENSOR:
                return "SCREEN_ORIENTATION_FULL_SENSOR";
            case SCREEN_ORIENTATION_USER_LANDSCAPE:
                return "SCREEN_ORIENTATION_USER_LANDSCAPE";
            case SCREEN_ORIENTATION_USER_PORTRAIT:
                return "SCREEN_ORIENTATION_USER_PORTRAIT";
            case SCREEN_ORIENTATION_FULL_USER:
                return "SCREEN_ORIENTATION_FULL_USER";
            case SCREEN_ORIENTATION_LOCKED:
                return "SCREEN_ORIENTATION_LOCKED";
            default:
                return Integer.toString(orientation);
        }
    }

    /**
     * @hide
     */
    public static String colorModeToString(@ColorMode int colorMode) {
        switch (colorMode) {
            case COLOR_MODE_DEFAULT:
                return "COLOR_MODE_DEFAULT";
            case COLOR_MODE_WIDE_COLOR_GAMUT:
                return "COLOR_MODE_WIDE_COLOR_GAMUT";
            case COLOR_MODE_HDR:
                return "COLOR_MODE_HDR";
            case COLOR_MODE_A8:
                return "COLOR_MODE_A8";
            default:
                return Integer.toString(colorMode);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ActivityInfo> CREATOR
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
        permission = source.readString8();
        taskAffinity = source.readString8();
        targetActivity = source.readString8();
        launchToken = source.readString8();
        flags = source.readInt();
        privateFlags = source.readInt();
        screenOrientation = source.readInt();
        configChanges = source.readInt();
        softInputMode = source.readInt();
        uiOptions = source.readInt();
        parentActivityName = source.readString8();
        persistableMode = source.readInt();
        maxRecents = source.readInt();
        lockTaskLaunchMode = source.readInt();
        if (source.readInt() == 1) {
            windowLayout = new WindowLayout(source);
        }
        resizeMode = source.readInt();
        requestedVrComponent = source.readString8();
        rotationAnimation = source.readInt();
        colorMode = source.readInt();
        mMaxAspectRatio = source.readFloat();
        mMinAspectRatio = source.readFloat();
        supportsSizeChanges = source.readBoolean();
        mKnownActivityEmbeddingCerts = sForStringSet.unparcel(source);
        if (mKnownActivityEmbeddingCerts.isEmpty()) {
            mKnownActivityEmbeddingCerts = null;
        }
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
        public WindowLayout(int width, float widthFraction, int height, float heightFraction,
                int gravity, int minWidth, int minHeight) {
            this(width, widthFraction, height, heightFraction, gravity, minWidth, minHeight,
                    null /* windowLayoutAffinity */);
        }

        /** @hide */
        public WindowLayout(int width, float widthFraction, int height, float heightFraction,
                int gravity, int minWidth, int minHeight, String windowLayoutAffinity) {
            this.width = width;
            this.widthFraction = widthFraction;
            this.height = height;
            this.heightFraction = heightFraction;
            this.gravity = gravity;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.windowLayoutAffinity = windowLayoutAffinity;
        }

        /** @hide */
        public WindowLayout(Parcel source) {
            width = source.readInt();
            widthFraction = source.readFloat();
            height = source.readInt();
            heightFraction = source.readFloat();
            gravity = source.readInt();
            minWidth = source.readInt();
            minHeight = source.readInt();
            windowLayoutAffinity = source.readString8();
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

        /**
         * Affinity of window layout parameters. Activities with the same UID and window layout
         * affinity will share the same window dimension record.
         *
         * @attr ref android.R.styleable#AndroidManifestLayout_windowLayoutAffinity
         * @hide
         */
        public String windowLayoutAffinity;

        /**
         * Returns if this {@link WindowLayout} has specified bounds.
         * @hide
         */
        public boolean hasSpecifiedSize() {
            return width >= 0 || height >= 0 || widthFraction >= 0 || heightFraction >= 0;
        }

        /** @hide */
        public void writeToParcel(Parcel dest) {
            dest.writeInt(width);
            dest.writeFloat(widthFraction);
            dest.writeInt(height);
            dest.writeFloat(heightFraction);
            dest.writeInt(gravity);
            dest.writeInt(minWidth);
            dest.writeInt(minHeight);
            dest.writeString8(windowLayoutAffinity);
        }
    }
}
