/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import android.content.ComponentName;
import android.os.Process;
import android.service.vr.IPersistentVrStateCallbacks;
import android.util.Slog;
import com.android.server.LocalServices;
import com.android.server.vr.VrManagerInternal;

/**
 * Helper class for {@link ActivityManagerService} responsible for VrMode-related ActivityManager
 * functionality.
 *
 * <p>Specifically, this class is responsible for:
 * <ul>
 * <li>Adjusting the scheduling of VR render threads while in VR mode.
 * <li>Handling ActivityManager calls to set a VR or a 'persistent' VR thread.
 * <li>Tracking the state of ActivityManagerService's view of VR-related behavior flags.
 * </ul>
 *
 * <p>This is NOT the class that manages the system VR mode lifecycle. The class responsible for
 * handling everything related to VR mode state changes (e.g. the lifecycles of the associated
 * VrListenerService, VrStateCallbacks, VR HAL etc.) is VrManagerService.
 *
 * <p>This class is exclusively for use by ActivityManagerService. Do not add callbacks or other
 * functionality to this for things that belong in VrManagerService.
 */
final class VrController {
    private static final String TAG = "VrController";

    // VR state flags.
    private static final int FLAG_NON_VR_MODE = 0;
    private static final int FLAG_VR_MODE = 1;
    private static final int FLAG_PERSISTENT_VR_MODE = 2;

    // Invariants maintained for mVrState
    //
    //   Always true:
    //      - Only a single VR-related thread will have elevated scheduling priorities at a time
    //        across all threads in all processes (and for all possible running modes).
    //
    //   Always true while FLAG_PERSISTENT_VR_MODE is set:
    //      - An application has set a flag to run in persistent VR mode the next time VR mode is
    //        entered. The device may or may not be in VR mode.
    //      - mVrState will contain FLAG_PERSISTENT_VR_MODE
    //      - An application may set a persistent VR thread that gains elevated scheduling
    //        priorities via a call to setPersistentVrThread.
    //      - Calls to set a regular (non-persistent) VR thread via setVrThread will fail, and
    //        thread that had previously elevated its scheduling priority in this way is returned
    //        to its normal scheduling priority.
    //
    //   Always true while FLAG_VR_MODE is set:
    //      - The current top application is running in VR mode.
    //      - mVrState will contain FLAG_VR_MODE
    //
    //   While FLAG_VR_MODE is set without FLAG_PERSISTENT_VR_MODE:
    //      - The current top application may set one of its threads to run at an elevated
    //        scheduling priority via a call to setVrThread.
    //
    //   While FLAG_VR_MODE is set with FLAG_PERSISTENT_VR_MODE:
    //      - The current top application may NOT set one of its threads to run at an elevated
    //        scheduling priority via a call to setVrThread (instead, the persistent VR thread will
    //        be kept if an application has set one).
    //
    //   While mVrState == FLAG_NON_VR_MODE:
    //      - Calls to setVrThread will fail.
    //      - Calls to setPersistentVrThread will fail.
    //      - No threads will have elevated scheduling priority for VR.
    //
    private int mVrState = FLAG_NON_VR_MODE;

    // The single VR render thread on the device that is given elevated scheduling priority.
    private int mVrRenderThreadTid = 0;

    private final Object mGlobalAmLock;

    private final IPersistentVrStateCallbacks mPersistentVrModeListener =
            new IPersistentVrStateCallbacks.Stub() {
        @Override
        public void onPersistentVrStateChanged(boolean enabled) {
            synchronized(mGlobalAmLock) {
                // Note: This is the only place where mVrState should have its
                // FLAG_PERSISTENT_VR_MODE setting changed.
                if (enabled) {
                    setVrRenderThreadLocked(0, ProcessList.SCHED_GROUP_TOP_APP, true);
                    mVrState |= FLAG_PERSISTENT_VR_MODE;
                } else {
                    setPersistentVrRenderThreadLocked(0, true);
                    mVrState &= ~FLAG_PERSISTENT_VR_MODE;
                }
            }
        }
    };

    /**
     * Create new VrController instance.
     *
     * @param globalAmLock the global ActivityManagerService lock.
     */
    public VrController(final Object globalAmLock) {
        mGlobalAmLock = globalAmLock;
    }

    /**
     * Called when ActivityManagerService receives its systemReady call during boot.
     */
    public void onSystemReady() {
        VrManagerInternal vrManagerInternal = LocalServices.getService(VrManagerInternal.class);
        if (vrManagerInternal != null) {
            vrManagerInternal.addPersistentVrModeStateListener(mPersistentVrModeListener);
        }
    }

    /**
     * Called when ActivityManagerService's TOP_APP process has changed.
     *
     * <p>Note: This must be called with the global ActivityManagerService lock held.
     *
     * @param proc is the ProcessRecord of the process that entered or left the TOP_APP scheduling
     *        group.
     */
    public void onTopProcChangedLocked(ProcessRecord proc) {
        if (proc.curSchedGroup == ProcessList.SCHED_GROUP_TOP_APP) {
            setVrRenderThreadLocked(proc.vrThreadTid, proc.curSchedGroup, true);
        } else {
            if (proc.vrThreadTid == mVrRenderThreadTid) {
                clearVrRenderThreadLocked(true);
            }
        }
    }

