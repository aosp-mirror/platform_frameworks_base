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

package com.google.android.test.dpi;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class DpiTestActivity extends Activity {
    public DpiTestActivity() {
        super();
        init(false);
    }
    
    public DpiTestActivity(boolean noCompat) {
        super();
        init(noCompat);
    }
    
    public void init(boolean noCompat) {
        try {
            // This is all a dirty hack.  Don't think a real application should
            // be doing it.
            Application app = ActivityThread.currentActivityThread().getApplication();
            ApplicationInfo ai = app.getPackageManager().getApplicationInfo(
                    "com.google.android.test.dpi", 0);
            if (noCompat) {
                ai.flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS
                    | ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS
                    | ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS
                    | ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS
                    | ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS
                    | ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
                app.getResources().setCompatibilityInfo(new CompatibilityInfo(ai,
                        getResources().getConfiguration().screenLayout,
                        getResources().getConfiguration().smallestScreenWidthDp, false, 1f));
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("ouch", e);
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LayoutInflater li = (LayoutInflater)getSystemService(
                LAYOUT_INFLATER_SERVICE);
        
        this.setTitle(R.string.act_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout layout = new LinearLayout(this);
        addBitmapDrawable(layout, R.drawable.logo120dpi, true);
        addBitmapDrawable(layout, R.drawable.logo160dpi, true);
        addBitmapDrawable(layout, R.drawable.logo240dpi, true);
        addLabelToRoot(root, "Prescaled bitmap in drawable");
        addChildToRoot(root, layout);

        layout = new LinearLayout(this);
        addBitmapDrawable(layout, R.drawable.logo120dpi, false);
        addBitmapDrawable(layout, R.drawable.logo160dpi, false);
        addBitmapDrawable(layout, R.drawable.logo240dpi, false);
        addLabelToRoot(root, "Autoscaled bitmap in drawable");
        addChildToRoot(root, layout);

        layout = new LinearLayout(this);
        addResourceDrawable(layout, R.drawable.logo120dpi);
        addResourceDrawable(layout, R.drawable.logo160dpi);
        addResourceDrawable(layout, R.drawable.logo240dpi);
        addLabelToRoot(root, "Prescaled resource drawable");
        addChildToRoot(root, layout);

        layout = (LinearLayout)li.inflate(R.layout.image_views, null);
        addLabelToRoot(root, "Inflated layout");
        addChildToRoot(root, layout);
        
        layout = (LinearLayout)li.inflate(R.layout.styled_image_views, null);
        addLabelToRoot(root, "Inflated styled layout");
        addChildToRoot(root, layout);
        
        layout = new LinearLayout(this);
        addCanvasBitmap(layout, R.drawable.logo120dpi, true);
        addCanvasBitmap(layout, R.drawable.logo160dpi, true);
        addCanvasBitmap(layout, R.drawable.logo240dpi, true);
        addLabelToRoot(root, "Prescaled bitmap");
        addChildToRoot(root, layout);

        layout = new LinearLayout(this);
        addCanvasBitmap(layout, R.drawable.logo120dpi, false);
        addCanvasBitmap(layout, R.drawable.logo160dpi, false);
        addCanvasBitmap(layout, R.drawable.logo240dpi, false);
        addLabelToRoot(root, "Autoscaled bitmap");
        addChildToRoot(root, layout);

        layout = new LinearLayout(this);
        addResourceDrawable(layout, R.drawable.logonodpi120);
        addResourceDrawable(layout, R.drawable.logonodpi160);
        addResourceDrawable(layout, R.drawable.logonodpi240);
        addLabelToRoot(root, "No-dpi resource drawable");
        addChildToRoot(root, layout);

        layout = new LinearLayout(this);
        addNinePatchResourceDrawable(layout, R.drawable.smlnpatch120dpi);
        addNinePatchResourceDrawable(layout, R.drawable.smlnpatch160dpi);
        addNinePatchResourceDrawable(layout, R.drawable.smlnpatch240dpi);
        addLabelToRoot(root, "Prescaled 9-patch resource drawable");
        addChildToRoot(root, layout);

        setContentView(scrollWrap(root));
    }

    private View scrollWrap(View view) {
        ScrollView scroller = new ScrollView(this);
        scroller.addView(view, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    private void addLabelToRoot(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        root.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addChildToRoot(LinearLayout root, LinearLayout layout) {
        root.addView(layout, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addBitmapDrawable(LinearLayout layout, int resource, boolean scale) {
        Bitmap bitmap;
        bitmap = loadAndPrintDpi(resource, scale);

        View view = new View(this);

        final BitmapDrawable d = new BitmapDrawable(getResources(), bitmap);
        if (!scale) d.setTargetDensity(getResources().getDisplayMetrics());
        view.setBackgroundDrawable(d);

        view.setLayoutParams(new LinearLayout.LayoutParams(d.getIntrinsicWidth(),
                d.getIntrinsicHeight()));
        layout.addView(view);
    }

    private void addResourceDrawable(LinearLayout layout, int resource) {
        View view = new View(this);

        final Drawable d = getResources().getDrawable(resource);
        view.setBackgroundDrawable(d);

        view.setLayoutParams(new LinearLayout.LayoutParams(d.getIntrinsicWidth(),
                d.getIntrinsicHeight()));
        layout.addView(view);
    }

    private void addCanvasBitmap(LinearLayout layout, int resource, boolean scale) {
        Bitmap bitmap;
        bitmap = loadAndPrintDpi(resource, scale);

        ScaledBitmapView view = new ScaledBitmapView(this, bitmap);

        view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(view);
    }

    private void addNinePatchResourceDrawable(LinearLayout layout, int resource) {
        View view = new View(this);

        final Drawable d = getResources().getDrawable(resource);
        view.setBackgroundDrawable(d);

        Log.i("foo", "9-patch #" + Integer.toHexString(resource)
                + " w=" + d.getIntrinsicWidth() + " h=" + d.getIntrinsicHeight());
        view.setLayoutParams(new LinearLayout.LayoutParams(
                d.getIntrinsicWidth()*2, d.getIntrinsicHeight()*2));
        layout.addView(view);
    }

    private Bitmap loadAndPrintDpi(int id, boolean scale) {
        Bitmap bitmap;
        if (scale) {
            bitmap = BitmapFactory.decodeResource(getResources(), id);
        } else {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            bitmap = BitmapFactory.decodeResource(getResources(), id, opts);
        }
        return bitmap;
    }

    private class ScaledBitmapView extends View {
        private Bitmap mBitmap;

        public ScaledBitmapView(Context context, Bitmap bitmap) {
            super(context);
            mBitmap = bitmap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            final DisplayMetrics metrics = getResources().getDisplayMetrics();
            setMeasuredDimension(
                    mBitmap.getScaledWidth(metrics),
                    mBitmap.getScaledHeight(metrics));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawBitmap(mBitmap, 0.0f, 0.0f, null);
        }
    }
}
