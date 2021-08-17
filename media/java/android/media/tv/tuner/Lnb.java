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

package android.media.tv.tuner;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.Tuner.Result;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * LNB (low-noise block downconverter) for satellite tuner.
 *
 * A Tuner LNB (low-noise block downconverter) is used by satellite frontend to receive the
 * microwave signal from the satellite, amplify it, and downconvert the frequency to a lower
 * frequency.
 *
 * @hide
 */
@SystemApi
public class Lnb implements AutoCloseable {
    /** @hide */
    @IntDef(prefix = "VOLTAGE_",
            value = {VOLTAGE_NONE, VOLTAGE_5V, VOLTAGE_11V, VOLTAGE_12V, VOLTAGE_13V, VOLTAGE_14V,
            VOLTAGE_15V, VOLTAGE_18V, VOLTAGE_19V})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Voltage {}

    /**
     * LNB power voltage not set.
     */
    public static final int VOLTAGE_NONE = Constants.LnbVoltage.NONE;
    /**
     * LNB power voltage 5V.
     */
    public static final int VOLTAGE_5V = Constants.LnbVoltage.VOLTAGE_5V;
    /**
     * LNB power voltage 11V.
     */
    public static final int VOLTAGE_11V = Constants.LnbVoltage.VOLTAGE_11V;
    /**
     * LNB power voltage 12V.
     */
    public static final int VOLTAGE_12V = Constants.LnbVoltage.VOLTAGE_12V;
    /**
     * LNB power voltage 13V.
     */
    public static final int VOLTAGE_13V = Constants.LnbVoltage.VOLTAGE_13V;
    /**
     * LNB power voltage 14V.
     */
    public static final int VOLTAGE_14V = Constants.LnbVoltage.VOLTAGE_14V;
    /**
     * LNB power voltage 15V.
     */
    public static final int VOLTAGE_15V = Constants.LnbVoltage.VOLTAGE_15V;
    /**
     * LNB power voltage 18V.
     */
    public static final int VOLTAGE_18V = Constants.LnbVoltage.VOLTAGE_18V;
    /**
     * LNB power voltage 19V.
     */
    public static final int VOLTAGE_19V = Constants.LnbVoltage.VOLTAGE_19V;

    /** @hide */
    @IntDef(prefix = "TONE_",
            value = {TONE_NONE, TONE_CONTINUOUS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Tone {}

    /**
     * LNB tone mode not set.
     */
    public static final int TONE_NONE = Constants.LnbTone.NONE;
    /**
     * LNB continuous tone mode.
     */
    public static final int TONE_CONTINUOUS = Constants.LnbTone.CONTINUOUS;

    /** @hide */
    @IntDef(prefix = "POSITION_",
            value = {POSITION_UNDEFINED, POSITION_A, POSITION_B})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Position {}

    /**
     * LNB position is not defined.
     */
    public static final int POSITION_UNDEFINED = Constants.LnbPosition.UNDEFINED;
    /**
     * Position A of two-band LNBs
     */
    public static final int POSITION_A = Constants.LnbPosition.POSITION_A;
    /**
     * Position B of two-band LNBs
     */
    public static final int POSITION_B = Constants.LnbPosition.POSITION_B;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "EVENT_TYPE_",
            value = {EVENT_TYPE_DISEQC_RX_OVERFLOW, EVENT_TYPE_DISEQC_RX_TIMEOUT,
            EVENT_TYPE_DISEQC_RX_PARITY_ERROR, EVENT_TYPE_LNB_OVERLOAD})
    public @interface EventType {}

    /**
     * Outgoing Diseqc message overflow.
     */
    public static final int EVENT_TYPE_DISEQC_RX_OVERFLOW =
            Constants.LnbEventType.DISEQC_RX_OVERFLOW;
    /**
     * Outgoing Diseqc message isn't delivered on time.
     */
    public static final int EVENT_TYPE_DISEQC_RX_TIMEOUT =
            Constants.LnbEventType.DISEQC_RX_TIMEOUT;
    /**
     * Incoming Diseqc message has parity error.
     */
    public static final int EVENT_TYPE_DISEQC_RX_PARITY_ERROR =
            Constants.LnbEventType.DISEQC_RX_PARITY_ERROR;
    /**
     * LNB is overload.
     */
    public static final int EVENT_TYPE_LNB_OVERLOAD = Constants.LnbEventType.LNB_OVERLOAD;

    private static final String TAG = "Lnb";

    LnbCallback mCallback;
    Executor mExecutor;
    Tuner mTuner;
    private final Object mCallbackLock = new Object();


    private native int nativeSetVoltage(int voltage);
    private native int nativeSetTone(int tone);
    private native int nativeSetSatellitePosition(int position);
    private native int nativeSendDiseqcMessage(byte[] message);
    private native int nativeClose();

    private long mNativeContext;

    private Boolean mIsClosed = false;
    private final Object mLock = new Object();

    private Lnb() {}

    void setCallback(Executor executor, @Nullable LnbCallback callback, Tuner tuner) {
        synchronized (mCallbackLock) {
            mCallback = callback;
            mExecutor = executor;
            mTuner = tuner;
        }
    }

    private void onEvent(int eventType) {
        synchronized (mCallbackLock) {
            if (mExecutor != null && mCallback != null) {
                mExecutor.execute(() -> mCallback.onEvent(eventType));
            }
        }
    }

    private void onDiseqcMessage(byte[] diseqcMessage) {
        synchronized (mCallbackLock) {
            if (mExecutor != null && mCallback != null) {
                mExecutor.execute(() -> mCallback.onDiseqcMessage(diseqcMessage));
            }
        }
    }

    /* package */ boolean isClosed() {
        synchronized (mLock) {
            return mIsClosed;
        }
    }

    /**
     * Sets the LNB's power voltage.
     *
     * @param voltage the power voltage constant the Lnb to use.
     * @return result status of the operation.
     */
    @Result
    public int setVoltage(@Voltage int voltage) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeSetVoltage(voltage);
        }
    }

    /**
     * Sets the LNB's tone mode.
     *
     * @param tone the tone mode the Lnb to use.
     * @return result status of the operation.
     */
    @Result
    public int setTone(@Tone int tone) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeSetTone(tone);
        }
    }

    /**
     * Selects the LNB's position.
     *
     * @param position the position the Lnb to use.
     * @return result status of the operation.
     */
    @Result
    public int setSatellitePosition(@Position int position) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeSetSatellitePosition(position);
        }
    }

    /**
     * Sends DiSEqC (Digital Satellite Equipment Control) message.
     *
     * The response message from the device comes back through callback onDiseqcMessage.
     *
     * @param message a byte array of data for DiSEqC message which is specified by EUTELSAT Bus
     *         Functional Specification Version 4.2.
     *
     * @return result status of the operation.
     */
    @Result
    public int sendDiseqcMessage(@NonNull byte[] message) {
        synchronized (mLock) {
            TunerUtils.checkResourceState(TAG, mIsClosed);
            return nativeSendDiseqcMessage(message);
        }
    }

    /**
     * Releases the LNB instance.
     */
    public void close() {
        synchronized (mLock) {
            if (mIsClosed) {
                return;
            }
            int res = nativeClose();
            if (res != Tuner.RESULT_SUCCESS) {
                TunerUtils.throwExceptionForResult(res, "Failed to close LNB");
            } else {
                mIsClosed = true;
                mTuner.releaseLnb();
            }
        }
    }
}
