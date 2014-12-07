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

package android.media.audiopolicy;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.IAudioService;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * @hide
 * AudioPolicy provides access to the management of audio routing and audio focus.
 */
@SystemApi
public class AudioPolicy {

    private static final String TAG = "AudioPolicy";
    private static final boolean DEBUG = false;
    private final Object mLock = new Object();

    /**
     * The status of an audio policy that is valid but cannot be used because it is not registered.
     */
    @SystemApi
    public static final int POLICY_STATUS_UNREGISTERED = 1;
    /**
     * The status of an audio policy that is valid, successfully registered and thus active.
     */
    @SystemApi
    public static final int POLICY_STATUS_REGISTERED = 2;

    private int mStatus;
    private String mRegistrationId;
    private AudioPolicyStatusListener mStatusListener;

    /**
     * The behavior of a policy with regards to audio focus where it relies on the application
     * to do the ducking, the is the legacy and default behavior.
     */
    @SystemApi
    public static final int FOCUS_POLICY_DUCKING_IN_APP = 0;
    public static final int FOCUS_POLICY_DUCKING_DEFAULT = FOCUS_POLICY_DUCKING_IN_APP;
    /**
     * The behavior of a policy with regards to audio focus where it handles ducking instead
     * of the application losing focus and being signaled it can duck (as communicated by
     * {@link android.media.AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}).
     * <br>Can only be used after having set a listener with
     * {@link AudioPolicy#setAudioPolicyFocusListener(AudioPolicyFocusListener)}.
     */
    @SystemApi
    public static final int FOCUS_POLICY_DUCKING_IN_POLICY = 1;

    private AudioPolicyFocusListener mFocusListener;

    private Context mContext;

    private AudioPolicyConfig mConfig;

    /** @hide */
    public AudioPolicyConfig getConfig() { return mConfig; }
    /** @hide */
    public boolean hasFocusListener() { return mFocusListener != null; }

    /**
     * The parameter is guaranteed non-null through the Builder
     */
    private AudioPolicy(AudioPolicyConfig config, Context context, Looper looper,
            AudioPolicyFocusListener fl, AudioPolicyStatusListener sl) {
        mConfig = config;
        mStatus = POLICY_STATUS_UNREGISTERED;
        mContext = context;
        if (looper == null) {
            looper = Looper.getMainLooper();
        }
        if (looper != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
            Log.e(TAG, "No event handler due to looper without a thread");
        }
        mFocusListener = fl;
        mStatusListener = sl;
    }

    /**
     * Builder class for {@link AudioPolicy} objects
     */
    @SystemApi
    public static class Builder {
        private ArrayList<AudioMix> mMixes;
        private Context mContext;
        private Looper mLooper;
        private AudioPolicyFocusListener mFocusListener;
        private AudioPolicyStatusListener mStatusListener;

        /**
         * Constructs a new Builder with no audio mixes.
         * @param context the context for the policy
         */
        @SystemApi
        public Builder(Context context) {
            mMixes = new ArrayList<AudioMix>();
            mContext = context;
        }

        /**
         * Add an {@link AudioMix} to be part of the audio policy being built.
         * @param mix a non-null {@link AudioMix} to be part of the audio policy.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        @SystemApi
        public Builder addMix(@NonNull AudioMix mix) throws IllegalArgumentException {
            if (mix == null) {
                throw new IllegalArgumentException("Illegal null AudioMix argument");
            }
            mMixes.add(mix);
            return this;
        }

        /**
         * Sets the {@link Looper} on which to run the event loop.
         * @param looper a non-null specific Looper.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        @SystemApi
        public Builder setLooper(@NonNull Looper looper) throws IllegalArgumentException {
            if (looper == null) {
                throw new IllegalArgumentException("Illegal null Looper argument");
            }
            mLooper = looper;
            return this;
        }

        /**
         * Sets the audio focus listener for the policy.
         * @param l a {@link AudioPolicy.AudioPolicyFocusListener}
         */
        @SystemApi
        public void setAudioPolicyFocusListener(AudioPolicyFocusListener l) {
            mFocusListener = l;
        }

