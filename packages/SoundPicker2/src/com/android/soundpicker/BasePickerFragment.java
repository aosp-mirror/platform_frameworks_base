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

import android.app.Activity;
import android.content.ContentProvider;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dagger.hilt.android.AndroidEntryPoint;

import java.util.Objects;

/**
 * Base class for generic picker fragments.
 *
 * <p>This fragment displays a recycler view that is populated by a {@link RingtoneListViewAdapter}
 * with data provided by a {@link RingtoneListHandler}. Each item can be selected on click,
 * which also triggers a ringtone preview performed by the shared {@link RingtonePickerViewModel}.
 * The ringtone preview uses the selection state of all picker fragments (e.g. sound selected by
 * one fragment and vibration selected by another).
 */
@AndroidEntryPoint(Fragment.class)
public abstract class BasePickerFragment extends Hilt_BasePickerFragment implements
        RingtoneListViewAdapter.Callbacks {

    private static final String TAG = "BasePickerFragment";
    private static final String COLUMN_LABEL = MediaStore.Audio.Media.TITLE;
    private boolean mIsManagedProfile;
    private Drawable mWorkIconDrawable;

    protected RingtoneListViewAdapter mRingtoneListViewAdapter;
    protected RecyclerView mRecyclerView;
    protected RingtonePickerViewModel.Config mPickerConfig;
    protected RingtonePickerViewModel mRingtonePickerViewModel;
    protected RingtoneListHandler.Config mRingtoneListConfig;
    protected RingtoneListHandler mRingtoneListHandler;

    public BasePickerFragment() {
        super(R.layout.fragment_ringtone_picker);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRingtonePickerViewModel = new ViewModelProvider(requireActivity()).get(
                RingtonePickerViewModel.class);
        mRingtoneListHandler = getRingtoneListHandler();
        mRecyclerView = view.requireViewById(R.id.recycler_view);

        mPickerConfig = mRingtonePickerViewModel.getPickerConfig();
        mRingtoneListConfig = mRingtoneListHandler.getRingtoneListConfig();

        mIsManagedProfile = UserManager.get(requireActivity()).isManagedProfile(
                mPickerConfig.userId);

        mRingtoneListViewAdapter = createRingtoneListViewAdapter();
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setAdapter(mRingtoneListViewAdapter);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        setSelectedItem(mRingtoneListHandler.getSelectedItemPosition());
        prepareRecyclerView(mRecyclerView);
    }

    @Override
    public boolean isWorkRingtone(int position) {
        if (!mIsManagedProfile) {
            return false;
        }

        /*
         * Display the work icon if the ringtone belongs to a work profile. We
         * can tell that a ringtone belongs to a work profile if the picker user
         * is a managed profile, the ringtone Uri is in external storage, and
         * either the uri has no user id or has the id of the picker user
         */
        Uri currentUri = mRingtoneListHandler.getRingtoneUri(position);
        int uriUserId = ContentProvider.getUserIdFromUri(currentUri,
                mPickerConfig.userId);
        Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(currentUri);

        return uriUserId == mPickerConfig.userId
                && uriWithoutUserId.toString().startsWith(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString());
    }

    @Override
    public Drawable getWorkIconDrawable() {
        if (mWorkIconDrawable == null) {
            mWorkIconDrawable = requireActivity().getPackageManager()
                    .getUserBadgeForDensityNoBackground(
                            UserHandle.of(mPickerConfig.userId), /* density= */ -1);
        }

        return mWorkIconDrawable;
    }

    @Override
    public void onRingtoneSelected(int position) {
        setSelectedItem(position);

        // In the buttonless (watch-only) version, preemptively set our result since
        // we won't have another chance to do so before the activity closes.
        if (!mPickerConfig.showOkCancelButtons) {
            setSuccessResultWithSelectedRingtone();
        }

        // Play clip
        mRingtonePickerViewModel.playRingtone();
    }

    @Override
    public void onAddRingtoneSelected() {
        addRingtoneAsync();
    }

    /**
     * Sets up the list by adding fixed items to the top and bottom, if required. And sets the
     * selected item in the list.
     * @param recyclerView The recyclerview that contains the list of displayed items.
     */
    protected void prepareRecyclerView(@NonNull RecyclerView recyclerView) {
        // Reset the static item count, as this method can be called multiple times
        mRingtoneListHandler.resetFixedItems();

        if (mRingtoneListConfig.hasDefaultItem) {
            int defaultItemPos = addDefaultRingtoneItem();

            if (getSelectedItem() < 0
                    && RingtoneManager.isDefault(mRingtoneListConfig.initialSelectedUri)) {
                setSelectedItem(defaultItemPos);
            }
        }

        if (mRingtoneListConfig.hasSilentItem) {
            int silentItemPos = addSilentItem();

            // The 'Silent' item should use a null Uri
            if (getSelectedItem() < 0
                    && mRingtoneListConfig.initialSelectedUri == null) {
                setSelectedItem(silentItemPos);
            }
        }

        if (getSelectedItem() < 0) {
            setSelectedItem(mRingtoneListHandler.getRingtonePosition(
                    mRingtoneListConfig.initialSelectedUri));
        }

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mPickerConfig.showOkCancelButtons) {
            setSuccessResultWithSelectedRingtone();
        }

        addNewRingtoneItem();

        // Enable context menu in ringtone items
        registerForContextMenu(recyclerView);
    }

    /**
     * Returns the fragment's sound/vibration list handler.
     * @return The ringtone list handler.
     */
    protected abstract RingtoneListHandler getRingtoneListHandler();

    /**
     * Starts the process to add a new ringtone to the list of ringtones asynchronously.
     * Currently, only works for adding sound files.
     */
    protected abstract void addRingtoneAsync();

    /**
     * Adds an item to the end of the list that can be used to add new ringtones to the list.
     * Currently, only works for adding sound files.
     */
    protected abstract void addNewRingtoneItem();

    protected int getSelectedItem() {
        return mRingtoneListHandler.getSelectedItemPosition();
    }

    /**
     * Returns the selected URI to the caller activity.
     */
    protected void setSuccessResultWithSelectedRingtone() {
        requireActivity().setResult(Activity.RESULT_OK,
                new Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        mRingtonePickerViewModel.getSelectedRingtoneUri()));
    }

    /**
     * Creates a ringtone recyclerview adapter using the ringtone manager cursor.
     * @return The created RingtoneListViewAdapter.
     */
    protected RingtoneListViewAdapter createRingtoneListViewAdapter() {
        LocalizedCursor cursor = new LocalizedCursor(
                mRingtoneListHandler.getRingtoneCursor(), getResources(), COLUMN_LABEL);
        return new RingtoneListViewAdapter(cursor, /* RingtoneListViewAdapterCallbacks= */ this);
    }

    /**
     * Sets the selected item in the list and scroll to the position in the recyclerview.
     * @param pos the position of the selected item in the list.
     */
    protected void setSelectedItem(int pos) {
        Objects.requireNonNull(mRingtoneListViewAdapter);
        mRingtoneListHandler.setSelectedItemPosition(pos);
        mRingtoneListViewAdapter.setSelectedItem(pos);
        mRingtoneListHandler.setSelectedItemId(mRingtoneListViewAdapter.getItemId(pos));
        mRecyclerView.scrollToPosition(pos);
    }

    /**
     * Adds a fixed item to the fixed items list . A fixed item is one that is not from
     * the RingtoneManager.
     *
     * @param textResId The resource ID of the text for the item.
     * @return The index of the inserted fixed item in the adapter.
     */
    protected int addFixedItem(int textResId) {
        return mRingtoneListViewAdapter.addTitleForFixedItem(textResId);
    }

    /**
     * Re-query RingtoneManager for the most recent set of installed ringtones. May move the
     * selected item position to match the new position of the chosen ringtone.
     * <p>
     * This should only need to happen after adding or removing a ringtone.
     */
    protected void requeryForAdapter() {
        mRingtonePickerViewModel.reinit();
        // Refresh and set a new cursor, and closing the old one.
        mRingtoneListViewAdapter = createRingtoneListViewAdapter();
        mRecyclerView.setAdapter(mRingtoneListViewAdapter);
        prepareRecyclerView(mRecyclerView);

        // Update selected item location.
        for (int i = 0; i < mRingtoneListViewAdapter.getItemCount(); i++) {
            if (mRingtoneListViewAdapter.getItemId(i)
                    == mRingtoneListHandler.getSelectedItemId()) {
                setSelectedItem(i);
                return;
            }
        }

        // If selected item is still unknown, then set it to the default item, if available.
        // If it's not available, then attempt to set it to the silent item in the list.
        int selectedPosition = mRingtoneListHandler.getDefaultItemPosition();

        if (selectedPosition < 0) {
            selectedPosition = mRingtoneListHandler.getSilentItemPosition();
        }

        setSelectedItem(selectedPosition);
    }

    private int addDefaultRingtoneItem() {
        int defaultItemPosInAdapter = addFixedItem(
                RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                        mPickerConfig.ringtoneType));
        int defaultItemPosInListHandler = mRingtoneListHandler.addDefaultItem();

        if (defaultItemPosInAdapter != defaultItemPosInListHandler) {
            Log.wtf(TAG, "Default item position in adapter and list handler must match.");
            return RingtoneListHandler.ITEM_POSITION_UNKNOWN;
        }

        return defaultItemPosInListHandler;
    }

    private int addSilentItem() {
        int silentItemPosInAdapter = addFixedItem(com.android.internal.R.string.ringtone_silent);
        int silentItemPosInListHandler = mRingtoneListHandler.addSilentItem();

        if (silentItemPosInAdapter != silentItemPosInListHandler) {
            Log.wtf(TAG, "Silent item position in adapter and list handler must match.");
            return RingtoneListHandler.ITEM_POSITION_UNKNOWN;
        }

        return silentItemPosInListHandler;
    }
}
