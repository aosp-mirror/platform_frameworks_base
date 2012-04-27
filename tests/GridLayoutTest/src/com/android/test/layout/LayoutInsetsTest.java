package com.android.test.layout;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import static android.widget.GridLayout.ALIGN_BOUNDS;
import static android.widget.GridLayout.LayoutParams;
import static android.widget.GridLayout.OPTICAL_BOUNDS;

public class LayoutInsetsTest extends Activity {
    static int[] GRAVITIES = {Gravity.LEFT, Gravity.LEFT, Gravity.CENTER_HORIZONTAL, Gravity.RIGHT, Gravity.RIGHT};

    public static View create(Context context) {
        final int N = GRAVITIES.length;

        GridLayout p = new GridLayout(context);
        p.setUseDefaultMargins(true);
        //p.setAlignmentMode(ALIGN_BOUNDS);
        p.setLayoutMode(OPTICAL_BOUNDS);

        p.setColumnCount(N);

        for (int i = 0; i < 2*N; i++) {
            View c;
            if (i % 2 == 0) {
                TextView tv = new TextView(context);
                tv.setTextSize(32);
                tv.setText("A");
                c = tv;
            } else {
                Button b = new Button(context);
                b.setBackgroundResource(R.drawable.btn_default_normal);
                b.setText("B");
                c = b;
            }

            LayoutParams lp = new LayoutParams();
            lp.setGravity(GRAVITIES[(i % N)]);
            p.addView(c, lp);

        }


        return p;
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.ICE_CREAM_SANDWICH;
        getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.JELLY_BEAN;
        setContentView(create(this));
    }
}
