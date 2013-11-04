/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Atlas;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.GraphicBuffer;
import android.view.IAssetAtlas;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This service is responsible for packing preloaded bitmaps into a single
 * atlas texture. The resulting texture can be shared across processes to
 * reduce overall memory usage.
 *
 * @hide
 */
public class AssetAtlasService extends IAssetAtlas.Stub {
    /**
     * Name of the <code>AssetAtlasService</code>.
     */
    public static final String ASSET_ATLAS_SERVICE = "assetatlas";

    private static final String LOG_TAG = "Atlas";

    // Turns debug logs on/off. Debug logs are kept to a minimum and should
    // remain on to diagnose issues
    private static final boolean DEBUG_ATLAS = true;

    // When set to true the content of the atlas will be saved to disk
    // in /data/system/atlas.png. The shared GraphicBuffer may be empty
    private static final boolean DEBUG_ATLAS_TEXTURE = false;

    // Minimum size in pixels to consider for the resulting texture
    private static final int MIN_SIZE = 768;
    // Maximum size in pixels to consider for the resulting texture
    private static final int MAX_SIZE = 2048;
    // Increment in number of pixels between size variants when looking
    // for the best texture dimensions
    private static final int STEP = 64;

    // This percentage of the total number of pixels represents the minimum
    // number of pixels we want to be able to pack in the atlas
    private static final float PACKING_THRESHOLD = 0.8f;

    // Defines the number of int fields used to represent a single entry
    // in the atlas map. This number defines the size of the array returned
    // by the getMap(). See the mAtlasMap field for more information
    private static final int ATLAS_MAP_ENTRY_FIELD_COUNT = 4;

    // Specifies how our GraphicBuffer will be used. To get proper swizzling
    // the buffer will be written to using OpenGL (from JNI) so we can leave
    // the software flag set to "never"
    private static final int GRAPHIC_BUFFER_USAGE = GraphicBuffer.USAGE_SW_READ_NEVER |
            GraphicBuffer.USAGE_SW_WRITE_NEVER | GraphicBuffer.USAGE_HW_TEXTURE;

    // This boolean is set to true if an atlas was successfully
    // computed and rendered
    private final AtomicBoolean mAtlasReady = new AtomicBoolean(false);

    private final Context mContext;

    // Version name of the current build, used to identify changes to assets list
    private final String mVersionName;

    // Holds the atlas' data. This buffer can be mapped to
    // OpenGL using an EGLImage
    private GraphicBuffer mBuffer;

    // Describes how bitmaps are placed in the atlas. Each bitmap is
    // represented by several entries in the array:
    // int0: SkBitmap*, the native bitmap object
    // int1: x position
    // int2: y position
    // int3: rotated, 1 if the bitmap must be rotated, 0 otherwise
    // NOTE: This will need to be handled differently to support 64 bit pointers
    private int[] mAtlasMap;

    /**
     * Creates a new service. Upon creating, the service will gather the list of
     * assets to consider for packing into the atlas and spawn a new thread to
     * start the packing work.
     *
     * @param context The context giving access to preloaded resources
     */
    public AssetAtlasService(Context context) {
        mContext = context;
        mVersionName = queryVersionName(context);

        ArrayList<Bitmap> bitmaps = new ArrayList<Bitmap>(300);
        int totalPixelCount = 0;

        // We only care about drawables that hold bitmaps
        final Resources resources = context.getResources();
        final LongSparseArray<Drawable.ConstantState> drawables = resources.getPreloadedDrawables();

        final int count = drawables.size();
        for (int i = 0; i < count; i++) {
            final Bitmap bitmap = drawables.valueAt(i).getBitmap();
            if (bitmap != null && bitmap.getConfig() == Bitmap.Config.ARGB_8888) {
                bitmaps.add(bitmap);
                totalPixelCount += bitmap.getWidth() * bitmap.getHeight();
            }
        }

        // Our algorithms perform better when the bitmaps are first sorted
        // The comparator will sort the bitmap by width first, then by height
        Collections.sort(bitmaps, new Comparator<Bitmap>() {
            @Override
            public int compare(Bitmap b1, Bitmap b2) {
                if (b1.getWidth() == b2.getWidth()) {
                    return b2.getHeight() - b1.getHeight();
                }
                return b2.getWidth() - b1.getWidth();
            }
        });

        // Kick off the packing work on a worker thread
        new Thread(new Renderer(bitmaps, totalPixelCount)).start();
    }

