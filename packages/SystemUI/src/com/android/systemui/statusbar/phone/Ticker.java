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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
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

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarNotification;
import com.android.internal.util.CharSequences;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;

public abstract class Ticker {
    private static final int TICKER_SEGMENT_DELAY = 3000;
    
    private Context mContext;
    private Handler mHandler = new Handler();
    private ArrayList<Segment> mSegments = new ArrayList();
    private TextPaint mPaint;
    private View mTickerView;
    private ImageSwitcher mIconSwitcher;
    private TextSwitcher mTextSwitcher;
    private float mIconScale;

    public static boolean isGraphicOrEmoji(char c) {
        int gc = Character.getType(c);
        return     gc != Character.CONTROL
                && gc != Character.FORMAT
                && gc != Character.UNASSIGNED
                && gc != Character.LINE_SEPARATOR
                && gc != Character.PARAGRAPH_SEPARATOR
                && gc != Character.SPACE_SEPARATOR;
    }

    private final class Segment {
        StatusBarNotification notification;
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
            while (end > start && !isGraphicOrEmoji(substr.charAt(end-1))) {
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
            while (index < len && !isGraphicOrEmoji(this.text.charAt(index))) {
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

        Segment(StatusBarNotification n, Drawable icon, CharSequence text) {
            this.notification = n;
            this.icon = icon;
            this.text = text;
            int index = 0;
            final int len = text.length();
            while (index < len && !isGraphicOrEmoji(text.charAt(index))) {
                index++;
            }
            this.current = index;
            this.next = index;
            this.first = true;
        }
    };

    public Ticker(Context context, View sb) {
        mContext = context;
        final Resources res = context.getResources();
        final int outerBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_size);
        final int imageBounds = res.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size);
        mIconScale = (float)imageBounds / (float)outerBounds;

        mTickerView = sb.findViewById(R.id.ticker);

        mIconSwitcher = (ImageSwitcher)sb.findViewById(R.id.tickerIcon);
        mIconSwitcher.setInAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_in));
        mIconSwitcher.setOutAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_out));
        mIconSwitcher.setScaleX(mIconScale);
        mIconSwitcher.setScaleY(mIconScale);

        mTextSwitcher = (TextSwitcher)sb.findViewById(R.id.tickerText);
        mTextSwitcher.setInAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_in));
        mTextSwitcher.setOutAnimation(
                    AnimationUtils.loadAnimation(context, com.android.internal.R.anim.push_up_out));

        // Copy the paint style of one of the TextSwitchers children to use later for measuring
        TextView text = (TextView)mTextSwitcher.getChildAt(0);
        mPaint = text.getPaint();
    }


    public void addEntry(StatusBarNotification n) {
        int initialCount = mSegments.size();

        // If what's being displayed has the same text and icon, just drop it
        // (which will let the current one finish, this happens when apps do
        // a notification storm).
        if (initialCount > 0) {
            final Segment seg = mSegments.get(0);
            if (n.pkg.equals(seg.notification.pkg)
                    && n.notification.icon == seg.notification.notification.icon
                    && n.notification.iconLevel == seg.notification.notification.iconLevel
                    && CharSequences.equals(seg.notification.notification.tickerText,
                        n.notification.tickerText)) {
                return;
            }
        }

        final Drawable icon = StatusBarIconView.getIcon(mContext,
                new StatusBarIcon(n.pkg, n.user, n.notification.icon, n.notification.iconLevel, 0,
                        n.notification.tickerText));
        final CharSequence text = n.notification.tickerText;
        final Segment newSegment = new Segment(n, icon, text);

        // If there's already a notification schedule for this package and id, remove it.
        for (int i=0; i<mSegments.size(); i++) {
            Segment seg = mSegments.get(i);
            if (n.id == seg.notification.id && n.pkg.equals(seg.notification.pkg)) {
                // just update that one to use this new data instead
                mSegments.remove(i--); // restart iteration here
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

    public void removeEntry(StatusBarNotification n) {
        for (int i=mSegments.size()-1; i>=0; i--) {
            Segment seg = mSegments.get(i);
            if (n.id == seg.notification.id && n.pkg.equals(seg.notification.pkg)) {
                mSegments.remove(i);
            }
        }
    }

    public void halt() {
        mHandler.removeCallbacks(mAdvanceTicker);
        mSegments.clear();
        tickerHalting();
    }

    public void reflowText() {
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

    public abstract void tickerStarting();
    public abstract void tickerDone();
    public abstract void tickerHalting();
}

