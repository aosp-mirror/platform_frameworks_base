/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;

import java.lang.ref.WeakReference;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Shows a restart-activity button when the foreground activity is in size compatibility mode. */
@Singleton
public class SizeCompatModeActivityController extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "SizeCompatMode";

    /** The showing buttons by display id. */
    private final SparseArray<RestartActivityButton> mActiveButtons = new SparseArray<>(1);
    /** Avoid creating display context frequently for non-default display. */
    private final SparseArray<WeakReference<Context>> mDisplayContextCache = new SparseArray<>(0);
    private final CommandQueue mCommandQueue;

    /** Only show once automatically in the process life. */
    private boolean mHasShownHint;

    @VisibleForTesting
    @Inject
    SizeCompatModeActivityController(Context context, ActivityManagerWrapper am,
            CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
        am.registerTaskStackListener(new TaskStackChangeListener() {
            @Override
            public void onSizeCompatModeActivityChanged(int displayId, IBinder activityToken) {
                // Note the callback already runs on main thread.
                updateRestartButton(displayId, activityToken);
            }
        });
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        RestartActivityButton button = mActiveButtons.get(displayId);
        if (button == null) {
            return;
        }
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int newVisibility = imeShown ? View.GONE : View.VISIBLE;
        // Hide the button when input method is showing.
        if (button.getVisibility() != newVisibility) {
            button.setVisibility(newVisibility);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayContextCache.remove(displayId);
        removeRestartButton(displayId);
    }

    private void removeRestartButton(int displayId) {
        RestartActivityButton button = mActiveButtons.get(displayId);
        if (button != null) {
            button.remove();
            mActiveButtons.remove(displayId);
        }
    }

    private void updateRestartButton(int displayId, IBinder activityToken) {
        if (activityToken == null) {
            // Null token means the current foreground activity is not in size compatibility mode.
            removeRestartButton(displayId);
            return;
        }

        RestartActivityButton restartButton = mActiveButtons.get(displayId);
        if (restartButton != null) {
            restartButton.updateLastTargetActivity(activityToken);
            return;
        }

        Context context = getOrCreateDisplayContext(displayId);
        if (context == null) {
            Log.i(TAG, "Cannot get context for display " + displayId);
            return;
        }

        restartButton = createRestartButton(context);
        restartButton.updateLastTargetActivity(activityToken);
        if (restartButton.show()) {
            mActiveButtons.append(displayId, restartButton);
        } else {
            onDisplayRemoved(displayId);
        }
    }

    @VisibleForTesting
    RestartActivityButton createRestartButton(Context context) {
        RestartActivityButton button = new RestartActivityButton(context, mHasShownHint);
        mHasShownHint = true;
        return button;
    }

    private Context getOrCreateDisplayContext(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return mContext;
        }
        Context context = null;
        WeakReference<Context> ref = mDisplayContextCache.get(displayId);
        if (ref != null) {
            context = ref.get();
        }
        if (context == null) {
            Display display = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display != null) {
                context = mContext.createDisplayContext(display);
                mDisplayContextCache.put(displayId, new WeakReference<Context>(context));
            }
        }
        return context;
    }

    @VisibleForTesting
    static class RestartActivityButton extends ImageButton implements View.OnClickListener,
            View.OnLongClickListener {

        final WindowManager.LayoutParams mWinParams;
        final boolean mShouldShowHint;
        IBinder mLastActivityToken;

        final int mPopupOffsetX;
        final int mPopupOffsetY;
        PopupWindow mShowingHint;

        RestartActivityButton(Context context, boolean hasShownHint) {
            super(context);
            mShouldShowHint = !hasShownHint;
            Drawable drawable = context.getDrawable(R.drawable.btn_restart);
            setImageDrawable(drawable);
            setContentDescription(context.getString(R.string.restart_button_description));

            int drawableW = drawable.getIntrinsicWidth();
            int drawableH = drawable.getIntrinsicHeight();
            mPopupOffsetX = drawableW / 2;
            mPopupOffsetY = drawableH * 2;

            ColorStateList color = ColorStateList.valueOf(Color.LTGRAY);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(color);
            setBackground(new RippleDrawable(color, null /* content */, mask));
            setOnClickListener(this);
            setOnLongClickListener(this);

            mWinParams = new WindowManager.LayoutParams();
            mWinParams.gravity = getGravity(getResources().getConfiguration().getLayoutDirection());
            mWinParams.width = drawableW * 2;
            mWinParams.height = drawableH * 2;
            mWinParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            mWinParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWinParams.format = PixelFormat.TRANSLUCENT;
            mWinParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
            mWinParams.setTitle(SizeCompatModeActivityController.class.getSimpleName()
                    + context.getDisplayId());
        }

        void updateLastTargetActivity(IBinder activityToken) {
            mLastActivityToken = activityToken;
        }

        /** @return {@code false} if the target display is invalid. */
        boolean show() {
            try {
                getContext().getSystemService(WindowManager.class).addView(this, mWinParams);
            } catch (WindowManager.InvalidDisplayException e) {
                // The target display may have been removed when the callback has just arrived.
                Log.w(TAG, "Cannot show on display " + getContext().getDisplayId(), e);
                return false;
            }
            return true;
        }

        void remove() {
            if (mShowingHint != null) {
                mShowingHint.dismiss();
            }
            getContext().getSystemService(WindowManager.class).removeViewImmediate(this);
        }

        @Override
        public void onClick(View v) {
            try {
                ActivityTaskManager.getService().restartActivityProcessIfVisible(
                        mLastActivityToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to restart activity", e);
            }
        }

        @Override
        public boolean onLongClick(View v) {
            showHint();
            return true;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (mShouldShowHint) {
                showHint();
            }
        }

        @Override
        public void setLayoutDirection(int layoutDirection) {
            int gravity = getGravity(layoutDirection);
            if (mWinParams.gravity != gravity) {
                mWinParams.gravity = gravity;
                if (mShowingHint != null) {
                    mShowingHint.dismiss();
                    showHint();
                }
                getContext().getSystemService(WindowManager.class).updateViewLayout(this,
                        mWinParams);
            }
            super.setLayoutDirection(layoutDirection);
        }

        void showHint() {
            if (mShowingHint != null) {
                return;
            }

            View popupView = LayoutInflater.from(getContext()).inflate(
                    R.layout.size_compat_mode_hint, null /* root */);
            PopupWindow popupWindow = new PopupWindow(popupView,
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            popupWindow.setWindowLayoutType(mWinParams.type);
            popupWindow.setElevation(getResources().getDimension(R.dimen.bubble_elevation));
            popupWindow.setAnimationStyle(android.R.style.Animation_InputMethod);
            popupWindow.setClippingEnabled(false);
            popupWindow.setOnDismissListener(() -> mShowingHint = null);
            mShowingHint = popupWindow;

            Button gotItButton = popupView.findViewById(R.id.got_it);
            gotItButton.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.LTGRAY),
                    null /* content */, null /* mask */));
            gotItButton.setOnClickListener(view -> popupWindow.dismiss());
            popupWindow.showAtLocation(this, mWinParams.gravity, mPopupOffsetX, mPopupOffsetY);
        }

        private static int getGravity(int layoutDirection) {
            return Gravity.BOTTOM
                    | (layoutDirection == View.LAYOUT_DIRECTION_RTL ? Gravity.START : Gravity.END);
        }
    }
}
