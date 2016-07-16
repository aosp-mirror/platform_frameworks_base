/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.egg.neko;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import com.android.egg.R;
import com.android.internal.logging.MetricsLogger;

public class Cat extends Drawable {
    public static final long[] PURR = {0, 40, 20, 40, 20, 40, 20, 40, 20, 40, 20, 40};

    private Random mNotSoRandom;
    private Bitmap mBitmap;
    private long mSeed;
    private String mName;
    private int mBodyColor;
    private int mFootType;
    private boolean mBowTie;

    private synchronized Random notSoRandom(long seed) {
        if (mNotSoRandom == null) {
            mNotSoRandom = new Random();
            mNotSoRandom.setSeed(seed);
        }
        return mNotSoRandom;
    }

    public static final float frandrange(Random r, float a, float b) {
        return (b-a)*r.nextFloat() + a;
    }

    public static final Object choose(Random r, Object...l) {
        return l[r.nextInt(l.length)];
    }

    public static final int chooseP(Random r, int[] a) {
        int pct = r.nextInt(1000);
        final int stop = a.length-2;
        int i=0;
        while (i<stop) {
            pct -= a[i];
            if (pct < 0) break;
            i+=2;
        }
        return a[i+1];
    }

    public static final int getColorIndex(int q, int[] a) {
        for(int i = 1; i < a.length; i+=2) {
            if (a[i] == q) {
                return i/2;
            }
        }
        return -1;
    }

    public static final int[] P_BODY_COLORS = {
            180, 0xFF212121, // black
            180, 0xFFFFFFFF, // white
            140, 0xFF616161, // gray
            140, 0xFF795548, // brown
            100, 0xFF90A4AE, // steel
            100, 0xFFFFF9C4, // buff
            100, 0xFFFF8F00, // orange
              5, 0xFF29B6F6, // blue..?
              5, 0xFFFFCDD2, // pink!?
              5, 0xFFCE93D8, // purple?!?!?
              4, 0xFF43A047, // yeah, why not green
              1, 0,          // ?!?!?!
    };

    public static final int[] P_COLLAR_COLORS = {
            250, 0xFFFFFFFF,
            250, 0xFF000000,
            250, 0xFFF44336,
             50, 0xFF1976D2,
             50, 0xFFFDD835,
             50, 0xFFFB8C00,
             50, 0xFFF48FB1,
             50, 0xFF4CAF50,
    };

    public static final int[] P_BELLY_COLORS = {
            750, 0,
            250, 0xFFFFFFFF,
    };

    public static final int[] P_DARK_SPOT_COLORS = {
            700, 0,
            250, 0xFF212121,
             50, 0xFF6D4C41,
    };

    public static final int[] P_LIGHT_SPOT_COLORS = {
            700, 0,
            300, 0xFFFFFFFF,
    };

    private CatParts D;

    public static void tint(int color, Drawable ... ds) {
        for (Drawable d : ds) {
            if (d != null) {
                d.mutate().setTint(color);
            }
        }
    }

    public static boolean isDark(int color) {
        final int r = (color & 0xFF0000) >> 16;
        final int g = (color & 0x00FF00) >> 8;
        final int b = color & 0x0000FF;
        return (r + g + b) < 0x80;
    }

