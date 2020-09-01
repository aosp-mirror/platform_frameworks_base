/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.surfacecomposition;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * This activity is designed to measure peformance scores of Android surfaces.
 * It can work in two modes. In first mode functionality of this activity is
 * invoked from Cts test (SurfaceCompositionTest). This activity can also be
 * used in manual mode as a normal app. Different pixel formats are supported.
 *
 * measureCompositionScore(pixelFormat)
 *   This test measures surface compositor performance which shows how many
 *   surfaces of specific format surface compositor can combine without dropping
 *   frames. We allow one dropped frame per half second.
 *
 * measureAllocationScore(pixelFormat)
 *   This test measures surface allocation/deallocation performance. It shows
 *   how many surface lifecycles (creation, destruction) can be done per second.
 *
 * In manual mode, which activated by pressing button 'Compositor speed' or
 * 'Allocator speed', all possible pixel format are tested and combined result
 * is displayed in text view. Additional system information such as memory
 * status, display size and surface format is also displayed and regulary
 * updated.
 */
public class SurfaceCompositionMeasuringActivity extends Activity implements OnClickListener {
    private final static int MIN_NUMBER_OF_SURFACES = 15;
    private final static int MAX_NUMBER_OF_SURFACES = 40;
    private final static int WARM_UP_ALLOCATION_CYCLES = 2;
    private final static int MEASURE_ALLOCATION_CYCLES = 5;
    private final static int TEST_COMPOSITOR = 1;
    private final static int TEST_ALLOCATION = 2;
    private final static float MIN_REFRESH_RATE_SUPPORTED = 50.0f;

    private final static DecimalFormat DOUBLE_FORMAT = new DecimalFormat("#.00");
    // Possible selection in pixel format selector.
    private final static int[] PIXEL_FORMATS = new int[] {
            PixelFormat.TRANSLUCENT,
            PixelFormat.TRANSPARENT,
            PixelFormat.OPAQUE,
            PixelFormat.RGBA_8888,
            PixelFormat.RGBX_8888,
            PixelFormat.RGB_888,
            PixelFormat.RGB_565,
    };


    private List<CustomSurfaceView> mViews = new ArrayList<CustomSurfaceView>();
    private Button mMeasureCompositionButton;
    private Button mMeasureAllocationButton;
    private Spinner mPixelFormatSelector;
    private TextView mResultView;
    private TextView mSystemInfoView;
    private final Object mLockResumed = new Object();
    private boolean mResumed;

    // Drop one frame per half second.
    private double mRefreshRate;
    private double mTargetFPS;
    private boolean mAndromeda;

    private int mWidth;
    private int mHeight;

    class CompositorScore {
        double mSurfaces;
        double mBandwidth;

        @Override
        public String toString() {
            return DOUBLE_FORMAT.format(mSurfaces) + " surfaces. " +
                    "Bandwidth: " + getReadableMemory((long)mBandwidth) + "/s";
        }
    }

    /**
     * Measure performance score.
     *
     * @return biggest possible number of visible surfaces which surface
     *         compositor can handle.
     */
    public CompositorScore measureCompositionScore(int pixelFormat) {
        waitForActivityResumed();
        //MemoryAccessTask memAccessTask = new MemoryAccessTask();
        //memAccessTask.start();
        // Destroy any active surface.
        configureSurfacesAndWait(0, pixelFormat, false);
        CompositorScore score = new CompositorScore();
        score.mSurfaces = measureCompositionScore(new Measurement(0, 60.0),
                new Measurement(mViews.size() + 1, 0.0f), pixelFormat);
        // Assume 32 bits per pixel.
        score.mBandwidth = score.mSurfaces * mTargetFPS * mWidth * mHeight * 4.0;
        //memAccessTask.stop();
        return score;
    }

    static class AllocationScore {
        double mMedian;
        double mMin;
        double mMax;

        @Override
        public String toString() {
            return DOUBLE_FORMAT.format(mMedian) + " (min:" + DOUBLE_FORMAT.format(mMin) +
                    ", max:" + DOUBLE_FORMAT.format(mMax) + ") surface allocations per second";
        }
    }

