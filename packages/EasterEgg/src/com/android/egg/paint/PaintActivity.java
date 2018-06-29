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

package com.android.egg.paint;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Magnifier;

import com.android.egg.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.IntStream;

public class PaintActivity extends Activity {
    private static final float MAX_BRUSH_WIDTH_DP = 100f;
    private static final float MIN_BRUSH_WIDTH_DP = 1f;

    private static final int NUM_BRUSHES = 6;
    private static final int NUM_COLORS = 6;

    private Painting painting = null;
    private CutoutAvoidingToolbar toolbar = null;
    private LinearLayout brushes = null;
    private LinearLayout colors = null;
    private Magnifier magnifier = null;
    private boolean sampling = false;

    private View.OnClickListener buttonHandler = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.btnBrush:
                    view.setSelected(true);
                    hideToolbar(colors);
                    toggleToolbar(brushes);
                    break;
                case R.id.btnColor:
                    view.setSelected(true);
                    hideToolbar(brushes);
                    toggleToolbar(colors);
                    break;
                case R.id.btnClear:
                    painting.clear();
                    break;
                case R.id.btnSample:
                    sampling = true;
                    view.setSelected(true);
                    break;
                case R.id.btnZen:
                    painting.setZenMode(!painting.getZenMode());
                    view.animate()
                            .setStartDelay(200)
                            .setInterpolator(new OvershootInterpolator())
                            .rotation(painting.getZenMode() ? 0f : 90f);
                    break;
            }
        }
    };

    private void showToolbar(View bar) {
        if (bar.getVisibility() != View.GONE) return;
        bar.setVisibility(View.VISIBLE);
        bar.setTranslationY(toolbar.getHeight()/2);
        bar.animate()
                .translationY(toolbar.getHeight())
                .alpha(1f)
                .setDuration(220)
                .start();
    }

    private void hideToolbar(View bar) {
        if (bar.getVisibility() != View.VISIBLE) return;
        bar.animate()
                .translationY(toolbar.getHeight()/2)
                .alpha(0f)
                .setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        bar.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void toggleToolbar(View bar) {
        if (bar.getVisibility() == View.VISIBLE) {
            hideToolbar(bar);
        } else {
            showToolbar(bar);
        }
    }

    private BrushPropertyDrawable widthButtonDrawable;
    private BrushPropertyDrawable colorButtonDrawable;
    private float maxBrushWidth, minBrushWidth;
    private int nightMode = Configuration.UI_MODE_NIGHT_UNDEFINED;

    static final float lerp(float f, float a, float b) {
        return a + (b-a) * f;
    }

    void setupViews(Painting oldPainting) {
        setContentView(R.layout.activity_paint);

        painting = oldPainting != null ? oldPainting : new Painting(this);
        ((FrameLayout) findViewById(R.id.contentView)).addView(painting,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        painting.setPaperColor(getColor(R.color.paper_color));
        painting.setPaintColor(getColor(R.color.paint_color));

        toolbar = findViewById(R.id.toolbar);
        brushes = findViewById(R.id.brushes);
        colors = findViewById(R.id.colors);

        magnifier = new Magnifier(painting);

        painting.setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case ACTION_DOWN:
                            case ACTION_MOVE:
                                if (sampling) {
                                    magnifier.show(event.getX(), event.getY());
                                    colorButtonDrawable.setWellColor(
                                            painting.sampleAt(event.getX(), event.getY()));
                                    return true;
                                }
                                break;
                            case ACTION_CANCEL:
                                if (sampling) {
                                    findViewById(R.id.btnSample).setSelected(false);
                                    sampling = false;
                                    magnifier.dismiss();
                                }
                                break;
                            case ACTION_UP:
                                if (sampling) {
                                    findViewById(R.id.btnSample).setSelected(false);
                                    sampling = false;
                                    magnifier.dismiss();
                                    painting.setPaintColor(
                                            painting.sampleAt(event.getX(), event.getY()));
                                    refreshBrushAndColor();
                                }
                                break;
                        }
                        return false; // allow view to continue handling
                    }
                });

        findViewById(R.id.btnBrush).setOnClickListener(buttonHandler);
        findViewById(R.id.btnColor).setOnClickListener(buttonHandler);
        findViewById(R.id.btnClear).setOnClickListener(buttonHandler);
        findViewById(R.id.btnSample).setOnClickListener(buttonHandler);
        findViewById(R.id.btnZen).setOnClickListener(buttonHandler);

        findViewById(R.id.btnColor).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                colors.removeAllViews();
                showToolbar(colors);
                refreshBrushAndColor();
                return true;
            }
        });

        findViewById(R.id.btnClear).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                painting.invertContents();
                return true;
            }
        });

        widthButtonDrawable = new BrushPropertyDrawable(this);
        widthButtonDrawable.setFrameColor(getColor(R.color.toolbar_icon_color));
        colorButtonDrawable = new BrushPropertyDrawable(this);
        colorButtonDrawable.setFrameColor(getColor(R.color.toolbar_icon_color));

        ((ImageButton) findViewById(R.id.btnBrush)).setImageDrawable(widthButtonDrawable);
        ((ImageButton) findViewById(R.id.btnColor)).setImageDrawable(colorButtonDrawable);

        refreshBrushAndColor();
    }

    private void refreshBrushAndColor() {
        final LinearLayout.LayoutParams button_lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        button_lp.weight = 1f;
        if (brushes.getChildCount() == 0) {
            for (int i = 0; i < NUM_BRUSHES; i++) {
                final BrushPropertyDrawable icon = new BrushPropertyDrawable(this);
                icon.setFrameColor(getColor(R.color.toolbar_icon_color));
                // exponentially increasing brush size
                final float width = lerp(
                        (float) Math.pow((float) i / NUM_BRUSHES, 2f), minBrushWidth,
                        maxBrushWidth);
                icon.setWellScale(width / maxBrushWidth);
                icon.setWellColor(getColor(R.color.toolbar_icon_color));
                final ImageButton button = new ImageButton(this);
                button.setImageDrawable(icon);
                button.setBackground(getDrawable(R.drawable.toolbar_button_bg));
                button.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                brushes.setSelected(false);
                                hideToolbar(brushes);
                                painting.setBrushWidth(width);
                                refreshBrushAndColor();
                            }
                        });
                brushes.addView(button, button_lp);
            }
        }

        if (colors.getChildCount() == 0) {
            final Palette pal = new Palette(NUM_COLORS);
            for (final int c : IntStream.concat(
                    IntStream.of(Color.BLACK, Color.WHITE),
                    Arrays.stream(pal.getColors())
            ).toArray()) {
                final BrushPropertyDrawable icon = new BrushPropertyDrawable(this);
                icon.setFrameColor(getColor(R.color.toolbar_icon_color));
                icon.setWellColor(c);
                final ImageButton button = new ImageButton(this);
                button.setImageDrawable(icon);
                button.setBackground(getDrawable(R.drawable.toolbar_button_bg));
                button.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            colors.setSelected(false);
                            hideToolbar(colors);
                            painting.setPaintColor(c);
                            refreshBrushAndColor();
                        }
                    });
                colors.addView(button, button_lp);
            }
        }

        widthButtonDrawable.setWellScale(painting.getBrushWidth() / maxBrushWidth);
        widthButtonDrawable.setWellColor(painting.getPaintColor());
        colorButtonDrawable.setWellColor(painting.getPaintColor());
    }

    private void refreshNightMode(Configuration config) {
        int newNightMode =
                (config.uiMode & Configuration.UI_MODE_NIGHT_MASK);
        if (nightMode != newNightMode) {
            if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
                painting.invertContents();

                ((ViewGroup) painting.getParent()).removeView(painting);
                setupViews(painting);

                final View decorView = getWindow().getDecorView();
                int decorSUIV = decorView.getSystemUiVisibility();

                if (newNightMode == Configuration.UI_MODE_NIGHT_YES) {
                    decorView.setSystemUiVisibility(
                            decorSUIV & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                } else {
                    decorView.setSystemUiVisibility(
                            decorSUIV | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
            }
            nightMode = newNightMode;
        }
    }

    public PaintActivity() {

    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        painting.onTrimMemory();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        refreshNightMode(newConfig);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.flags = lp.flags
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        getWindow().setAttributes(lp);

        maxBrushWidth = MAX_BRUSH_WIDTH_DP * getResources().getDisplayMetrics().density;
        minBrushWidth = MIN_BRUSH_WIDTH_DP * getResources().getDisplayMetrics().density;

        setupViews(null);
        refreshNightMode(getResources().getConfiguration());
    }

    @Override
    public void onPostResume() {
        super.onPostResume();
    }

}