        /**
         * Sets the audio policy status listener.
         * @param l a {@link AudioPolicy.AudioPolicyStatusListener}
         */
        @SystemApi
        public void setAudioPolicyStatusListener(AudioPolicyStatusListener l) {
            mStatusListener = l;
        }

        @SystemApi
        public AudioPolicy build() {
            return new AudioPolicy(new AudioPolicyConfig(mMixes), mContext, mLooper,
                    mFocusListener, mStatusListener);
        }
    }

    public void setRegistration(String regId) {
        synchronized (mLock) {
            mRegistrationId = regId;
            mConfig.setRegistration(regId);
            if (regId != null) {
                mStatus = POLICY_STATUS_REGISTERED;
            } else {
                mStatus = POLICY_STATUS_UNREGISTERED;
            }
        }
        sendMsg(MSG_POLICY_STATUS_CHANGE);
    }

    private boolean policyReadyToUse() {
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                Log.e(TAG, "Cannot use unregistered AudioPolicy");
                return false;
            }
            if (mContext == null) {
                Log.e(TAG, "Cannot use AudioPolicy without context");
                return false;
            }
            if (mRegistrationId == null) {
                Log.e(TAG, "Cannot use unregistered AudioPolicy");
                return false;
            }
        }
        if (!(PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
            Slog.w(TAG, "Cannot use AudioPolicy for pid " + Binder.getCallingPid() + " / uid "
                    + Binder.getCallingUid() + ", needs MODIFY_AUDIO_ROUTING");
            return false;
        }
        return true;
    }

    private void checkMixReadyToUse(AudioMix mix, boolean forTrack)
            throws IllegalArgumentException{
        if (mix == null) {
            String msg = forTrack ? "Invalid null AudioMix for AudioTrack creation"
                    : "Invalid null AudioMix for AudioRecord creation";
            throw new IllegalArgumentException(msg);
        }
        if (!mConfig.mMixes.contains(mix)) {
            throw new IllegalArgumentException("Invalid mix: not part of this policy");
        }
        if ((mix.getRouteFlags() & AudioMix.ROUTE_FLAG_LOOP_BACK) != AudioMix.ROUTE_FLAG_LOOP_BACK)
        {
            throw new IllegalArgumentException("Invalid AudioMix: not defined for loop back");
        }
        if (forTrack && (mix.getMixType() != AudioMix.MIX_TYPE_RECORDERS)) {
            throw new IllegalArgumentException(
                    "Invalid AudioMix: not defined for being a recording source");
        }
        if (!forTrack && (mix.getMixType() != AudioMix.MIX_TYPE_PLAYERS)) {
            throw new IllegalArgumentException(
                    "Invalid AudioMix: not defined for capturing playback");
        }
    }

    /**
     * Returns the current behavior for audio focus-related ducking.
     * @return {@link #FOCUS_POLICY_DUCKING_IN_APP} or {@link #FOCUS_POLICY_DUCKING_IN_POLICY}
     */
    @SystemApi
    public int getFocusDuckingBehavior() {
        return mConfig.mDuckingPolicy;
    }

    // Note on implementation: not part of the Builder as there can be only one registered policy
    // that handles ducking but there can be multiple policies
    /**
     * Sets the behavior for audio focus-related ducking.
     * There must be a focus listener if this policy is to handle ducking.
     * @param behavior {@link #FOCUS_POLICY_DUCKING_IN_APP} or
     *     {@link #FOCUS_POLICY_DUCKING_IN_POLICY}
     * @return {@link AudioManager#SUCCESS} or {@link AudioManager#ERROR} (for instance if there
     *     is already an audio policy that handles ducking).
     * @throws IllegalArgumentException
     * @throws IllegalStateException
     */
    @SystemApi
    public int setFocusDuckingBehavior(int behavior)
            throws IllegalArgumentException, IllegalStateException {
        if ((behavior != FOCUS_POLICY_DUCKING_IN_APP)
                && (behavior != FOCUS_POLICY_DUCKING_IN_POLICY)) {
            throw new IllegalArgumentException("Invalid ducking behavior " + behavior);
        }
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException(
                        "Cannot change ducking behavior for unregistered policy");
            }
            if ((behavior == FOCUS_POLICY_DUCKING_IN_POLICY)
                    && (mFocusListener == null)) {
                // there must be a focus listener if the policy handles ducking
                throw new IllegalStateException(
                        "Cannot handle ducking without an audio focus listener");
            }
            IAudioService service = getService();
            try {
                final int status = service.setFocusPropertiesForPolicy(behavior /*duckingBehavior*/,
                        this.cb());
                if (status == AudioManager.SUCCESS) {
                    mConfig.mDuckingPolicy = behavior;
                }
                return status;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setFocusPropertiesForPolicy for behavior", e);
                return AudioManager.ERROR;
            }
        }
    }

    /**
     * Create an {@link AudioRecord} instance that is associated with the given {@link AudioMix}.
     * Audio buffers recorded through the created instance will contain the mix of the audio
     * streams that fed the given mixer.
     * @param mix a non-null {@link AudioMix} instance whose routing flags was defined with
     *     {@link AudioMix#ROUTE_FLAG_LOOP_BACK}, previously added to this policy.
     * @return a new {@link AudioRecord} instance whose data format is the one defined in the
     *     {@link AudioMix}, or null if this policy was not successfully registered
     *     with {@link AudioManager#registerAudioPolicy(AudioPolicy)}.
     * @throws IllegalArgumentException
     */
    @SystemApi
    public AudioRecord createAudioRecordSink(AudioMix mix) throws IllegalArgumentException {
        if (!policyReadyToUse()) {
            Log.e(TAG, "Cannot create AudioRecord sink for AudioMix");
            return null;
        }
        checkMixReadyToUse(mix, false/*not for an AudioTrack*/);
        // create an AudioFormat from the mix format compatible with recording, as the mix
        // was defined for playback
        AudioFormat mixFormat = new AudioFormat.Builder(mix.getFormat())
                .setChannelMask(AudioFormat.inChannelMaskFromOutChannelMask(
                        mix.getFormat().getChannelMask()))
                .build();
        // create the AudioRecord, configured for loop back, using the same format as the mix
        AudioRecord ar = new AudioRecord(
                new AudioAttributes.Builder()
                        .setInternalCapturePreset(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        .addTag(addressForTag(mix))
                        .build(),
                mixFormat,
                AudioRecord.getMinBufferSize(mix.getFormat().getSampleRate(),
                        // using stereo for buffer size to avoid the current poor support for masks
                        AudioFormat.CHANNEL_IN_STEREO, mix.getFormat().getEncoding()),
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        return ar;
    }

    /**
     * Create an {@link AudioTrack} instance that is associated with the given {@link AudioMix}.
     * Audio buffers played through the created instance will be sent to the given mix
     * to be recorded through the recording APIs.
     * @param mix a non-null {@link AudioMix} instance whose routing flags was defined with
     *     {@link AudioMix#ROUTE_FLAG_LOOP_BACK}, previously added to this policy.
     * @return a new {@link AudioTrack} instance whose data format is the one defined in the
     *     {@link AudioMix}, or null if this policy was not successfully registered
     *     with {@link AudioManager#registerAudioPolicy(AudioPolicy)}.
     * @throws IllegalArgumentException
     */
    @SystemApi
    public AudioTrack createAudioTrackSource(AudioMix mix) throws IllegalArgumentException {
        if (!policyReadyToUse()) {
            Log.e(TAG, "Cannot create AudioTrack source for AudioMix");
            return null;
        }
        checkMixReadyToUse(mix, true/*for an AudioTrack*/);
        // create the AudioTrack, configured for loop back, using the same format as the mix
        AudioTrack at = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VIRTUAL_SOURCE)
                        .addTag(addressForTag(mix))
                        .build(),
                mix.getFormat(),
                AudioTrack.getMinBufferSize(mix.getFormat().getSampleRate(),
                        mix.getFormat().getChannelMask(), mix.getFormat().getEncoding()),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        return at;
    }

    @SystemApi
    public int getStatus() {
        return mStatus;
    }

    @SystemApi
    public static abstract class AudioPolicyStatusListener {
        public void onStatusChange() {}
        public void onMixStateUpdate(AudioMix mix) {}
    }

    @SystemApi
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}
    }

    private void onPolicyStatusChange() {
        AudioPolicyStatusListener l;
        synchronized (mLock) {
            if (mStatusListener == null) {
                return;
            }
            l = mStatusListener;
        }
        l.onStatusChange();
    }

    //==================================================
    // Callback interface

    /** @hide */
    public IAudioPolicyCallback cb() { return mPolicyCb; }

    private final IAudioPolicyCallback mPolicyCb = new IAudioPolicyCallback.Stub() {

        public void notifyAudioFocusGrant(AudioFocusInfo afi, int requestResult) {
            sendMsg(MSG_FOCUS_GRANT, afi, requestResult);
            if (DEBUG) {
                Log.v(TAG, "notifyAudioFocusGrant: pack=" + afi.getPackageName() + " client="
                        + afi.getClientId() + "reqRes=" + requestResult);
            }
        }

        public void notifyAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {
            sendMsg(MSG_FOCUS_LOSS, afi, wasNotified ? 1 : 0);
            if (DEBUG) {
                Log.v(TAG, "notifyAudioFocusLoss: pack=" + afi.getPackageName() + " client="
                        + afi.getClientId() + "wasNotified=" + wasNotified);
            }
        }
    };

    //==================================================
    // Event handling
    private final EventHandler mEventHandler;
    private final static int MSG_POLICY_STATUS_CHANGE = 0;
    private final static int MSG_FOCUS_GRANT = 1;
    private final static int MSG_FOCUS_LOSS = 2;

    private class EventHandler extends Handler {
        public EventHandler(AudioPolicy ap, Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_POLICY_STATUS_CHANGE:
                    onPolicyStatusChange();
                    break;
                case MSG_FOCUS_GRANT:
                    if (mFocusListener != null) {
                        mFocusListener.onAudioFocusGrant(
                                (AudioFocusInfo) msg.obj, msg.arg1);
                    }
                    break;
                case MSG_FOCUS_LOSS:
                    if (mFocusListener != null) {
                        mFocusListener.onAudioFocusLoss(
                                (AudioFocusInfo) msg.obj, msg.arg1 != 0);
                    }
                    break;
                default:
                    Log.e(TAG, "Unknown event " + msg.what);
            }
        }
    }

    //==========================================================
    // Utils
    private static String addressForTag(AudioMix mix) {
        return "addr=" + mix.getRegistration();
    }

    private void sendMsg(int msg) {
        if (mEventHandler != null) {
            mEventHandler.sendEmptyMessage(msg);
        }
    }

    private void sendMsg(int msg, Object obj, int i) {
        if (mEventHandler != null) {
            mEventHandler.sendMessage(
                    mEventHandler.obtainMessage(msg, i /*arg1*/, 0 /*arg2, ignored*/, obj));
        }
    }

    private static IAudioService sService;

    private static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    public String toLogFriendlyString() {
        String textDump = new String("android.media.audiopolicy.AudioPolicy:\n");
        textDump += "config=" + mConfig.toLogFriendlyString();
        return (textDump);
    }

    /** @hide */
    @IntDef({
        POLICY_STATUS_REGISTERED,
        POLICY_STATUS_UNREGISTERED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PolicyStatus {}
}
