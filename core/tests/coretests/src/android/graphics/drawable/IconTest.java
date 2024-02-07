/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics.drawable;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.IUriGrantsManager;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Region;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.frameworks.coretests.R;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class IconTest {
    public static final String TAG = IconTest.class.getSimpleName();
    private Context mContext;

    public static void L(String s, Object... parts) {
        Log.d(TAG, (parts.length == 0) ? s : String.format(s, parts));
    }

    private Context getContext() {
        return mContext;
    }

    @Before
    public void setup() {
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testWithBitmap() throws Exception {
        final Bitmap bm1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        final Bitmap bm2 = Bitmap.createBitmap(100, 200, Bitmap.Config.RGB_565);
        final Bitmap bm3 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();

        final Canvas can1 = new Canvas(bm1);
        can1.drawColor(0xFFFF0000);
        final Canvas can2 = new Canvas(bm2);
        can2.drawColor(0xFF00FF00);

        final Icon im1 = Icon.createWithBitmap(bm1);
        final Icon im2 = Icon.createWithBitmap(bm2);
        final Icon im3 = Icon.createWithBitmap(bm3);

        final Drawable draw1 = im1.loadDrawable(mContext);
        final Drawable draw2 = im2.loadDrawable(mContext);
        final Drawable draw3 = im3.loadDrawable(mContext);

        final Bitmap test1 = Bitmap.createBitmap(draw1.getIntrinsicWidth(),
                draw1.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        final Bitmap test2 = Bitmap.createBitmap(draw2.getIntrinsicWidth(),
                draw2.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        final Bitmap test3 = Bitmap.createBitmap(draw3.getIntrinsicWidth(),
                draw3.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);

        draw1.setBounds(0, 0, draw1.getIntrinsicWidth(), draw1.getIntrinsicHeight());
        draw1.draw(new Canvas(test1));

        draw2.setBounds(0, 0, draw2.getIntrinsicWidth(), draw2.getIntrinsicHeight());
        draw2.draw(new Canvas(test2));

        draw3.setBounds(0, 0, draw3.getIntrinsicWidth(), draw3.getIntrinsicHeight());
        draw3.draw(new Canvas(test3));

        final File dir = getContext().getExternalFilesDir(null);
        L("writing temp bitmaps to %s...", dir);

        bm1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap1-original.png")));
        test1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap1-test.png")));
        if (!equalBitmaps(bm1, test1)) {
            findBitmapDifferences(bm1, test1);
            fail("bitmap1 differs, check " + dir);
        }

        bm2.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap2-original.png")));
        test2.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap2-test.png")));
        if (!equalBitmaps(bm2, test2)) {
            findBitmapDifferences(bm2, test2);
            fail("bitmap2 differs, check " + dir);
        }

        bm3.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap3-original.png")));
        test3.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap3-test.png")));
        if (!equalBitmaps(bm3, test3)) {
            findBitmapDifferences(bm3, test3);
            fail("bitmap3 differs, check " + dir);
        }
    }

    @Test
    public void testScaleDownIfNecessary() throws Exception {
        final Bitmap bm = Bitmap.createBitmap(4321, 78, Bitmap.Config.ARGB_8888);
        final Icon ic = Icon.createWithBitmap(bm);
        ic.scaleDownIfNecessary(40, 20);

        assertThat(bm.getWidth()).isEqualTo(4321);
        assertThat(bm.getHeight()).isEqualTo(78);

        assertThat(ic.getBitmap().getWidth()).isLessThan(41);
        assertThat(ic.getBitmap().getHeight()).isLessThan(21);
    }

    @Test
    public void testWithAdaptiveBitmap() throws Exception {
        final Bitmap bm1 = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888);

        final Canvas can1 = new Canvas(bm1);
        can1.drawColor(0xFFFF0000);

        final Icon im1 = Icon.createWithAdaptiveBitmap(bm1);

        final AdaptiveIconDrawable draw1 = (AdaptiveIconDrawable) im1.loadDrawable(mContext);

        final Bitmap test1 = Bitmap.createBitmap(
            (int)(draw1.getIntrinsicWidth() * (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())),
            (int)(draw1.getIntrinsicHeight() * (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())),
            Bitmap.Config.ARGB_8888);

        draw1.setBounds(0, 0,
            (int) (draw1.getIntrinsicWidth() * (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())),
            (int) (draw1.getIntrinsicHeight() * (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction())));
        draw1.draw(new Canvas(test1));

        final File dir = getContext().getExternalFilesDir(null);
        L("writing temp bitmaps to %s...", dir);

        bm1.compress(Bitmap.CompressFormat.PNG, 100,
            new FileOutputStream(new File(dir, "adaptive-bitmap1-original.png")));
        test1.compress(Bitmap.CompressFormat.PNG, 100,
            new FileOutputStream(new File(dir, "adaptive-bitmap1-test.png")));
        if (!equalBitmaps(bm1, test1, draw1.getSafeZone())) {
            findBitmapDifferences(bm1, test1);
            fail("adaptive bitmap1 differs, check " + dir);
        }
    }

    @Test
    public void testWithBitmapResource() throws Exception {
        final Bitmap res1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();

        final Icon im1 = Icon.createWithResource(getContext(), R.drawable.landscape);
        final Drawable draw1 = im1.loadDrawable(mContext);
        final Bitmap test1 = Bitmap.createBitmap(draw1.getIntrinsicWidth(),
                draw1.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        draw1.setBounds(0, 0, test1.getWidth(), test1.getHeight());
        draw1.draw(new Canvas(test1));

        final File dir = getContext().getExternalFilesDir(null);
        res1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "res1-original.png")));
        test1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "res1-test.png")));
        if (!equalBitmaps(res1, test1)) {
            findBitmapDifferences(res1, test1);
            fail("res1 differs, check " + dir);
        }
    }

    /**
     * Icon resource test that ensures we can load and draw non-bitmaps. (In this case,
     * stat_sys_adb is assumed, and asserted, to be a vector drawable.)
     */
    @Test
    public void testWithStatSysAdbResource() throws Exception {
        // establish reference bitmap
        final float dp = getContext().getResources().getDisplayMetrics().density;
        final int stat_sys_adb_width = (int) (24 * dp);
        final int stat_sys_adb_height = (int) (24 * dp);

        final Drawable stat_sys_adb = getContext()
                .getDrawable(com.android.internal.R.drawable.stat_sys_adb);
        if (!(stat_sys_adb instanceof VectorDrawable)) {
            fail("stat_sys_adb is a " + stat_sys_adb.toString()
                    + ", not a VectorDrawable; stat_sys_adb malformed");
        }

        if (stat_sys_adb.getIntrinsicWidth() != stat_sys_adb_width) {
            fail("intrinsic width of stat_sys_adb is not 24dp; stat_sys_adb malformed");
        }
        if (stat_sys_adb.getIntrinsicHeight() != stat_sys_adb_height) {
            fail("intrinsic height of stat_sys_adb is not 24dp; stat_sys_adb malformed");
        }
        final Bitmap referenceBitmap = Bitmap.createBitmap(
                stat_sys_adb_width,
                stat_sys_adb_height,
                Bitmap.Config.ARGB_8888);
        stat_sys_adb.setBounds(0, 0, stat_sys_adb_width, stat_sys_adb_height);
        stat_sys_adb.draw(new Canvas(referenceBitmap));

        final Icon im1 = Icon.createWithResource(getContext(),
                com.android.internal.R.drawable.stat_sys_adb);
        final Drawable draw1 = im1.loadDrawable(getContext());

        assertEquals(stat_sys_adb.getIntrinsicWidth(), draw1.getIntrinsicWidth());
        assertEquals(stat_sys_adb.getIntrinsicHeight(), draw1.getIntrinsicHeight());
        assertEquals(im1.getResId(), com.android.internal.R.drawable.stat_sys_adb);

        final Bitmap test1 = Bitmap.createBitmap(
                draw1.getIntrinsicWidth(),
                draw1.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        draw1.setBounds(0, 0, test1.getWidth(), test1.getHeight());
        draw1.draw(new Canvas(test1));

        final File dir = getContext().getExternalFilesDir(null);
        test1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "testWithVectorDrawableResource-test.png")));
        if (!equalBitmaps(referenceBitmap, test1)) {
            findBitmapDifferences(referenceBitmap, test1);
            fail("testWithFile: file1 differs, check " + dir);
        }
    }

    @Test
    public void testWithFile() throws Exception {
        final Bitmap bit1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();
        final File dir = getContext().getExternalFilesDir(null);
        final File file1 = new File(dir, "file1-original.png");
        bit1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(file1));

        final Icon im1 = Icon.createWithFilePath(file1.toString());
        final Drawable draw1 = im1.loadDrawable(mContext);
        final Bitmap test1 = Bitmap.createBitmap(draw1.getIntrinsicWidth(),
                draw1.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        draw1.setBounds(0, 0, test1.getWidth(), test1.getHeight());
        draw1.draw(new Canvas(test1));

        test1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "file1-test.png")));
        if (!equalBitmaps(bit1, test1)) {
            findBitmapDifferences(bit1, test1);
            fail("testWithFile: file1 differs, check " + dir);
        }
    }

    @Test
    public void testWithAdaptiveIconResource_useMonochrome() throws Exception {
        final int colorMono = ((ColorDrawable) getContext().getDrawable(
                android.R.color.system_accent2_800)).getColor();
        final Icon im1 = Icon.createWithResourceAdaptiveDrawable(getContext().getPackageName(),
                R.drawable.adaptiveicon_drawable, true, 0.0f);
        final Drawable draw1 = im1.loadDrawable(mContext);
        assertThat(draw1 instanceof InsetDrawable).isTrue();
        ColorDrawable colorDrawable = (ColorDrawable) ((DrawableWrapper) draw1).getDrawable();
        assertThat(colorDrawable.getColor()).isEqualTo(colorMono);
    }

    @Test
    public void testWithAdaptiveIconResource_dontUseMonochrome() throws Exception {
        final int colorMono = ((ColorDrawable) getContext().getDrawable(
                android.R.color.system_accent2_800)).getColor();
        final int colorFg = ((ColorDrawable) getContext().getDrawable(
                android.R.color.black)).getColor();
        final int colorBg = ((ColorDrawable) getContext().getDrawable(
                android.R.color.white)).getColor();

        final Icon im1 = Icon.createWithResourceAdaptiveDrawable(getContext().getPackageName(),
                R.drawable.adaptiveicon_drawable, false , 0.0f);
        final Drawable draw1 = im1.loadDrawable(mContext);
        assertThat(draw1 instanceof AdaptiveIconDrawable).isTrue();
        ColorDrawable colorDrawableMono = (ColorDrawable) ((AdaptiveIconDrawable) draw1)
                .getMonochrome();
        assertThat(colorDrawableMono.getColor()).isEqualTo(colorMono);
        ColorDrawable colorDrawableFg = (ColorDrawable) ((AdaptiveIconDrawable) draw1)
                .getForeground();
        assertThat(colorDrawableFg.getColor()).isEqualTo(colorFg);
        ColorDrawable colorDrawableBg = (ColorDrawable) ((AdaptiveIconDrawable) draw1)
                .getBackground();
        assertThat(colorDrawableBg.getColor()).isEqualTo(colorBg);
    }

    @Test
    public void testAdaptiveIconResource_sameAs(@TestParameter boolean useMonochrome)
            throws Exception {
        final Icon im1 = Icon.createWithResourceAdaptiveDrawable(getContext().getPackageName(),
                R.drawable.adaptiveicon_drawable, useMonochrome, 1.0f);
        final Parcel parcel = Parcel.obtain();
        im1.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final Icon im2 = Icon.CREATOR.createFromParcel(parcel);
        assertThat(im1.sameAs(im2)).isTrue();
    }

    @Test
    public void testAsync() throws Exception {
        final Bitmap bit1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();
        final File dir = getContext().getExternalFilesDir(null);
        final File file1 = new File(dir, "async-original.png");
        bit1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(file1));

        final Icon im1 = Icon.createWithFilePath(file1.toString());
        final HandlerThread thd = new HandlerThread("testAsync");
        thd.start();
        final Handler h = new Handler(thd.getLooper());
        L(TAG, "asyncTest: dispatching load to thread: " + thd);
        im1.loadDrawableAsync(mContext, new Icon.OnDrawableLoadedListener() {
            @Override
            public void onDrawableLoaded(Drawable draw1) {
                L(TAG, "asyncTest: thread: loading drawable");
                L(TAG, "asyncTest: thread: loaded: %dx%d", draw1.getIntrinsicWidth(),
                    draw1.getIntrinsicHeight());
                final Bitmap test1 = Bitmap.createBitmap(draw1.getIntrinsicWidth(),
                        draw1.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                draw1.setBounds(0, 0, test1.getWidth(), test1.getHeight());
                draw1.draw(new Canvas(test1));

                try {
                    test1.compress(Bitmap.CompressFormat.PNG, 100,
                            new FileOutputStream(new File(dir, "async-test.png")));
                } catch (java.io.FileNotFoundException ex) {
                    fail("couldn't create test file: " + ex);
                }
                if (!equalBitmaps(bit1, test1)) {
                    findBitmapDifferences(bit1, test1);
                    fail("testAsync: file1 differs, check " + dir);
                }
            }
        }, h);
        L(TAG, "asyncTest: awaiting result");
        Thread.sleep(500); // ;_;
        assertTrue("async-test.png does not exist!", new File(dir, "async-test.png").exists());
        L(TAG, "asyncTest: done");
    }

    @Test
    public void testParcel() throws Exception {
        final Bitmap originalbits = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();

        final ByteArrayOutputStream ostream = new ByteArrayOutputStream(
                originalbits.getWidth() * originalbits.getHeight() * 2); // guess 50% compression
        originalbits.compress(Bitmap.CompressFormat.PNG, 100, ostream);
        final byte[] pngdata = ostream.toByteArray();

        L("starting testParcel; bitmap: %d bytes, PNG: %d bytes",
                originalbits.getByteCount(),
                pngdata.length);

        final File dir = getContext().getExternalFilesDir(null);
        final File originalfile = new File(dir, "parcel-original.png");
        new FileOutputStream(originalfile).write(pngdata);

        ArrayList<Icon> imgs = new ArrayList<>();
        final Icon file1 = Icon.createWithFilePath(originalfile.getAbsolutePath());
        imgs.add(file1);
        final Icon bit1 = Icon.createWithBitmap(originalbits);
        imgs.add(bit1);
        final Icon data1 = Icon.createWithData(pngdata, 0, pngdata.length);
        imgs.add(data1);
        final Icon res1 = Icon.createWithResource(getContext(), R.drawable.landscape);
        imgs.add(res1);

        ArrayList<Icon> test = new ArrayList<>();
        final Parcel parcel = Parcel.obtain();
        int pos = 0;
        parcel.writeInt(imgs.size());
        for (Icon img : imgs) {
            img.writeToParcel(parcel, 0);
            L("used %d bytes parceling: %s", parcel.dataPosition() - pos, img);
            pos = parcel.dataPosition();
        }

        parcel.setDataPosition(0); // rewind
        final int N = parcel.readInt();
        for (int i=0; i<N; i++) {
            Icon img = Icon.CREATOR.createFromParcel(parcel);
            L("test %d: read from parcel: %s", i, img);
            final File testfile = new File(dir,
                    String.format("parcel-test%02d.png", i));

            final Drawable draw1 = img.loadDrawable(mContext);
            if (draw1 == null) {
                fail("null drawable from img: " + img);
            }
            final Bitmap test1 = Bitmap.createBitmap(draw1.getIntrinsicWidth(),
                    draw1.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            draw1.setBounds(0, 0, test1.getWidth(), test1.getHeight());
            draw1.draw(new Canvas(test1));

            try {
                test1.compress(Bitmap.CompressFormat.PNG, 100,
                        new FileOutputStream(testfile));
            } catch (java.io.FileNotFoundException ex) {
                fail("couldn't create test file " + testfile + ": " + ex);
            }
            if (!equalBitmaps(originalbits, test1)) {
                findBitmapDifferences(originalbits, test1);
                fail(testfile + " differs from original: " + originalfile);
            }

        }
    }

    private int getMaxWidth(int origWidth, int origHeight, int maxNumPixels) {
        float aspRatio = (float) origWidth / (float) origHeight;
        int newHeight = (int) Math.sqrt(maxNumPixels / aspRatio);
        return (int) (newHeight * aspRatio);
    }

    private int getMaxHeight(int origWidth, int origHeight, int maxNumPixels) {
        float aspRatio = (float) origWidth / (float) origHeight;
        return (int) Math.sqrt(maxNumPixels / aspRatio);
    }

    @Test
    public void testScaleDownMaxSizeWithBitmap() throws Exception {
        final int bmpWidth = 13_000;
        final int bmpHeight = 10_000;
        final int bmpBpp = 4;
        final int maxNumPixels = RecordingCanvas.MAX_BITMAP_SIZE / bmpBpp;
        final int maxWidth = getMaxWidth(bmpWidth, bmpHeight, maxNumPixels);
        final int maxHeight = getMaxHeight(bmpWidth, bmpHeight, maxNumPixels);

        final Bitmap bm = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        final Icon ic = Icon.createWithBitmap(bm);
        final Drawable drawable = ic.loadDrawable(mContext);

        assertThat(drawable.getIntrinsicWidth()).isEqualTo(maxWidth);
        assertThat(drawable.getIntrinsicHeight()).isEqualTo(maxHeight);
    }

    @Test
    public void testScaleDownMaxSizeWithAdaptiveBitmap() throws Exception {
        final int bmpWidth = 20_000;
        final int bmpHeight = 10_000;
        final int bmpBpp = 4;
        final int maxNumPixels = RecordingCanvas.MAX_BITMAP_SIZE / bmpBpp;
        final int maxWidth = getMaxWidth(bmpWidth, bmpHeight, maxNumPixels);
        final int maxHeight = getMaxHeight(bmpWidth, bmpHeight, maxNumPixels);

        final Bitmap bm = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        final Icon ic = Icon.createWithAdaptiveBitmap(bm);
        final AdaptiveIconDrawable adaptiveDrawable = (AdaptiveIconDrawable) ic.loadDrawable(
                mContext);
        final Drawable drawable = adaptiveDrawable.getForeground();

        assertThat(drawable.getIntrinsicWidth()).isEqualTo(maxWidth);
        assertThat(drawable.getIntrinsicHeight()).isEqualTo(maxHeight);
    }

    @Test
    public void testScaleDownMaxSizeWithResource() throws Exception {
        final Icon ic = Icon.createWithResource(getContext(), R.drawable.test_too_big);
        final BitmapDrawable drawable = (BitmapDrawable) ic.loadDrawable(mContext);

        assertThat(drawable.getBitmap().getByteCount()).isAtMost(RecordingCanvas.MAX_BITMAP_SIZE);
    }

    @Test
    public void testScaleDownMaxSizeWithFile() throws Exception {
        final Bitmap bit1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.test_too_big))
                .getBitmap();
        final File dir = getContext().getExternalFilesDir(null);
        final File file1 = new File(dir, "file1-too-big.png");
        bit1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(file1));

        final Icon ic = Icon.createWithFilePath(file1.toString());
        final BitmapDrawable drawable = (BitmapDrawable) ic.loadDrawable(mContext);

        assertThat(drawable.getBitmap().getByteCount()).isAtMost(RecordingCanvas.MAX_BITMAP_SIZE);
    }

    @Test
    public void testScaleDownMaxSizeWithData() throws Exception {
        final int bmpBpp = 4;
        final Bitmap originalBits = ((BitmapDrawable) getContext().getDrawable(
                R.drawable.test_too_big)).getBitmap();
        final ByteArrayOutputStream ostream = new ByteArrayOutputStream(
                originalBits.getWidth() * originalBits.getHeight() * bmpBpp);
        originalBits.compress(Bitmap.CompressFormat.PNG, 100, ostream);
        final byte[] pngdata = ostream.toByteArray();
        final Icon ic = Icon.createWithData(pngdata, 0, pngdata.length);
        final BitmapDrawable drawable = (BitmapDrawable) ic.loadDrawable(mContext);

        assertThat(drawable.getBitmap().getByteCount()).isAtMost(RecordingCanvas.MAX_BITMAP_SIZE);
    }

    @Test
    public void testLoadSafeDrawable_loadSuccessful() throws FileNotFoundException {
        int uid = 12345;
        String packageName = "test_pkg";

        final Bitmap bit1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();
        final File dir = getContext().getExternalFilesDir(null);
        final File file1 = new File(dir, "file1-original.png");
        bit1.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(file1));

        final Icon im1 = Icon.createWithFilePath(file1.toString());

        TestableIUriGrantsManager ugm =
                new TestableIUriGrantsManager(/* rejectCheckRequests */ false);

        Drawable loadedDrawable = im1.loadDrawableCheckingUriGrant(
                getContext(), ugm, uid, packageName);
        assertThat(loadedDrawable).isNotNull();

        assertThat(ugm.mRequests.size()).isEqualTo(1);
        TestableIUriGrantsManager.CheckRequest r = ugm.mRequests.get(0);
        assertThat(r.mCallingUid).isEqualTo(uid);
        assertThat(r.mPackageName).isEqualTo(packageName);
        assertThat(r.mMode).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertThat(r.mUri).isEqualTo(ContentProvider.getUriWithoutUserId(im1.getUri()));
        assertThat(r.mUserId).isEqualTo(ContentProvider.getUserIdFromUri(im1.getUri()));

        final Bitmap test1 = Bitmap.createBitmap(loadedDrawable.getIntrinsicWidth(),
                loadedDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        loadedDrawable.setBounds(0, 0, loadedDrawable.getIntrinsicWidth(),
                loadedDrawable.getIntrinsicHeight());
        loadedDrawable.draw(new Canvas(test1));

        bit1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap1-original.png")));
        test1.compress(Bitmap.CompressFormat.PNG, 100,
                new FileOutputStream(new File(dir, "bitmap1-test.png")));
        if (!equalBitmaps(bit1, test1)) {
            findBitmapDifferences(bit1, test1);
            fail("bitmap1 differs, check " + dir);
        }
    }

    @Test
    public void testLoadSafeDrawable_grantRejected_nullDrawable() throws FileNotFoundException {
        int uid = 12345;
        String packageName = "test_pkg";

        final Bitmap bit1 = ((BitmapDrawable) getContext().getDrawable(R.drawable.landscape))
                .getBitmap();
        final File dir = getContext().getExternalFilesDir(null);
        final File file1 = new File(dir, "file1-original.png");
        bit1.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(file1));

        final Icon im1 = Icon.createWithFilePath(file1.toString());

        TestableIUriGrantsManager ugm =
                new TestableIUriGrantsManager(/* rejectCheckRequests */ true);

        Drawable loadedDrawable = im1.loadDrawableCheckingUriGrant(
                getContext(), ugm, uid, packageName);

        assertThat(ugm.mRequests.size()).isEqualTo(1);
        TestableIUriGrantsManager.CheckRequest r = ugm.mRequests.get(0);
        assertThat(r.mCallingUid).isEqualTo(uid);
        assertThat(r.mPackageName).isEqualTo(packageName);
        assertThat(r.mMode).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertThat(r.mUri).isEqualTo(ContentProvider.getUriWithoutUserId(im1.getUri()));
        assertThat(r.mUserId).isEqualTo(ContentProvider.getUserIdFromUri(im1.getUri()));

        assertThat(loadedDrawable).isNull();
    }


    // ======== utils ========

    static final char[] GRADIENT = " .:;+=xX$#".toCharArray();
    static float[] hsv = new float[3];
    static char colorToChar(int color) {
        int sum = ((color >> 16) & 0xff)
                + ((color >> 8)  & 0xff)
                + ((color)       & 0xff);
        return GRADIENT[sum * (GRADIENT.length-1) / (3*0xff)];
    }
    static void printBits(int[] a, int w, int h) {
        final StringBuilder sb = new StringBuilder();
        for (int i=0; i<w; i++) {
            for (int j=0; j<h; j++) {
                sb.append(colorToChar(a[i+w*j]));
            }
            sb.append('\n');
        }
        L(sb.toString());
    }
    static void printBits(Bitmap a) {
        final int w = a.getWidth();
        final int h = a.getHeight();
        int[] aPix = new int[w * h];
        printBits(aPix, w, h);
    }
    boolean equalBitmaps(Bitmap a, Bitmap b) {
        return equalBitmaps(a, b, null);
    }

    boolean equalBitmaps(Bitmap a, Bitmap b, Region region) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;

        final int w = a.getWidth();
        final int h = a.getHeight();
        int[] aPix = new int[w * h];
        int[] bPix = new int[w * h];

        if (region != null) {
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    if (region.contains(i, j) && a.getPixel(i, j) != b.getPixel(i, j)) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            a.getPixels(aPix, 0, w, 0, 0, w, h);
            b.getPixels(bPix, 0, w, 0, 0, w, h);
            return Arrays.equals(aPix, bPix);
        }
    }

    void findBitmapDifferences(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            L("different sizes: %dx%d vs %dx%d",
                        a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
            return;
        }
        
        final int w = a.getWidth();
        final int h = a.getHeight();
        int[] aPix = new int[w * h];
        int[] bPix = new int[w * h];

        a.getPixels(aPix, 0, w, 0, 0, w, h);
        b.getPixels(bPix, 0, w, 0, 0, w, h);

        L("bitmap a (%dx%d)", w, h);
        printBits(aPix, w, h);
        L("bitmap b (%dx%d)", w, h);
        printBits(bPix, w, h);

        StringBuffer sb = new StringBuffer("Different pixels: ");
        for (int i=0; i<w; i++) {
            for (int j=0; j<h; j++) {
                if (aPix[i+w*j] != bPix[i+w*j]) {
                    sb.append(" ").append(i).append(",").append(j);
                }
            }
        }
        L(sb.toString());
    }

    private static class TestableIUriGrantsManager extends IUriGrantsManager.Stub {

        final ArrayList<CheckRequest> mRequests = new ArrayList<>();
        final boolean mRejectCheckRequests;

        TestableIUriGrantsManager(boolean rejectCheckRequests) {
            this.mRejectCheckRequests = rejectCheckRequests;
        }

        @Override
        public void takePersistableUriPermission(Uri uri, int i, String s, int i1)
                throws RemoteException {

        }

        @Override
        public void releasePersistableUriPermission(Uri uri, int i, String s, int i1)
                throws RemoteException {

        }

        @Override
        public void grantUriPermissionFromOwner(IBinder iBinder, int i, String s, Uri uri, int i1,
                int i2, int i3) throws RemoteException {

        }

        @Override
        public ParceledListSlice getGrantedUriPermissions(String s, int i) throws RemoteException {
            return null;
        }

        @Override
        public void clearGrantedUriPermissions(String s, int i) throws RemoteException {

        }

        @Override
        public ParceledListSlice getUriPermissions(String s, boolean b, boolean b1)
                throws RemoteException {
            return null;
        }

        @Override
        public int checkGrantUriPermission_ignoreNonSystem(
                int uid, String packageName, Uri uri, int mode, int userId)
                throws RemoteException {
            CheckRequest r = new CheckRequest(uid, packageName, uri, mode, userId);
            mRequests.add(r);
            if (mRejectCheckRequests) {
                throw new SecurityException();
            } else {
                return uid;
            }
        }

        static class CheckRequest {
            final int mCallingUid;
            final String mPackageName;
            final Uri mUri;
            final int mMode;
            final int mUserId;

            CheckRequest(int callingUid, String packageName, Uri uri, int mode, int userId) {
                this.mCallingUid = callingUid;
                this.mPackageName = packageName;
                this.mUri = uri;
                this.mMode = mode;
                this.mUserId = userId;
            }
        }
    }
}