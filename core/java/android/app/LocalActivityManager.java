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

package android.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.util.Config;
import android.util.Log;
import android.view.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Helper class for managing multiple running embedded activities in the same
 * process. This class is not normally used directly, but rather created for
 * you as part of the {@link android.app.ActivityGroup} implementation.
 *
 * @see ActivityGroup
 */
public class LocalActivityManager {
    private static final String TAG = "LocalActivityManager";
    private static final boolean localLOGV = false || Config.LOGV;

    // Internal token for an Activity being managed by LocalActivityManager.
    private static class LocalActivityRecord extends Binder {
        LocalActivityRecord(String _id, Intent _intent) {
            id = _id;
            intent = _intent;
        }

        final String id;                // Unique name of this record.
        Intent intent;                  // Which activity to run here.
        ActivityInfo activityInfo;      // Package manager info about activity.
        Activity activity;              // Currently instantiated activity.
        Window window;                  // Activity's top-level window.
        Bundle instanceState;           // Last retrieved freeze state.
        int curState = RESTORED;        // Current state the activity is in.
    }

    static final int RESTORED = 0;      // State restored, but no startActivity().
    static final int INITIALIZING = 1;  // Ready to launch (after startActivity()).
    static final int CREATED = 2;       // Created, not started or resumed.
    static final int STARTED = 3;       // Created and started, not resumed.
    static final int RESUMED = 4;       // Created started and resumed.
    static final int DESTROYED = 5;     // No longer with us.
    
    /** Thread our activities are running in. */
    private final ActivityThread mActivityThread;
    /** The containing activity that owns the activities we create. */
    private final Activity mParent;

    /** The activity that is currently resumed. */
    private LocalActivityRecord mResumed;
    /** id -> record of all known activities. */
    private final Map<String, LocalActivityRecord> mActivities
            = new HashMap<String, LocalActivityRecord>();
    /** array of all known activities for easy iterating. */
    private final ArrayList<LocalActivityRecord> mActivityArray
            = new ArrayList<LocalActivityRecord>();

    /** True if only one activity can be resumed at a time */
    private boolean mSingleMode;
    
    /** Set to true once we find out the container is finishing. */
    private boolean mFinishing;
    
    /** Current state the owner (ActivityGroup) is in */
    private int mCurState = INITIALIZING;
    
    /** String ids of running activities starting with least recently used. */
    // TODO: put back in stopping of activities.
    //private List<LocalActivityRecord>  mLRU = new ArrayList();

    /**
     * Create a new LocalActivityManager for holding activities running within
     * the given <var>parent</var>.
     * 
     * @param parent the host of the embedded activities
     * @param singleMode True if the LocalActivityManger should keep a maximum
     * of one activity resumed
     */
    public LocalActivityManager(Activity parent, boolean singleMode) {
        mActivityThread = ActivityThread.currentActivityThread();
        mParent = parent;
        mSingleMode = singleMode;
    }

    private void moveToState(LocalActivityRecord r, int desiredState) {
        if (r.curState == RESTORED || r.curState == DESTROYED) {
            // startActivity() has not yet been called, so nothing to do.
            return;
        }
        
        if (r.curState == INITIALIZING) {
            // Get the lastNonConfigurationInstance for the activity
            HashMap<String,Object> lastNonConfigurationInstances =
                mParent.getLastNonConfigurationChildInstances();
            Object instance = null;
            if (lastNonConfigurationInstances != null) {
                instance = lastNonConfigurationInstances.get(r.id);
            }
            
            // We need to have always created the activity.
            if (localLOGV) Log.v(TAG, r.id + ": starting " + r.intent);
            if (r.activityInfo == null) {
                r.activityInfo = mActivityThread.resolveActivityInfo(r.intent);
            }
            r.activity = mActivityThread.startActivityNow(
                    mParent, r.id, r.intent, r.activityInfo, r, r.instanceState, instance);
            if (r.activity == null) {
                return;
            }
            r.window = r.activity.getWindow();
            r.instanceState = null;
            r.curState = STARTED;
            
            if (desiredState == RESUMED) {
                if (localLOGV) Log.v(TAG, r.id + ": resuming");
                mActivityThread.performResumeActivity(r, true);
                r.curState = RESUMED;
            }
            
            // Don't do anything more here.  There is an important case:
            // if this is being done as part of onCreate() of the group, then
            // the launching of the activity gets its state a little ahead
            // of our own (it is now STARTED, while we are only CREATED).
            // If we just leave things as-is, we'll deal with it as the
            // group's state catches up.
            return;
        }
        
        switch (r.curState) {
            case CREATED:
                if (desiredState == STARTED) {
                    if (localLOGV) Log.v(TAG, r.id + ": restarting");
                    mActivityThread.performRestartActivity(r);
                    r.curState = STARTED;
                }
                if (desiredState == RESUMED) {
                    if (localLOGV) Log.v(TAG, r.id + ": restarting and resuming");
                    mActivityThread.performRestartActivity(r);
                    mActivityThread.performResumeActivity(r, true);
                    r.curState = RESUMED;
                }
                return;
                
            case STARTED:
                if (desiredState == RESUMED) {
                    // Need to resume it...
                    if (localLOGV) Log.v(TAG, r.id + ": resuming");
                    mActivityThread.performResumeActivity(r, true);
                    r.instanceState = null;
                    r.curState = RESUMED;
                }
                if (desiredState == CREATED) {
                    if (localLOGV) Log.v(TAG, r.id + ": stopping");
                    mActivityThread.performStopActivity(r);
                    r.curState = CREATED;
                }
                return;
                
            case RESUMED:
                if (desiredState == STARTED) {
                    if (localLOGV) Log.v(TAG, r.id + ": pausing");
                    performPause(r, mFinishing);
                    r.curState = STARTED;
                }
                if (desiredState == CREATED) {
                    if (localLOGV) Log.v(TAG, r.id + ": pausing");
                    performPause(r, mFinishing);
                    if (localLOGV) Log.v(TAG, r.id + ": stopping");
                    mActivityThread.performStopActivity(r);
                    r.curState = CREATED;
                }
                return;
        }
    }
    
