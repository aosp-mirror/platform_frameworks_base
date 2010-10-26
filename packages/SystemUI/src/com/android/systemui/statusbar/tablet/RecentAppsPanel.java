/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.BaseAdapter;
import android.widget.Gallery;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

public class RecentAppsPanel extends LinearLayout implements StatusBarPanel, OnClickListener {
    private static final String TAG = "RecentAppsPanel";
    private static final boolean DEBUG = TabletStatusBarService.DEBUG;
    private static final int MAX_RECENT_TASKS = 20;
    private static final float ITEM_WIDTH = 75;
    private static final float ITEM_HEIGHT = 75;
    private TabletStatusBarService mBar;
    private TextView mNoRecents;
    private LinearLayout mRecentsContainer;
    private float mDensity;
    private HorizontalScrollView mScrollView;

    public boolean isInContentArea(int x, int y) {
        final int l = getPaddingLeft();
        final int r = getWidth() - getPaddingRight();
        final int t = getPaddingTop();
        final int b = getHeight() - getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void setBar(TabletStatusBarService bar) {
        mBar = bar;
    }

    public RecentAppsPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentAppsPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mDensity = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNoRecents = (TextView) findViewById(R.id.recents_no_recents);
        mRecentsContainer = (LinearLayout) findViewById(R.id.recents_container);
        mScrollView = (HorizontalScrollView) findViewById(R.id.scroll_view);
        mScrollView.setHorizontalFadingEdgeEnabled(true);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        Log.v(TAG, "onVisibilityChanged(" + changedView + ", " + visibility + ")");
        if (visibility == View.VISIBLE && changedView == this) {
            refreshIcons();
            mRecentsContainer.setScrollbarFadingEnabled(true);
            mRecentsContainer.scrollTo(0, 0);
        }
    }

    private void refreshIcons() {
        mRecentsContainer.removeAllViews();
        final Context context = getContext();
        final PackageManager pm = context.getPackageManager();
        final ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_RECENT_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .resolveActivityInfo(pm, 0);

        int numTasks = recentTasks.size();
        final int width = (int) (mDensity * ITEM_WIDTH + 0.5f);
        final int height = (int) (mDensity * ITEM_HEIGHT + 0.5f);
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(width, height);
        for (int i = 0; i < numTasks; ++i) {
            final ActivityManager.RecentTaskInfo info = recentTasks.get(i);

            Intent intent = new Intent(info.baseIntent);
            if (info.origActivity != null) {
                intent.setComponent(info.origActivity);
            }

            // Exclude home activity.
            if (homeInfo != null
                    && homeInfo.packageName.equals(intent.getComponent().getPackageName())
                    && homeInfo.name.equals(intent.getComponent().getClassName())) {
                    continue;
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo activityInfo = resolveInfo.activityInfo;
                final String title = activityInfo.loadLabel(pm).toString();
                Drawable icon = activityInfo.loadIcon(pm);

                if (title != null && title.length() > 0 && icon != null) {
                    ImageView imageView = new ImageView(mContext);
                    imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                    imageView.setLayoutParams(layoutParams);
                    imageView.setOnClickListener(this);
                    imageView.setTag(intent);
                    imageView.setImageDrawable(icon);
                    mRecentsContainer.addView(imageView);
                }
            }
        }

        int views = mRecentsContainer.getChildCount();
        mNoRecents.setVisibility(views == 0 ? View.VISIBLE : View.GONE);
        mRecentsContainer.setVisibility(views > 0 ? View.VISIBLE : View.GONE);
    }

    public void onClick(View v) {
        Intent intent = (Intent) v.getTag();
        if (DEBUG) Log.v(TAG, "Starting activity " + intent);
        getContext().startActivity(intent);
        mBar.animateCollapse();
    }
}
