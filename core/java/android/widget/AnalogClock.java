/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.widget.RemoteViews.RemoteView;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * This widget display an analogic clock with two hands for hours and
 * minutes.
 *
 * @attr ref android.R.styleable#AnalogClock_dial
 * @attr ref android.R.styleable#AnalogClock_hand_hour
 * @attr ref android.R.styleable#AnalogClock_hand_minute
 * @attr ref android.R.styleable#AnalogClock_hand_second
 * @deprecated This widget is no longer supported.
 */
@RemoteView
@Deprecated
public class AnalogClock extends View {
    /** How often the clock should refresh to make the seconds hand advance at ~15 FPS. */
    private static final long SECONDS_TICK_FREQUENCY_MS = 1000 / 15;

    private Clock mClock;

    @UnsupportedAppUsage
    private Drawable mHourHand;
    @UnsupportedAppUsage
    private Drawable mMinuteHand;
    @Nullable
    private Drawable mSecondHand;
    @UnsupportedAppUsage
    private Drawable mDial;

    private int mDialWidth;
    private int mDialHeight;

    private boolean mVisible;

    private float mSeconds;
    private float mMinutes;
    private float mHour;
    private boolean mChanged;

    public AnalogClock(Context context) {
        this(context, null);
    }

    public AnalogClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnalogClock(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AnalogClock(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final Resources r = context.getResources();
        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.AnalogClock, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, com.android.internal.R.styleable.AnalogClock,
                attrs, a, defStyleAttr, defStyleRes);

        mDial = a.getDrawable(com.android.internal.R.styleable.AnalogClock_dial);
        if (mDial == null) {
            mDial = context.getDrawable(com.android.internal.R.drawable.clock_dial);
        }

        mHourHand = a.getDrawable(com.android.internal.R.styleable.AnalogClock_hand_hour);
        if (mHourHand == null) {
            mHourHand = context.getDrawable(com.android.internal.R.drawable.clock_hand_hour);
        }

        mMinuteHand = a.getDrawable(com.android.internal.R.styleable.AnalogClock_hand_minute);
        if (mMinuteHand == null) {
            mMinuteHand = context.getDrawable(com.android.internal.R.drawable.clock_hand_minute);
        }

        mSecondHand = a.getDrawable(com.android.internal.R.styleable.AnalogClock_hand_second);

        mClock = Clock.systemDefaultZone();

        mDialWidth = mDial.getIntrinsicWidth();
        mDialHeight = mDial.getIntrinsicHeight();
    }

    /** Sets the dial of the clock to the specified Icon. */
    @RemotableViewMethod
    public void setDial(@NonNull Icon icon) {
        mDial = icon.loadDrawable(getContext());
        mDialWidth = mDial.getIntrinsicWidth();
        mDialHeight = mDial.getIntrinsicHeight();

        mChanged = true;
        invalidate();
    }

    /** Sets the hour hand of the clock to the specified Icon. */
    @RemotableViewMethod
    public void setHourHand(@NonNull Icon icon) {
        mHourHand = icon.loadDrawable(getContext());

        mChanged = true;
        invalidate();
    }

    /** Sets the minute hand of the clock to the specified Icon. */
    @RemotableViewMethod
    public void setMinuteHand(@NonNull Icon icon) {
        mMinuteHand = icon.loadDrawable(getContext());

        mChanged = true;
        invalidate();
    }

    /**
     * Sets the second hand of the clock to the specified Icon, or hides the second hand if it is
     * null.
     */
    @RemotableViewMethod
    public void setSecondHand(@Nullable Icon icon) {
        mSecondHand = icon == null ? null : icon.loadDrawable(getContext());
        mSecondsTick.run();

        mChanged = true;
        invalidate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);

