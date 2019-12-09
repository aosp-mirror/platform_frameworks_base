package com.android.systemui.bubbles;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;

/**
 * Activity for showing aged out bubbles.
 * Must be public to be accessible to androidx...AppComponentFactory
 */
public class BubbleOverflowActivity extends Activity {
    private RecyclerView mRecyclerView;
    private int mMaxBubbles;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bubble_overflow_activity);
        setBackgroundColor();

        mMaxBubbles = getResources().getInteger(R.integer.bubbles_max_rendered);
        mRecyclerView = findViewById(R.id.bubble_overflow_recycler);
        mRecyclerView.setLayoutManager(
                new GridLayoutManager(getApplicationContext(), /* numberOfColumns */ mMaxBubbles));
    }

    void setBackgroundColor() {
        final TypedArray ta = getApplicationContext().obtainStyledAttributes(
                new int[] {android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();
        findViewById(android.R.id.content).setBackgroundColor(bgColor);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onRestart() {
        super.onRestart();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    public void onDestroy() {
        super.onStop();
    }
}
