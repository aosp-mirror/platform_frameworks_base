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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

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

    public static final int INVALID_FRONTEND_ID = -1;
    public static final int INVALID_CAS_SESSION_RESOURCE_ID = -1;
    public static final int INVALID_LNB_ID = -1;
    public static final int INVALID_TV_INPUT_DEVICE_ID = -1;
    public static final int INVALID_TV_INPUT_PORT_ID = -1;
    public static final int INVALID_RESOURCE_HANDLE = -1;

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
     * Updates the current TRM of the TunerHAL Frontend information.
     *
     * <p><strong>Note:</strong> This update must happen before the first
     * {@link #requestFrontend(TunerFrontendRequest, int[])} and {@link #releaseFrontend(int)} call.
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
     * {@link #requestCasSession(CasSessionRequest, int[])} and {@link #releaseCasSession(int)}
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
     * {@link #requestLnb(TunerLnbRequest, int[])} and {@link #releaseLnb(int)} call.
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
     * @param frontendId a one-element array to return the granted frontendId. If
     *                   no frontend granted, this will return {@link #INVALID_FRONTEND_ID}.
     *
     * @return true if there is frontend granted.
     */
    public boolean requestFrontend(@NonNull TunerFrontendRequest request,
                @Nullable int[] frontendId) {
        boolean result = false;
        try {
            result = mService.requestFrontend(request, frontendId);
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
     * @param sessionResourceId a one-element array to return the granted cas session id.
     *                          If no CAS granted, this will return
     *                          {@link #INVALID_CAS_SESSION_RESOURCE_ID}.
     *
     * @return true if there is CAS session granted.
     */
    public boolean requestCasSession(@NonNull CasSessionRequest request,
                @NonNull int[] sessionResourceId) {
        boolean result = false;
        try {
            result = mService.requestCasSession(request, sessionResourceId);
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
     * <p><strong>Note:</strong> {@link #setLnbInfos(int[])} must be called before this request.
     *
     * @param request {@link TunerLnbRequest} information of the current request.
     * @param lnbId a one-element array to return the granted Lnb id.
     *              If no Lnb granted, this will return {@link #INVALID_LNB_ID}.
     *
     * @return true if there is Lnb granted.
     */
    public boolean requestLnb(@NonNull TunerLnbRequest request, @NonNull int[] lnbId) {
        boolean result = false;
        try {
            result = mService.requestLnb(request, lnbId);
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
     * @param frontendId the id of the released frontend.
     */
    public void releaseFrontend(int frontendId) {
        try {
            mService.releaseFrontend(frontendId);
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
     */
    public void releaseDemux(int demuxHandle) {
        try {
            mService.releaseDemux(demuxHandle);
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
     * @param sessionResourceId the id of the released CAS session.
     */
    public void releaseCasSession(int sessionResourceId) {
        try {
            mService.releaseCasSession(sessionResourceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TRM that the Lnb with the given id has been released.
     *
     * <p>Client must call this whenever it releases an Lnb.
     *
     * <p><strong>Note:</strong> {@link #setLnbInfos(int[])} must be called before this release.
     *
     * @param lnbId the id of the released Tuner Lnb.
     */
    public void releaseLnb(int lnbId) {
        try {
            mService.releaseLnb(lnbId);
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
     * Interface used to receive events from TunerResourceManager.
     */
    public abstract static class ResourcesReclaimListener {
        /*
         * To reclaim all the resources of the callack owner.
         */
        public abstract void onReclaimResources();
    }
}
