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

import static android.media.audiopolicy.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.FadeManagerConfiguration;
import android.media.IAudioService;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    public static final int POLICY_STATUS_UNREGISTERED = 1;
    /**
     * The status of an audio policy that is valid, successfully registered and thus active.
     */
    public static final int POLICY_STATUS_REGISTERED = 2;

    @GuardedBy("mLock")
    private int mStatus;
    @GuardedBy("mLock")
    private String mRegistrationId;
    private final AudioPolicyStatusListener mStatusListener;
    private final boolean mIsFocusPolicy;
    private final boolean mIsTestFocusPolicy;

    /**
     * The list of AudioTrack instances created to inject audio into the associated mixes
     * Lazy initialization in {@link #createAudioTrackSource(AudioMix)}
     */
    @GuardedBy("mLock")
    @Nullable private ArrayList<WeakReference<AudioTrack>> mInjectors;
    /**
     * The list AudioRecord instances created to capture audio from the associated mixes
     * Lazy initialization in {@link #createAudioRecordSink(AudioMix)}
     */
    @GuardedBy("mLock")
    @Nullable private ArrayList<WeakReference<AudioRecord>> mCaptors;

    /**
     * The behavior of a policy with regards to audio focus where it relies on the application
     * to do the ducking, the is the legacy and default behavior.
     */
    public static final int FOCUS_POLICY_DUCKING_IN_APP = 0;
    public static final int FOCUS_POLICY_DUCKING_DEFAULT = FOCUS_POLICY_DUCKING_IN_APP;
    /**
     * The behavior of a policy with regards to audio focus where it handles ducking instead
     * of the application losing focus and being signaled it can duck (as communicated by
     * {@link android.media.AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}).
     * <br>Can only be used after having set a listener with
     * {@link AudioPolicy#setAudioPolicyFocusListener(AudioPolicyFocusListener)}.
     */
    public static final int FOCUS_POLICY_DUCKING_IN_POLICY = 1;

    private AudioPolicyFocusListener mFocusListener;

    private final AudioPolicyVolumeCallback mVolCb;

    private Context mContext;

    @GuardedBy("mLock")
    private AudioPolicyConfig mConfig;

    private final MediaProjection mProjection;

    /** @hide */
    public AudioPolicyConfig getConfig() { return mConfig; }
    /** @hide */
    public boolean hasFocusListener() { return mFocusListener != null; }
    /** @hide */
    public boolean isFocusPolicy() { return mIsFocusPolicy; }
    /** @hide */
    public boolean isTestFocusPolicy() {
        return mIsTestFocusPolicy;
    }
    /** @hide */
    public boolean isVolumeController() { return mVolCb != null; }
    /** @hide */
    public @Nullable MediaProjection getMediaProjection() {
        return mProjection;
    }

    /** @hide */
    public AttributionSource getAttributionSource() {
        return getAttributionSource(mContext);
    }

    private static AttributionSource getAttributionSource(Context context) {
        return context == null
                ? AttributionSource.myAttributionSource() : context.getAttributionSource();
    }

    /**
     * The parameters are guaranteed non-null through the Builder
     */
    private AudioPolicy(AudioPolicyConfig config, Context context, Looper looper,
            AudioPolicyFocusListener fl, AudioPolicyStatusListener sl,
            boolean isFocusPolicy, boolean isTestFocusPolicy,
            AudioPolicyVolumeCallback vc, @Nullable MediaProjection projection) {
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
        mIsFocusPolicy = isFocusPolicy;
        mIsTestFocusPolicy = isTestFocusPolicy;
        mVolCb = vc;
        mProjection = projection;
    }

    /**
     * Builder class for {@link AudioPolicy} objects.
     * By default the policy to be created doesn't govern audio focus decisions.
     */
    public static class Builder {
        private ArrayList<AudioMix> mMixes;
        private Context mContext;
        private Looper mLooper;
        private AudioPolicyFocusListener mFocusListener;
        private AudioPolicyStatusListener mStatusListener;
        private boolean mIsFocusPolicy = false;
        private boolean mIsTestFocusPolicy = false;
        private AudioPolicyVolumeCallback mVolCb;
        private MediaProjection mProjection;

        /**
         * Constructs a new Builder with no audio mixes.
         * @param context the context for the policy
         */
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
        @NonNull
        public Builder addMix(@NonNull AudioMix mix) throws IllegalArgumentException {
            if (mix == null) {
                throw new IllegalArgumentException("Illegal null AudioMix argument");
            }
            if (android.permission.flags.Flags.deviceAwarePermissionApisEnabled()) {
                mix.setVirtualDeviceId(getAttributionSource(mContext).getDeviceId());
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
        @NonNull
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
        public void setAudioPolicyFocusListener(AudioPolicyFocusListener l) {
            mFocusListener = l;
        }

        /**
         * Declares whether this policy will grant and deny audio focus through
         * the {@link AudioPolicy.AudioPolicyFocusListener}.
         * If set to {@code true}, it is mandatory to set an
         * {@link AudioPolicy.AudioPolicyFocusListener} in order to successfully build
         * an {@code AudioPolicy} instance.
         * @param isFocusPolicy true if the policy will govern audio focus decisions.
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setIsAudioFocusPolicy(boolean isFocusPolicy) {
            mIsFocusPolicy = isFocusPolicy;
            return this;
        }

        /**
         * @hide
         * Test method to declare whether this audio focus policy is for test purposes only.
         * Having a test policy registered will disable the current focus policy and replace it
         * with this test policy. When unregistered, the previous focus policy will be restored.
         * <p>A value of <code>true</code> will be ignored if the AudioPolicy is not also
         * focus policy.
         * @param isTestFocusPolicy true if the focus policy to register is for testing purposes.
         * @return the same Builder instance
         */
        @TestApi
        @NonNull
        public Builder setIsTestFocusPolicy(boolean isTestFocusPolicy) {
            mIsTestFocusPolicy = isTestFocusPolicy;
            return this;
        }

        /**
         * Sets the audio policy status listener.
         * @param l a {@link AudioPolicy.AudioPolicyStatusListener}
         */
        public void setAudioPolicyStatusListener(AudioPolicyStatusListener l) {
            mStatusListener = l;
        }

        /**
         * Sets the callback to receive all volume key-related events.
         * The callback will only be called if the device is configured to handle volume events
         * in the PhoneWindowManager (see config_handleVolumeKeysInWindowManager)
         * @param vc
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setAudioPolicyVolumeCallback(@NonNull AudioPolicyVolumeCallback vc) {
            if (vc == null) {
                throw new IllegalArgumentException("Invalid null volume callback");
            }
            mVolCb = vc;
            return this;
        }

        /**
         * Set a media projection obtained through createMediaProjection().
         *
         * A MediaProjection that can project audio allows to register an audio
         * policy LOOPBACK|RENDER without the MODIFY_AUDIO_ROUTING permission.
         *
         * @hide
         */
        @NonNull
        public Builder setMediaProjection(@NonNull MediaProjection projection) {
            if (projection == null) {
                throw new IllegalArgumentException("Invalid null volume callback");
            }
            mProjection = projection;
            return this;

        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder} and returns a
         * new {@link AudioPolicy} object.
         * @return a new {@code AudioPolicy} object.
         * @throws IllegalStateException if there is no
         *     {@link AudioPolicy.AudioPolicyStatusListener} but the policy was configured
         *     as an audio focus policy with {@link #setIsAudioFocusPolicy(boolean)}.
         */
        @NonNull
        public AudioPolicy build() {
            if (mStatusListener != null) {
                // the AudioPolicy status listener includes updates on each mix activity state
                for (AudioMix mix : mMixes) {
                    mix.mCallbackFlags |= AudioMix.CALLBACK_FLAG_NOTIFY_ACTIVITY;
                }
            }
            if (mIsFocusPolicy && mFocusListener == null) {
                throw new IllegalStateException("Cannot be a focus policy without "
                        + "an AudioPolicyFocusListener");
            }
            return new AudioPolicy(new AudioPolicyConfig(mMixes), mContext, mLooper,
                    mFocusListener, mStatusListener, mIsFocusPolicy, mIsTestFocusPolicy,
                    mVolCb, mProjection);
        }
    }

    /**
     * Update the current configuration of the set of audio mixes by adding new ones, while
     * keeping the policy registered. If any of the provided audio mixes is invalid then none of
     * the passed mixes will be registered.
     *
     * This method can only be called on a registered policy.
     * @param mixes the list of {@link AudioMix} to add
     * @return {@link AudioManager#SUCCESS} if the change was successful, {@link AudioManager#ERROR}
     *    otherwise.
     */
    public int attachMixes(@NonNull List<AudioMix> mixes) {
        if (mixes == null) {
            throw new IllegalArgumentException("Illegal null list of AudioMix");
        }
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot alter unregistered AudioPolicy");
            }
            final ArrayList<AudioMix> zeMixes = new ArrayList<AudioMix>(mixes.size());
            for (AudioMix mix : mixes) {
                if (mix == null) {
                    throw new IllegalArgumentException("Illegal null AudioMix in attachMixes");
                } else {
                    if (android.permission.flags.Flags.deviceAwarePermissionApisEnabled()) {
                        mix.setVirtualDeviceId(getAttributionSource(mContext).getDeviceId());
                    }
                    zeMixes.add(mix);
                }
            }
            final AudioPolicyConfig cfg = new AudioPolicyConfig(zeMixes);
            IAudioService service = getService();
            try {
                final int status = service.addMixForPolicy(cfg, this.cb());
                if (status == AudioManager.SUCCESS) {
                    mConfig.add(zeMixes);
                }
                return status;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in attachMixes", e);
                return AudioManager.ERROR;
            }
        }
    }

    /**
     * Update the current configuration of the set of audio mixes for this audio policy by
     * removing some, while keeping the policy registered. Will unregister all provided audio
     * mixes, if possible.
     *
     * This method can only be called on a registered policy and only affects this current policy.
     * @param mixes the list of {@link AudioMix} to remove
     * @return {@link AudioManager#SUCCESS} if the change was successful, {@link AudioManager#ERROR}
     *    otherwise. If only some of the provided audio mixes were detached but any one mix
     *    failed to be detached, this method returns {@link AudioManager#ERROR}.
     */
    public int detachMixes(@NonNull List<AudioMix> mixes) {
        if (mixes == null) {
            throw new IllegalArgumentException("Illegal null list of AudioMix");
        }
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot alter unregistered AudioPolicy");
            }
            final ArrayList<AudioMix> zeMixes = new ArrayList<AudioMix>(mixes.size());
            for (AudioMix mix : mixes) {
                if (mix == null) {
                    throw new IllegalArgumentException("Illegal null AudioMix in detachMixes");
                } else {
                    if (android.permission.flags.Flags.deviceAwarePermissionApisEnabled()) {
                        mix.setVirtualDeviceId(getAttributionSource(mContext).getDeviceId());
                    }
                    zeMixes.add(mix);
                }
            }
            final AudioPolicyConfig cfg = new AudioPolicyConfig(zeMixes);
            IAudioService service = getService();
            try {
                final int status = service.removeMixForPolicy(cfg, this.cb());
                if (status == AudioManager.SUCCESS) {
                    mConfig.remove(zeMixes);
                }
                return status;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in detachMixes", e);
                return AudioManager.ERROR;
            }
        }
    }

    /**
     * Update {@link AudioMixingRule}-s of already registered {@link AudioMix}-es.
     *
     * @param mixingRuleUpdates - {@link List} of {@link Pair}-s, each pair containing
     *  {@link AudioMix} to update and its new corresponding {@link AudioMixingRule}.
     *
     * @return {@link AudioManager#SUCCESS} if the update was successful,
     *  {@link AudioManager#ERROR} otherwise.
     */
    @FlaggedApi(Flags.FLAG_AUDIO_POLICY_UPDATE_MIXING_RULES_API)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public int updateMixingRules(
            @NonNull List<Pair<AudioMix, AudioMixingRule>> mixingRuleUpdates) {
        Objects.requireNonNull(mixingRuleUpdates);

        IAudioService service = getService();
        try {
            synchronized (mLock) {
                final int status = service.updateMixingRulesForPolicy(
                        mixingRuleUpdates.stream().map(p -> p.first).toArray(AudioMix[]::new),
                        mixingRuleUpdates.stream().map(p -> p.second).toArray(
                                AudioMixingRule[]::new),
                        cb());
                if (status == AudioManager.SUCCESS) {
                    mConfig.updateMixingRules(mixingRuleUpdates);
                }
                return status;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Received remote exeception in updateMixingRules call: ", e);
            return AudioManager.ERROR;
        }
    }

    /**
     * @hide
     * Configures the audio framework so that all audio streams originating from the given UID
     * can only come from a set of audio devices.
     * For this routing to be operational, a number of {@link AudioMix} instances must have been
     * previously registered on this policy, and routed to a super-set of the given audio devices
     * with {@link AudioMix.Builder#setDevice(android.media.AudioDeviceInfo)}. Note that having
     * multiple devices in the list doesn't imply the signals will be duplicated on the different
     * audio devices, final routing will depend on the {@link AudioAttributes} of the sounds being
     * played.
     * @param uid UID of the application to affect.
     * @param devices list of devices to which the audio stream of the application may be routed.
     * @return true if the change was successful, false otherwise.
     */
    @SystemApi
    public boolean setUidDeviceAffinity(int uid, @NonNull List<AudioDeviceInfo> devices) {
        if (devices == null) {
            throw new IllegalArgumentException("Illegal null list of audio devices");
        }
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot use unregistered AudioPolicy");
            }
            final int[] deviceTypes = new int[devices.size()];
            final String[] deviceAdresses = new String[devices.size()];
            int i = 0;
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    throw new IllegalArgumentException(
                            "Illegal null AudioDeviceInfo in setUidDeviceAffinity");
                }
                deviceTypes[i] =
                        AudioDeviceInfo.convertDeviceTypeToInternalDevice(device.getType());
                deviceAdresses[i] = device.getAddress();
                i++;
            }
            final IAudioService service = getService();
            try {
                final int status = service.setUidDeviceAffinity(this.cb(),
                        uid, deviceTypes, deviceAdresses);
                return (status == AudioManager.SUCCESS);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setUidDeviceAffinity", e);
                return false;
            }
        }
    }

    /**
     * @hide
     * Removes audio device affinity previously set by
     * {@link #setUidDeviceAffinity(int, java.util.List)}.
     * @param uid UID of the application affected.
     * @return true if the change was successful, false otherwise.
     */
    @SystemApi
    public boolean removeUidDeviceAffinity(int uid) {
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot use unregistered AudioPolicy");
            }
            final IAudioService service = getService();
            try {
                final int status = service.removeUidDeviceAffinity(this.cb(), uid);
                return (status == AudioManager.SUCCESS);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in removeUidDeviceAffinity", e);
                return false;
            }
        }
    }

    /**
     * @hide
     * Removes audio device affinity previously set by
     * {@link #setUserIdDeviceAffinity(int, java.util.List)}.
     * @param userId userId of the application affected, as obtained via
     * {@link UserHandle#getIdentifier}. Not to be confused with application uid.
     * @return true if the change was successful, false otherwise.
     */
    @SystemApi
    public boolean removeUserIdDeviceAffinity(@UserIdInt int userId) {
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot use unregistered AudioPolicy");
            }
            final IAudioService service = getService();
            try {
                final int status = service.removeUserIdDeviceAffinity(this.cb(), userId);
                return (status == AudioManager.SUCCESS);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in removeUserIdDeviceAffinity", e);
                return false;
            }
        }
    }

    /**
     * @hide
     * Configures the audio framework so that all audio streams originating from the given user
     * can only come from a set of audio devices.
     * For this routing to be operational, a number of {@link AudioMix} instances must have been
     * previously registered on this policy, and routed to a super-set of the given audio devices
     * with {@link AudioMix.Builder#setDevice(android.media.AudioDeviceInfo)}. Note that having
     * multiple devices in the list doesn't imply the signals will be duplicated on the different
     * audio devices, final routing will depend on the {@link AudioAttributes} of the sounds being
     * played.
     * @param userId userId of the application affected, as obtained via
     * {@link UserHandle#getIdentifier}. Not to be confused with application uid.
     * @param devices list of devices to which the audio stream of the application may be routed.
     * @return true if the change was successful, false otherwise.
     */
    @SystemApi
    public boolean setUserIdDeviceAffinity(@UserIdInt int userId,
            @NonNull List<AudioDeviceInfo> devices) {
        Objects.requireNonNull(devices, "Illegal null list of audio devices");
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                throw new IllegalStateException("Cannot use unregistered AudioPolicy");
            }
            final int[] deviceTypes = new int[devices.size()];
            final String[] deviceAddresses = new String[devices.size()];
            int i = 0;
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    throw new IllegalArgumentException(
                            "Illegal null AudioDeviceInfo in setUserIdDeviceAffinity");
                }
                deviceTypes[i] =
                        AudioDeviceInfo.convertDeviceTypeToInternalDevice(device.getType());
                deviceAddresses[i] = device.getAddress();
                i++;
            }
            final IAudioService service = getService();
            try {
                final int status = service.setUserIdDeviceAffinity(this.cb(),
                        userId, deviceTypes, deviceAddresses);
                return (status == AudioManager.SUCCESS);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in setUserIdDeviceAffinity", e);
                return false;
            }
        }
    }

    /** @hide */
    public void reset() {
        setRegistration(null);
    }

    /**
     * @hide
     */
    @TestApi
    @NonNull
    @FlaggedApi(Flags.FLAG_AUDIO_MIX_TEST_API)
    public List<AudioMix> getMixes() {
        if (!Flags.audioMixTestApi()) {
            return Collections.emptyList();
        }
        synchronized (mLock) {
            return List.copyOf(mConfig.getMixes());
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
                mConfig.reset();
            }
        }
        sendMsg(MSG_POLICY_STATUS_CHANGE);
    }

    /**@hide*/
    public String getRegistration() {
        return mRegistrationId;
    }

    /**
     * Sets a custom {@link FadeManagerConfiguration} to handle fade cycle of players during
     * {@link android.media.AudioManager#AUDIOFOCUS_LOSS}
     *
     * @param fmcForFocusLoss custom {@link FadeManagerConfiguration}
     * @return {@link AudioManager#SUCCESS} if the update was successful,
     *     {@link AudioManager#ERROR} otherwise
     * @throws IllegalStateException if the audio policy is not registered
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @SystemApi
    public int setFadeManagerConfigurationForFocusLoss(
            @NonNull FadeManagerConfiguration fmcForFocusLoss) {
        Objects.requireNonNull(fmcForFocusLoss,
                "FadeManagerConfiguration for focus loss cannot be null");

        IAudioService service = getService();
        synchronized (mLock) {
            Preconditions.checkState(isAudioPolicyRegisteredLocked(),
                    "Cannot set FadeManagerConfiguration with unregistered AudioPolicy");

            try {
                return service.setFadeManagerConfigurationForFocusLoss(fmcForFocusLoss);
            } catch (RemoteException e) {
                Log.e(TAG, "Received remote exception for setFadeManagerConfigurationForFocusLoss:",
                        e);
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Clear the current {@link FadeManagerConfiguration} set to handle fade cycles of players
     * during {@link android.media.AudioManager#AUDIOFOCUS_LOSS}
     *
     * <p>In the absence of custom {@link FadeManagerConfiguration}, the default configurations will
     * be used to handle fade cycles during audio focus loss.
     *
     * @return {@link AudioManager#SUCCESS} if the update was successful,
     *     {@link AudioManager#ERROR} otherwise
     * @throws IllegalStateException if the audio policy is not registered
     * @see #setFadeManagerConfigurationForFocusLoss(FadeManagerConfiguration)
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @SystemApi
    public int clearFadeManagerConfigurationForFocusLoss() {
        IAudioService service = getService();
        synchronized (mLock) {
            Preconditions.checkState(isAudioPolicyRegisteredLocked(),
                    "Cannot clear FadeManagerConfiguration from unregistered AudioPolicy");

            try {
                return service.clearFadeManagerConfigurationForFocusLoss();
            } catch (RemoteException e) {
                Log.e(TAG, "Received remote exception for "
                                + "clearFadeManagerConfigurationForFocusLoss:", e);
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get the current fade manager configuration used for fade operations during
     * {@link android.media.AudioManager#AUDIOFOCUS_LOSS}
     *
     * <p>If no custom {@link FadeManagerConfiguration} is set, the default configuration currently
     * active will be returned.
     *
     * @return the active {@link FadeManagerConfiguration} used during audio focus loss
     * @throws IllegalStateException if the audio policy is not registered
     * @see #setFadeManagerConfigurationForFocusLoss(FadeManagerConfiguration)
     * @see #clearFadeManagerConfigurationForFocusLoss()
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    @SystemApi
    @NonNull
    public FadeManagerConfiguration getFadeManagerConfigurationForFocusLoss() {
        IAudioService service = getService();
        synchronized (mLock) {
            Preconditions.checkState(isAudioPolicyRegisteredLocked(),
                    "Cannot get FadeManagerConfiguration from unregistered AudioPolicy");

            try {
                return service.getFadeManagerConfigurationForFocusLoss();
            } catch (RemoteException e) {
                Log.e(TAG, "Received remote exception for getFadeManagerConfigurationForFocusLoss:",
                        e);
                throw e.rethrowFromSystemServer();

            }
        }
    }

    @GuardedBy("mLock")
    private boolean isAudioPolicyRegisteredLocked() {
        return mStatus == POLICY_STATUS_REGISTERED;
    }

    private boolean policyReadyToUse() {
        synchronized (mLock) {
            if (mStatus != POLICY_STATUS_REGISTERED) {
                Log.e(TAG, "Cannot use unregistered AudioPolicy");
                return false;
            }
            if (mRegistrationId == null) {
                Log.e(TAG, "Cannot use unregistered AudioPolicy");
                return false;
            }
        }

        // Loopback|capture only need an audio projection, everything else need MODIFY_AUDIO_ROUTING
        boolean canModifyAudioRouting = PackageManager.PERMISSION_GRANTED
                == checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING);

        boolean canInterceptCallAudio = PackageManager.PERMISSION_GRANTED
                == checkCallingOrSelfPermission(
                        android.Manifest.permission.CALL_AUDIO_INTERCEPTION);

        boolean canProjectAudio;
        try {
            canProjectAudio = mProjection != null && mProjection.getProjection().canProjectAudio();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check if MediaProjection#canProjectAudio");
            throw e.rethrowFromSystemServer();
        }

        if (!((isLoopbackRenderPolicy() && canProjectAudio)
                || (isCallRedirectionPolicy() && canInterceptCallAudio)
                || canModifyAudioRouting)) {
            Slog.w(TAG, "Cannot use AudioPolicy for pid " + Binder.getCallingPid() + " / uid "
                    + Binder.getCallingUid() + ", needs MODIFY_AUDIO_ROUTING or "
                    + "MediaProjection that can project audio.");
            return false;
        }
        return true;
    }

    private boolean isLoopbackRenderPolicy() {
        synchronized (mLock) {
            return mConfig.mMixes.stream().allMatch(mix -> mix.getRouteFlags()
                    == (mix.ROUTE_FLAG_RENDER | mix.ROUTE_FLAG_LOOP_BACK));
        }
    }

    private boolean isCallRedirectionPolicy() {
        synchronized (mLock) {
            for (AudioMix mix : mConfig.mMixes) {
                if (mix.isForCallRedirection()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns {@link PackageManager#PERMISSION_GRANTED} if the caller has the given permission.
     */
    private @PackageManager.PermissionResult int checkCallingOrSelfPermission(String permission) {
        if (mContext != null) {
            return mContext.checkCallingOrSelfPermission(permission);
        }
        Slog.v(TAG, "Null context, checking permission via ActivityManager");
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        try {
            return ActivityManager.getService().checkPermission(permission, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Returns the list of entries in the focus stack.
     * The list is ordered with increasing rank of focus ownership, where the last entry is at the
     * top of the focus stack and is the current focus owner.
     * @return the ordered list of focus owners
     * @see AudioManager#registerAudioPolicy(AudioPolicy)
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull List<AudioFocusInfo> getFocusStack() {
        try {
            return getService().getFocusStack();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Causes the given audio focus owner to lose audio focus with
     * {@link android.media.AudioManager#AUDIOFOCUS_LOSS}, and be removed from the focus stack.
     * Unlike {@link #sendFocusLoss(AudioFocusInfo)}, the method causes the focus stack
     * to be reevaluated as the discarded focus owner may have been at the top of stack,
     * and now the new owner needs to be notified of the gain.
     * @param focusLoser identifies the focus owner to discard from the focus stack
     * @throws IllegalStateException if used on an unregistered policy, or a registered policy
     * with no {@link AudioPolicyFocusListener} set
     * @see #getFocusStack()
     * @see #sendFocusLoss(AudioFocusInfo)
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void sendFocusLossAndUpdate(@NonNull AudioFocusInfo focusLoser)
            throws IllegalStateException {
        try {
            getService().sendFocusLossAndUpdate(Objects.requireNonNull(focusLoser), cb());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Send AUDIOFOCUS_LOSS to a specific stack entry, causing it to be notified of the focus
     * loss, and for it to exit the focus stack (its focus listener will not be invoked after that).
     * This operation is only valid for a registered policy (with
     * {@link AudioManager#registerAudioPolicy(AudioPolicy)}) that is also set as the policy focus
     * listener (with {@link Builder#setAudioPolicyFocusListener(AudioPolicyFocusListener)}.
     * @param focusLoser the stack entry that is exiting the stack through a focus loss
     * @return false if the focusLoser wasn't found in the stack, true otherwise
     * @throws IllegalStateException if used on an unregistered policy, or a registered policy
     *     with no {@link AudioPolicyFocusListener} set
     * @see AudioManager#registerAudioPolicy(AudioPolicy)
     * @see Builder#setAudioPolicyStatusListener(AudioPolicyStatusListener)
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public boolean sendFocusLoss(@NonNull AudioFocusInfo focusLoser) throws IllegalStateException {
        Objects.requireNonNull(focusLoser);
        try {
            return getService().sendFocusLoss(focusLoser, cb());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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

        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setInternalCapturePreset(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .addTag(addressForTag(mix))
                .addTag(AudioRecord.SUBMIX_FIXED_VOLUME);
        if (mix.isForCallRedirection()) {
            ab.setForCallRedirection();
        }
        // create the AudioRecord, configured for loop back, using the same format as the mix
        AudioRecord ar = new AudioRecord(ab.build(),
                mixFormat,
                AudioRecord.getMinBufferSize(mix.getFormat().getSampleRate(),
                        // using stereo for buffer size to avoid the current poor support for masks
                        AudioFormat.CHANNEL_IN_STEREO, mix.getFormat().getEncoding()),
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        synchronized (mLock) {
            if (mCaptors == null) {
                mCaptors = new ArrayList<>(1);
            }
            mCaptors.add(new WeakReference<AudioRecord>(ar));
        }
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
    public AudioTrack createAudioTrackSource(AudioMix mix) throws IllegalArgumentException {
        if (!policyReadyToUse()) {
            Log.e(TAG, "Cannot create AudioTrack source for AudioMix");
            return null;
        }
        checkMixReadyToUse(mix, true/*for an AudioTrack*/);
        // create the AudioTrack, configured for loop back, using the same format as the mix
        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VIRTUAL_SOURCE)
                .addTag(addressForTag(mix));
        if (mix.isForCallRedirection()) {
            ab.setForCallRedirection();
        }
        AudioTrack at = new AudioTrack(ab.build(),
                mix.getFormat(),
                AudioTrack.getMinBufferSize(mix.getFormat().getSampleRate(),
                        mix.getFormat().getChannelMask(), mix.getFormat().getEncoding()),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        synchronized (mLock) {
            if (mInjectors == null) {
                mInjectors = new ArrayList<>(1);
            }
            mInjectors.add(new WeakReference<AudioTrack>(at));
        }
        return at;
    }

    /**
     * @hide
     */
    public void invalidateCaptorsAndInjectors() {
        if (!policyReadyToUse()) {
            return;
        }
        synchronized (mLock) {
            if (mInjectors != null) {
                for (final WeakReference<AudioTrack> weakTrack : mInjectors) {
                    final AudioTrack track = weakTrack.get();
                    if (track == null) {
                        continue;
                    }
                    try {
                        // TODO: add synchronous versions
                        track.stop();
                        track.flush();
                    } catch (IllegalStateException e) {
                        // ignore exception, AudioTrack could have already been stopped or
                        // released by the user of the AudioPolicy
                    }
                }
                mInjectors.clear();
            }
            if (mCaptors != null) {
                for (final WeakReference<AudioRecord> weakRecord : mCaptors) {
                    final AudioRecord record = weakRecord.get();
                    if (record == null) {
                        continue;
                    }
                    try {
                        // TODO: if needed: implement an invalidate method
                        record.stop();
                    } catch (IllegalStateException e) {
                        // ignore exception, AudioRecord could have already been stopped or
                        // released by the user of the AudioPolicy
                    }
                }
                mCaptors.clear();
            }
        }
    }

    public int getStatus() {
        return mStatus;
    }

    public static abstract class AudioPolicyStatusListener {
        public void onStatusChange() {}
        public void onMixStateUpdate(AudioMix mix) {}
    }

    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}
        /**
         * Called whenever an application requests audio focus.
         * Only ever called if the {@link AudioPolicy} was built with
         * {@link AudioPolicy.Builder#setIsAudioFocusPolicy(boolean)} set to {@code true}.
         * @param afi information about the focus request and the requester
         * @param requestResult deprecated after the addition of
         *     {@link AudioManager#setFocusRequestResult(AudioFocusInfo, int, AudioPolicy)}
         *     in Android P, always equal to {@link #AUDIOFOCUS_REQUEST_GRANTED}.
         */
        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}
        /**
         * Called whenever an application abandons audio focus.
         * Only ever called if the {@link AudioPolicy} was built with
         * {@link AudioPolicy.Builder#setIsAudioFocusPolicy(boolean)} set to {@code true}.
         * @param afi information about the focus request being abandoned and the original
         *     requester.
         */
        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }

    /**
     * Callback class to receive volume change-related events.
     * See {@link #Builder.setAudioPolicyVolumeCallback(AudioPolicyCallback)} to configure the
     * {@link AudioPolicy} to receive those events.
     *
     */
    public static abstract class AudioPolicyVolumeCallback {
        public AudioPolicyVolumeCallback() {}
        /**
         * Called when volume key-related changes are triggered, on the key down event.
         * @param adjustment the type of volume adjustment for the key.
         */
        public void onVolumeAdjustment(@AudioManager.VolumeAdjustment int adjustment) {}
    }

    private void onPolicyStatusChange() {
        if (mStatusListener != null) {
            mStatusListener.onStatusChange();
        }
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

        public void notifyAudioFocusRequest(AudioFocusInfo afi, int requestResult) {
            sendMsg(MSG_FOCUS_REQUEST, afi, requestResult);
            if (DEBUG) {
                Log.v(TAG, "notifyAudioFocusRequest: pack=" + afi.getPackageName() + " client="
                        + afi.getClientId() + " gen=" + afi.getGen());
            }
        }

        public void notifyAudioFocusAbandon(AudioFocusInfo afi) {
            sendMsg(MSG_FOCUS_ABANDON, afi, 0 /* ignored */);
            if (DEBUG) {
                Log.v(TAG, "notifyAudioFocusAbandon: pack=" + afi.getPackageName() + " client="
                        + afi.getClientId());
            }
        }

        public void notifyMixStateUpdate(String regId, int state) {
            for (AudioMix mix : mConfig.getMixes()) {
                if (mix.getRegistration().equals(regId)) {
                    mix.mMixState = state;
                    sendMsg(MSG_MIX_STATE_UPDATE, mix, 0/*ignored*/);
                    if (DEBUG) {
                        Log.v(TAG, "notifyMixStateUpdate: regId=" + regId + " state=" + state);
                    }
                }
            }
        }

        public void notifyVolumeAdjust(int adjustment) {
            sendMsg(MSG_VOL_ADJUST, null /* ignored */, adjustment);
            if (DEBUG) {
                Log.v(TAG, "notifyVolumeAdjust: " + adjustment);
            }
        }

        public void notifyUnregistration() {
            setRegistration(null);
        }
    };

    //==================================================
    // Event handling
    private final EventHandler mEventHandler;
    private final static int MSG_POLICY_STATUS_CHANGE = 0;
    private final static int MSG_FOCUS_GRANT = 1;
    private final static int MSG_FOCUS_LOSS = 2;
    private final static int MSG_MIX_STATE_UPDATE = 3;
    private final static int MSG_FOCUS_REQUEST = 4;
    private final static int MSG_FOCUS_ABANDON = 5;
    private final static int MSG_VOL_ADJUST = 6;

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
                case MSG_MIX_STATE_UPDATE:
                    if (mStatusListener != null) {
                        mStatusListener.onMixStateUpdate((AudioMix) msg.obj);
                    }
                    break;
                case MSG_FOCUS_REQUEST:
                    if (mFocusListener != null) {
                        mFocusListener.onAudioFocusRequest((AudioFocusInfo) msg.obj, msg.arg1);
                    } else { // should never be null, but don't crash
                        Log.e(TAG, "Invalid null focus listener for focus request event");
                    }
                    break;
                case MSG_FOCUS_ABANDON:
                    if (mFocusListener != null) { // should never be null
                        mFocusListener.onAudioFocusAbandon((AudioFocusInfo) msg.obj);
                    } else { // should never be null, but don't crash
                        Log.e(TAG, "Invalid null focus listener for focus abandon event");
                    }
                    break;
                case MSG_VOL_ADJUST:
                    if (mVolCb != null) {
                        mVolCb.onVolumeAdjustment(msg.arg1);
                    } else { // should never be null, but don't crash
                        Log.e(TAG, "Invalid null volume event");
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
