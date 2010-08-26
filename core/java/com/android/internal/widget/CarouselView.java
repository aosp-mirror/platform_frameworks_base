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

package com.android.internal.widget;

import com.android.internal.R;
import com.android.internal.widget.CarouselRS.CarouselCallback;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Bitmap.Config;
import android.renderscript.FileA3D;
import android.renderscript.Mesh;
import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class CarouselView extends RSSurfaceView {
    private static final boolean USE_DEPTH_BUFFER = true;
    private final int DEFAULT_SLOT_COUNT = 10;
    private final Bitmap DEFAULT_BITMAP = Bitmap.createBitmap(1, 1, Config.RGB_565);
    private static final String TAG = "CarouselView";
    private CarouselRS mRenderScript;
    private RenderScriptGL mRS;
    private Context mContext;
    private boolean mTracking;
    private Bitmap mDefaultBitmap;
    private Bitmap mLoadingBitmap;
    private Mesh mDefaultGeometry;
    private Mesh mLoadingGeometry;
    private int mCardCount = 0;
    private int mVisibleSlots = 0;
    private float mStartAngle;
    private int mSlotCount = DEFAULT_SLOT_COUNT;

    public CarouselView(Context context) {
        this(context, null);
    }

    /**
     * Constructor used when this widget is created from a layout file.
     */
    public CarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        boolean useDepthBuffer = true;
        mRS = createRenderScript(USE_DEPTH_BUFFER);
        mRenderScript = new CarouselRS();
        mRenderScript.init(mRS, getResources());
        // TODO: add parameters to layout
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        //mRS.contextSetSurface(w, h, holder.getSurface());
        mRenderScript.init(mRS, getResources());
        setSlotCount(mSlotCount);
        createCards(mCardCount);
        setVisibleSlots(mVisibleSlots);
        setCallback(mCarouselCallback);
        setDefaultBitmap(mDefaultBitmap);
        setLoadingBitmap(mLoadingBitmap);
        setDefaultGeometry(mDefaultGeometry);
        setLoadingGeometry(mLoadingGeometry);
        setStartAngle(mStartAngle);
    }

    /**
     * Loads geometry from a resource id.
     * 
     * @param resId
     * @return the loaded mesh or null if it cannot be loaded
     */
    public Mesh loadGeometry(int resId) {
        Resources res = mContext.getResources();
        FileA3D model = FileA3D.createFromResource(mRS, res, resId);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if(entry == null || entry.getClassID() != FileA3D.ClassID.MESH) {
            return null;
        }
        return (Mesh) entry.getObject();
    }
    
    /**
     * Load A3D file from resource.  If resId == 0, will clear geometry for this item.
     * @param n
     * @param resId
     */
    public void setGeometryForItem(int n, Mesh mesh) {
        mRenderScript.setGeometry(n, mesh);
    }
    
    public void setSlotCount(int n) {
        mSlotCount = n;
        if (mRenderScript != null) {
            mRenderScript.setSlotCount(n);
        }
    }

    public void setVisibleSlots(int n) {
        mVisibleSlots = n;
        if (mRenderScript != null) {
            mRenderScript.setVisibleSlots(n);
        }
    }

    public void createCards(int n) {
        mCardCount = n;
        if (mRenderScript != null) {
            mRenderScript.createCards(n);
        }
    }
    
    public void setTextureForItem(int n, Bitmap bitmap) {
        if (mRenderScript != null) {
            Log.v(TAG, "setTextureForItem(" + n + ")");
            mRenderScript.setTexture(n, bitmap); 
            Log.v(TAG, "done");
        }
    }

    public void setDefaultBitmap(Bitmap bitmap) {
        mDefaultBitmap = bitmap; 
        if (mRenderScript != null) {
            mRenderScript.setDefaultBitmap(bitmap);
        }
    }
    
    public void setLoadingBitmap(Bitmap bitmap) {
        mLoadingBitmap = bitmap;
        if (mRenderScript != null) {
            mRenderScript.setLoadingBitmap(bitmap);
        }
    }
    
    public void setDefaultGeometry(Mesh mesh) {
        mDefaultGeometry = mesh;
        if (mRenderScript != null) {
            mRenderScript.setDefaultGeometry(mesh);
        }
    }
    
    public void setLoadingGeometry(Mesh mesh) {
        mLoadingGeometry = mesh;
        if (mRenderScript != null) {
            mRenderScript.setLoadingGeometry(mesh);
        }
    }
    
    public void setCallback(CarouselCallback callback)
    {
        mCarouselCallback = callback;
        if (mRenderScript != null) {
            mRenderScript.setCallback(callback);
        }
    }

    public void setStartAngle(float angle)
    {
        mStartAngle = angle;
        if (mRenderScript != null) {
            mRenderScript.setStartAngle(angle);
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if(mRS != null) {
            mRS = null;
            destroyRenderScript();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mRS == null) {
            mRS = createRenderScript(USE_DEPTH_BUFFER);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        final float x = event.getX();
        final float y = event.getY();
        
        if (mRenderScript == null) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTracking = true;
                mRenderScript.doStart(x, y);
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (mTracking) {
                    mRenderScript.doMotion(x, y);
                }
                break;
                
            case MotionEvent.ACTION_UP:
                mRenderScript.doStop(x, y);
                mTracking = false;
                break;
        }

        return true;
    }
    
    private final CarouselCallback DEBUG_CALLBACK = new CarouselCallback() {
        public void onAnimationStarted() {
            Log.v(TAG, "onAnimationStarted()");
        }
        
        public void onAnimationFinished() {
            Log.v(TAG, "onAnimationFinished()");
        }

        public void onCardSelected(int n) {
            Log.v(TAG, "onCardSelected(" + n + ")");
        }

        public void onRequestGeometry(int n) {
            Log.v(TAG, "onRequestGeometry(" + n + ")");
        }

        public void onInvalidateGeometry(int n) {
            Log.v(TAG, "onInvalidateGeometry(" + n + ")");
        }
        
        public void onRequestTexture(final int n) {
            Log.v(TAG, "onRequestTexture(" + n + ")");
        }

        public void onInvalidateTexture(int n) {
            Log.v(TAG, "onInvalidateTexture(" + n + ")");
        }

    };
    
    private CarouselCallback mCarouselCallback = DEBUG_CALLBACK;
}
