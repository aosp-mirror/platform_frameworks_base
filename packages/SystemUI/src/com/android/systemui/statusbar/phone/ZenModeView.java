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
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.RelativeSizeSpan;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.ZenModeView.Adapter.ExitCondition;

public class ZenModeView extends RelativeLayout {
    private static final String TAG = ZenModeView.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Typeface CONDENSED =
            Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final int GRAY = 0xff999999; //TextAppearance.StatusBar.Expanded.Network
    private static final int BACKGROUND = 0xff1d3741; //0x3333b5e5;
    private static final long DURATION = new ValueAnimator().getDuration();
    private static final long BOUNCE_DURATION = DURATION / 3;
    private static final float BOUNCE_SCALE = 0.8f;
    private static final float SETTINGS_ALPHA = 0.6f;

    private static final String FULL_TEXT =
            "You won't hear any calls, alarms or timers.";

    private final Context mContext;
    private final Paint mPathPaint;
    private final TextView mHintText;
    private final ModeSpinner mModeSpinner;
    private final ImageView mCloseButton;
    private final ImageView mSettingsButton;
    private final Rect mLayoutRect = new Rect();
    private final UntilPager mUntilPager;
    private final AlarmWarning mAlarmWarning;

    private float mDownY;
    private int mDownBottom;
    private boolean mPeekable = true;
    private boolean mClosing;
    private int mBottom;
    private int mWidthSpec;
    private Adapter mAdapter;

    public ZenModeView(Context context) {
        this(context, null);
    }

