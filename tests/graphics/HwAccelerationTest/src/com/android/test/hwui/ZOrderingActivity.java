package com.android.test.hwui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

public class ZOrderingActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.z_ordering);

        ViewGroup grandParent = findViewById(R.id.parent);
        if (grandParent == null) throw new IllegalStateException();
        View.OnClickListener l = new View.OnClickListener() {
            @Override
            public void onClick(View v) {}
        };
        for (int i = 0; i < grandParent.getChildCount(); i++) {
            ViewGroup parent = (ViewGroup) grandParent.getChildAt(i);
            for (int j = 0; j < parent.getChildCount(); j++) {
                parent.getChildAt(j).setOnClickListener(l);
            }
        }
    }
}
