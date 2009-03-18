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

package com.android.server.am;

import com.android.server.AttributeCache;
import com.android.server.am.ActivityManagerService.ActivityState;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Log;
import android.view.IApplicationToken;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * An entry in the history stack, representing an activity.
 */
class HistoryRecord extends IApplicationToken.Stub {
    final ActivityManagerService service; // owner
    final ActivityInfo info; // all about me
    final int launchedFromUid; // always the uid who started the activity.
    final Intent intent;    // the original intent that generated us
    final ComponentName realActivity;  // the intent component, or target of an alias.
    final String shortComponentName; // the short component name of the intent
    final String resolvedType; // as per original caller;
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String taskAffinity; // as per ActivityInfo.taskAffinity
    final boolean stateNotNeeded; // As per ActivityInfo.flags
    final boolean fullscreen;     // covers the full screen?
    final boolean componentSpecified;  // did caller specifiy an explicit component?
    final boolean isHomeActivity; // do we consider this to be a home activity?
    final String baseDir;   // where activity source (resources etc) located
    final String resDir;   // where public activity source (public resources etc) located
    final String dataDir;   // where activity data should go
    CharSequence nonLocalizedLabel;  // the label information from the package mgr.
    int labelRes;           // the label information from the package mgr.
    int icon;               // resource identifier of activity's icon.
    int theme;              // resource identifier of activity's theme.
    TaskRecord task;        // the task this is in.
    long startTime;         // when we starting launching this activity
    Configuration configuration; // configuration activity was last running in
    HistoryRecord resultTo; // who started this entry, so will get our reply
    final String resultWho; // additional identifier for use by resultTo.
    final int requestCode;  // code given by requester (resultTo)
    ArrayList results;      // pending ActivityResult objs we have received
    HashSet<WeakReference<PendingIntentRecord>> pendingResults; // all pending intents for this act
    ArrayList newIntents;   // any pending new intents for single-top mode
    HashSet<ConnectionRecord> connections; // All ConnectionRecord we hold
    HashSet<UriPermission> readUriPermissions; // special access to reading uris.
    HashSet<UriPermission> writeUriPermissions; // special access to writing uris.
    ProcessRecord app;  // if non-null, hosting application
    Bitmap thumbnail;       // icon representation of paused screen
    CharSequence description; // textual description of paused screen
    ActivityManagerService.ActivityState state;    // current state we are in
    Bundle  icicle;         // last saved activity state
    boolean frontOfTask;    // is this the root activity of its task?
    boolean launchFailed;   // set if a launched failed, to abort on 2nd try
    boolean haveState;      // have we gotten the last activity state?
    boolean stopped;        // is activity pause finished?
    boolean finishing;      // activity in pending finish list?
    boolean configDestroy;  // need to destroy due to config change?
    int configChangeFlags;  // which config values have changed
    boolean keysPaused;     // has key dispatching been paused for it?
    boolean inHistory;      // are we in the history stack?
    boolean persistent;     // requested to be persistent?
    int launchMode;         // the launch mode activity attribute.
    boolean visible;        // does this activity's window need to be shown?
    boolean waitingVisible; // true if waiting for a new act to become vis
    boolean nowVisible;     // is this activity's window visible?
    boolean thumbnailNeeded;// has someone requested a thumbnail?
    boolean idle;           // has the activity gone idle?
    boolean hasBeenLaunched;// has this activity ever been launched?
    boolean frozenBeforeDestroy;// has been frozen but not yet destroyed.

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "packageName=" + packageName
              + " processName=" + processName);
        pw.println(prefix + "launchedFromUid=" + launchedFromUid
                + " app=" + app);
        pw.println(prefix + intent);
        pw.println(prefix + "frontOfTask=" + frontOfTask + " task=" + task);
        pw.println(prefix + "taskAffinity=" + taskAffinity);
        pw.println(prefix + "realActivity=" + realActivity);
        pw.println(prefix + "dir=" + baseDir + " res=" + resDir + " data=" + dataDir);
        pw.println(prefix + "labelRes=0x" + Integer.toHexString(labelRes)
                + " icon=0x" + Integer.toHexString(icon)
                + " theme=0x" + Integer.toHexString(theme));
        pw.println(prefix + "stateNotNeeded=" + stateNotNeeded
                + " componentSpecified=" + componentSpecified
                + " isHomeActivity=" + isHomeActivity);
        pw.println(prefix + "configuration=" + configuration);
        pw.println(prefix + "resultTo=" + resultTo
              + " resultWho=" + resultWho + " resultCode=" + requestCode);
        pw.println(prefix + "results=" + results);
        pw.println(prefix + "pendingResults=" + pendingResults);
        pw.println(prefix + "readUriPermissions=" + readUriPermissions);
        pw.println(prefix + "writeUriPermissions=" + writeUriPermissions);
        pw.println(prefix + "launchFailed=" + launchFailed
              + " haveState=" + haveState + " icicle=" + icicle);
        pw.println(prefix + "state=" + state
              + " stopped=" + stopped + " finishing=" + finishing);
        pw.println(prefix + "keysPaused=" + keysPaused
              + " inHistory=" + inHistory + " persistent=" + persistent
              + " launchMode=" + launchMode);
        pw.println(prefix + "fullscreen=" + fullscreen
              + " visible=" + visible
              + " frozenBeforeDestroy=" + frozenBeforeDestroy
              + " thumbnailNeeded=" + thumbnailNeeded + " idle=" + idle);
        pw.println(prefix + "waitingVisible=" + waitingVisible
              + " nowVisible=" + nowVisible);
        pw.println(prefix + "configDestroy=" + configDestroy
                + " configChangeFlags=" + Integer.toHexString(configChangeFlags));
        pw.println(prefix + "connections=" + connections);
    }

    HistoryRecord(ActivityManagerService _service, ProcessRecord _caller,
            int _launchedFromUid, Intent _intent, String _resolvedType,
            ActivityInfo aInfo, Configuration _configuration,
            HistoryRecord _resultTo, String _resultWho, int _reqCode,
            boolean _componentSpecified) {
        service = _service;
        info = aInfo;
        launchedFromUid = _launchedFromUid;
        intent = _intent;
        shortComponentName = _intent.getComponent().flattenToShortString();
        resolvedType = _resolvedType;
        componentSpecified = _componentSpecified;
        configuration = _configuration;
        resultTo = _resultTo;
        resultWho = _resultWho;
        requestCode = _reqCode;
        state = ActivityManagerService.ActivityState.INITIALIZING;
        frontOfTask = false;
        launchFailed = false;
        haveState = false;
        stopped = false;
        finishing = false;
        configDestroy = false;
        keysPaused = false;
        inHistory = false;
        persistent = false;
        visible = true;
        waitingVisible = false;
        nowVisible = false;
        thumbnailNeeded = false;
        idle = false;
        hasBeenLaunched = false;

        if (aInfo != null) {
            if (aInfo.targetActivity == null
                    || aInfo.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                    || aInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                realActivity = _intent.getComponent();
            } else {
                realActivity = new ComponentName(aInfo.packageName,
                        aInfo.targetActivity);
            }
            taskAffinity = aInfo.taskAffinity;
            stateNotNeeded = (aInfo.flags&
                    ActivityInfo.FLAG_STATE_NOT_NEEDED) != 0;
            baseDir = aInfo.applicationInfo.sourceDir;
            resDir = aInfo.applicationInfo.publicSourceDir;
            dataDir = aInfo.applicationInfo.dataDir;
            nonLocalizedLabel = aInfo.nonLocalizedLabel;
            labelRes = aInfo.labelRes;
            if (nonLocalizedLabel == null && labelRes == 0) {
                ApplicationInfo app = aInfo.applicationInfo;
                nonLocalizedLabel = app.nonLocalizedLabel;
                labelRes = app.labelRes;
            }
            icon = aInfo.getIconResource();
            theme = aInfo.getThemeResource();
            if ((aInfo.flags&ActivityInfo.FLAG_MULTIPROCESS) != 0
                    && _caller != null
                    && (aInfo.applicationInfo.uid == Process.SYSTEM_UID
                            || aInfo.applicationInfo.uid == _caller.info.uid)) {
                processName = _caller.processName;
            } else {
                processName = aInfo.processName;
            }

            if (intent != null && (aInfo.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0) {
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            }
            
            packageName = aInfo.applicationInfo.packageName;
            launchMode = aInfo.launchMode;
            
            AttributeCache.Entry ent = AttributeCache.instance().get(packageName,
                    theme != 0 ? theme : android.R.style.Theme,
                    com.android.internal.R.styleable.Window);
            fullscreen = ent != null && !ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsFloating, false)
                    && !ent.array.getBoolean(
                    com.android.internal.R.styleable.Window_windowIsTranslucent, false);
            
            if (!_componentSpecified || _launchedFromUid == Process.myUid()
                    || _launchedFromUid == 0) {
                // If we know the system has determined the component, then
                // we can consider this to be a home activity...
                if (Intent.ACTION_MAIN.equals(_intent.getAction()) &&
                        _intent.hasCategory(Intent.CATEGORY_HOME) &&
                        _intent.getCategories().size() == 1 &&
                        _intent.getData() == null &&
                        _intent.getType() == null &&
                        (intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                        !"android".equals(realActivity.getClassName())) {
                    // This sure looks like a home activity!
                    // Note the last check is so we don't count the resolver
                    // activity as being home...  really, we don't care about
                    // doing anything special with something that comes from
                    // the core framework package.
                    isHomeActivity = true;
                } else {
                    isHomeActivity = false;
                }
            } else {
                isHomeActivity = false;
            }
        } else {
            realActivity = null;
            taskAffinity = null;
            stateNotNeeded = false;
            baseDir = null;
            resDir = null;
            dataDir = null;
            processName = null;
            packageName = null;
            fullscreen = true;
            isHomeActivity = false;
        }
    }

    void addResultLocked(HistoryRecord from, String resultWho,
            int requestCode, int resultCode,
            Intent resultData) {
        ActivityResult r = new ActivityResult(from, resultWho,
        		requestCode, resultCode, resultData);
        if (results == null) {
            results = new ArrayList();
        }
        results.add(r);
    }

    void removeResultsLocked(HistoryRecord from, String resultWho,
            int requestCode) {
        if (results != null) {
            for (int i=results.size()-1; i>=0; i--) {
                ActivityResult r = (ActivityResult)results.get(i);
                if (r.mFrom != from) continue;
                if (r.mResultWho == null) {
                    if (resultWho != null) continue;
                } else {
                    if (!r.mResultWho.equals(resultWho)) continue;
                }
                if (r.mRequestCode != requestCode) continue;

                results.remove(i);
            }
        }
    }

    void addNewIntentLocked(Intent intent) {
        if (newIntents == null) {
            newIntents = new ArrayList();
        }
        newIntents.add(intent);
    }

    void pauseKeyDispatchingLocked() {
        if (!keysPaused) {
            keysPaused = true;
            service.mWindowManager.pauseKeyDispatching(this);
        }
    }

    void resumeKeyDispatchingLocked() {
        if (keysPaused) {
            keysPaused = false;
            service.mWindowManager.resumeKeyDispatching(this);
        }
    }

    // IApplicationToken

    public boolean mayFreezeScreenLocked(ProcessRecord app) {
        // Only freeze the screen if this activity is currently attached to
        // an application, and that application is not blocked or unresponding.
        // In any other case, we can't count on getting the screen unfrozen,
        // so it is best to leave as-is.
        return app == null || (!app.crashing && !app.notResponding);
    }
    
    public void startFreezingScreenLocked(ProcessRecord app, int configChanges) {
        if (mayFreezeScreenLocked(app)) {
            service.mWindowManager.startAppFreezingScreen(this, configChanges);
        }
    }
    
    public void stopFreezingScreenLocked(boolean force) {
        if (force || frozenBeforeDestroy) {
            frozenBeforeDestroy = false;
            service.mWindowManager.stopAppFreezingScreen(this, force);
        }
    }
    
    public void windowsVisible() {
        synchronized(service) {
            if (ActivityManagerService.SHOW_ACTIVITY_START_TIME
                    && startTime != 0) {
                long time = SystemClock.uptimeMillis() - startTime;
                EventLog.writeEvent(ActivityManagerService.LOG_ACTIVITY_LAUNCH_TIME,
                        System.identityHashCode(this), shortComponentName, time);
                Log.i(ActivityManagerService.TAG, "Displayed activity "
                        + shortComponentName
                        + ": " + time + " ms");
                startTime = 0;
            }
            if (ActivityManagerService.DEBUG_SWITCH) Log.v(
                    ActivityManagerService.TAG, "windowsVisible(): " + this);
            if (!nowVisible) {
                nowVisible = true;
                if (!idle) {
                    // Instead of doing the full stop routine here, let's just
                    // hide any activities we now can, and let them stop when
                    // the normal idle happens.
                    service.processStoppingActivitiesLocked(false);
                } else {
                    // If this activity was already idle, then we now need to
                    // make sure we perform the full stop of any activities
                    // that are waiting to do so.  This is because we won't
                    // do that while they are still waiting for this one to
                    // become visible.
                    final int N = service.mWaitingVisibleActivities.size();
                    if (N > 0) {
                        for (int i=0; i<N; i++) {
                            HistoryRecord r = (HistoryRecord)
                                service.mWaitingVisibleActivities.get(i);
                            r.waitingVisible = false;
                            if (ActivityManagerService.DEBUG_SWITCH) Log.v(
                                    ActivityManagerService.TAG,
                                    "Was waiting for visible: " + r);
                        }
                        service.mWaitingVisibleActivities.clear();
                        Message msg = Message.obtain();
                        msg.what = ActivityManagerService.IDLE_NOW_MSG;
                        service.mHandler.sendMessage(msg);
                    }
                }
                service.scheduleAppGcsLocked();
            }
        }
    }

    public void windowsGone() {
        if (ActivityManagerService.DEBUG_SWITCH) Log.v(
                ActivityManagerService.TAG, "windowsGone(): " + this);
        nowVisible = false;
    }
    
    private HistoryRecord getWaitingHistoryRecordLocked() {
        // First find the real culprit...  if we are waiting
        // for another app to start, then we have paused dispatching
        // for this activity.
        HistoryRecord r = this;
        if (r.waitingVisible) {
            // Hmmm, who might we be waiting for?
            r = service.mResumedActivity;
            if (r == null) {
                r = service.mPausingActivity;
            }
            // Both of those null?  Fall back to 'this' again
            if (r == null) {
                r = this;
            }
        }
        
        return r;
    }

    public boolean keyDispatchingTimedOut() {
        synchronized(service) {
            HistoryRecord r = getWaitingHistoryRecordLocked();
            if (r != null && r.app != null) {
                if (r.app.debugging) {
                    return false;
                }
                
                if (r.app.instrumentationClass == null) { 
                    service.appNotRespondingLocked(r.app, r, "keyDispatchingTimedOut");
                } else {
                    Bundle info = new Bundle();
                    info.putString("shortMsg", "keyDispatchingTimedOut");
                    info.putString("longMsg", "Timed out while dispatching key event");
                    service.finishInstrumentationLocked(
                            r.app, Activity.RESULT_CANCELED, info);
                }
            }
            return true;
        }
    }
    
    /** Returns the key dispatching timeout for this application token. */
    public long getKeyDispatchingTimeout() {
        synchronized(service) {
            HistoryRecord r = getWaitingHistoryRecordLocked();
            if (r == null || r.app == null
                    || r.app.instrumentationClass == null) {
                return ActivityManagerService.KEY_DISPATCHING_TIMEOUT;
            }
            
            return ActivityManagerService.INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT;
        }
    }

    /**
     * This method will return true if the activity is either visible, is becoming visible, is
     * currently pausing, or is resumed.
     */
    public boolean isInterestingToUserLocked() {
        return visible || nowVisible || state == ActivityState.PAUSING || 
                state == ActivityState.RESUMED;
     }
    
    
    public String toString() {
        return "HistoryRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + intent.getComponent().toShortString() + "}";
    }
}
