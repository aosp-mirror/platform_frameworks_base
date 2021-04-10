/*
 * Copyright (C) 2015 The Android Open Source Project
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

/** {@hide} */
oneway interface IUidObserver {
    // WARNING: when these transactions are updated, check if they are any callers on the native
    // side. If so, make sure they are using the correct transaction ids and arguments.
    // If a transaction which will also be used on the native side is being inserted, add it to
    // below block of transactions.

    // Since these transactions are also called from native code, these must be kept in sync with
    // the ones in frameworks/native/include_activitymanager/binder/IActivityManager.h
    // =============== Beginning of transactions used on native side as well ======================

    /**
     * Report that there are no longer any processes running for a uid.
     */
    void onUidGone(int uid, boolean disabled);

    /**
     * Report that a uid is now active (no longer idle).
     */
    void onUidActive(int uid);

    /**
     * Report that a uid is idle -- it has either been running in the background for
     * a sufficient period of time, or all of its processes have gone away.
     */
    void onUidIdle(int uid, boolean disabled);

    /**
     * General report of a state change of an uid.
     *
     * @param uid The uid for which the state change is being reported.
     * @param procState The updated process state for the uid.
     * @param procStateSeq The sequence no. associated with process state change of the uid,
     *                     see UidRecord.procStateSeq for details.
     * @param capability the updated process capability for the uid.
     */
    void onUidStateChanged(int uid, int procState, long procStateSeq, int capability);

    // =============== End of transactions used on native side as well ============================

    /**
     * Report when the cached state of a uid has changed.
     * If true, a uid has become cached -- that is, it has some active processes that are
     * all in the cached state.  It should be doing as little as possible at this point.
     * If false, that a uid is no longer cached.  This will only be called after
     * onUidCached() has been reported true.  It will happen when either one of its actively
     * running processes is no longer cached, or it no longer has any actively running processes.
     */
    void onUidCachedChanged(int uid, boolean cached);
}
