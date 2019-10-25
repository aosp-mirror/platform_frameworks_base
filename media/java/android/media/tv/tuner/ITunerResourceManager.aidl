/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv.tuner;

import android.media.tv.tuner.ITunerResourceManagerListener;
import android.media.tv.tuner.ResourceClientProfile;
import android.media.tv.tuner.TunerFrontendInfo;
import android.media.tv.tuner.TunerFrontendRequest;

/**
 * Interface of the Tuner Resource Manager. It manages resources used by TV Tuners.
 * <p>Resources include:
 * <ul>
 * <li>TunerFrontend {@link android.media.tv.tuner.frontend}.
 * <li>TunerLnb {@link android.media.tv.tuner.Lnb}.
 * <li>MediaCas {@link android.media.MediaCas}.
 * <li>TvInputHardware {@link android.media.tv.TvInputHardwareInfo}.
 * <ul>
 *
 * <p>Expected workflow is:
 * <ul>
 * <li>Tuner Java/MediaCas/TIF update resources of the current device with TRM.
 * <li>Client registers its profile through {@link #registerClientProfile(ResourceClientProfile,
 * ITunerResourceManagerListener, int[])}.
 * <li>Client requests resources through request APIs.
 * <li>If the resource needs to be handed to a higher priority client from a lower priority
 * one, TRM calls ITunerResourceManagerListener registered by the lower priority client to release
 * the resource.
 * <ul>
 *
 * @hide
 */
interface ITunerResourceManager {
    /*
     * This API is used by the client to register their profile with the Tuner Resource manager.
     *
     * <p>The profile contains information that can show the base priority score of the client.
     *
     * @param profile {@link ResourceClientProfile} profile of the current client
     * @param listener {@link ITunerResourceManagerListener} a callback to
     *                 reclaim clients' resources when needed.
     * @param clientId returns a clientId from the resource manager when the
     *                 the client registers its profile.
     */
    void registerClientProfile(in ResourceClientProfile profile,
        ITunerResourceManagerListener listener, out int[] clientId);

    /*
     * This API is used by the client to unregister their profile with the Tuner Resource manager.
     *
     * @param clientId the client id that needs to be unregistered.
     */
    void unregisterClientProfile(in int clientId);

    /*
     * Updates a registered client's priority and niceValue.
     *
     * @param clientId the id of the client that is updating its profile.
     * @param priority the priority that the client would like to update to.
     * @param niceValue the nice value that the client would like to update to.
     *
     * @return true if the update is successful.
     */
    boolean updateClientPriority(in int clientId, in int priority, in int niceValue);

    /*
     * Updates the available Frontend resources information on the current device.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestFrontend(TunerFrontendRequest,int[])} and {@link #releaseFrontend(int)} call.
     *
     * @param infos an array of the available {@link TunerFrontendInfo} information.
     */
    void setFrontendInfoList(in TunerFrontendInfo[] infos);

    /*
     * This API is used by the Tuner framework to request an available frontend from the TunerHAL.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is frontend available, the API would send the id back.
     *
     * <li>If no Frontend is available but the current request info can show higher priority than
     * other uses of Frontend, the API will send
     * {@link ITunerResourceManagerListener#onResourcesReclaim()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no frontend can be granted, the API would return false.
     * <ul>
     *
     * <p><strong>Note:</strong> {@link #setFrontendInfoList(TunerFrontendInfo[])} must be called
     * before this request.
     *
     * @param request {@link TunerFrontendRequest} information of the current request.
     * @param frontendId a one-element array to return the granted frontendId.
     *
     * @return true if there is frontend granted.
     */
    boolean requestFrontend(in TunerFrontendRequest request, out int[] frontendId);

    /*
     * Requests to share frontend with an existing client.
     *
     * <p><strong>Note:</strong> {@link #setFrontendInfoList(TunerFrontendInfo[])} must be called
     * before this request.
     *
     * @param selfClientId the id of the client that sends the request.
     * @param targetClientId the id of the client to share the frontend with.
     */
    void shareFrontend(in int selfClientId, in int targetClientId);

    /*
     * Notifies the TRM that the given frontend has been released.
     *
     * <p>Client must call this whenever it releases a Tuner frontend.
     *
     * <p><strong>Note:</strong> {@link #setFrontendInfoList(TunerFrontendInfo[])} must be called
     * before this release.
     *
     * @param frontendId the id of the released frontend.
     */
    void releaseFrontend(in int frontendId);
}