    public AllocationScore measureAllocationScore(int pixelFormat) {
        waitForActivityResumed();
        AllocationScore score = new AllocationScore();
        for (int i = 0; i < MEASURE_ALLOCATION_CYCLES + WARM_UP_ALLOCATION_CYCLES; ++i) {
            long time1 = System.currentTimeMillis();
            configureSurfacesAndWait(MIN_NUMBER_OF_SURFACES, pixelFormat, false);
            acquireSurfacesCanvas();
            long time2 = System.currentTimeMillis();
            releaseSurfacesCanvas();
            configureSurfacesAndWait(0, pixelFormat, false);
            // Give SurfaceFlinger some time to rebuild the layer stack and release the buffers.
            try {
                Thread.sleep(500);
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
            if (i < WARM_UP_ALLOCATION_CYCLES) {
                // This is warm-up cycles, ignore result so far.
                continue;
            }
            double speed = MIN_NUMBER_OF_SURFACES * 1000.0 / (time2 - time1);
            score.mMedian += speed / MEASURE_ALLOCATION_CYCLES;
            if (i == WARM_UP_ALLOCATION_CYCLES) {
                score.mMin = speed;
                score.mMax = speed;
            } else {
                score.mMin = Math.min(score.mMin, speed);
                score.mMax = Math.max(score.mMax, speed);
            }
        }

        return score;
    }

    public boolean isAndromeda() {
        return mAndromeda;
    }

    @Override
    public void onClick(View view) {
        if (view == mMeasureCompositionButton) {
            doTest(TEST_COMPOSITOR);
        } else if (view == mMeasureAllocationButton) {
            doTest(TEST_ALLOCATION);
        }
    }

    private void doTest(final int test) {
        enableControls(false);
        final int pixelFormat = PIXEL_FORMATS[mPixelFormatSelector.getSelectedItemPosition()];
        new Thread() {
            public void run() {
                final StringBuffer sb = new StringBuffer();
                switch (test) {
                    case TEST_COMPOSITOR: {
                            sb.append("Compositor score:");
                            CompositorScore score = measureCompositionScore(pixelFormat);
                            sb.append("\n    " + getPixelFormatInfo(pixelFormat) + ":" +
                                    score + ".");
                        }
                        break;
                    case TEST_ALLOCATION: {
                            sb.append("Allocation score:");
                            AllocationScore score = measureAllocationScore(pixelFormat);
                            sb.append("\n    " + getPixelFormatInfo(pixelFormat) + ":" +
                                    score + ".");
                        }
                        break;
                }
                runOnUiThreadAndWait(new Runnable() {
                    public void run() {
                        mResultView.setText(sb.toString());
                        enableControls(true);
                        updateSystemInfo(pixelFormat);
                    }
                });
            }
        }.start();
    }

    /**
     * Wait until activity is resumed.
     */
    public void waitForActivityResumed() {
        synchronized (mLockResumed) {
            if (!mResumed) {
                try {
                    mLockResumed.wait(10000);
                } catch (InterruptedException e) {
                }
            }
            if (!mResumed) {
                throw new RuntimeException("Activity was not resumed");
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Detect Andromeda devices by having free-form window management feature.
        mAndromeda = getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);
        detectRefreshRate();

        // To layouts in parent. First contains list of Surfaces and second
        // controls. Controls stay on top.
        RelativeLayout rootLayout = new RelativeLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        CustomLayout layout = new CustomLayout(this);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Rect rect = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);
        mWidth = rect.right;
        mHeight = rect.bottom;
        long maxMemoryPerSurface = roundToNextPowerOf2(mWidth) * roundToNextPowerOf2(mHeight) * 4;
        // Use 75% of available memory.
        int surfaceCnt = (int)((getMemoryInfo().availMem * 3) / (4 * maxMemoryPerSurface));
        if (surfaceCnt < MIN_NUMBER_OF_SURFACES) {
            throw new RuntimeException("Not enough memory to allocate " +
                    MIN_NUMBER_OF_SURFACES + " surfaces.");
        }
        if (surfaceCnt > MAX_NUMBER_OF_SURFACES) {
            surfaceCnt = MAX_NUMBER_OF_SURFACES;
        }

        LinearLayout controlLayout = new LinearLayout(this);
        controlLayout.setOrientation(LinearLayout.VERTICAL);
        controlLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mMeasureCompositionButton = createButton("Compositor speed.", controlLayout);
        mMeasureAllocationButton = createButton("Allocation speed", controlLayout);

        String[] pixelFomats = new String[PIXEL_FORMATS.length];
        for (int i = 0; i < pixelFomats.length; ++i) {
            pixelFomats[i] = getPixelFormatInfo(PIXEL_FORMATS[i]);
        }
        mPixelFormatSelector = new Spinner(this);
        ArrayAdapter<String> pixelFormatSelectorAdapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, pixelFomats);
        pixelFormatSelectorAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mPixelFormatSelector.setAdapter(pixelFormatSelectorAdapter);
        mPixelFormatSelector.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        controlLayout.addView(mPixelFormatSelector);

        mResultView = new TextView(this);
        mResultView.setBackgroundColor(0);
        mResultView.setText("Press button to start test.");
        mResultView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        controlLayout.addView(mResultView);

        mSystemInfoView = new TextView(this);
        mSystemInfoView.setBackgroundColor(0);
        mSystemInfoView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        controlLayout.addView(mSystemInfoView);

        for (int i = 0; i < surfaceCnt; ++i) {
            CustomSurfaceView view = new CustomSurfaceView(this, "Surface:" + i);
            // Create all surfaces overlapped in order to prevent SurfaceFlinger
            // to filter out surfaces by optimization in case surface is opaque.
            // In case surface is transparent it will be drawn anyway. Note that first
            // surface covers whole screen and must stand below other surfaces. Z order of
            // layers is not predictable and there is only one way to force first
            // layer to be below others is to mark it as media and all other layers
            // to mark as media overlay.
            if (i == 0) {
                view.setLayoutParams(new CustomLayout.LayoutParams(0, 0, mWidth, mHeight));
                view.setZOrderMediaOverlay(false);
            } else {
                // Z order of other layers is not predefined so make offset on x and reverse
                // offset on y to make sure that surface is visible in any layout.
                int x = i;
                int y = (surfaceCnt - i);
                view.setLayoutParams(new CustomLayout.LayoutParams(x, y, x + mWidth, y + mHeight));
                view.setZOrderMediaOverlay(true);
            }
            view.setVisibility(View.INVISIBLE);
            layout.addView(view);
            mViews.add(view);
        }

        rootLayout.addView(layout);
        rootLayout.addView(controlLayout);

        setContentView(rootLayout);
    }

