/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.tests.appwidgethost;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

public class AppWidgetHostActivity extends Activity
{
    static final String TAG = "AppWidgetHostActivity";

    static final int DISCOVER_APPWIDGET_REQUEST = 1;
    static final int CONFIGURE_APPWIDGET_REQUEST = 2;
    static final int HOST_ID = 1234;

    static final String PENDING_APPWIDGET_ID = "pending_appwidget";
    
    AppWidgetManager mAppWidgetManager;
    AppWidgetContainerView mAppWidgetContainer;

    public AppWidgetHostActivity() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mAppWidgetManager = AppWidgetManager.getInstance(this);

        setContentView(R.layout.appwidget_host);

        mHost = new AppWidgetHost(this, HOST_ID) {
                protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
                    return new MyAppWidgetView(appWidgetId);
                }
            };


        findViewById(R.id.add_appwidget).setOnClickListener(mOnClickListener);
        mAppWidgetContainer = (AppWidgetContainerView)findViewById(R.id.appwidget_container);

        if (false) {
            if (false) {
                mHost.deleteHost();
            } else {
                AppWidgetHost.deleteAllHosts();
            }
        }
    }

    View.OnClickListener mOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            discoverAppWidget(DISCOVER_APPWIDGET_REQUEST);
        }
    };

    void discoverAppWidget(int requestCode) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mHost.allocateAppWidgetId());
        startActivityForResult(intent, requestCode);
    }

    void configureAppWidget(int requestCode, int appWidgetId, ComponentName configure) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
        intent.setComponent(configure);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        SharedPreferences.Editor prefs = getPreferences(0).edit();
        prefs.putInt(PENDING_APPWIDGET_ID, appWidgetId);
        prefs.commit();
        startActivityForResult(intent, requestCode);
    }

    void handleAppWidgetPickResult(int resultCode, Intent intent) {
        // BEGIN_INCLUDE(getExtra_EXTRA_APPWIDGET_ID)
        Bundle extras = intent.getExtras();
        int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
        // END_INCLUDE(getExtra_EXTRA_APPWIDGET_ID)
        if (resultCode == RESULT_OK) {
            AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);

            if (appWidget.configure != null) {
                // configure the AppWidget if we should
                configureAppWidget(CONFIGURE_APPWIDGET_REQUEST, appWidgetId, appWidget.configure);
            } else {
                // just add it as is
                addAppWidgetView(appWidgetId, appWidget);
            }
        } else {
            mHost.deleteAppWidgetId(appWidgetId);
        }
    }

    void handleAppWidgetConfigureResult(int resultCode, Intent data) {
        int appWidgetId = getPreferences(0).getInt(PENDING_APPWIDGET_ID, -1);
        Log.d(TAG, "resultCode=" + resultCode + " appWidgetId=" + appWidgetId);
        if (appWidgetId < 0) {
            Log.w(TAG, "was no preference for PENDING_APPWIDGET_ID");
            return;
        }
        if (resultCode == RESULT_OK) {
            AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
            addAppWidgetView(appWidgetId, appWidget);
        } else {
            mHost.deleteAppWidgetId(appWidgetId);
        }
    }

    void addAppWidgetView(int appWidgetId, AppWidgetProviderInfo appWidget) {
        // Inflate the AppWidget's RemoteViews
        AppWidgetHostView view = mHost.createView(this, appWidgetId, appWidget);

        // Add it to the list
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mAppWidgetContainer.addView(view, layoutParams);

        registerForContextMenu(view);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case DISCOVER_APPWIDGET_REQUEST:
            handleAppWidgetPickResult(resultCode, data);
            break;
        case CONFIGURE_APPWIDGET_REQUEST:
            handleAppWidgetConfigureResult(resultCode, data);
        }
    }

    protected void onStart() {
        super.onStart();
        mHost.startListening();
    }

    protected void onStop() {
        super.onStop();
        mHost.stopListening();
    }

    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        menu.add(ContextMenu.NONE, R.string.delete_appwidget, ContextMenu.NONE,
                R.string.delete_appwidget);
    }

    public boolean onContextItemSelected(MenuItem item) {
        MyAppWidgetView view = (MyAppWidgetView)item.getMenuInfo();
        switch (item.getItemId()) {
        case R.string.delete_appwidget:
            Log.d(TAG, "delete! " + view.appWidgetId);
            mAppWidgetContainer.removeView(view);
            mHost.deleteAppWidgetId(view.appWidgetId);
            break;
        }

        return true;
    }

    class MyAppWidgetView extends AppWidgetHostView implements ContextMenu.ContextMenuInfo {
        int appWidgetId;

        MyAppWidgetView(int appWidgetId) {
            super(AppWidgetHostActivity.this);
            this.appWidgetId = appWidgetId;
        }

        public ContextMenu.ContextMenuInfo getContextMenuInfo() {
            return this;
        }
    }

    AppWidgetHost mHost;
}


