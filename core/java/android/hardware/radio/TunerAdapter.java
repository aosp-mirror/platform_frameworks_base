/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements the RadioTuner interface by forwarding calls to radio service.
 */
final class TunerAdapter extends RadioTuner {
    private static final String TAG = "BroadcastRadio.TunerAdapter";

    private final ITuner mTuner;
    private final TunerCallbackAdapter mCallback;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mIsClosed;

    @GuardedBy("mLock")
    @RadioManager.Band
    private int mBand;

    @GuardedBy("mLock")
    private ProgramList mLegacyListProxy;

    @GuardedBy("mLock")
    private Map<String, String> mLegacyListFilter;

    TunerAdapter(ITuner tuner, TunerCallbackAdapter callback,
            @RadioManager.Band int band) {
        mTuner = Objects.requireNonNull(tuner, "Tuner cannot be null");
        mCallback = Objects.requireNonNull(callback, "Callback cannot be null");
        mBand = band;
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) {
                Log.v(TAG, "Tuner is already closed");
                return;
            }
            mIsClosed = true;
            if (mLegacyListProxy != null) {
                mLegacyListProxy.close();
                mLegacyListProxy = null;
            }
        }
        mCallback.close();
        try {
            mTuner.close();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception trying to close tuner", e);
        }
    }

    @Override
    public int setConfiguration(RadioManager.BandConfig config) {
        if (config == null) {
            return RadioManager.STATUS_BAD_VALUE;
        }
        try {
            mTuner.setConfiguration(config);
            synchronized (mLock) {
                mBand = config.getType();
            }
            return RadioManager.STATUS_OK;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Can't set configuration", e);
            return RadioManager.STATUS_BAD_VALUE;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
    }

    @Override
    public int getConfiguration(RadioManager.BandConfig[] config) {
        if (config == null || config.length != 1) {
            throw new IllegalArgumentException("The argument must be an array of length 1");
        }
        try {
            config[0] = mTuner.getConfiguration();
            return RadioManager.STATUS_OK;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
    }

    @Override
    public int setMute(boolean mute) {
        try {
            mTuner.setMuted(mute);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't set muted", e);
            return RadioManager.STATUS_ERROR;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public boolean getMute() {
        try {
            return mTuner.isMuted();
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return true;
        }
    }

    @Override
    public int step(int direction, boolean skipSubChannel) {
        try {
            mTuner.step(/* directionDown= */ direction == RadioTuner.DIRECTION_DOWN,
                    skipSubChannel);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't step", e);
            return RadioManager.STATUS_INVALID_OPERATION;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public int scan(int direction, boolean skipSubChannel) {
        try {
            mTuner.seek(/* directionDown= */ direction == RadioTuner.DIRECTION_DOWN,
                    skipSubChannel);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't scan", e);
            return RadioManager.STATUS_INVALID_OPERATION;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public int seek(int direction, boolean skipSubChannel) {
        try {
            mTuner.seek(/* directionDown= */ direction == RadioTuner.DIRECTION_DOWN,
                    skipSubChannel);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't seek", e);
            return RadioManager.STATUS_INVALID_OPERATION;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public int tune(int channel, int subChannel) {
        try {
            int band;
            synchronized (mLock) {
                band = mBand;
            }
            mTuner.tune(ProgramSelector.createAmFmSelector(band, channel, subChannel));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't tune", e);
            return RadioManager.STATUS_INVALID_OPERATION;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Can't tune", e);
            return RadioManager.STATUS_BAD_VALUE;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public void tune(ProgramSelector selector) {
        try {
            mTuner.tune(selector);
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public int cancel() {
        try {
            mTuner.cancel();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't cancel", e);
            return RadioManager.STATUS_INVALID_OPERATION;
        } catch (RemoteException e) {
            Log.e(TAG, "Service died", e);
            return RadioManager.STATUS_DEAD_OBJECT;
        }
        return RadioManager.STATUS_OK;
    }

    @Override
    public void cancelAnnouncement() {
        try {
            mTuner.cancelAnnouncement();
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public int getProgramInformation(RadioManager.ProgramInfo[] info) {
        if (info == null || info.length != 1) {
            Log.e(TAG, "The argument must be an array of length 1");
            return RadioManager.STATUS_BAD_VALUE;
        }

        RadioManager.ProgramInfo current = mCallback.getCurrentProgramInformation();
        if (current == null) {
            Log.w(TAG, "Didn't get program info yet");
            return RadioManager.STATUS_INVALID_OPERATION;
        }
        info[0] = current;
        return RadioManager.STATUS_OK;
    }

    @Override
    @Nullable
    public Bitmap getMetadataImage(int id) {
        try {
            return mTuner.getImage(id);
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public boolean startBackgroundScan() {
        try {
            return mTuner.startBackgroundScan();
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public List<RadioManager.ProgramInfo>
            getProgramList(@Nullable Map<String, String> vendorFilter) {
        synchronized (mLock) {
            if (mLegacyListProxy == null || !Objects.equals(mLegacyListFilter, vendorFilter)) {
                Log.i(TAG, "Program list filter has changed, requesting new list");
                mLegacyListProxy = new ProgramList();
                mLegacyListFilter = vendorFilter;
                mCallback.clearLastCompleteList();
                mCallback.setProgramListObserver(mLegacyListProxy, () -> {
                    Log.i(TAG, "Empty closeListener in programListObserver");
                });
            }
        }
        try {
            mTuner.startProgramListUpdates(new ProgramList.Filter(vendorFilter));
        } catch (RemoteException ex) {
            throw new RuntimeException("Service died", ex);
        }

        List<RadioManager.ProgramInfo> list = mCallback.getLastCompleteList();
        if (list == null) {
            throw new IllegalStateException("Program list is not ready yet");
        }
        return list;
    }

    @Override
    @Nullable
    public ProgramList getDynamicProgramList(@Nullable ProgramList.Filter filter) {
        synchronized (mLock) {
            if (mLegacyListProxy != null) {
                mLegacyListProxy.close();
                mLegacyListProxy = null;
            }
            mLegacyListFilter = null;
        }
        ProgramList list = new ProgramList();
        mCallback.setProgramListObserver(list, () -> {
            try {
                mTuner.stopProgramListUpdates();
            } catch (IllegalStateException ex) {
                // it's fine to not stop updates if tuner is already closed
                Log.e(TAG, "Tuner may already be closed", ex);
            } catch (RemoteException ex) {
                Log.e(TAG, "Couldn't stop program list updates", ex);
            }
        });

        try {
            mTuner.startProgramListUpdates(filter);
        } catch (UnsupportedOperationException ex) {
            Log.i(TAG, "Program list is not supported with this hardware");
            return null;
        } catch (RemoteException ex) {
            mCallback.setProgramListObserver(null, () -> {
                Log.i(TAG, "Empty closeListener in programListObserver");
            });
            throw new RuntimeException("Service died", ex);
        }

        return list;
    }

    @Override
    public boolean isAnalogForced() {
        try {
            return isConfigFlagSet(RadioManager.CONFIG_FORCE_ANALOG);
        } catch (UnsupportedOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void setAnalogForced(boolean isForced) {
        try {
            setConfigFlag(RadioManager.CONFIG_FORCE_ANALOG, isForced);
        } catch (UnsupportedOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public boolean isConfigFlagSupported(@RadioManager.ConfigFlag int flag) {
        try {
            return mTuner.isConfigFlagSupported(flag);
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public boolean isConfigFlagSet(@RadioManager.ConfigFlag int flag) {
        try {
            return mTuner.isConfigFlagSet(convertForceAnalogConfigFlag(flag));
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public void setConfigFlag(@RadioManager.ConfigFlag int flag, boolean value) {
        try {
            mTuner.setConfigFlag(convertForceAnalogConfigFlag(flag), value);
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public Map<String, String> setParameters(Map<String, String> parameters) {
        try {
            return mTuner.setParameters(Objects.requireNonNull(parameters,
                    "Parameters cannot be null"));
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public Map<String, String> getParameters(List<String> keys) {
        try {
            return mTuner.getParameters(Objects.requireNonNull(keys, "Keys cannot be null"));
        } catch (RemoteException e) {
            throw new RuntimeException("Service died", e);
        }
    }

    @Override
    public boolean isAntennaConnected() {
        return mCallback.isAntennaConnected();
    }

    @Override
    public boolean hasControl() {
        try {
            // don't rely on mIsClosed, as tuner might get closed internally
            return !mTuner.isClosed();
        } catch (RemoteException e) {
            return false;
        }
    }

    private @RadioManager.ConfigFlag int convertForceAnalogConfigFlag(
            @RadioManager.ConfigFlag int flag) throws RemoteException {
        if (Flags.hdRadioImproved() && flag == RadioManager.CONFIG_FORCE_ANALOG
                && mTuner.isConfigFlagSupported(RadioManager.CONFIG_FORCE_ANALOG_FM)) {
            flag = RadioManager.CONFIG_FORCE_ANALOG_FM;
        }
        return flag;
    }
}
