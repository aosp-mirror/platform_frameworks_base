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

package com.android.internal.app;

import com.android.internal.R;

import android.app.Activity;
import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This activity is displayed when the system attempts to start an Intent for
 * which there is more than one matching activity, allowing the user to decide
 * which to go to.  It is not normally used directly by application developers.
 */
public class ResolverActivity extends AlertActivity implements 
        DialogInterface.OnClickListener, CheckBox.OnCheckedChangeListener {
    private ResolveListAdapter mAdapter;
    private CheckBox mAlwaysCheck;
    private TextView mClearDefaultHint;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        onCreate(savedInstanceState, new Intent(getIntent()),
                getResources().getText(com.android.internal.R.string.whichApplication),
                true);
    }
    
    protected void onCreate(Bundle savedInstanceState, Intent intent,
            CharSequence title, boolean alwaysUseOption) {
        super.onCreate(savedInstanceState);

        intent.setComponent(null);

        AlertController.AlertParams ap = mAlertParams;
        
        ap.mTitle = title;
        ap.mOnClickListener = this;
        
        if (alwaysUseOption) {
            LayoutInflater inflater = (LayoutInflater) getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            ap.mView = inflater.inflate(R.layout.always_use_checkbox, null);
            mAlwaysCheck = (CheckBox)ap.mView.findViewById(com.android.internal.R.id.alwaysUse);
            mAlwaysCheck.setOnCheckedChangeListener(this);
            mClearDefaultHint = (TextView)ap.mView.findViewById(
                                                        com.android.internal.R.id.clearDefaultHint);
            mClearDefaultHint.setVisibility(View.GONE);
        }
        mAdapter = new ResolveListAdapter(this, intent);
        if (mAdapter.getCount() > 1) {
            ap.mAdapter = mAdapter;
        } else if (mAdapter.getCount() == 1) {
            startActivity(mAdapter.intentForPosition(0));
            finish();
            return;
        } else {
            ap.mMessage = getResources().getText(com.android.internal.R.string.noApplications);
        }
        
        setupAlert();
    }
    
    public void onClick(DialogInterface dialog, int which) {
        ResolveInfo ri = mAdapter.resolveInfoForPosition(which);
        Intent intent = mAdapter.intentForPosition(which);

        if ((mAlwaysCheck != null) && mAlwaysCheck.isChecked()) {
            // Build a reasonable intent filter, based on what matched.
            IntentFilter filter = new IntentFilter();
            
            if (intent.getAction() != null) {
                filter.addAction(intent.getAction());
            }
            Set<String> categories = intent.getCategories();
            if (categories != null) {
                for (String cat : categories) {
                    filter.addCategory(cat);
                }
            }
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            
            int cat = ri.match&IntentFilter.MATCH_CATEGORY_MASK;
            Uri data = intent.getData();
            if (cat == IntentFilter.MATCH_CATEGORY_TYPE) {
                String mimeType = intent.resolveType(this);
                if (mimeType != null) {
                    try {
                        filter.addDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        Log.w("ResolverActivity", e);
                        filter = null;
                    }
                }
            } else if (data != null && data.getScheme() != null) {
                filter.addDataScheme(data.getScheme());
                
                // Look through the resolved filter to determine which part
                // of it matched the original Intent.
                Iterator<IntentFilter.AuthorityEntry> aIt = ri.filter.authoritiesIterator();
                if (aIt != null) {
                    while (aIt.hasNext()) {
                        IntentFilter.AuthorityEntry a = aIt.next();
                        if (a.match(data) >= 0) {
                            int port = a.getPort();
                            filter.addDataAuthority(a.getHost(),
                                    port >= 0 ? Integer.toString(port) : null);
                            break;
                        }
                    }
                }
                Iterator<PatternMatcher> pIt = ri.filter.pathsIterator();
                if (pIt != null) {
                    String path = data.getPath();
                    while (path != null && pIt.hasNext()) {
                        PatternMatcher p = pIt.next();
                        if (p.match(path)) {
                            filter.addDataPath(p.getPath(), p.getType());
                            break;
                        }
                    }
                }
            }
            
            if (filter != null) {
                final int N = mAdapter.mList.size();
                ComponentName[] set = new ComponentName[N];
                int bestMatch = 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo r = mAdapter.mList.get(i);
                    set[i] = new ComponentName(r.activityInfo.packageName,
                            r.activityInfo.name);
                    if (r.match > bestMatch) bestMatch = r.match;
                }
                getPackageManager().addPreferredActivity(filter, bestMatch, set,
                        intent.getComponent());
            }
        }
        
        if (intent != null) {
            startActivity(intent);
        }
        finish();
    }
    
    private final class ResolveListAdapter extends BaseAdapter {
        private final Intent mIntent;
        private final LayoutInflater mInflater;

        private List<ResolveInfo> mList;
        
        public ResolveListAdapter(Context context, Intent intent) {
            mIntent = new Intent(intent);
            mIntent.setComponent(null);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            PackageManager pm = context.getPackageManager();
            mList = pm.queryIntentActivities(
                    intent, PackageManager.MATCH_DEFAULT_ONLY
                    | (mAlwaysCheck != null ? PackageManager.GET_RESOLVED_FILTER : 0));
            if (mList != null) {
                int N = mList.size();
                if (N > 1) {
                    // Only display the first matches that are either of equal
                    // priority or have asked to be default options.
                    ResolveInfo r0 = mList.get(0);
                    for (int i=1; i<N; i++) {
                        ResolveInfo ri = mList.get(i);
                        if (Config.LOGV) Log.v(
                            "ResolveListActivity",
                            r0.activityInfo.name + "=" +
                            r0.priority + "/" + r0.isDefault + " vs " +
                            ri.activityInfo.name + "=" +
                            ri.priority + "/" + ri.isDefault);
                        if (r0.priority != ri.priority ||
                            r0.isDefault != ri.isDefault) {
                            while (i < N) {
                                mList.remove(i);
                                N--;
                            }
                        }
                    }
                    Collections.sort(mList, new ResolveInfo.DisplayNameComparator(pm));
                }
            }
        }

        public ResolveInfo resolveInfoForPosition(int position) {
            if (mList == null) {
                return null;
            }

            return mList.get(position);
        }

        public Intent intentForPosition(int position) {
            if (mList == null) {
                return null;
            }

            Intent intent = new Intent(mIntent);
            intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    |Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
            ActivityInfo ai = mList.get(position).activityInfo;
            intent.setComponent(new ComponentName(
                    ai.applicationInfo.packageName, ai.name));
            return intent;
        }

        public int getCount() {
            return mList != null ? mList.size() : 0;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView == null) {
                view = mInflater.inflate(
                        com.android.internal.R.layout.resolve_list_item, parent, false);
            } else {
                view = convertView;
            }
            bindView(view, mList.get(position));
            return view;
        }

        private final void bindView(View view, ResolveInfo info) {
            TextView text = (TextView)view.findViewById(com.android.internal.R.id.text1);
            ImageView icon = (ImageView)view.findViewById(R.id.icon);

            PackageManager pm = getPackageManager();

            CharSequence label = info.loadLabel(pm);
            if (label == null) label = info.activityInfo.name;
            text.setText(label);
            icon.setImageDrawable(info.loadIcon(pm));
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (mClearDefaultHint == null) return;
        
        if(isChecked) {
            mClearDefaultHint.setVisibility(View.VISIBLE);
        } else {
            mClearDefaultHint.setVisibility(View.GONE);
        }
    }
}

