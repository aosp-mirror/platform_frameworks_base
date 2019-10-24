/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;

/**
 * Single media player for carousel in QSPanel
 */
public class QSMediaPlayer {

    private static final String TAG = "QSMediaPlayer";

    private Context mContext;
    private LinearLayout mMediaNotifView;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mWidth;
    private int mHeight;

    /**
     *
     * @param context
     * @param parent
     * @param width
     * @param height
     */
    public QSMediaPlayer(Context context, ViewGroup parent, int width, int height) {
        mContext = context;
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mMediaNotifView = (LinearLayout) inflater.inflate(R.layout.qs_media_panel, parent, false);

        mWidth = width;
        mHeight = height;
    }

    public View getView() {
        return mMediaNotifView;
    }

    /**
     *
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param notif
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor, int bgColor,
            View actionsContainer, Notification notif) {
        Log.d(TAG, "got media session: " + token);
        mToken = token;
        mController = new MediaController(mContext, token);
        MediaMetadata mMediaMetadata = mController.getMetadata();

        if (mMediaMetadata == null) {
            Log.e(TAG, "Media metadata was null");
            return;
        }

        Notification.Builder builder = Notification.Builder.recoverBuilder(mContext, notif);

        // Album art
        addAlbumArtBackground(mMediaMetadata, bgColor, mWidth, mHeight);

        // Reuse notification header instead of reimplementing everything
        RemoteViews headerRemoteView = builder.makeNotificationHeader();
        LinearLayout headerView = mMediaNotifView.findViewById(R.id.header);
        View result = headerRemoteView.apply(mContext, headerView);
        result.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 75);
        result.setLayoutParams(lp);
        headerView.removeAllViews();
        headerView.addView(result);

        View seamless = headerView.findViewById(com.android.internal.R.id.media_seamless);
        seamless.setVisibility(View.VISIBLE);

        // App icon
        ImageView appIcon = headerView.findViewById(com.android.internal.R.id.icon);
        Drawable iconDrawable = icon.loadDrawable(mContext);
        iconDrawable.setTint(iconColor);
        appIcon.setImageDrawable(iconDrawable);

        // App title
        TextView appName = headerView.findViewById(com.android.internal.R.id.app_name_text);
        String appNameString = builder.loadHeaderAppName();
        appName.setText(appNameString);
        appName.setTextColor(iconColor);

        // Action
        mMediaNotifView.setOnClickListener(v -> {
            try {
                notif.contentIntent.send();
                // Also close shade
                mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Pending intent was canceled");
                e.printStackTrace();
            }
        });

        // Separator
        TextView separator = headerView.findViewById(com.android.internal.R.id.header_text_divider);
        separator.setTextColor(iconColor);

        // Album name
        TextView albumName = headerView.findViewById(com.android.internal.R.id.header_text);
        String albumString = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        if (TextUtils.isEmpty(albumString)) {
            albumName.setVisibility(View.GONE);
            separator.setVisibility(View.GONE);
        } else {
            albumName.setText(albumString);
            albumName.setTextColor(iconColor);
            albumName.setVisibility(View.VISIBLE);
            separator.setVisibility(View.VISIBLE);
        }

        // Transfer chip
        View transferBackgroundView = headerView.findViewById(
                com.android.internal.R.id.media_seamless);
        LinearLayout viewLayout = (LinearLayout) transferBackgroundView;
        RippleDrawable bkgDrawable = (RippleDrawable) viewLayout.getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, iconColor);
        rect.setColor(bgColor);
        ImageView transferIcon = headerView.findViewById(
                com.android.internal.R.id.media_seamless_image);
        transferIcon.setBackgroundColor(bgColor);
        transferIcon.setImageTintList(ColorStateList.valueOf(iconColor));
        TextView transferText = headerView.findViewById(
                com.android.internal.R.id.media_seamless_text);
        transferText.setTextColor(iconColor);

        ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
        transferBackgroundView.setOnClickListener(v -> {
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT);
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        });

        // Artist name
        TextView artistText = mMediaNotifView.findViewById(R.id.header_title);
        String artistName = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        artistText.setText(artistName);
        artistText.setTextColor(iconColor);

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_text);
        String songName = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        titleText.setText(songName);
        titleText.setTextColor(iconColor);

        // Media controls
        LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
        final int[] actionIds = {
                R.id.action0,
                R.id.action1,
                R.id.action2,
                R.id.action3,
                R.id.action4
        };
        final int[] notifActionIds = {
                com.android.internal.R.id.action0,
                com.android.internal.R.id.action1,
                com.android.internal.R.id.action2,
                com.android.internal.R.id.action3,
                com.android.internal.R.id.action4
        };
        for (int i = 0; i < parentActionsLayout.getChildCount() && i < actionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
            ImageButton thatBtn = parentActionsLayout.findViewById(notifActionIds[i]);
            if (thatBtn == null || thatBtn.getDrawable() == null) {
                thisBtn.setVisibility(View.GONE);
                continue;
            }

            Drawable thatIcon = thatBtn.getDrawable();
            thisBtn.setImageDrawable(thatIcon.mutate());
            thisBtn.setVisibility(View.VISIBLE);
            thisBtn.setOnClickListener(v -> {
                Log.d(TAG, "clicking on other button");
                thatBtn.performClick();
            });
        }
    }

    public MediaSession.Token getMediaSessionToken() {
        return mToken;
    }

    public String getMediaPlayerPackage() {
        return mController.getPackageName();
    }

    /**
     * Check whether the media controlled by this player is currently playing
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying() {
        if (mController == null) {
            return false;
        }

        PlaybackState state = mController.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    private void addAlbumArtBackground(MediaMetadata metadata, int bgColor, int width, int height) {
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (albumArt != null) {

            Bitmap original = albumArt.copy(Bitmap.Config.ARGB_8888, true);
            Bitmap scaled = scaleBitmap(original, width, height);
            Canvas canvas = new Canvas(scaled);

            // Add translucent layer over album art to improve contrast
            Paint p = new Paint();
            p.setStyle(Paint.Style.FILL);
            p.setColor(bgColor);
            p.setAlpha(200);
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), p);

            RoundedBitmapDrawable roundedDrawable = RoundedBitmapDrawableFactory.create(
                    mContext.getResources(), scaled);
            roundedDrawable.setCornerRadius(20);

            mMediaNotifView.setBackground(roundedDrawable);
        } else {
            Log.e(TAG, "No album art available");
        }
    }

    private Bitmap scaleBitmap(Bitmap original, int width, int height) {
        Bitmap cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cropped);

        float scale = (float) cropped.getWidth() / (float) original.getWidth();
        float dy = (cropped.getHeight() - original.getHeight() * scale) / 2.0f;
        Matrix transformation = new Matrix();
        transformation.postTranslate(0, dy);
        transformation.preScale(scale, scale);

        Paint paint = new Paint();
        paint.setFilterBitmap(true);
        canvas.drawBitmap(original, transformation, paint);

        return cropped;
    }
}
