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

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.gadget.GadgetInfo;
import android.gadget.GadgetManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.util.Log;

public class GadgetPickActivity extends ListActivity
{
    private static final String TAG = "GadgetPickActivity";

    GadgetManager mGadgetManager;
    
    public GadgetPickActivity() {
        mGadgetManager = GadgetManager.getInstance(this);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Bundle extras = getIntent().getExtras();

        String[] labels = new String[10];
        for (int i=0; i<labels.length; i++) {
            labels[i] = "Gadget " + (i+1);
        }

        setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id)
    {
        Log.d(TAG, "Clicked item " + position);

        int gadgetId = mGadgetManager.allocateGadgetId(getCallingPackage());
        mGadgetManager.bindGadgetId(gadgetId, new ComponentName(
                    "com.android.gadgethost", "com.android.gadgethost.TestGadgetProvider"));

        Intent result = new Intent();
        result.putExtra(GadgetManager.EXTRA_GADGET_ID, gadgetId);

        setResult(RESULT_OK, result);
        finish();
    }
}

