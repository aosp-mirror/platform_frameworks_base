/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.google.android.test.activity;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.os.Bundle;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.view.LayoutInflater;
import android.view.View;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.util.DisplayMetrics;
import android.util.Log;

public class ActivityTestMain extends Activity {
    private void addThumbnail(LinearLayout container, Bitmap bm) {
        ImageView iv = new ImageView(this);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setBackgroundResource(android.R.drawable.gallery_thumb);
        int w = getResources().getDimensionPixelSize(android.R.dimen.thumbnail_width);
        int h = getResources().getDimensionPixelSize(android.R.dimen.thumbnail_height);
        container.addView(iv, new LinearLayout.LayoutParams(w, h));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);

        List<ActivityManager.RecentTaskInfo> recents = am.getRecentTasks(10,
                ActivityManager.RECENT_WITH_EXCLUDED);
        if (recents != null) {
            for (int i=0; i<recents.size(); i++) {
                ActivityManager.RecentTaskInfo r = recents.get(i);
                ActivityManager.TaskThumbnails tt = r != null
                        ? am.getTaskThumbnails(r.persistentId) : null;
                TextView tv = new TextView(this);
                tv.setText(r.baseIntent.getComponent().flattenToShortString());
                top.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.HORIZONTAL);
                addThumbnail(item, tt != null ? tt.mainThumbnail : null);
                for (int j=0; j<tt.numSubThumbbails; j++) {
                    addThumbnail(item, tt.getSubThumbnail(j));
                }
                top.addView(item, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        setContentView(scrollWrap(top));
    }

    private View scrollWrap(View view) {
        ScrollView scroller = new ScrollView(this);
        scroller.addView(view, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        return scroller;
    }
}
