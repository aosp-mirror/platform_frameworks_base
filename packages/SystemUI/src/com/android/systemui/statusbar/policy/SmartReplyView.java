package com.android.systemui.statusbar.policy;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;

/** View which displays smart reply buttons in notifications. */
public class SmartReplyView extends LinearLayout {

    private static final String TAG = "SmartReplyView";

    private final SmartReplyConstants mConstants;

    public SmartReplyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mConstants = Dependency.get(SmartReplyConstants.class);
    }

    public void setRepliesFromRemoteInput(RemoteInput remoteInput, PendingIntent pendingIntent) {
        removeAllViews();
        if (remoteInput != null && pendingIntent != null) {
            CharSequence[] choices = remoteInput.getChoices();
            if (choices != null) {
                for (CharSequence choice : choices) {
                    Button replyButton = inflateReplyButton(
                            getContext(), this, choice, remoteInput, pendingIntent);
                    addView(replyButton);
                }
            }
        }
    }

    public static SmartReplyView inflate(Context context, ViewGroup root) {
        return (SmartReplyView)
                LayoutInflater.from(context).inflate(R.layout.smart_reply_view, root, false);
    }

    private static Button inflateReplyButton(Context context, ViewGroup root, CharSequence choice,
            RemoteInput remoteInput, PendingIntent pendingIntent) {
        Button b = (Button) LayoutInflater.from(context).inflate(
                R.layout.smart_reply_button, root, false);
        b.setText(choice);
        b.setOnClickListener(view -> {
            Bundle results = new Bundle();
            results.putString(remoteInput.getResultKey(), choice.toString());
            Intent intent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            RemoteInput.addResultsToIntent(new RemoteInput[]{remoteInput}, intent, results);
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE);
            try {
                pendingIntent.send(context, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "Unable to send smart reply", e);
            }
        });
        return b;
    }
}