    private Button createButton(String caption, LinearLayout layout) {
        Button button = new Button(this);
        button.setText(caption);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        button.setOnClickListener(this);
        layout.addView(button);
        return button;
    }

    private void enableControls(boolean enabled) {
        mMeasureCompositionButton.setEnabled(enabled);
        mMeasureAllocationButton.setEnabled(enabled);
        mPixelFormatSelector.setEnabled(enabled);
    }

    @Override
    protected void onResume() {
        super.onResume();

        updateSystemInfo(PixelFormat.UNKNOWN);

        synchronized (mLockResumed) {
            mResumed = true;
            mLockResumed.notifyAll();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        synchronized (mLockResumed) {
            mResumed = false;
        }
    }

    class Measurement {
        Measurement(int surfaceCnt, double fps) {
            mSurfaceCnt = surfaceCnt;
            mFPS = fps;
        }

        public final int mSurfaceCnt;
        public final double mFPS;
    }

    private double measureCompositionScore(Measurement ok, Measurement fail, int pixelFormat) {
        if (ok.mSurfaceCnt + 1 == fail.mSurfaceCnt) {
            // Interpolate result.
            double fraction = (mTargetFPS - fail.mFPS) / (ok.mFPS - fail.mFPS);
            return ok.mSurfaceCnt + fraction;
        }

        int medianSurfaceCnt = (ok.mSurfaceCnt + fail.mSurfaceCnt) / 2;
        Measurement median = new Measurement(medianSurfaceCnt,
                measureFPS(medianSurfaceCnt, pixelFormat));

        if (median.mFPS >= mTargetFPS) {
            return measureCompositionScore(median, fail, pixelFormat);
        } else {
            return measureCompositionScore(ok, median, pixelFormat);
        }
    }

    private double measureFPS(int surfaceCnt, int pixelFormat) {
        configureSurfacesAndWait(surfaceCnt, pixelFormat, true);
        // At least one view is visible and it is enough to update only
        // one overlapped surface in order to force SurfaceFlinger to send
        // all surfaces to compositor.
        double fps = mViews.get(0).measureFPS(mRefreshRate * 0.8, mRefreshRate * 0.999);

        // Make sure that surface configuration was not changed.
        validateSurfacesNotChanged();

        return fps;
    }

    private void waitForSurfacesConfigured(final int pixelFormat) {
        for (int i = 0; i < mViews.size(); ++i) {
            CustomSurfaceView view = mViews.get(i);
            if (view.getVisibility() == View.VISIBLE) {
                view.waitForSurfaceReady();
            } else {
                view.waitForSurfaceDestroyed();
            }
        }
        runOnUiThreadAndWait(new Runnable() {
            @Override
            public void run() {
                updateSystemInfo(pixelFormat);
            }
        });
    }

    private void validateSurfacesNotChanged() {
        for (int i = 0; i < mViews.size(); ++i) {
            CustomSurfaceView view = mViews.get(i);
            view.validateSurfaceNotChanged();
        }
    }

    private void configureSurfaces(int surfaceCnt, int pixelFormat, boolean invalidate) {
        for (int i = 0; i < mViews.size(); ++i) {
            CustomSurfaceView view = mViews.get(i);
            if (i < surfaceCnt) {
                view.setMode(pixelFormat, invalidate);
                view.setVisibility(View.VISIBLE);
            } else {
                view.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void configureSurfacesAndWait(final int surfaceCnt, final int pixelFormat,
            final boolean invalidate) {
        runOnUiThreadAndWait(new Runnable() {
            @Override
            public void run() {
                configureSurfaces(surfaceCnt, pixelFormat, invalidate);
            }
        });
        waitForSurfacesConfigured(pixelFormat);
    }

    private void acquireSurfacesCanvas() {
        for (int i = 0; i < mViews.size(); ++i) {
            CustomSurfaceView view = mViews.get(i);
            view.acquireCanvas();
        }
    }

    private void releaseSurfacesCanvas() {
        for (int i = 0; i < mViews.size(); ++i) {
            CustomSurfaceView view = mViews.get(i);
            view.releaseCanvas();
        }
    }

    private static String getReadableMemory(long bytes) {
        long unit = 1024;
        if (bytes < unit) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp),
                "KMGTPE".charAt(exp-1));
    }

    private MemoryInfo getMemoryInfo() {
        ActivityManager activityManager = (ActivityManager)
                getSystemService(ACTIVITY_SERVICE);
        MemoryInfo memInfo = new MemoryInfo();
        activityManager.getMemoryInfo(memInfo);
        return memInfo;
    }

    private void updateSystemInfo(int pixelFormat) {
        int visibleCnt = 0;
        for (int i = 0; i < mViews.size(); ++i) {
            if (mViews.get(i).getVisibility() == View.VISIBLE) {
                ++visibleCnt;
            }
        }

        MemoryInfo memInfo = getMemoryInfo();
        String platformName = mAndromeda ? "Andromeda" : "Android";
        String info = platformName + ": available " +
                getReadableMemory(memInfo.availMem) + " from " +
                getReadableMemory(memInfo.totalMem) + ".\nVisible " +
                visibleCnt + " from " + mViews.size() + " " +
                getPixelFormatInfo(pixelFormat) + " surfaces.\n" +
                "View size: " + mWidth + "x" + mHeight +
                ". Refresh rate: " + DOUBLE_FORMAT.format(mRefreshRate) + ".";
        mSystemInfoView.setText(info);
    }

    private void detectRefreshRate() {
        mRefreshRate = getDisplay().getRefreshRate();
        if (mRefreshRate < MIN_REFRESH_RATE_SUPPORTED)
            throw new RuntimeException("Unsupported display refresh rate: " + mRefreshRate);
        mTargetFPS = mRefreshRate - 2.0f;
    }

    private int roundToNextPowerOf2(int value) {
        --value;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        return value + 1;
    }

    public static String getPixelFormatInfo(int pixelFormat) {
        switch (pixelFormat) {
        case PixelFormat.TRANSLUCENT:
            return "TRANSLUCENT";
        case PixelFormat.TRANSPARENT:
            return "TRANSPARENT";
        case PixelFormat.OPAQUE:
            return "OPAQUE";
        case PixelFormat.RGBA_8888:
            return "RGBA_8888";
        case PixelFormat.RGBX_8888:
            return "RGBX_8888";
        case PixelFormat.RGB_888:
            return "RGB_888";
        case PixelFormat.RGB_565:
            return "RGB_565";
        default:
            return "PIX.FORMAT:" + pixelFormat;
        }
    }

    /**
     * A helper that executes a task in the UI thread and waits for its completion.
     *
     * @param task - task to execute.
     */
    private void runOnUiThreadAndWait(Runnable task) {
        new UIExecutor(task);
    }

    class UIExecutor implements Runnable {
        private final Object mLock = new Object();
        private Runnable mTask;
        private boolean mDone = false;

        UIExecutor(Runnable task) {
            mTask = task;
            mDone = false;
            runOnUiThread(this);
            synchronized (mLock) {
                while (!mDone) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public void run() {
            mTask.run();
            synchronized (mLock) {
                mDone = true;
                mLock.notify();
            }
        }
    }
}
