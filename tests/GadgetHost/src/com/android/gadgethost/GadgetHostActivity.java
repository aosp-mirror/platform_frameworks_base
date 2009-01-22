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

package com.android.gadgethost;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.gadget.GadgetHost;
import android.gadget.GadgetHostView;
import android.gadget.GadgetInfo;
import android.gadget.GadgetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.View;
import android.widget.LinearLayout;

public class GadgetHostActivity extends Activity
{
    static final String TAG = "GadgetHostActivity";

    static final int DISCOVER_GADGET_REQUEST = 1;

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
    }

    View.OnClickListener mOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            discoverGadget(DISCOVER_GADGET_REQUEST);
        }
    };

    void discoverGadget(int requestCode) {
        Intent intent = new Intent(GadgetManager.GADGET_PICK_ACTION);
        startActivityForResult(intent, requestCode);
    }

    void handleGadgetPickResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Bundle extras = data.getExtras();
            int gadgetId = extras.getInt(GadgetManager.EXTRA_GADGET_ID);
            GadgetInfo gadget = mGadgetManager.getGadgetInfo(gadgetId);

            if (gadget.configure != null) {
                // configure the gadget if we should

                // TODO: start the activity.  Watch for a cancel result.  If it returns
                // RESULT_CANCELED, then remove the gadget.
            } else {
                // just add it as is
                addGadgetView(gadgetId, gadget);
            }
        }
    }

    void addGadgetView(int gadgetId, GadgetInfo gadget) {
        // Inflate the gadget's RemoteViews
        GadgetHostView view = mHost.createView(this, gadgetId, gadget);

        // Add it to the list
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                65, // LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mGadgetContainer.addView(view, layoutParams);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case DISCOVER_GADGET_REQUEST:
            handleGadgetPickResult(resultCode, data);
            break;
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

    class MyGadgetView extends GadgetHostView {
        MyGadgetView() {
            super(GadgetHostActivity.this);
        }

        public void createContextMenu(ContextMenu menu) {
            menu.add("Delete");
        }
    }

    GadgetHost mHost = new GadgetHost(this, 0) {
        protected GadgetHostView onCreateView(Context context, int gadgetId, GadgetInfo gadget) {
            return new MyGadgetView();
        }
    };

}


