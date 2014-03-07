/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.android.systemui.recents.model.SpaceNode;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsView;
import com.android.systemui.R;

import java.util.ArrayList;


/* Activity */
public class RecentsActivity extends Activity {
    FrameLayout mContainerView;
    RecentsView mRecentsView;
    View mEmptyView;
    boolean mVisible;

    /** Updates the set of recent tasks */
    void updateRecentsTasks() {
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        SpaceNode root = loader.reload(this, Constants.Values.RecentsTaskLoader.PreloadFirstTasksCount);
        ArrayList<TaskStack> stacks = root.getStacks();
        if (!stacks.isEmpty()) {
            // XXX: We just replace the root every time for now, we will change this in the future
            mRecentsView.setBSP(root);
        }

        // Add the default no-recents layout
        if (stacks.size() == 1 && stacks.get(0).getTaskCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);

            // Dim the background even more
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.dimAmount = Constants.Values.Window.DarkBackgroundDim;
            getWindow().setAttributes(wlp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } else {
            mEmptyView.setVisibility(View.GONE);
        }
    }

    /** Dismisses recents if we are already visible and the intent is to toggle the recents view */
    boolean dismissRecentsIfVisible(Intent intent) {
        if ("com.android.systemui.recents.TOGGLE_RECENTS".equals(intent.getAction())) {
            if (mVisible) {
                if (!mRecentsView.launchFirstTask()) {
                    finish();
                }
                return true;
            }
        }
        return false;
    }

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Console.logDivider(Constants.DebugFlags.App.SystemUIHandshake);
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onCreate]",
                getIntent().getAction() + " visible: " + mVisible, Console.AnsiRed);

        // Initialize the loader and the configuration
        RecentsTaskLoader.initialize(this);
        RecentsConfiguration.reinitialize(this);

        // Dismiss recents if it is visible and we are toggling
        if (dismissRecentsIfVisible(getIntent())) return;

        // Set the background dim
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.dimAmount = Constants.Values.Window.BackgroundDim;
        getWindow().setAttributes(wlp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        // Create the view hierarchy
        mRecentsView = new RecentsView(this);
        mRecentsView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Create the empty view
        LayoutInflater inflater = LayoutInflater.from(this);
        mEmptyView = inflater.inflate(R.layout.recents_empty, mContainerView, false);

        mContainerView = new FrameLayout(this);
        mContainerView.addView(mRecentsView);
        mContainerView.addView(mEmptyView);
        setContentView(mContainerView);

        // Update the recent tasks
        updateRecentsTasks();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Console.logDivider(Constants.DebugFlags.App.SystemUIHandshake);
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onNewIntent]",
                intent.getAction() + " visible: " + mVisible, Console.AnsiRed);

        // Dismiss recents if it is visible and we are toggling
        if (dismissRecentsIfVisible(intent)) return;

        // Initialize the loader and the configuration
        RecentsTaskLoader.initialize(this);
        RecentsConfiguration.reinitialize(this);

        // Update the recent tasks
        updateRecentsTasks();
    }

    @Override
    protected void onStart() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onStart]", "",
                Console.AnsiRed);
        super.onStart();
        mVisible = true;
    }

    @Override
    protected void onResume() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onResume]", "",
                Console.AnsiRed);
        super.onResume();
    }

    @Override
    protected void onPause() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onPause]", "",
                Console.AnsiRed);
        super.onPause();

        // Stop the loader when we leave Recents
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.stopLoader();
    }

    @Override
    protected void onStop() {
        Console.log(Constants.DebugFlags.App.SystemUIHandshake, "[RecentsActivity|onStop]", "",
                Console.AnsiRed);
        super.onStop();
        mVisible = false;
    }

    @Override
    public void onBackPressed() {
        if (!mRecentsView.unfilterFilteredStacks()) {
            super.onBackPressed();
        }
    }
}
