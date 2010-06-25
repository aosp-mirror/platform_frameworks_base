package com.android.statusbartest;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class TestAlertActivity extends Activity {
    int mId;

    @Override
    public void onResume() {
        super.onResume();
        Log.d("StatusBarTest", "TestAlertActivity.onResume");
        Intent intent = getIntent();
        mId = intent.getIntExtra("id", -1);
        Log.d("StatusBarTest", "Remembering notification id=" + mId);
        setContentView(R.layout.test_alert);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d("StatusBarTest", "onPause: Canceling notification id=" + mId);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(mId);
        finish();
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void dismiss(View v) {
        Log.d("StatusBarTest", "TestAlertActivity.dismiss");
        finish();
    }
}
