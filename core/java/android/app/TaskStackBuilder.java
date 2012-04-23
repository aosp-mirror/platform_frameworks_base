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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;

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
public class TaskStackBuilder implements Iterable<Intent> {
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
    public static TaskStackBuilder from(Context context) {
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
        final int insertAt = mIntents.size();
        Intent parent = sourceActivity.getParentActivityIntent();
        PackageManager pm = sourceActivity.getPackageManager();
        while (parent != null) {
            mIntents.add(insertAt, parent);
            try {
                ActivityInfo info = pm.getActivityInfo(parent.getComponent(), 0);
                String parentActivity = info.parentActivityName;
                if (parentActivity != null) {
                    parent = new Intent().setComponent(
                            new ComponentName(mSourceContext, parentActivity));
                } else {
                    parent = null;
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Bad ComponentName while traversing activity parent metadata");
                throw new IllegalArgumentException(e);
            }
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
        final int insertAt = mIntents.size();
        PackageManager pm = mSourceContext.getPackageManager();
        try {
            ActivityInfo info = pm.getActivityInfo(
                    new ComponentName(mSourceContext, sourceActivityClass), 0);
            String parentActivity = info.parentActivityName;
            Intent parent = new Intent().setComponent(
                    new ComponentName(mSourceContext, parentActivity));
            while (parent != null) {
                mIntents.add(insertAt, parent);
                info = pm.getActivityInfo(parent.getComponent(), 0);
                parentActivity = info.parentActivityName;
                if (parentActivity != null) {
                    parent = new Intent().setComponent(
                            new ComponentName(mSourceContext, parentActivity));
                } else {
                    parent = null;
                }
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Bad ComponentName while traversing activity parent metadata");
            throw new IllegalArgumentException(e);
        }
        return this;
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
            Intent parent = new Intent().setComponent(
                    new ComponentName(info.packageName, parentActivity));
            while (parent != null) {
                mIntents.add(insertAt, parent);
                info = pm.getActivityInfo(parent.getComponent(), 0);
                parentActivity = info.parentActivityName;
                if (parentActivity != null) {
                    parent = new Intent().setComponent(
                            new ComponentName(info.packageName, parentActivity));
                } else {
                    parent = null;
                }
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
     * Get the intent at the specified index.
     * Useful if you need to modify the flags or extras of an intent that was previously added,
     * for example with {@link #addParentStack(Activity)}.
     *
     * @param index Index from 0-getIntentCount()
     * @return the intent at position index
     */
    public Intent getIntent(int index) {
        return mIntents.get(index);
    }

    public Iterator<Intent> iterator() {
        return mIntents.iterator();
    }

    /**
     * Start the task stack constructed by this builder.
     */
    public void startActivities() {
        if (mIntents.isEmpty()) {
            throw new IllegalStateException(
                    "No intents added to TaskStackBuilder; cannot startActivities");
        }

        Intent[] intents = mIntents.toArray(new Intent[mIntents.size()]);
        intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        mSourceContext.startActivities(intents);
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
     * @return The obtained PendingIntent
     */
    public PendingIntent getPendingIntent(int requestCode, int flags) {
        if (mIntents.isEmpty()) {
            throw new IllegalStateException(
                    "No intents added to TaskStackBuilder; cannot getPendingIntent");
        }

        Intent[] intents = mIntents.toArray(new Intent[mIntents.size()]);
        intents[0].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        return PendingIntent.getActivities(mSourceContext, requestCode, intents, flags);
    }
}
