/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tests.usagereporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class UsageReporterActivity extends ListActivity {

    private Activity mActivity;
    private final ArrayList<String> mTokens = new ArrayList();
    private final ArraySet<String> mActives = new ArraySet();
    private UsageStatsManager mUsageStatsManager;
    private Adapter mAdapter;
    private boolean mRestoreOnStart = false;
    private static Context sContext;

    /** Called with the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sContext = getApplicationContext();

        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        mAdapter = new Adapter();
        setListAdapter(mAdapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mActivity = this;


        if (mRestoreOnStart) {
            ArrayList<String> removed = null;
            for (String token : mActives) {
                try {
                    mUsageStatsManager.reportUsageStart(mActivity, token);
                } catch (Exception e) {
                    // Somthing went wrong, recover and move on
                    if (removed == null) {
                        removed = new ArrayList();
                    }
                    removed.add(token);
                }
            }
            if (removed != null) {
                for (String token : removed) {
                    mActives.remove(token);
                }
            }
        } else {
            mActives.clear();
        }
    }

    /**
     * Called when the activity is about to start interacting with the user.
     */
    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.notifyDataSetChanged();
    }


    /**
     * Called when your activity's options menu needs to be created.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }


    /**
     * Called when a menu item is selected.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_token:
                callAddToken();
                return true;
            case R.id.add_many_tokens:
                callAddManyTokens();
                return true;
            case R.id.stop_all:
                callStopAll();
                return true;
            case R.id.restore_on_start:
                mRestoreOnStart = !mRestoreOnStart;
                item.setChecked(mRestoreOnStart);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void callAddToken() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.token_query));
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(getString(R.string.default_token));
        builder.setView(input);

        builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String tokenNames = input.getText().toString().trim();
                if (TextUtils.isEmpty(tokenNames)) {
                    tokenNames = getString(R.string.default_token);
                }
                String[] tokens = tokenNames.split(",");
                for (String token : tokens) {
                    if (mTokens.contains(token)) continue;
                    mTokens.add(token);
                }
                mAdapter.notifyDataSetChanged();

            }
        });
        builder.setNegativeButton(getString(R.string.cancel),
                                  new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void callAddManyTokens() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.many_tokens_query));
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);

        builder.setPositiveButton(getString(R.string.ok),
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String val = input.getText().toString().trim();
                if (TextUtils.isEmpty(val)) return;
                int n = Integer.parseInt(val);
                for (int i = 0; i < n; i++) {
                    final String token = getString(R.string.default_token) + i;
                    if (mTokens.contains(token)) continue;
                    mTokens.add(token);
                }
                mAdapter.notifyDataSetChanged();

            }
        });
        builder.setNegativeButton(getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void callStopAll() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.stop_all_tokens_query));

        builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                for (String token : mActives) {
                    mUsageStatsManager.reportUsageStop(mActivity, token);
                }
                mActives.clear();
                mAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(getString(R.string.cancel),
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    /**
     * A call-back for when the user presses the back button.
     */
    OnClickListener mStartListener = new OnClickListener() {
        public void onClick(View v) {
            final View parent = (View) v.getParent();
            final String token = ((TextView) parent.findViewById(R.id.token)).getText().toString();
            try {
                mUsageStatsManager.reportUsageStart(mActivity, token);
            } catch (Exception e) {
                Toast.makeText(sContext, e.toString(), Toast.LENGTH_LONG).show();
            }
            parent.setBackgroundColor(getColor(R.color.active_color));
            mActives.add(token);
        }
    };

    /**
     * A call-back for when the user presses the clear button.
     */
    OnClickListener mStopListener = new OnClickListener() {
        public void onClick(View v) {
            final View parent = (View) v.getParent();

            final String token = ((TextView) parent.findViewById(R.id.token)).getText().toString();
            try {
                mUsageStatsManager.reportUsageStop(mActivity, token);
            } catch (Exception e) {
                Toast.makeText(sContext, e.toString(), Toast.LENGTH_LONG).show();
            }
            parent.setBackgroundColor(getColor(R.color.inactive_color));
            mActives.remove(token);
        }
    };


    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mTokens.size();
        }

        @Override
        public Object getItem(int position) {
            return mTokens.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(UsageReporterActivity.this)
                        .inflate(R.layout.row_item, parent, false);
                holder = new ViewHolder();
                holder.tokenName = (TextView) convertView.findViewById(R.id.token);

                holder.startButton = ((Button) convertView.findViewById(R.id.start));
                holder.startButton.setOnClickListener(mStartListener);
                holder.stopButton = ((Button) convertView.findViewById(R.id.stop));
                holder.stopButton.setOnClickListener(mStopListener);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final String token = mTokens.get(position);
            holder.tokenName.setText(mTokens.get(position));
            if (mActives.contains(token)) {
                convertView.setBackgroundColor(getColor(R.color.active_color));
            } else {
                convertView.setBackgroundColor(getColor(R.color.inactive_color));
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        public TextView tokenName;
        public Button startButton;
        public Button stopButton;
    }
}
