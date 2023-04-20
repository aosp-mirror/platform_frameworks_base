/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.dreams;


/**
 * Dream manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class DreamManagerInternal {
    /**
     * Called by the power manager to start a dream.
     *
     * @param doze If true, starts the doze dream component if one has been configured,
     * otherwise starts the user-specified dream.
     * @param reason The reason to start dreaming, which is logged to help debugging.
     */
    public abstract void startDream(boolean doze, String reason);

    /**
     * Called by the power manager to stop a dream.
     *
     * @param immediate If true, ends the dream summarily, otherwise gives it some time
     * to perform a proper exit transition.
     * @param reason The reason to stop dreaming, which is logged to help debugging.
     */
    public abstract void stopDream(boolean immediate, String reason);

    /**
     * Called by the power manager to determine whether a dream is running.
     */
    public abstract boolean isDreaming();

    /**
     * Ask the power manager to nap.  It will eventually call back into startDream() if/when it is
     * appropriate to start dreaming.
     */
    public abstract void requestDream();

    /**
     * Whether dreaming can start given user settings and the current dock/charge state.
     *
     * @param isScreenOn True if the screen is currently on.
     */
    public abstract boolean canStartDreaming(boolean isScreenOn);

    /**
     * Return whether dreams can continue when undocking by default. Even if the default is true,
     * it can be overridden temporarily, in which case {@link DreamManagerStateListener} will be
     * informed of any changes.
     */
    public abstract boolean keepDreamingWhenUndockedDefault();

    /**
     * Register a {@link DreamManagerStateListener}, which will be called when there are changes to
     * dream state.
     *
     * @param listener The listener to register.
     */
    public abstract void registerDreamManagerStateListener(DreamManagerStateListener listener);

    /**
     * Unregister a {@link DreamManagerStateListener}, which will be called when there are changes
     * to dream state.
     *
     * @param listener The listener to unregister.
     */
    public abstract void unregisterDreamManagerStateListener(DreamManagerStateListener listener);

    /**
     * Called when there are changes to dream state.
     */
    public interface DreamManagerStateListener {
        /**
         * Called when keep dreaming when undocked has changed.
         *
         * @param keepDreaming True if the current dream should continue when undocking.
         */
        void onKeepDreamingWhenUndockedChanged(boolean keepDreaming);
    }
}
