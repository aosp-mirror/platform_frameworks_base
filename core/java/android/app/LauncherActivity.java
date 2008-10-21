/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Displays a list of all activities which can be performed
 * for a given intent. Launches when clicked.
 *
 */
public abstract class LauncherActivity extends ListActivity {
    
    /**
     * Adapter which shows the set of activities that can be performed for a given intent.
     */
    private class ActivityAdapter extends BaseAdapter implements Filterable {
        private final Object lock = new Object();
        private ArrayList<ResolveInfo> mOriginalValues;

        protected final Context mContext;
        protected final Intent mIntent;
        protected final LayoutInflater mInflater;

        protected List<ResolveInfo> mActivitiesList;

        private Filter mFilter;

        public ActivityAdapter(Context context, Intent intent) {
            mContext = context;
            mIntent = new Intent(intent);
            mIntent.setComponent(null);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            PackageManager pm = context.getPackageManager();
            mActivitiesList = pm.queryIntentActivities(intent, 0);
            if (mActivitiesList != null) {
                Collections.sort(mActivitiesList, new ResolveInfo.DisplayNameComparator(pm));
            }
        }

        public Intent intentForPosition(int position) {
            if (mActivitiesList == null) {
                return null;
            }

            Intent intent = new Intent(mIntent);
            ActivityInfo ai = mActivitiesList.get(position).activityInfo;
            intent.setClassName(ai.applicationInfo.packageName, ai.name);
            return intent;
        }

        public int getCount() {
            return mActivitiesList != null ? mActivitiesList.size() : 0;
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
                        com.android.internal.R.layout.simple_list_item_1, parent, false);
            } else {
                view = convertView;
            }
            bindView(view, mActivitiesList.get(position));
            return view;
        }

        private char getCandidateLetter(ResolveInfo info) {
            PackageManager pm = mContext.getPackageManager();
            CharSequence label = info.loadLabel(pm);

            if (label == null) {
                label = info.activityInfo.name;
            }

            return Character.toLowerCase(label.charAt(0));
        }

        private void bindView(View view, ResolveInfo info) {
            TextView text = (TextView) view.findViewById(com.android.internal.R.id.text1);

            PackageManager pm = mContext.getPackageManager();
            CharSequence label = info.loadLabel(pm);
            text.setText(label != null ? label : info.activityInfo.name);
        }

        public Filter getFilter() {
            if (mFilter == null) {
                mFilter = new ArrayFilter();
            }
            return mFilter;
        }

        /**
         * <p>An array filters constrains the content of the array adapter with a prefix. Each item that
         * does not start with the supplied prefix is removed from the list.</p>
         */
        private class ArrayFilter extends Filter {
            @Override
            protected FilterResults performFiltering(CharSequence prefix) {
                FilterResults results = new FilterResults();

                if (mOriginalValues == null) {
                    synchronized (lock) {
                        mOriginalValues = new ArrayList<ResolveInfo>(mActivitiesList);
                    }
                }

                if (prefix == null || prefix.length() == 0) {
                    synchronized (lock) {
                        ArrayList<ResolveInfo> list = new ArrayList<ResolveInfo>(mOriginalValues);
                        results.values = list;
                        results.count = list.size();
                    }
                } else {
                    final PackageManager pm = mContext.getPackageManager();
                    final String prefixString = prefix.toString().toLowerCase();

                    ArrayList<ResolveInfo> values = mOriginalValues;
                    int count = values.size();

                    ArrayList<ResolveInfo> newValues = new ArrayList<ResolveInfo>(count);

                    for (int i = 0; i < count; i++) {
                        ResolveInfo value = values.get(i);

                        final CharSequence label = value.loadLabel(pm);
                        final CharSequence name = label != null ? label : value.activityInfo.name;

                        String[] words = name.toString().toLowerCase().split(" ");
                        int wordCount = words.length;

                        for (int k = 0; k < wordCount; k++) {
                            final String word = words[k];

                            if (word.startsWith(prefixString)) {
                                newValues.add(value);
                                break;
                            }
                        }
                    }

                    results.values = newValues;
                    results.count = newValues.size();
                }

                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                //noinspection unchecked
                mActivitiesList = (List<ResolveInfo>) results.values;
                if (results.count > 0) {
                    notifyDataSetChanged();
                } else {
                    notifyDataSetInvalidated();
                }
            }
        }
    }
    
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mAdapter = new ActivityAdapter(this, getTargetIntent());
        
        setListAdapter(mAdapter);
        getListView().setTextFilterEnabled(true);
    }


    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Intent intent = ((ActivityAdapter)mAdapter).intentForPosition(position);

        startActivity(intent);
    }
    
    protected abstract Intent getTargetIntent();
   
}
