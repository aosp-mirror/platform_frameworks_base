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

import java.util.List;

public class GadgetPickActivity extends ListActivity
{
    private static final String TAG = "GadgetPickActivity";

    GadgetManager mGadgetManager;
    List<GadgetInfo> mInstalled;
    
    public GadgetPickActivity() {
        mGadgetManager = GadgetManager.getInstance(this);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Bundle extras = getIntent().getExtras();

        List<GadgetInfo> installed = mGadgetManager.getInstalledProviders();
        mInstalled = installed;
        final int N = installed.size();
        String[] labels = new String[N];
        for (int i=0; i<N; i++) {
            labels[i] = installed.get(i).provider.getClassName();
        }

        setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id)
    {
        int gadgetId = mGadgetManager.allocateGadgetId(getCallingPackage());
        mGadgetManager.bindGadgetId(gadgetId, mInstalled.get(position).provider);

        Intent result = new Intent();
        result.putExtra(GadgetManager.EXTRA_GADGET_ID, gadgetId);

        setResult(RESULT_OK, result);
        finish();
    }
}

