package com.android.server.am;

import com.android.internal.R;

import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

public class LaunchWarningWindow extends Dialog {
    public LaunchWarningWindow(Context context, ActivityRecord cur, ActivityRecord next) {
        super(context, R.style.Theme_Toast);

        requestWindowFeature(Window.FEATURE_LEFT_ICON);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
        setContentView(R.layout.launch_warning);
        setTitle(context.getText(R.string.launch_warning_title));
        getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                R.drawable.ic_dialog_alert);
        ImageView icon = (ImageView)findViewById(R.id.replace_app_icon);
        icon.setImageDrawable(next.info.applicationInfo.loadIcon(context.getPackageManager()));
        TextView text = (TextView)findViewById(R.id.replace_message);
        text.setText(context.getResources().getString(R.string.launch_warning_replace,
                next.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
        icon = (ImageView)findViewById(R.id.original_app_icon);
        icon.setImageDrawable(cur.info.applicationInfo.loadIcon(context.getPackageManager()));
        text = (TextView)findViewById(R.id.original_message);
        text.setText(context.getResources().getString(R.string.launch_warning_original,
                cur.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
    }
}
