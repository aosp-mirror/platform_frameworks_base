/*
 * Copyright (C) 2013 Team AOSPAL
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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.ActivityManagerNative;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardViewMediator.ViewMediatorCallback;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;

public class NotificationHostView extends FrameLayout {
    private static final String TAG = "Keyguard:NotificationView";
    private static final int MSG_NOTIFICATION_ADD = 0;
    private static final int MSG_NOTIFICATION_REMOVE = 1;

    private static final float SWIPE = 0.2f;
    private static final int ANIMATION_MAX_DURATION = 300;
    private static final int PPMS = 2;
    private static final int MAX_ALPHA = 150;

    //Here we store dimissed notifications so we don't add them again in onFinishInflate
    private static HashMap<String, StatusBarNotification> mDismissedNotifications = new HashMap<String, StatusBarNotification>();

    private Queue<NotificationView> mNotificationsToAdd = new ArrayDeque<NotificationView>();
    private Queue<NotificationView> mNotificationsToRemove = new ArrayDeque<NotificationView>();
    private HashMap<String, NotificationView> mNotifications = new HashMap<String, NotificationView>();
    private INotificationManager mNotificationManager;
    private WindowManager mWindowManager;
    private int mNotificationMinHeight, mNotificationMinRowHeight;
    private int mDisplayWidth, mDisplayHeight;
    private int mShownNotifications = 0;
    private boolean mDynamicWidth;

    private ViewMediatorCallback mViewMediatorCallback;
    private LinearLayout mNotifView;
    private TouchModalScrollView mScrollView;

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_NOTIFICATION_ADD:
                    handleAddNotification(msg.arg1 == 1, msg.arg2 == 1);
                    break;
                case MSG_NOTIFICATION_REMOVE:
                    handleRemoveNotification(msg.arg1 == 1);
                    break;
            }
        }
    };

    public NotificationView getViewByPoint(int x, int y) {
        y += mScrollView.getScrollY();
        for (NotificationView nv : mNotifications.values()) {
            Rect hitRect = new Rect();
            nv.getChildAt(0).getHitRect(hitRect);
            hitRect.top = nv.getTop();
            hitRect.bottom = nv.getBottom();
            if (hitRect.contains(x, y))
                return nv;
        }
        return null;
    }

    public static class TouchModalScrollView extends ScrollView {
        private NotificationHostView hostView;
        private boolean touchAllowed = false;

        public TouchModalScrollView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void setHostView(NotificationHostView view) {
            hostView = view;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                NotificationView v = hostView.getViewByPoint((int)event.getX(), (int)event.getY());
                touchAllowed = (v != null);
            }
            if (touchAllowed) {
                return super.dispatchTouchEvent(event);
            }
            return false;
        }
    }

    private class NotificationView extends FrameLayout implements View.OnClickListener {
        private static final int CLICK_THRESHOLD = 10;

        private StatusBarNotification statusBarNotification;
        private Runnable onActionUp;
        private Runnable onAnimationEnd;
        private VelocityTracker velocityTracker;
        private int animations = 0;
        private boolean swipeGesture = false;
        private boolean pointerDown = false;
        private boolean bigContentView;
        private boolean switchView = false;
        private float initialX;
        private float delta;
        private boolean shown = false;

        public NotificationView(Context context, StatusBarNotification sbn) {
            super(context);
            statusBarNotification = sbn;
        }

        public ViewPropertyAnimator animateChild() {
            final ViewPropertyAnimator animation = getChildAt(0).animate();
            animation.withEndAction(new Runnable() {
               public void run() {
                   animations--;
                   if (animations == 0 && onAnimationEnd != null){
                       onAnimationEnd.run();
                       onAnimationEnd = null;
                   }
               }
            });
            animation.withStartAction(new Runnable() {
                public void run() {
                    animations++;
                }
            });
            return animation;
        }

        @Override
        public void onClick(View v) {
            if (!swipeGesture) {
                PendingIntent i = statusBarNotification.getNotification().contentIntent;
                if (i != null) {
                    try {
                        Intent intent = i.getIntent();
                        intent.setFlags(
                            intent.getFlags()
                            | Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
                        i.send();
                    } catch (CanceledException ex) {
                        Log.e(TAG, "intent canceled!");
                    } catch (RemoteException ex) {
                        Log.e(TAG, "failed to dimiss keyguard!");
                    }
                }
            }
        }
        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            mViewMediatorCallback.userActivity();
            if (!NotificationViewManager.config.privacyMode) {
                View v = getChildAt(0);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = event.getX();
                        delta = initialX - v.getX();
                        pointerDown = true;
                        velocityTracker = VelocityTracker.obtain();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        velocityTracker.addMovement(event);
                        float x = (event.getX() - delta);
                        float xr = x - (mDisplayWidth - v.getWidth());
                        if (canBeDismissed() && x < mDisplayWidth - v.getWidth()) {
                            v.setAlpha(1f + (xr / (v.getWidth() * (SWIPE * 2))));
                        }
                        if (canBeDismissed() && (mShownNotifications == 0 || (shown && mShownNotifications == 1)))
                            NotificationHostView.this.setBackgroundColor(Color.argb(MAX_ALPHA -
                                    (int)(Math.abs(xr) / v.getWidth() * MAX_ALPHA), 0, 0, 0));
                        if (swipeGesture  || Math.abs(event.getX() - initialX) > CLICK_THRESHOLD) {
                            swipeGesture = true;
                            v.cancelPendingInputEvents();
                            mScrollView.requestDisallowInterceptTouchEvent(true);
                            v.setTranslationX((!canBeDismissed() && x < 0) ? -4 * (float)Math.sqrt(-x) : x);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (v != null && swipeGesture) {
                            if (v.getX() - (mDisplayWidth - v.getWidth())< -SWIPE * mDisplayWidth && canBeDismissed()) {
                                removeNotification(statusBarNotification);
                            } else if (v.getX() < (SWIPE * mDisplayWidth)) {
                                showNotification(this);
                                onAnimationEnd = onActionUp;
                            } else if (v.getX() < ((1 - SWIPE) * mDisplayWidth) && getVelocity() < 0) {
                                showNotification(this);
                                onAnimationEnd = onActionUp;
                            } else {
                                hideNotification(this);
                                onAnimationEnd = onActionUp;
                            }
                        }
                        velocityTracker.recycle();
                        onActionUp = null;
                        swipeGesture = false;
                        pointerDown = false;
                        break;
                }
            }
            return false;
        }

        public void runOnAnimationEnd(Runnable r) {
            if (animations > 0) onAnimationEnd = r;
            else if ((pointerDown && !switchView) || swipeGesture) onActionUp = r;
            else r.run();
        }

        public boolean canBeDismissed() {
            return (NotificationViewManager.config.dismissAll || statusBarNotification.isClearable());
        }

        public float getVelocity() {
            if (pointerDown) velocityTracker.computeCurrentVelocity(1); // 1 = pixel per millisecond
            return pointerDown ? velocityTracker.getXVelocity() : PPMS;
        }

        @Override
        public void addView(View v) {
            v.setOnClickListener(this);
            super.addView(v);
        }
    }

    public NotificationHostView(Context context, AttributeSet attributes) {
        super(context, attributes);

        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mNotificationMinHeight = mContext.getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        mNotificationMinRowHeight = mContext.getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mDynamicWidth = getResources().getBoolean(R.bool.config_lnDynamicWidth);
    }

    @Override
    public void onFinishInflate() {
        if (NotificationViewManager.config != null) {
            mNotifications.clear();
            mNotificationsToAdd.clear();
            mNotificationsToRemove.clear();
            mShownNotifications = 0;
            setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    if (mShownNotifications > 0) {
                        hideAllNotifications();
                    }
                    return false;
                }
            });
            Point p = new Point();
            mWindowManager.getDefaultDisplay().getSize(p);
            mDisplayWidth = p.x;
            mDisplayHeight = p.y;
            mNotifView = (LinearLayout) findViewById(R.id.linearlayout);
            mScrollView = (TouchModalScrollView) findViewById(R.id.scrollview);
            mScrollView.setHostView(this);
            mScrollView.setY(mDisplayHeight * NotificationViewManager.config.offsetTop);
            int maxHeight = Math.round(mDisplayHeight - mDisplayHeight * NotificationViewManager.config.offsetTop);
            mScrollView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                    Math.min(maxHeight, NotificationViewManager.config.notificationsHeight * mNotificationMinRowHeight)));
        }
    }

    public void addNotifications() {
        if (NotificationViewManager.NotificationListener != null) {
            try {
                StatusBarNotification[] sbns = mNotificationManager.getActiveNotificationsFromListener(NotificationViewManager.NotificationListener);
                StatusBarNotification dismissedSbn;
                for (StatusBarNotification sbn : sbns) {
                    if ((dismissedSbn = mDismissedNotifications.get(describeNotification(sbn))) == null || dismissedSbn.getPostTime() != sbn.getPostTime())
                        addNotification(sbn);
                }
                setButtonDrawable();
                bringToFront();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get active notifications!");
            }
        }
    }

    public void setViewMediator(ViewMediatorCallback viewMediator) {
        mViewMediatorCallback = viewMediator;
    }

    public boolean addNotification(StatusBarNotification sbn) {
        return addNotification(sbn, false, NotificationViewManager.config.forceExpandedView);
    }

    public boolean addNotification(StatusBarNotification sbn, boolean showNotification, boolean forceBigContentView) {
        if ((!NotificationViewManager.config.hideLowPriority || sbn.getNotification().priority > Notification.PRIORITY_LOW)
                && NotificationViewManager.NotificationListener.isValidNotification(sbn)
                && (!NotificationViewManager.config.hideNonClearable || sbn.isClearable())) {
            mNotificationsToAdd.add(new NotificationView(mContext, sbn));
            Message msg = new Message();
            msg.arg1 = showNotification ? 1 : 0;
            msg.arg2 = forceBigContentView ? 1 : 0;
            msg.what = MSG_NOTIFICATION_ADD;
            mHandler.sendMessage(msg);
            return true;
        }
        return false;
    }

    private void setBackgroundRecursive(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof ViewGroup) {
                setBackgroundRecursive((ViewGroup)v);
                v.setBackground(null);
            }
        }
    }
    View.OnLayoutChangeListener mLayoutListener = new View.OnLayoutChangeListener() {
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            NotificationView nv = (NotificationView) v;
            if (nv != null && nv.shown) {
                nv.getChildAt(0).setX(mDisplayWidth - nv.getChildAt(0).getWidth());
            }
            v.removeOnLayoutChangeListener(this);
        }
    };
    private void handleAddNotification(final boolean showNotification, boolean forceBigContentView) {
        final NotificationView nv = mNotificationsToAdd.poll();
        Log.d(TAG, "Add: " + describeNotification(nv.statusBarNotification));
        final StatusBarNotification sbn = nv.statusBarNotification;
        mDismissedNotifications.remove(describeNotification(sbn));

        if (sbn.getNotification().contentView == null) {
            if (sbn.getNotification().bigContentView == null) {
                return;
            }
            forceBigContentView = true;
        }
        final NotificationView oldView = mNotifications.get(describeNotification(sbn));
        final boolean reposted = oldView != null;
        if (reposted && oldView.bigContentView) forceBigContentView = true;
        boolean bigContentView = sbn.getNotification().bigContentView != null && ((reposted && oldView.bigContentView) ||
                (NotificationViewManager.config.expandedView || sbn.getNotification().contentView == null));
        nv.bigContentView = bigContentView && forceBigContentView;
        RemoteViews rv = nv.bigContentView ? sbn.getNotification().bigContentView : sbn.getNotification().contentView;
        final View remoteView = rv.apply(mContext, null);
        remoteView.setLayoutParams(new LayoutParams(mDynamicWidth ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        remoteView.setX(mDisplayWidth - mNotificationMinHeight);
        setBackgroundRecursive((ViewGroup)remoteView);
        remoteView.setBackgroundColor(NotificationViewManager.config.notificationColor);
        remoteView.setAlpha(1f);
        if (bigContentView && sbn.getNotification().contentView != null) {
            final boolean bc = !forceBigContentView;
            final NotificationView notifView = reposted ? oldView : nv;
            remoteView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    notifView.onActionUp = null;
                    notifView.onAnimationEnd = null;
                    notifView.bigContentView = bc;
                    notifView.switchView = true;
                    addNotification(sbn, false, bc);
                    return true;
                }
            });
        }

        if (reposted){
            //The notification already exists, so it was just changed. Remove the old view and add the new one
            Runnable replaceView = new Runnable() {
                public void run() {
                    oldView.removeAllViews();
                    oldView.addView(remoteView);
                    oldView.addOnLayoutChangeListener(mLayoutListener);
                    oldView.statusBarNotification = sbn;
                }
            };
            if (showNotification && !oldView.shown && showNotification && !oldView.pointerDown) showNotification(sbn);
            oldView.runOnAnimationEnd(replaceView);
            oldView.bigContentView = nv.bigContentView;
            oldView.switchView = false;
            return;
        }

        nv.addView(remoteView);
        nv.setPadding(0, 0, 0, mNotificationMinRowHeight - mNotificationMinHeight);

        mNotifView.addView(nv);
        mNotifications.put(describeNotification(sbn), nv);
        mNotifView.bringToFront();
        if(showNotification) {
            // showNotification uses v.getWidth but until the layout is done, this just returns 0.
            // by using mHandler.post, we wait until getWidth returns the real width
            if (mDynamicWidth) {
                mHandler.post(new Runnable() {
                    public void run() {
                        showNotification(nv);
                    }
                });
            } else {
                showNotification(nv);
            }
        }
        setButtonDrawable();
    }

    public void removeNotification(final StatusBarNotification sbn) {
        removeNotification(sbn, true);
    }

    public void removeNotification(final StatusBarNotification sbn, boolean dismiss) {
        mNotificationsToRemove.add(mNotifications.get(describeNotification(sbn)));
        Message msg = new Message();
        msg.what = MSG_NOTIFICATION_REMOVE;
        msg.arg1 = dismiss ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    private void handleRemoveNotification(final boolean dismiss) {
        final NotificationView v = mNotificationsToRemove.poll();
        final StatusBarNotification sbn = v.statusBarNotification;
        if (mNotifications.containsKey(describeNotification(sbn)) && sbn != null) {
            Log.d(TAG, "Remove: " + describeNotification(v.statusBarNotification));
            if (v.shown) {
                if (mShownNotifications > 0) mShownNotifications--;
                if (mShownNotifications == 0) {
                    animateBackgroundColor(0);
                }
            }
            if (!sbn.isClearable()) {
                mDismissedNotifications.put(describeNotification(sbn), sbn);
            }
            int duration =  getDurationFromDistance(v.getChildAt(0), v.shown ? -mDisplayWidth : mDisplayWidth, 0);
            v.animateChild().setDuration(duration).alpha(0).start();
            mNotifications.remove(describeNotification(sbn));
            v.onAnimationEnd = new Runnable() {
                public void run() {
                    if (dismiss) {
                        dismiss(sbn);
                    }
                    mNotifView.removeView(v);
                    mNotifView.requestLayout();
                }
            };
            animateTranslation(v, v.shown ? -mDisplayWidth : mDisplayWidth, 0, duration);
            setButtonDrawable();
        }
    }

    public void onButtonClick(int buttonId) {
        if (mShownNotifications == mNotifications.size())
            dismissAll();
        else
            showAllNotifications();
    }

    private void dismissAll() {
        for (NotificationView nv : mNotifications.values()) {
            if (nv.canBeDismissed()) removeNotification(nv.statusBarNotification);
        }
    }

    private void dismiss(StatusBarNotification sbn) {
        if (sbn.isClearable()) {
            INotificationManager nm = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            try {
                nm.cancelNotificationFromListener(NotificationViewManager.NotificationListener, sbn.getPackageName(), sbn.getTag(), sbn.getId());
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to cancel notification: " + sbn.getPackageName());
            }
        }
    }

    public void showNotification(StatusBarNotification sbn) {
        showNotification(mNotifications.get(describeNotification(sbn)));
    }

    private void showNotification(NotificationView nv) {
        if (!NotificationViewManager.config.privacyMode) {
            View v = nv.getChildAt(0);
            int targetX = mDynamicWidth ? (mDisplayWidth - v.getWidth()) : 0;
            boolean useRealVelocity = !(Math.copySign(1, nv.getVelocity()) == Math.copySign(1, v.getX()));
            int duration = useRealVelocity ? getDurationFromDistance(v, targetX, 0, Math.abs(nv.getVelocity())) :
                                                            ANIMATION_MAX_DURATION;
            nv.animateChild().setDuration(duration).alpha(1);
            animateTranslation(nv, targetX, 0, duration);
            if (mShownNotifications == 0 ||
                    (mShownNotifications == 1 && nv.shown)) {
                animateBackgroundColor(Color.argb(MAX_ALPHA, 0, 0, 0));
            }
            if (!nv.shown) {
                nv.shown = true;
                mShownNotifications++;
            }
        }
        setButtonDrawable();
        bringToFront();
    }

    private void hideNotification(NotificationView nv) {
        View v = nv.getChildAt(0);
        int targetX = Math.round(mDisplayWidth - mNotificationMinHeight);
        int duration = getDurationFromDistance(v, targetX, (int)v.getY(), Math.abs(nv.getVelocity()));
        if (mShownNotifications > 0 && nv.shown) mShownNotifications--;
        if (mShownNotifications == 0) animateBackgroundColor(0);
        animateTranslation(nv, targetX, 0, duration);
        nv.shown = false;
        setButtonDrawable();
    }

    public void showAllNotifications() {
        for (NotificationView nv : mNotifications.values()) {
            showNotification (nv);
        }
        mScrollView.fullScroll(ScrollView.FOCUS_DOWN);
    }

    public void hideAllNotifications() {
        for (NotificationView nv : mNotifications.values()) {
            if (nv.shown)
                hideNotification (nv);
        }
    }

    private void setButtonDrawable() {
        IStatusBarService statusBar = null;
        try {
            statusBar = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        } catch (Exception ex) {
            Log.w(TAG, "Failed to get statusbar service!");
            return;
        }

        if (statusBar != null) {
            try {
                if (mNotifications.size() == 0) {
                    statusBar.setButtonDrawable(0, 0);
                } else if (mShownNotifications == mNotifications.size()) {
                    statusBar.setButtonDrawable(0, 2);
                } else {
                    statusBar.setButtonDrawable(0, 1);
                }
            } catch (Exception ex) {
                Log.e(TAG, "Failed to set button drawable!");
            }
        }
    }

    private void animateBackgroundColor(final int targetColor) {
        if (!(getBackground() instanceof ColorDrawable)) {
            setBackgroundColor(0x0);
        }
        final ObjectAnimator colorFade = ObjectAnimator.ofObject(this, "backgroundColor", new ArgbEvaluator(),
                ((ColorDrawable)getBackground()).getColor(),
                targetColor);
        colorFade.setDuration(ANIMATION_MAX_DURATION);
        Runnable r = new Runnable() {
            public void run() {
                colorFade.start();
            }
        };
        if (Looper.myLooper() == mHandler.getLooper()) {
            r.run();
        } else {
            mHandler.post(r);
        }
    }

    private void animateTranslation(final NotificationView v, final float targetX, final float targetY, final int duration) {
        ViewPropertyAnimator vpa = v.animateChild();
        vpa.setDuration(Math.min(duration, ANIMATION_MAX_DURATION)).translationX(targetX);
        vpa.setDuration(Math.min(duration, ANIMATION_MAX_DURATION)).translationY(targetY);
    }

    public int getNotificationCount() {
        return mNotifications.size();
    }

    public boolean containsNotification(StatusBarNotification sbn) {
        return mNotifications.containsKey(describeNotification(sbn));
    }

    public Notification getNotification(StatusBarNotification sbn) {
        if (containsNotification(sbn))
            return mNotifications.get(describeNotification(sbn)).statusBarNotification.getNotification();
        else
            return null;
    }

    private String describeNotification(StatusBarNotification sbn) {
        return sbn.getPackageName() + sbn.getId();
    }

    private int getDurationFromDistance (View v, int targetX, int targetY) {
        return getDurationFromDistance (v, targetX, targetY, PPMS);
    }

    private int getDurationFromDistance (View v, int targetX, int targetY, float ppms) {
        int distance = 0;
        float x = v.getX();
        float y = v.getY();
        if (targetY == y) distance = Math.abs(Math.round(x) - targetX);
        else if (targetX == x) distance = Math.abs(Math.round(y - targetY));
        else distance = (int) Math.abs(Math.round(Math.sqrt((x - targetX)*(x * targetX)+(y - targetY)*(y - targetY))));
        return Math.round(distance / ppms);
    }

    public void bringToFront() {
        mNotifView.bringToFront();
        super.bringToFront();
    }

    public void setVisibility (int v) {
        super.setVisibility(v);
        bringToFront();
    }
}