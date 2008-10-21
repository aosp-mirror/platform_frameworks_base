package com.android.layoutlib.bridge;

import com.android.ninepatch.NinePatch;

import java.net.URL;

import junit.framework.TestCase;

public class NinePatchTest extends TestCase {
    
    private NinePatch mPatch;

    @Override
    protected void setUp() throws Exception {
        URL url = this.getClass().getClassLoader().getResource("data/button.9.png");

        mPatch = NinePatch.load(url, false /* convert */);
    }
    
    public void test9PatchLoad() throws Exception {
        assertNotNull(mPatch);
    }
    
    public void test9PatchMinSize() {
        int[] padding = new int[4];
        mPatch.getPadding(padding);
        assertEquals(13, padding[0]);
        assertEquals(3, padding[1]);
        assertEquals(13, padding[2]);
        assertEquals(4, padding[3]);
        assertEquals(38, mPatch.getWidth());
        assertEquals(27, mPatch.getHeight());
    }

}
