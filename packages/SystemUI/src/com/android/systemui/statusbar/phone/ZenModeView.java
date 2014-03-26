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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.ZenModeView.Adapter.ExitCondition;

public class ZenModeView extends RelativeLayout {
    private static final String TAG = ZenModeView.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final String MODE_LABEL = "Limited interruptions";
    public static final int BACKGROUND = 0xff282828;

    private static final Typeface CONDENSED =
            Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final int GRAY = 0xff999999; //TextAppearance.StatusBar.Expanded.Network
    private static final int DARK_GRAY = 0xff333333;

    private static final long DURATION = new ValueAnimator().getDuration();
    private static final long PAGER_DURATION = DURATION / 2;
    private static final float BOUNCE_SCALE = 0.8f;
    private static final long CLOSE_DELAY = 600;

    private final Context mContext;
    private final Paint mPathPaint;
    private final ImageView mSettingsButton;
    private final TextView mModeText;
    private final Switch mModeSwitch;
    private final View mDivider;
    private final UntilPager mUntilPager;
    private final ProgressDots mProgressDots;

    private Adapter mAdapter;
    private boolean mInit;

    public ZenModeView(Context context) {
        this(context, null);
    }

    public ZenModeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DEBUG) log("new %s()", getClass().getSimpleName());
        mContext = context;

        final int iconSize = mContext.getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.notification_large_icon_width);
        final int topRowSize = iconSize * 2 / 3;
        final int p = topRowSize / 7;

        mPathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPathPaint.setStyle(Paint.Style.STROKE);
        mPathPaint.setColor(GRAY);
        mPathPaint.setStrokeWidth(p / 2);

        mSettingsButton = new ImageView(mContext);
        mSettingsButton.setPadding(p, p, p, p);
        mSettingsButton.setImageResource(R.drawable.ic_notify_settings_normal);
        LayoutParams lp = new LayoutParams(topRowSize, topRowSize);
        lp.topMargin = p;
        lp.leftMargin = p;
        addView(mSettingsButton, lp);
        mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAdapter != null) {
                    mAdapter.configure();
                }
                bounce(mSettingsButton, null);
            }
        });

        mModeText = new TextView(mContext);
        mModeText.setText(MODE_LABEL);
        mModeText.setId(android.R.id.title);
        mModeText.setTextColor(GRAY);
        mModeText.setTypeface(CONDENSED);
        mModeText.setAllCaps(true);
        mModeText.setGravity(Gravity.CENTER);
        mModeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mModeText.getTextSize() * 1.1f);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, topRowSize);
        lp.topMargin = p;
        lp.addRule(CENTER_HORIZONTAL);
        addView(mModeText, lp);

        mModeSwitch = new Switch(mContext);
        mModeSwitch.setSwitchPadding(0);
        mModeSwitch.setSwitchTypeface(CONDENSED);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, topRowSize);
        lp.topMargin = p;
        lp.addRule(ALIGN_PARENT_RIGHT);
        lp.addRule(ALIGN_BASELINE, mModeText.getId());
        addView(mModeSwitch, lp);
        mModeSwitch.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mAdapter.setMode(isChecked);
                if (!mInit) return;
                postDelayed(new Runnable(){
                    @Override
                    public void run() {
                        mAdapter.close();
                    }
                }, CLOSE_DELAY);
            }
        });

        mDivider = new View(mContext);
        mDivider.setId(android.R.id.empty);
        mDivider.setBackgroundColor(GRAY);
        lp = new LayoutParams(LayoutParams.MATCH_PARENT, 2);
        lp.addRule(BELOW, mModeText.getId());
        lp.topMargin = p;
        lp.bottomMargin = p * 2;
        addView(mDivider, lp);

        mUntilPager = new UntilPager(mContext, mPathPaint, iconSize * 3 / 4);
        mUntilPager.setId(android.R.id.tabhost);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = iconSize / 2;
        lp.addRule(CENTER_HORIZONTAL);
        lp.addRule(BELOW, mDivider.getId());
        addView(mUntilPager, lp);

        mProgressDots = new ProgressDots(mContext, iconSize / 5);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(CENTER_HORIZONTAL);
        lp.addRule(BELOW, mUntilPager.getId());
        addView(mProgressDots, lp);
    }

    public void setAdapter(Adapter adapter) {
        mAdapter = adapter;
        mAdapter.setCallbacks(new Adapter.Callbacks() {
            @Override
            public void onChanged() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        updateState(true);
                    }
                });
            }
        });
        updateState(false);
    }

    private void updateState(boolean animate) {
        mUntilPager.updateState();
        mModeSwitch.setChecked(mAdapter.getMode());
        mInit = true;
    }

    private static void log(String msg, Object... args) {
        Log.d(TAG, args == null || args.length == 0 ? msg : String.format(msg, args));
    }

    private static void bounce(final View v, final Runnable midBounce) {
        v.animate().scaleX(BOUNCE_SCALE).scaleY(BOUNCE_SCALE).setDuration(DURATION / 3)
            .setListener(new AnimatorListenerAdapter() {
                private boolean mFired;
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mFired) {
                        mFired = true;
                        if (midBounce != null) {
                            midBounce.run();
                        }
                        v.animate().scaleX(1).scaleY(1).setListener(null).start();
                    }
                }
            }).start();
    }

    private final class UntilView extends FrameLayout {
        private static final boolean SUPPORT_LINKS = false;

        private final TextView mText;
        public UntilView(Context context) {
            super(context);
            mText = new TextView(mContext);
            mText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mText.getTextSize() * 1.2f);
            mText.setTypeface(CONDENSED);
            mText.setTextColor(GRAY);
            mText.setGravity(Gravity.CENTER);
            addView(mText);
        }

        public void setExitCondition(final ExitCondition ec) {
            SpannableStringBuilder ss = new SpannableStringBuilder(ec.summary);
            if (SUPPORT_LINKS && ec.action != null) {
                ss.setSpan(new CustomLinkSpan() {
                    @Override
                    public void onClick() {
                        // TODO wire up links
                        Toast.makeText(mContext, ec.action, Toast.LENGTH_SHORT).show();
                    }
                }, 0, ss.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                mText.setMovementMethod(LinkMovementMethod.getInstance());
            } else {
                mText.setMovementMethod(null);
            }
            mText.setText(ss);
        }
    }

    private final class ProgressDots extends LinearLayout {
        private final int mDotSize;
        public ProgressDots(Context context, int dotSize) {
            super(context);
            setOrientation(HORIZONTAL);
            mDotSize = dotSize;
        }

        private void updateState(int current, int count) {
            while (getChildCount() < count) {
                View dot = new View(mContext);
                OvalShape s = new OvalShape();
                ShapeDrawable sd = new ShapeDrawable(s);

                dot.setBackground(sd);
                LayoutParams lp = new LayoutParams(mDotSize, mDotSize);
                lp.leftMargin = lp.rightMargin = mDotSize / 2;
                lp.topMargin = lp.bottomMargin = mDotSize * 2 / 3;
                addView(dot, lp);
            }
            while (getChildCount() > count) {
                removeViewAt(getChildCount() - 1);
            }
            final int N = getChildCount();
            for (int i = 0; i < N; i++) {
                final int color = current == i ? GRAY : DARK_GRAY;
                ((ShapeDrawable)getChildAt(i).getBackground()).setColorFilter(color, Mode.ADD);
            }
        }
    }

    private final class UntilPager extends RelativeLayout {
        private final UntilView[] mViews;
        private int mCurrent;
        private float mDownX;

        public UntilPager(Context context, Paint pathPaint, int iconSize) {
            super(context);
            mViews = new UntilView[3];
            for (int i = 0; i < mViews.length; i++) {
                UntilView v = new UntilView(mContext);
                LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, iconSize);
                addView(v, lp);
                mViews[i] = v;
            }
            updateState();
            addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right,
                        int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (left != oldLeft || right != oldRight) {
                        updateState();
                    }
                }
            });
            setBackgroundColor(DARK_GRAY);
        }

        private void updateState() {
            if (mAdapter == null) {
                return;
            }
            UntilView current = mViews[mCurrent];
            current.setExitCondition(mAdapter.getExitCondition(0));
            UntilView next = mViews[mCurrent + 1 % 3];
            next.setExitCondition(mAdapter.getExitCondition(1));
            UntilView prev = mViews[mCurrent + 2 % 3];
            prev.setExitCondition(mAdapter.getExitCondition(-1));
            position(0, false);
            mProgressDots.updateState(mAdapter.getExitConditionIndex(),
                    mAdapter.getExitConditionCount());
        }

        private void position(float dx, boolean animate) {
            int w = getWidth();
            UntilView current = mViews[mCurrent];
            UntilView next = mViews[mCurrent + 1 % 3];
            UntilView prev = mViews[mCurrent + 2 % 3];
            if (animate) {
                current.animate().setDuration(PAGER_DURATION).translationX(dx).start();
                next.animate().setDuration(PAGER_DURATION).translationX(w + dx).start();
                prev.animate().setDuration(PAGER_DURATION).translationX(-w + dx).start();
            } else {
                current.setTranslationX(dx);
                next.setTranslationX(w + dx);
                prev.setTranslationX(-w + dx);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            log("onTouchEvent " + MotionEvent.actionToString(event.getAction()));
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mDownX = event.getX();
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - mDownX;
                position(dx, false);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                float dx = event.getX() - mDownX;
                int d = Math.abs(dx) < getWidth() / 3 ? 0 : Math.signum(dx) > 0 ? -1 : 1;
                if (d != 0 && mAdapter.getExitConditionCount() > 1) {
                    mAdapter.select(mAdapter.getExitCondition(d));
                } else {
                    position(0, true);
                }
            }
            return true;
        }
    }

    private abstract static class CustomLinkSpan extends URLSpan {
        abstract public void onClick();

        public CustomLinkSpan() {
            super("#");
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            super.updateDrawState(ds);
            ds.setUnderlineText(false);
            ds.bgColor = BACKGROUND;
        }

        @Override
        public void onClick(View widget) {
            onClick();
        }
    }

    public interface Adapter {
        void configure();
        void close();
        boolean getMode();
        void setMode(boolean mode);
        void select(ExitCondition ec);
        void init();
        void setCallbacks(Callbacks callbacks);
        ExitCondition getExitCondition(int d);
        int getExitConditionCount();
        int getExitConditionIndex();

        public static class ExitCondition {
            public String summary;
            public String line1;
            public String line2;
            public String action;
        }

        public interface Callbacks {
            void onChanged();
        }
    }
}
