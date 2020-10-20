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

package com.android.systemui.people;

import android.app.Activity;
import android.app.INotificationManager;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.os.Bundle;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.util.Log;
import android.view.ViewGroup;

import com.android.systemui.R;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shows the user their tiles for their priority People (go/live-status).
 */
public class PeopleSpaceActivity extends Activity {

    private static String sTAG = "PeopleSpaceActivity";

    private ViewGroup mPeopleSpaceLayout;
    private IPeopleManager mPeopleManager;
    private INotificationManager mNotificationManager;
    private PackageManager mPackageManager;
    private LauncherApps mLauncherApps;
    private Context mContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.people_space_activity);
        mPeopleSpaceLayout = findViewById(R.id.people_space_layout);
        mContext = getApplicationContext();
        mNotificationManager =
                INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mPackageManager = getPackageManager();
        mPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mLauncherApps = mContext.getSystemService(LauncherApps.class);
        setTileViewsWithPriorityConversations();
    }

    /**
     * Retrieves all priority conversations and sets a {@link PeopleSpaceTileView}s for each
     * priority conversation.
     */
    private void setTileViewsWithPriorityConversations() {
        try {
            boolean showAllConversations = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE) == 0;
            List<ConversationChannelWrapper> conversations =
                    mNotificationManager.getConversations(
                            !showAllConversations /* priority only */).getList();
            List<ShortcutInfo> shortcutInfos = conversations.stream().filter(
                    c -> shouldKeepConversation(c)).map(c -> c.getShortcutInfo()).collect(
                    Collectors.toList());
            if (showAllConversations) {
                List<ConversationChannel> recentConversations =
                        mPeopleManager.getRecentConversations().getList();
                List<ShortcutInfo> recentShortcuts = recentConversations.stream().map(
                        c -> c.getShortcutInfo()).collect(Collectors.toList());
                shortcutInfos.addAll(recentShortcuts);
            }
            for (ShortcutInfo conversation : shortcutInfos) {
                PeopleSpaceTileView tileView = new PeopleSpaceTileView(mContext,
                        mPeopleSpaceLayout,
                        conversation.getId());
                setTileView(tileView, conversation);
            }
        } catch (Exception e) {
            Log.e(sTAG, "Couldn't retrieve conversations", e);
        }
    }

    /** Sets {@code tileView} with the data in {@code conversation}. */
    private void setTileView(PeopleSpaceTileView tileView,
            ShortcutInfo shortcutInfo) {
        try {
            int userId = UserHandle.getUserHandleForUid(
                    shortcutInfo.getUserId()).getIdentifier();

            String pkg = shortcutInfo.getPackage();
            long lastInteraction = mPeopleManager.getLastInteraction(
                    pkg, userId,
                    shortcutInfo.getId());
            String status = lastInteraction != 0l ? mContext.getString(
                    R.string.last_interaction_status,
                    getLastInteractionString(
                            lastInteraction)) : mContext.getString(R.string.basic_status);
            tileView.setStatus(status);

            tileView.setName(shortcutInfo.getLabel().toString());
            tileView.setPackageIcon(mPackageManager.getApplicationIcon(pkg));
            tileView.setPersonIcon(mLauncherApps.getShortcutIconDrawable(shortcutInfo, 0));
            tileView.setOnClickListener(mLauncherApps, shortcutInfo);
        } catch (Exception e) {
            Log.e(sTAG, "Couldn't retrieve shortcut information", e);
        }
    }

    /** Returns a readable representation of {@code lastInteraction}. */
    private String getLastInteractionString(long lastInteraction) {
        long now = System.currentTimeMillis();
        Duration durationSinceLastInteraction = Duration.ofMillis(
                now - lastInteraction);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.WIDE);
        if (durationSinceLastInteraction.toDays() >= 1) {
            return
                    formatter
                            .formatMeasures(new Measure(durationSinceLastInteraction.toDays(),
                                    MeasureUnit.DAY));
        } else if (durationSinceLastInteraction.toHours() >= 1) {
            return formatter.formatMeasures(new Measure(durationSinceLastInteraction.toHours(),
                    MeasureUnit.HOUR));
        } else if (durationSinceLastInteraction.toMinutes() >= 1) {
            return formatter.formatMeasures(new Measure(durationSinceLastInteraction.toMinutes(),
                    MeasureUnit.MINUTE));
        } else {
            return formatter.formatMeasures(
                    new Measure(durationSinceLastInteraction.toMillis() / 1000,
                            MeasureUnit.SECOND));
        }
    }

    /**
     * Returns whether the {@code conversation} should be kept for display in the People Space.
     *
     * <p>A valid {@code conversation} must:
     *     <ul>
     *         <li>Have a non-null {@link ShortcutInfo}
     *         <li>Have an associated label in the {@link ShortcutInfo}
     *     </ul>
     * </li>
     */
    private boolean shouldKeepConversation(ConversationChannelWrapper conversation) {
        ShortcutInfo shortcutInfo = conversation.getShortcutInfo();
        return shortcutInfo != null && shortcutInfo.getLabel().length() != 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh tile views to sync new conversations.
        setTileViewsWithPriorityConversations();
    }
}
