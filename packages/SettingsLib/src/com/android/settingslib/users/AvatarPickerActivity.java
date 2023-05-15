/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.users;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.util.UserIcons;
import com.android.settingslib.R;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeHelper;
import com.google.android.setupdesign.util.ThemeResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Activity to allow the user to choose a user profile picture.
 *
 * <p>Options are provided to take a photo or choose a photo using the photo picker. In addition,
 * preselected avatar images may be provided in the resource array {@code avatar_images}. If
 * provided, every element of that array must be a bitmap drawable.
 *
 * <p>If preselected images are not provided, the default avatar will be shown instead, in a range
 * of colors.
 *
 * <p>This activity should be started with startActivityForResult. If a photo or a preselected image
 * is selected, a Uri will be returned in the data field of the result intent. If a colored default
 * avatar is selected, the chosen color will be returned as {@code EXTRA_DEFAULT_ICON_TINT_COLOR}
 * and the data field will be empty.
 */
public class AvatarPickerActivity extends Activity {

    static final String EXTRA_FILE_AUTHORITY = "file_authority";
    static final String EXTRA_DEFAULT_ICON_TINT_COLOR = "default_icon_tint_color";

    private static final String KEY_AWAITING_RESULT = "awaiting_result";
    private static final String KEY_SELECTED_POSITION = "selected_position";

    private boolean mWaitingForActivityResult;

    private FooterButton mDoneButton;
    private AvatarAdapter mAdapter;

    private AvatarPhotoController mAvatarPhotoController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dayNightEnabled = ThemeHelper.isSetupWizardDayNightEnabled(this);
        ThemeResolver themeResolver =
                new ThemeResolver.Builder(ThemeResolver.getDefault())
                        .setDefaultTheme(ThemeHelper.getSuwDefaultTheme(this))
                        .setUseDayNight(true)
                        .build();
        int themeResId = themeResolver.resolve("", /* suppressDayNight= */ !dayNightEnabled);
        setTheme(themeResId);
        ThemeHelper.trySetDynamicColor(this);
        setContentView(R.layout.avatar_picker);
        setUpButtons();

