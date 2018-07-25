package com.android.packageinstaller.permission.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Space;

import androidx.wear.ble.view.AcceptDenyDialog;
import androidx.wear.ble.view.WearableDialogHelper;

import com.android.packageinstaller.R;

/**
 * Watch-specific view handler for the grant permissions activity.
 */
final class GrantPermissionsWatchViewHandler implements GrantPermissionsViewHandler,
        DialogInterface.OnClickListener {
    private static final String TAG = "GrantPermsWatchViewH";

    private static final String WATCH_HANDLER_BUNDLE = "watch_handler_bundle";
    private static final String DIALOG_BUNDLE = "dialog_bundle";
    private static final String GROUP_NAME = "group_name";
    private static final String SHOW_DO_NOT_ASK = "show_do_not_ask";
    private static final String ICON = "icon";
    private static final String MESSAGE = "message";
    private static final String CURRENT_PAGE_TEXT = "current_page_text";

    private final Context mContext;

    private ResultListener mResultListener;

    private Dialog mDialog;

    private String mGroupName;
    private boolean mShowDoNotAsk;

    private CharSequence mMessage;
    private String mCurrentPageText;
    private Icon mIcon;

    GrantPermissionsWatchViewHandler(Context context) {
        mContext = context;
    }

    @Override
    public GrantPermissionsWatchViewHandler setResultListener(ResultListener listener) {
        mResultListener = listener;
        return this;
    }

    @Override
    public View createView() {
        return new Space(mContext);
    }

    @Override
    public void updateWindowAttributes(WindowManager.LayoutParams outLayoutParams) {
        outLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        outLayoutParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        outLayoutParams.format = PixelFormat.OPAQUE;
        outLayoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        outLayoutParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    }

    @Override
    public void updateUi(String groupName, int groupCount, int groupIndex, Icon icon,
            CharSequence message, CharSequence detailMessage, boolean showForegroundChooser,
            boolean showDoNotAsk) {
        // TODO: Handle detailMessage
        // TODO: Handle showForegroundChooser

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "updateUi() - groupName: " + groupName
                            + ", groupCount: " + groupCount
                            + ", groupIndex: " + groupIndex
                            + ", icon: " + icon
                            + ", message: " + message
                            + ", showDoNotAsk: " + showDoNotAsk);
        }

        mGroupName = groupName;
        mShowDoNotAsk = showDoNotAsk;
        mMessage = message;
        mIcon = icon;
        mCurrentPageText = groupCount > 1
                ? mContext.getString(R.string.current_permission_template,
                        groupIndex + 1, groupCount)
                : null;
        showDialog(null);
    }

    private void showDialog(Bundle savedInstanceState) {
        TypedArray a = mContext.obtainStyledAttributes(
                new int[] { android.R.attr.textColorPrimary });
        int color = a.getColor(0, mContext.getColor(android.R.color.white));
        a.recycle();
        Drawable drawable = mIcon == null ? null : mIcon.setTint(color).loadDrawable(mContext);

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(mCurrentPageText)) {
            ssb.append(mCurrentPageText, new TextAppearanceSpan(mContext, R.style.BreadcrumbText),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.append('\n');
        }
        if (!TextUtils.isEmpty(mMessage)) {
            ssb.append(mMessage, new TextAppearanceSpan(mContext, R.style.TitleText),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        if (mShowDoNotAsk) {
            AlertDialog alertDialog = new WearableDialogHelper.DialogBuilder(mContext)
                    .setPositiveIcon(R.drawable.confirm_button)
                    .setNeutralIcon(R.drawable.cancel_button)
                    .setNegativeIcon(R.drawable.deny_button)
                    .setTitle(ssb)
                    .setIcon(drawable)
                    .setPositiveButton(R.string.grant_dialog_button_allow, this)
                    .setNeutralButton(R.string.grant_dialog_button_deny, this)
                    .setNegativeButton(R.string.grant_dialog_button_deny_dont_ask_again, this)
                    .show();
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    .setId(R.id.permission_allow_button);
            alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                    .setId(R.id.permission_deny_button);
            alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                    .setId(R.id.permission_deny_dont_ask_again_button);

            mDialog = alertDialog;
        } else {
            AcceptDenyDialog acceptDenyDialog = new AcceptDenyDialog(mContext);
            acceptDenyDialog.setTitle(ssb);
            acceptDenyDialog.setIcon(drawable);
            acceptDenyDialog.setPositiveButton(this);
            acceptDenyDialog.setNegativeButton(this);
            acceptDenyDialog.show();
            acceptDenyDialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    .setId(R.id.permission_allow_button);
            acceptDenyDialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                    .setId(R.id.permission_deny_button);

            mDialog = acceptDenyDialog;
        }
        mDialog.setCancelable(false);

        if (savedInstanceState != null) {
            mDialog.onRestoreInstanceState(savedInstanceState);
        }
    }

    @Override
    public void saveInstanceState(Bundle outState) {
        Bundle b = new Bundle();
        b.putByte(SHOW_DO_NOT_ASK, (byte) (mShowDoNotAsk ? 1 : 0));
        b.putString(GROUP_NAME, mGroupName);
        b.putBundle(DIALOG_BUNDLE, mDialog.onSaveInstanceState());

        outState.putBundle(WATCH_HANDLER_BUNDLE, b);
    }

    @Override
    public void loadInstanceState(Bundle savedInstanceState) {
        Bundle b = savedInstanceState.getBundle(WATCH_HANDLER_BUNDLE);
        mShowDoNotAsk = b.getByte(SHOW_DO_NOT_ASK) == 1;
        mGroupName = b.getString(GROUP_NAME);
        showDialog(b.getBundle(DIALOG_BUNDLE));
    }

    @Override
    public void onBackPressed() {
        notifyListener(DENIED);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                notifyListener(GRANTED_ALWAYS);
                break;
            case DialogInterface.BUTTON_NEUTRAL:
                notifyListener(DENIED);
                break;
            case DialogInterface.BUTTON_NEGATIVE:
                /* In AlertDialog, the negative button is also a don't ask again button. */
                if (dialog instanceof AlertDialog) {
                    notifyListener(DENIED_DO_NOT_ASK_AGAIN);
                } else {
                    notifyListener(DENIED);
                }
                break;
        }
    }

    private void notifyListener(@Result int result) {
        if (mResultListener != null) {
            mResultListener.onPermissionGrantResult(mGroupName, result);
        }
    }
}
