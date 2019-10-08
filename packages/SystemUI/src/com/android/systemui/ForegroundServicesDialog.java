/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

import java.util.ArrayList;

/**
 * Show a list of currently running foreground services (supplied by the caller)
 * that the user can tap through to their application details.
 */
public final class ForegroundServicesDialog extends AlertActivity implements
        AdapterView.OnItemSelectedListener, DialogInterface.OnClickListener,
        AlertController.AlertParams.OnPrepareListViewListener {

    private static final String TAG = "ForegroundServicesDialog";

    LayoutInflater mInflater;

    private MetricsLogger mMetricsLogger;

    private String[] mPackages;
    private PackageItemAdapter mAdapter;

    private DialogInterface.OnClickListener mAppClickListener =
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    String pkg = mAdapter.getItem(which).packageName;
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", pkg, null));
                    startActivity(intent);
                    finish();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Dependency.initDependencies(getApplicationContext());

        mMetricsLogger = Dependency.get(MetricsLogger.class);

        mInflater = LayoutInflater.from(this);

        mAdapter = new PackageItemAdapter(this);

        final AlertController.AlertParams p = mAlertParams;
        p.mAdapter = mAdapter;
        p.mOnClickListener = mAppClickListener;
        p.mCustomTitleView = mInflater.inflate(R.layout.foreground_service_title, null);
        p.mIsSingleChoice = true;
        p.mOnItemSelectedListener = this;
        p.mPositiveButtonText = getString(com.android.internal.R.string.done_label);
        p.mPositiveButtonListener = this;
        p.mOnPrepareListViewListener = this;

        updateApps(getIntent());
        if (mPackages == null) {
            Log.w(TAG, "No packages supplied");
            finish();
            return;
        }

        setupAlert();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMetricsLogger.visible(MetricsProto.MetricsEvent.RUNNING_BACKGROUND_APPS_DIALOG);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMetricsLogger.hidden(MetricsProto.MetricsEvent.RUNNING_BACKGROUND_APPS_DIALOG);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateApps(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // This is a transient dialog, if the user leaves it then it goes away,
        // they can return back to it from the notification.
        if (!isChangingConfigurations()) {
            finish();
        }
    }

    void updateApps(Intent intent) {
        mPackages = intent.getStringArrayExtra("packages");
        if (mPackages != null) {
            mAdapter.setPackages(mPackages);
        }
    }

    public void onPrepareListView(ListView listView) {
    }

    /*
     * On click of Ok/Cancel buttons
     */
    public void onClick(DialogInterface dialog, int which) {
        finish();
    }

    public void onItemSelected(AdapterView parent, View view, int position, long id) {
    }

    public void onNothingSelected(AdapterView parent) {
    }

    static private class PackageItemAdapter extends ArrayAdapter<ApplicationInfo> {
        final PackageManager mPm;
        final LayoutInflater mInflater;
        final IconDrawableFactory mIconDrawableFactory;

        public PackageItemAdapter(Context context) {
            super(context, R.layout.foreground_service_item);
            mPm = context.getPackageManager();
            mInflater = LayoutInflater.from(context);
            mIconDrawableFactory = IconDrawableFactory.newInstance(context, true);
        }

        public void setPackages(String[] packages) {
            clear();

            ArrayList<ApplicationInfo> apps = new ArrayList<>();
            for (int i = 0; i < packages.length; i++) {
                try {
                    apps.add(mPm.getApplicationInfo(packages[i],
                            PackageManager.MATCH_KNOWN_PACKAGES));
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            apps.sort(new ApplicationInfo.DisplayNameComparator(mPm));
            addAll(apps);
        }

        public @NonNull
        View getView(int position, @Nullable View convertView,
                @NonNull ViewGroup parent) {
            final View view;
            if (convertView == null) {
                view = mInflater.inflate(R.layout.foreground_service_item, parent, false);
            } else {
                view = convertView;
            }

            ImageView icon = view.findViewById(R.id.app_icon);
            icon.setImageDrawable(mIconDrawableFactory.getBadgedIcon(getItem(position)));

            TextView label = view.findViewById(R.id.app_name);
            label.setText(getItem(position).loadLabel(mPm));

            return view;
        }
    }
}
