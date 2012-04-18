package com.android.test.layout;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.Space;
import android.widget.TextView;

import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
import static android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
import static android.widget.GridLayout.*;
import static android.widget.GridLayout.FILL;
import static android.widget.GridLayout.spec;

public class LayoutInsetsTest extends Activity {
    public static View create(Context context) {
        GridLayout p = new GridLayout(context);
        p.setUseDefaultMargins(true);
        p.setAlignmentMode(ALIGN_BOUNDS);
        p.setOrientation(VERTICAL);

        {
            TextView c = new TextView(context);
            c.setTextSize(32);
            c.setText("Email setup");
            p.addView(c);
        }
        {
            Button c = new Button(context);
            c.setBackgroundResource(R.drawable.btn_default);
            c.setText("Test");
            p.addView(c);
        }

        {
            Button c = new Button(context);
            c.setBackgroundResource(R.drawable.btn_default);
            c.setText("Manual setup");
            p.addView(c);
            c.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Button b = (Button) v;
                    b.setEnabled(false);
                }
            });
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