    /**
     * Called when ActivityManagerService is switching VR mode for the TOP_APP process.
     *
     * @param record the ActivityRecord of the activity changing the system VR mode.
     * @return {@code true} if the VR state changed.
     */
    public boolean onVrModeChanged(ActivityRecord record) {
        // This message means that the top focused activity enabled VR mode (or an activity
        // that previously set this has become focused).
        VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);
        if (vrService == null) {
            // VR mode isn't supported on this device.
            return false;
        }
        boolean vrMode;
        ComponentName requestedPackage;
        ComponentName callingPackage;
        int userId;
        int processId = -1;
        boolean changed = false;
        synchronized (mGlobalAmLock) {
            vrMode = record.requestedVrComponent != null;
            requestedPackage = record.requestedVrComponent;
            userId = record.userId;
            callingPackage = record.info.getComponentName();

            // Tell the VrController that a VR mode change is requested.
            changed = changeVrModeLocked(vrMode, record.app);

            if (record.app != null) {
                processId = record.app.pid;
            }
        }

        // Tell VrManager that a VR mode changed is requested, VrManager will handle
        // notifying all non-AM dependencies if needed.
        vrService.setVrMode(vrMode, requestedPackage, userId, processId, callingPackage);
        return changed;
    }

    /**
     * Called to set an application's VR thread.
     *
     * <p>This will fail if the system is not in VR mode, the system has the persistent VR flag set,
     * or the scheduling group of the thread is not for the current top app.  If this succeeds, any
     * previous VR thread will be returned to a normal sheduling priority; if this fails, the
     * scheduling for the previous thread will be unaffected.
     *
     * <p>Note: This must be called with the global ActivityManagerService lock and the
     *     mPidsSelfLocked object locks held.
     *
     * @param tid the tid of the thread to set, or 0 to unset the current thread.
     * @param pid the pid of the process owning the thread to set.
     * @param proc the ProcessRecord of the process owning the thread to set.
     */
    public void setVrThreadLocked(int tid, int pid, ProcessRecord proc) {
        if (hasPersistentVrFlagSet()) {
            Slog.w(TAG, "VR thread cannot be set in persistent VR mode!");
            return;
        }
        if (proc == null) {
           Slog.w(TAG, "Persistent VR thread not set, calling process doesn't exist!");
           return;
        }
        if (tid != 0) {
            enforceThreadInProcess(tid, pid);
        }
        if (!inVrMode()) {
            Slog.w(TAG, "VR thread cannot be set when not in VR mode!");
        } else {
            setVrRenderThreadLocked(tid, proc.curSchedGroup, false);
        }
        proc.vrThreadTid = (tid > 0) ? tid : 0;
    }

    /**
     * Called to set an application's persistent VR thread.
     *
     * <p>This will fail if the system does not have the persistent VR flag set. If this succeeds,
     * any previous VR thread will be returned to a normal sheduling priority; if this fails,
     * the scheduling for the previous thread will be unaffected.
     *
     * <p>Note: This must be called with the global ActivityManagerService lock and the
     *     mPidsSelfLocked object locks held.
     *
     * @param tid the tid of the thread to set, or 0 to unset the current thread.
     * @param pid the pid of the process owning the thread to set.
     * @param proc the ProcessRecord of the process owning the thread to set.
     */
    public void setPersistentVrThreadLocked(int tid, int pid, ProcessRecord proc) {
        if (!hasPersistentVrFlagSet()) {
            Slog.w(TAG, "Persistent VR thread may only be set in persistent VR mode!");
            return;
        }
        if (proc == null) {
           Slog.w(TAG, "Persistent VR thread not set, calling process doesn't exist!");
           return;
        }
        if (tid != 0) {
            enforceThreadInProcess(tid, pid);
        }
        setPersistentVrRenderThreadLocked(tid, false);
    }

    /**
     * Return {@code true} when UI features incompatible with VR mode should be disabled.
     *
     * <p>Note: This must be called with the global ActivityManagerService lock held.
     */
    public boolean shouldDisableNonVrUiLocked() {
        return mVrState != FLAG_NON_VR_MODE;
    }

    /**
     * Called when to update this VrController instance's state when the system VR mode is being
     * changed.
     *
     * <p>Note: This must be called with the global ActivityManagerService lock held.
     *
     * @param vrMode {@code true} if the system VR mode is being enabled.
     * @param proc the ProcessRecord of the process enabling the system VR mode.
     *
     * @return {@code true} if our state changed.
     */
    private boolean changeVrModeLocked(boolean vrMode, ProcessRecord proc) {
        final int oldVrState = mVrState;

        // This is the only place where mVrState should have its FLAG_VR_MODE setting
        // changed.
        if (vrMode) {
            mVrState |= FLAG_VR_MODE;
        } else {
            mVrState &= ~FLAG_VR_MODE;
        }

        boolean changed = (oldVrState != mVrState);

        if (changed) {
            if (proc != null) {
                if (proc.vrThreadTid > 0) {
                    setVrRenderThreadLocked(proc.vrThreadTid, proc.curSchedGroup, false);
                }
            } else {
              clearVrRenderThreadLocked(false);
            }
        }
        return changed;
    }

    /**
     * Set the given thread as the new VR thread, and give it special scheduling priority.
     *
     * <p>If the current thread is this thread, do nothing. If the current thread is different from
     * the given thread, the current thread will be returned to a normal scheduling priority.
     *
     * @param newTid the tid of the thread to set, or 0 to unset the current thread.
     * @param suppressLogs {@code true} if any error logging should be disabled.
     *
     * @return the tid of the thread configured to run at the scheduling priority for VR
     *          mode after this call completes (this may be the previous thread).
     */
    private int updateVrRenderThreadLocked(int newTid, boolean suppressLogs) {
        if (mVrRenderThreadTid == newTid) {
            return mVrRenderThreadTid;
        }

        if (mVrRenderThreadTid > 0) {
            ActivityManagerService.scheduleAsRegularPriority(mVrRenderThreadTid, suppressLogs);
            mVrRenderThreadTid = 0;
        }

        if (newTid > 0) {
            mVrRenderThreadTid = newTid;
            ActivityManagerService.scheduleAsFifoPriority(mVrRenderThreadTid, suppressLogs);
        }
        return mVrRenderThreadTid;
    }

    /**
     * Set special scheduling for the given application persistent VR thread, if allowed.
     *
     * <p>This will fail if the system does not have the persistent VR flag set. If this succeeds,
     * any previous VR thread will be returned to a normal sheduling priority; if this fails,
     * the scheduling for the previous thread will be unaffected.
     *
     * @param newTid the tid of the thread to set, or 0 to unset the current thread.
     * @param suppressLogs {@code true} if any error logging should be disabled.
     *
     * @return the tid of the thread configured to run at the scheduling priority for VR
     *          mode after this call completes (this may be the previous thread).
     */
    private int setPersistentVrRenderThreadLocked(int newTid, boolean suppressLogs) {
       if (!hasPersistentVrFlagSet()) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set persistent VR thread, "
                        + "system not in persistent VR mode.");
            }
            return mVrRenderThreadTid;
        }
        return updateVrRenderThreadLocked(newTid, suppressLogs);
    }

    /**
     * Set special scheduling for the given application VR thread, if allowed.
     *
     * <p>This will fail if the system is not in VR mode, the system has the persistent VR flag set,
     * or the scheduling group of the thread is not for the current top app.  If this succeeds, any
     * previous VR thread will be returned to a normal sheduling priority; if this fails, the
     * scheduling for the previous thread will be unaffected.
     *
     * @param newTid the tid of the thread to set, or 0 to unset the current thread.
     * @param schedGroup the current scheduling group of the thread to set.
     * @param suppressLogs {@code true} if any error logging should be disabled.
     *
     * @return the tid of the thread configured to run at the scheduling priority for VR
     *          mode after this call completes (this may be the previous thread).
     */
    private int setVrRenderThreadLocked(int newTid, int schedGroup, boolean suppressLogs) {
        boolean inVr = inVrMode();
        boolean inPersistentVr = hasPersistentVrFlagSet();
        if (!inVr || inPersistentVr || schedGroup != ProcessList.SCHED_GROUP_TOP_APP) {
            if (!suppressLogs) {
               String reason = "caller is not the current top application.";
               if (!inVr) {
                   reason = "system not in VR mode.";
               } else if (inPersistentVr) {
                   reason = "system in persistent VR mode.";
               }
               Slog.w(TAG, "Failed to set VR thread, " + reason);
            }
            return mVrRenderThreadTid;
        }
        return updateVrRenderThreadLocked(newTid, suppressLogs);
    }

    /**
     * Unset any special scheduling used for the current VR render thread, and return it to normal
     * scheduling priority.
     *
     * @param suppressLogs {@code true} if any error logging should be disabled.
     */
    private void clearVrRenderThreadLocked(boolean suppressLogs) {
        updateVrRenderThreadLocked(0, suppressLogs);
    }

    /**
     * Check that the given tid is running in the process for the given pid, and throw an exception
     * if not.
     */
    private void enforceThreadInProcess(int tid, int pid) {
        if (!Process.isThreadInProcess(pid, tid)) {
            throw new IllegalArgumentException("VR thread does not belong to process");
        }
    }

    /**
     * True when the system is in VR mode.
     */
    private boolean inVrMode() {
        return (mVrState & FLAG_VR_MODE) != 0;
    }

    /**
     * True when the persistent VR mode flag has been set.
     *
     * Note: Currently this does not necessarily mean that the system is in VR mode.
     */
    private boolean hasPersistentVrFlagSet() {
        return (mVrState & FLAG_PERSISTENT_VR_MODE) != 0;
    }

    @Override
    public String toString() {
      return String.format("[VrState=0x%x,VrRenderThreadTid=%d]", mVrState, mVrRenderThreadTid);
    }
}
