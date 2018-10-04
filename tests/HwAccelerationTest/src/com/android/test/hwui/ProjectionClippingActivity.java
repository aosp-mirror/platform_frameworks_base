package com.android.test.hwui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

public class ProjectionClippingActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.projection_clipping);
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // woo! nothing!
            }
        };
        findViewById(R.id.clickable1).setOnClickListener(listener);
        findViewById(R.id.clickable2).setOnClickListener(listener);
    }
}
