/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.sample.rollbackapp;

import static android.app.PendingIntent.FLAG_MUTABLE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    List<Integer> mIdsToRollback = new ArrayList<>();
    Button mTriggerRollbackButton;
    RollbackManager mRollbackManager;
    static final String ROLLBACK_ID_EXTRA = "rollbackId";
    static final String ACTION_NAME = MainActivity.class.getName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ListView rollbackListView = findViewById(R.id.listView);
        mRollbackManager = getApplicationContext().getSystemService(RollbackManager.class);
        initTriggerRollbackButton();

        // Populate list of available rollbacks.
        List<RollbackInfo> availableRollbacks = mRollbackManager.getAvailableRollbacks();
        CustomAdapter adapter = new CustomAdapter(availableRollbacks);
        rollbackListView.setAdapter(adapter);

        // Register receiver for rollback status events.
        getApplicationContext().registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context,
                            Intent intent) {
                        int rollbackId = intent.getIntExtra(ROLLBACK_ID_EXTRA, -1);
                        int rollbackStatusCode = intent.getIntExtra(RollbackManager.EXTRA_STATUS,
                                RollbackManager.STATUS_FAILURE);
                        String rollbackStatus = "FAILED";
                        if (rollbackStatusCode == RollbackManager.STATUS_SUCCESS) {
                            rollbackStatus = "SUCCESS";
                            mTriggerRollbackButton.setClickable(false);
                        }
                        makeToast("Status for rollback ID " + rollbackId + " is " + rollbackStatus);
                    }}, new IntentFilter(ACTION_NAME), Context.RECEIVER_NOT_EXPORTED);
    }

    private void initTriggerRollbackButton() {
        mTriggerRollbackButton = findViewById(R.id.trigger_rollback_button);
        mTriggerRollbackButton.setClickable(false);
        mTriggerRollbackButton.setOnClickListener(v -> {
            // Commits all selected rollbacks. Rollback status events will be sent to our receiver.
            for (int i = 0; i < mIdsToRollback.size(); i++) {
                Intent intent = new Intent(ACTION_NAME);
                intent.putExtra(ROLLBACK_ID_EXTRA, mIdsToRollback.get(i));
                intent.setPackage(getApplicationContext().getPackageName());
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        getApplicationContext(), 0, intent, FLAG_MUTABLE);
                mRollbackManager.commitRollback(mIdsToRollback.get(i),
                        Collections.emptyList(),
                        pendingIntent.getIntentSender());
            }
        });
    }



    private void makeToast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
    }

    public class CustomAdapter extends BaseAdapter {
        List<RollbackInfo> mRollbackInfos;
        LayoutInflater mInflater = LayoutInflater.from(getApplicationContext());

        CustomAdapter(List<RollbackInfo> rollbackInfos) {
            mRollbackInfos = rollbackInfos;
        }

        @Override
        public int getCount() {
            return mRollbackInfos.size();
        }

        @Override
        public Object getItem(int position) {
            return mRollbackInfos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mRollbackInfos.get(position).getRollbackId();
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = mInflater.inflate(R.layout.listitem_rollbackinfo, null);
            }
            RollbackInfo rollbackInfo = mRollbackInfos.get(position);
            TextView rollbackIdView = view.findViewById(R.id.rollback_id);
            rollbackIdView.setText("Rollback ID " + rollbackInfo.getRollbackId());
            TextView rollbackPackagesTextView = view.findViewById(R.id.rollback_packages);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rollbackInfo.getPackages().size(); i++) {
                PackageRollbackInfo pkgInfo = rollbackInfo.getPackages().get(i);
                sb.append(pkgInfo.getPackageName() + ": "
                        + pkgInfo.getVersionRolledBackFrom().getLongVersionCode() + " -> "
                        + pkgInfo.getVersionRolledBackTo().getLongVersionCode() + ",");
            }
            sb.deleteCharAt(sb.length() - 1);
            rollbackPackagesTextView.setText(sb.toString());
            CheckBox checkbox = view.findViewById(R.id.checkbox);
            checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    mIdsToRollback.add(rollbackInfo.getRollbackId());
                } else {
                    mIdsToRollback.remove(Integer.valueOf(rollbackInfo.getRollbackId()));
                }
                mTriggerRollbackButton.setClickable(mIdsToRollback.size() > 0);
            });
            return view;
        }
    }
}
