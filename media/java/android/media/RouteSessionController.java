/*
 * Copyright 2019 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;


import com.android.internal.annotations.GuardedBy;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A class to control media route session in media route provider.
 * For example, adding/removing/transferring routes to session can be done through this class.
 * Instances are created by {@link MediaRouter2}.
 *
 * TODO: When session is introduced, change Javadoc of all methods/classes by using [@link Session].
 *
 * @hide
 */
public class RouteSessionController {
    private final int mSessionId;
    private final String mCategory;
    private final Object mLock = new Object();
    private final Bundle mControlHints;

    private List<String> mSelectedRoutes;

    @GuardedBy("mLock")
    private final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords =
            new CopyOnWriteArrayList<>();

    private volatile boolean mIsReleased;

    /**
     * @param sessionInfo
     */
    RouteSessionController(@NonNull RouteSessionInfo sessionInfo) {
        mSessionId = sessionInfo.getSessionId();
        mCategory = sessionInfo.getControlCategory();
        mSelectedRoutes = sessionInfo.getSelectedRoutes();
        mControlHints = sessionInfo.getControlHints();
        // TODO: Create getters for all other types of routes
    }

    /**
     * @return the ID of this controller
     */
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * @return the category of routes that the session includes.
     */
    @NonNull
    public String getCategory() {
        return mCategory;
    }

    /**
     * @return the control hints used to control route session if available.
     */
    @Nullable
    public Bundle getControlHints() {
        return mControlHints;
    }

    /**
     * @return the list of currently selected routes
     */
    @NonNull
    public List<String> getSelectedRoutes() {
        return Collections.unmodifiableList(mSelectedRoutes);
    }

    /**
     * Returns true if the session is released, false otherwise.
     * If it is released, then all other getters from this instance may return invalid values.
     * Also, any operations to this instance will be ignored once released.
     *
     * @see #release
     * @see Callback#onReleased
     */
    public boolean isReleased() {
        return mIsReleased;
    }

    /**
     * Add routes to the remote session.
     *
     * @see #getSelectedRoutes()
     * @see Callback#onSessionInfoChanged
     */
    public void addRoutes(List<MediaRoute2Info> routes) {
        // TODO: Implement this when the actual connection logic is implemented.
    }

    /**
     * Remove routes from this session. Media may be stopped on those devices.
     * Route removal requests that are not currently in {@link #getSelectedRoutes()} will be
     * ignored.
     *
     * @see #getSelectedRoutes()
     * @see Callback#onSessionInfoChanged
     */
    public void removeRoutes(List<MediaRoute2Info> routes) {
        // TODO: Implement this when the actual connection logic is implemented.
    }

    /**
     * Registers a {@link Callback} for monitoring route changes.
     * If the same callback is registered previously, previous executor will be overwritten with the
     * new one.
     */
    public void registerCallback(Executor executor, Callback callback) {
        if (mIsReleased) {
            return;
        }
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        synchronized (mLock) {
            CallbackRecord recordWithSameCallback = null;
            for (CallbackRecord record : mCallbackRecords) {
                if (callback == record.mCallback) {
                    recordWithSameCallback = record;
                    break;
                }
            }

            if (recordWithSameCallback != null) {
                recordWithSameCallback.mExecutor = executor;
            } else {
                mCallbackRecords.add(new CallbackRecord(executor, callback));
            }
        }
    }

    /**
     * Unregisters a previously registered {@link Callback}.
     */
    public void unregisterCallback(Callback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        synchronized (mLock) {
            CallbackRecord recordToRemove = null;
            for (CallbackRecord record : mCallbackRecords) {
                if (callback == record.mCallback) {
                    recordToRemove = record;
                    break;
                }
            }

            if (recordToRemove != null) {
                mCallbackRecords.remove(recordToRemove);
            }
        }
    }

    /**
     * Release this session.
     * Any operation on this session after calling this method will be ignored.
     *
     * @param stopMedia Should the device where the media is played
     *                  be stopped after this session is released.
     */
    public void release(boolean stopMedia) {
        mIsReleased = true;
        mCallbackRecords.clear();
        // TODO: Use stopMedia variable when the actual connection logic is implemented.
    }

    /**
     * Callback class for getting updates on routes and session release.
     */
    public static class Callback {

        /**
         * Called when the session info has changed.
         * TODO: When SessionInfo is introduced, uncomment below argument.
         */
        void onSessionInfoChanged(/* SessionInfo info */) {}

        /**
         * Called when the session is released. Session can be released by the controller using
         * {@link #release(boolean)}, or by the {@link MediaRoute2ProviderService} itself.
         * One can do clean-ups here.
         *
         * TODO: When SessionInfo is introduced, change the javadoc of releasing session on
         * provider side.
         */
        void onReleased(int reason, boolean shouldStop) {}
    }

    private class CallbackRecord {
        public final Callback mCallback;
        public Executor mExecutor;

        CallbackRecord(@NonNull Executor executor, @NonNull Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }
    }
}