    public Cat(Context context, long seed) {
        D = new CatParts(context);
        mSeed = seed;

        setName(context.getString(R.string.default_cat_name,
                String.valueOf(mSeed % 1000)));

        final Random nsr = notSoRandom(seed);

        // body color
        mBodyColor = chooseP(nsr, P_BODY_COLORS);
        if (mBodyColor == 0) mBodyColor = Color.HSVToColor(new float[] {
                nsr.nextFloat()*360f, frandrange(nsr,0.5f,1f), frandrange(nsr,0.5f, 1f)});

        tint(mBodyColor, D.body, D.head, D.leg1, D.leg2, D.leg3, D.leg4, D.tail,
                D.leftEar, D.rightEar, D.foot1, D.foot2, D.foot3, D.foot4, D.tailCap);
        tint(0x20000000, D.leg2Shadow, D.tailShadow);
        if (isDark(mBodyColor)) {
            tint(0xFFFFFFFF, D.leftEye, D.rightEye, D.mouth, D.nose);
        }
        tint(isDark(mBodyColor) ? 0xFFEF9A9A : 0x20D50000, D.leftEarInside, D.rightEarInside);

        tint(chooseP(nsr, P_BELLY_COLORS), D.belly);
        tint(chooseP(nsr, P_BELLY_COLORS), D.back);
        final int faceColor = chooseP(nsr, P_BELLY_COLORS);
        tint(faceColor, D.faceSpot);
        if (!isDark(faceColor)) {
            tint(0xFF000000, D.mouth, D.nose);
        }

        mFootType = 0;
        if (nsr.nextFloat() < 0.25f) {
            mFootType = 4;
            tint(0xFFFFFFFF, D.foot1, D.foot2, D.foot3, D.foot4);
        } else {
            if (nsr.nextFloat() < 0.25f) {
                mFootType = 2;
                tint(0xFFFFFFFF, D.foot1, D.foot3);
            } else if (nsr.nextFloat() < 0.25f) {
                mFootType = 3; // maybe -2 would be better? meh.
                tint(0xFFFFFFFF, D.foot2, D.foot4);
            } else if (nsr.nextFloat() < 0.1f) {
                mFootType = 1;
                tint(0xFFFFFFFF, (Drawable) choose(nsr, D.foot1, D.foot2, D.foot3, D.foot4));
            }
        }

        tint(nsr.nextFloat() < 0.333f ? 0xFFFFFFFF : mBodyColor, D.tailCap);

        final int capColor = chooseP(nsr, isDark(mBodyColor) ? P_LIGHT_SPOT_COLORS : P_DARK_SPOT_COLORS);
        tint(capColor, D.cap);
        //tint(chooseP(nsr, isDark(bodyColor) ? P_LIGHT_SPOT_COLORS : P_DARK_SPOT_COLORS), D.nose);

        final int collarColor = chooseP(nsr, P_COLLAR_COLORS);
        tint(collarColor, D.collar);
        mBowTie = nsr.nextFloat() < 0.1f;
        tint(mBowTie ? collarColor : 0, D.bowtie);
    }

    public static Cat create(Context context) {
        return new Cat(context, Math.abs(ThreadLocalRandom.current().nextInt()));
    }

