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

import static android.media.MediaFormat.KEY_AAC_DRC_ALBUM_MODE;
import static android.media.MediaFormat.KEY_AAC_DRC_ATTENUATION_FACTOR;
import static android.media.MediaFormat.KEY_AAC_DRC_BOOST_FACTOR;
import static android.media.MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;
import static android.media.MediaFormat.KEY_AAC_DRC_HEAVY_COMPRESSION;
import static android.media.MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;

import android.annotation.CallbackExecutor;
import android.media.LoudnessCodecController.OnLoudnessCodecUpdateListener;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
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

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final HashMap<OnLoudnessCodecUpdateListener, LoudnessCodecController>
                mConfiguratorListener = new HashMap<>();

        public static synchronized LoudnessCodecUpdatesDispatcherStub getInstance() {
            if (sLoudnessCodecStub == null) {
                sLoudnessCodecStub = new LoudnessCodecUpdatesDispatcherStub();
            }
            return sLoudnessCodecStub;
        }

        private LoudnessCodecUpdatesDispatcherStub() {}

        @Override
        public void dispatchLoudnessCodecParameterChange(int sessionId, PersistableBundle params) {
            if (DEBUG) {
                Log.d(TAG, "dispatchLoudnessCodecParameterChange for sessionId " + sessionId
                        + " persistable bundle: " + params);
            }
            mLoudnessListenerMgr.callListeners(listener -> {
                synchronized (mLock) {
                    mConfiguratorListener.computeIfPresent(listener, (l, lcConfig) -> {
                        // send the appropriate bundle for the user to update
                        if (lcConfig.getSessionId() == sessionId) {
                            lcConfig.mediaCodecsConsume(mcEntry -> {
                                final LoudnessCodecInfo codecInfo = mcEntry.getKey();
                                final String infoKey = Integer.toString(codecInfo.hashCode());
                                Bundle bundle = null;
                                if (params.containsKey(infoKey)) {
                                    bundle = new Bundle(params.getPersistableBundle(infoKey));
                                }

                                final Set<MediaCodec> mediaCodecs = mcEntry.getValue();
                                for (MediaCodec mediaCodec : mediaCodecs) {
                                    final String mediaCodecKey = Integer.toString(
                                            mediaCodec.hashCode());
                                    if (bundle == null && !params.containsKey(mediaCodecKey)) {
                                        continue;
                                    }
                                    boolean canBreak = false;
                                    if (bundle == null) {
                                        // key was set by media codec hash to update single codec
                                        bundle = new Bundle(
                                                params.getPersistableBundle(mediaCodecKey));
                                        canBreak = true;
                                    }
                                    bundle =
                                            LoudnessCodecUpdatesDispatcherStub.filterLoudnessParams(
                                                    l.onLoudnessCodecUpdate(mediaCodec,
                                                            bundle));

                                    if (!bundle.isDefinitelyEmpty()) {
                                        try {
                                            mediaCodec.setParameters(bundle);
                                        } catch (IllegalStateException e) {
                                            Log.w(TAG, "Cannot set loudness bundle on media codec "
                                                    + mediaCodec);
                                        }
                                    }
                                    if (canBreak) {
                                        break;
                                    }
                                }
                            });
                        }
                        return lcConfig;
                    });
                }
            });
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
            if (bundle.containsKey(KEY_AAC_DRC_BOOST_FACTOR)) {
                filteredBundle.putInt(KEY_AAC_DRC_BOOST_FACTOR,
                        bundle.getInt(KEY_AAC_DRC_BOOST_FACTOR));
            }
            if (bundle.containsKey(KEY_AAC_DRC_ATTENUATION_FACTOR)) {
                filteredBundle.putInt(KEY_AAC_DRC_ATTENUATION_FACTOR,
                        bundle.getInt(KEY_AAC_DRC_ATTENUATION_FACTOR));
            }
            if (bundle.containsKey(KEY_AAC_DRC_ALBUM_MODE)) {
                filteredBundle.putInt(KEY_AAC_DRC_ALBUM_MODE,
                        bundle.getInt(KEY_AAC_DRC_ALBUM_MODE));
            }

            return filteredBundle;
        }

        void addLoudnessCodecListener(@NonNull CallbackUtil.DispatcherStub dispatcher,
                @NonNull LoudnessCodecController configurator,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnLoudnessCodecUpdateListener listener) {
            Objects.requireNonNull(configurator);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(listener);

            mLoudnessListenerMgr.addListener(
                    executor, listener, "addLoudnessCodecListener",
                    () -> dispatcher);
            synchronized (mLock) {
                mConfiguratorListener.put(listener, configurator);
            }
        }

        void removeLoudnessCodecListener(@NonNull LoudnessCodecController configurator) {
            Objects.requireNonNull(configurator);

            OnLoudnessCodecUpdateListener listenerToRemove = null;
            synchronized (mLock) {
                Iterator<Entry<OnLoudnessCodecUpdateListener, LoudnessCodecController>> iterator =
                        mConfiguratorListener.entrySet().iterator();
                while (iterator.hasNext()) {
                    Entry<OnLoudnessCodecUpdateListener, LoudnessCodecController> e =
                            iterator.next();
                    if (e.getValue() == configurator) {
                        final OnLoudnessCodecUpdateListener listener = e.getKey();
                        iterator.remove();
                        listenerToRemove = listener;
                        break;
                    }
                }
            }
            if (listenerToRemove != null) {
                mLoudnessListenerMgr.removeListener(listenerToRemove,
                        "removeLoudnessCodecListener");
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
    public void addLoudnessCodecListener(@NonNull LoudnessCodecController configurator,
                                         @NonNull @CallbackExecutor Executor executor,
                                         @NonNull OnLoudnessCodecUpdateListener listener) {
        LoudnessCodecUpdatesDispatcherStub.getInstance().addLoudnessCodecListener(this,
                configurator, executor, listener);
    }

    /** @hide */
    public void removeLoudnessCodecListener(@NonNull LoudnessCodecController configurator) {
        LoudnessCodecUpdatesDispatcherStub.getInstance().removeLoudnessCodecListener(configurator);
    }

    /** @hide */
    public void startLoudnessCodecUpdates(int sessionId) {
        try {
            mAudioService.startLoudnessCodecUpdates(sessionId);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void stopLoudnessCodecUpdates(int sessionId) {
        try {
            mAudioService.stopLoudnessCodecUpdates(sessionId);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void addLoudnessCodecInfo(int sessionId, int mediaCodecHash,
            @NonNull LoudnessCodecInfo mcInfo) {
        try {
            mAudioService.addLoudnessCodecInfo(sessionId, mediaCodecHash, mcInfo);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void removeLoudnessCodecInfo(int sessionId, @NonNull LoudnessCodecInfo mcInfo) {
        try {
            mAudioService.removeLoudnessCodecInfo(sessionId, mcInfo);
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public Bundle getLoudnessCodecParams(@NonNull LoudnessCodecInfo mcInfo) {
        Bundle loudnessParams = null;
        try {
            loudnessParams = new Bundle(mAudioService.getLoudnessParams(mcInfo));
        }  catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return loudnessParams;
    }
}