    /**
     * Queries the version name stored in framework's AndroidManifest.
     * The version name can be used to identify possible changes to
     * framework resources.
     *
     * @see #getBuildIdentifier(String)
     */
    private static String queryVersionName(Context context) {
        try {
            String packageName = context.getPackageName();
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(LOG_TAG, "Could not get package info", e);
        }
        return null;
    }

    /**
     * Callback invoked by the server thread to indicate we can now run
     * 3rd party code.
     */
    public void systemRunning() {
    }

    /**
     * The renderer does all the work:
     */
    private class Renderer implements Runnable {
        private final ArrayList<Bitmap> mBitmaps;
        private final int mPixelCount;

        private int mNativeBitmap;

        // Used for debugging only
        private Bitmap mAtlasBitmap;

        Renderer(ArrayList<Bitmap> bitmaps, int pixelCount) {
            mBitmaps = bitmaps;
            mPixelCount = pixelCount;
        }

        /**
         * 1. On first boot or after every update, brute-force through all the
         *    possible atlas configurations and look for the best one (maximimize
         *    number of packed assets and minimize texture size)
         *    a. If a best configuration was computed, write it out to disk for
         *       future use
         * 2. Read best configuration from disk
         * 3. Compute the packing using the best configuration
         * 4. Allocate a GraphicBuffer
         * 5. Render assets in the buffer
         */
        @Override
        public void run() {
            Configuration config = chooseConfiguration(mBitmaps, mPixelCount, mVersionName);
            if (DEBUG_ATLAS) Log.d(LOG_TAG, "Loaded configuration: " + config);

            if (config != null) {
                mBuffer = GraphicBuffer.create(config.width, config.height,
                        PixelFormat.RGBA_8888, GRAPHIC_BUFFER_USAGE);

                if (mBuffer != null) {
                    Atlas atlas = new Atlas(config.type, config.width, config.height, config.flags);
                    if (renderAtlas(mBuffer, atlas, config.count)) {
                        mAtlasReady.set(true);
                    }
                }
            }
        }

        /**
         * Renders a list of bitmaps into the atlas. The position of each bitmap
         * was decided by the packing algorithm and will be honored by this
         * method. If need be this method will also rotate bitmaps.
         *
         * @param buffer The buffer to render the atlas entries into
         * @param atlas The atlas to pack the bitmaps into
         * @param packCount The number of bitmaps that will be packed in the atlas
         *
         * @return true if the atlas was rendered, false otherwise
         */
        @SuppressWarnings("MismatchedReadAndWriteOfArray")
        private boolean renderAtlas(GraphicBuffer buffer, Atlas atlas, int packCount) {
            // Use a Source blend mode to improve performance, the target bitmap
            // will be zero'd out so there's no need to waste time applying blending
            final Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

            // We always render the atlas into a bitmap. This bitmap is then
            // uploaded into the GraphicBuffer using OpenGL to swizzle the content
            final Canvas canvas = acquireCanvas(buffer.getWidth(), buffer.getHeight());
            if (canvas == null) return false;

            final Atlas.Entry entry = new Atlas.Entry();

            mAtlasMap = new int[packCount * ATLAS_MAP_ENTRY_FIELD_COUNT];
            int[] atlasMap = mAtlasMap;
            int mapIndex = 0;

            boolean result = false;
            try {
                final long startRender = System.nanoTime();
                final int count = mBitmaps.size();

                for (int i = 0; i < count; i++) {
                    final Bitmap bitmap = mBitmaps.get(i);
                    if (atlas.pack(bitmap.getWidth(), bitmap.getHeight(), entry) != null) {
                        // We have more bitmaps to pack than the current configuration
                        // says, we were most likely not able to detect a change in the
                        // list of preloaded drawables, abort and delete the configuration
                        if (mapIndex >= mAtlasMap.length) {
                            deleteDataFile();
                            break;
                        }

                        canvas.save();
                        canvas.translate(entry.x, entry.y);
                        if (entry.rotated) {
                            canvas.translate(bitmap.getHeight(), 0.0f);
                            canvas.rotate(90.0f);
                        }
                        canvas.drawBitmap(bitmap, 0.0f, 0.0f, null);
                        canvas.restore();

                        atlasMap[mapIndex++] = bitmap.mNativeBitmap;
                        atlasMap[mapIndex++] = entry.x;
                        atlasMap[mapIndex++] = entry.y;
                        atlasMap[mapIndex++] = entry.rotated ? 1 : 0;
                    }
                }

                final long endRender = System.nanoTime();
                if (mNativeBitmap != 0) {
                    result = nUploadAtlas(buffer, mNativeBitmap);
                }

                final long endUpload = System.nanoTime();
                if (DEBUG_ATLAS) {
                    float renderDuration = (endRender - startRender) / 1000.0f / 1000.0f;
                    float uploadDuration = (endUpload - endRender) / 1000.0f / 1000.0f;
                    Log.d(LOG_TAG, String.format("Rendered atlas in %.2fms (%.2f+%.2fms)",
                            renderDuration + uploadDuration, renderDuration, uploadDuration));
                }

            } finally {
                releaseCanvas(canvas);
            }

            return result;
        }

