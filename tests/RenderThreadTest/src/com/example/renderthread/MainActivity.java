
package com.example.renderthread;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.RenderNodeAnimator;
import android.view.View;
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

    @Override
    public void onItemClick(final AdapterView<?> adapterView, View clickedView,
            int clickedPosition, long clickedId) {
        int topPosition = adapterView.getFirstVisiblePosition();
        int dy = adapterView.getHeight();
        for (int i = 0; i < adapterView.getChildCount(); i++) {
            int pos = topPosition + i;
            View child = adapterView.getChildAt(i);
            float delta = (pos - clickedPosition) * 1.1f;
            if (delta == 0) delta = -1;
            RenderNodeAnimator animator = new RenderNodeAnimator(
                    RenderNodeAnimator.TRANSLATION_Y, dy * delta);
            animator.setDuration(DURATION);
            if (child == clickedView) logTranslationY(clickedView);
            animator.setTarget(child);
            animator.start();
            if (child == clickedView) logTranslationY(clickedView);
        }
        //mHandler.postDelayed(mLaunchActivity, (long) (DURATION * .4));
        mLaunchActivity.run();
    }

    private void logTranslationY(View v) {
        Log.d("RTTest", "View has translationY: " + v.getTranslationY());
    }

    private Runnable mLaunchActivity = new Runnable() {

        @Override
        public void run() {
            startActivity(new Intent(MainActivity.this, SubActivity.class));
            overridePendingTransition(0, 0);
        }
    };

}