    public Notification.Builder buildNotification(Context context) {
        final Bundle extras = new Bundle();
        extras.putString("android.substName", context.getString(R.string.notification_name));
        final Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClass(context, NekoLand.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Notification.Builder(context)
                .setSmallIcon(Icon.createWithResource(context, R.drawable.stat_icon))
                .setLargeIcon(createNotificationLargeIcon(context))
                .setColor(getBodyColor())
                .setPriority(Notification.PRIORITY_LOW)
                .setContentTitle(context.getString(R.string.notification_title))
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentText(getName())
                .setContentIntent(PendingIntent.getActivity(context, 0, intent, 0))
                .setAutoCancel(true)
                .setVibrate(PURR)
                .addExtras(extras);
    }

    public long getSeed() {
        return mSeed;
    }

    @Override
    public void draw(Canvas canvas) {
        final int w = Math.min(canvas.getWidth(), canvas.getHeight());
        final int h = w;

        if (mBitmap == null || mBitmap.getWidth() != w || mBitmap.getHeight() != h) {
            mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas bitCanvas = new Canvas(mBitmap);
            slowDraw(bitCanvas, 0, 0, w, h);
        }
        canvas.drawBitmap(mBitmap, 0, 0, null);
    }

    private void slowDraw(Canvas canvas, int x, int y, int w, int h) {
        for (int i = 0; i < D.drawingOrder.length; i++) {
            final Drawable d = D.drawingOrder[i];
            if (d != null) {
                d.setBounds(x, y, x+w, y+h);
                d.draw(canvas);
            }
        }

    }

    public Bitmap createBitmap(int w, int h) {
        if (mBitmap != null && mBitmap.getWidth() == w && mBitmap.getHeight() == h) {
            return mBitmap.copy(mBitmap.getConfig(), true);
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        slowDraw(new Canvas(result), 0, 0, w, h);
        return result;
    }

    public static Icon recompressIcon(Icon bitmapIcon) {
        if (bitmapIcon.getType() != Icon.TYPE_BITMAP) return bitmapIcon;
        final Bitmap bits = bitmapIcon.getBitmap();
        final ByteArrayOutputStream ostream = new ByteArrayOutputStream(
                bits.getWidth() * bits.getHeight() * 2); // guess 50% compression
        final boolean ok = bits.compress(Bitmap.CompressFormat.PNG, 100, ostream);
        if (!ok) return null;
        return Icon.createWithData(ostream.toByteArray(), 0, ostream.size());
    }

    public Icon createNotificationLargeIcon(Context context) {
        final Resources res = context.getResources();
        final int w = 2*res.getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
        final int h = 2*res.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
        return recompressIcon(createIcon(context, w, h));
    }

    public Icon createIcon(Context context, int w, int h) {
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);
        final Paint pt = new Paint();
        float[] hsv = new float[3];
        Color.colorToHSV(mBodyColor, hsv);
        hsv[2] = (hsv[2]>0.5f)
                ? (hsv[2] - 0.25f)
                : (hsv[2] + 0.25f);
        pt.setColor(Color.HSVToColor(hsv));
        float r = w/2;
        canvas.drawCircle(r, r, r, pt);
        int m = w/10;

        slowDraw(canvas, m, m, w-m-m, h-m-m);

        return Icon.createWithBitmap(result);
    }

    @Override
    public void setAlpha(int i) {

    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public int getBodyColor() {
        return mBodyColor;
    }

    public void logAdd(Context context) {
        logCatAction(context, "egg_neko_add");
    }

    public void logRename(Context context) {
        logCatAction(context, "egg_neko_rename");
    }

    public void logRemove(Context context) {
        logCatAction(context, "egg_neko_remove");
    }

    public void logShare(Context context) {
        logCatAction(context, "egg_neko_share");
    }

    private void logCatAction(Context context, String prefix) {
        MetricsLogger.count(context, prefix, 1);
        MetricsLogger.histogram(context, prefix +"_color",
                getColorIndex(mBodyColor, P_BODY_COLORS));
        MetricsLogger.histogram(context, prefix + "_bowtie", mBowTie ? 1 : 0);
        MetricsLogger.histogram(context, prefix + "_feet", mFootType);
    }

    public static class CatParts {
        public Drawable leftEar;
        public Drawable rightEar;
        public Drawable rightEarInside;
        public Drawable leftEarInside;
        public Drawable head;
        public Drawable faceSpot;
        public Drawable cap;
        public Drawable mouth;
        public Drawable body;
        public Drawable foot1;
        public Drawable leg1;
        public Drawable foot2;
        public Drawable leg2;
        public Drawable foot3;
        public Drawable leg3;
        public Drawable foot4;
        public Drawable leg4;
        public Drawable tail;
        public Drawable leg2Shadow;
        public Drawable tailShadow;
        public Drawable tailCap;
        public Drawable belly;
        public Drawable back;
        public Drawable rightEye;
        public Drawable leftEye;
        public Drawable nose;
        public Drawable bowtie;
        public Drawable collar;
        public Drawable[] drawingOrder;

        public CatParts(Context context) {
            body = context.getDrawable(R.drawable.body);
            head = context.getDrawable(R.drawable.head);
            leg1 = context.getDrawable(R.drawable.leg1);
            leg2 = context.getDrawable(R.drawable.leg2);
            leg3 = context.getDrawable(R.drawable.leg3);
            leg4 = context.getDrawable(R.drawable.leg4);
            tail = context.getDrawable(R.drawable.tail);
            leftEar = context.getDrawable(R.drawable.left_ear);
            rightEar = context.getDrawable(R.drawable.right_ear);
            rightEarInside = context.getDrawable(R.drawable.right_ear_inside);
            leftEarInside = context.getDrawable(R.drawable.left_ear_inside);
            faceSpot = context.getDrawable(R.drawable.face_spot);
            cap = context.getDrawable(R.drawable.cap);
            mouth = context.getDrawable(R.drawable.mouth);
            foot4 = context.getDrawable(R.drawable.foot4);
            foot3 = context.getDrawable(R.drawable.foot3);
            foot1 = context.getDrawable(R.drawable.foot1);
            foot2 = context.getDrawable(R.drawable.foot2);
            leg2Shadow = context.getDrawable(R.drawable.leg2_shadow);
            tailShadow = context.getDrawable(R.drawable.tail_shadow);
            tailCap = context.getDrawable(R.drawable.tail_cap);
            belly = context.getDrawable(R.drawable.belly);
            back = context.getDrawable(R.drawable.back);
            rightEye = context.getDrawable(R.drawable.right_eye);
            leftEye = context.getDrawable(R.drawable.left_eye);
            nose = context.getDrawable(R.drawable.nose);
            collar = context.getDrawable(R.drawable.collar);
            bowtie = context.getDrawable(R.drawable.bowtie);
            drawingOrder = getDrawingOrder();
        }
        private Drawable[] getDrawingOrder() {
            return new Drawable[] {
                    collar,
                    leftEar, leftEarInside, rightEar, rightEarInside,
                    head,
                    faceSpot,
                    cap,
                    leftEye, rightEye,
                    nose, mouth,
                    tail, tailCap, tailShadow,
                    foot1, leg1,
                    foot2, leg2,
                    foot3, leg3,
                    foot4, leg4,
                    leg2Shadow,
                    body, belly,
                    bowtie
            };
        }
    }
}
