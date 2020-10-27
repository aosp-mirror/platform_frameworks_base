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

package com.android.systemui.people.widget;

import android.app.INotificationManager;
import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.android.systemui.R;
import com.android.systemui.people.PeopleSpaceTileView;
import com.android.systemui.people.PeopleSpaceUtils;

import java.util.ArrayList;
import java.util.List;

/** People Space Widget RemoteViewsFactory class. */
public class PeopleSpaceWidgetRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
    private static final String TAG = "PeopleSpaceWRVFactory";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private IPeopleManager mPeopleManager;
    private INotificationManager mNotificationManager;
    private PackageManager mPackageManager;
    private LauncherApps mLauncherApps;
    private List<ShortcutInfo> mShortcutInfos = new ArrayList<>();
    private Context mContext;

    public PeopleSpaceWidgetRemoteViewsFactory(Context context, Intent intent) {
        this.mContext = context;
    }

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate called");
        mNotificationManager =
                INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mPackageManager = mContext.getPackageManager();
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
            mShortcutInfos =
                    PeopleSpaceUtils.getShortcutInfos(
                            mContext, mNotificationManager, mPeopleManager);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve conversations", e);
        }
    }

    @Override
    public void onDataSetChanged() {
        if (DEBUG) Log.d(TAG, "onDataSetChanged called");
        setTileViewsWithPriorityConversations();
    }

    @Override
    public void onDestroy() {
        mShortcutInfos.clear();
    }

    @Override
    public int getCount() {
        return mShortcutInfos.size();
    }

    @Override
    public RemoteViews getViewAt(int i) {
        if (DEBUG) Log.d(TAG, "getViewAt called, index: " + i);

        RemoteViews personView =
                new RemoteViews(mContext.getPackageName(), R.layout.people_space_widget_item);
        try {
            ShortcutInfo shortcutInfo = mShortcutInfos.get(i);
            int userId = UserHandle.getUserHandleForUid(
                    shortcutInfo.getUserId()).getIdentifier();
            String pkg = shortcutInfo.getPackage();
            long lastInteraction = mPeopleManager.getLastInteraction(
                    pkg, userId,
                    shortcutInfo.getId());

            String status = lastInteraction != 0L ? mContext.getString(
                    R.string.last_interaction_status,
                    PeopleSpaceUtils.getLastInteractionString(
                            lastInteraction)) : mContext.getString(R.string.basic_status);

            personView.setTextViewText(R.id.status, status);
            personView.setTextViewText(R.id.name, shortcutInfo.getLabel().toString());

            personView.setImageViewBitmap(
                    R.id.package_icon,
                    PeopleSpaceUtils.convertDrawableToBitmap(
                            mPackageManager.getApplicationIcon(pkg)
                    )
            );
            personView.setImageViewBitmap(
                    R.id.person_icon,
                    PeopleSpaceUtils.convertDrawableToBitmap(
                            mLauncherApps.getShortcutIconDrawable(shortcutInfo, 0)
                    )
            );

            Intent fillInIntent = new Intent();
            fillInIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_SHORTCUT_INFO, shortcutInfo);
            personView.setOnClickFillInIntent(R.id.item, fillInIntent);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve shortcut information", e);
        }
        return personView;
    }

    @Override
    public RemoteViews getLoadingView() {
        return null;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }
}
