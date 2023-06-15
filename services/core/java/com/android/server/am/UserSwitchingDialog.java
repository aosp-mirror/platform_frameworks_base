/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.util.ObjectUtils;
import com.android.internal.util.UserIcons;
import com.android.server.wm.WindowManagerService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dialog to show during the user switch. This dialog shows target user's name and their profile
 * picture with a circular spinner animation around it if the animations for this dialog are not
 * disabled. And covers the whole screen so that all the UI jank caused by the switch are hidden.
 */
class UserSwitchingDialog extends Dialog {
    private static final String TAG = "UserSwitchingDialog";
    private static final long TRACE_TAG = Trace.TRACE_TAG_ACTIVITY_MANAGER;

    // User switching doesn't happen that frequently, so it doesn't hurt to have it always on
    protected static final boolean DEBUG = true;

    private static final long DIALOG_SHOW_HIDE_ANIMATION_DURATION_MS = 300;
    private final boolean mDisableAnimations;

    // Time to wait for the onAnimationEnd() callbacks before moving on
    private static final int ANIMATION_TIMEOUT_MS = 1000;
    private final Handler mHandler = new Handler(Looper.myLooper());

    protected final UserInfo mOldUser;
    protected final UserInfo mNewUser;
    private final String mSwitchingFromSystemUserMessage;
    private final String mSwitchingToSystemUserMessage;
    private final WindowManagerService mWindowManager;
    protected final Context mContext;
    private final int mTraceCookie;
    private final boolean mNeedToFreezeScreen;

    UserSwitchingDialog(Context context, UserInfo oldUser, UserInfo newUser,
            String switchingFromSystemUserMessage, String switchingToSystemUserMessage,
            WindowManagerService windowManager) {
        super(context, R.style.Theme_Material_NoActionBar_Fullscreen);

        mContext = context;
        mOldUser = oldUser;
        mNewUser = newUser;
        mSwitchingFromSystemUserMessage = switchingFromSystemUserMessage;
        mSwitchingToSystemUserMessage = switchingToSystemUserMessage;
        mDisableAnimations = SystemProperties.getBoolean(
                "debug.usercontroller.disable_user_switching_dialog_animations", false);
        mWindowManager = windowManager;
        mNeedToFreezeScreen = !mDisableAnimations && !isUserSetupComplete(newUser);
        mTraceCookie = UserHandle.MAX_SECONDARY_USER_ID * oldUser.id + newUser.id;

        inflateContent();
        configureWindow();
    }

    private void configureWindow() {
        final Window window = getWindow();
        final WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR |
                WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(attrs);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        window.setDecorFitsSystemWindows(false);
    }

    void inflateContent() {
        setCancelable(false);
        setContentView(R.layout.user_switching_dialog);

        final TextView textView = findViewById(R.id.message);
        if (textView != null) {
            final String message = getTextMessage();
            textView.setAccessibilityPaneTitle(message);
            textView.setText(message);
        }

        final ImageView imageView = findViewById(R.id.icon);
        if (imageView != null) {
            imageView.setImageBitmap(getUserIconRounded());
        }

        final ImageView progressCircular = findViewById(R.id.progress_circular);
        if (progressCircular != null) {
            if (mDisableAnimations) {
                progressCircular.setVisibility(View.GONE);
            } else {
                final TypedValue value = new TypedValue();
                getContext().getTheme().resolveAttribute(R.attr.colorAccentPrimary, value, true);
                progressCircular.setColorFilter(value.data);
            }
        }
    }

    private Bitmap getUserIconRounded() {
        final Bitmap bmp = ObjectUtils.getOrElse(BitmapFactory.decodeFile(mNewUser.iconPath),
                defaultUserIcon(mNewUser.id));
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        final Bitmap bmpRounded = Bitmap.createBitmap(w, h, bmp.getConfig());
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(bmpRounded).drawRoundRect((new RectF(0, 0, w, h)), w / 2f, h / 2f, paint);
        return bmpRounded;
    }

    private Bitmap defaultUserIcon(@UserIdInt int userId) {
        final Resources res = getContext().getResources();
        final Drawable icon = UserIcons.getDefaultUserIcon(res, userId, /* light= */ false);
        return UserIcons.convertToBitmapAtUserIconSize(res, icon);
    }

    private String getTextMessage() {
        final Resources res = getContext().getResources();

        if (UserManager.isDeviceInDemoMode(mContext)) {
            return res.getString(mOldUser.isDemo()
                    ? R.string.demo_restarting_message
                    : R.string.demo_starting_message);
        }

        final String message =
                mOldUser.id == UserHandle.USER_SYSTEM ? mSwitchingFromSystemUserMessage
                : mNewUser.id == UserHandle.USER_SYSTEM ? mSwitchingToSystemUserMessage : null;

        return message != null ? message
                // If switchingFromSystemUserMessage or switchingToSystemUserMessage is null,
                // fallback to system message.
                : res.getString(R.string.user_switching_message, mNewUser.name);
    }

