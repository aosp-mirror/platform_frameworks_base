/*
* Copyright (C) 2013 SlimRoms Project
* Copyright (C) 2015 TeamEos Project
* Copyright (C) 2015-2016 The DirtyUnicorns Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.hwkeys;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;

public class ImageHelper {
    private static final int VECTOR_WIDTH = 512;
    private static final int VECTOR_HEIGHT = 512;

    public static Drawable getColoredDrawable(Drawable d, int color) {
        if (d == null) {
            return null;
        }
        if (d instanceof VectorDrawable) {
            d.setTint(color);
            return d;
        }
        Bitmap colorBitmap = ((BitmapDrawable) d).getBitmap();
        Bitmap grayscaleBitmap = toGrayscale(colorBitmap);
        Paint pp = new Paint();
        pp.setAntiAlias(true);
        PorterDuffColorFilter frontFilter =
            new PorterDuffColorFilter(color, Mode.MULTIPLY);
        pp.setColorFilter(frontFilter);
        Canvas cc = new Canvas(grayscaleBitmap);
        final Rect rect = new Rect(0, 0, grayscaleBitmap.getWidth(), grayscaleBitmap.getHeight());
        cc.drawBitmap(grayscaleBitmap, rect, rect, pp);
        return new BitmapDrawable(grayscaleBitmap);
    }

    public static Bitmap drawableToBitmap (Drawable drawable) {
        if (drawable == null) {
            return null;
        } else if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    public static Bitmap getColoredBitmap(Drawable d, int color) {
        if (d == null) {
            return null;
        }
        Bitmap colorBitmap = ((BitmapDrawable) d).getBitmap();
        Bitmap grayscaleBitmap = toGrayscale(colorBitmap);
        Paint pp = new Paint();
        pp.setAntiAlias(true);
        PorterDuffColorFilter frontFilter =
            new PorterDuffColorFilter(color, Mode.MULTIPLY);
        pp.setColorFilter(frontFilter);
        Canvas cc = new Canvas(grayscaleBitmap);
        final Rect rect = new Rect(0, 0, grayscaleBitmap.getWidth(), grayscaleBitmap.getHeight());
        cc.drawBitmap(grayscaleBitmap, rect, rect, pp);
        return grayscaleBitmap;
    }

    private static Bitmap toGrayscale(Bitmap bmpOriginal) {
        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        ColorMatrix cm = new ColorMatrix();
        final Rect rect = new Rect(0, 0, width, height);
        cm.setSaturation(0);

        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, rect, rect, paint);
        return bmpGrayscale;
    }

    public static Drawable resize(Context context, Drawable image, int size) {
        if (image == null || context == null) {
            return null;
        }
        if (image instanceof VectorDrawable) {
            return image;
        } else {
            int newSize = ActionUtils.dpToPx(context, size);
            Bitmap bitmap = ((BitmapDrawable) image).getBitmap();
            Bitmap scaledBitmap = Bitmap.createBitmap(newSize, newSize, Config.ARGB_8888);

            float ratioX = newSize / (float) bitmap.getWidth();
            float ratioY = newSize / (float) bitmap.getHeight();
            float middleX = newSize / 2.0f;
            float middleY = newSize / 2.0f;

            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setAntiAlias(true);

            Matrix scaleMatrix = new Matrix();
            scaleMatrix.setScale(ratioX, ratioY, middleX, middleY);

            Canvas canvas = new Canvas(scaledBitmap);
            canvas.setMatrix(scaleMatrix);
            canvas.drawBitmap(bitmap, middleX - bitmap.getWidth() / 2,
                    middleY - bitmap.getHeight() / 2, paint);
            return new BitmapDrawable(context.getResources(), scaledBitmap);
        }
    }

    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int color = 0xff424242;
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);
        final float roundPx = 24;
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(color);
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint);
        paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    public static Bitmap getCircleBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        Bitmap output = Bitmap.createBitmap(width, height,
                Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        BitmapShader shader = new BitmapShader(bitmap,  TileMode.CLAMP, TileMode.CLAMP);
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setShader(shader);

        canvas.drawCircle(width/2, height/2, width/2, paint);

        return output;
    }

    public static Drawable getVector(Resources res, int resId) {
        return getVector(res, resId, 0, 0, false);
    }

    public static Drawable getVector(Resources res, int resId, int width, int height) {
        return getVector(res, resId, width, height, false);
    }

    public static Drawable getVector(Resources res, int resId, boolean toBitmapDrawable) {
        return getVector(res, resId, 0, 0, toBitmapDrawable);
    }

    public static Drawable getVector(Resources res, int resId, int width, int height,
            boolean toBitmapDrawable) {
        if (width <= 0) {
            width = VECTOR_WIDTH;
        }
        if (height <= 0) {
            width = VECTOR_HEIGHT;
        }

        VectorDrawable vectorDrawable = new VectorDrawable();
        vectorDrawable.setBounds(0, 0, width, height);
        try {
            XmlPullParser parser = res.getXml(resId);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG &&
                    type != XmlPullParser.END_DOCUMENT) {
                // Empty loop
            }

            if (type != XmlPullParser.START_TAG) {
//                Log.e("ImageHelper VectorLoader", "No start tag found");
            }

            vectorDrawable.inflate(res, parser, attrs);

            if (!toBitmapDrawable) {
                return vectorDrawable;
            }

            return new BitmapDrawable(res, drawableToBitmap(vectorDrawable));
        } catch (Exception e) {
//            Log.e("ImageHelper VectorLoader", "Error loading resource ID " + String.valueOf(resId) + " Try loading as a non vector");
            return null;
        }
    }

    /**
     * @param context callers context
     * @param uri Uri to handle
     * @return A bitmap from the requested uri
     * @throws IOException
     *
     * @Credit: StackOverflow
     *             http://stackoverflow.com/questions/35909008/pick-image
     *             -from-gallery-or-google-photos-failing
     */
    public static Bitmap getBitmapFromUri(Context context, Uri uri) throws IOException {
        if (context == null || uri == null) {
            return null;
        }
        ParcelFileDescriptor parcelFileDescriptor =
                context.getContentResolver().openFileDescriptor(uri, "r");
        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        parcelFileDescriptor.close();
        return image;
    }

    /**
     * @param storageDir Desired location in storage as a File
     * @param fileName Name of bitmap file to store
     * @param bitmap the bitmap to store
     * @return the Uri of the bitmap
     */
    public static Uri addBitmapToStorage(File storageDir, String fileName, Bitmap bitmap) {
        if (storageDir == null || fileName == null || bitmap == null) {
            return null;
        }
        File imageFile = new File(storageDir, fileName);
        try {
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            return null;
        }
        return Uri.fromFile(imageFile);
    }
}
