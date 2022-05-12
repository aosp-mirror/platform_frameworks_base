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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SuppressLint;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.tv.TvInputService;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Interface of the Tuner Resource Manager(TRM). It manages resources used by TV Tuners.
 * <p>Resources include:
 * <ul>
 * <li>TunerFrontend {@link android.media.tv.tuner.frontend}.
 * <li>TunerLnb {@link android.media.tv.tuner.Lnb}.
 * <li>MediaCas {@link android.media.MediaCas}.
 * <ul>
 *
 * <p>Expected workflow is:
 * <ul>
 * <li>Tuner Java/MediaCas/TIF update resources of the current device with TRM.
 * <li>Client registers its profile through {@link #registerClientProfile(ResourceClientProfile,
 * Executor, ResourcesReclaimListener, int[])}.
 * <li>Client requests resources through request APIs.
 * <li>If the resource needs to be handed to a higher priority client from a lower priority
 * one, TRM calls IResourcesReclaimListener registered by the lower priority client to release
 * the resource.
 * <ul>
 *
 * <p>TRM also exposes its priority comparison algorithm as a helping method to other services.
 * {@see #isHigherPriority(ResourceClientProfile, ResourceClientProfile)}.
 *
 * @hide
 */
@RequiresFeature(PackageManager.FEATURE_LIVE_TV)
@SystemService(Context.TV_TUNER_RESOURCE_MGR_SERVICE)
public class TunerResourceManager {
    private static final String TAG = "TunerResourceManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int INVALID_RESOURCE_HANDLE = -1;
    public static final int INVALID_OWNER_ID = -1;
    /**
     * Tuner resource type to help generate resource handle
     */
    @IntDef({
        TUNER_RESOURCE_TYPE_FRONTEND,
        TUNER_RESOURCE_TYPE_DEMUX,
        TUNER_RESOURCE_TYPE_DESCRAMBLER,
        TUNER_RESOURCE_TYPE_LNB,
        TUNER_RESOURCE_TYPE_CAS_SESSION,
        TUNER_RESOURCE_TYPE_FRONTEND_CICAM,
        TUNER_RESOURCE_TYPE_MAX,
     })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TunerResourceType {}

    public static final int TUNER_RESOURCE_TYPE_FRONTEND = 0;
    public static final int TUNER_RESOURCE_TYPE_DEMUX = 1;
    public static final int TUNER_RESOURCE_TYPE_DESCRAMBLER = 2;
    public static final int TUNER_RESOURCE_TYPE_LNB = 3;
    public static final int TUNER_RESOURCE_TYPE_CAS_SESSION = 4;
    public static final int TUNER_RESOURCE_TYPE_FRONTEND_CICAM = 5;
    public static final int TUNER_RESOURCE_TYPE_MAX = 6;

    private final ITunerResourceManager mService;
    private final int mUserId;

    /**
     * @hide
     */
    public TunerResourceManager(ITunerResourceManager service, int userId) {
        mService = service;
        mUserId = userId;
    }

    /**
     * This API is used by the client to register their profile with the Tuner Resource manager.
     *
     * <p>The profile contains information that can show the base priority score of the client.
     *
     * @param profile {@link ResourceClientProfile} profile of the current client. Undefined use
     *                case would cause IllegalArgumentException.
     * @param executor the executor on which the listener would be invoked.
     * @param listener {@link ResourcesReclaimListener} callback to reclaim clients' resources when
     *                 needed.
     * @param clientId returned a clientId from the resource manager when the
     *                 the client registeres.
     * @throws IllegalArgumentException when {@code profile} contains undefined use case.
     */
    public void registerClientProfile(@NonNull ResourceClientProfile profile,
                        @NonNull @CallbackExecutor Executor executor,
                        @NonNull ResourcesReclaimListener listener,
                        @NonNull int[] clientId) {
        // TODO: throw new IllegalArgumentException("Unknown client use case")
        // when the use case is not defined.
        try {
            mService.registerClientProfile(profile,
                    new IResourcesReclaimListener.Stub() {
                    @Override
                public void onReclaimResources() {
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            executor.execute(() -> listener.onReclaimResources());
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                }, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This API is used by the client to unregister their profile with the
     * Tuner Resource manager.
     *
     * @param clientId the client id that needs to be unregistered.
     */
    public void unregisterClientProfile(int clientId) {
        try {
            mService.unregisterClientProfile(clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This API is used by client to update its registered {@link ResourceClientProfile}.
     *
     * <p>We recommend creating a new tuner instance for different use cases instead of using this
     * API since different use cases may need different resources.
     *
     * <p>If TIS updates use case, it needs to ensure underneath resources are exchangeable between
     * two different use cases.
     *
     * <p>Only the arbitrary priority and niceValue are allowed to be updated.
     *
     * @param clientId the id of the client that is updating its profile.
     * @param priority the priority that the client would like to update to.
     * @param niceValue the nice value that the client would like to update to.
     *
     * @return true if the update is successful.
     */
    public boolean updateClientPriority(int clientId, int priority, int niceValue) {
        boolean result = false;
        try {
            result = mService.updateClientPriority(clientId, priority, niceValue);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Checks if there is an unused frontend resource available.
     *
     * @param frontendType The frontend type for the query to be done for.
     */
    public boolean hasUnusedFrontend(int frontendType) {
        boolean result = false;
        try {
            result = mService.hasUnusedFrontend(frontendType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
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
    public boolean isLowestPriority(int clientId, int frontendType) {
        boolean result = false;
        try {
            result = mService.isLowestPriority(clientId, frontendType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Stores the frontend resource map if it was stored before.
     *
     * <p>This API is only for testing purpose and should be used in pair with
     * restoreResourceMap(), which allows testing of {@link Tuner} APIs
     * that behave differently based on different sets of resource map.
     *
     * @param resourceType The resource type to store the map for.
     */
    public void storeResourceMap(int resourceType) {
        try {
            mService.storeResourceMap(resourceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the frontend resource map.
     *
     * <p>This API is only for testing purpose and should be called right after
     * storeResourceMap(), so TRMService#removeFrontendResource() does not
     * get called in TRMService#setFrontendInfoListInternal() for custom frontend
     * resource map creation.
     *
     * @param resourceType The resource type to clear the map for.
     */
    public void clearResourceMap(int resourceType) {
        try {
            mService.clearResourceMap(resourceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Restores Frontend resource map for the later restore.
     *
     * <p>This API is only for testing purpose and should be used in pair with
     * storeResourceMap(), which allows testing of {@link Tuner} APIs
     * that behave differently based on different sets of resource map.
     *
     * @param resourceType The resource type to restore the map for.
     */
    public void restoreResourceMap(int resourceType) {
        try {
            mService.restoreResourceMap(resourceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the current TRM of the TunerHAL Frontend information.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestFrontend(TunerFrontendRequest, int[])} and
     * {@link #releaseFrontend(int, int)} call.
     *
     * @param infos an array of the available {@link TunerFrontendInfo} information.
     */
    public void setFrontendInfoList(@NonNull TunerFrontendInfo[] infos) {
        try {
            mService.setFrontendInfoList(infos);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the TRM of the current CAS information.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestCasSession(CasSessionRequest, int[])} and {@link #releaseCasSession(int, int)}
     * call.
     *
     * @param casSystemId id of the updating CAS system.
     * @param maxSessionNum the max session number of the CAS system that is updated.
     */
    public void updateCasInfo(int casSystemId, int maxSessionNum) {
        try {
            mService.updateCasInfo(casSystemId, maxSessionNum);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the TRM of the current Lnb information.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestLnb(TunerLnbRequest, int[])} and {@link #releaseLnb(int, int)} call.
     *
     * @param lnbIds ids of the updating lnbs.
     */
    public void setLnbInfoList(int[] lnbIds) {
        try {
            mService.setLnbInfoList(lnbIds);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
    public boolean acquireLock(int clientId) {
        try {
            return mService.acquireLock(clientId, Thread.currentThread().getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
    public boolean releaseLock(int clientId) {
        try {
            return mService.releaseLock(clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests a frontend resource.
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
     * @param frontendHandle a one-element array to return the granted frontendHandle. If
     *                       no frontend granted, this will return {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is frontend granted.
     */
    public boolean requestFrontend(@NonNull TunerFrontendRequest request,
                @Nullable int[] frontendHandle) {
        boolean result = false;
        try {
            result = mService.requestFrontend(request, frontendHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Sets the maximum usable frontends number of a given frontend type. It is used to enable or
     * disable frontends when cable connection status is changed by user.
     *
     * @param frontendType the frontendType which the maximum usable number will be set for.
     * @param maxNum the new maximum usable number.
     *
     * @return true if  successful and false otherwise.
     */
    public boolean setMaxNumberOfFrontends(int frontendType, int maxNum) {
        boolean result = false;
        try {
            result = mService.setMaxNumberOfFrontends(frontendType, maxNum);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Get the maximum usable frontends number of a given frontend type.
     *
     * @param frontendType the frontendType which the maximum usable number will be queried for.
     *
     * @return the maximum usable number of the queried frontend type. Returns -1 when the
     *         frontendType is invalid
     */
    public int getMaxNumberOfFrontends(int frontendType) {
        int result = -1;
        try {
            result = mService.getMaxNumberOfFrontends(frontendType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Requests from the client to share frontend with an existing client.
     *
     * <p><strong>Note:</strong> {@link #setFrontendInfoList(TunerFrontendInfo[])} must be called
     * before this request.
     *
     * @param selfClientId the id of the client that sends the request.
     * @param targetClientId the id of the client to share the frontend with.
     */
    public void shareFrontend(int selfClientId, int targetClientId) {
        try {
            mService.shareFrontend(selfClientId, targetClientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Transfers the ownership of shared resource.
     *
     * <p><strong>Note:</strong> Only the existing frontend sharee can be the new owner.
     *
     * @param resourceType the type of the resource to transfer the ownership for.
     * @param currentOwnerId the id of the current owner client.
     * @param newOwnerId the id of the new owner client.
     *
     * @return true if successful and false otherwise.
     */
    public boolean transferOwner(int resourceType, int currentOwnerId, int newOwnerId) {
        try {
            return mService.transferOwner(resourceType, currentOwnerId, newOwnerId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests a Tuner Demux resource.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Demux available, the API would send the handle back.
     *
     * <li>If no Demux is available but the current request has a higher priority than other uses of
     * demuxes, the API will send {@link IResourcesReclaimListener#onReclaimResources()} to the
     * {@link Tuner}. Tuner would handle the resource reclaim on the holder of lower priority and
     * notify the holder of its resource loss.
     *
     * <li>If no Demux system can be granted, the API would return false.
     * <ul>
     *
     * @param request {@link TunerDemuxRequest} information of the current request.
     * @param demuxHandle a one-element array to return the granted Demux handle.
     *                    If no Demux granted, this will return {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is Demux granted.
     */
    public boolean requestDemux(@NonNull TunerDemuxRequest request, @NonNull int[] demuxHandle) {
        boolean result = false;
        try {
            result = mService.requestDemux(request, demuxHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Requests a Tuner Descrambler resource.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Descrambler available, the API would send the handle back.
     *
     * <li>If no Descrambler is available but the current request has a higher priority than other
     * uses of descramblers, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <li>If no Descrambler system can be granted, the API would return false.
     * <ul>
     *
     * @param request {@link TunerDescramblerRequest} information of the current request.
     * @param descramblerHandle a one-element array to return the granted Descrambler handle.
     *                          If no Descrambler granted, this will return
     *                          {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is Descrambler granted.
     */
    public boolean requestDescrambler(@NonNull TunerDescramblerRequest request,
                @NonNull int[] descramblerHandle) {
        boolean result = false;
        try {
            result = mService.requestDescrambler(request, descramblerHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Requests a CAS session resource.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Cas session available, the API would send the id back.
     *
     * <li>If no Cas system is available but the current request info can show higher priority than
     * other uses of the cas sessions under the requested cas system, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this
     * request.
     *
     * @param request {@link CasSessionRequest} information of the current request.
     * @param casSessionHandle a one-element array to return the granted cas session handel.
     *                         If no CAS granted, this will return {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is CAS session granted.
     */
    public boolean requestCasSession(@NonNull CasSessionRequest request,
                @NonNull int[] casSessionHandle) {
        boolean result = false;
        try {
            result = mService.requestCasSession(request, casSessionHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Requests a CiCam resource.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is CiCam available, the API would send the id back.
     *
     * <li>If no CiCam is available but the current request info can show higher priority than
     * other uses of the CiCam, the API will send
     * {@link IResourcesReclaimListener#onReclaimResources()} to the {@link Tuner}. Tuner would
     * handle the resource reclaim on the holder of lower priority and notify the holder of its
     * resource loss.
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this
     * request.
     *
     * @param request {@link TunerCiCamRequest} information of the current request.
     * @param ciCamHandle a one-element array to return the granted ciCam handle.
     *                    If no ciCam granted, this will return {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is ciCam granted.
     */
    public boolean requestCiCam(TunerCiCamRequest request, int[] ciCamHandle) {
        boolean result = false;
        try {
            result = mService.requestCiCam(request, ciCamHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Requests a Tuner Lnb resource.
     *
     * <p>There are three possible scenarios:
     * <ul>
     * <li>If there is Lnb available, the API would send the id back.
     *
     * <li>If no Lnb is available but the current request has a higher priority than other uses of
     * lnbs, the API will send {@link IResourcesReclaimListener#onReclaimResources()} to the
     * {@link Tuner}. Tuner would handle the resource reclaim on the holder of lower priority and
     * notify the holder of its resource loss.
     *
     * <li>If no Lnb system can be granted, the API would return false.
     * <ul>
     *
     * <p><strong>Note:</strong> {@link #setLnbInfoList(int[])} must be called before this request.
     *
     * @param request {@link TunerLnbRequest} information of the current request.
     * @param lnbHandle a one-element array to return the granted Lnb handle.
     *                  If no Lnb granted, this will return {@link #INVALID_RESOURCE_HANDLE}.
     *
     * @return true if there is Lnb granted.
     */
    public boolean requestLnb(@NonNull TunerLnbRequest request, @NonNull int[] lnbHandle) {
        boolean result = false;
        try {
            result = mService.requestLnb(request, lnbHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
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
    public void releaseFrontend(int frontendHandle, int clientId) {
        try {
            mService.releaseFrontend(frontendHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TRM that the Demux with the given handle has been released.
     *
     * <p>Client must call this whenever it releases an Demux.
     *
     * @param demuxHandle the handle of the released Tuner Demux.
     * @param clientId the id of the client that is releasing the demux.
     */
    public void releaseDemux(int demuxHandle, int clientId) {
        try {
            mService.releaseDemux(demuxHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TRM that the Descrambler with the given handle has been released.
     *
     * <p>Client must call this whenever it releases an Descrambler.
     *
     * @param descramblerHandle the handle of the released Tuner Descrambler.
     * @param clientId the id of the client that is releasing the descrambler.
     */
    public void releaseDescrambler(int descramblerHandle, int clientId) {
        try {
            mService.releaseDescrambler(descramblerHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TRM that the given Cas session has been released.
     *
     * <p>Client must call this whenever it releases a Cas session.
     *
     * <p><strong>Note:</strong> {@link #updateCasInfo(int, int)} must be called before this
     * release.
     *
     * @param casSessionHandle the handle of the released CAS session.
     * @param clientId the id of the client that is releasing the cas session.
     */
    public void releaseCasSession(int casSessionHandle, int clientId) {
        try {
            mService.releaseCasSession(casSessionHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
    public void releaseCiCam(int ciCamHandle, int clientId) {
        try {
            mService.releaseCiCam(ciCamHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TRM that the Lnb with the given id has been released.
     *
     * <p>Client must call this whenever it releases an Lnb.
     *
     * <p><strong>Note:</strong> {@link #setLnbInfoList(int[])} must be called before this release.
     *
     * @param lnbHandle the handle of the released Tuner Lnb.
     * @param clientId the id of the client that is releasing the lnb.
     */
    public void releaseLnb(int lnbHandle, int clientId) {
        try {
            mService.releaseLnb(lnbHandle, clientId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Compare two clients' priority.
     *
     * @param challengerProfile the {@link ResourceClientProfile} of the challenger.
     * @param holderProfile the {@link ResourceClientProfile} of the holder of the resource.
     *
     * @return true if the challenger has higher priority than the holder.
     */
    public boolean isHigherPriority(ResourceClientProfile challengerProfile,
            ResourceClientProfile holderProfile) {
        try {
            return mService.isHigherPriority(challengerProfile, holderProfile);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
    public int getClientPriority(@TvInputService.PriorityHintUseCaseType int useCase, int pid) {
        try {
            return mService.getClientPriority(useCase, pid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a config priority for the given use case type and the foreground or background
     * status.
     *
     * @param useCase the use case type of the client. When the given use case type is invalid,
     *        the default use case type will be used. {@see TvInputService#PriorityHintUseCaseType}.
     * @param isForeground {@code true} if foreground, {@code false} otherwise.
     *
     * @return the config priority.
     *
     * @hide
     */
    @TestApi
    @SuppressLint("ShowingMemberInHiddenClass")
    public int getConfigPriority(@TvInputService.PriorityHintUseCaseType int useCase,
            boolean isForeground) {
        try {
            return mService.getConfigPriority(useCase, isForeground);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Interface used to receive events from TunerResourceManager.
     */
    public abstract static class ResourcesReclaimListener {
        /*
         * To reclaim all the resources of the callack owner.
         */
        public abstract void onReclaimResources();
    }
}
