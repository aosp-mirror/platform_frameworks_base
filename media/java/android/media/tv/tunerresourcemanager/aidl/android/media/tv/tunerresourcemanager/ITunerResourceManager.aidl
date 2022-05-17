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

package android.media.tv.tunerresourcemanager;

import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;

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
 * IResourcesReclaimListener, int[])}.
 * <li>Client requests resources through request APIs.
 * <li>If the resource needs to be handed to a higher priority client from a lower priority
 * one, TRM calls IResourcesReclaimListener registered by the lower priority client to release
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
     * @param listener {@link IResourcesReclaimListener} a callback to
     *                 reclaim clients' resources when needed.
     * @param clientId returns a clientId from the resource manager when the
     *                 the client registers its profile.
     */
    void registerClientProfile(in ResourceClientProfile profile,
        IResourcesReclaimListener listener, out int[] clientId);

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
     * Checks if there is any unused frontend resource of the specified type.
     *
     * @param frontendType the specific type of frontend resource to be checked for.
     *
     * @return true if there is any unused resource of the specified type.
     */
    boolean hasUnusedFrontend(in int frontendType);

    /*
     * Checks if the client has the lowest priority among the clients that are holding
     * the frontend resource of the specified type.
     *
     * <p> When this function returns false, it means that there is at least one client with the
     * strictly lower priority (than clientId) that is reclaimable by the system.
     *
     * @param clientId The client ID to be checked the priority for.
     * @param frontendType The specific frontend type to be checked for.
     *
     * @return false if there is another client holding the frontend resource of the specified type
     * that can be reclaimed. Otherwise true.
     */
    boolean isLowestPriority(in int clientId, in int frontendType);

    /*
     * Updates the available Frontend resources information on the current device.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestFrontend(TunerFrontendRequest,int[])} and {@link #releaseFrontend(int, int)}
     * call.
     *
     * @param infos an array of the available {@link TunerFrontendInfo} information.
     */
    void setFrontendInfoList(in TunerFrontendInfo[] infos);

    /*
     * Updates the available Cas resource information on the current device.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestCasSession(CasSessionRequest, int[])} and {@link #releaseCasSession(int, int)}
     * call.
     *
     * @param casSystemId id of the updating CAS system.
     * @param maxSessionNum the max session number of the CAS system that is updated.
     */
    void updateCasInfo(in int casSystemId, in int maxSessionNum);

    /*
     * Updates the available Lnb resource information on the current device.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestLnb(TunerLnbRequest, int[])} and {@link #releaseLnb(int, int)} call.
     *
     * @param lnbIds ids of the updating lnbs.
     */
    void setLnbInfoList(in int[] lnbIds);

    /*
     * This API is used by the Tuner framework to request an available frontend from the TunerHAL.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is frontend available, the API would send the id back.
     *
     * <li>If no Frontend is available but the current request info can show higher priority than
     * other uses of Frontend, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
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
     * @param frontendHandle a one-element array to return the granted frontendHandle.
     *
     * @return true if there is frontend granted.
     */
    boolean requestFrontend(in TunerFrontendRequest request, out int[] frontendHandle);

    /*
     * Sets the maximum usable frontends number of a given frontend type. It is used to enable or
     * disable frontends when cable connection status is changed by user.
     *
     * @param frontendType the frontendType which the maximum usable number will be set for.
     * @param maxNumber the new maximum usable number.
     *
     * @return true if  successful and false otherwise.
     */
    boolean setMaxNumberOfFrontends(in int frontendType, in int maxNum);

    /*
     * Get the maximum usable frontends number of a given frontend type.
     *
     * @param frontendType the frontendType which the maximum usable number will be queried for.
     *
     * @return the maximum usable number of the queried frontend type. Returns -1 when the
     *         frontendType is invalid
     */
    int getMaxNumberOfFrontends(in int frontendType);

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
     * Transfers the ownership of the shared resource.
     *
     * <p><strong>Note:</strong> Only the existing frontend sharee can be the new owner.
     *
     * @param resourceType the type of resource to transfer the ownership for.
     * @param currentOwnerId the id of the current owner client.
     * @param newOwnerId the id of the new owner client.
     *
     * @return true if successful. false otherwise.
     */
    boolean transferOwner(in int resourceType, in int currentOwnerId, in int newOwnerId);

    /*
     * This API is used by the Tuner framework to request an available demux from the TunerHAL.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is demux available, the API would send the handle back.
     *
     * <li>If no Demux is available but the current request info can show higher priority than
     * other uses of demuxes, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no demux can be granted, the API would return false.
     * <ul>
     *
     * @param request {@link TunerDemuxRequest} information of the current request.
     * @param demuxHandle a one-element array to return the granted demux handle.
     *
     * @return true if there is demux granted.
     */
    boolean requestDemux(in TunerDemuxRequest request, out int[] demuxHandle);

    /*
     * This API is used by the Tuner framework to request an available descrambler from the
     * TunerHAL.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is descrambler available, the API would send the handle back.
     *
     * <li>If no Descrambler is available but the current request info can show higher priority than
     * other uses of Descrambler, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no Descrambler can be granted, the API would return false.
     * <ul>
     *
     * @param request {@link TunerDescramblerRequest} information of the current request.
     * @param descramblerHandle a one-element array to return the granted descrambler handle.
     *
     * @return true if there is Descrambler granted.
     */
    boolean requestDescrambler(in TunerDescramblerRequest request, out int[] descramblerHandle);

    /*
     * This API is used by the Tuner framework to request an available Cas session. This session
     * needs to be under the CAS system with the id indicated in the {@code request}.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Cas session available, the API would send the id back.
     *
     * <li>If no Cas session is available but the current request info can show higher priority than
     * other uses of the sessions under the requested CAS system, the API will send
     * {@link ITunerResourceManagerCallback#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no Cas session can be granted, the API would return false.
     * <ul>
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this request.
     *
     * @param request {@link CasSessionRequest} information of the current request.
     * @param casSessionHandle a one-element array to return the granted cas session handle.
     *
     * @return true if there is CAS session granted.
     */
    boolean requestCasSession(in CasSessionRequest request, out int[] casSessionHandle);

    /*
     * This API is used by the Tuner framework to request an available CuCam.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is CiCam available, the API would send the handle back.
     *
     * <li>If no CiCma is available but the current request info can show higher priority than
     * other uses of the ciCam, the API will send
     * {@link ITunerResourceManagerCallback#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no CiCam can be granted, the API would return false.
     * <ul>
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this request.
     *
     * @param request {@link TunerCiCamRequest} information of the current request.
     * @param ciCamHandle a one-element array to return the granted ciCam handle.
     *
     * @return true if there is CiCam granted.
     */
    boolean requestCiCam(in TunerCiCamRequest request, out int[] ciCamHandle);

    /*
     * This API is used by the Tuner framework to request an available Lnb from the TunerHAL.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Lnb available, the API would send the id back.
     *
     * <li>If no Lnb is available but the current request has a higher priority than other uses of
     * lnbs, the API will send {@link ITunerResourceManagerCallback#onReclaimResources()} to the
     * {@link Tuner}. Tuner would handle the resource reclaim on the holder of lower priority and
     * notify the holder of its resource loss.
     *
     * <li>If no Lnb system can be granted, the API would return false.
     * <ul>
     *
     * <p><strong>Note:</strong> {@link #setLnbInfos(int[])} must be called before this request.
     *
     * @param request {@link TunerLnbRequest} information of the current request.
     * @param lnbHandle a one-element array to return the granted Lnb handle.
     *
     * @return true if there is Lnb granted.
     */
    boolean requestLnb(in TunerLnbRequest request, out int[] lnbHandle);

    /*
     * Notifies the TRM that the given frontend has been released.
     *
     * <p>Client must call this whenever it releases a Tuner frontend.
     *
     * <p><strong>Note:</strong> {@link #setFrontendInfoList(TunerFrontendInfo[])} must be called
     * before this release.
     *
     * @param frontendHandle the handle of the released frontend.
     * @param clientId the id of the client that is releasing the frontend.
     */
    void releaseFrontend(in int frontendHandle, int clientId);

    /*
     * Notifies the TRM that the Demux with the given handle was released.
     *
     * <p>Client must call this whenever it releases a demux.
     *
     * @param demuxHandle the handle of the released Tuner Demux.
     * @param clientId the id of the client that is releasing the demux.
     */
    void releaseDemux(in int demuxHandle, int clientId);

    /*
     * Notifies the TRM that the Descrambler with the given handle was released.
     *
     * <p>Client must call this whenever it releases a descrambler.
     *
     * @param descramblerHandle the handle of the released Tuner Descrambler.
     * @param clientId the id of the client that is releasing the descrambler.
     */
    void releaseDescrambler(in int descramblerHandle, int clientId);

    /*
     * Notifies the TRM that the given Cas session has been released.
     *
     * <p>Client must call this whenever it releases a Cas session.
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this release.
     *
     * @param casSessionHandle the handle of the released CAS session.
     * @param clientId the id of the client that is releasing the cas session.
     */
    void releaseCasSession(in int casSessionHandle, int clientId);

    /**
     * Notifies the TRM that the given CiCam has been released.
     *
     * <p>Client must call this whenever it releases a CiCam.
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this
     * release.
     *
     * @param ciCamHandle the handle of the releasing CiCam.
     * @param clientId the id of the client that is releasing the CiCam.
     */
    void releaseCiCam(in int ciCamHandle, int clientId);

    /*
     * Notifies the TRM that the Lnb with the given handle was released.
     *
     * <p>Client must call this whenever it releases an Lnb.
     *
     * <p><strong>Note:</strong> {@link #setLnbInfos(int[])} must be called before this release.
     *
     * @param lnbHandle the handle of the released Tuner Lnb.
     * @param clientId the id of the client that is releasing the lnb.
     */
    void releaseLnb(in int lnbHandle, int clientId);

    /*
     * Compare two clients' priority.
     *
     * @param challengerProfile the {@link ResourceClientProfile} of the challenger.
     * @param holderProfile the {@link ResourceClientProfile} of the holder of the resource.
     *
     * @return true if the challenger has higher priority than the holder.
     */
    boolean isHigherPriority(in ResourceClientProfile challengerProfile,
            in ResourceClientProfile holderProfile);

    /*
     * Stores Frontend resource map for the later restore.
     *
     * <p>This is API is only for testing purpose and should be used in pair with
     * restoreResourceMap(), which allows testing of {@link Tuner} APIs
     * that behave differently based on different sets of resource map.
     *
     * @param resourceType The resource type to store the map for.
     */
    void storeResourceMap(in int resourceType);

    /*
     * Clears the frontend resource map.
     *
     * <p>This is API is only for testing purpose and should be called right after
     * storeResourceMap(), so TRMService#removeFrontendResource() does not
     * get called in TRMService#setFrontendInfoListInternal() for custom frontend
     * resource map creation.
     *
     * @param resourceType The resource type to clear the map for.
     */
    void clearResourceMap(in int resourceType);

    /*
     * Restores Frontend resource map if it was stored before.
     *
     * <p>This is API is only for testing purpose and should be used in pair with
     * storeResourceMap(), which allows testing of {@link Tuner} APIs
     * that behave differently based on different sets of resource map.
     *
     * @param resourceType The resource type to restore the map for.
     */
    void restoreResourceMap(in int resourceType);

    /**
     * Grants the lock to the caller for public {@link Tuner} APIs
     *
     * <p>{@link Tuner} functions that call both [@link TunerResourceManager} APIs and
     * grabs lock that are also used in {@link IResourcesReclaimListener#onReclaimResources()}
     * must call this API before acquiring lock used in onReclaimResources().
     *
     * <p>This API will block until it releases the lock or fails
     *
     * @param clientId The ID of the caller.
     *
     * @return true if the lock is granted. If false is returned, calling this API again is not
     * guaranteed to work and may be unrecoverrable. (This should not happen.)
     */
    boolean acquireLock(in int clientId, in long clientThreadId);

    /**
     * Releases the lock to the caller for public {@link Tuner} APIs
     *
     * <p>This API must be called in pair with {@link #acquireLock(int, int)}
     *
     * <p>This API will block until it releases the lock or fails
     *
     * @param clientId The ID of the caller.
     *
     * @return true if the lock is granted. If false is returned, calling this API again is not
     * guaranteed to work and may be unrecoverrable. (This should not happen.)
     */
    boolean releaseLock(in int clientId);

    /**
     * Returns a priority for the given use case type and the client's foreground or background
     * status.
     *
     * @param useCase the use case type of the client. When the given use case type is invalid,
     *        the default use case type will be used. {@see TvInputService#PriorityHintUseCaseType}.
     * @param pid the pid of the client. When the pid is invalid, background status will be used as
     *        a client's status. Otherwise, client's app corresponding to the given session id will
     *        be used as a client. {@see TvInputService#onCreateSession(String, String)}.
     *
     * @return the client priority..
     */
    int getClientPriority(int useCase, int pid);

    /**
     * Returns a config priority for the given use case type and the foreground or background
     * status.
     *
     * @param useCase the use case type of the client. When the given use case type is invalid,
     *        the default use case type will be used. {@see TvInputService#PriorityHintUseCaseType}.
     * @param isForeground {@code true} if foreground, {@code false} otherwise.
     *
     * @return the config priority.
     */
    int getConfigPriority(int useCase, boolean isForeground);
}
