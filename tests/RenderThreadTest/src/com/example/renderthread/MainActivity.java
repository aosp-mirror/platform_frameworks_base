
package com.example.renderthread;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.RenderNode;
import android.view.HardwareRenderer;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity implements OnItemClickListener {

    static final int DURATION = 400;

    static final String KEY_NAME = "name";
    static final String KEY_CLASS = "clazz";

    static Map<String,?> make(String name) {
        Map<String,Object> ret = new HashMap<String,Object>();
        ret.put(KEY_NAME, name);
        return ret;
    }

    @SuppressWarnings("serial")
    static final ArrayList<Map<String,?>> SAMPLES = new ArrayList<Map<String,?>>() {{
        for (int i = 1; i < 25; i++) {
            add(make("List Item: " + i));
        }
    }};

    Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HardwareRenderer.sUseRenderThread = true;
        setContentView(R.layout.activity_main);
        ListView lv = (ListView) findViewById(android.R.id.list);
        lv.setDrawSelectorOnTop(true);
        lv.setAdapter(new SimpleAdapter(this, SAMPLES,
                R.layout.item_layout, new String[] { KEY_NAME },
                new int[] { android.R.id.text1 }));
        lv.setOnItemClickListener(this);
        getActionBar().setTitle("MainActivity");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ListView lv = (ListView) findViewById(android.R.id.list);
        for (int i = 0; i < lv.getChildCount(); i++) {
            lv.getChildAt(i).animate().translationY(0).setDuration(DURATION);
        }
    }

    private static class DisplayListAnimator {
        private static final TimeInterpolator sDefaultInterpolator =
                new AccelerateDecelerateInterpolator();

        RenderNode mDisplayList;
        float mFromValue;
        float mDelta;
        long mDuration = DURATION * 2;
        long mStartTime;

        DisplayListAnimator(View view, float translateXBy) {
            mDelta = translateXBy;
            mFromValue = view.getTranslationY();
            mDisplayList = view.getDisplayList();
        }

        boolean animate(long currentTime) {
            if (mStartTime == 0) mStartTime = currentTime;

            float fraction = (float)(currentTime - mStartTime) / mDuration;
            if (fraction > 1) {
                return false;
            }
            fraction = sDefaultInterpolator.getInterpolation(fraction);
            float translation = mFromValue + (mDelta * fraction);
            mDisplayList.setTranslationY(translation);
            return fraction < 1f;
        }
    }

    private static class AnimationExecutor implements Runnable {
        DisplayListAnimator[] mAnimations;
        ThreadedRenderer mRenderer;

        AnimationExecutor(ThreadedRenderer renderer, DisplayListAnimator[] animations) {
            mRenderer = renderer;
            mAnimations = animations;
            ThreadedRenderer.postToRenderThread(this);
        }

        @Override
        public void run() {
            boolean hasMore = false;
            long now = SystemClock.uptimeMillis();
            for (DisplayListAnimator animator : mAnimations) {
                hasMore |= animator.animate(now);
            }
            mRenderer.repeatLastDraw();
            if (hasMore) {
                ThreadedRenderer.postToRenderThread(this);
            }
        }

    }

    @Override
    public void onItemClick(final AdapterView<?> adapterView, View clickedView,
            int clickedPosition, long clickedId) {
        int topPosition = adapterView.getFirstVisiblePosition();
        int dy = adapterView.getHeight();
        final DisplayListAnimator[] animators = new DisplayListAnimator[adapterView.getChildCount()];
        for (int i = 0; i < adapterView.getChildCount(); i++) {
            int pos = topPosition + i;
            View child = adapterView.getChildAt(i);
            float delta = (pos - clickedPosition) * 1.1f;
            if (delta == 0) delta = -1;
            animators[i] = new DisplayListAnimator(child, dy * delta);
        }
        adapterView.invalidate();
        adapterView.post(new Runnable() {

            @Override
            public void run() {
                new AnimationExecutor((ThreadedRenderer) adapterView.getHardwareRenderer(), animators);
            }
        });
        //mHandler.postDelayed(mLaunchActivity, (long) (DURATION * .4));
        mLaunchActivity.run();
    }

    private Runnable mLaunchActivity = new Runnable() {

        @Override
        public void run() {
            startActivity(new Intent(MainActivity.this, SubActivity.class));
            overridePendingTransition(0, 0);
        }
    };

}
