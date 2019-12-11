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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.systemui.R;

import java.util.List;

/**
 * QQS mini media player
 */
public class QuickQSMediaPlayer {

    private static final String TAG = "QQSMediaPlayer";

    private Context mContext;
    private LinearLayout mMediaNotifView;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mBackgroundColor;
    private int mForegroundColor;
    private ComponentName mRecvComponent;

    private MediaController.Callback mSessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "session destroyed");
            mController.unregisterCallback(mSessionCallback);

            // Hide all the old buttons
            final int[] actionIds = {R.id.action0, R.id.action1, R.id.action2};
            for (int i = 0; i < actionIds.length; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
                if (thisBtn != null) {
                    thisBtn.setVisibility(View.GONE);
                }
            }

            // Add a restart button
            ImageButton btn = mMediaNotifView.findViewById(actionIds[0]);
            btn.setOnClickListener(v -> {
                Log.d(TAG, "Attempting to restart session");
                // Send a media button event to previously found receiver
                if (mRecvComponent != null) {
                    Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    intent.setComponent(mRecvComponent);
                    int keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                    intent.putExtra(
                            Intent.EXTRA_KEY_EVENT,
                            new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                    mContext.sendBroadcast(intent);
                } else {
                    Log.d(TAG, "No receiver to restart");
                    // If we don't have a receiver, try relaunching the activity instead
                    try {
                        mController.getSessionActivity().send();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "Pending intent was canceled");
                        e.printStackTrace();
                    }
                }
            });
            btn.setImageDrawable(mContext.getResources().getDrawable(R.drawable.lb_ic_replay));
            btn.setImageTintList(ColorStateList.valueOf(mForegroundColor));
            btn.setVisibility(View.VISIBLE);
        }
    };

    /**
     *
     * @param context
     * @param parent
     */
    public QuickQSMediaPlayer(Context context, ViewGroup parent) {
        mContext = context;
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mMediaNotifView = (LinearLayout) inflater.inflate(R.layout.qqs_media_panel, parent, false);
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
     * @param actionsToShow indices of which actions to display in the mini player
     *                      (max 3: Notification.MediaStyle.MAX_MEDIA_BUTTONS_IN_COMPACT)
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor, int bgColor,
            View actionsContainer, int[] actionsToShow) {
        Log.d(TAG, "Setting media session: " + token);
        mToken = token;
        mForegroundColor = iconColor;
        mBackgroundColor = bgColor;
        mController = new MediaController(mContext, token);
        MediaMetadata mMediaMetadata = mController.getMetadata();

        // Try to find a receiver for the media button that matches this app
        PackageManager pm = mContext.getPackageManager();
        Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
        List<ResolveInfo> info = pm.queryBroadcastReceiversAsUser(it, 0, mContext.getUser());
        if (info != null) {
            for (ResolveInfo inf : info) {
                if (inf.activityInfo.packageName.equals(mController.getPackageName())) {
                    Log.d(TAG, "Found receiver for package: " + inf);
                    mRecvComponent = inf.getComponentInfo().getComponentName();
                }
            }
        }
        mController.registerCallback(mSessionCallback);

        if (mMediaMetadata == null) {
            Log.e(TAG, "Media metadata was null");
            return;
        }

        // Album art
        addAlbumArtBackground(mMediaMetadata, mBackgroundColor);

        // App icon
        ImageView appIcon = mMediaNotifView.findViewById(R.id.icon);
        Drawable iconDrawable = icon.loadDrawable(mContext);
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

        // Artist name
        TextView appText = mMediaNotifView.findViewById(R.id.header_title);
        String artistName = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        appText.setText(artistName);
        appText.setTextColor(mForegroundColor);

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_text);
        String songName = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        titleText.setText(songName);
        titleText.setTextColor(mForegroundColor);

        // Buttons we can display
        final int[] actionIds = {R.id.action0, R.id.action1, R.id.action2};

        // Existing buttons in the notification
        LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
        final int[] notifActionIds = {
                com.android.internal.R.id.action0,
                com.android.internal.R.id.action1,
                com.android.internal.R.id.action2,
                com.android.internal.R.id.action3,
                com.android.internal.R.id.action4
        };

        int i = 0;
        if (actionsToShow != null) {
            int maxButtons = Math.min(actionsToShow.length, parentActionsLayout.getChildCount());
            maxButtons = Math.min(maxButtons, actionIds.length);
            for (; i < maxButtons; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
                int thatId = notifActionIds[actionsToShow[i]];
                ImageButton thatBtn = parentActionsLayout.findViewById(thatId);
                if (thatBtn == null || thatBtn.getDrawable() == null
                        || thatBtn.getVisibility() != View.VISIBLE) {
                    thisBtn.setVisibility(View.GONE);
                    continue;
                }

                Drawable thatIcon = thatBtn.getDrawable();
                thisBtn.setImageDrawable(thatIcon.mutate());
                thisBtn.setVisibility(View.VISIBLE);
                thisBtn.setOnClickListener(v -> {
                    thatBtn.performClick();
                });
            }
        }

        // Hide any unused buttons
        for (; i < actionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
            thisBtn.setVisibility(View.GONE);
        }
    }

    public MediaSession.Token getMediaSessionToken() {
        return mToken;
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

    /**
     * Check whether this player has an attached media session.
     * @return whether there is a controller with a current media session.
     */
    public boolean hasMediaSession() {
        return mController != null && mController.getPlaybackState() != null;
    }

    private void addAlbumArtBackground(MediaMetadata metadata, int bgColor) {
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        float radius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
        if (albumArt != null) {
            Rect bounds = new Rect();
            mMediaNotifView.getBoundsOnScreen(bounds);
            int width = bounds.width();
            int height = bounds.height();

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
            roundedDrawable.setCornerRadius(radius);

            mMediaNotifView.setBackground(roundedDrawable);
        } else {
            Log.e(TAG, "No album art available");
            GradientDrawable rect = new GradientDrawable();
            rect.setCornerRadius(radius);
            rect.setColor(bgColor);
            mMediaNotifView.setBackground(rect);
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
