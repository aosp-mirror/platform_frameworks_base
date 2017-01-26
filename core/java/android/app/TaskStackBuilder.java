/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;

/**
 * Utility class for constructing synthetic back stacks for cross-task navigation
 * on Android 3.0 and newer.
 *
 * <p>In API level 11 (Android 3.0/Honeycomb) the recommended conventions for
 * app navigation using the back key changed. The back key's behavior is local
 * to the current task and does not capture navigation across different tasks.
 * Navigating across tasks and easily reaching the previous task is accomplished
 * through the "recents" UI, accessible through the software-provided Recents key
 * on the navigation or system bar. On devices with the older hardware button configuration
 * the recents UI can be accessed with a long press on the Home key.</p>
 *
 * <p>When crossing from one task stack to another post-Android 3.0,
 * the application should synthesize a back stack/history for the new task so that
 * the user may navigate out of the new task and back to the Launcher by repeated
 * presses of the back key. Back key presses should not navigate across task stacks.</p>
 *
 * <p>TaskStackBuilder provides a way to obey the correct conventions
 * around cross-task navigation.</p>
 *
 * <div class="special reference">
 * <h3>About Navigation</h3>
 * For more detailed information about tasks, the back stack, and navigation design guidelines,
 * please read
 * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back Stack</a>
 * from the developer guide and <a href="{@docRoot}design/patterns/navigation.html">Navigation</a>
 * from the design guide.
 * </div>
 */
public class TaskStackBuilder {
    private static final String TAG = "TaskStackBuilder";

    private final ArrayList<Intent> mIntents = new ArrayList<Intent>();
    private final Context mSourceContext;

    private TaskStackBuilder(Context a) {
        mSourceContext = a;
    }

    /**
     * Return a new TaskStackBuilder for launching a fresh task stack consisting
     * of a series of activities.
     *
     * @param context The context that will launch the new task stack or generate a PendingIntent
     * @return A new TaskStackBuilder
     */
    public static TaskStackBuilder create(Context context) {
        return new TaskStackBuilder(context);
    }

    /**
     * Add a new Intent to the task stack. The most recently added Intent will invoke
     * the Activity at the top of the final task stack.
     *
     * @param nextIntent Intent for the next Activity in the synthesized task stack
     * @return This TaskStackBuilder for method chaining
     */
    public TaskStackBuilder addNextIntent(Intent nextIntent) {
        mIntents.add(nextIntent);
        return this;
    }

    /**
     * Add a new Intent with the resolved chain of parents for the target activity to
     * the task stack.
     *
     * <p>This is equivalent to calling {@link #addParentStack(ComponentName) addParentStack}
     * with the resolved ComponentName of nextIntent (if it can be resolved), followed by
     * {@link #addNextIntent(Intent) addNextIntent} with nextIntent.</p>
     *
     * @param nextIntent Intent for the topmost Activity in the synthesized task stack.
     *                   Its chain of parents as specified in the manifest will be added.
     * @return This TaskStackBuilder for method chaining.
     */
    public TaskStackBuilder addNextIntentWithParentStack(Intent nextIntent) {
        ComponentName target = nextIntent.getComponent();
        if (target == null) {
            target = nextIntent.resolveActivity(mSourceContext.getPackageManager());
        }
        if (target != null) {
            addParentStack(target);
        }
        addNextIntent(nextIntent);
        return this;
    }

    /**
     * Add the activity parent chain as specified by the
     * {@link Activity#getParentActivityIntent() getParentActivityIntent()} method of the activity
     * specified and the {@link android.R.attr#parentActivityName parentActivityName} attributes
     * of each successive activity (or activity-alias) element in the application's manifest
     * to the task stack builder.
     *
     * @param sourceActivity All parents of this activity will be added
     * @return This TaskStackBuilder for method chaining
     */
    public TaskStackBuilder addParentStack(Activity sourceActivity) {
        final Intent parent = sourceActivity.getParentActivityIntent();
        if (parent != null) {
            // We have the actual parent intent, build the rest from static metadata
            // then add the direct parent intent to the end.
            ComponentName target = parent.getComponent();
            if (target == null) {
                target = parent.resolveActivity(mSourceContext.getPackageManager());
            }
            addParentStack(target);
            addNextIntent(parent);
        }
        return this;
    }

    /**
     * Add the activity parent chain as specified by the
     * {@link android.R.attr#parentActivityName parentActivityName} attribute of the activity
     * (or activity-alias) element in the application's manifest to the task stack builder.
     *
     * @param sourceActivityClass All parents of this activity will be added
     * @return This TaskStackBuilder for method chaining
     */
    public TaskStackBuilder addParentStack(Class<?> sourceActivityClass) {
        return addParentStack(new ComponentName(mSourceContext, sourceActivityClass));
    }

