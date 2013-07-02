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

package android.widget;

import com.android.internal.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.drawable.shapes.Shape;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Pools.SynchronizedPool;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.Transformation;
import android.widget.RemoteViews.RemoteView;

import java.util.ArrayList;


/**
 * <p>
 * Visual indicator of progress in some operation.  Displays a bar to the user
 * representing how far the operation has progressed; the application can 
 * change the amount of progress (modifying the length of the bar) as it moves 
 * forward.  There is also a secondary progress displayable on a progress bar
 * which is useful for displaying intermediate progress, such as the buffer
 * level during a streaming playback progress bar.
 * </p>
 *
 * <p>
 * A progress bar can also be made indeterminate. In indeterminate mode, the
 * progress bar shows a cyclic animation without an indication of progress. This mode is used by
 * applications when the length of the task is unknown. The indeterminate progress bar can be either
 * a spinning wheel or a horizontal bar.
 * </p>
 *
 * <p>The following code example shows how a progress bar can be used from
 * a worker thread to update the user interface to notify the user of progress:
 * </p>
 * 
 * <pre>
 * public class MyActivity extends Activity {
 *     private static final int PROGRESS = 0x1;
 *
 *     private ProgressBar mProgress;
 *     private int mProgressStatus = 0;
 *
 *     private Handler mHandler = new Handler();
 *
 *     protected void onCreate(Bundle icicle) {
 *         super.onCreate(icicle);
 *
 *         setContentView(R.layout.progressbar_activity);
 *
 *         mProgress = (ProgressBar) findViewById(R.id.progress_bar);
 *
 *         // Start lengthy operation in a background thread
 *         new Thread(new Runnable() {
 *             public void run() {
 *                 while (mProgressStatus &lt; 100) {
 *                     mProgressStatus = doWork();
 *
 *                     // Update the progress bar
 *                     mHandler.post(new Runnable() {
 *                         public void run() {
 *                             mProgress.setProgress(mProgressStatus);
 *                         }
 *                     });
 *                 }
 *             }
 *         }).start();
 *     }
 * }</pre>
 *
 * <p>To add a progress bar to a layout file, you can use the {@code &lt;ProgressBar&gt;} element.
 * By default, the progress bar is a spinning wheel (an indeterminate indicator). To change to a
 * horizontal progress bar, apply the {@link android.R.style#Widget_ProgressBar_Horizontal
 * Widget.ProgressBar.Horizontal} style, like so:</p>
 *
 * <pre>
 * &lt;ProgressBar
 *     style="@android:style/Widget.ProgressBar.Horizontal"
 *     ... /&gt;</pre>
 *
 * <p>If you will use the progress bar to show real progress, you must use the horizontal bar. You
 * can then increment the  progress with {@link #incrementProgressBy incrementProgressBy()} or
 * {@link #setProgress setProgress()}. By default, the progress bar is full when it reaches 100. If
 * necessary, you can adjust the maximum value (the value for a full bar) using the {@link
 * android.R.styleable#ProgressBar_max android:max} attribute. Other attributes available are listed
 * below.</p>
 *
 * <p>Another common style to apply to the progress bar is {@link
 * android.R.style#Widget_ProgressBar_Small Widget.ProgressBar.Small}, which shows a smaller
 * version of the spinning wheel&mdash;useful when waiting for content to load.
 * For example, you can insert this kind of progress bar into your default layout for
 * a view that will be populated by some content fetched from the Internet&mdash;the spinning wheel
 * appears immediately and when your application receives the content, it replaces the progress bar
 * with the loaded content. For example:</p>
 *
 * <pre>
 * &lt;LinearLayout
 *     android:orientation="horizontal"
 *     ... &gt;
 *     &lt;ProgressBar
 *         android:layout_width="wrap_content"
 *         android:layout_height="wrap_content"
 *         style="@android:style/Widget.ProgressBar.Small"
 *         android:layout_marginRight="5dp" /&gt;
 *     &lt;TextView
 *         android:layout_width="wrap_content"
 *         android:layout_height="wrap_content"
 *         android:text="@string/loading" /&gt;
 * &lt;/LinearLayout&gt;</pre>
 *
 * <p>Other progress bar styles provided by the system include:</p>
 * <ul>
 * <li>{@link android.R.style#Widget_ProgressBar_Horizontal Widget.ProgressBar.Horizontal}</li>
 * <li>{@link android.R.style#Widget_ProgressBar_Small Widget.ProgressBar.Small}</li>
 * <li>{@link android.R.style#Widget_ProgressBar_Large Widget.ProgressBar.Large}</li>
 * <li>{@link android.R.style#Widget_ProgressBar_Inverse Widget.ProgressBar.Inverse}</li>
 * <li>{@link android.R.style#Widget_ProgressBar_Small_Inverse
 * Widget.ProgressBar.Small.Inverse}</li>
 * <li>{@link android.R.style#Widget_ProgressBar_Large_Inverse
 * Widget.ProgressBar.Large.Inverse}</li>
 * </ul>
 * <p>The "inverse" styles provide an inverse color scheme for the spinner, which may be necessary
 * if your application uses a light colored theme (a white background).</p>
 *  
 * <p><strong>XML attributes</b></strong> 
 * <p> 
 * See {@link android.R.styleable#ProgressBar ProgressBar Attributes}, 
 * {@link android.R.styleable#View View Attributes}
 * </p>
 * 
 * @attr ref android.R.styleable#ProgressBar_animationResolution
 * @attr ref android.R.styleable#ProgressBar_indeterminate
 * @attr ref android.R.styleable#ProgressBar_indeterminateBehavior
 * @attr ref android.R.styleable#ProgressBar_indeterminateDrawable
 * @attr ref android.R.styleable#ProgressBar_indeterminateDuration
 * @attr ref android.R.styleable#ProgressBar_indeterminateOnly
 * @attr ref android.R.styleable#ProgressBar_interpolator
 * @attr ref android.R.styleable#ProgressBar_max
 * @attr ref android.R.styleable#ProgressBar_maxHeight
 * @attr ref android.R.styleable#ProgressBar_maxWidth
 * @attr ref android.R.styleable#ProgressBar_minHeight
 * @attr ref android.R.styleable#ProgressBar_minWidth
 * @attr ref android.R.styleable#ProgressBar_mirrorForRtl
 * @attr ref android.R.styleable#ProgressBar_progress
 * @attr ref android.R.styleable#ProgressBar_progressDrawable
 * @attr ref android.R.styleable#ProgressBar_secondaryProgress
 */
