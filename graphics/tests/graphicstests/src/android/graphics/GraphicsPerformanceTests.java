/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import junit.framework.Assert;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.test.AndroidTestCase;
import android.test.PerformanceTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import com.android.frameworks.graphicstests.R;

/**
 * Graphics Performance Tests
 * 
 */
//We don't want to run these perf tests in the continuous build.
@Suppress 
public class GraphicsPerformanceTests {
    private static final String TAG = "GfxPerf";
    public static String[] children() {
        return new String[] {
                // test decoding bitmaps of various sizes
                DecodeBitmapTest.class.getName(),
                
                // odd-sized bitmap drawing tests
                DrawBitmap7x7.class.getName(), 
                DrawBitmap15x15.class.getName(),
                DrawBitmap31x31.class.getName(), 
                DrawBitmap63x63.class.getName(),
                DrawBitmap127x127.class.getName(),
                DrawBitmap319x239.class.getName(),
                DrawBitmap319x479.class.getName(),
                
                // even-sized bitmap drawing tests
                DrawBitmap8x8.class.getName(), 
                DrawBitmap16x16.class.getName(),
                DrawBitmap32x32.class.getName(), 
                DrawBitmap64x64.class.getName(),
                DrawBitmap128x128.class.getName(), 
                DrawBitmap320x240.class.getName(),
                DrawBitmap320x480.class.getName()};
    }

    /**
     * Base class for all graphics tests
     * 
     */
    public static abstract class GraphicsTestBase extends AndroidTestCase
            implements PerformanceTestCase {
        /** Target "screen" (bitmap) width and height */
        private static final int DEFAULT_ITERATIONS = 1;
        private static final int SCREEN_WIDTH = 320;
        private static final int SCREEN_HEIGHT = 480;
        
        /** Number of iterations to pass back to harness. Subclass should override */
        protected int mIterations = 1;
        
        /** Bitmap we allocate and draw to */
        protected Bitmap mDestBitmap;
        
        /** Canvas of drawing routines */
        protected Canvas mCanvas;
        
        /** Style and color information (uses defaults) */
        protected Paint mPaint;
     
        @Override
        public void setUp() throws Exception {
            super.setUp();
            // Create drawable bitmap for rendering into
            mDestBitmap = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT,
                                              Bitmap.Config.RGB_565);
            // Set of drawing routines
            mCanvas = new Canvas(mDestBitmap);
            // Styles
            mPaint = new Paint();
            // Ask subclass for number of iterations
            mIterations = getIterations();
        }
        
        // A reasonable default
        public int getIterations() {
            return DEFAULT_ITERATIONS;
        }

        public boolean isPerformanceOnly() {
            return true;
        }

        public int startPerformance(Intermediates intermediates) {
            intermediates.setInternalIterations(mIterations * 10);
            return 0;
        }
    }

    /**
     * Tests time to decode a number of sizes of images.
     */
    public static class DecodeBitmapTest extends GraphicsTestBase {
        /** Number of times to run this test */
        private static final int DECODE_ITERATIONS = 10;
        
        /** Used to access package bitmap images */
        private Resources mResources;

        @Override
        public void setUp() throws Exception {
            super.setUp();
            
            // For bitmap resources
            Context context = getContext();
            Assert.assertNotNull(context);
            mResources = context.getResources();
            Assert.assertNotNull(mResources); 
        }
        
        @Override
        public int getIterations() {
            return DECODE_ITERATIONS;
        }

        public void testDecodeBitmap() {
            for (int i = 0; i < DECODE_ITERATIONS; i++) {
                BitmapFactory.decodeResource(mResources, R.drawable.test16x12);
                BitmapFactory.decodeResource(mResources, R.drawable.test32x24);
                BitmapFactory.decodeResource(mResources, R.drawable.test64x48);
                BitmapFactory.decodeResource(mResources, R.drawable.test128x96);
                BitmapFactory.decodeResource(mResources, R.drawable.test256x192);
                BitmapFactory.decodeResource(mResources, R.drawable.test320x240);
            }
        }
    }
    
    /**
     * Base class for bitmap drawing tests
     * 
     */
    public static abstract class DrawBitmapTest extends GraphicsTestBase {
        /** Number of times to run each draw test */
        private static final int ITERATIONS = 1000;
        
        /** Bitmap to draw. Allocated by subclass's createBitmap() function. */
        private Bitmap mBitmap;
        
        @Override
        public void setUp() throws Exception {
            super.setUp();
            
            // Invoke subclass's method to create the bitmap
            mBitmap = createBitmap();
        }
        
        public int getIterations() {
            return ITERATIONS;
        }
       
        // Generic abstract function to create bitmap for any given subclass
        public abstract Bitmap createBitmap();
        
        // Provide convenience test code for all subsequent classes. 
        // Note: Though it would be convenient to declare all of the test*() methods here
        // and just inherit them, our test harness doesn't support it. So we replicate
        // a bit of code in each derived test case.
        public void drawBitmapEven() {
            for (int i = 0; i < ITERATIONS; i++) {
                mCanvas.drawBitmap(mBitmap, 0.0f, 0.0f, mPaint);
            }
        }

        public void drawBitmapOdd() {
            for (int i = 0; i < ITERATIONS; i++) {
                mCanvas.drawBitmap(mBitmap, 1.0f, 0.0f, mPaint);
            }
        }
    }


    /**
     * Test drawing of 7x7 image
     */
    public static class DrawBitmap7x7 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(7, 7, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 15x15 image
     */
    public static class DrawBitmap15x15 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(15, 15, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 31x31 image
     */
    public static class DrawBitmap31x31 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(31, 31, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 63x63 image
     */
    public static class DrawBitmap63x63 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(63, 63, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 127x127 image
     */
    public static class DrawBitmap127x127 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(127, 127, Bitmap.Config.RGB_565);
        }
        
        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 319x239 image
     */
    public static class DrawBitmap319x239 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(319, 239, Bitmap.Config.RGB_565);
        }
        
        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }
    
    /**
     * Test drawing of 319x479 image
     */
    public static class DrawBitmap319x479 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(319, 479, Bitmap.Config.RGB_565);
        }
        
        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 8x8 image
     */
    public static class DrawBitmap8x8 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 16x16 image
     */
    public static class DrawBitmap16x16 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565);
        }
        
        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 32x32 image
     */
    public static class DrawBitmap32x32 extends DrawBitmapTest {
        
        public Bitmap createBitmap() {
            return Bitmap.createBitmap(32, 32, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 64x64 image
     */
    public static class DrawBitmap64x64 extends DrawBitmapTest {

        public Bitmap createBitmap() {
            return Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 128x128 image
     */
    public static class DrawBitmap128x128 extends DrawBitmapTest {

        public Bitmap createBitmap() {
            return Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }

    /**
     * Test drawing of 320x240 image
     */
    public static class DrawBitmap320x240 extends DrawBitmapTest {

        public Bitmap createBitmap() {
            return Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }
    
    /**
     * Test drawing of 320x480 image
     */
    public static class DrawBitmap320x480 extends DrawBitmapTest {

        public Bitmap createBitmap() {
            return Bitmap.createBitmap(320, 480, Bitmap.Config.RGB_565);
        }

        public void testDrawBitmapEven() {
            drawBitmapEven();
        }
        
        public void testDrawBitmapOdd() {
            drawBitmapOdd();
        }
    }
}
