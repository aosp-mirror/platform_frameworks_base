/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.media.MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;
import static android.media.MediaFormat.KEY_AAC_DRC_HEAVY_COMPRESSION;
import static android.media.MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;

import android.annotation.CallbackExecutor;
import android.media.LoudnessCodecConfigurator.OnLoudnessCodecUpdateListener;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Class used to handle the loudness related communication with the audio service.
 *
 * @hide
 */
public class LoudnessCodecDispatcher implements CallbackUtil.DispatcherStub {
    private static final String TAG = "LoudnessCodecDispatcher";

    private static final boolean DEBUG = false;

    private static final class LoudnessCodecUpdatesDispatcherStub
            extends ILoudnessCodecUpdatesDispatcher.Stub {
        private static LoudnessCodecUpdatesDispatcherStub sLoudnessCodecStub;

        private final CallbackUtil.LazyListenerManager<OnLoudnessCodecUpdateListener>
                mLoudnessListenerMgr = new CallbackUtil.LazyListenerManager<>();

        private final HashMap<OnLoudnessCodecUpdateListener, LoudnessCodecConfigurator>
                mConfiguratorListener = new HashMap<>();

        public static synchronized LoudnessCodecUpdatesDispatcherStub getInstance() {
            if (sLoudnessCodecStub == null) {
                sLoudnessCodecStub = new LoudnessCodecUpdatesDispatcherStub();
            }
            return sLoudnessCodecStub;
        }

        private LoudnessCodecUpdatesDispatcherStub() {}

        @Override
        public void dispatchLoudnessCodecParameterChange(int piid, PersistableBundle params) {
            mLoudnessListenerMgr.callListeners(listener ->
                    mConfiguratorListener.computeIfPresent(listener, (l, lcConfig) -> {
                        // send the appropriate bundle for the user to update
                        if (lcConfig.getAssignedTrackPiid() == piid) {
                            final List<MediaCodec> mediaCodecs =
                                    lcConfig.getRegisteredMediaCodecList();
                            for (MediaCodec mediaCodec : mediaCodecs) {
                                final String infoKey = Integer.toString(mediaCodec.hashCode());
                                if (params.containsKey(infoKey)) {
                                    Bundle bundle = new Bundle(
                                            params.getPersistableBundle(infoKey));
                                    if (DEBUG) {
                                        Log.d(TAG,
                                                "Received for piid " + piid + " bundle: " + bundle);
                                    }
                                    bundle =
                                            LoudnessCodecUpdatesDispatcherStub.filterLoudnessParams(
                                                    l.onLoudnessCodecUpdate(mediaCodec, bundle));
                                    if (DEBUG) {
                                        Log.d(TAG, "User changed for piid " + piid
                                                + " to filtered bundle: " + bundle);
                                    }

                                    if (!bundle.isDefinitelyEmpty()) {
                                        mediaCodec.setParameters(bundle);
                                    }
                                }
                            }
                        }

                        return lcConfig;
                    }));
        }

        private static Bundle filterLoudnessParams(Bundle bundle) {
            Bundle filteredBundle = new Bundle();

            if (bundle.containsKey(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL)) {
                filteredBundle.putInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL,
                        bundle.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
            }
            if (bundle.containsKey(KEY_AAC_DRC_HEAVY_COMPRESSION)) {
                filteredBundle.putInt(KEY_AAC_DRC_HEAVY_COMPRESSION,
                        bundle.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));
            }
            if (bundle.containsKey(KEY_AAC_DRC_EFFECT_TYPE)) {
                filteredBundle.putInt(KEY_AAC_DRC_EFFECT_TYPE,
                        bundle.getInt(KEY_AAC_DRC_EFFECT_TYPE));
            }

            return filteredBundle;
        }

        void addLoudnessCodecListener(@NonNull CallbackUtil.DispatcherStub dispatcher,
                @NonNull LoudnessCodecConfigurator configurator,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnLoudnessCodecUpdateListener listener) {
            Objects.requireNonNull(configurator);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(listener);

            mLoudnessListenerMgr.addListener(
                    executor, listener, "addLoudnessCodecListener",
                    () -> dispatcher);
            mConfiguratorListener.put(listener, configurator);
        }

        void removeLoudnessCodecListener(@NonNull LoudnessCodecConfigurator configurator) {
            Objects.requireNonNull(configurator);

            for (Entry<OnLoudnessCodecUpdateListener, LoudnessCodecConfigurator> e :
                    mConfiguratorListener.entrySet()) {
                if (e.getValue() == configurator) {
                    final OnLoudnessCodecUpdateListener listener = e.getKey();
                    mConfiguratorListener.remove(listener);
                    mLoudnessListenerMgr.removeListener(listener, "removeLoudnessCodecListener");
                    break;
                }
            }
        }
    }

    @NonNull private final IAudioService mAudioService;

    /** @hide */
    public LoudnessCodecDispatcher(@NonNull IAudioService audioService) {
        mAudioService = Objects.requireNonNull(audioService);
    }

    @Override
    public void register(boolean register) {
        try {
            if (register) {
                mAudioService.registerLoudnessCodecUpdatesDispatcher(
                        LoudnessCodecUpdatesDispatcherStub.getInstance());
            } else {
                mAudioService.unregisterLoudnessCodecUpdatesDispatcher(
                        LoudnessCodecUpdatesDispatcherStub.getInstance());
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void addLoudnessCodecListener(@NonNull LoudnessCodecConfigurator configurator,
                                         @NonNull @CallbackExecutor Executor executor,
                                         @NonNull OnLoudnessCodecUpdateListener listener) {
        LoudnessCodecUpdatesDispatcherStub.getInstance().addLoudnessCodecListener(this,
                configurator, executor, listener);
    }

    /** @hide */
    public void removeLoudnessCodecListener(@NonNull LoudnessCodecConfigurator configurator) {
        LoudnessCodecUpdatesDispatcherStub.getInstance().removeLoudnessCodecListener(configurator);
    }

    /** @hide */
    public void startLoudnessCodecUpdates(int piid, List<LoudnessCodecInfo> codecInfoList) {
        try {
            mAudioService.startLoudnessCodecUpdates(piid, codecInfoList);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void stopLoudnessCodecUpdates(int piid) {
        try {
            mAudioService.stopLoudnessCodecUpdates(piid);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void addLoudnessCodecInfo(int piid, @NonNull LoudnessCodecInfo mcInfo) {
        try {
            mAudioService.addLoudnessCodecInfo(piid, mcInfo);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void removeLoudnessCodecInfo(int piid, @NonNull LoudnessCodecInfo mcInfo) {
        try {
            mAudioService.removeLoudnessCodecInfo(piid, mcInfo);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public Bundle getLoudnessCodecParams(int piid, @NonNull LoudnessCodecInfo mcInfo) {
        Bundle loudnessParams = null;
        try {
            loudnessParams = new Bundle(mAudioService.getLoudnessParams(piid, mcInfo));
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return loudnessParams;
    }
}