@RemoteView
public class ProgressBar extends View {
    private static final int MAX_LEVEL = 10000;
    private static final int TIMEOUT_SEND_ACCESSIBILITY_EVENT = 200;

    int mMinWidth;
    int mMaxWidth;
    int mMinHeight;
    int mMaxHeight;

    private int mProgress;
    private int mSecondaryProgress;
    private int mMax;

    private int mBehavior;
    private int mDuration;
    private boolean mIndeterminate;
    private boolean mOnlyIndeterminate;
    private Transformation mTransformation;
    private AlphaAnimation mAnimation;
    private boolean mHasAnimation;
    private Drawable mIndeterminateDrawable;
    private Drawable mProgressDrawable;
    private Drawable mCurrentDrawable;
    Bitmap mSampleTile;
    private boolean mNoInvalidate;
    private Interpolator mInterpolator;
    private RefreshProgressRunnable mRefreshProgressRunnable;
    private long mUiThreadId;
    private boolean mShouldStartAnimationDrawable;

    private boolean mInDrawing;
    private boolean mAttached;
    private boolean mRefreshIsPosted;

    boolean mMirrorForRtl = false;

    private final ArrayList<RefreshData> mRefreshData = new ArrayList<RefreshData>();

    private AccessibilityEventSender mAccessibilityEventSender;

    /**
     * Create a new progress bar with range 0...100 and initial progress of 0.
     * @param context the application environment
     */
    public ProgressBar(Context context) {
        this(context, null);
    }
    
