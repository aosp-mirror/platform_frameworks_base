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
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** People Tile Widget configuration activity that shows the user their conversation tiles. */
public class PeopleSpaceActivity extends Activity {

    private static final String TAG = "PeopleSpaceActivity";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private IPeopleManager mPeopleManager;
    private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    private INotificationManager mNotificationManager;
    private PackageManager mPackageManager;
    private LauncherApps mLauncherApps;
    private Context mContext;
    private NotificationEntryManager mNotificationEntryManager;
    private int mAppWidgetId;

    @Inject
    public PeopleSpaceActivity(NotificationEntryManager notificationEntryManager,
            PeopleSpaceWidgetManager peopleSpaceWidgetManager) {
        super();
        mNotificationEntryManager = notificationEntryManager;
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getApplicationContext();
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mPackageManager = getPackageManager();
        mPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mLauncherApps = mContext.getSystemService(LauncherApps.class);
        mAppWidgetId = getIntent().getIntExtra(EXTRA_APPWIDGET_ID,
                INVALID_APPWIDGET_ID);
        setResult(RESULT_CANCELED);
    }

    /** Builds the conversation selection activity. */
    private void buildActivity() {
        List<PeopleSpaceTile> priorityTiles = new ArrayList<>();
        List<PeopleSpaceTile> recentTiles = new ArrayList<>();
        try {
            priorityTiles = PeopleSpaceUtils.getPriorityTiles(mContext, mNotificationManager,
                    mPeopleManager, mLauncherApps, mNotificationEntryManager);
            recentTiles = PeopleSpaceUtils.getRecentTiles(mContext, mNotificationManager,
                    mPeopleManager, mLauncherApps, mNotificationEntryManager);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve conversations", e);
        }

        // If no conversations, render activity without conversations
        if (recentTiles.isEmpty() && priorityTiles.isEmpty()) {
            setContentView(R.layout.people_space_activity_no_conversations);

            // The Tile preview has colorBackground as its background. Change it so it's different
            // than the activity's background.
            LinearLayout item = findViewById(R.id.item);
            GradientDrawable shape = (GradientDrawable) item.getBackground();
            final TypedArray ta = mContext.obtainStyledAttributes(
                    new int[] {android.R.attr.colorBackgroundFloating});
            shape.setColor(ta.getColor(0, Color.WHITE));
            return;
        }

        setContentView(R.layout.people_space_activity);
        setTileViews(R.id.priority, R.id.priority_tiles, priorityTiles);
        setTileViews(R.id.recent, R.id.recent_tiles, recentTiles);
    }

    private ViewOutlineProvider mViewOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                    mContext.getResources().getDimension(R.dimen.people_space_widget_radius));
        }
    };

    /** Sets a {@link PeopleSpaceTileView}s for each conversation. */
    private void setTileViews(int viewId, int tilesId, List<PeopleSpaceTile> tiles) {
        if (tiles.isEmpty()) {
            LinearLayout view = findViewById(viewId);
            view.setVisibility(View.GONE);
            return;
        }

        ViewGroup layout = findViewById(tilesId);
        layout.setClipToOutline(true);
        layout.setOutlineProvider(mViewOutlineProvider);
        for (int i = 0; i < tiles.size(); ++i) {
            PeopleSpaceTile tile = tiles.get(i);
            PeopleSpaceTileView tileView = new PeopleSpaceTileView(mContext,
                    layout, tile.getId(), i == (tiles.size() - 1));
            setTileView(tileView, tile);
        }
    }

    /** Sets {@code tileView} with the data in {@code conversation}. */
    private void setTileView(PeopleSpaceTileView tileView, PeopleSpaceTile tile) {
        try {
            String pkg = tile.getPackageName();

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
        if (PeopleSpaceUtils.DEBUG) {
            if (DEBUG) {
                Log.d(TAG, "Put " + tile.getUserName() + "'s shortcut ID: "
                        + tile.getId() + " for widget ID: "
                        + mAppWidgetId);
            }
        }
        mPeopleSpaceWidgetManager.addNewWidget(mAppWidgetId, tile);
        finishActivity();
    }

    /** Finish activity with a successful widget configuration result. */
    private void finishActivity() {
        if (DEBUG) Log.d(TAG, "Widget added!");
        setActivityResult(RESULT_OK);
        finish();
    }

    /** Finish activity without choosing a widget. */
    public void dismissActivity(View v) {
        if (DEBUG) Log.d(TAG, "Activity dismissed with no widgets added!");
        finish();
    }

    private void setActivityResult(int result) {
        Intent resultValue = new Intent();
        resultValue.putExtra(EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(result, resultValue);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh tile views to sync new conversations.
        buildActivity();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }
}