        RecyclerView recyclerView = findViewById(R.id.avatar_grid);
        mAdapter = new AvatarAdapter();
        recyclerView.setAdapter(mAdapter);
        recyclerView.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.avatar_picker_columns)));

        restoreState(savedInstanceState);

        mAvatarPhotoController = new AvatarPhotoController(
                new AvatarPhotoController.AvatarUiImpl(this),
                new AvatarPhotoController.ContextInjectorImpl(this, getFileAuthority()),
                mWaitingForActivityResult);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.onAdapterResume();
    }

    private void setUpButtons() {
        GlifLayout glifLayout = findViewById(R.id.glif_layout);
        FooterBarMixin mixin = glifLayout.getMixin(FooterBarMixin.class);

        FooterButton secondaryButton =
                new FooterButton.Builder(this)
                        .setText(getString(android.R.string.cancel))
                        .setListener(view -> cancel())
                        .build();

        mDoneButton =
                new FooterButton.Builder(this)
                        .setText(getString(R.string.done))
                        .setListener(view -> mAdapter.returnSelectionResult())
                        .build();
        mDoneButton.setEnabled(false);

        mixin.setSecondaryButton(secondaryButton);
        mixin.setPrimaryButton(mDoneButton);
    }

    private String getFileAuthority() {
        String authority = getIntent().getStringExtra(EXTRA_FILE_AUTHORITY);
        if (authority == null) {
            throw new IllegalStateException("File authority must be provided");
        }
        return authority;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mWaitingForActivityResult = false;
        mAvatarPhotoController.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_AWAITING_RESULT, mWaitingForActivityResult);
        outState.putInt(KEY_SELECTED_POSITION, mAdapter.mSelectedPosition);
        super.onSaveInstanceState(outState);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mWaitingForActivityResult = savedInstanceState.getBoolean(KEY_AWAITING_RESULT, false);
            mAdapter.mSelectedPosition =
                    savedInstanceState.getInt(KEY_SELECTED_POSITION, AvatarAdapter.NONE);
            mDoneButton.setEnabled(mAdapter.mSelectedPosition != AvatarAdapter.NONE);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        mWaitingForActivityResult = true;
        super.startActivityForResult(intent, requestCode);
    }

    void returnUriResult(Uri uri) {
        Intent resultData = new Intent();
        resultData.setData(uri);
        setResult(RESULT_OK, resultData);
        finish();
    }

    void returnColorResult(int color) {
        Intent resultData = new Intent();
        resultData.putExtra(EXTRA_DEFAULT_ICON_TINT_COLOR, color);
        setResult(RESULT_OK, resultData);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private class AvatarAdapter extends RecyclerView.Adapter<AvatarViewHolder> {

        private static final int NONE = -1;

        private final int mTakePhotoPosition;
        private final int mChoosePhotoPosition;
        private final int mPreselectedImageStartPosition;

        private final List<Drawable> mImageDrawables;
        private final List<String> mImageDescriptions;
        private final TypedArray mPreselectedImages;
        private final int[] mUserIconColors;
        private int mSelectedPosition = NONE;

        private int mLastSelectedPosition = NONE;

        AvatarAdapter() {
            final boolean canTakePhoto =
                    PhotoCapabilityUtils.canTakePhoto(AvatarPickerActivity.this);
            final boolean canChoosePhoto =
                    PhotoCapabilityUtils.canChoosePhoto(AvatarPickerActivity.this);
            mTakePhotoPosition = (canTakePhoto ? 0 : NONE);
            mChoosePhotoPosition = (canChoosePhoto ? (canTakePhoto ? 1 : 0) : NONE);
            mPreselectedImageStartPosition = (canTakePhoto ? 1 : 0) + (canChoosePhoto ? 1 : 0);

            mPreselectedImages = getResources().obtainTypedArray(R.array.avatar_images);
            mUserIconColors = UserIcons.getUserIconColors(getResources());
            mImageDrawables = buildDrawableList();
            mImageDescriptions = buildDescriptionsList();
        }

        @NonNull
        @Override
        public AvatarViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int position) {
            LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
            View itemView = layoutInflater.inflate(R.layout.avatar_item, parent, false);
            return new AvatarViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull AvatarViewHolder viewHolder, int position) {
            if (position == mTakePhotoPosition) {
                viewHolder.setDrawable(getDrawable(R.drawable.avatar_take_photo_circled));
                viewHolder.setContentDescription(getString(R.string.user_image_take_photo));

            } else if (position == mChoosePhotoPosition) {
                viewHolder.setDrawable(getDrawable(R.drawable.avatar_choose_photo_circled));
                viewHolder.setContentDescription(getString(R.string.user_image_choose_photo));

            } else if (position >= mPreselectedImageStartPosition) {
                int index = indexFromPosition(position);
                viewHolder.setSelected(position == mSelectedPosition);
                viewHolder.setDrawable(mImageDrawables.get(index));
                if (mImageDescriptions != null) {
                    viewHolder.setContentDescription(mImageDescriptions.get(index));
                } else {
                    viewHolder.setContentDescription(getString(
                            R.string.default_user_icon_description));
                }
            }
            viewHolder.setClickListener(view -> onViewHolderSelected(position));
        }

        private void onViewHolderSelected(int position) {
            if ((mTakePhotoPosition == position) && (mLastSelectedPosition != position)) {
                mAvatarPhotoController.takePhoto();
            } else if ((mChoosePhotoPosition == position) && (mLastSelectedPosition != position)) {
                mAvatarPhotoController.choosePhoto();
            } else {
                if (mSelectedPosition == position) {
                    deselect(position);
                } else {
                    select(position);
                }
            }
            mLastSelectedPosition = position;
        }

        public void onAdapterResume() {
            mLastSelectedPosition = NONE;
        }

        @Override
        public int getItemCount() {
            return mPreselectedImageStartPosition + mImageDrawables.size();
        }

        private List<Drawable> buildDrawableList() {
            List<Drawable> result = new ArrayList<>();

            for (int i = 0; i < mPreselectedImages.length(); i++) {
                Drawable drawable = mPreselectedImages.getDrawable(i);
                if (drawable instanceof BitmapDrawable) {
                    result.add(circularDrawableFrom((BitmapDrawable) drawable));
                } else {
                    throw new IllegalStateException("Avatar drawables must be bitmaps");
                }
            }
            if (!result.isEmpty()) {
                return result;
            }

            // No preselected images. Use tinted default icon.
            for (int i = 0; i < mUserIconColors.length; i++) {
                result.add(UserIcons.getDefaultUserIconInColor(getResources(), mUserIconColors[i]));
            }
            return result;
        }

        private List<String> buildDescriptionsList() {
            if (mPreselectedImages.length() > 0) {
                return Arrays.asList(
                        getResources().getStringArray(R.array.avatar_image_descriptions));
            }

            return null;
        }

        private Drawable circularDrawableFrom(BitmapDrawable drawable) {
            Bitmap bitmap = drawable.getBitmap();

            RoundedBitmapDrawable roundedBitmapDrawable =
                    RoundedBitmapDrawableFactory.create(getResources(), bitmap);
            roundedBitmapDrawable.setCircular(true);

            return roundedBitmapDrawable;
        }

        private int indexFromPosition(int position) {
            return position - mPreselectedImageStartPosition;
        }

        private void select(int position) {
            final int oldSelection = mSelectedPosition;
            mSelectedPosition = position;
            notifyItemChanged(position);
            if (oldSelection != NONE) {
                notifyItemChanged(oldSelection);
            } else {
                mDoneButton.setEnabled(true);
            }
        }

        private void deselect(int position) {
            mSelectedPosition = NONE;
            notifyItemChanged(position);
            mDoneButton.setEnabled(false);
        }

        private void returnSelectionResult() {
            int index = indexFromPosition(mSelectedPosition);
            if (mPreselectedImages.length() > 0) {
                int resourceId = mPreselectedImages.getResourceId(index, -1);
                if (resourceId == -1) {
                    throw new IllegalStateException("Preselected avatar images must be resources.");
                }
                returnUriResult(uriForResourceId(resourceId));
            } else {
                returnColorResult(
                        mUserIconColors[index]);
            }
        }

        private Uri uriForResourceId(int resourceId) {
            return new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(getResources().getResourcePackageName(resourceId))
                    .appendPath(getResources().getResourceTypeName(resourceId))
                    .appendPath(getResources().getResourceEntryName(resourceId))
                    .build();
        }
    }

    private static class AvatarViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mImageView;

        AvatarViewHolder(View view) {
            super(view);
            mImageView = view.findViewById(R.id.avatar_image);
        }

        public void setDrawable(Drawable drawable) {
            mImageView.setImageDrawable(drawable);
        }

        public void setContentDescription(String desc) {
            mImageView.setContentDescription(desc);
        }

        public void setClickListener(View.OnClickListener listener) {
            mImageView.setOnClickListener(listener);
        }

        public void setSelected(boolean selected) {
            mImageView.setSelected(selected);
        }
    }
}
