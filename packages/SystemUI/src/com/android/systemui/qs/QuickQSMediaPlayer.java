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
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.media.MediaControlPanel;

import java.util.concurrent.Executor;

/**
 * QQS mini media player
 */
public class QuickQSMediaPlayer extends MediaControlPanel {

    private static final String TAG = "QQSMediaPlayer";

    // Button IDs for QS controls
    private static final int[] QQS_ACTION_IDS = {R.id.action0, R.id.action1, R.id.action2};

    /**
     * Initialize mini media player for QQS
     * @param context
     * @param parent
     * @param foregroundExecutor
     * @param backgroundExecutor
     */
    public QuickQSMediaPlayer(Context context, ViewGroup parent, Executor foregroundExecutor,
            Executor backgroundExecutor) {
        super(context, parent, null, R.layout.qqs_media_panel, QQS_ACTION_IDS,
                foregroundExecutor, backgroundExecutor);
    }

    /**
     * Update media panel view for the given media session
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param actionsToShow indices of which actions to display in the mini player
     *                      (max 3: Notification.MediaStyle.MAX_MEDIA_BUTTONS_IN_COMPACT)
     * @param contentIntent Intent to send when user taps on the view
     * @param key original notification's key
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor, int bgColor,
            View actionsContainer, int[] actionsToShow, PendingIntent contentIntent, String key) {
        // Only update if this is a different session and currently playing
        String oldPackage = "";
        if (getController() != null) {
            oldPackage = getController().getPackageName();
        }
        MediaController controller = new MediaController(getContext(), token);
        MediaSession.Token currentToken = getMediaSessionToken();
        boolean samePlayer = currentToken != null
                && currentToken.equals(token)
                && oldPackage.equals(controller.getPackageName());
        if (getController() != null && !samePlayer && !isPlaying(controller)) {
            return;
        }

        super.setMediaSession(token, icon, iconColor, bgColor, contentIntent, null, key);

        LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
        int i = 0;
        if (actionsToShow != null) {
            int maxButtons = Math.min(actionsToShow.length, parentActionsLayout.getChildCount());
            maxButtons = Math.min(maxButtons, QQS_ACTION_IDS.length);
            for (; i < maxButtons; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(QQS_ACTION_IDS[i]);
                int thatId = NOTIF_ACTION_IDS[actionsToShow[i]];
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
        for (; i < QQS_ACTION_IDS.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(QQS_ACTION_IDS[i]);
            thisBtn.setVisibility(View.GONE);
        }
    }
}
