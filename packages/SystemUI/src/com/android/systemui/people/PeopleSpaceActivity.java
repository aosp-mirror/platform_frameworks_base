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

import static android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID;
import static android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID;

import android.app.Activity;
import android.app.INotificationManager;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;

import androidx.preference.PreferenceManager;

import com.android.systemui.R;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;

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
    private AppWidgetManager mAppWidgetManager;
    private int mAppWidgetId;
    private boolean mShowSingleConversation;

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
        mAppWidgetManager = AppWidgetManager.getInstance(mContext);
        setTileViewsWithPriorityConversations();
        mAppWidgetId = getIntent().getIntExtra(EXTRA_APPWIDGET_ID,
                INVALID_APPWIDGET_ID);
        mShowSingleConversation = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;
        // Finish the configuration activity immediately if a widget is added for multiple
        // conversations. If the mAppWidgetId is INVALID, then the activity wasn't launched as a
        // widget configuration activity.
        if (!mShowSingleConversation && mAppWidgetId != INVALID_APPWIDGET_ID) {
            finishActivity();
        }
    }

    /**
     * Retrieves all priority conversations and sets a {@link PeopleSpaceTileView}s for each
     * priority conversation.
     */
    private void setTileViewsWithPriorityConversations() {
        try {
            List<Map.Entry<Long, PeopleSpaceTile>> tiles = PeopleSpaceUtils.getTiles(
                    mContext, mNotificationManager, mPeopleManager, mLauncherApps);
            for (Map.Entry<Long, PeopleSpaceTile> entry : tiles) {
                PeopleSpaceTile tile = entry.getValue();
                PeopleSpaceTileView tileView = new PeopleSpaceTileView(mContext, mPeopleSpaceLayout,
                        tile.getId());
                setTileView(tileView, tile, entry.getKey());
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve conversations", e);
        }
    }

    /** Sets {@code tileView} with the data in {@code conversation}. */
    private void setTileView(PeopleSpaceTileView tileView, PeopleSpaceTile tile,
            long lastInteraction) {
        try {
            String pkg = tile.getPackageName();
            String status =
                    PeopleSpaceUtils.getLastInteractionString(mContext, lastInteraction);
            tileView.setStatus(status);

            tileView.setName(tile.getUserName().toString());
            tileView.setPackageIcon(mPackageManager.getApplicationIcon(pkg));
            tileView.setPersonIcon(tile.getUserIcon());
            tileView.setOnClickListener(v -> storeWidgetConfiguration(tile));
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve shortcut information", e);
        }
    }

    /** Stores the user selected configuration for {@code mAppWidgetId}. */
    private void storeWidgetConfiguration(PeopleSpaceTile tile) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        if (PeopleSpaceUtils.DEBUG) {
            Log.d(TAG, "Put " + tile.getUserName() + "'s shortcut ID: "
                    + tile.getId() + " for widget ID: "
                    + mAppWidgetId);
        }
        editor.putString(String.valueOf(mAppWidgetId), tile.getId());
        editor.commit();
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(mContext);
        Bundle options = new Bundle();
        options.putParcelable(PeopleSpaceUtils.OPTIONS_PEOPLE_SPACE_TILE, tile);
        appWidgetManager.updateAppWidgetOptions(mAppWidgetId, options);
        int[] widgetIds = appWidgetManager.getAppWidgetIds(
                new ComponentName(mContext, PeopleSpaceWidgetProvider.class));
        // TODO: Populate new widget with existing conversation notification, if there is any.
        PeopleSpaceUtils.updateSingleConversationWidgets(mContext, widgetIds, mAppWidgetManager,
                mNotificationManager);
        finishActivity();
    }

    /** Finish activity with a successful widget configuration result. */
    private void finishActivity() {
        Intent resultValue = new Intent();
        resultValue.putExtra(EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_OK, resultValue);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh tile views to sync new conversations.
        setTileViewsWithPriorityConversations();
    }
}