        if (isVisible) {
            onVisible();
        } else {
            onInvisible();
        }
    }

    private void onVisible() {
        if (!mVisible) {
            mVisible = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

            // OK, this is gross but needed. This class is supported by the
            // remote views machanism and as a part of that the remote views
            // can be inflated by a context for another user without the app
            // having interact users permission - just for loading resources.
            // For exmaple, when adding widgets from a user profile to the
            // home screen. Therefore, we register the receiver as the current
            // user not the one the context is for.
            getContext().registerReceiverAsUser(mIntentReceiver,
                    android.os.Process.myUserHandle(), filter, null, getHandler());

            mSecondsTick.run();
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mClock = Clock.systemDefaultZone();

        // Make sure we update to the current time
        onTimeChanged();
    }

    private void onInvisible() {
        if (mVisible) {
            getContext().unregisterReceiver(mIntentReceiver);
            removeCallbacks(mSecondsTick);
            mVisible = false;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize =  MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize =  MeasureSpec.getSize(heightMeasureSpec);

        float hScale = 1.0f;
        float vScale = 1.0f;

        if (widthMode != MeasureSpec.UNSPECIFIED && widthSize < mDialWidth) {
            hScale = (float) widthSize / (float) mDialWidth;
        }

        if (heightMode != MeasureSpec.UNSPECIFIED && heightSize < mDialHeight) {
            vScale = (float )heightSize / (float) mDialHeight;
        }

        float scale = Math.min(hScale, vScale);

        setMeasuredDimension(resolveSizeAndState((int) (mDialWidth * scale), widthMeasureSpec, 0),
                resolveSizeAndState((int) (mDialHeight * scale), heightMeasureSpec, 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mChanged = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        boolean changed = mChanged;
        if (changed) {
            mChanged = false;
        }

        int availableWidth = mRight - mLeft;
        int availableHeight = mBottom - mTop;

        int x = availableWidth / 2;
        int y = availableHeight / 2;

        final Drawable dial = mDial;
        int w = dial.getIntrinsicWidth();
        int h = dial.getIntrinsicHeight();

        boolean scaled = false;

        if (availableWidth < w || availableHeight < h) {
            scaled = true;
            float scale = Math.min((float) availableWidth / (float) w,
                                   (float) availableHeight / (float) h);
            canvas.save();
            canvas.scale(scale, scale, x, y);
        }

        if (changed) {
            dial.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        dial.draw(canvas);

        canvas.save();
        canvas.rotate(mHour / 12.0f * 360.0f, x, y);
        final Drawable hourHand = mHourHand;
        if (changed) {
            w = hourHand.getIntrinsicWidth();
            h = hourHand.getIntrinsicHeight();
            hourHand.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        hourHand.draw(canvas);
        canvas.restore();

        canvas.save();
        canvas.rotate(mMinutes / 60.0f * 360.0f, x, y);

        final Drawable minuteHand = mMinuteHand;
        if (changed) {
            w = minuteHand.getIntrinsicWidth();
            h = minuteHand.getIntrinsicHeight();
            minuteHand.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
        }
        minuteHand.draw(canvas);
        canvas.restore();

        final Drawable secondHand = mSecondHand;
        if (secondHand != null) {
            canvas.save();
            canvas.rotate(mSeconds / 60.0f * 360.0f, x, y);

            if (changed) {
                w = secondHand.getIntrinsicWidth();
                h = secondHand.getIntrinsicHeight();
                secondHand.setBounds(x - (w / 2), y - (h / 2), x + (w / 2), y + (h / 2));
            }
            secondHand.draw(canvas);
            canvas.restore();
        }

        if (scaled) {
            canvas.restore();
        }
    }

    private void onTimeChanged() {
        long nowMillis = mClock.millis();
        LocalDateTime localDateTime = toLocalDateTime(nowMillis, mClock.getZone());

        int hour = localDateTime.getHour();
        int minute = localDateTime.getMinute();
        int second = localDateTime.getSecond();

        mSeconds = second + localDateTime.getNano() / 1_000_000_000f;
        mMinutes = minute + second / 60.0f;
        mHour = hour + mMinutes / 60.0f;
        mChanged = true;

        updateContentDescription(nowMillis);
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                mClock = Clock.system(ZoneId.of(tz));
            }

            onTimeChanged();

            invalidate();
        }
    };

    private final Runnable mSecondsTick = new Runnable() {
        @Override
        public void run() {
            if (!mVisible || mSecondHand == null) {
                return;
            }

            onTimeChanged();

            invalidate();

            postDelayed(this, SECONDS_TICK_FREQUENCY_MS);
        }
    };

    private void updateContentDescription(long timeMillis) {
        final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_24HOUR;
        String contentDescription = DateUtils.formatDateTime(mContext, timeMillis, flags);
        setContentDescription(contentDescription);
    }

    private static LocalDateTime toLocalDateTime(long timeMillis, ZoneId zoneId) {
        // java.time types like LocalDateTime / Instant can support the full range of "long millis"
        // with room to spare so we do not need to worry about overflow / underflow and the
        // resulting exceptions while the input to this class is a long.
        Instant instant = Instant.ofEpochMilli(timeMillis);
        return LocalDateTime.ofInstant(instant, zoneId);
    }
}
