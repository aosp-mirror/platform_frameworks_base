package com.android.statusbartest;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

public class ConfirmationActivity extends Activity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";

    @Override
    public void onResume() {
        super.onResume();
        setContentView(R.layout.confirmation_activity);
        setTitle(getTextExtra(EXTRA_TITLE, "Title"));
        ((TextView)findViewById(R.id.text)).setText(getTextExtra(EXTRA_TEXT, "text"));
        findViewById(R.id.ok).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    finish();
                }
            });
    }

    private String getTextExtra(String extra, String def) {
        final String text = getIntent().getStringExtra(extra);
        if (text == null) {
            return def;
        } else {
            return text;
        }
    }
}
