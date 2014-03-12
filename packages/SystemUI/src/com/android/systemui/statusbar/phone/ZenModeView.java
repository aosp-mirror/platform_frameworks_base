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
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
    private static final int BACKGROUND = 0xff282828;
    private static final long DURATION = new ValueAnimator().getDuration();
    private static final long BOUNCE_DURATION = DURATION / 3;
    private static final float BOUNCE_SCALE = 0.8f;
    private static final float SETTINGS_ALPHA = 0.6f;

    private static final String FULL_TEXT =
            "You won't hear any calls, alarms or timers.";

    private final Context mContext;
    private final Paint mPathPaint;
    private final ImageView mSettingsButton;
    private final ModeSpinner mModeSpinner;
    private final TextView mActionButton;
    private final View mDivider;
    private final UntilPager mUntilPager;
    private final AlarmWarning mAlarmWarning;

    private Adapter mAdapter;

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
                if (mAdapter != null && mAdapter.getMode() == Adapter.MODE_LIMITED) {
                    mAdapter.configure();
                }
                bounce(mSettingsButton, null);
            }
        });

        mModeSpinner = new ModeSpinner(mContext);
        mModeSpinner.setId(android.R.id.title);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, topRowSize);
        lp.topMargin = p;
        lp.addRule(CENTER_HORIZONTAL);
        addView(mModeSpinner, lp);

        mActionButton = new TextView(mContext);
        mActionButton.setTextColor(GRAY);
        mActionButton.setTypeface(CONDENSED);
        mActionButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, mActionButton.getTextSize() * 1.2f);
        mActionButton.setAllCaps(true);
        mActionButton.setGravity(Gravity.CENTER);
        mActionButton.setPadding(p, 0, p * 2, 0);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, topRowSize);
        lp.topMargin = p;
        lp.addRule(ALIGN_PARENT_RIGHT);
        lp.addRule(ALIGN_BASELINE, mModeSpinner.getId());
        addView(mActionButton, lp);
        mActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bounce(v, null);
                beginOrEnd();
            }
        });

        mDivider = new View(mContext);
        mDivider.setId(android.R.id.empty);
        mDivider.setBackgroundColor(GRAY);
        lp = new LayoutParams(LayoutParams.MATCH_PARENT, 2);
        lp.addRule(BELOW, mModeSpinner.getId());
        lp.topMargin = p;
        lp.bottomMargin = p;
        addView(mDivider, lp);

        mUntilPager = new UntilPager(mContext, mPathPaint, iconSize * 3 / 4);
        mUntilPager.setId(android.R.id.tabhost);
        lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(BELOW, mDivider.getId());
        addView(mUntilPager, lp);

        mAlarmWarning = new AlarmWarning(mContext);
        lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(CENTER_HORIZONTAL);
        lp.addRule(BELOW, mUntilPager.getId());
        lp.bottomMargin = p;
        addView(mAlarmWarning, lp);
    }

    private void beginOrEnd() {
        if (mAdapter == null) return;
        if (mAdapter.getMode() == mAdapter.getCommittedMode()) {
            // end
            mAdapter.setCommittedMode(Adapter.MODE_OFF);
        } else {
            // begin
            mAdapter.setCommittedMode(mAdapter.getMode());
        }
        mAdapter.close();
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
        mModeSpinner.updateState();
        mUntilPager.updateState();
        mAlarmWarning.updateState(animate);
        final float settingsAlpha = isFull() ? 0 : SETTINGS_ALPHA;
        if (settingsAlpha != mSettingsButton.getAlpha()) {
            if (animate) {
                mSettingsButton.animate().alpha(settingsAlpha).start();
            } else {
                mSettingsButton.setAlpha(settingsAlpha);
            }
        }
        final boolean committed = mAdapter != null
                && mAdapter.getMode() == mAdapter.getCommittedMode();
        mActionButton.setText(committed ? "End" : "Begin");
    }

    private boolean isFull() {
        return mAdapter != null && mAdapter.getMode() == Adapter.MODE_FULL;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (DEBUG) log("onMeasure %s %s",
                MeasureSpec.toString(widthMeasureSpec), MeasureSpec.toString(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (!isFull()) {
            final LayoutParams lp = (LayoutParams) mModeSpinner.getLayoutParams();
            final int mh = vh(mModeSpinner) + vh(mDivider) + vh(mUntilPager) + lp.topMargin;
            setMeasuredDimension(getMeasuredWidth(), mh);
        }
    }

    private int vh(View v) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        return v.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
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

    public static String modeToString(int mode) {
        if (mode == Adapter.MODE_OFF) return "MODE_OFF";
        if (mode == Adapter.MODE_LIMITED) return "MODE_LIMITED";
        if (mode == Adapter.MODE_FULL) return "MODE_FULL";
        throw new IllegalArgumentException("Invalid mode: " + mode);
    }

    public static String modeToLabel(int mode) {
        if (mode == Adapter.MODE_LIMITED) return "Limited interruptions";
        if (mode == Adapter.MODE_FULL) return "Zero interruptions";
        throw new UnsupportedOperationException("Unsupported mode: " + mode);
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
            mText1.setTextSize(TypedValue.COMPLEX_UNIT_PX, mText1.getTextSize() * 1.2f);
            mText1.setTypeface(CONDENSED);
            mText1.setTextColor(GRAY);
            mText1.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, iconSize);
            addView(mText1, lp);
            mText = mText1;

            mText2 = new TextView(mContext);
            mText2.setTextSize(TypedValue.COMPLEX_UNIT_PX, mText1.getTextSize());
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
            lp.addRule(ALIGN_PARENT_RIGHT);
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
            SpannableStringBuilder ss = new SpannableStringBuilder(ec.summary);
            if (ec.action != null) {
                ss.setSpan(new CustomLinkSpan() {
                    @Override
                    public void onClick() {
                        // TODO wire up links
                        Toast.makeText(mContext, ec.action, Toast.LENGTH_SHORT).show();
                    }
                }, 0, ss.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
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
            final int hp = size * 3 / 8;
            final int vp = size / 4;
            final Path p = new Path();
            p.moveTo(size - hp, vp);
            p.lineTo(hp, size / 2);
            p.lineTo(size - hp, size - vp);
            return p;
        }

        private Path nextPath(int size) {
            final int hp = size * 3 / 8;
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

        void configure();
        void close();
        int getMode();
        void setMode(int mode);
        int getCommittedMode();
        void setCommittedMode(int mode);
        void select(ExitCondition ec);
        void init();
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
                public View getDropDownView(final int position, View convertView, ViewGroup parent) {
                    if (DEBUG) log("getDropDownView %s cv=%s parent=%s",
                            position, convertView, parent);
                    final TextView tv = convertView != null ? (TextView) convertView
                            : new TextView(context);
                    final int mode = getItem(position);
                    tv.setText(modeToLabel(mode));
                    final boolean inDropdown = parent instanceof ListView;
                    if (convertView == null) {
                        if (DEBUG) log(" setting up view");
                        tv.setTextColor(GRAY);
                        tv.setTypeface(CONDENSED);
                        tv.setAllCaps(true);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * 1.2f);
                        final int p = (int) tv.getTextSize() / 2;
                        if (inDropdown) {
                            tv.setPadding(p, p, 0, p);
                        } else {
                            tv.setGravity(Gravity.CENTER_HORIZONTAL);
                            tv.setPadding(p, 0, 0, 0);
                        }
                    }
                    tv.setOnTouchListener(new OnTouchListener(){
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            if (DEBUG) log("onTouch %s %s inDropdown=%s", tv.getText(),
                                    MotionEvent.actionToString(event.getAction()), inDropdown);
                            if (inDropdown && mAdapter != null) {
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
                    onDetachedFromWindow();
                }
            }
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
            final float alpha = isFull() ? 1 : 0;
            if (alpha == getAlpha()) {
                return;
            }
            if (animate) {
                animate().alpha(alpha).start();
            } else {
                setAlpha(alpha);
                requestLayout();
            }
        }
    }
}
