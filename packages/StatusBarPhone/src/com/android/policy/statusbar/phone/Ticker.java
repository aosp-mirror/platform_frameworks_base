/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.policy.statusbar.phone;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.StaticLayout;
import android.text.Layout.Alignment;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Slog;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ImageSwitcher;

import java.util.ArrayList;


public abstract class Ticker {
    private static final int TICKER_SEGMENT_DELAY = 3000;
    
    private final class Segment {
        NotificationData notificationData;
        Drawable icon;
        CharSequence text;
        int current;
        int next;
        boolean first;

        StaticLayout getLayout(CharSequence substr) {
            int w = mTextSwitcher.getWidth() - mTextSwitcher.getPaddingLeft()
                    - mTextSwitcher.getPaddingRight();
            return new StaticLayout(substr, mPaint, w, Alignment.ALIGN_NORMAL, 1, 0, true);
        }

        CharSequence rtrim(CharSequence substr, int start, int end) {
            while (end > start && !TextUtils.isGraphic(substr.charAt(end-1))) {
                end--;
            }
            if (end > start) {
                return substr.subSequence(start, end);
            }
            return null;
        }

        /** returns null if there is no more text */
        CharSequence getText() {
            if (this.current > this.text.length()) {
                return null;
            }
            CharSequence substr = this.text.subSequence(this.current, this.text.length());
            StaticLayout l = getLayout(substr);
            int lineCount = l.getLineCount();
            if (lineCount > 0) {
                int start = l.getLineStart(0);
                int end = l.getLineEnd(0);
                this.next = this.current + end;
                return rtrim(substr, start, end);
            } else {
                throw new RuntimeException("lineCount=" + lineCount + " current=" + current +
                        " text=" + text);
            }
        }

        /** returns null if there is no more text */
        CharSequence advance() {
            this.first = false;
            int index = this.next;
            final int len = this.text.length();
            while (index < len && !TextUtils.isGraphic(this.text.charAt(index))) {
                index++;
            }
            if (index >= len) {
                return null;
            }

            CharSequence substr = this.text.subSequence(index, this.text.length());
            StaticLayout l = getLayout(substr);
            final int lineCount = l.getLineCount();
            int i;
            for (i=0; i<lineCount; i++) {
                int start = l.getLineStart(i);
                int end = l.getLineEnd(i);
                if (i == lineCount-1) {
                    this.next = len;
                } else {
                    this.next = index + l.getLineStart(i+1);
                }
                CharSequence result = rtrim(substr, start, end);
                if (result != null) {
                    this.current = index + start;
                    return result;
                }
            }
            this.current = len;
            return null;
        }

        Segment(NotificationData n, Drawable icon, CharSequence text) {
            this.notificationData = n;
            this.icon = icon;
            this.text = text;
            int index = 0;
            final int len = text.length();
            while (index < len && !TextUtils.isGraphic(text.charAt(index))) {
                index++;
            }
            this.current = index;
            this.next = index;
            this.first = true;
        }
    };

    private Handler mHandler = new Handler();
    private ArrayList<Segment> mSegments = new ArrayList();
    private TextPaint mPaint;
    private View mTickerView;
    private ImageSwitcher mIconSwitcher;
    private TextSwitcher mTextSwitcher;

    Ticker(Context context, StatusBarView sb) {
        mTickerView = sb.findViewById(R.id.ticker);

        mIconSwitcher = (ImageSwitcher)sb.findViewById(R.id.tickerIcon);
        mIconSwitcher.setInAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_in));
        mIconSwitcher.setOutAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_out));

        mTextSwitcher = (TextSwitcher)sb.findViewById(R.id.tickerText);
        mTextSwitcher.setInAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_in));
        mTextSwitcher.setOutAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_out));

        // Copy the paint style of one of the TextSwitchers children to use later for measuring
        TextView text = (TextView)mTextSwitcher.getChildAt(0);
        mPaint = text.getPaint();
    }

    void addEntry(NotificationData n, Drawable icon, CharSequence text) {
        int initialCount = mSegments.size();

        Segment newSegment = new Segment(n, icon, text);

        // prune out any preexisting ones for this notification, but not the current one.
        // let that finish, even if it's the same id
        for (int i=1; i<initialCount; i++) {
            Segment seg = mSegments.get(i);
            if (n.id == seg.notificationData.id && n.pkg.equals(seg.notificationData.pkg)) {
                // just update that one to use this new data instead
                mSegments.set(i, newSegment);
                // and since we know initialCount != 0, just return
                return ;
            }
        }

        mSegments.add(newSegment);

        if (initialCount == 0 && mSegments.size() > 0) {
            Segment seg = mSegments.get(0);
            seg.first = false;
            
            mIconSwitcher.setAnimateFirstView(false);
            mIconSwitcher.reset();
            mIconSwitcher.setImageDrawable(seg.icon);
            
            mTextSwitcher.setAnimateFirstView(false);
            mTextSwitcher.reset();
            mTextSwitcher.setText(seg.getText());
            
            tickerStarting();
            scheduleAdvance();
        }
    }

    void halt() {
        mHandler.removeCallbacks(mAdvanceTicker);
        mSegments.clear();
        tickerHalting();
    }

    void reflowText() {
        if (mSegments.size() > 0) {
            Segment seg = mSegments.get(0);
            CharSequence text = seg.getText();
            mTextSwitcher.setCurrentText(text);
        }
    }

    private Runnable mAdvanceTicker = new Runnable() {
        public void run() {
            while (mSegments.size() > 0) {
                Segment seg = mSegments.get(0);

                if (seg.first) {
                    // this makes the icon slide in for the first one for a given
                    // notification even if there are two notifications with the
                    // same icon in a row
                    mIconSwitcher.setImageDrawable(seg.icon);
                }
                CharSequence text = seg.advance();
                if (text == null) {
                    mSegments.remove(0);
                    continue;
                }
                mTextSwitcher.setText(text);

                scheduleAdvance();
                break;
            }
            if (mSegments.size() == 0) {
                tickerDone();
            }
        }
    };

    private void scheduleAdvance() {
        mHandler.postDelayed(mAdvanceTicker, TICKER_SEGMENT_DELAY);
    }

    abstract void tickerStarting();
    abstract void tickerDone();
    abstract void tickerHalting();
}

