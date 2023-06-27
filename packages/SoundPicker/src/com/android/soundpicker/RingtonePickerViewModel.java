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
import android.database.Cursor;
import android.media.AudioAttributes;
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
 * View model for {@link RingtonePickerActivity}.
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
    private static final String RINGTONE_MANAGER_NULL_MESSAGE =
            "RingtoneManager must not be null. Did you forget to call "
                    + "RingtonePickerViewModel#initRingtoneManager?";
    private static final int ITEM_POSITION_UNKNOWN = -1;

    private final RingtoneManagerFactory mRingtoneManagerFactory;
    private final RingtoneFactory mRingtoneFactory;
    private final ListeningExecutorService mListeningExecutorService;

    /** The position in the list of the 'Silent' item. */
    private int mSilentItemPosition = ITEM_POSITION_UNKNOWN;
    /** The position in the list of the ringtone to sample. */
    private int mSampleItemPosition = ITEM_POSITION_UNKNOWN;
    /** The position in the list of the 'Default' item. */
    private int mDefaultItemPosition = ITEM_POSITION_UNKNOWN;
    /** The number of fixed items in the list. */
    private int mFixedItemCount;
    private ListenableFuture<Uri> mAddCustomRingtoneFuture;
    private RingtoneManager mRingtoneManager;

    /**
     * Stable ID for the ringtone that is currently selected (may be -1 if no ringtone is selected).
     */
    private long mSelectedItemId = -1;
    private int mSelectedItemPosition = ITEM_POSITION_UNKNOWN;

    /**
     * The ringtone that's currently playing.
     */
    private Ringtone mCurrentRingtone;

    private PickerConfig mPickerConfig;

    public enum PickerType {
        RINGTONE_PICKER,
        SOUND_PICKER,
        VIBRATION_PICKER
    }

    /**
     * Holds immutable info on the picker that should be displayed.
     */
    static final class PickerConfig {
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
         * Whether this list has the 'Default' item.
         */
        public final boolean hasDefaultItem;
        /**
         * The Uri to play when the 'Default' item is clicked.
         */
        public final Uri uriForDefaultItem;
        /**
         * Whether this list has the 'Silent' item.
         */
        public final boolean hasSilentItem;
        /**
         * AudioAttributes flags.
         */
        public final int audioAttributesFlags;
        /**
         * The Uri to place a checkmark next to.
         */
        public final Uri existingUri;
        /**
         * In the buttonless (watch-only) version we don't show the OK/Cancel buttons.
         */
        public final boolean showOkCancelButtons;

        public final PickerType mPickerType;

        PickerConfig(String title, int userId, int ringtoneType,
                boolean hasDefaultItem, Uri uriForDefaultItem, boolean hasSilentItem,
                int audioAttributesFlags, Uri existingUri, boolean showOkCancelButtons,
                PickerType pickerType) {
            this.title = title;
            this.userId = userId;
            this.ringtoneType = ringtoneType;
            this.hasDefaultItem = hasDefaultItem;
            this.uriForDefaultItem = uriForDefaultItem;
            this.hasSilentItem = hasSilentItem;
            this.audioAttributesFlags = audioAttributesFlags;
            this.existingUri = existingUri;
            this.showOkCancelButtons = showOkCancelButtons;
            this.mPickerType = pickerType;
        }
    }

    @Inject
    RingtonePickerViewModel(RingtoneManagerFactory ringtoneManagerFactory,
            RingtoneFactory ringtoneFactory,
            ListeningExecutorServiceFactory listeningExecutorServiceFactory) {
        mRingtoneManagerFactory = ringtoneManagerFactory;
        mRingtoneFactory = ringtoneFactory;
        mListeningExecutorService = listeningExecutorServiceFactory.createSingleThreadExecutor();
    }

    @NonNull
    PickerConfig getPickerConfig() {
        return requireNonNull(mPickerConfig,
                "PickerConfig was never set. Did you forget to call "
                        + "RingtonePickerViewModel#init?");
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

    void init(@NonNull PickerConfig pickerConfig) {
        mRingtoneManager = mRingtoneManagerFactory.create();
        mPickerConfig = pickerConfig;
        if (pickerConfig.ringtoneType != RINGTONE_TYPE_UNKNOWN) {
            mRingtoneManager.setType(pickerConfig.ringtoneType);
        }
    }

    /**
     * Adds an audio file to the list of ringtones asynchronously.
     * Any previous async tasks are canceled before start the new one.
     *
     * @param uri  Uri of the file to be added as ringtone. Must be a media file.
     * @param type The type of the ringtone to be added.
     * @param callback The callback to invoke when the task is completed.
     * @param executor The executor to run the callback on when the task completes.
     */
    void addRingtoneAsync(Uri uri, int type, FutureCallback<Uri> callback, Executor executor) {
        // Cancel any currently running add ringtone tasks before starting a new one
        cancelPendingAsyncTasks();
        mAddCustomRingtoneFuture = mListeningExecutorService.submit(() -> addRingtone(uri, type));
        Futures.addCallback(mAddCustomRingtoneFuture, callback, executor);
    }

    /**
     * Cancels all pending async tasks.
     */
    void cancelPendingAsyncTasks() {
        if (mAddCustomRingtoneFuture != null && !mAddCustomRingtoneFuture.isDone()) {
            mAddCustomRingtoneFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
    }

    int getRingtoneStreamType() {
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
        return mRingtoneManager.inferStreamType();
    }

    Cursor getRingtoneCursor() {
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
        return mRingtoneManager.getCursor();
    }

    Uri getRingtoneUri(int position) {
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
        return mRingtoneManager.getRingtoneUri(mapListPositionToRingtonePosition(position));
    }

    int getRingtonePosition(Uri uri) {
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
        return mapRingtonePositionToListPosition(mRingtoneManager.getRingtonePosition(uri));
    }

    /**
     * Maps the item position in the list, to its equivalent position in the RingtoneManager.
     *
     * @param itemPosition the position of item in the list.
     * @return position of the item in the RingtoneManager.
     */
    private int mapListPositionToRingtonePosition(int itemPosition) {
        // If the manager position is -1 (for not found), then return that.
        if (itemPosition < 0) return itemPosition;

        return itemPosition - mFixedItemCount;
    }

    /**
     * Maps the item position in the RingtoneManager, to its equivalent position in the list.
     *
     * @param itemPosition the position of the item in the RingtoneManager.
     * @return position of the item in the list.
     */
    private int mapRingtonePositionToListPosition(int itemPosition) {
        // If the manager position is -1 (for not found), then return that.
        if (itemPosition < 0) return itemPosition;

        return itemPosition + mFixedItemCount;
    }

    void resetFixedItemCount() {
        mFixedItemCount = 0;
    }

    int incrementAndGetFixedItemCount() {
        return mFixedItemCount++;
    }

    void setDefaultItemPosition(int defaultItemPosition) {
        mDefaultItemPosition = defaultItemPosition;
    }

    int getSilentItemPosition() {
        return mSilentItemPosition;
    }

    void setSilentItemPosition(int silentItemPosition) {
        mSilentItemPosition = silentItemPosition;
    }

    public int getSampleItemPosition() {
        return mSampleItemPosition;
    }

    public void setSampleItemPosition(int sampleItemPosition) {
        mSampleItemPosition = sampleItemPosition;
    }

    public int getSelectedItemPosition() {
        return mSelectedItemPosition;
    }

    public void setSelectedItemPosition(int selectedItemPosition) {
        mSelectedItemPosition = selectedItemPosition;
    }

    public void setSelectedItemId(long selectedItemId) {
        mSelectedItemId = selectedItemId;
    }

    public long getSelectedItemId() {
        return mSelectedItemId;
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

    @Nullable
    Uri getCurrentlySelectedRingtoneUri() {
        if (mSelectedItemPosition == ITEM_POSITION_UNKNOWN) {
            // When the selected item is POS_UNKNOWN, it is not the case we expected.
            // We return null for this case.
            return null;
        } else if (mSelectedItemPosition == mDefaultItemPosition) {
            // Use the default Uri that they originally gave us.
            return mPickerConfig.uriForDefaultItem;
        } else if (mSelectedItemPosition == mSilentItemPosition) {
            // Use a null Uri for the 'Silent' item.
            return null;
        } else {
            return getRingtoneUri(mSelectedItemPosition);
        }
    }

    void playRingtone(int position, Uri uriForDefaultItem, int attributesFlags) {
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
        stopAnyPlayingRingtone();
        if (mSampleItemPosition == mSilentItemPosition) {
            return;
        }

        if (mSampleItemPosition == mDefaultItemPosition) {
            mCurrentRingtone = mRingtoneFactory.create(uriForDefaultItem);
            /*
             * Stream type of mDefaultRingtone is not set explicitly here. It should be set in
             * accordance with mRingtoneManager of this Activity.
             */
            if (mCurrentRingtone != null) {
                mCurrentRingtone.setStreamType(mRingtoneManager.inferStreamType());
            }
        } else {
            mCurrentRingtone = mRingtoneManager.getRingtone(
                    mapListPositionToRingtonePosition(position));
        }

        if (mCurrentRingtone != null) {
            if (attributesFlags != 0) {
                mCurrentRingtone.setAudioAttributes(new AudioAttributes.Builder(
                        mCurrentRingtone.getAudioAttributes()).setFlags(attributesFlags).build());
            }
            mCurrentRingtone.play();
        }
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
        requireNonNull(mRingtoneManager, RINGTONE_MANAGER_NULL_MESSAGE);
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
}