    private void performPause(LocalActivityRecord r, boolean finishing) {
        boolean needState = r.instanceState == null;
        Bundle instanceState = mActivityThread.performPauseActivity(r,
                finishing, needState);
        if (needState) {
            r.instanceState = instanceState;
        }
    }
    
    /**
     * Start a new activity running in the group.  Every activity you start
     * must have a unique string ID associated with it -- this is used to keep
     * track of the activity, so that if you later call startActivity() again
     * on it the same activity object will be retained.
     * 
     * <p>When there had previously been an activity started under this id,
     * it may either be destroyed and a new one started, or the current
     * one re-used, based on these conditions, in order:</p>
     * 
     * <ul>
     * <li> If the Intent maps to a different activity component than is
     * currently running, the current activity is finished and a new one
     * started.
     * <li> If the current activity uses a non-multiple launch mode (such
     * as singleTop), or the Intent has the
     * {@link Intent#FLAG_ACTIVITY_SINGLE_TOP} flag set, then the current
     * activity will remain running and its
     * {@link Activity#onNewIntent(Intent) Activity.onNewIntent()} method
     * called.
     * <li> If the new Intent is the same (excluding extras) as the previous
     * one, and the new Intent does not have the
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP} set, then the current activity
     * will remain running as-is.
     * <li> Otherwise, the current activity will be finished and a new
     * one started.
     * </ul>
     * 
     * <p>If the given Intent can not be resolved to an available Activity,
     * this method throws {@link android.content.ActivityNotFoundException}.
     * 
     * <p>Warning: There is an issue where, if the Intent does not
     * include an explicit component, we can restore the state for a different
     * activity class than was previously running when the state was saved (if
     * the set of available activities changes between those points).
     * 
     * @param id Unique identifier of the activity to be started
     * @param intent The Intent describing the activity to be started
     * 
     * @return Returns the window of the activity.  The caller needs to take
     * care of adding this window to a view hierarchy, and likewise dealing
     * with removing the old window if the activity has changed.
     * 
     * @throws android.content.ActivityNotFoundException
     */
    public Window startActivity(String id, Intent intent) {
        if (mCurState == INITIALIZING) {
            throw new IllegalStateException(
                    "Activities can't be added until the containing group has been created.");
        }
        
        boolean adding = false;
        boolean sameIntent = false;

        ActivityInfo aInfo = null;
        
        // Already have information about the new activity id?
        LocalActivityRecord r = mActivities.get(id);
        if (r == null) {
            // Need to create it...
            r = new LocalActivityRecord(id, intent);
            adding = true;
        } else if (r.intent != null) {
            sameIntent = r.intent.filterEquals(intent); 
            if (sameIntent) {
                // We are starting the same activity.
                aInfo = r.activityInfo;
            }
        }
        if (aInfo == null) {
            aInfo = mActivityThread.resolveActivityInfo(intent);
        }
        
        // Pause the currently running activity if there is one and only a single
        // activity is allowed to be running at a time.
        if (mSingleMode) {
            LocalActivityRecord old = mResumed;
    
            // If there was a previous activity, and it is not the current
            // activity, we need to stop it.
            if (old != null && old != r && mCurState == RESUMED) {
                moveToState(old, STARTED);
            }
        }

        if (adding) {
            // It's a brand new world.
            mActivities.put(id, r);
            mActivityArray.add(r);
        } else if (r.activityInfo != null) {
            // If the new activity is the same as the current one, then
            // we may be able to reuse it.
            if (aInfo == r.activityInfo ||
                    (aInfo.name.equals(r.activityInfo.name) &&
                            aInfo.packageName.equals(r.activityInfo.packageName))) {
                if (aInfo.launchMode != ActivityInfo.LAUNCH_MULTIPLE ||
                        (intent.getFlags()&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) {
                    // The activity wants onNewIntent() called.
                    ArrayList<Intent> intents = new ArrayList<Intent>(1);
                    intents.add(intent);
                    if (localLOGV) Log.v(TAG, r.id + ": new intent");
                    mActivityThread.performNewIntents(r, intents);
                    r.intent = intent;
                    moveToState(r, mCurState);
                    if (mSingleMode) {
                        mResumed = r;
                    }
                    return r.window;
                }
                if (sameIntent &&
                        (intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP) == 0) {
                    // We are showing the same thing, so this activity is
                    // just resumed and stays as-is.
                    r.intent = intent;
                    moveToState(r, mCurState);
                    if (mSingleMode) {
                        mResumed = r;
                    }
                    return r.window;
                }
            }
            
            // The new activity is different than the current one, or it
            // is a multiple launch activity, so we need to destroy what
            // is currently there.
            performDestroy(r, true);
        }
        
        r.intent = intent;
        r.curState = INITIALIZING;
        r.activityInfo = aInfo;

        moveToState(r, mCurState);

        // When in single mode keep track of the current activity
        if (mSingleMode) {
            mResumed = r;
        }
        return r.window;
    }

    private Window performDestroy(LocalActivityRecord r, boolean finish) {
        Window win = null;
        win = r.window;
        if (r.curState == RESUMED && !finish) {
            performPause(r, finish);
        }
        if (localLOGV) Log.v(TAG, r.id + ": destroying");
        mActivityThread.performDestroyActivity(r, finish);
        r.activity = null;
        r.window = null;
        if (finish) {
            r.instanceState = null;
        }
        r.curState = DESTROYED;
        return win;
    }
    
    /**
     * Destroy the activity associated with a particular id.  This activity
     * will go through the normal lifecycle events and fine onDestroy(), and
     * then the id removed from the group.
     * 
     * @param id Unique identifier of the activity to be destroyed
     * @param finish If true, this activity will be finished, so its id and
     * all state are removed from the group.
     * 
     * @return Returns the window that was used to display the activity, or
     * null if there was none.
     */
    public Window destroyActivity(String id, boolean finish) {
        LocalActivityRecord r = mActivities.get(id);
        Window win = null;
        if (r != null) {
            win = performDestroy(r, finish);
            if (finish) {
                mActivities.remove(id);
            }
        }
        return win;
    }
    
    /**
     * Retrieve the Activity that is currently running.
     * 
     * @return the currently running (resumed) Activity, or null if there is
     *         not one
     * 
     * @see #startActivity
     * @see #getCurrentId
     */
    public Activity getCurrentActivity() {
        return mResumed != null ? mResumed.activity : null;
    }

    /**
     * Retrieve the ID of the activity that is currently running.
     * 
     * @return the ID of the currently running (resumed) Activity, or null if
     *         there is not one
     * 
     * @see #startActivity
     * @see #getCurrentActivity
     */
    public String getCurrentId() {
        return mResumed != null ? mResumed.id : null;
    }

    /**
     * Return the Activity object associated with a string ID.
     * 
     * @see #startActivity
     * 
     * @return the associated Activity object, or null if the id is unknown or
     *         its activity is not currently instantiated
     */
    public Activity getActivity(String id) {
        LocalActivityRecord r = mActivities.get(id);
        return r != null ? r.activity : null;
    }

    /**
     * Restore a state that was previously returned by {@link #saveInstanceState}.  This
     * adds to the activity group information about all activity IDs that had
     * previously been saved, even if they have not been started yet, so if the
     * user later navigates to them the correct state will be restored.
     * 
     * <p>Note: This does <b>not</b> change the current running activity, or
     * start whatever activity was previously running when the state was saved.
     * That is up to the client to do, in whatever way it thinks is best.
     * 
     * @param state a previously saved state; does nothing if this is null
     * 
     * @see #saveInstanceState
     */
    public void dispatchCreate(Bundle state) {
        if (state != null) {
            final Iterator<String> i = state.keySet().iterator();
            while (i.hasNext()) {
                try {
                    final String id = i.next();
                    final Bundle astate = state.getBundle(id);
                    LocalActivityRecord r = mActivities.get(id);
                    if (r != null) {
                        r.instanceState = astate;
                    } else {
                        r = new LocalActivityRecord(id, null);
                        r.instanceState = astate;
                        mActivities.put(id, r);
                        mActivityArray.add(r);
                    }
                } catch (Exception e) {
                    // Recover from -all- app errors.
                    Log.e(TAG,
                          "Exception thrown when restoring LocalActivityManager state",
                          e);
                }
            }
        }
        
        mCurState = CREATED;
    }

    /**
     * Retrieve the state of all activities known by the group.  For
     * activities that have previously run and are now stopped or finished, the
     * last saved state is used.  For the current running activity, its
     * {@link Activity#onSaveInstanceState} is called to retrieve its current state.
     * 
     * @return a Bundle holding the newly created state of all known activities
     * 
     * @see #dispatchCreate
     */
    public Bundle saveInstanceState() {
        Bundle state = null;

        // FIXME: child activities will freeze as part of onPaused. Do we
        // need to do this here?
        final int N = mActivityArray.size();
        for (int i=0; i<N; i++) {
            final LocalActivityRecord r = mActivityArray.get(i);
            if (state == null) {
                state = new Bundle();
            }
            if ((r.instanceState != null || r.curState == RESUMED)
                    && r.activity != null) {
                // We need to save the state now, if we don't currently
                // already have it or the activity is currently resumed.
                final Bundle childState = new Bundle();
                r.activity.onSaveInstanceState(childState);
                r.instanceState = childState;
            }
            if (r.instanceState != null) {
                state.putBundle(r.id, r.instanceState);
            }
        }

        return state;
    }

    /**
     * Called by the container activity in its {@link Activity#onResume} so
     * that LocalActivityManager can perform the corresponding action on the
     * activities it holds.
     * 
     * @see Activity#onResume
     */
    public void dispatchResume() {
        mCurState = RESUMED;
        if (mSingleMode) {
            if (mResumed != null) {
                moveToState(mResumed, RESUMED);
            }
        } else {
            final int N = mActivityArray.size();
            for (int i=0; i<N; i++) {
                moveToState(mActivityArray.get(i), RESUMED);
            }
        }
    }

    /**
     * Called by the container activity in its {@link Activity#onPause} so
     * that LocalActivityManager can perform the corresponding action on the
     * activities it holds.
     * 
     * @param finishing set to true if the parent activity has been finished;
     *                  this can be determined by calling
     *                  Activity.isFinishing()
     * 
     * @see Activity#onPause
     * @see Activity#isFinishing
     */
    public void dispatchPause(boolean finishing) {
        if (finishing) {
            mFinishing = true;
        }
        mCurState = STARTED;
        if (mSingleMode) {
            if (mResumed != null) {
                moveToState(mResumed, STARTED);
            }
        } else {
            final int N = mActivityArray.size();
            for (int i=0; i<N; i++) {
                LocalActivityRecord r = mActivityArray.get(i);
                if (r.curState == RESUMED) {
                    moveToState(r, STARTED);
                }
            }
        }
    }

    /**
     * Called by the container activity in its {@link Activity#onStop} so
     * that LocalActivityManager can perform the corresponding action on the
     * activities it holds.
     * 
     * @see Activity#onStop
     */
    public void dispatchStop() {
        mCurState = CREATED;
        final int N = mActivityArray.size();
        for (int i=0; i<N; i++) {
            LocalActivityRecord r = mActivityArray.get(i);
            moveToState(r, CREATED);
        }
    }
    
    /**
     * Call onRetainNonConfigurationInstance on each child activity and store the
     * results in a HashMap by id.  Only construct the HashMap if there is a non-null
     * object to store.  Note that this does not support nested ActivityGroups.
     * 
     * {@hide}
     */
    public HashMap<String,Object> dispatchRetainNonConfigurationInstance() {
        HashMap<String,Object> instanceMap = null;
        
        final int N = mActivityArray.size();
        for (int i=0; i<N; i++) {
            LocalActivityRecord r = mActivityArray.get(i);
            if ((r != null) && (r.activity != null)) {
                Object instance = r.activity.onRetainNonConfigurationInstance();
                if (instance != null) {
                    if (instanceMap == null) {
                        instanceMap = new HashMap<String,Object>();
                    }
                    instanceMap.put(r.id, instance);
                }
            }
        }
        return instanceMap;
    }

    /**
     * Remove all activities from this LocalActivityManager, performing an
     * {@link Activity#onDestroy} on any that are currently instantiated.
     */
    public void removeAllActivities() {
        dispatchDestroy(true);
    }

    /**
     * Called by the container activity in its {@link Activity#onDestroy} so
     * that LocalActivityManager can perform the corresponding action on the
     * activities it holds.
     * 
     * @see Activity#onDestroy
     */
    public void dispatchDestroy(boolean finishing) {
        final int N = mActivityArray.size();
        for (int i=0; i<N; i++) {
            LocalActivityRecord r = mActivityArray.get(i);
            if (localLOGV) Log.v(TAG, r.id + ": destroying");
            mActivityThread.performDestroyActivity(r, finishing);
        }
        mActivities.clear();
        mActivityArray.clear();
    }
}
