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

package com.android.soundpicker;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import com.android.internal.annotations.VisibleForTesting;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import dagger.hilt.android.lifecycle.HiltViewModel;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * A view model which holds immutable info about the picker state and means to retrieve and play
 * currently selected ringtones.
 */
@HiltViewModel
public final class RingtonePickerViewModel extends ViewModel {

    static final int RINGTONE_TYPE_UNKNOWN = -1;

    /**
     * Keep the currently playing ringtone around when changing orientation, so that it
     * can be stopped later, after the activity is recreated.
     */
    @VisibleForTesting
    static Ringtone sPlayingRingtone;

    private static final String TAG = "RingtonePickerViewModel";

    private final RingtoneManagerFactory mRingtoneManagerFactory;
    private final RingtoneFactory mRingtoneFactory;
    private final RingtoneListHandler mSoundListHandler;
    private final RingtoneListHandler mVibrationListHandler;
    private final ListeningExecutorService mListeningExecutorService;

    private RingtoneManager mRingtoneManager;

    /**
     * The ringtone that's currently playing.
     */
    private Ringtone mCurrentRingtone;

    private Config mPickerConfig;

    private ListenableFuture<Uri> mAddCustomRingtoneFuture;

    public enum PickerType {
        RINGTONE_PICKER,
        SOUND_PICKER,
        VIBRATION_PICKER
    }

    /**
     * Holds immutable info on the picker that should be displayed.
     */
    static final class Config {
        public final String title;
        /**
         * Id of the user to which the ringtone picker should list the ringtones.
         */
        public final int userId;
        /**
         * Ringtone type.
         */
        public final int ringtoneType;
        /**
         * AudioAttributes flags.
         */
        public final int audioAttributesFlags;
        /**
         * In the buttonless (watch-only) version we don't show the OK/Cancel buttons.
         */
        public final boolean showOkCancelButtons;

        public final PickerType mPickerType;

        Config(String title, int userId, int ringtoneType, boolean showOkCancelButtons,
                int audioAttributesFlags, PickerType pickerType) {
            this.title = title;
            this.userId = userId;
            this.ringtoneType = ringtoneType;
            this.showOkCancelButtons = showOkCancelButtons;
            this.audioAttributesFlags = audioAttributesFlags;
            this.mPickerType = pickerType;
        }
    }

    @Inject
    RingtonePickerViewModel(RingtoneManagerFactory ringtoneManagerFactory,
            RingtoneFactory ringtoneFactory,
            ListeningExecutorServiceFactory listeningExecutorServiceFactory,
            RingtoneListHandler soundListHandler,
            RingtoneListHandler vibrationListHandler) {
        mRingtoneManagerFactory = ringtoneManagerFactory;
        mRingtoneFactory = ringtoneFactory;
        mListeningExecutorService = listeningExecutorServiceFactory.createSingleThreadExecutor();
        mSoundListHandler = soundListHandler;
        mVibrationListHandler = vibrationListHandler;
    }