    private boolean isUserSetupComplete(UserInfo user) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, /* default= */ 0, user.id) == 1;
    }

    @Override
    public void show() {
        asyncTraceBegin("dialog", 0);
        super.show();
    }

    @Override
    public void dismiss() {
        super.dismiss();
        stopFreezingScreen();
        asyncTraceEnd("dialog", 0);
    }

    public void show(@NonNull Runnable onShown) {
        if (DEBUG) Slog.d(TAG, "show called");
        show();
        startShowAnimation(() -> {
            startFreezingScreen();
            onShown.run();
        });
    }

    public void dismiss(@Nullable Runnable onDismissed) {
        if (DEBUG) Slog.d(TAG, "dismiss called");
        if (onDismissed == null) {
            // no animation needed
            dismiss();
        } else {
            startDismissAnimation(() -> {
                dismiss();
                onDismissed.run();
            });
        }
    }

    private void startFreezingScreen() {
        if (!mNeedToFreezeScreen) {
            return;
        }
        traceBegin("startFreezingScreen");
        mWindowManager.startFreezingScreen(0, 0);
        traceEnd("startFreezingScreen");
    }

    private void stopFreezingScreen() {
        if (!mNeedToFreezeScreen) {
            return;
        }
        traceBegin("stopFreezingScreen");
        mWindowManager.stopFreezingScreen();
        traceEnd("stopFreezingScreen");
    }

    private void startShowAnimation(Runnable onAnimationEnd) {
        if (mDisableAnimations) {
            onAnimationEnd.run();
            return;
        }
        asyncTraceBegin("showAnimation", 1);
        startDialogAnimation("show", new AlphaAnimation(0, 1), () -> {
            asyncTraceEnd("showAnimation", 1);

            asyncTraceBegin("spinnerAnimation", 2);
            startProgressAnimation(() -> {
                asyncTraceEnd("spinnerAnimation", 2);

                onAnimationEnd.run();
            });
        });
    }

    private void startDismissAnimation(Runnable onAnimationEnd) {
        if (mDisableAnimations || mNeedToFreezeScreen) {
            // animations are disabled or screen is frozen, no need to play an animation
            onAnimationEnd.run();
            return;
        }
        asyncTraceBegin("dismissAnimation", 3);
        startDialogAnimation("dismiss", new AlphaAnimation(1, 0), () -> {
            asyncTraceEnd("dismissAnimation", 3);

            onAnimationEnd.run();
        });
    }

    private void startProgressAnimation(Runnable onAnimationEnd) {
        final AnimatedVectorDrawable avd = getSpinnerAVD();
        if (mDisableAnimations || avd == null) {
            onAnimationEnd.run();
            return;
        }
        final Runnable onAnimationEndWithTimeout = animationWithTimeout("spinner", onAnimationEnd);
        avd.registerAnimationCallback(new Animatable2.AnimationCallback() {
            @Override
            public void onAnimationEnd(Drawable drawable) {
                onAnimationEndWithTimeout.run();
            }
        });
        avd.start();
    }

    private AnimatedVectorDrawable getSpinnerAVD() {
        final ImageView view = findViewById(R.id.progress_circular);
        if (view != null) {
            final Drawable drawable = view.getDrawable();
            if (drawable instanceof AnimatedVectorDrawable) {
                return (AnimatedVectorDrawable) drawable;
            }
        }
        return null;
    }

    private void startDialogAnimation(String name, Animation animation, Runnable onAnimationEnd) {
        final View view = findViewById(R.id.content);
        if (mDisableAnimations || view == null) {
            onAnimationEnd.run();
            return;
        }
        final Runnable onAnimationEndWithTimeout = animationWithTimeout(name, onAnimationEnd);
        animation.setDuration(DIALOG_SHOW_HIDE_ANIMATION_DURATION_MS);
        animation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                onAnimationEndWithTimeout.run();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        view.startAnimation(animation);
    }

    private Runnable animationWithTimeout(String name, Runnable onAnimationEnd) {
        final AtomicBoolean isFirst = new AtomicBoolean(true);
        final Runnable onAnimationEndOrTimeout = () -> {
            if (isFirst.getAndSet(false)) {
                mHandler.removeCallbacksAndMessages(null);
                onAnimationEnd.run();
            }
        };
        mHandler.postDelayed(() -> {
            Slog.w(TAG, name + " animation not completed in " + ANIMATION_TIMEOUT_MS + " ms");
            onAnimationEndOrTimeout.run();
        }, ANIMATION_TIMEOUT_MS);

        return onAnimationEndOrTimeout;
    }

    private void asyncTraceBegin(String subTag, int subCookie) {
        if (DEBUG) Slog.d(TAG, "asyncTraceBegin-" + subTag);
        Trace.asyncTraceBegin(TRACE_TAG, TAG + subTag, mTraceCookie + subCookie);
    }

    private void asyncTraceEnd(String subTag, int subCookie) {
        Trace.asyncTraceEnd(TRACE_TAG, TAG + subTag, mTraceCookie + subCookie);
        if (DEBUG) Slog.d(TAG, "asyncTraceEnd-" + subTag);
    }

    private void traceBegin(String msg) {
        if (DEBUG) Slog.d(TAG, "traceBegin-" + msg);
        Trace.traceBegin(TRACE_TAG, msg);
    }

    private void traceEnd(String msg) {
        Trace.traceEnd(TRACE_TAG);
        if (DEBUG) Slog.d(TAG, "traceEnd-" + msg);
    }
}
