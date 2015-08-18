/*
* Copyright (C) 2015 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.systemui.statusbar;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.internal.graphics.palette.Palette;
import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;

import lineageos.providers.LineageSettings;

public class VisualizerView extends View
        implements Palette.PaletteAsyncListener, TunerService.Tunable {

    private static final String TAG = VisualizerView.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String LOCKSCREEN_VISUALIZER_ENABLED =
            "lineagesecure:" + LineageSettings.Secure.LOCKSCREEN_VISUALIZER_ENABLED;

    private Paint mPaint;
    private Visualizer mVisualizer;
    private ObjectAnimator mVisualizerColorAnimator;

    private ValueAnimator[] mValueAnimators;
    private float[] mFFTPoints;

    private int mStatusBarState;
    private boolean mVisualizerEnabled;
    private boolean mVisible;
    private boolean mPlaying;
    private boolean mPowerSaveMode;
    private boolean mDisplaying; // the state we're animating to
    private boolean mDozing;
    private boolean mOccluded;

    private int mColor;
    private Bitmap mCurrentBitmap;

    private Visualizer.OnDataCaptureListener mVisualizerListener =
            new Visualizer.OnDataCaptureListener() {
        byte rfk, ifk;
        int dbValue;
        float magnitude;

        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            for (int i = 0; i < 32; i++) {
                mValueAnimators[i].cancel();
                rfk = fft[i * 2 + 2];
                ifk = fft[i * 2 + 3];
                magnitude = rfk * rfk + ifk * ifk;
                dbValue = magnitude > 0 ? (int) (10 * Math.log10(magnitude)) : 0;

                mValueAnimators[i].setFloatValues(mFFTPoints[i * 4 + 1],
                        mFFTPoints[3] - (dbValue * 16f));
                mValueAnimators[i].start();
            }
        }
    };

    private final Runnable mLinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.w(TAG, "+++ mLinkVisualizer run()");
            }

            try {
                mVisualizer = new Visualizer(0);
            } catch (Exception e) {
                Log.e(TAG, "error initializing visualizer", e);
                return;
            }

            mVisualizer.setEnabled(false);
            mVisualizer.setCaptureSize(66);
            mVisualizer.setDataCaptureListener(mVisualizerListener,Visualizer.getMaxCaptureRate(),
                    false, true);
            mVisualizer.setEnabled(true);

            if (DEBUG) {
                Log.w(TAG, "--- mLinkVisualizer run()");
            }
        }
    };

    private final Runnable mAsyncUnlinkVisualizer = new Runnable() {
        @Override
        public void run() {
            AsyncTask.execute(mUnlinkVisualizer);
        }
    };

    private final Runnable mUnlinkVisualizer = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.w(TAG, "+++ mUnlinkVisualizer run(), mVisualizer: " + mVisualizer);
            }
            if (mVisualizer != null) {
                mVisualizer.setEnabled(false);
                mVisualizer.release();
                mVisualizer = null;
            }
            if (DEBUG) {
                Log.w(TAG, "--- mUnlinkVisualizer run()");
            }
        }
    };

    public VisualizerView(Context context) {
        this(context, null, 0);
    }

    public VisualizerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VisualizerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mColor = Color.TRANSPARENT;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setColor(mColor);

        mFFTPoints = new float[128];
        mValueAnimators = new ValueAnimator[32];
        for (int i = 0; i < 32; i++) {
            final int j = i * 4 + 1;
            mValueAnimators[i] = new ValueAnimator();
            mValueAnimators[i].setDuration(128);
            mValueAnimators[i].addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFFTPoints[j] = (float) animation.getAnimatedValue();
                    postInvalidate();
                }
            });
        }
    }

    private void updateViewVisibility() {
        final int curVis = getVisibility();
        final int newVis = mStatusBarState != StatusBarState.SHADE &&
                mVisualizerEnabled ? View.VISIBLE : View.GONE;
        if (curVis != newVis) {
            setVisibility(newVis);
            checkStateChanged();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(TunerService.class).addTunable(this, LOCKSCREEN_VISUALIZER_ENABLED);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(TunerService.class).removeTunable(this);
        mCurrentBitmap = null;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (LOCKSCREEN_VISUALIZER_ENABLED.equals(key)) {
            mVisualizerEnabled = newValue == null || Integer.parseInt(newValue) != 0;
            checkStateChanged();
            updateViewVisibility();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        float barUnit = w / 32f;
        float barWidth = barUnit * 8f / 9f;
        barUnit = barWidth + (barUnit - barWidth) * 32f / 31f;
        mPaint.setStrokeWidth(barWidth);

        for (int i = 0; i < 32; i++) {
            mFFTPoints[i * 4] = mFFTPoints[i * 4 + 2] = i * barUnit + (barWidth / 2);
            mFFTPoints[i * 4 + 1] = h;
            mFFTPoints[i * 4 + 3] = h;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mVisualizer != null) {
            canvas.drawLines(mFFTPoints, mPaint);
        }
    }

    @Override
    public void onGenerated(Palette palette) {
        int color = Color.TRANSPARENT;

        color = palette.getVibrantColor(color);
        if (color == Color.TRANSPARENT) {
            color = palette.getLightVibrantColor(color);
            if (color == Color.TRANSPARENT) {
                color = palette.getDarkVibrantColor(color);
            }
        }

        setColor(color);
    }

    public void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            if (DEBUG) {
                Log.i(TAG, "setDozing() called with dozing = [" + dozing + "]");
            }
            mDozing = dozing;
            checkStateChanged();
        }
    }

    public void setOccluded(boolean occluded) {
        if (mOccluded != occluded) {
            if (DEBUG) {
                Log.i(TAG, "setOccluded() called with occluded = [" + occluded + "]");
            }
            mOccluded = occluded;
            checkStateChanged();
        }
    }

    public void setPlaying(boolean playing) {
        if (mPlaying != playing) {
            if (DEBUG) {
                Log.i(TAG, "setPlaying() called with playing = [" + playing + "]");
            }
            mPlaying = playing;
            checkStateChanged();
        }
    }

    public void setPowerSaveMode(boolean powerSaveMode) {
        if (mPowerSaveMode != powerSaveMode) {
            if (DEBUG) {
                Log.i(TAG, "setPowerSaveMode() called with powerSaveMode = [" + powerSaveMode + "]");
            }
            mPowerSaveMode = powerSaveMode;
            checkStateChanged();
        }
    }

    public void setVisible(boolean visible) {
        if (mVisible != visible) {
            if (DEBUG) {
                Log.i(TAG, "setVisible() called with visible = [" + visible + "]");
            }
            mVisible = visible;
            checkStateChanged();
        }
    }

    public void setStatusBarState(int statusBarState) {
        if (mStatusBarState != statusBarState) {
            mStatusBarState = statusBarState;
            updateViewVisibility();
        }
    }

    public void setBitmap(Bitmap bitmap) {
        if (mCurrentBitmap == bitmap) {
            return;
        }
        mCurrentBitmap = bitmap;
        if (bitmap != null) {
            Palette.generateAsync(bitmap, this);
        } else {
            setColor(Color.TRANSPARENT);
        }
    }

    private void setColor(int color) {
        if (color == Color.TRANSPARENT) {
            color = Color.WHITE;
        }

        color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color));

        if (mColor != color) {
            mColor = color;

            if (mVisualizer != null) {
                if (mVisualizerColorAnimator != null) {
                    mVisualizerColorAnimator.cancel();
                }

                mVisualizerColorAnimator = ObjectAnimator.ofArgb(mPaint, "color",
                        mPaint.getColor(), mColor);
                mVisualizerColorAnimator.setStartDelay(600);
                mVisualizerColorAnimator.setDuration(1200);
                mVisualizerColorAnimator.start();
            } else {
                mPaint.setColor(mColor);
            }
        }
    }

    private void checkStateChanged() {
        if (getVisibility() == View.VISIBLE && mVisible && mPlaying && !mDozing && !mPowerSaveMode
                && mVisualizerEnabled && !mOccluded) {
            if (!mDisplaying) {
                mDisplaying = true;
                AsyncTask.execute(mLinkVisualizer);
                animate()
                        .alpha(1f)
                        .withEndAction(null)
                        .setDuration(800);
            }
        } else {
            if (mDisplaying) {
                mDisplaying = false;
                if (mVisible) {
                    animate()
                            .alpha(0f)
                            .withEndAction(mAsyncUnlinkVisualizer)
                            .setDuration(600);
                } else {
                    animate().
                            alpha(0f)
                            .withEndAction(mAsyncUnlinkVisualizer)
                            .setDuration(0);
                }
            }
        }
    }
}
