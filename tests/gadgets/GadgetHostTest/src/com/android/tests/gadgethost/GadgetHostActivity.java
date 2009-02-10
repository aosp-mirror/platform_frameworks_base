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

package com.android.tests.gadgethost;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.gadget.GadgetHost;
import android.gadget.GadgetHostView;
import android.gadget.GadgetInfo;
import android.gadget.GadgetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

public class GadgetHostActivity extends Activity
{
    static final String TAG = "GadgetHostActivity";

    static final int DISCOVER_GADGET_REQUEST = 1;
    static final int CONFIGURE_GADGET_REQUEST = 2;
    static final int HOST_ID = 1234;

    static final String PENDING_GADGET_ID = "pending_gadget";
    
    GadgetManager mGadgetManager;
    GadgetContainerView mGadgetContainer;

    public GadgetHostActivity() {
        mGadgetManager = GadgetManager.getInstance(this);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.gadget_host);

        findViewById(R.id.add_gadget).setOnClickListener(mOnClickListener);
        mGadgetContainer = (GadgetContainerView)findViewById(R.id.gadget_container);

        if (false) {
            if (false) {
                mHost.deleteHost();
            } else {
                GadgetHost.deleteAllHosts();
            }
        }
    }

    View.OnClickListener mOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            discoverGadget(DISCOVER_GADGET_REQUEST);
        }
    };

    void discoverGadget(int requestCode) {
        Intent intent = new Intent(GadgetManager.GADGET_PICK_ACTION);
        intent.putExtra(GadgetManager.EXTRA_HOST_ID, HOST_ID);
        intent.putExtra(GadgetManager.EXTRA_GADGET_ID, mHost.allocateGadgetId());
        startActivityForResult(intent, requestCode);
    }

    void configureGadget(int requestCode, int gadgetId, ComponentName configure) {
        Intent intent = new Intent(GadgetManager.GADGET_CONFIGURE_ACTION);
        intent.setComponent(configure);
        intent.putExtra(GadgetManager.EXTRA_GADGET_ID, gadgetId);
        SharedPreferences.Editor prefs = getPreferences(0).edit();
        prefs.putInt(PENDING_GADGET_ID, gadgetId);
        prefs.commit();
        startActivityForResult(intent, requestCode);
    }

    void handleGadgetPickResult(int resultCode, Intent data) {
        Bundle extras = data.getExtras();
        int gadgetId = extras.getInt(GadgetManager.EXTRA_GADGET_ID);
        if (resultCode == RESULT_OK) {
            GadgetInfo gadget = mGadgetManager.getGadgetInfo(gadgetId);

            if (gadget.configure != null) {
                // configure the gadget if we should
                configureGadget(CONFIGURE_GADGET_REQUEST, gadgetId, gadget.configure);
            } else {
                // just add it as is
                addGadgetView(gadgetId, gadget);
            }
        } else {
            mHost.deleteGadgetId(gadgetId);
        }
    }

    void handleGadgetConfigureResult(int resultCode, Intent data) {
        int gadgetId = getPreferences(0).getInt(PENDING_GADGET_ID, -1);
        Log.d(TAG, "resultCode=" + resultCode + " gadgetId=" + gadgetId);
        if (gadgetId < 0) {
            Log.w(TAG, "was no preference for PENDING_GADGET_ID");
            return;
        }
        if (resultCode == RESULT_OK) {
            GadgetInfo gadget = mGadgetManager.getGadgetInfo(gadgetId);
            addGadgetView(gadgetId, gadget);
        } else {
            mHost.deleteGadgetId(gadgetId);
        }
    }

    void addGadgetView(int gadgetId, GadgetInfo gadget) {
        // Inflate the gadget's RemoteViews
        GadgetHostView view = mHost.createView(this, gadgetId, gadget);

        // Add it to the list
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mGadgetContainer.addView(view, layoutParams);

        registerForContextMenu(view);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case DISCOVER_GADGET_REQUEST:
            handleGadgetPickResult(resultCode, data);
            break;
        case CONFIGURE_GADGET_REQUEST:
            handleGadgetConfigureResult(resultCode, data);
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
        menu.add(ContextMenu.NONE, R.string.delete_gadget, ContextMenu.NONE,
                R.string.delete_gadget);
    }

    public boolean onContextItemSelected(MenuItem item) {
        MyGadgetView view = (MyGadgetView)item.getMenuInfo();
        switch (item.getItemId()) {
        case R.string.delete_gadget:
            Log.d(TAG, "delete! " + view.gadgetId);
            mGadgetContainer.removeView(view);
            mHost.deleteGadgetId(view.gadgetId);
            break;
        }

        return true;
    }

    class MyGadgetView extends GadgetHostView implements ContextMenu.ContextMenuInfo {
        int gadgetId;

        MyGadgetView(int gadgetId) {
            super(GadgetHostActivity.this);
            this.gadgetId = gadgetId;
        }

        public ContextMenu.ContextMenuInfo getContextMenuInfo() {
            return this;
        }
    }

    GadgetHost mHost = new GadgetHost(this, HOST_ID) {
        protected GadgetHostView onCreateView(Context context, int gadgetId, GadgetInfo gadget) {
            return new MyGadgetView(gadgetId);
        }
    };

}


