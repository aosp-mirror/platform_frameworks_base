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

import android.annotation.NonNull;
import android.app.WallpaperColors;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastMetadata;
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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Base dialog for media output UI
 */
public abstract class MediaOutputBaseDialog extends SystemUIDialog implements
        MediaOutputController.Callback, Window.Callback {

    private static final String TAG = "MediaOutputDialog";
    private static final String EMPTY_TITLE = " ";
    private static final String PREF_NAME = "MediaOutputDialog";
    private static final String PREF_IS_LE_BROADCAST_FIRST_LAUNCH = "PrefIsLeBroadcastFirstLaunch";
    private static final boolean DEBUG = true;
    private static final int HANDLE_BROADCAST_FAILED_DELAY = 3000;

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
    private ImageView mBroadcastIcon;
    private RecyclerView mDevicesRecyclerView;
    private LinearLayout mDeviceListLayout;
    private LinearLayout mCastAppLayout;
    private Button mDoneButton;
    private Button mStopButton;
    private Button mAppButton;
    private int mListMaxHeight;
    private WallpaperColors mWallpaperColors;
    private Executor mExecutor;
    private boolean mShouldLaunchLeBroadcastDialog;

    MediaOutputBaseAdapter mAdapter;

    private final ViewTreeObserver.OnGlobalLayoutListener mDeviceListLayoutListener = () -> {
        // Set max height for list
        if (mDeviceListLayout.getHeight() > mListMaxHeight) {
            ViewGroup.LayoutParams params = mDeviceListLayout.getLayoutParams();
            params.height = mListMaxHeight;
            mDeviceListLayout.setLayoutParams(params);
        }
    };

    private final BluetoothLeBroadcast.Callback mBroadcastCallback =
            new BluetoothLeBroadcast.Callback() {
                @Override
                public void onBroadcastStarted(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastStarted(), reason = " + reason
                                + ", broadcastId = " + broadcastId);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastStarted());
                }

                @Override
                public void onBroadcastStartFailed(int reason) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastStartFailed(), reason = " + reason);
                    }
                    mMainThreadHandler.postDelayed(() -> handleLeBroadcastStartFailed(),
                            HANDLE_BROADCAST_FAILED_DELAY);
                }

                @Override
                public void onBroadcastMetadataChanged(int broadcastId,
                        @NonNull BluetoothLeBroadcastMetadata metadata) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastMetadataChanged(), broadcastId = " + broadcastId
                                + ", metadata = " + metadata);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastMetadataChanged());
                }

                @Override
                public void onBroadcastStopped(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastStopped(), reason = " + reason
                                + ", broadcastId = " + broadcastId);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastStopped());
                }

                @Override
                public void onBroadcastStopFailed(int reason) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastStopFailed(), reason = " + reason);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastStopFailed());
                }

                @Override
                public void onBroadcastUpdated(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastUpdated(), reason = " + reason
                                + ", broadcastId = " + broadcastId);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastUpdated());
                }

                @Override
                public void onBroadcastUpdateFailed(int reason, int broadcastId) {
                    if (DEBUG) {
                        Log.d(TAG, "onBroadcastUpdateFailed(), reason = " + reason
                                + ", broadcastId = " + broadcastId);
                    }
                    mMainThreadHandler.post(() -> handleLeBroadcastUpdateFailed());
                }

                @Override
                public void onPlaybackStarted(int reason, int broadcastId) {
                }

                @Override
                public void onPlaybackStopped(int reason, int broadcastId) {
                }
            };

    private class LayoutManagerWrapper extends LinearLayoutManager {
        LayoutManagerWrapper(Context context) {
            super(context);
        }

        @Override
        public void onLayoutCompleted(RecyclerView.State state) {
            super.onLayoutCompleted(state);
            mMediaOutputController.setRefreshing(false);
            mMediaOutputController.refreshDataSetIfNeeded();
        }
    }

    public MediaOutputBaseDialog(Context context, BroadcastSender broadcastSender,
            MediaOutputController mediaOutputController) {
        super(context, R.style.Theme_SystemUI_Dialog_Media);

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mBroadcastSender = broadcastSender;
        mMediaOutputController = mediaOutputController;
        mLayoutManager = new LayoutManagerWrapper(mContext);
        mListMaxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_max_height);
        mExecutor = Executors.newSingleThreadExecutor();
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
        window.setTitle(mContext.getString(R.string.media_output_dialog_accessibility_title));

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
        mBroadcastIcon = mDialogView.requireViewById(R.id.broadcast_icon);

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
        if(isBroadcastSupported()) {
            mMediaOutputController.registerLeBroadcastServiceCallBack(mExecutor,
                    mBroadcastCallback);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if(isBroadcastSupported()) {
            mMediaOutputController.unregisterLeBroadcastServiceCallBack(mBroadcastCallback);
        }
        mMediaOutputController.stop();
    }

    @VisibleForTesting
    void refresh() {
        refresh(false);
    }

    void refresh(boolean deviceSetChanged) {
        if (mMediaOutputController.isRefreshing()) {
            return;
        }
        mMediaOutputController.setRefreshing(true);
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
            if (icon.getType() != Icon.TYPE_BITMAP && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP) {
                // icon doesn't support getBitmap, use default value for color scheme
                updateButtonBackgroundColorFilter();
            } else {
                Configuration config = mContext.getResources().getConfiguration();
                int currentNightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                boolean isDarkThemeOn = currentNightMode == Configuration.UI_MODE_NIGHT_YES;
                WallpaperColors wallpaperColors = WallpaperColors.fromBitmap(icon.getBitmap());
                colorSetUpdated = !wallpaperColors.equals(mWallpaperColors);
                if (colorSetUpdated) {
                    mAdapter.updateColorScheme(wallpaperColors, isDarkThemeOn);
                    updateButtonBackgroundColorFilter();
                }
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
        if (!mAdapter.isDragging()) {
            int currentActivePosition = mAdapter.getCurrentActivePosition();
            if (!colorSetUpdated && !deviceSetChanged && currentActivePosition >= 0
                    && currentActivePosition < mAdapter.getItemCount()) {
                mAdapter.notifyItemChanged(currentActivePosition);
            } else {
                mAdapter.notifyDataSetChanged();
            }
        }
        // Show when remote media session is available or
        //      when the device supports BT LE audio + media is playing
        mStopButton.setVisibility(getStopButtonVisibility());
        mStopButton.setEnabled(true);
        mStopButton.setText(getStopButtonText());
        mStopButton.setOnClickListener(v -> onStopButtonClick());

        mBroadcastIcon.setVisibility(getBroadcastIconVisibility());
        mBroadcastIcon.setOnClickListener(v -> onBroadcastIconClick());
    }

    private void updateButtonBackgroundColorFilter() {
        ColorFilter buttonColorFilter = new PorterDuffColorFilter(
                mAdapter.getController().getColorButtonBackground(),
                PorterDuff.Mode.SRC_IN);
        mDoneButton.getBackground().setColorFilter(buttonColorFilter);
        mStopButton.getBackground().setColorFilter(buttonColorFilter);
        mDoneButton.setTextColor(mAdapter.getController().getColorPositiveButtonText());
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

    public void handleLeBroadcastStarted() {
        // Waiting for the onBroadcastMetadataChanged. The UI launchs the broadcast dialog when
        // the metadata is ready.
        mShouldLaunchLeBroadcastDialog = true;
    }

    public void handleLeBroadcastStartFailed() {
        mStopButton.setText(R.string.media_output_broadcast_start_failed);
        mStopButton.setEnabled(false);
        refresh();
    }

    public void handleLeBroadcastMetadataChanged() {
        if (mShouldLaunchLeBroadcastDialog) {
            startLeBroadcastDialog();
            mShouldLaunchLeBroadcastDialog = false;
        }
        refresh();
    }

    public void handleLeBroadcastStopped() {
        mShouldLaunchLeBroadcastDialog = false;
        refresh();
    }

    public void handleLeBroadcastStopFailed() {
        refresh();
    }

    public void handleLeBroadcastUpdated() {
        refresh();
    }

    public void handleLeBroadcastUpdateFailed() {
        refresh();
    }

    protected void startLeBroadcast() {
        mStopButton.setText(R.string.media_output_broadcast_starting);
        mStopButton.setEnabled(false);
        if (!mMediaOutputController.startBluetoothLeBroadcast()) {
            // If the system can't execute "broadcast start", then UI shows the error.
            handleLeBroadcastStartFailed();
        }
    }

    protected boolean startLeBroadcastDialogForFirstTime(){
        SharedPreferences sharedPref = mContext.getSharedPreferences(PREF_NAME,
                Context.MODE_PRIVATE);
        if (sharedPref != null
                && sharedPref.getBoolean(PREF_IS_LE_BROADCAST_FIRST_LAUNCH, true)) {
            Log.d(TAG, "PREF_IS_LE_BROADCAST_FIRST_LAUNCH: true");

            mMediaOutputController.launchLeBroadcastNotifyDialog(mDialogView,
                    mBroadcastSender,
                    MediaOutputController.BroadcastNotifyDialog.ACTION_FIRST_LAUNCH,
                    (d, w) -> {
                        startLeBroadcast();
                    });
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean(PREF_IS_LE_BROADCAST_FIRST_LAUNCH, false);
            editor.apply();
            return true;
        }
        return false;
    }

    protected void startLeBroadcastDialog() {
        mMediaOutputController.launchMediaOutputBroadcastDialog(mDialogView,
                mBroadcastSender);
        refresh();
    }

    protected void stopLeBroadcast() {
        mStopButton.setEnabled(false);
        if (!mMediaOutputController.stopBluetoothLeBroadcast()) {
            // If the system can't execute "broadcast stop", then UI does refresh.
            mMainThreadHandler.post(() -> refresh());
        }
    }

    abstract Drawable getAppSourceIcon();

    abstract int getHeaderIconRes();

    abstract IconCompat getHeaderIcon();

    abstract int getHeaderIconSize();

    abstract CharSequence getHeaderText();

    abstract CharSequence getHeaderSubtitle();

    abstract int getStopButtonVisibility();

    public CharSequence getStopButtonText() {
        return mContext.getText(R.string.media_output_dialog_button_stop_casting);
    }

    public void onStopButtonClick() {
        mMediaOutputController.releaseSession();
        dismiss();
    }

    public int getBroadcastIconVisibility() {
        return View.GONE;
    }

    public void onBroadcastIconClick() {
        // Do nothing.
    }

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
