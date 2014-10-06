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

package com.android.internal.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class PlatLogoActivity extends Activity {
    final static int[] FLAVORS = {
            0xFF9C27B0, 0xFFBA68C8, // grape
            0xFFFF9800, 0xFFFFB74D, // orange
            0xFFF06292, 0xFFF8BBD0, // bubblegum
            0xFFAFB42B, 0xFFCDDC39, // lime
            0xFFFFEB3B, 0xFFFFF176, // lemon
            0xFF795548, 0xFFA1887F, // mystery flavor
    };
    FrameLayout mLayout;
    int mTapCount;
    PathInterpolator mInterpolator = new PathInterpolator(0f, 0f, 0.5f, 1f);

    static int newColorIndex() {
        return 2*((int) (Math.random()*FLAVORS.length/2));
    }

    Drawable makeRipple() {
        final int idx = newColorIndex();
        final ShapeDrawable popbg = new ShapeDrawable(new OvalShape());
        popbg.getPaint().setColor(FLAVORS[idx]);
        final RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(FLAVORS[idx+1]),
                popbg, null);
        return ripple;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLayout = new FrameLayout(this);
        setContentView(mLayout);
    }

    @Override
    public void onAttachedToWindow() {
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final float dp = dm.density;
        final int size = (int)
                (Math.min(Math.min(dm.widthPixels, dm.heightPixels), 600*dp) - 100*dp);

        final View stick = new View(this) {
            Paint mPaint = new Paint();
            Path mShadow = new Path();

            @Override
            public void onAttachedToWindow() {
                super.onAttachedToWindow();
                setWillNotDraw(false);
                setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRect(0, getHeight() / 2, getWidth(), getHeight());
                    }
                });
            }
            @Override
            public void onDraw(Canvas c) {
                final int w = c.getWidth();
                final int h = c.getHeight() / 2;
                c.translate(0, h);
                final GradientDrawable g = new GradientDrawable();
                g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                g.setGradientCenter(w * 0.75f, 0);
                g.setColors(new int[] { 0xFFFFFFFF, 0xFFAAAAAA });
                g.setBounds(0, 0, w, h);
                g.draw(c);
                mPaint.setColor(0xFFAAAAAA);
                mShadow.reset();
                mShadow.moveTo(0,0);
                mShadow.lineTo(w, 0);
                mShadow.lineTo(w, size/2 + 1.5f*w);
                mShadow.lineTo(0, size/2);
                mShadow.close();
                c.drawPath(mShadow, mPaint);
            }
        };
        mLayout.addView(stick, new FrameLayout.LayoutParams((int) (32 * dp),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        stick.setAlpha(0f);

        final ImageView im = new ImageView(this);
        im.setTranslationZ(20);
        im.setScaleX(0);
        im.setScaleY(0);
        final Drawable platlogo = getDrawable(com.android.internal.R.drawable.platlogo);
        platlogo.setAlpha(0);
        im.setImageDrawable(platlogo);
        im.setBackground(makeRipple());
        im.setClickable(true);
        final ShapeDrawable highlight = new ShapeDrawable(new OvalShape());
        highlight.getPaint().setColor(0x10FFFFFF);
        highlight.setBounds((int)(size*.15f), (int)(size*.15f),
                            (int)(size*.6f), (int)(size*.6f));
        im.getOverlay().add(highlight);
        im.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTapCount == 0) {
                    im.animate()
                            .translationZ(40)
                            .scaleX(1)
                            .scaleY(1)
                            .setInterpolator(mInterpolator)
                            .setDuration(700)
                            .setStartDelay(500)
                            .start();

                    final ObjectAnimator a = ObjectAnimator.ofInt(platlogo, "alpha", 0, 255);
                    a.setInterpolator(mInterpolator);
                    a.setStartDelay(1000);
                    a.start();

                    stick.animate()
                            .translationZ(20)
                            .alpha(1)
                            .setInterpolator(mInterpolator)
                            .setDuration(700)
                            .setStartDelay(750)
                            .start();

                    im.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            if (mTapCount < 5) return false;

                            final ContentResolver cr = getContentResolver();
                            if (Settings.System.getLong(cr, Settings.System.EGG_MODE, 0)
                                    == 0) {
                                // For posterity: the moment this user unlocked the easter egg
                                Settings.System.putLong(cr,
                                        Settings.System.EGG_MODE,
                                        System.currentTimeMillis());
                            }
                            im.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        startActivity(new Intent(Intent.ACTION_MAIN)
                                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                                .addCategory("com.android.internal.category.PLATLOGO"));
                                    } catch (ActivityNotFoundException ex) {
                                        Log.e("PlatLogoActivity", "No more eggs.");
                                    }
                                    finish();
                                }
                            });
                            return true;
                        }
                    });
                } else {
                    im.setBackground(makeRipple());
                }
                mTapCount++;
            }
        });

        mLayout.addView(im, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        im.animate().scaleX(0.3f).scaleY(0.3f)
                .setInterpolator(mInterpolator)
                .setDuration(500)
                .setStartDelay(800)
                .start();
    }
}
