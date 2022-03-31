/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import android.app.WallpaperColors;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.drawable.IconCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.statusbar.phone.SystemUIDialog;

/**
 * Base dialog for media output UI
 */
public abstract class MediaOutputBaseDialog extends SystemUIDialog implements
        MediaOutputController.Callback, Window.Callback {

    private static final String TAG = "MediaOutputDialog";
    private static final String EMPTY_TITLE = " ";
    private static final String PREF_NAME = "MediaOutputDialog";
    private static final String PREF_IS_LE_BROADCAST_FIRST_LAUNCH = "PrefIsLeBroadcastFirstLaunch";

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private final RecyclerView.LayoutManager mLayoutManager;

    final Context mContext;
    final MediaOutputController mMediaOutputController;
    final BroadcastSender mBroadcastSender;

    @VisibleForTesting
    View mDialogView;
    private TextView mHeaderTitle;
    private TextView mHeaderSubtitle;
    private ImageView mHeaderIcon;
    private ImageView mAppResourceIcon;
    private RecyclerView mDevicesRecyclerView;
    private LinearLayout mDeviceListLayout;
    private LinearLayout mCastAppLayout;
    private Button mDoneButton;
    private Button mStopButton;
    private Button mAppButton;
    private int mListMaxHeight;
    private WallpaperColors mWallpaperColors;

    MediaOutputBaseAdapter mAdapter;

    private final ViewTreeObserver.OnGlobalLayoutListener mDeviceListLayoutListener = () -> {
        // Set max height for list
        if (mDeviceListLayout.getHeight() > mListMaxHeight) {
            ViewGroup.LayoutParams params = mDeviceListLayout.getLayoutParams();
            params.height = mListMaxHeight;
            mDeviceListLayout.setLayoutParams(params);
        }
    };

    public MediaOutputBaseDialog(Context context, BroadcastSender broadcastSender,
            MediaOutputController mediaOutputController) {
        super(context, R.style.Theme_SystemUI_Dialog_Media);

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mBroadcastSender = broadcastSender;
        mMediaOutputController = mediaOutputController;
        mLayoutManager = new LinearLayoutManager(mContext);
        mListMaxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_max_height);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.media_output_dialog, null);
        final Window window = getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.CENTER;
        // Config insets to make sure the layout is above the navigation bar
        lp.setFitInsetsTypes(statusBars() | navigationBars());
        lp.setFitInsetsSides(WindowInsets.Side.all());
        lp.setFitInsetsIgnoringVisibility(true);
        window.setAttributes(lp);
        window.setContentView(mDialogView);
        // Sets window to a blank string to avoid talkback announce app label first when pop up,
        // which doesn't make sense.
        window.setTitle(EMPTY_TITLE);

        mHeaderTitle = mDialogView.requireViewById(R.id.header_title);
        mHeaderSubtitle = mDialogView.requireViewById(R.id.header_subtitle);
        mHeaderIcon = mDialogView.requireViewById(R.id.header_icon);
        mDevicesRecyclerView = mDialogView.requireViewById(R.id.list_result);
        mDeviceListLayout = mDialogView.requireViewById(R.id.device_list);
        mDoneButton = mDialogView.requireViewById(R.id.done);
        mStopButton = mDialogView.requireViewById(R.id.stop);
        mAppButton = mDialogView.requireViewById(R.id.launch_app_button);
        mAppResourceIcon = mDialogView.requireViewById(R.id.app_source_icon);
        mCastAppLayout = mDialogView.requireViewById(R.id.cast_app_section);

        mDeviceListLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                mDeviceListLayoutListener);
        // Init device list
        mDevicesRecyclerView.setLayoutManager(mLayoutManager);
        mDevicesRecyclerView.setAdapter(mAdapter);
        // Init header icon
        mHeaderIcon.setOnClickListener(v -> onHeaderIconClick());
        // Init bottom buttons
        mDoneButton.setOnClickListener(v -> dismiss());
        mStopButton.setOnClickListener(v -> {
            mMediaOutputController.releaseSession();
            dismiss();
        });
        mAppButton.setOnClickListener(v -> {
            mBroadcastSender.closeSystemDialogs();
            if (mMediaOutputController.getAppLaunchIntent() != null) {
                mContext.startActivity(mMediaOutputController.getAppLaunchIntent());
            }
            dismiss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        mMediaOutputController.start(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        mMediaOutputController.stop();
    }

    @VisibleForTesting
    void refresh() {
        refresh(false);
    }

    void refresh(boolean deviceSetChanged) {
        // Update header icon
        final int iconRes = getHeaderIconRes();
        final IconCompat iconCompat = getHeaderIcon();
        final Drawable appSourceDrawable = getAppSourceIcon();
        boolean colorSetUpdated = false;
        mCastAppLayout.setVisibility(
                mMediaOutputController.shouldShowLaunchSection()
                        ? View.VISIBLE : View.GONE);
        if (appSourceDrawable != null) {
            mAppResourceIcon.setImageDrawable(appSourceDrawable);
            mAppButton.setCompoundDrawablesWithIntrinsicBounds(resizeDrawable(appSourceDrawable,
                            mContext.getResources().getDimensionPixelSize(
                                    R.dimen.media_output_dialog_app_tier_icon_size
                            )),
                    null, null, null);
        } else {
            mAppResourceIcon.setVisibility(View.GONE);
        }
        if (iconRes != 0) {
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageResource(iconRes);
        } else if (iconCompat != null) {
            Icon icon = iconCompat.toIcon(mContext);
            Configuration config = mContext.getResources().getConfiguration();
            int currentNightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean isDarkThemeOn = currentNightMode == Configuration.UI_MODE_NIGHT_YES;
            WallpaperColors wallpaperColors = WallpaperColors.fromBitmap(icon.getBitmap());
            colorSetUpdated = !wallpaperColors.equals(mWallpaperColors);
            if (colorSetUpdated) {
                mAdapter.updateColorScheme(wallpaperColors, isDarkThemeOn);
                ColorFilter buttonColorFilter = new PorterDuffColorFilter(
                        mAdapter.getController().getColorButtonBackground(),
                        PorterDuff.Mode.SRC_IN);
                mDoneButton.getBackground().setColorFilter(buttonColorFilter);
                mStopButton.getBackground().setColorFilter(buttonColorFilter);
                mDoneButton.setTextColor(mAdapter.getController().getColorPositiveButtonText());
            }
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageIcon(icon);
        } else {
            mHeaderIcon.setVisibility(View.GONE);
        }
        if (mHeaderIcon.getVisibility() == View.VISIBLE) {
            final int size = getHeaderIconSize();
            final int padding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.media_output_dialog_header_icon_padding);
            mHeaderIcon.setLayoutParams(new LinearLayout.LayoutParams(size + padding, size));
        }
        mAppButton.setText(mMediaOutputController.getAppSourceName());
        // Update title and subtitle
        mHeaderTitle.setText(getHeaderText());
        final CharSequence subTitle = getHeaderSubtitle();
        if (TextUtils.isEmpty(subTitle)) {
            mHeaderSubtitle.setVisibility(View.GONE);
            mHeaderTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        } else {
            mHeaderSubtitle.setVisibility(View.VISIBLE);
            mHeaderSubtitle.setText(subTitle);
            mHeaderTitle.setGravity(Gravity.NO_GRAVITY);
        }
        if (!mAdapter.isDragging() && !mAdapter.isAnimating()) {
            int currentActivePosition = mAdapter.getCurrentActivePosition();
            if (!colorSetUpdated && !deviceSetChanged && currentActivePosition >= 0
                    && currentActivePosition < mAdapter.getItemCount()) {
                mAdapter.notifyItemChanged(currentActivePosition);
            } else {
                mAdapter.notifyDataSetChanged();
            }
        }
        // Show when remote media session is available
        mStopButton.setVisibility(getStopButtonVisibility());
        if (isBroadcastSupported() && mMediaOutputController.isPlaying()) {
            mStopButton.setText(R.string.media_output_broadcast);
            mStopButton.setOnClickListener(v -> {
                SharedPreferences sharedPref = mContext.getSharedPreferences(PREF_NAME,
                        Context.MODE_PRIVATE);

                if (sharedPref != null
                        && sharedPref.getBoolean(PREF_IS_LE_BROADCAST_FIRST_LAUNCH, true)) {
                    Log.d(TAG, "PREF_IS_LE_BROADCAST_FIRST_LAUNCH: true");

                    mMediaOutputController.launchLeBroadcastNotifyDialog(mDialogView,
                            mBroadcastSender,
                            MediaOutputController.BroadcastNotifyDialog.ACTION_FIRST_LAUNCH);
                    SharedPreferences.Editor editor = sharedPref.edit();
                    editor.putBoolean(PREF_IS_LE_BROADCAST_FIRST_LAUNCH, false);
                    editor.apply();
                } else {
                    mMediaOutputController.launchMediaOutputBroadcastDialog(mDialogView,
                            mBroadcastSender);
                }
            });
        } else {
            mStopButton.setOnClickListener(v -> {
                mMediaOutputController.releaseSession();
                dismiss();
            });
        }
    }

    private Drawable resizeDrawable(Drawable drawable, int size) {
        if (drawable == null) {
            return null;
        }
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        Bitmap.Config config = drawable.getOpacity() != PixelFormat.OPAQUE ? Bitmap.Config.ARGB_8888
                : Bitmap.Config.RGB_565;
        Bitmap bitmap = Bitmap.createBitmap(width, height, config);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return new BitmapDrawable(mContext.getResources(),
                Bitmap.createScaledBitmap(bitmap, size, size, false));
    }

    abstract Drawable getAppSourceIcon();

    abstract int getHeaderIconRes();

    abstract IconCompat getHeaderIcon();

    abstract int getHeaderIconSize();

    abstract CharSequence getHeaderText();

    abstract CharSequence getHeaderSubtitle();

    abstract int getStopButtonVisibility();

    public boolean isBroadcastSupported() {
        return false;
    }

    @Override
    public void onMediaChanged() {
        mMainThreadHandler.post(() -> refresh());
    }

    @Override
    public void onMediaStoppedOrPaused() {
        if (isShowing()) {
            dismiss();
        }
    }

    @Override
    public void onRouteChanged() {
        mMainThreadHandler.post(() -> refresh());
    }

    @Override
    public void onDeviceListChanged() {
        mMainThreadHandler.post(() -> refresh(true));
    }

    @Override
    public void dismissDialog() {
        dismiss();
    }

    void onHeaderIconClick() {
    }

    View getDialogView() {
        return mDialogView;
    }
}