    @StringRes
    static int getTitleByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return com.android.internal.R.string.ringtone_picker_title_alarm;
            case RingtoneManager.TYPE_NOTIFICATION:
                return com.android.internal.R.string.ringtone_picker_title_notification;
            default:
                return com.android.internal.R.string.ringtone_picker_title;
        }
    }

    static Uri getDefaultItemUriByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return Settings.System.DEFAULT_ALARM_ALERT_URI;
            case RingtoneManager.TYPE_NOTIFICATION:
                return Settings.System.DEFAULT_NOTIFICATION_URI;
            default:
                return Settings.System.DEFAULT_RINGTONE_URI;
        }
    }

    @StringRes
    static int getAddNewItemTextByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return R.string.add_alarm_text;
            case RingtoneManager.TYPE_NOTIFICATION:
                return R.string.add_notification_text;
            default:
                return R.string.add_ringtone_text;
        }
    }

    @StringRes
    static int getDefaultRingtoneItemTextByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return R.string.alarm_sound_default;
            case RingtoneManager.TYPE_NOTIFICATION:
                return R.string.notification_sound_default;
            default:
                return R.string.ringtone_default;
        }
    }

    void init(@NonNull Config pickerConfig,
            RingtoneListHandler.Config soundListConfig,
            RingtoneListHandler.Config vibrationListConfig) {
        mRingtoneManager = mRingtoneManagerFactory.create();
        mPickerConfig = pickerConfig;
        if (mPickerConfig.ringtoneType != RINGTONE_TYPE_UNKNOWN) {
            mRingtoneManager.setType(mPickerConfig.ringtoneType);
        }
        if (soundListConfig != null) {
            mSoundListHandler.init(soundListConfig, mRingtoneManager,
                    mRingtoneManager.getCursor());
        }
        if (vibrationListConfig != null) {
            // TODO: Switch to the vibration cursor, once the API is made available.
            mVibrationListHandler.init(vibrationListConfig, mRingtoneManager,
                    mRingtoneManager.getCursor());
        }
    }

    /**
     * Re-initializes the view model which is required after updating any of the picker lists.
     * This could happen when adding a custom ringtone.
     */
    void reinit() {
        init(mPickerConfig, mSoundListHandler.getRingtoneListConfig(),
                mVibrationListHandler.getRingtoneListConfig());
    }

    @NonNull
    Config getPickerConfig() {
        requireInitCalled();
        return mPickerConfig;
    }

    @NonNull
    RingtoneListHandler getSoundListHandler() {
        return mSoundListHandler;
    }

    @NonNull
    RingtoneListHandler getVibrationListHandler() {
        return mVibrationListHandler;
    }

    /**
     * Combined the currently selected sound and vibration URIs and returns a unified URI. If the
     * picker does not show either sound or vibration, that portion of the URI will be null.
     *
     * Currently only the sound URI is returned, since we don't have the API to retrieve vibrations
     * yet.
     * @return Combined sound and vibration URI.
     */
    Uri getSelectedRingtoneUri() {
        // TODO: Combine sound and vibration URIs before returning.
        return mSoundListHandler.getSelectedRingtoneUri();
    }

    int getRingtoneStreamType() {
        requireInitCalled();
        return mRingtoneManager.inferStreamType();
    }

    void onPause(boolean isChangingConfigurations) {
        if (!isChangingConfigurations) {
            stopAnyPlayingRingtone();
        }
    }

    void onStop(boolean isChangingConfigurations) {
        if (isChangingConfigurations) {
            saveAnyPlayingRingtone();
        } else {
            stopAnyPlayingRingtone();
        }
    }

    /**
     * Plays a ringtone which is created using the currently selected sound and vibration URIs. If
     * this is a sound or vibration only picker, then the other portion of the URI will be empty
     * and should not affect the played ringtone.
     *
     * Currently, we only use the sound URI to create the ringtone, since we still don't have the
     * API to retrieve the available vibrations list.
     */
    void playRingtone() {
        requireInitCalled();
        stopAnyPlayingRingtone();

        mCurrentRingtone = mRingtoneFactory.create(getSelectedRingtoneUri(),
                mPickerConfig.audioAttributesFlags);

        if (mCurrentRingtone != null) {
            mCurrentRingtone.play();
        }
    }

    /**
     * Cancels all pending async tasks.
     */
    void cancelPendingAsyncTasks() {
        if (mAddCustomRingtoneFuture != null && !mAddCustomRingtoneFuture.isDone()) {
            mAddCustomRingtoneFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }

    /**
     * Adds an audio file to the list of ringtones asynchronously.
     * Any previous async tasks are canceled before start the new one.
     *
     * @param uri      Uri of the file to be added as ringtone. Must be a media file.
     * @param type     The type of the ringtone to be added.
     * @param callback The callback to invoke when the task is completed.
     * @param executor The executor to run the callback on when the task completes.
     */
    void addSoundRingtoneAsync(Uri uri, int type, FutureCallback<Uri> callback, Executor executor) {
        // Cancel any currently running add ringtone tasks before starting a new one
        cancelPendingAsyncTasks();
        mAddCustomRingtoneFuture = mListeningExecutorService.submit(
                () -> addRingtone(uri, type));
        Futures.addCallback(mAddCustomRingtoneFuture, callback, executor);
    }

    /**
     * Adds an audio file to the list of ringtones.
     *
     * @param uri  Uri of the file to be added as ringtone. Must be a media file.
     * @param type The type of the ringtone to be added.
     * @return The Uri of the installed ringtone, which may be the {@code uri} if it
     * is already in ringtone storage. Or null if it failed to add the audio file.
     */
    @Nullable
    private Uri addRingtone(Uri uri, int type) throws IOException {
        requireInitCalled();
        return mRingtoneManager.addCustomExternalRingtone(uri, type);
    }

    private void saveAnyPlayingRingtone() {
        if (mCurrentRingtone != null && mCurrentRingtone.isPlaying()) {
            sPlayingRingtone = mCurrentRingtone;
        }
        mCurrentRingtone = null;
    }

    private void stopAnyPlayingRingtone() {
        if (sPlayingRingtone != null && sPlayingRingtone.isPlaying()) {
            sPlayingRingtone.stop();
        }
        sPlayingRingtone = null;

        if (mCurrentRingtone != null && mCurrentRingtone.isPlaying()) {
            mCurrentRingtone.stop();
        }
        mCurrentRingtone = null;
    }

    private void requireInitCalled() {
        requireNonNull(mRingtoneManager);
        requireNonNull(mPickerConfig);
    }
}
