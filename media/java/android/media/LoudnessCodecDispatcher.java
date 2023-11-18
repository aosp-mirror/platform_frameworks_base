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

import android.annotation.CallbackExecutor;
import android.media.LoudnessCodecConfigurator.OnLoudnessCodecUpdateListener;
import android.os.PersistableBundle;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Class used to handle the loudness related communication with the audio service.
 * @hide
 */
public class LoudnessCodecDispatcher {
    private final class LoudnessCodecUpdatesDispatcherStub
            extends ILoudnessCodecUpdatesDispatcher.Stub
            implements CallbackUtil.DispatcherStub {
        @Override
        public void dispatchLoudnessCodecParameterChange(int piid, PersistableBundle params) {
            mLoudnessListenerMgr.callListeners(listener ->
                    mConfiguratorListener.computeIfPresent(listener, (l, c) -> {
                        // TODO: send the bundle for the user to update
                        return c;
                    }));
        }

        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    mAm.getService().registerLoudnessCodecUpdatesDispatcher(this);
                } else {
                    mAm.getService().unregisterLoudnessCodecUpdatesDispatcher(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    private final CallbackUtil.LazyListenerManager<OnLoudnessCodecUpdateListener>
            mLoudnessListenerMgr = new CallbackUtil.LazyListenerManager<>();

    @NonNull private final LoudnessCodecUpdatesDispatcherStub mLoudnessCodecStub;

    private final HashMap<OnLoudnessCodecUpdateListener, LoudnessCodecConfigurator>
            mConfiguratorListener = new HashMap<>();

    @NonNull private final AudioManager mAm;

    protected LoudnessCodecDispatcher(@NonNull AudioManager am) {
        mAm = Objects.requireNonNull(am);
        mLoudnessCodecStub = new LoudnessCodecUpdatesDispatcherStub();
    }

    /** @hide */
    public LoudnessCodecConfigurator createLoudnessCodecConfigurator() {
        return new LoudnessCodecConfigurator(this);
    }

    /** @hide */
    public void addLoudnessCodecListener(@NonNull LoudnessCodecConfigurator configurator,
                                         @NonNull @CallbackExecutor Executor executor,
                                         @NonNull OnLoudnessCodecUpdateListener listener) {
        Objects.requireNonNull(configurator);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        mConfiguratorListener.put(listener, configurator);
        mLoudnessListenerMgr.addListener(
                executor, listener, "addLoudnessCodecListener", () -> mLoudnessCodecStub);
    }

    /** @hide */
    public void removeLoudnessCodecListener(@NonNull LoudnessCodecConfigurator configurator) {
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
