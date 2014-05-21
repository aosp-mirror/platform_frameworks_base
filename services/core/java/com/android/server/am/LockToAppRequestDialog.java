
package com.android.server.am;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.Slog;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;

public class LockToAppRequestDialog implements OnClickListener {
    private static final String TAG = "ActivityManager";

    final private Context mContext;
    final private ActivityManagerService mService;

    private AlertDialog mDialog;
    private TaskRecord mRequestedTask;

    public LockToAppRequestDialog(Context context, ActivityManagerService activityManagerService) {
        mContext = context;
        mService = activityManagerService;
    }

    public void showLockTaskPrompt(TaskRecord task) {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        mRequestedTask = task;

        final Resources r = Resources.getSystem();
        final String descriptionString = r.getString(R.string.lock_to_app_description);
        final SpannableString description =
                new SpannableString(descriptionString.replace('$', ' '));
        final ImageSpan imageSpan = new ImageSpan(mContext,
                BitmapFactory.decodeResource(r, R.drawable.ic_recent),
                DynamicDrawableSpan.ALIGN_BOTTOM);
        final int index = descriptionString.indexOf('$');
        if (index >= 0) {
            description.setSpan(imageSpan, index, index + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mDialog =
                new AlertDialog.Builder(mContext)
                        .setTitle(r.getString(R.string.lock_to_app_title))
                        .setMessage(description)
                        .setPositiveButton(r.getString(R.string.lock_to_app_positive), this)
                        .setNegativeButton(r.getString(R.string.lock_to_app_negative), this)
                        .create();

        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mDialog.show();

        // Make icon fit.
        final TextView msgTxt = (TextView) mDialog.findViewById(R.id.message);
        final float width = imageSpan.getDrawable().getIntrinsicWidth();
        final float height = imageSpan.getDrawable().getIntrinsicHeight();
        final int lineHeight = msgTxt.getLineHeight();
        imageSpan.getDrawable().setBounds(0, 0, (int) (lineHeight * width / height), lineHeight);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (DialogInterface.BUTTON_POSITIVE == which) {
            Slog.d(TAG, "accept lock-to-app request");
            // Automatically enable if not currently on. (Could be triggered by an app)
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.LOCK_TO_APP_ENABLED, 1);

            // Start lock-to-app.
            mService.startLockTaskMode(mRequestedTask);
        } else {
            Slog.d(TAG, "ignore lock-to-app request");
        }
    }

}
