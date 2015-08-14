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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class PlatLogoActivity extends Activity {
    FrameLayout mLayout;
    int mTapCount;
    int mKeyCount;
    PathInterpolator mInterpolator = new PathInterpolator(0f, 0f, 0.5f, 1f);

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

        final View im = new View(this);
        im.setTranslationZ(20);
        im.setScaleX(0.5f);
        im.setScaleY(0.5f);
        im.setAlpha(0f);
        im.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                final int pad = (int) (8 * dp);
                outline.setOval(pad, pad, view.getWidth() - pad, view.getHeight() - pad);
            }
        });
        final float hue = (float) Math.random();
        final Paint bgPaint = new Paint();
        bgPaint.setColor(Color.HSBtoColor(hue, 0.4f, 1f));
        final Paint fgPaint = new Paint();
        fgPaint.setColor(Color.HSBtoColor(hue, 0.5f, 1f));
        final Drawable M = getDrawable(com.android.internal.R.drawable.platlogo_m);
        final Drawable platlogo = new Drawable() {
            @Override
            public void setAlpha(int alpha) { }

            @Override
            public void setColorFilter(@Nullable ColorFilter colorFilter) { }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            @Override
            public void draw(Canvas c) {
                final float r = c.getWidth() / 2f;
                c.drawCircle(r, r, r, bgPaint);
                c.drawArc(0, 0, 2 * r, 2 * r, 135, 180, false, fgPaint);
                M.setBounds(0, 0, c.getWidth(), c.getHeight());
                M.draw(c);
            }
        };
        im.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0xFFFFFFFF),
                platlogo,
                null));
        im.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        im.setClickable(true);
        im.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mTapCount == 0) {
                    showMarshmallow(im);
                }
                im.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (mTapCount < 5) return false;

                        final ContentResolver cr = getContentResolver();
                        if (Settings.System.getLong(cr, Settings.System.EGG_MODE, 0)
                                == 0) {
                            // For posterity: the moment this user unlocked the easter egg
                            try {
                                Settings.System.putLong(cr,
                                        Settings.System.EGG_MODE,
                                        System.currentTimeMillis());
                            } catch (RuntimeException e) {
                                Log.e("PlatLogoActivity", "Can't write settings", e);
                            }
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
                mTapCount++;
            }
        });

        // Enable hardware keyboard input for TV compatibility.
        im.setFocusable(true);
        im.requestFocus();
        im.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (mKeyCount == 0) {
                        showMarshmallow(im);
                    }
                    ++mKeyCount;
                    if (mKeyCount > 2) {
                        if (mTapCount > 5) {
                            im.performLongClick();
                        } else {
                            im.performClick();
                        }
                    }
                    return true;
                } else {
                    return false;
                }
            }
        });

        mLayout.addView(im, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        im.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(mInterpolator)
                .setDuration(500)
                .setStartDelay(800)
                .start();
    }

    public void showMarshmallow(View im) {
        final Drawable fg = getDrawable(com.android.internal.R.drawable.platlogo);
        fg.setBounds(0, 0, im.getWidth(), im.getHeight());
        fg.setAlpha(0);
        im.getOverlay().add(fg);

        final Animator fadeIn = ObjectAnimator.ofInt(fg, "alpha", 255);
        fadeIn.setInterpolator(mInterpolator);
        fadeIn.setDuration(300);
        fadeIn.start();
    }

}
