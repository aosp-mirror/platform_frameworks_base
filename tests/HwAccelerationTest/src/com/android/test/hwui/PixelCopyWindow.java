package com.android.test.hwui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Paint.Style;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PixelCopyWindow extends Activity {

    private Handler mHandler;
    private ImageView mImage;
    private TextView mText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();

        LinearLayout layout = new LinearLayout(this);
        TextView text = new TextView(this);
        text.setText("Hello, World!");
        Button btn = new Button(this);
        btn.setText("Screenshot!");
        btn.setOnClickListener((v) -> takeScreenshot());
        mImage = new ImageView(this);
        mText = new TextView(this);

        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(text);
        layout.addView(btn);
        layout.addView(mImage);
        layout.addView(mText);
        final float density = getResources().getDisplayMetrics().density;
        layout.setBackground(new Drawable() {
            Paint mPaint = new Paint();

            @Override
            public void draw(Canvas canvas) {
                mPaint.setStyle(Style.STROKE);
                mPaint.setStrokeWidth(4 * density);
                mPaint.setColor(Color.BLUE);
                final Rect bounds = getBounds();
                canvas.drawRect(bounds, mPaint);
                mPaint.setColor(Color.RED);
                canvas.drawLine(bounds.centerX(), 0, bounds.centerX(), bounds.height(), mPaint);
                mPaint.setColor(Color.GREEN);
                canvas.drawLine(0, bounds.centerY(), bounds.width(), bounds.centerY(), mPaint);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        });
        setContentView(layout);
    }

    private void takeScreenshot() {
        View decor = getWindow().getDecorView();
        Rect srcRect = new Rect();
        decor.getGlobalVisibleRect(srcRect);
        final Bitmap bitmap = Bitmap.createBitmap(
                (int) (srcRect.width() * .25), (int) (srcRect.height() * .25), Config.ARGB_8888);
        PixelCopy.request(getWindow(), srcRect, bitmap, (result) -> {
            if (result != PixelCopy.SUCCESS) {
                mText.setText("Copy failed, result: " + result);
                mImage.setImageBitmap(null);
            } else {
                mText.setText("");
                mImage.setImageBitmap(bitmap);
            }
        }, mHandler);
    }
}
