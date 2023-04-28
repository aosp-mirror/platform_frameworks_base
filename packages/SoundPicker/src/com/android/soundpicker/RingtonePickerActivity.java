/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

import java.util.regex.Pattern;

/**
 * The {@link RingtonePickerActivity} allows the user to choose one from all of the
 * available ringtones. The chosen ringtone's URI will be persisted as a string.
 *
 * @see RingtoneManager#ACTION_RINGTONE_PICKER
 */
public final class RingtonePickerActivity extends AlertActivity implements
        AdapterView.OnItemSelectedListener, Runnable, DialogInterface.OnClickListener,
        AlertController.AlertParams.OnPrepareListViewListener {

    private static final int POS_UNKNOWN = -1;

    private static final String TAG = "RingtonePickerActivity";

    private static final int DELAY_MS_SELECTION_PLAYED = 300;

    private static final String COLUMN_LABEL = MediaStore.Audio.Media.TITLE;

    private static final String SAVE_CLICKED_POS = "clicked_pos";

    private static final String SOUND_NAME_RES_PREFIX = "sound_name_";

    private static final int ADD_FILE_REQUEST_CODE = 300;

    private RingtonePickerViewModel mRingtonePickerViewModel;

    private int mType;

    private Cursor mCursor;
    private Handler mHandler;
    private BadgedRingtoneAdapter mAdapter;

    /** Whether this list has the 'Silent' item. */
    private boolean mHasSilentItem;

    /** The Uri to place a checkmark next to. */
    private Uri mExistingUri;

    /** Whether this list has the 'Default' item. */
    private boolean mHasDefaultItem;

    /** The Uri to play when the 'Default' item is clicked. */
    private Uri mUriForDefaultItem;

    /** Id of the user to which the ringtone picker should list the ringtones */
    private int mPickerUserId;

    /**
     * Stable ID for the ringtone that is currently checked (may be -1 if no ringtone is checked).
     */
    private long mCheckedItemId = -1;

    private int mAttributesFlags;

    private boolean mShowOkCancelButtons;

    private final DialogInterface.OnClickListener mRingtoneClickListener =
            new DialogInterface.OnClickListener() {

        /*
         * On item clicked
         */
        public void onClick(DialogInterface dialog, int which) {
            if (which == mCursor.getCount() + mRingtonePickerViewModel.getFixedItemCount()) {
                // The "Add new ringtone" item was clicked. Start a file picker intent to select
                // only audio files (MIME type "audio/*")
                final Intent chooseFile = getMediaFilePickerIntent();
                startActivityForResult(chooseFile, ADD_FILE_REQUEST_CODE);
                return;
            }

            // Save the position of most recently clicked item
            setCheckedItem(which);

            // In the buttonless (watch-only) version, preemptively set our result since we won't
            // have another chance to do so before the activity closes.
            if (!mShowOkCancelButtons) {
                setSuccessResultWithRingtone(
                        mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri(getCheckedItem(),
                                mUriForDefaultItem));
            }

            // Play clip
            playRingtone(which, 0);
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRingtonePickerViewModel = new RingtonePickerViewModel(
                new RingtoneManagerFactory(this), new RingtoneFactory(this));
        mHandler = new Handler();

        Intent intent = getIntent();
        mPickerUserId = UserHandle.myUserId();

        // Get the types of ringtones to show
        mType = intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtonePickerViewModel.RINGTONE_TYPE_UNKNOWN);
        mRingtonePickerViewModel.setRingtoneType(mType);
        setupCursor();

        /*
         * Get whether to show the 'Default' item, and the URI to play when the
         * default is clicked
         */
        mHasDefaultItem = intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        mUriForDefaultItem = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI);
        if (mUriForDefaultItem == null) {
            mUriForDefaultItem = RingtonePickerViewModel.getDefaultItemUriByType(mType);
        }

        // Get whether to show the 'Silent' item
        mHasSilentItem = intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        // AudioAttributes flags
        mAttributesFlags |= intent.getIntExtra(
                RingtoneManager.EXTRA_RINGTONE_AUDIO_ATTRIBUTES_FLAGS,
                0 /*defaultValue == no flags*/);

        mShowOkCancelButtons = getResources().getBoolean(R.bool.config_showOkCancelButtons);

        // The volume keys will control the stream that we are choosing a ringtone for
        setVolumeControlStream(mRingtonePickerViewModel.getRingtoneStreamType());

        // Get the URI whose list item should have a checkmark
        mExistingUri = intent
                .getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);

        // Create the list of ringtones and hold on to it so we can update later.
        mAdapter = new BadgedRingtoneAdapter(this, mCursor,
                /* isManagedProfile = */ UserManager.get(this).isManagedProfile(mPickerUserId));
        if (savedInstanceState != null) {
            setCheckedItem(savedInstanceState.getInt(SAVE_CLICKED_POS, POS_UNKNOWN));
        }

        final AlertController.AlertParams p = mAlertParams;
        p.mAdapter = mAdapter;
        p.mOnClickListener = mRingtoneClickListener;
        p.mLabelColumn = COLUMN_LABEL;
        p.mIsSingleChoice = true;
        p.mOnItemSelectedListener = this;
        if (mShowOkCancelButtons) {
            p.mPositiveButtonText = getString(com.android.internal.R.string.ok);
            p.mPositiveButtonListener = this;
            p.mNegativeButtonText = getString(com.android.internal.R.string.cancel);
            p.mPositiveButtonListener = this;
        }
        p.mOnPrepareListViewListener = this;
        p.mTitle = intent.getCharSequenceExtra(RingtoneManager.EXTRA_RINGTONE_TITLE);
        if (p.mTitle == null) {
            p.mTitle = getString(RingtonePickerViewModel.getTitleByType(mType));
        }

        setupAlert();

        ListView listView = mAlert.getListView();
        if (listView != null) {
            // List view needs to gain focus in order for RSB to work.
            if (!listView.requestFocus()) {
                Log.e(TAG, "Unable to gain focus! RSB may not work properly.");
            }
        }
    }
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_CLICKED_POS, getCheckedItem());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ADD_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            // Add the custom ringtone in a separate thread
            final AsyncTask<Uri, Void, Uri> installTask = new AsyncTask<Uri, Void, Uri>() {
                @Override
                protected Uri doInBackground(Uri... params) {
                    return mRingtonePickerViewModel.addRingtone(params[0], mType);
                }

                @Override
                protected void onPostExecute(Uri ringtoneUri) {
                    if (ringtoneUri != null) {
                        requeryForAdapter();
                    } else {
                        // Ringtone was not added, display error Toast
                        Toast.makeText(RingtonePickerActivity.this, R.string.unable_to_add_ringtone,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            };
            installTask.execute(data.getData());
        }
    }

    // Disabled because context menus aren't Material Design :(
    /*
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        int position = ((AdapterContextMenuInfo) menuInfo).position;

        Ringtone ringtone = getRingtone(getRingtoneManagerPosition(position));
        if (ringtone != null && mRingtoneManager.isCustomRingtone(ringtone.getUri())) {
            // It's a custom ringtone so we display the context menu
            menu.setHeaderTitle(ringtone.getTitle(this));
            menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, R.string.delete_ringtone_text);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case Menu.FIRST: {
                int deletedRingtonePos = ((AdapterContextMenuInfo) item.getMenuInfo()).position;
                Uri deletedRingtoneUri = getRingtone(
                        getRingtoneManagerPosition(deletedRingtonePos)).getUri();
                if(mRingtoneManager.deleteExternalRingtone(deletedRingtoneUri)) {
                    requeryForAdapter();
                } else {
                    Toast.makeText(this, R.string.unable_to_delete_ringtone, Toast.LENGTH_SHORT)
                            .show();
                }
                return true;
            }
            default: {
                return false;
            }
        }
    }
    */

    @Override
    public void onDestroy() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
        super.onDestroy();
    }

    public void onPrepareListView(ListView listView) {
        // Reset the static item count, as this method can be called multiple times
        mRingtonePickerViewModel.resetFixedItemCount();

        if (mHasDefaultItem) {
            int defaultItemPos = addDefaultRingtoneItem(listView);

            if (getCheckedItem() == POS_UNKNOWN && RingtoneManager.isDefault(mExistingUri)) {
                setCheckedItem(defaultItemPos);
            }
        }

        if (mHasSilentItem) {
            int silentItemPos = addSilentItem(listView);

            // The 'Silent' item should use a null Uri
            if (getCheckedItem() == POS_UNKNOWN && mExistingUri == null) {
                setCheckedItem(silentItemPos);
            }
        }

        if (getCheckedItem() == POS_UNKNOWN) {
            setCheckedItem(
                    getListPosition(mRingtonePickerViewModel.getRingtonePosition(mExistingUri)));
        }

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mShowOkCancelButtons) {
            setSuccessResultWithRingtone(
                    mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri(getCheckedItem(),
                            mUriForDefaultItem));
        }
        // If external storage is available, add a button to install sounds from storage.
        if (resolvesMediaFilePicker()
                && Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            addNewSoundItem(listView);
        }

        // Enable context menu in ringtone items
        registerForContextMenu(listView);
    }

    /**
     * Re-query RingtoneManager for the most recent set of installed ringtones. May move the
     * selected item position to match the new position of the chosen sound.
     *
     * This should only need to happen after adding or removing a ringtone.
     */
    private void requeryForAdapter() {
        // Refresh and set a new cursor, closing the old one.
        mRingtonePickerViewModel.initRingtoneManager(mType);
        setupCursor();
        mAdapter.changeCursor(mCursor);

        // Update checked item location.
        int checkedPosition = POS_UNKNOWN;
        for (int i = 0; i < mAdapter.getCount(); i++) {
            if (mAdapter.getItemId(i) == mCheckedItemId) {
                checkedPosition = getListPosition(i);
                break;
            }
        }
        if (mHasSilentItem && checkedPosition == POS_UNKNOWN) {
            checkedPosition = mRingtonePickerViewModel.getSilentItemPosition();
        }
        setCheckedItem(checkedPosition);
        setupAlert();
    }

    /**
     * Adds a static item to the top of the list. A static item is one that is not from the
     * RingtoneManager.
     *
     * @param listView The ListView to add to.
     * @param textResId The resource ID of the text for the item.
     * @return The position of the inserted item.
     */
    private int addStaticItem(ListView listView, int textResId) {
        TextView textView = (TextView) getLayoutInflater().inflate(
                com.android.internal.R.layout.select_dialog_singlechoice_material, listView, false);
        textView.setText(textResId);
        listView.addHeaderView(textView);
        mRingtonePickerViewModel.incrementFixedItemCount();
        return listView.getHeaderViewsCount() - 1;
    }

    private int addDefaultRingtoneItem(ListView listView) {
        int defaultRingtoneItemPos = addStaticItem(listView,
                RingtonePickerViewModel.getDefaultRingtoneItemTextByType(mType));
        mRingtonePickerViewModel.setDefaultItemPosition(defaultRingtoneItemPos);
        return defaultRingtoneItemPos;
    }

    private int addSilentItem(ListView listView) {
        int silentItemPos = addStaticItem(listView, com.android.internal.R.string.ringtone_silent);
        mRingtonePickerViewModel.setSilentItemPosition(silentItemPos);
        return silentItemPos;
    }

    private void addNewSoundItem(ListView listView) {
        View view = getLayoutInflater().inflate(R.layout.add_new_sound_item, listView,
                false /* attachToRoot */);
        TextView text = (TextView)view.findViewById(R.id.add_new_sound_text);

        text.setText(RingtonePickerViewModel.getAddNewItemTextByType(mType));

        listView.addFooterView(view);
    }

    private void setupCursor() {
        mCursor = new LocalizedCursor(
                mRingtonePickerViewModel.getRingtoneCursor(), getResources(), COLUMN_LABEL);
    }

    private int getCheckedItem() {
        return mAlertParams.mCheckedItem;
    }

    private void setCheckedItem(int pos) {
        mAlertParams.mCheckedItem = pos;
        mCheckedItemId = mAdapter.getItemId(
                mRingtonePickerViewModel.itemPositionToRingtonePosition(pos));
    }

    /*
     * On click of Ok/Cancel buttons
     */
    public void onClick(DialogInterface dialog, int which) {
        boolean positiveResult = which == DialogInterface.BUTTON_POSITIVE;

        if (positiveResult) {
            setSuccessResultWithRingtone(
                    mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri(getCheckedItem(),
                            mUriForDefaultItem));
        } else {
            setResult(RESULT_CANCELED);
        }

        finish();
    }

    /*
     * On item selected via keys
     */
    public void onItemSelected(AdapterView parent, View view, int position, long id) {
        // footer view
        if (position >= mCursor.getCount() + mRingtonePickerViewModel.getFixedItemCount()) {
            return;
        }

        playRingtone(position, DELAY_MS_SELECTION_PLAYED);

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mShowOkCancelButtons) {
            setSuccessResultWithRingtone(
                    mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri(getCheckedItem(),
                            mUriForDefaultItem));
        }
    }

    public void onNothingSelected(AdapterView parent) {
    }

    private void playRingtone(int position, int delayMs) {
        mHandler.removeCallbacks(this);
        mRingtonePickerViewModel.setSampleItemPosition(position);
        mHandler.postDelayed(this, delayMs);
    }

    public void run() {
        mRingtonePickerViewModel.playRingtone(
                mRingtonePickerViewModel.itemPositionToRingtonePosition(
                        mRingtonePickerViewModel.getSampleItemPosition()), mUriForDefaultItem,
                mAttributesFlags);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mRingtonePickerViewModel.onStop(isChangingConfigurations());
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRingtonePickerViewModel.onPause(isChangingConfigurations());
    }

    private void setSuccessResultWithRingtone(Uri ringtoneUri) {
      setResult(RESULT_OK,
          new Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, ringtoneUri));
    }


    private int getListPosition(int ringtoneManagerPos) {

        // If the manager position is -1 (for not found), return that
        if (ringtoneManagerPos < 0) return ringtoneManagerPos;

        return ringtoneManagerPos + mRingtonePickerViewModel.getFixedItemCount();
    }

    private Intent getMediaFilePickerIntent() {
        final Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
        chooseFile.setType("audio/*");
        chooseFile.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] { "audio/*", "application/ogg" });
        return chooseFile;
    }

    private boolean resolvesMediaFilePicker() {
        return getMediaFilePickerIntent().resolveActivity(getPackageManager()) != null;
    }

    private static class LocalizedCursor extends CursorWrapper {

        final int mTitleIndex;
        final Resources mResources;
        String mNamePrefix;
        final Pattern mSanitizePattern;

        LocalizedCursor(Cursor cursor, Resources resources, String columnLabel) {
            super(cursor);
            mTitleIndex = mCursor.getColumnIndex(columnLabel);
            mResources = resources;
            mSanitizePattern = Pattern.compile("[^a-zA-Z0-9]");
            if (mTitleIndex == -1) {
                Log.e(TAG, "No index for column " + columnLabel);
                mNamePrefix = null;
            } else {
                try {
                    // Build the prefix for the name of the resource to look up
                    // format is: "ResourcePackageName::ResourceTypeName/"
                    // (the type name is expected to be "string" but let's not hardcode it).
                    // Here we use an existing resource "notification_sound_default" which is
                    // always expected to be found.
                    mNamePrefix = String.format("%s:%s/%s",
                            mResources.getResourcePackageName(R.string.notification_sound_default),
                            mResources.getResourceTypeName(R.string.notification_sound_default),
                            SOUND_NAME_RES_PREFIX);
                } catch (NotFoundException e) {
                    mNamePrefix = null;
                }
            }
        }

        /**
         * Process resource name to generate a valid resource name.
         * @param input
         * @return a non-null String
         */
        private String sanitize(String input) {
            if (input == null) {
                return "";
            }
            return mSanitizePattern.matcher(input).replaceAll("_").toLowerCase();
        }

        @Override
        public String getString(int columnIndex) {
            final String defaultName = mCursor.getString(columnIndex);
            if ((columnIndex != mTitleIndex) || (mNamePrefix == null)) {
                return defaultName;
            }
            TypedValue value = new TypedValue();
            try {
                // the name currently in the database is used to derive a name to match
                // against resource names in this package
                mResources.getValue(mNamePrefix + sanitize(defaultName), value, false);
            } catch (NotFoundException e) {
                // no localized string, use the default string
                return defaultName;
            }
            if ((value != null) && (value.type == TypedValue.TYPE_STRING)) {
                Log.d(TAG, String.format("Replacing name %s with %s",
                        defaultName, value.string.toString()));
                return value.string.toString();
            } else {
                Log.e(TAG, "Invalid value when looking up localized name, using " + defaultName);
                return defaultName;
            }
        }
    }

    private class BadgedRingtoneAdapter extends CursorAdapter {
        private final boolean mIsManagedProfile;

        public BadgedRingtoneAdapter(Context context, Cursor cursor, boolean isManagedProfile) {
            super(context, cursor);
            mIsManagedProfile = isManagedProfile;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0) {
                return position;
            }
            return super.getItemId(position);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(context);
            return inflater.inflate(R.layout.radio_with_work_badge, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            // Set text as the title of the ringtone
            ((TextView) view.findViewById(R.id.checked_text_view))
                    .setText(cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX));

            boolean isWorkRingtone = false;
            if (mIsManagedProfile) {
                /*
                 * Display the work icon if the ringtone belongs to a work profile. We can tell that
                 * a ringtone belongs to a work profile if the picker user is a managed profile, the
                 * ringtone Uri is in external storage, and either the uri has no user id or has the
                 * id of the picker user
                 */
                Uri currentUri = mRingtonePickerViewModel.getRingtoneUri(cursor.getPosition());
                int uriUserId = ContentProvider.getUserIdFromUri(currentUri, mPickerUserId);
                Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(currentUri);

                if (uriUserId == mPickerUserId && uriWithoutUserId.toString()
                        .startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())) {
                    isWorkRingtone = true;
                }
            }

            ImageView workIcon = (ImageView) view.findViewById(R.id.work_icon);
            if(isWorkRingtone) {
                workIcon.setImageDrawable(getPackageManager().getUserBadgeForDensityNoBackground(
                        UserHandle.of(mPickerUserId), -1 /* density */));
                workIcon.setVisibility(View.VISIBLE);
            } else {
                workIcon.setVisibility(View.GONE);
            }
        }
    }
}
