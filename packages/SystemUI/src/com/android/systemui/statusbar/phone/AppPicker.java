/*
 * Copyright (C) 2019 The Dirty Unicorns Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ListActivity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.android.systemui.R;

public class AppPicker extends ListActivity {

    protected PackageManager packageManager = null;
    protected List<ApplicationInfo> applist = null;
    protected Adapter listadapter = null;

    protected List<ActivityInfo> mActivitiesList = null;
    protected boolean mIsActivitiesList = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(android.R.layout.list_content);
        setTitle(R.string.active_edge_app_select_title);

        packageManager = getPackageManager();
        new LoadApplications().execute();
    }

    protected void onListItemClick(ListView l, View v, int position, long id) {
        if (!mIsActivitiesList) {
            // we are in the Apps list
        } else if (mIsActivitiesList) {
            // we are in the Activities list
        }

        mIsActivitiesList = false;

        finish();
    }

    @Override
    public void onBackPressed() {
        if (mIsActivitiesList) {
            setListAdapter(listadapter);
            setTitle(R.string.active_edge_app_select_title);
            // Reset the dialog again
            mIsActivitiesList = false;
        } else {
            finish();
        }
    }

    private List<ApplicationInfo> checkForLaunchIntent(List<ApplicationInfo> list) {
        ArrayList<ApplicationInfo> applist = new ArrayList<>();

        // If we need to blacklist apps, this is where we list them
        String[] blacklist_packages = {
                "com.google.android.as", // Actions Services
                "com.google.android.GoogleCamera", // Google camera
                "com.google.android.imaging.easel.service", // Pixel Visual Core Service
                "com.android.traceur" // System Tracing (Google spyware lol)
        };

        for (ApplicationInfo info : list) {
            try {
                /* Remove blacklisted apps from the list of apps we give to
                   the user to select from. */
                if ((!Arrays.asList(blacklist_packages).contains(info.packageName)
                        && null != packageManager.getLaunchIntentForPackage(info.packageName))) {
                    applist.add(info);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Lets alphabatize the list of installed user apps
        Collections.sort(applist, new ApplicationInfo.DisplayNameComparator(packageManager));

        return applist;
    }

    class LoadApplications extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            applist = checkForLaunchIntent(packageManager.getInstalledApplications(
                    PackageManager.GET_META_DATA));
            listadapter = new Adapter(AppPicker.this,
                    R.layout.app_list_item, applist, packageManager);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            setListAdapter(listadapter);

            getListView().setLongClickable(true);
            getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> arg0, View arg1,
                        int pos, long id) {
                    onLongClick(pos);
                    return true;
                }
            });
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }
    }

    protected void onLongClick(int pos) {
        /*if (mIsActivitiesList) return;
        String packageName = applist.get(position).packageName;
        showActivitiesDialog(packageName);*/
    }

    protected void showActivitiesDialog(String packageName) {
        mIsActivitiesList = true;
        ArrayList<ActivityInfo> list = null;
        try {
            PackageInfo pi = packageManager.getPackageInfo (
                    packageName, PackageManager.GET_ACTIVITIES);

            list = new ArrayList<>(Arrays.asList(pi.activities));

        } catch (PackageManager.NameNotFoundException e) {
        }

        mActivitiesList = list;

        if (list == null) {
            // no activities to show, let's stay in the Apps list
            mIsActivitiesList = false;
            return;
        }

        setTitle(R.string.active_edge_activity_select_title);
        // switch to a new adapter to show app activities
        ActivitiesAdapter adapter = new ActivitiesAdapter(this, R.layout.app_list_item, list, packageManager);
        setListAdapter(adapter);
    }

    class Adapter extends ArrayAdapter<ApplicationInfo> {

        private List<ApplicationInfo> appList;
        private Context context;
        private PackageManager packageManager;

        private Adapter(Context context, int resource, List<ApplicationInfo> objects, PackageManager pm) {
            super(context, resource, objects);

            this.context = context;
            this.appList = objects;
            packageManager = pm;
        }

        @Override
        public int getCount() {
            return ((null != appList) ? appList.size() : 0);
        }

        @Override
        public ApplicationInfo getItem(int position) {
            return ((null != appList) ? appList.get(position) : null);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;

            ApplicationInfo data = appList.get(position);

            if (view == null) {
                LayoutInflater layoutInflater = (LayoutInflater) context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = layoutInflater.inflate(R.layout.app_list_item, null);
            }

            if (data != null) {
                TextView appName = view.findViewById(R.id.app_name);
                ImageView iconView = view.findViewById(R.id.app_icon);

                appName.setText(data.loadLabel(packageManager));
                iconView.setImageDrawable(data.loadIcon(packageManager));
            }
            return view;
        }
    }

    class ActivitiesAdapter extends ArrayAdapter<ActivityInfo> {

        private List<ActivityInfo> appList;
        private Context context;
        private PackageManager packageManager;

        private ActivitiesAdapter(Context context, int resource, List<ActivityInfo> objects, PackageManager pm) {
            super(context, resource, objects);

            this.context = context;
            this.appList = objects;
            this.packageManager = pm;
        }

        @Override
        public int getCount() {
            return ((null != appList) ? appList.size() : 0);
        }

        @Override
        public ActivityInfo getItem(int position) {
            return ((null != appList) ? appList.get(position) : null);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;

            ActivityInfo data = appList.get(position);

            if (view == null) {
                LayoutInflater layoutInflater = (LayoutInflater) context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null);
            }

            if (data != null) {
                TextView appName = view.findViewById(android.R.id.text1);

                String name = /*data.loadLabel(packageManager).toString();
                if (name == null) {
                    name = */data.name;
                //}
                appName.setText(name);
            }

            return view;
        }
    }
}


