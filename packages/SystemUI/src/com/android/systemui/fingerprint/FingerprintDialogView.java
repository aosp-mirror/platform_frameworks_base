/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.fingerprint;

import android.animation.Animator;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintDialog;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Fingerprint Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FingerprintDialogView extends LinearLayout {

    private static final String TAG = "FingerprintDialogView";

    private static final int ANIMATION_VERTICAL_OFFSET_DP = 96;
    private static final int ANIMATION_DURATION = 250; // ms

    private final IBinder mWindowToken = new Binder();
    private final WindowManager mWindowManager;
    private final ActivityManagerWrapper mActivityManagerWrapper;
    private final PackageManagerWrapper mPackageManageWrapper;
    private final Interpolator mLinearOutSlowIn;
    private final Interpolator mFastOutLinearIn;

    private ViewGroup mLayout;
    private final TextView mErrorText;
    private Handler mHandler;
    private Bundle mBundle;
    private final float mDensity;
    private final LinearLayout mDialog;

    public FingerprintDialogView(Context context, Handler handler) {
        super(context);
        mHandler = handler;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mPackageManageWrapper = PackageManagerWrapper.getInstance();
        mLinearOutSlowIn = AnimationUtils
                .loadInterpolator(getContext(), android.R.interpolator.linear_out_slow_in);
        mFastOutLinearIn = AnimationUtils
                .loadInterpolator(getContext(), android.R.interpolator.fast_out_linear_in);

        // Create the dialog
        LayoutInflater factory = LayoutInflater.from(getContext());
        mLayout = (ViewGroup) factory.inflate(R.layout.fingerprint_dialog, this, false);
        addView(mLayout);

        mDialog = mLayout.findViewById(R.id.dialog);
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        mErrorText = mLayout.findViewById(R.id.error);

        mLayout.setOnKeyListener(new View.OnKeyListener() {
            boolean downPressed = false;
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && downPressed == false) {
                    downPressed = true;
                } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    downPressed = false;
                } else if (event.getAction() == KeyEvent.ACTION_UP && downPressed == true) {
                    downPressed = false;
                    mHandler.obtainMessage(FingerprintDialogImpl.MSG_USER_CANCELED).sendToTarget();
                }
                return true;
            }
        });

        final View space = mLayout.findViewById(R.id.space);
        final Button negative = mLayout.findViewById(R.id.button2);
        final Button positive = mLayout.findViewById(R.id.button1);

        space.setClickable(true);
        space.setOnTouchListener((View view, MotionEvent event) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG, true /* userCanceled*/)
                    .sendToTarget();
            return true;
        });

        negative.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_NEGATIVE).sendToTarget();
        });

        positive.setOnClickListener((View v) -> {
            mHandler.obtainMessage(FingerprintDialogImpl.MSG_BUTTON_POSITIVE).sendToTarget();
        });

        mLayout.setFocusableInTouchMode(true);
        mLayout.requestFocus();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        final TextView title = mLayout.findViewById(R.id.title);
        final TextView subtitle = mLayout.findViewById(R.id.subtitle);
        final TextView description = mLayout.findViewById(R.id.description);
        final Button negative = mLayout.findViewById(R.id.button2);
        final ImageView image = mLayout.findViewById(R.id.icon);
        final Button positive = mLayout.findViewById(R.id.button1);
        final ImageView fingerprint_icon = mLayout.findViewById(R.id.fingerprint_icon);

        title.setText(mBundle.getCharSequence(FingerprintDialog.KEY_TITLE));
        title.setSelected(true);
        subtitle.setText(mBundle.getCharSequence(FingerprintDialog.KEY_SUBTITLE));
        description.setText(mBundle.getCharSequence(FingerprintDialog.KEY_DESCRIPTION));
        negative.setText(mBundle.getCharSequence(FingerprintDialog.KEY_NEGATIVE_TEXT));
        image.setImageDrawable(getAppIcon());

        final CharSequence positiveText =
                mBundle.getCharSequence(FingerprintDialog.KEY_POSITIVE_TEXT);
        positive.setText(positiveText); // needs to be set for marquee to work
        if (positiveText != null) {
            positive.setVisibility(View.VISIBLE);
        } else {
            positive.setVisibility(View.GONE);
        }

        // Dim the background and slide the dialog up
        mDialog.setTranslationY(ANIMATION_VERTICAL_OFFSET_DP * mDensity);
        mLayout.setAlpha(0f);
        postOnAnimation(new Runnable() {
            @Override
            public void run() {
                mLayout.animate()
                        .alpha(1f)
                        .setDuration(ANIMATION_DURATION)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
                mDialog.animate()
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION)
                        .setInterpolator(mLinearOutSlowIn)
                        .withLayer()
                        .start();
            }
        });
    }

    public void setBundle(Bundle bundle) {
        mBundle = bundle;
    }

    protected void clearMessage() {
        mErrorText.setVisibility(View.INVISIBLE);
    }

    private void showMessage(String message) {
        mHandler.removeMessages(FingerprintDialogImpl.MSG_CLEAR_MESSAGE);
        mErrorText.setText(message);
        mErrorText.setVisibility(View.VISIBLE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_CLEAR_MESSAGE),
                FingerprintDialog.HIDE_DIALOG_DELAY);
    }

    public void showHelpMessage(String message) {
        showMessage(message);
    }

    public void showErrorMessage(String error) {
        showMessage(error);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(FingerprintDialogImpl.MSG_HIDE_DIALOG,
                false /* userCanceled */), FingerprintDialog.HIDE_DIALOG_DELAY);
    }

    private Drawable getAppIcon() {
        final ActivityManager.RunningTaskInfo taskInfo = mActivityManagerWrapper.getRunningTask();
        final ComponentName cn = taskInfo.topActivity;
        final int userId = mActivityManagerWrapper.getCurrentUserId();
        final ActivityInfo activityInfo = mPackageManageWrapper.getActivityInfo(cn, userId);
        return mActivityManagerWrapper.getBadgedActivityIcon(activityInfo, userId);
    }

    public WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FingerprintDialogView");
        lp.token = mWindowToken;
        return lp;
    }
}