    public ProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.progressBarStyle);
    }

    public ProgressBar(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, 0);
    }

    /**
     * @hide
     */
    public ProgressBar(Context context, AttributeSet attrs, int defStyle, int styleRes) {
        super(context, attrs, defStyle);
        mUiThreadId = Thread.currentThread().getId();
        initProgressBar();

        TypedArray a =
            context.obtainStyledAttributes(attrs, R.styleable.ProgressBar, defStyle, styleRes);
        
        mNoInvalidate = true;
        
        Drawable drawable = a.getDrawable(R.styleable.ProgressBar_progressDrawable);
        if (drawable != null) {
            drawable = tileify(drawable, false);
            // Calling this method can set mMaxHeight, make sure the corresponding
            // XML attribute for mMaxHeight is read after calling this method
            setProgressDrawable(drawable);
        }


        mDuration = a.getInt(R.styleable.ProgressBar_indeterminateDuration, mDuration);

        mMinWidth = a.getDimensionPixelSize(R.styleable.ProgressBar_minWidth, mMinWidth);
        mMaxWidth = a.getDimensionPixelSize(R.styleable.ProgressBar_maxWidth, mMaxWidth);
        mMinHeight = a.getDimensionPixelSize(R.styleable.ProgressBar_minHeight, mMinHeight);
        mMaxHeight = a.getDimensionPixelSize(R.styleable.ProgressBar_maxHeight, mMaxHeight);

        mBehavior = a.getInt(R.styleable.ProgressBar_indeterminateBehavior, mBehavior);

        final int resID = a.getResourceId(
                com.android.internal.R.styleable.ProgressBar_interpolator, 
                android.R.anim.linear_interpolator); // default to linear interpolator
        if (resID > 0) {
            setInterpolator(context, resID);
        } 

        setMax(a.getInt(R.styleable.ProgressBar_max, mMax));

        setProgress(a.getInt(R.styleable.ProgressBar_progress, mProgress));

        setSecondaryProgress(
                a.getInt(R.styleable.ProgressBar_secondaryProgress, mSecondaryProgress));

        drawable = a.getDrawable(R.styleable.ProgressBar_indeterminateDrawable);
        if (drawable != null) {
            drawable = tileifyIndeterminate(drawable);
            setIndeterminateDrawable(drawable);
        }

        mOnlyIndeterminate = a.getBoolean(
                R.styleable.ProgressBar_indeterminateOnly, mOnlyIndeterminate);

        mNoInvalidate = false;

        setIndeterminate(mOnlyIndeterminate || a.getBoolean(
                R.styleable.ProgressBar_indeterminate, mIndeterminate));

        mMirrorForRtl = a.getBoolean(R.styleable.ProgressBar_mirrorForRtl, mMirrorForRtl);

        a.recycle();
    }

    /**
     * Converts a drawable to a tiled version of itself. It will recursively
     * traverse layer and state list drawables.
     */
    private Drawable tileify(Drawable drawable, boolean clip) {
        
        if (drawable instanceof LayerDrawable) {
            LayerDrawable background = (LayerDrawable) drawable;
            final int N = background.getNumberOfLayers();
            Drawable[] outDrawables = new Drawable[N];
            
            for (int i = 0; i < N; i++) {
                int id = background.getId(i);
                outDrawables[i] = tileify(background.getDrawable(i),
                        (id == R.id.progress || id == R.id.secondaryProgress));
            }

            LayerDrawable newBg = new LayerDrawable(outDrawables);
            
            for (int i = 0; i < N; i++) {
                newBg.setId(i, background.getId(i));
            }
            
            return newBg;
            
        } else if (drawable instanceof StateListDrawable) {
            StateListDrawable in = (StateListDrawable) drawable;
            StateListDrawable out = new StateListDrawable();
            int numStates = in.getStateCount();
            for (int i = 0; i < numStates; i++) {
                out.addState(in.getStateSet(i), tileify(in.getStateDrawable(i), clip));
            }
            return out;
            
        } else if (drawable instanceof BitmapDrawable) {
            final Bitmap tileBitmap = ((BitmapDrawable) drawable).getBitmap();
            if (mSampleTile == null) {
                mSampleTile = tileBitmap;
            }
            
            final ShapeDrawable shapeDrawable = new ShapeDrawable(getDrawableShape());

            final BitmapShader bitmapShader = new BitmapShader(tileBitmap,
                    Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
            shapeDrawable.getPaint().setShader(bitmapShader);

            return (clip) ? new ClipDrawable(shapeDrawable, Gravity.LEFT,
                    ClipDrawable.HORIZONTAL) : shapeDrawable;
        }
        
        return drawable;
    }

    Shape getDrawableShape() {
        final float[] roundedCorners = new float[] { 5, 5, 5, 5, 5, 5, 5, 5 };
        return new RoundRectShape(roundedCorners, null, null);
    }
    
    /**
     * Convert a AnimationDrawable for use as a barberpole animation.
     * Each frame of the animation is wrapped in a ClipDrawable and
     * given a tiling BitmapShader.
     */
    private Drawable tileifyIndeterminate(Drawable drawable) {
        if (drawable instanceof AnimationDrawable) {
            AnimationDrawable background = (AnimationDrawable) drawable;
            final int N = background.getNumberOfFrames();
            AnimationDrawable newBg = new AnimationDrawable();
            newBg.setOneShot(background.isOneShot());
            
            for (int i = 0; i < N; i++) {
                Drawable frame = tileify(background.getFrame(i), true);
                frame.setLevel(10000);
                newBg.addFrame(frame, background.getDuration(i));
            }
            newBg.setLevel(10000);
            drawable = newBg;
        }
        return drawable;
    }
    
    /**
     * <p>
     * Initialize the progress bar's default values:
     * </p>
     * <ul>
     * <li>progress = 0</li>
     * <li>max = 100</li>
     * <li>animation duration = 4000 ms</li>
     * <li>indeterminate = false</li>
     * <li>behavior = repeat</li>
     * </ul>
     */
    private void initProgressBar() {
        mMax = 100;
        mProgress = 0;
        mSecondaryProgress = 0;
        mIndeterminate = false;
        mOnlyIndeterminate = false;
        mDuration = 4000;
        mBehavior = AlphaAnimation.RESTART;
        mMinWidth = 24;
        mMaxWidth = 48;
        mMinHeight = 24;
        mMaxHeight = 48;
    }

    /**
     * <p>Indicate whether this progress bar is in indeterminate mode.</p>
     *
     * @return true if the progress bar is in indeterminate mode
     */
    @ViewDebug.ExportedProperty(category = "progress")
    public synchronized boolean isIndeterminate() {
        return mIndeterminate;
    }

    /**
     * <p>Change the indeterminate mode for this progress bar. In indeterminate
     * mode, the progress is ignored and the progress bar shows an infinite
     * animation instead.</p>
     * 
     * If this progress bar's style only supports indeterminate mode (such as the circular
     * progress bars), then this will be ignored.
     *
     * @param indeterminate true to enable the indeterminate mode
     */
    @android.view.RemotableViewMethod
    public synchronized void setIndeterminate(boolean indeterminate) {
        if ((!mOnlyIndeterminate || !mIndeterminate) && indeterminate != mIndeterminate) {
            mIndeterminate = indeterminate;

            if (indeterminate) {
                // swap between indeterminate and regular backgrounds
                mCurrentDrawable = mIndeterminateDrawable;
                startAnimation();
            } else {
                mCurrentDrawable = mProgressDrawable;
                stopAnimation();
            }
        }
    }

    /**
     * <p>Get the drawable used to draw the progress bar in
     * indeterminate mode.</p>
     *
     * @return a {@link android.graphics.drawable.Drawable} instance
     *
     * @see #setIndeterminateDrawable(android.graphics.drawable.Drawable)
     * @see #setIndeterminate(boolean)
     */
    public Drawable getIndeterminateDrawable() {
        return mIndeterminateDrawable;
    }

    /**
     * <p>Define the drawable used to draw the progress bar in
     * indeterminate mode.</p>
     *
     * @param d the new drawable
     *
     * @see #getIndeterminateDrawable()
     * @see #setIndeterminate(boolean)
     */
    public void setIndeterminateDrawable(Drawable d) {
        if (d != null) {
            d.setCallback(this);
        }
        mIndeterminateDrawable = d;
        if (mIndeterminateDrawable != null && canResolveLayoutDirection()) {
            mIndeterminateDrawable.setLayoutDirection(getLayoutDirection());
        }
        if (mIndeterminate) {
            mCurrentDrawable = d;
            postInvalidate();
        }
    }
    
    /**
     * <p>Get the drawable used to draw the progress bar in
     * progress mode.</p>
     *
     * @return a {@link android.graphics.drawable.Drawable} instance
     *
     * @see #setProgressDrawable(android.graphics.drawable.Drawable)
     * @see #setIndeterminate(boolean)
     */
    public Drawable getProgressDrawable() {
        return mProgressDrawable;
    }

    /**
     * <p>Define the drawable used to draw the progress bar in
     * progress mode.</p>
     *
     * @param d the new drawable
     *
     * @see #getProgressDrawable()
     * @see #setIndeterminate(boolean)
     */
    public void setProgressDrawable(Drawable d) {
        boolean needUpdate;
        if (mProgressDrawable != null && d != mProgressDrawable) {
            mProgressDrawable.setCallback(null);
            needUpdate = true;
        } else {
            needUpdate = false;
        }

        if (d != null) {
            d.setCallback(this);
            if (canResolveLayoutDirection()) {
                d.setLayoutDirection(getLayoutDirection());
            }

            // Make sure the ProgressBar is always tall enough
            int drawableHeight = d.getMinimumHeight();
            if (mMaxHeight < drawableHeight) {
                mMaxHeight = drawableHeight;
                requestLayout();
            }
        }
        mProgressDrawable = d;
        if (!mIndeterminate) {
            mCurrentDrawable = d;
            postInvalidate();
        }

        if (needUpdate) {
            updateDrawableBounds(getWidth(), getHeight());
            updateDrawableState();
            doRefreshProgress(R.id.progress, mProgress, false, false);
            doRefreshProgress(R.id.secondaryProgress, mSecondaryProgress, false, false);
        }
    }
    
    /**
     * @return The drawable currently used to draw the progress bar
     */
    Drawable getCurrentDrawable() {
        return mCurrentDrawable;
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mProgressDrawable || who == mIndeterminateDrawable
                || super.verifyDrawable(who);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mProgressDrawable != null) mProgressDrawable.jumpToCurrentState();
        if (mIndeterminateDrawable != null) mIndeterminateDrawable.jumpToCurrentState();
    }

    /**
     * @hide
     */
    @Override
    public void onResolveDrawables(int layoutDirection) {
        final Drawable d = mCurrentDrawable;
        if (d != null) {
            d.setLayoutDirection(layoutDirection);
        }
        if (mIndeterminateDrawable != null) {
            mIndeterminateDrawable.setLayoutDirection(layoutDirection);
        }
        if (mProgressDrawable != null) {
            mProgressDrawable.setLayoutDirection(layoutDirection);
        }
    }

    @Override
    public void postInvalidate() {
        if (!mNoInvalidate) {
            super.postInvalidate();
        }
    }

    private class RefreshProgressRunnable implements Runnable {
        public void run() {
            synchronized (ProgressBar.this) {
                final int count = mRefreshData.size();
                for (int i = 0; i < count; i++) {
                    final RefreshData rd = mRefreshData.get(i);
                    doRefreshProgress(rd.id, rd.progress, rd.fromUser, true);
                    rd.recycle();
                }
                mRefreshData.clear();
                mRefreshIsPosted = false;
            }
        }
    }

    private static class RefreshData {
        private static final int POOL_MAX = 24;
        private static final SynchronizedPool<RefreshData> sPool =
                new SynchronizedPool<RefreshData>(POOL_MAX);

        public int id;
        public int progress;
        public boolean fromUser;

        public static RefreshData obtain(int id, int progress, boolean fromUser) {
            RefreshData rd = sPool.acquire();
            if (rd == null) {
                rd = new RefreshData();
            }
            rd.id = id;
            rd.progress = progress;
            rd.fromUser = fromUser;
            return rd;
        }
        
        public void recycle() {
            sPool.release(this);
        }
    }

    private synchronized void doRefreshProgress(int id, int progress, boolean fromUser,
            boolean callBackToApp) {
        float scale = mMax > 0 ? (float) progress / (float) mMax : 0;
        final Drawable d = mCurrentDrawable;
        if (d != null) {
            Drawable progressDrawable = null;

            if (d instanceof LayerDrawable) {
                progressDrawable = ((LayerDrawable) d).findDrawableByLayerId(id);
                if (progressDrawable != null && canResolveLayoutDirection()) {
                    progressDrawable.setLayoutDirection(getLayoutDirection());
                }
            }

            final int level = (int) (scale * MAX_LEVEL);
            (progressDrawable != null ? progressDrawable : d).setLevel(level);
        } else {
            invalidate();
        }
        
        if (callBackToApp && id == R.id.progress) {
            onProgressRefresh(scale, fromUser);
        }
    }

    void onProgressRefresh(float scale, boolean fromUser) {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            scheduleAccessibilityEventSender();
        }
    }

    private synchronized void refreshProgress(int id, int progress, boolean fromUser) {
        if (mUiThreadId == Thread.currentThread().getId()) {
            doRefreshProgress(id, progress, fromUser, true);
        } else {
            if (mRefreshProgressRunnable == null) {
                mRefreshProgressRunnable = new RefreshProgressRunnable();
            }

            final RefreshData rd = RefreshData.obtain(id, progress, fromUser);
            mRefreshData.add(rd);
            if (mAttached && !mRefreshIsPosted) {
                post(mRefreshProgressRunnable);
                mRefreshIsPosted = true;
            }
        }
    }
    
    /**
     * <p>Set the current progress to the specified value. Does not do anything
     * if the progress bar is in indeterminate mode.</p>
     *
     * @param progress the new progress, between 0 and {@link #getMax()}
     *
     * @see #setIndeterminate(boolean)
     * @see #isIndeterminate()
     * @see #getProgress()
     * @see #incrementProgressBy(int) 
     */
    @android.view.RemotableViewMethod
    public synchronized void setProgress(int progress) {
        setProgress(progress, false);
    }
    
    @android.view.RemotableViewMethod
    synchronized void setProgress(int progress, boolean fromUser) {
        if (mIndeterminate) {
            return;
        }

        if (progress < 0) {
            progress = 0;
        }

        if (progress > mMax) {
            progress = mMax;
        }

        if (progress != mProgress) {
            mProgress = progress;
            refreshProgress(R.id.progress, mProgress, fromUser);
        }
    }

    /**
     * <p>
     * Set the current secondary progress to the specified value. Does not do
     * anything if the progress bar is in indeterminate mode.
     * </p>
     * 
     * @param secondaryProgress the new secondary progress, between 0 and {@link #getMax()}
     * @see #setIndeterminate(boolean)
     * @see #isIndeterminate()
     * @see #getSecondaryProgress()
     * @see #incrementSecondaryProgressBy(int)
     */
    @android.view.RemotableViewMethod
    public synchronized void setSecondaryProgress(int secondaryProgress) {
        if (mIndeterminate) {
            return;
        }

        if (secondaryProgress < 0) {
            secondaryProgress = 0;
        }

        if (secondaryProgress > mMax) {
            secondaryProgress = mMax;
        }

        if (secondaryProgress != mSecondaryProgress) {
            mSecondaryProgress = secondaryProgress;
            refreshProgress(R.id.secondaryProgress, mSecondaryProgress, false);
        }
    }

    /**
     * <p>Get the progress bar's current level of progress. Return 0 when the
     * progress bar is in indeterminate mode.</p>
     *
     * @return the current progress, between 0 and {@link #getMax()}
     *
     * @see #setIndeterminate(boolean)
     * @see #isIndeterminate()
     * @see #setProgress(int)
     * @see #setMax(int)
     * @see #getMax()
     */
    @ViewDebug.ExportedProperty(category = "progress")
    public synchronized int getProgress() {
        return mIndeterminate ? 0 : mProgress;
    }

    /**
     * <p>Get the progress bar's current level of secondary progress. Return 0 when the
     * progress bar is in indeterminate mode.</p>
     *
     * @return the current secondary progress, between 0 and {@link #getMax()}
     *
     * @see #setIndeterminate(boolean)
     * @see #isIndeterminate()
     * @see #setSecondaryProgress(int)
     * @see #setMax(int)
     * @see #getMax()
     */
    @ViewDebug.ExportedProperty(category = "progress")
    public synchronized int getSecondaryProgress() {
        return mIndeterminate ? 0 : mSecondaryProgress;
    }

    /**
     * <p>Return the upper limit of this progress bar's range.</p>
     *
     * @return a positive integer
     *
     * @see #setMax(int)
     * @see #getProgress()
     * @see #getSecondaryProgress()
     */
    @ViewDebug.ExportedProperty(category = "progress")
    public synchronized int getMax() {
        return mMax;
    }

    /**
     * <p>Set the range of the progress bar to 0...<tt>max</tt>.</p>
     *
     * @param max the upper range of this progress bar
     *
     * @see #getMax()
     * @see #setProgress(int) 
     * @see #setSecondaryProgress(int) 
     */
    @android.view.RemotableViewMethod
    public synchronized void setMax(int max) {
        if (max < 0) {
            max = 0;
        }
        if (max != mMax) {
            mMax = max;
            postInvalidate();

            if (mProgress > max) {
                mProgress = max;
            }
            refreshProgress(R.id.progress, mProgress, false);
        }
    }
    
    /**
     * <p>Increase the progress bar's progress by the specified amount.</p>
     *
     * @param diff the amount by which the progress must be increased
     *
     * @see #setProgress(int) 
     */
    public synchronized final void incrementProgressBy(int diff) {
        setProgress(mProgress + diff);
    }

    /**
     * <p>Increase the progress bar's secondary progress by the specified amount.</p>
     *
     * @param diff the amount by which the secondary progress must be increased
     *
     * @see #setSecondaryProgress(int) 
     */
    public synchronized final void incrementSecondaryProgressBy(int diff) {
        setSecondaryProgress(mSecondaryProgress + diff);
    }

    /**
     * <p>Start the indeterminate progress animation.</p>
     */
    void startAnimation() {
        if (getVisibility() != VISIBLE) {
            return;
        }

        if (mIndeterminateDrawable instanceof Animatable) {
            mShouldStartAnimationDrawable = true;
            mHasAnimation = false;
        } else {
            mHasAnimation = true;

            if (mInterpolator == null) {
                mInterpolator = new LinearInterpolator();
            }
    
            if (mTransformation == null) {
                mTransformation = new Transformation();
            } else {
                mTransformation.clear();
            }
            
            if (mAnimation == null) {
                mAnimation = new AlphaAnimation(0.0f, 1.0f);
            } else {
                mAnimation.reset();
            }

            mAnimation.setRepeatMode(mBehavior);
            mAnimation.setRepeatCount(Animation.INFINITE);
            mAnimation.setDuration(mDuration);
            mAnimation.setInterpolator(mInterpolator);
            mAnimation.setStartTime(Animation.START_ON_FIRST_FRAME);
        }
        postInvalidate();
    }

    /**
     * <p>Stop the indeterminate progress animation.</p>
     */
    void stopAnimation() {
        mHasAnimation = false;
        if (mIndeterminateDrawable instanceof Animatable) {
            ((Animatable) mIndeterminateDrawable).stop();
            mShouldStartAnimationDrawable = false;
        }
        postInvalidate();
    }

    /**
     * Sets the acceleration curve for the indeterminate animation.
     * The interpolator is loaded as a resource from the specified context.
     *
     * @param context The application environment
     * @param resID The resource identifier of the interpolator to load
     */
    public void setInterpolator(Context context, int resID) {
        setInterpolator(AnimationUtils.loadInterpolator(context, resID));
    }

    /**
     * Sets the acceleration curve for the indeterminate animation.
     * Defaults to a linear interpolation.
     *
     * @param interpolator The interpolator which defines the acceleration curve
     */
    public void setInterpolator(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    /**
     * Gets the acceleration curve type for the indeterminate animation.
     *
     * @return the {@link Interpolator} associated to this animation
     */
    public Interpolator getInterpolator() {
        return mInterpolator;
    }

    @Override
    @RemotableViewMethod
    public void setVisibility(int v) {
        if (getVisibility() != v) {
            super.setVisibility(v);

            if (mIndeterminate) {
                // let's be nice with the UI thread
                if (v == GONE || v == INVISIBLE) {
                    stopAnimation();
                } else {
                    startAnimation();
                }
            }
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (mIndeterminate) {
            // let's be nice with the UI thread
            if (visibility == GONE || visibility == INVISIBLE) {
                stopAnimation();
            } else {
                startAnimation();
            }
        }
    }

    @Override
    public void invalidateDrawable(Drawable dr) {
        if (!mInDrawing) {
            if (verifyDrawable(dr)) {
                final Rect dirty = dr.getBounds();

                invalidate(dirty.left + mScrollX, dirty.top + mScrollY,
                        dirty.right + mScrollX, dirty.bottom + mScrollY);
            } else {
                super.invalidateDrawable(dr);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateDrawableBounds(w, h);
    }

    private void updateDrawableBounds(int w, int h) {
        // onDraw will translate the canvas so we draw starting at 0,0.
        // Subtract out padding for the purposes of the calculations below.
        w -= mPaddingRight + mPaddingLeft;
        h -= mPaddingTop + mPaddingBottom;

        int right = w;
        int bottom = h;
        int top = 0;
        int left = 0;

        if (mIndeterminateDrawable != null) {
            // Aspect ratio logic does not apply to AnimationDrawables
            if (mOnlyIndeterminate && !(mIndeterminateDrawable instanceof AnimationDrawable)) {
                // Maintain aspect ratio. Certain kinds of animated drawables
                // get very confused otherwise.
                final int intrinsicWidth = mIndeterminateDrawable.getIntrinsicWidth();
                final int intrinsicHeight = mIndeterminateDrawable.getIntrinsicHeight();
                final float intrinsicAspect = (float) intrinsicWidth / intrinsicHeight;
                final float boundAspect = (float) w / h;
                if (intrinsicAspect != boundAspect) {
                    if (boundAspect > intrinsicAspect) {
                        // New width is larger. Make it smaller to match height.
                        final int width = (int) (h * intrinsicAspect);
                        left = (w - width) / 2;
                        right = left + width;
                    } else {
                        // New height is larger. Make it smaller to match width.
                        final int height = (int) (w * (1 / intrinsicAspect));
                        top = (h - height) / 2;
                        bottom = top + height;
                    }
                }
            }
            if (isLayoutRtl() && mMirrorForRtl) {
                int tempLeft = left;
                left = w - right;
                right = w - tempLeft;
            }
            mIndeterminateDrawable.setBounds(left, top, right, bottom);
        }
        
        if (mProgressDrawable != null) {
            mProgressDrawable.setBounds(0, 0, right, bottom);
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Drawable d = mCurrentDrawable;
        if (d != null) {
            // Translate canvas so a indeterminate circular progress bar with padding
            // rotates properly in its animation
            canvas.save();
            if(isLayoutRtl() && mMirrorForRtl) {
                canvas.translate(getWidth() - mPaddingRight, mPaddingTop);
                canvas.scale(-1.0f, 1.0f);
            } else {
                canvas.translate(mPaddingLeft, mPaddingTop);
            }
            long time = getDrawingTime();
            if (mHasAnimation) {
                mAnimation.getTransformation(time, mTransformation);
                float scale = mTransformation.getAlpha();
                try {
                    mInDrawing = true;
                    d.setLevel((int) (scale * MAX_LEVEL));
                } finally {
                    mInDrawing = false;
                }
                postInvalidateOnAnimation();
            }
            d.draw(canvas);
            canvas.restore();
            if (mShouldStartAnimationDrawable && d instanceof Animatable) {
                ((Animatable) d).start();
                mShouldStartAnimationDrawable = false;
            }
        }
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable d = mCurrentDrawable;

        int dw = 0;
        int dh = 0;
        if (d != null) {
            dw = Math.max(mMinWidth, Math.min(mMaxWidth, d.getIntrinsicWidth()));
            dh = Math.max(mMinHeight, Math.min(mMaxHeight, d.getIntrinsicHeight()));
        }
        updateDrawableState();
        dw += mPaddingLeft + mPaddingRight;
        dh += mPaddingTop + mPaddingBottom;

        setMeasuredDimension(resolveSizeAndState(dw, widthMeasureSpec, 0),
                resolveSizeAndState(dh, heightMeasureSpec, 0));
    }
    
    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateDrawableState();
    }
        
    private void updateDrawableState() {
        int[] state = getDrawableState();
        
        if (mProgressDrawable != null && mProgressDrawable.isStateful()) {
            mProgressDrawable.setState(state);
        }
        
        if (mIndeterminateDrawable != null && mIndeterminateDrawable.isStateful()) {
            mIndeterminateDrawable.setState(state);
        }
    }

    static class SavedState extends BaseSavedState {
        int progress;
        int secondaryProgress;
        
        /**
         * Constructor called from {@link ProgressBar#onSaveInstanceState()}
         */
        SavedState(Parcelable superState) {
            super(superState);
        }
        
        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            progress = in.readInt();
            secondaryProgress = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(progress);
            out.writeInt(secondaryProgress);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        // Force our ancestor class to save its state
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        
        ss.progress = mProgress;
        ss.secondaryProgress = mSecondaryProgress;
        
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        
        setProgress(ss.progress);
        setSecondaryProgress(ss.secondaryProgress);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mIndeterminate) {
            startAnimation();
        }
        if (mRefreshData != null) {
            synchronized (this) {
                final int count = mRefreshData.size();
                for (int i = 0; i < count; i++) {
                    final RefreshData rd = mRefreshData.get(i);
                    doRefreshProgress(rd.id, rd.progress, rd.fromUser, true);
                    rd.recycle();
                }
                mRefreshData.clear();
            }
        }
        mAttached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mIndeterminate) {
            stopAnimation();
        }
        if (mRefreshProgressRunnable != null) {
            removeCallbacks(mRefreshProgressRunnable);
        }
        if (mRefreshProgressRunnable != null && mRefreshIsPosted) {
            removeCallbacks(mRefreshProgressRunnable);
        }
        if (mAccessibilityEventSender != null) {
            removeCallbacks(mAccessibilityEventSender);
        }
        // This should come after stopAnimation(), otherwise an invalidate message remains in the
        // queue, which can prevent the entire view hierarchy from being GC'ed during a rotation
        super.onDetachedFromWindow();
        mAttached = false;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(ProgressBar.class.getName());
        event.setItemCount(mMax);
        event.setCurrentItemIndex(mProgress);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(ProgressBar.class.getName());
    }

    /**
     * Schedule a command for sending an accessibility event.
     * </br>
     * Note: A command is used to ensure that accessibility events
     *       are sent at most one in a given time frame to save
     *       system resources while the progress changes quickly.
     */
    private void scheduleAccessibilityEventSender() {
        if (mAccessibilityEventSender == null) {
            mAccessibilityEventSender = new AccessibilityEventSender();
        } else {
            removeCallbacks(mAccessibilityEventSender);
        }
        postDelayed(mAccessibilityEventSender, TIMEOUT_SEND_ACCESSIBILITY_EVENT);
    }

    /**
     * Command for sending an accessibility event.
     */
    private class AccessibilityEventSender implements Runnable {
        public void run() {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
    }
}
