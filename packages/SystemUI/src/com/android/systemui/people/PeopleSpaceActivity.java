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
import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.ServiceManager;
import android.util.Log;
import android.view.ViewGroup;

import com.android.systemui.R;

import java.util.List;
import java.util.Map;

/**
 * Shows the user their tiles for their priority People (go/live-status).
 */
public class PeopleSpaceActivity extends Activity {

    private static final String TAG = "PeopleSpaceActivity";

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
        mNotificationManager = INotificationManager.Stub.asInterface(
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
            List<Map.Entry<Long, ShortcutInfo>> shortcutInfos = PeopleSpaceUtils.getShortcutInfos(
                    mContext, mNotificationManager, mPeopleManager);
            for (Map.Entry<Long, ShortcutInfo> entry : shortcutInfos) {
                ShortcutInfo shortcutInfo = entry.getValue();
                PeopleSpaceTileView tileView = new PeopleSpaceTileView(mContext, mPeopleSpaceLayout,
                        shortcutInfo.getId());
                setTileView(tileView, shortcutInfo, entry.getKey());
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve conversations", e);
        }
    }

    /** Sets {@code tileView} with the data in {@code conversation}. */
    private void setTileView(PeopleSpaceTileView tileView, ShortcutInfo shortcutInfo,
            long lastInteraction) {
        try {
            String pkg = shortcutInfo.getPackage();
            String status =
                    PeopleSpaceUtils.getLastInteractionString(mContext, lastInteraction);
            tileView.setStatus(status);

            tileView.setName(shortcutInfo.getLabel().toString());
            tileView.setPackageIcon(mPackageManager.getApplicationIcon(pkg));
            tileView.setPersonIcon(mLauncherApps.getShortcutIconDrawable(shortcutInfo, 0));
            tileView.setOnClickListener(mLauncherApps, shortcutInfo);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve shortcut information", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh tile views to sync new conversations.
        setTileViewsWithPriorityConversations();
    }
}
