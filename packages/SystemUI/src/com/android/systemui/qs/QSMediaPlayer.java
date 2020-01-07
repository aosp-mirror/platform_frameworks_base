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
import android.view.KeyEvent;
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

import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;

import java.util.List;

/**
 * Single media player for carousel in QSPanel
 */
public class QSMediaPlayer {

    private static final String TAG = "QSMediaPlayer";

    private Context mContext;
    private LinearLayout mMediaNotifView;
    private View mSeamless;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mWidth;
    private int mHeight;
    private int mForegroundColor;
    private int mBackgroundColor;
    private ComponentName mRecvComponent;
    private QSPanel mParent;

    private MediaController.Callback mSessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "session destroyed");
            mController.unregisterCallback(mSessionCallback);

            // Hide all the old buttons
            final int[] actionIds = {
                    R.id.action0,
                    R.id.action1,
                    R.id.action2,
                    R.id.action3,
                    R.id.action4
            };
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
            btn.setImageDrawable(mContext.getResources().getDrawable(R.drawable.lb_ic_play));
            btn.setImageTintList(ColorStateList.valueOf(mForegroundColor));
            btn.setVisibility(View.VISIBLE);

            // Add long-click option to remove the player
            ViewGroup mMediaCarousel = (ViewGroup) mMediaNotifView.getParent();
            mMediaNotifView.setOnLongClickListener(v -> {
                // Replace player view with delete/cancel view
                v.setVisibility(View.GONE);

                View options = LayoutInflater.from(mContext).inflate(
                        R.layout.qs_media_panel_options, null, false);
                ImageButton btnDelete = options.findViewById(R.id.remove);
                btnDelete.setOnClickListener(b -> {
                    mMediaCarousel.removeView(options);
                    mParent.removeMediaPlayer(QSMediaPlayer.this);
                });
                ImageButton btnCancel = options.findViewById(R.id.cancel);
                btnCancel.setOnClickListener(b -> {
                    mMediaCarousel.removeView(options);
                    v.setVisibility(View.VISIBLE);
                });

                int pos = mMediaCarousel.indexOfChild(v);
                mMediaCarousel.addView(options, pos, v.getLayoutParams());
                return true; // consumed click
            });
        }
    };

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
     * Create or update the player view for the given media session
     * @param parent the parent QSPanel
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param notif reference to original notification
     * @param device current playback device
     */
    public void setMediaSession(QSPanel parent, MediaSession.Token token, Icon icon, int iconColor,
            int bgColor, View actionsContainer, Notification notif, MediaDevice device) {
        mParent = parent;
        mToken = token;
        mForegroundColor = iconColor;
        mBackgroundColor = bgColor;
        mController = new MediaController(mContext, token);

        // Try to find a receiver for the media button that matches this app
        PackageManager pm = mContext.getPackageManager();
        Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
        List<ResolveInfo> info = pm.queryBroadcastReceiversAsUser(it, 0, mContext.getUser());
        if (info != null) {
            for (ResolveInfo inf : info) {
                if (inf.activityInfo.packageName.equals(mController.getPackageName())) {
                    mRecvComponent = inf.getComponentInfo().getComponentName();
                }
            }
        }

        // reset in case we had previously restarted the stream
        mMediaNotifView.setOnLongClickListener(null);
        mController.registerCallback(mSessionCallback);
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
        mSeamless = headerView.findViewById(com.android.internal.R.id.media_seamless);
        mSeamless.setVisibility(View.VISIBLE);
        updateChip(device);
        ActivityStarter mActivityStarter = Dependency.get(ActivityStarter.class);
        mSeamless.setOnClickListener(v -> {
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

        int i = 0;
        for (; i < parentActionsLayout.getChildCount() && i < actionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
            ImageButton thatBtn = parentActionsLayout.findViewById(notifActionIds[i]);
            if (thatBtn == null || thatBtn.getDrawable() == null
                    || thatBtn.getVisibility() != View.VISIBLE) {
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

        // Hide any unused buttons
        for (; i < actionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
            thisBtn.setVisibility(View.GONE);
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
        float radius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
        if (albumArt != null) {
            Log.d(TAG, "updating album art");
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

    protected void updateChip(MediaDevice device) {
        if (mSeamless == null) {
            return;
        }
        ColorStateList fgTintList = ColorStateList.valueOf(mForegroundColor);

        // Update the outline color
        LinearLayout viewLayout = (LinearLayout) mSeamless;
        RippleDrawable bkgDrawable = (RippleDrawable) viewLayout.getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, mForegroundColor);
        rect.setColor(mBackgroundColor);

        ImageView iconView = mSeamless.findViewById(com.android.internal.R.id.media_seamless_image);
        TextView deviceName = mSeamless.findViewById(com.android.internal.R.id.media_seamless_text);
        deviceName.setTextColor(fgTintList);

        if (device != null) {
            Drawable icon = device.getIcon();
            iconView.setVisibility(View.VISIBLE);
            iconView.setImageTintList(fgTintList);

            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(mBackgroundColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(device.getName());
        } else {
            // Reset to default
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }
    }
}