        /**
         * Returns a Canvas for the specified buffer. If {@link #DEBUG_ATLAS_TEXTURE}
         * is turned on, the returned Canvas will render into a local bitmap that
         * will then be saved out to disk for debugging purposes.
         * @param width
         * @param height
         */
        private Canvas acquireCanvas(int width, int height) {
            if (DEBUG_ATLAS_TEXTURE) {
                mAtlasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                return new Canvas(mAtlasBitmap);
            } else {
                Canvas canvas = new Canvas();
                mNativeBitmap = nAcquireAtlasCanvas(canvas, width, height);
                return canvas;
            }
        }

        /**
         * Releases the canvas used to render into the buffer. Calling this method
         * will release any resource previously acquired. If {@link #DEBUG_ATLAS_TEXTURE}
         * is turend on, calling this method will write the content of the atlas
         * to disk in /data/system/atlas.png for debugging.
         */
        private void releaseCanvas(Canvas canvas) {
            if (DEBUG_ATLAS_TEXTURE) {
                canvas.setBitmap(null);

                File systemDirectory = new File(Environment.getDataDirectory(), "system");
                File dataFile = new File(systemDirectory, "atlas.png");

                try {
                    FileOutputStream out = new FileOutputStream(dataFile);
                    mAtlasBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                } catch (FileNotFoundException e) {
                    // Ignore
                } catch (IOException e) {
                    // Ignore
                }

                mAtlasBitmap.recycle();
                mAtlasBitmap = null;
            } else {
                nReleaseAtlasCanvas(canvas, mNativeBitmap);
            }
        }
    }

    private static native int nAcquireAtlasCanvas(Canvas canvas, int width, int height);
    private static native void nReleaseAtlasCanvas(Canvas canvas, int bitmap);
    private static native boolean nUploadAtlas(GraphicBuffer buffer, int bitmap);

    @Override
    public boolean isCompatible(int ppid) {
        return ppid == android.os.Process.myPpid();
    }

    @Override
    public GraphicBuffer getBuffer() throws RemoteException {
        return mAtlasReady.get() ? mBuffer : null;
    }

    @Override
    public int[] getMap() throws RemoteException {
        return mAtlasReady.get() ? mAtlasMap : null;
    }

    /**
     * Finds the best atlas configuration to pack the list of supplied bitmaps.
     * This method takes advantage of multi-core systems by spawning a number
     * of threads equal to the number of available cores.
     */
    private static Configuration computeBestConfiguration(
            ArrayList<Bitmap> bitmaps, int pixelCount) {
        if (DEBUG_ATLAS) Log.d(LOG_TAG, "Computing best atlas configuration...");

        long begin = System.nanoTime();
        List<WorkerResult> results = Collections.synchronizedList(new ArrayList<WorkerResult>());

        // Don't bother with an extra thread if there's only one processor
        int cpuCount = Runtime.getRuntime().availableProcessors();
        if (cpuCount == 1) {
            new ComputeWorker(MIN_SIZE, MAX_SIZE, STEP, bitmaps, pixelCount, results, null).run();
        } else {
            int start = MIN_SIZE;
            int end = MAX_SIZE - (cpuCount - 1) * STEP;
            int step = STEP * cpuCount;

            final CountDownLatch signal = new CountDownLatch(cpuCount);

            for (int i = 0; i < cpuCount; i++, start += STEP, end += STEP) {
                ComputeWorker worker = new ComputeWorker(start, end, step,
                        bitmaps, pixelCount, results, signal);
                new Thread(worker, "Atlas Worker #" + (i + 1)).start();
            }

            try {
                signal.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.w(LOG_TAG, "Could not complete configuration computation");
                return null;
            }
        }

        // Maximize the number of packed bitmaps, minimize the texture size
        Collections.sort(results, new Comparator<WorkerResult>() {
            @Override
            public int compare(WorkerResult r1, WorkerResult r2) {
                int delta = r2.count - r1.count;
                if (delta != 0) return delta;
                return r1.width * r1.height - r2.width * r2.height;
            }
        });

        if (DEBUG_ATLAS) {
            float delay = (System.nanoTime() - begin) / 1000.0f / 1000.0f / 1000.0f;
            Log.d(LOG_TAG, String.format("Found best atlas configuration in %.2fs", delay));
        }

        // PRELOAD_RESOURCES may have been disabled!
        if (results.size() == 0) return null;

        WorkerResult result = results.get(0);
        return new Configuration(result.type, result.width, result.height, result.count);
    }