    /**
     * Add the activity parent chain as specified by the
     * {@link android.R.attr#parentActivityName parentActivityName} attribute of the activity
     * (or activity-alias) element in the application's manifest to the task stack builder.
     *
     * @param sourceActivityName Must specify an Activity component. All parents of
     *                           this activity will be added
     * @return This TaskStackBuilder for method chaining
     */
    public TaskStackBuilder addParentStack(ComponentName sourceActivityName) {
        final int insertAt = mIntents.size();
        PackageManager pm = mSourceContext.getPackageManager();
        try {
            ActivityInfo info = pm.getActivityInfo(sourceActivityName, 0);
            String parentActivity = info.parentActivityName;
            while (parentActivity != null) {
                final ComponentName target = new ComponentName(info.packageName, parentActivity);
                info = pm.getActivityInfo(target, 0);
                parentActivity = info.parentActivityName;
                final Intent parent = parentActivity == null && insertAt == 0
                        ? Intent.makeMainActivity(target)
                        : new Intent().setComponent(target);
                mIntents.add(insertAt, parent);
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Bad ComponentName while traversing activity parent metadata");
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    /**
     * @return the number of intents added so far.
     */
    public int getIntentCount() {
        return mIntents.size();
    }

    /**
     * Return the intent at the specified index for modification.
     * Useful if you need to modify the flags or extras of an intent that was previously added,
     * for example with {@link #addParentStack(Activity)}.
     *
     * @param index Index from 0-getIntentCount()
     * @return the intent at position index
     */
    public Intent editIntentAt(int index) {
        return mIntents.get(index);
    }

    /**
     * Start the task stack constructed by this builder.
     */
    public void startActivities() {
        startActivities(null);
    }

    /**
     * Start the task stack constructed by this builder.
     * @hide
     */
    public void startActivities(Bundle options, UserHandle userHandle) {
        if (mIntents.isEmpty()) {
            throw new IllegalStateException(
                    "No intents added to TaskStackBuilder; cannot startActivities");
        }

        mSourceContext.startActivitiesAsUser(getIntents(), options, userHandle);
    }

    /**
     * Start the task stack constructed by this builder.
     *
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.
     */
    public void startActivities(Bundle options) {
        startActivities(options, new UserHandle(UserHandle.myUserId()));
    }

    /**
     * Obtain a {@link PendingIntent} for launching the task constructed by this builder so far.
     *
     * @param requestCode Private request code for the sender
     * @param flags May be {@link PendingIntent#FLAG_ONE_SHOT},
     *              {@link PendingIntent#FLAG_NO_CREATE}, {@link PendingIntent#FLAG_CANCEL_CURRENT},
     *              {@link PendingIntent#FLAG_UPDATE_CURRENT}, or any of the flags supported by
     *              {@link Intent#fillIn(Intent, int)} to control which unspecified parts of the
     *              intent that can be supplied when the actual send happens.
     *
     * @return The obtained PendingIntent
     */
    public PendingIntent getPendingIntent(int requestCode, @PendingIntent.Flags int flags) {
        return getPendingIntent(requestCode, flags, null);
    }

    /**
     * Obtain a {@link PendingIntent} for launching the task constructed by this builder so far.
     *
     * @param requestCode Private request code for the sender
     * @param flags May be {@link PendingIntent#FLAG_ONE_SHOT},
     *              {@link PendingIntent#FLAG_NO_CREATE}, {@link PendingIntent#FLAG_CANCEL_CURRENT},
     *              {@link PendingIntent#FLAG_UPDATE_CURRENT}, or any of the flags supported by
     *              {@link Intent#fillIn(Intent, int)} to control which unspecified parts of the
     *              intent that can be supplied when the actual send happens.
     * @param options Additional options for how the Activity should be started.
     * See {@link android.content.Context#startActivity(Intent, Bundle)}
     * Context.startActivity(Intent, Bundle)} for more details.
     *
     * @return The obtained PendingIntent
     */
    public PendingIntent getPendingIntent(int requestCode, @PendingIntent.Flags int flags,
            Bundle options) {
        if (mIntents.isEmpty()) {
            throw new IllegalStateException(
                    "No intents added to TaskStackBuilder; cannot getPendingIntent");
        }

        return PendingIntent.getActivities(mSourceContext, requestCode, getIntents(),
                flags, options);
    }

    /**
     * @hide
     */
    public PendingIntent getPendingIntent(int requestCode, int flags, Bundle options,
            UserHandle user) {
        if (mIntents.isEmpty()) {
            throw new IllegalStateException(
                    "No intents added to TaskStackBuilder; cannot getPendingIntent");
        }

        return PendingIntent.getActivitiesAsUser(mSourceContext, requestCode, getIntents(), flags,
                options, user);
    }

    /**
     * Return an array containing the intents added to this builder. The intent at the
     * root of the task stack will appear as the first item in the array and the
     * intent at the top of the stack will appear as the last item.
     *
     * @return An array containing the intents added to this builder.
     */
    @NonNull
    public Intent[] getIntents() {
        Intent[] intents = new Intent[mIntents.size()];
        if (intents.length == 0) return intents;

        intents[0] = new Intent(mIntents.get(0)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        for (int i = 1; i < intents.length; i++) {
            intents[i] = new Intent(mIntents.get(i));
        }
        return intents;
    }
}
