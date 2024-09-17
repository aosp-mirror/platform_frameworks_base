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

import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Base dialog for media output UI */
public abstract class MediaOutputBaseDialog extends SystemUIDialog
        implements MediaSwitchingController.Callback, Window.Callback {

    private static final String TAG = "MediaOutputDialog";
    private static final String EMPTY_TITLE = " ";
    private static final String PREF_NAME = "MediaOutputDialog";
    private static final String PREF_IS_LE_BROADCAST_FIRST_LAUNCH = "PrefIsLeBroadcastFirstLaunch";
    private static final boolean DEBUG = true;
    private static final int HANDLE_BROADCAST_FAILED_DELAY = 3000;

    protected final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private final RecyclerView.LayoutManager mLayoutManager;

    final Context mContext;
    final MediaSwitchingController mMediaSwitchingController;
    final BroadcastSender mBroadcastSender;

    /**
     * Signals whether the dialog should NOT show app-related metadata.
     *
     * <p>A metadata-less dialog hides the title, subtitle, and app icon in the header.
     */
    private final boolean mIncludePlaybackAndAppMetadata;

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
    private LinearLayout mMediaMetadataSectionLayout;
    private Button mDoneButton;
    private Button mStopButton;
    private Button mAppButton;
    private int mListMaxHeight;
    private int mItemHeight;
    private int mListPaddingTop;
    private WallpaperColors mWallpaperColors;
    private boolean mShouldLaunchLeBroadcastDialog;
    private boolean mIsLeBroadcastCallbackRegistered;
    private boolean mDismissing;

    MediaOutputBaseAdapter mAdapter;

    protected Executor mExecutor;

    private final ViewTreeObserver.OnGlobalLayoutListener mDeviceListLayoutListener = () -> {
        ViewGroup.LayoutParams params = mDeviceListLayout.getLayoutParams();
        int totalItemsHeight = mAdapter.getItemCount() * mItemHeight
                + mListPaddingTop;
        int correctHeight = Math.min(totalItemsHeight, mListMaxHeight);
        // Set max height for list
        if (correctHeight != params.height) {
            params.height = correctHeight;
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
            mMediaSwitchingController.setRefreshing(false);
            mMediaSwitchingController.refreshDataSetIfNeeded();
        }
    }

    public MediaOutputBaseDialog(
            Context context,
            BroadcastSender broadcastSender,
            MediaSwitchingController mediaSwitchingController,
            boolean includePlaybackAndAppMetadata) {
        super(context, R.style.Theme_SystemUI_Dialog_Media);

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mBroadcastSender = broadcastSender;
        mMediaSwitchingController = mediaSwitchingController;
        mLayoutManager = new LayoutManagerWrapper(mContext);
        mListMaxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_max_height);
        mItemHeight = context.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_item_height);
        mListPaddingTop = mContext.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_padding_top);
        mExecutor = Executors.newSingleThreadExecutor();
        mIncludePlaybackAndAppMetadata = includePlaybackAndAppMetadata;
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
        window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);

        mHeaderTitle = mDialogView.requireViewById(R.id.header_title);
        mHeaderSubtitle = mDialogView.requireViewById(R.id.header_subtitle);
        mHeaderIcon = mDialogView.requireViewById(R.id.header_icon);
        mDevicesRecyclerView = mDialogView.requireViewById(R.id.list_result);
        mMediaMetadataSectionLayout = mDialogView.requireViewById(R.id.media_metadata_section);
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
        mLayoutManager.setAutoMeasureEnabled(true);
        mDevicesRecyclerView.setLayoutManager(mLayoutManager);
        mDevicesRecyclerView.setAdapter(mAdapter);
        mDevicesRecyclerView.setHasFixedSize(false);
        // Init bottom buttons
        mDoneButton.setOnClickListener(v -> dismiss());
        mStopButton.setOnClickListener(v -> onStopButtonClick());
        mAppButton.setOnClickListener(mMediaSwitchingController::tryToLaunchMediaApplication);
        mMediaMetadataSectionLayout.setOnClickListener(
                mMediaSwitchingController::tryToLaunchMediaApplication);

        mDismissing = false;
    }

    @Override
    public void dismiss() {
        // TODO(287191450): remove this once expensive binder calls are removed from refresh().
        // Due to these binder calls on the UI thread, calling refresh() during dismissal causes
        // significant frame drops for the dismissal animation. Since the dialog is going away
        // anyway, we use this state to turn refresh() into a no-op.
        mDismissing = true;
        super.dismiss();
    }

    @Override
    public void start() {
        mMediaSwitchingController.start(this);
        if (isBroadcastSupported() && !mIsLeBroadcastCallbackRegistered) {
            mMediaSwitchingController.registerLeBroadcastServiceCallback(
                    mExecutor, mBroadcastCallback);
            mIsLeBroadcastCallbackRegistered = true;
        }
    }

    @Override
    public void stop() {
        // unregister broadcast callback should only depend on profile and registered flag
        // rather than remote device or broadcast state
        // otherwise it might have risks of leaking registered callback handle
        if (mMediaSwitchingController.isBroadcastSupported() && mIsLeBroadcastCallbackRegistered) {
            mMediaSwitchingController.unregisterLeBroadcastServiceCallback(mBroadcastCallback);
            mIsLeBroadcastCallbackRegistered = false;
        }
        mMediaSwitchingController.stop();
    }

    @VisibleForTesting
    void refresh() {
        refresh(false);
    }

    void refresh(boolean deviceSetChanged) {
        // TODO(287191450): remove binder calls in this method from the UI thread.
        // If the dialog is going away or is already refreshing, do nothing.
        if (mDismissing || mMediaSwitchingController.isRefreshing()) {
            return;
        }
        mMediaSwitchingController.setRefreshing(true);
        // Update header icon
        final int iconRes = getHeaderIconRes();
        final IconCompat headerIcon = getHeaderIcon();
        final IconCompat appSourceIcon = getAppSourceIcon();
        boolean colorSetUpdated = false;
        mCastAppLayout.setVisibility(
                mMediaSwitchingController.shouldShowLaunchSection() ? View.VISIBLE : View.GONE);
        if (iconRes != 0) {
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageResource(iconRes);
        } else if (headerIcon != null) {
            Icon icon = headerIcon.toIcon(mContext);
            if (icon.getType() != Icon.TYPE_BITMAP && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP) {
                // icon doesn't support getBitmap, use default value for color scheme
                updateButtonBackgroundColorFilter();
                updateDialogBackgroundColor();
            } else {
                Configuration config = mContext.getResources().getConfiguration();
                int currentNightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                boolean isDarkThemeOn = currentNightMode == Configuration.UI_MODE_NIGHT_YES;
                WallpaperColors wallpaperColors = WallpaperColors.fromBitmap(icon.getBitmap());
                colorSetUpdated = !wallpaperColors.equals(mWallpaperColors);
                if (colorSetUpdated) {
                    mAdapter.updateColorScheme(wallpaperColors, isDarkThemeOn);
                    updateButtonBackgroundColorFilter();
                    updateDialogBackgroundColor();
                }
            }
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageIcon(icon);
        } else {
            updateButtonBackgroundColorFilter();
            updateDialogBackgroundColor();
            mHeaderIcon.setVisibility(View.GONE);
        }

        if (!mIncludePlaybackAndAppMetadata) {
            mAppResourceIcon.setVisibility(View.GONE);
        } else if (appSourceIcon != null) {
            Icon appIcon = appSourceIcon.toIcon(mContext);
            mAppResourceIcon.setColorFilter(mMediaSwitchingController.getColorItemContent());
            mAppResourceIcon.setImageIcon(appIcon);
        } else {
            Drawable appIconDrawable = mMediaSwitchingController.getAppSourceIconFromPackage();
            if (appIconDrawable != null) {
                mAppResourceIcon.setImageDrawable(appIconDrawable);
            } else {
                mAppResourceIcon.setVisibility(View.GONE);
            }
        }
        if (mHeaderIcon.getVisibility() == View.VISIBLE) {
            final int size = getHeaderIconSize();
            final int padding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.media_output_dialog_header_icon_padding);
            mHeaderIcon.setLayoutParams(new LinearLayout.LayoutParams(size + padding, size));
        }
        mAppButton.setText(mMediaSwitchingController.getAppSourceName());

        if (!mIncludePlaybackAndAppMetadata) {
            mHeaderTitle.setVisibility(View.GONE);
            mHeaderSubtitle.setVisibility(View.GONE);
        } else {
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
        }

        // Show when remote media session is available or
        //      when the device supports BT LE audio + media is playing
        mStopButton.setVisibility(getStopButtonVisibility());
        mStopButton.setEnabled(true);
        mStopButton.setText(getStopButtonText());
        mStopButton.setOnClickListener(v -> onStopButtonClick());

        mBroadcastIcon.setVisibility(getBroadcastIconVisibility());
        mBroadcastIcon.setOnClickListener(v -> onBroadcastIconClick());
        if (!mAdapter.isDragging()) {
            int currentActivePosition = mAdapter.getCurrentActivePosition();
            if (!colorSetUpdated && !deviceSetChanged && currentActivePosition >= 0
                    && currentActivePosition < mAdapter.getItemCount()) {
                mAdapter.notifyItemChanged(currentActivePosition);
            } else {
                mAdapter.updateItems();
            }
        } else {
            mMediaSwitchingController.setRefreshing(false);
            mMediaSwitchingController.refreshDataSetIfNeeded();
        }
    }

    private void updateButtonBackgroundColorFilter() {
        ColorFilter buttonColorFilter =
                new PorterDuffColorFilter(
                        mMediaSwitchingController.getColorButtonBackground(),
                        PorterDuff.Mode.SRC_IN);
        mDoneButton.getBackground().setColorFilter(buttonColorFilter);
        mStopButton.getBackground().setColorFilter(buttonColorFilter);
        mDoneButton.setTextColor(mMediaSwitchingController.getColorPositiveButtonText());
    }

    private void updateDialogBackgroundColor() {
        getDialogView()
                .getBackground()
                .setTint(mMediaSwitchingController.getColorDialogBackground());
        mDeviceListLayout.setBackgroundColor(mMediaSwitchingController.getColorDialogBackground());
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
        if (!mMediaSwitchingController.startBluetoothLeBroadcast()) {
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

            mMediaSwitchingController.launchLeBroadcastNotifyDialog(
                    mDialogView,
                    mBroadcastSender,
                    MediaSwitchingController.BroadcastNotifyDialog.ACTION_FIRST_LAUNCH,
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
        mMediaSwitchingController.launchMediaOutputBroadcastDialog(mDialogView, mBroadcastSender);
        refresh();
    }

    protected void stopLeBroadcast() {
        mStopButton.setEnabled(false);
        if (!mMediaSwitchingController.stopBluetoothLeBroadcast()) {
            // If the system can't execute "broadcast stop", then UI does refresh.
            mMainThreadHandler.post(() -> refresh());
        }
    }

    abstract IconCompat getAppSourceIcon();

    abstract int getHeaderIconRes();

    abstract IconCompat getHeaderIcon();

    abstract int getHeaderIconSize();

    abstract CharSequence getHeaderText();

    abstract CharSequence getHeaderSubtitle();

    abstract int getStopButtonVisibility();

    public CharSequence getStopButtonText() {
        return mContext.getText(R.string.keyboard_key_media_stop);
    }

    public void onStopButtonClick() {
        mMediaSwitchingController.releaseSession();
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
        mBroadcastSender.closeSystemDialogs();
    }

    void onHeaderIconClick() {
    }

    View getDialogView() {
        return mDialogView;
    }
}