    /**
     * Returns the path to the file containing the best computed
     * atlas configuration.
     */
    private static File getDataFile() {
        File systemDirectory = new File(Environment.getDataDirectory(), "system");
        return new File(systemDirectory, "framework_atlas.config");
    }

    private static void deleteDataFile() {
        Log.w(LOG_TAG, "Current configuration inconsistent with assets list");
        if (!getDataFile().delete()) {
            Log.w(LOG_TAG, "Could not delete the current configuration");
        }
    }

    private File getFrameworkResourcesFile() {
        return new File(mContext.getApplicationInfo().sourceDir);
    }

    /**
     * Returns the best known atlas configuration. This method will either
     * read the configuration from disk or start a brute-force search
     * and save the result out to disk.
     */
    private Configuration chooseConfiguration(ArrayList<Bitmap> bitmaps, int pixelCount,
            String versionName) {
        Configuration config = null;

        final File dataFile = getDataFile();
        if (dataFile.exists()) {
            config = readConfiguration(dataFile, versionName);
        }

        if (config == null) {
            config = computeBestConfiguration(bitmaps, pixelCount);
            if (config != null) writeConfiguration(config, dataFile, versionName);
        }

        return config;
    }

    /**
     * Writes the specified atlas configuration to the specified file.
     */
    private void writeConfiguration(Configuration config, File file, String versionName) {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
            writer.write(getBuildIdentifier(versionName));
            writer.newLine();
            writer.write(config.type.toString());
            writer.newLine();
            writer.write(String.valueOf(config.width));
            writer.newLine();
            writer.write(String.valueOf(config.height));
            writer.newLine();
            writer.write(String.valueOf(config.count));
            writer.newLine();
            writer.write(String.valueOf(config.flags));
            writer.newLine();
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "Could not write " + file, e);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not write " + file, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Reads an atlas configuration from the specified file. This method
     * returns null if an error occurs or if the configuration is invalid.
     */
    private Configuration readConfiguration(File file, String versionName) {
        BufferedReader reader = null;
        Configuration config = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));

            if (checkBuildIdentifier(reader, versionName)) {
                Atlas.Type type = Atlas.Type.valueOf(reader.readLine());
                int width = readInt(reader, MIN_SIZE, MAX_SIZE);
                int height = readInt(reader, MIN_SIZE, MAX_SIZE);
                int count = readInt(reader, 0, Integer.MAX_VALUE);
                int flags = readInt(reader, Integer.MIN_VALUE, Integer.MAX_VALUE);

                config = new Configuration(type, width, height, count, flags);
            }
        } catch (IllegalArgumentException e) {
            Log.w(LOG_TAG, "Invalid parameter value in " + file, e);
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "Could not read " + file, e);
        } catch (IOException e) {
            Log.w(LOG_TAG, "Could not read " + file, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        return config;
    }

    private static int readInt(BufferedReader reader, int min, int max) throws IOException {
        return Math.max(min, Math.min(max, Integer.parseInt(reader.readLine())));
    }

    /**
     * Compares the next line in the specified buffered reader to the current
     * build identifier. Returns whether the two values are equal.
     *
     * @see #getBuildIdentifier(String)
     */
    private boolean checkBuildIdentifier(BufferedReader reader, String versionName)
            throws IOException {
        String deviceBuildId = getBuildIdentifier(versionName);
        String buildId = reader.readLine();
        return deviceBuildId.equals(buildId);
    }

    /**
     * Returns an identifier for the current build that can be used to detect
     * likely changes to framework resources. The build identifier is made of
     * several distinct values:
     *
     * build fingerprint/framework version name/file size of framework resources apk
     *
     * Only the build fingerprint should be necessary on user builds but
     * the other values are useful to detect changes on eng builds during
     * development.
     *
     * This identifier does not attempt to be exact: a new identifier does not
     * necessarily mean the preloaded drawables have changed. It is important
     * however that whenever the list of preloaded drawables changes, this
     * identifier changes as well.
     *
     * @see #checkBuildIdentifier(java.io.BufferedReader, String)
     */
    private String getBuildIdentifier(String versionName) {
        return SystemProperties.get("ro.build.fingerprint", "") + '/' + versionName + '/' +
                String.valueOf(getFrameworkResourcesFile().length());
    }

    /**
     * Atlas configuration. Specifies the algorithm, dimensions and flags to use.
     */
    private static class Configuration {
        final Atlas.Type type;
        final int width;
        final int height;
        final int count;
        final int flags;

        Configuration(Atlas.Type type, int width, int height, int count) {
            this(type, width, height, count, Atlas.FLAG_DEFAULTS);
        }

        Configuration(Atlas.Type type, int width, int height, int count, int flags) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.count = count;
            this.flags = flags;
        }

        @Override
        public String toString() {
            return type.toString() + " (" + width + "x" + height + ") flags=0x" +
                    Integer.toHexString(flags) + " count=" + count;
        }
    }

    /**
     * Used during the brute-force search to gather information about each
     * variant of the packing algorithm.
     */
    private static class WorkerResult {
        Atlas.Type type;
        int width;
        int height;
        int count;

        WorkerResult(Atlas.Type type, int width, int height, int count) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.count = count;
        }

        @Override
        public String toString() {
            return String.format("%s %dx%d", type.toString(), width, height);
        }
    }

    /**
     * A compute worker will try a finite number of variations of the packing
     * algorithms and save the results in a supplied list.
     */
    private static class ComputeWorker implements Runnable {
        private final int mStart;
        private final int mEnd;
        private final int mStep;
        private final List<Bitmap> mBitmaps;
        private final List<WorkerResult> mResults;
        private final CountDownLatch mSignal;
        private final int mThreshold;

        /**
         * Creates a new compute worker to brute-force through a range of
         * packing algorithms variants.
         *
         * @param start The minimum texture width to try
         * @param end The maximum texture width to try
         * @param step The number of pixels to increment the texture width by at each step
         * @param bitmaps The list of bitmaps to pack in the atlas
         * @param pixelCount The total number of pixels occupied by the list of bitmaps
         * @param results The list of results in which to save the brute-force search results
         * @param signal Latch to decrement when this worker is done, may be null
         */
        ComputeWorker(int start, int end, int step, List<Bitmap> bitmaps, int pixelCount,
                List<WorkerResult> results, CountDownLatch signal) {
            mStart = start;
            mEnd = end;
            mStep = step;
            mBitmaps = bitmaps;
            mResults = results;
            mSignal = signal;

            // Minimum number of pixels we want to be able to pack
            int threshold = (int) (pixelCount * PACKING_THRESHOLD);
            // Make sure we can find at least one configuration
            while (threshold > MAX_SIZE * MAX_SIZE) {
                threshold >>= 1;
            }
            mThreshold = threshold;
        }

        @Override
        public void run() {
            if (DEBUG_ATLAS) Log.d(LOG_TAG, "Running " + Thread.currentThread().getName());

            Atlas.Entry entry = new Atlas.Entry();
            for (Atlas.Type type : Atlas.Type.values()) {
                for (int width = mStart; width < mEnd; width += mStep) {
                    for (int height = MIN_SIZE; height < MAX_SIZE; height += STEP) {
                        // If the atlas is not big enough, skip it
                        if (width * height <= mThreshold) continue;

                        final int count = packBitmaps(type, width, height, entry);
                        if (count > 0) {
                            mResults.add(new WorkerResult(type, width, height, count));
                            // If we were able to pack everything let's stop here
                            // Increasing the height further won't make things better
                            if (count == mBitmaps.size()) {
                                break;
                            }
                        }
                    }
                }
            }

            if (mSignal != null) {
                mSignal.countDown();
            }
        }

        private int packBitmaps(Atlas.Type type, int width, int height, Atlas.Entry entry) {
            int total = 0;
            Atlas atlas = new Atlas(type, width, height);

            final int count = mBitmaps.size();
            for (int i = 0; i < count; i++) {
                final Bitmap bitmap = mBitmaps.get(i);
                if (atlas.pack(bitmap.getWidth(), bitmap.getHeight(), entry) != null) {
                    total++;
                }
            }

            return total;
        }
    }
}