    public ZenModeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DEBUG) log("new %s()", getClass().getSimpleName());
        mContext = context;

        mPathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPathPaint.setStyle(Paint.Style.STROKE);
        mPathPaint.setColor(GRAY);
        mPathPaint.setStrokeWidth(5);

        final int iconSize = mContext.getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.notification_large_icon_width);
        final int topRowSize = iconSize * 2 / 3;

        mCloseButton = new ImageView(mContext);
        mCloseButton.setAlpha(0f);
        mCloseButton.setImageDrawable(sd(closePath(topRowSize), topRowSize, mPathPaint));
        addView(mCloseButton, new LayoutParams(topRowSize, topRowSize));
        mCloseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bounce(v, null);
                close();
            }
        });

        mSettingsButton = new ImageView(mContext);
        mSettingsButton.setAlpha(0f);
        final int p = topRowSize / 7;
        mSettingsButton.setPadding(p, p, p, p);
        mSettingsButton.setImageResource(R.drawable.ic_notify_settings_normal);
        LayoutParams lp = new LayoutParams(topRowSize, topRowSize);
        lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        addView(mSettingsButton, lp);
        mSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAdapter != null && mAdapter.getMode() == Adapter.MODE_LIMITED) {
                    mAdapter.configure();
                }
                bounce(mSettingsButton, null);
            }
        });

        mModeSpinner = new ModeSpinner(mContext);
        mModeSpinner.setAlpha(0);
        mModeSpinner.setEnabled(false);
        mModeSpinner.setId(android.R.id.title);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, topRowSize);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        addView(mModeSpinner, lp);


        mUntilPager = new UntilPager(mContext, mPathPaint, iconSize);
        mUntilPager.setId(android.R.id.tabhost);
        mUntilPager.setAlpha(0);
        lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(BELOW, mModeSpinner.getId());
        addView(mUntilPager, lp);

        mAlarmWarning = new AlarmWarning(mContext);
        mAlarmWarning.setAlpha(0);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(CENTER_HORIZONTAL);
        lp.addRule(BELOW, mUntilPager.getId());
        addView(mAlarmWarning, lp);

        mHintText = new TextView(mContext);
        mHintText.setTypeface(CONDENSED);
        mHintText.setText("Swipe down for Limited Interruptions");
        mHintText.setGravity(Gravity.CENTER);
        mHintText.setTextColor(GRAY);
        addView(mHintText, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private boolean isApplicable() {
        return mAdapter != null && mAdapter.isApplicable();
    }

    private void close() {
        mClosing = true;
        final int startBottom = mBottom;
        final int max = mPeekable ? getExpandedBottom() : startBottom;
        mHintText.animate().alpha(1).setUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float f = animation.getAnimatedFraction();
                final int hintBottom = mHintText.getBottom();
                setPeeked(hintBottom + (int)((1-f) * (startBottom - hintBottom)), max);
                if (f == 1) {
                    mPeekable = true;
                    mClosing = false;
                    mModeSpinner.updateState();
                    if (mAdapter != null) {
                        mAdapter.cancel();
                    }
                }
            }
        }).start();
        mUntilPager.animate().alpha(0).start();
        mAlarmWarning.animate().alpha(0).start();
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
        final boolean applicable = isApplicable();
        setVisibility(applicable ? VISIBLE : GONE);
        if (!applicable) {
            return;
        }
        if (mAdapter != null && mAdapter.getMode() == Adapter.MODE_OFF && !mPeekable) {
            close();
        } else {
            mModeSpinner.updateState();
            mUntilPager.updateState();
            mAlarmWarning.updateState(animate);
            final float settingsAlpha = getSettingsButtonAlpha();
            if (settingsAlpha != mSettingsButton.getAlpha()) {
                if (animate) {
                    mSettingsButton.animate().alpha(settingsAlpha).start();
                } else {
                    mSettingsButton.setAlpha(settingsAlpha);
                }
            }
            if (mPeekable && mAdapter != null && mAdapter.getMode() != Adapter.MODE_OFF) {
                if (DEBUG) log("panic expand!");
                mPeekable = false;
                mModeSpinner.setEnabled(true);
                mBottom = getExpandedBottom();
                setExpanded(1);
            }
        }
    }

    private float getSettingsButtonAlpha() {
        final boolean full = mAdapter != null && mAdapter.getMode() == Adapter.MODE_FULL;
        final boolean collapsed = mHintText.getAlpha() == 1;
        return full || collapsed ? 0 : SETTINGS_ALPHA;
    }

    private static Path closePath(int size) {
        final int pad = size / 4;
        final Path p = new Path();
        p.moveTo(pad, pad);
        p.lineTo(size - pad, size - pad);
        p.moveTo(size - pad, pad);
        p.lineTo(pad, size - pad);
        return p;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (DEBUG) log("onMeasure %s %s",
                MeasureSpec.toString(widthMeasureSpec), MeasureSpec.toString(heightMeasureSpec));
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            throw new UnsupportedOperationException("Width must be exact");
        }
        if (widthMeasureSpec != mWidthSpec) {
            if (DEBUG) log("  super.onMeasure");
            final int hms = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            super.onMeasure(widthMeasureSpec, hms);
            mBottom = mPeekable ? mHintText.getMeasuredHeight() : getExpandedBottom();
            mWidthSpec = widthMeasureSpec;
        }
        if (DEBUG) log("mBottom (OM) = " + mBottom);
        setMeasuredDimension(getMeasuredWidth(), mBottom);
        if (DEBUG) log("  mw=%s mh=%s",
                toString(getMeasuredWidthAndState()), toString(getMeasuredHeightAndState()));
    }

    private static String toString(int sizeAndState) {
        final int size = sizeAndState & MEASURED_SIZE_MASK;
        final boolean tooSmall = (sizeAndState & MEASURED_STATE_TOO_SMALL) != 0;
        return size + (tooSmall ? "TOO SMALL" : "");
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mLayoutRect.set(left, top, right, bottom);
        if (DEBUG) log("onLayout %s %s %dx%d", changed,
                mLayoutRect.toShortString(), mLayoutRect.width(), mLayoutRect.height());
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final boolean rt = super.dispatchTouchEvent(ev);
        if (DEBUG) logTouchEvent("dispatchTouchEvent", rt, ev);
        return rt;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final boolean rt = super.onInterceptTouchEvent(ev);
        if (DEBUG) logTouchEvent("onInterceptTouchEvent", rt, ev);
        if (isApplicable()
                && ev.getAction() == MotionEvent.ACTION_DOWN
                && ev.getY() > mCloseButton.getBottom()
                && mPeekable) {
            return true;
        }
        return rt;
    }

    private static void logTouchEvent(String method, boolean rt, MotionEvent event) {
        final String action = MotionEvent.actionToString(event.getAction());
        Log.d(TAG, method + " " + (rt ? "TRUE" : "FALSE") + " " + action);
    }

    private int getExpandedBottom() {
        int b = mModeSpinner.getMeasuredHeight() + mUntilPager.getMeasuredHeight();
        if (mAlarmWarning.getAlpha() == 1) b += mAlarmWarning.getMeasuredHeight();
        return b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean rt = super.onTouchEvent(event);
        if (DEBUG) logTouchEvent("onTouchEvent", rt, event);
        if (!isApplicable() || !mPeekable) {
            return rt;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mDownY = event.getY();
            if (DEBUG) log("  mDownY=" + mDownY);
            mDownBottom = mBottom;
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            final float dy = event.getY() - mDownY;
            setPeeked(mDownBottom + (int)dy, getExpandedBottom());
        } else if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
            final float dy = event.getY() - mDownY;
            setPeeked(mDownBottom + (int)dy, getExpandedBottom());
            if (mPeekable) {
                close();
            }
        }
        return rt;
    }

    private void setPeeked(int peeked, int max) {
        if (DEBUG) log("setPeeked=" + peeked);
        final int min = mHintText.getBottom();
        peeked = Math.max(min, Math.min(peeked, max));
        if (mBottom == peeked) {
            return;
        }
        if (peeked == max) {
            mPeekable = false;
            mModeSpinner.setEnabled(true);
            if (mAdapter != null) {
                mAdapter.setMode(Adapter.MODE_LIMITED);
            }
        }
        if (peeked == min) {
            mPeekable = true;
            mModeSpinner.setEnabled(false);
        }
        if (DEBUG) log("  mBottom=" + peeked);
        mBottom = peeked;
        final float f = (peeked - min) / (float)(max - min);
        setExpanded(f);
        requestLayout();
    }

    private void setExpanded(float f) {
        if (DEBUG) log("setExpanded " + f);
        final int a = (int)(Color.alpha(BACKGROUND) * f);
        setBackgroundColor(Color.argb(a,
                Color.red(BACKGROUND), Color.green(BACKGROUND), Color.blue(BACKGROUND)));
        mHintText.setAlpha(1 - f);
        mCloseButton.setAlpha(f);
        mModeSpinner.setAlpha(f);
        mUntilPager.setAlpha(f);
        mSettingsButton.setAlpha(f * getSettingsButtonAlpha());
    }

    private static void log(String msg, Object... args) {
        Log.d(TAG, args == null || args.length == 0 ? msg : String.format(msg, args));
    }

    private static ShapeDrawable sd(Path p, int size, Paint pt) {
        final ShapeDrawable sd = new ShapeDrawable(new PathShape(p, size, size));
        sd.getPaint().set(pt);
        sd.setIntrinsicHeight(size);
        sd.setIntrinsicWidth(size);
        return sd;
    }

    public void dispatchExternalTouchEvent(MotionEvent ev) {
        if (isApplicable()) {
            onTouchEvent(ev);
        }
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

    private final class UntilPager extends RelativeLayout {
        private final ImageView mPrev;
        private final ImageView mNext;
        private final TextView mText1;
        private final TextView mText2;

        private TextView mText;

        public UntilPager(Context context, Paint pathPaint, int iconSize) {
            super(context);
            mText1 = new TextView(mContext);
            mText1.setTypeface(CONDENSED);
            mText1.setTextColor(GRAY);
            mText1.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, iconSize);
            addView(mText1, lp);
            mText = mText1;

            mText2 = new TextView(mContext);
            mText2.setTypeface(CONDENSED);
            mText2.setTextColor(GRAY);
            mText2.setAlpha(0);
            mText2.setGravity(Gravity.CENTER);
            addView(mText2, lp);

            lp = new LayoutParams(iconSize, iconSize);
            final View v = new View(mContext);
            v.setBackgroundColor(BACKGROUND);
            addView(v, lp);
            mPrev = new ImageView(mContext);
            mPrev.setId(android.R.id.button1);
            mPrev.setImageDrawable(sd(prevPath(iconSize), iconSize, pathPaint));
            addView(mPrev, lp);
            mPrev.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onNav(v, -1);
                }
            });

            lp = new LayoutParams(iconSize, iconSize);
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            final View v2 = new View(mContext);
            v2.setBackgroundColor(BACKGROUND);
            addView(v2, lp);
            mNext = new ImageView(mContext);
            mNext.setId(android.R.id.button2);
            mNext.setImageDrawable(sd(nextPath(iconSize), iconSize, pathPaint));
            addView(mNext, lp);
            mNext.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onNav(v, 1);
                }
            });

            updateState();
        }

        private void onNav(View v, int d) {
            bounce(v, null);
            if (mAdapter == null) {
                return;
            }
            if (mAdapter.getExitConditionCount() == 1) {
                horBounce(d);
                return;
            }
            final int w = getWidth();
            final float s = Math.signum(d);
            final TextView current = mText;
            final TextView other = mText == mText1 ? mText2 : mText1;
            final ExitCondition ec = mAdapter.getExitCondition(d);
            setText(other, ec);
            other.setTranslationX(-s * w);
            other.animate().translationX(0).alpha(1).setDuration(DURATION).start();
            current.animate().translationX(s * w).alpha(0).setDuration(DURATION).start();
            mText = other;
            mAdapter.select(ec);
        }

        private void horBounce(int d) {
            final int w = getWidth();
            mText.animate()
                    .setDuration(BOUNCE_DURATION)
                    .translationX(Math.signum(d) * w / 20)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mText.animate().translationX(0).setListener(null).start();
                        }
                    }).start();
        }

        private void setText(final TextView textView, final ExitCondition ec) {
            SpannableStringBuilder ss = new SpannableStringBuilder(ec.line1 + "\n" + ec.line2);
            ss.setSpan(new RelativeSizeSpan(1.5f), (ec.line1 + "\n").length(), ss.length(),
                    Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            if (ec.action != null) {
                ss.setSpan(new CustomLinkSpan() {
                    @Override
                    public void onClick() {
                        // TODO wire up links
                        Toast.makeText(mContext, ec.action, Toast.LENGTH_SHORT).show();
                    }
                }, (ec.line1 + "\n").length(), ss.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                textView.setMovementMethod(LinkMovementMethod.getInstance());
            } else {
                textView.setMovementMethod(null);
            }
            textView.setText(ss);
        }

        private void updateState() {
            if (mAdapter == null) {
                return;
            }
            setText(mText, mAdapter.getExitCondition(0));
        }

        private Path prevPath(int size) {
            final int hp = size / 3;
            final int vp = size / 4;
            final Path p = new Path();
            p.moveTo(size - hp, vp);
            p.lineTo(hp, size / 2);
            p.lineTo(size - hp, size - vp);
            return p;
        }

        private Path nextPath(int size) {
            final int hp = size / 3;
            final int vp = size / 4;
            Path p = new Path();
            p.moveTo(hp, vp);
            p.lineTo(size - hp, size / 2);
            p.lineTo(hp, size - vp);
            return p;
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
        public static final int MODE_OFF = 0;
        public static final int MODE_LIMITED = 1;
        public static final int MODE_FULL = 2;

        boolean isApplicable();
        void configure();
        int getMode();
        void setMode(int mode);
        void select(ExitCondition ec);
        void cancel();
        void setCallbacks(Callbacks callbacks);
        ExitCondition getExitCondition(int d);
        int getExitConditionCount();

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

    private final class ModeSpinner extends Spinner {
        public ModeSpinner(final Context context) {
            super(context);
            setBackgroundResource(R.drawable.spinner_default_holo_dark_am_no_underline);
            final ArrayAdapter<Integer> adapter = new ArrayAdapter<Integer>(mContext, 0) {
                @Override
                public View getView(int position, View convertView,  ViewGroup parent) {
                    if (DEBUG) log("getView %s parent=%s", position, parent);
                    return getDropDownView(position, convertView, parent);
                }

                @Override
                public View getDropDownView(int position, View convertView, ViewGroup parent) {
                    if (DEBUG) log("getDropDownView %s cv=%s parent=%s",
                            position, convertView, parent);
                    final TextView tv = convertView != null ? (TextView) convertView
                            : new TextView(context);
                    final int mode = getItem(position);
                    tv.setText(modeToString(mode));
                    if (convertView == null) {
                        if (DEBUG) log(" setting up view");
                        tv.setTextColor(GRAY);
                        tv.setTypeface(CONDENSED);
                        tv.setAllCaps(true);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * 1.5f);
                        final int p = (int) tv.getTextSize() / 2;
                        if (parent instanceof ListView) {
                            tv.setPadding(p, p, p, p);
                        } else {
                            tv.setGravity(Gravity.CENTER_HORIZONTAL);
                            tv.setPadding(p, 0, p, 0);
                        }
                    }
                    tv.setOnTouchListener(new OnTouchListener(){
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            if (DEBUG) log("onTouch %s %s", tv.getText(),
                                    MotionEvent.actionToString(event.getAction()));
                            if (mAdapter != null) {
                                mAdapter.setMode(mode);
                            }
                            return false;
                        }

                    });
                    return tv;
                }
            };
            adapter.add(Adapter.MODE_LIMITED);
            adapter.add(Adapter.MODE_FULL);
            setAdapter(adapter);
        }

        public void updateState() {
            int mode = mAdapter != null ? mAdapter.getMode() : Adapter.MODE_LIMITED;
            if (mode == Adapter.MODE_OFF) {
                mode = Adapter.MODE_LIMITED;
            }
            if (DEBUG) log("setSelectedMode " + mode);
            for (int i = 0; i < getAdapter().getCount(); i++) {
                if (getAdapter().getItem(i).equals(mode)) {
                    if (DEBUG) log("  setting selection = " + i);
                    setSelection(i, true);
                    return;
                }
            }
        }

        private String modeToString(int mode) {
            if (mode == Adapter.MODE_LIMITED) return "Limited interruptions";
            if (mode == Adapter.MODE_FULL) return "Zero interruptions";
            throw new UnsupportedOperationException("Unsupported mode: " + mode);
        }
    }

    private final class AlarmWarning extends LinearLayout {
        public AlarmWarning(Context context) {
            super(context);
            setOrientation(HORIZONTAL);

            final TextView tv = new TextView(mContext);
            tv.setTextColor(GRAY);
            tv.setGravity(Gravity.TOP);
            tv.setTypeface(CONDENSED);
            tv.setText(FULL_TEXT);
            addView(tv);

            final ImageView icon = new ImageView(mContext);
            icon.setAlpha(.75f);
            int size = (int)tv.getTextSize();
            icon.setImageResource(android.R.drawable.ic_dialog_alert);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            final int p = size / 4;
            lp.bottomMargin = lp.topMargin = lp.rightMargin = lp.leftMargin = p;
            addView(icon, 0, lp);
            setPadding(p, 0, p, p);
        }

        public void updateState(boolean animate) {
            final boolean visible = mAdapter != null && mAdapter.getMode() == Adapter.MODE_FULL;
            final float alpha = visible ? 1 : 0;
            if (alpha == getAlpha()) {
                return;
            }
            if (animate) {
                final boolean in = alpha == 1;
                animate().alpha(alpha).setUpdateListener(new AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        if (mPeekable || mClosing) {
                            return;
                        }
                        float f = animation.getAnimatedFraction();
                        if (!in) {
                            f = 1 - f;
                        }
                        ZenModeView.this.mBottom = mUntilPager.getBottom()
                                + (int)(mAlarmWarning.getMeasuredHeight() * f);
                        if (DEBUG) log("mBottom (AW) = " + mBottom);
                        requestLayout();
                    }
                }).start();
            } else {
                setAlpha(alpha);
                requestLayout();
            }
        }
    }
}
