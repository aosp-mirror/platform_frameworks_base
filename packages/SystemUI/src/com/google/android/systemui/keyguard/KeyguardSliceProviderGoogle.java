package com.google.android.systemui.keyguard;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BlurMaskFilter;
import android.graphics.BlurMaskFilter.Blur;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.graphics.drawable.IconCompat;
import androidx.slice.Slice;
import androidx.slice.builders.ListBuilder;
import androidx.slice.builders.ListBuilder.HeaderBuilder;
import androidx.slice.builders.ListBuilder.RowBuilder;
import androidx.slice.builders.SliceAction;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.google.android.systemui.smartspace.SmartSpaceCard;
import com.google.android.systemui.smartspace.SmartSpaceController;
import com.google.android.systemui.smartspace.SmartSpaceData;
import com.google.android.systemui.smartspace.SmartSpaceUpdateListener;
import java.lang.ref.WeakReference;

public class KeyguardSliceProviderGoogle extends KeyguardSliceProvider implements SmartSpaceUpdateListener {
    private final Uri mCalendarUri = Uri.parse("content://com.android.systemui.keyguard/smartSpace/calendar");
    private SmartSpaceData mSmartSpaceData;
    private final Uri mWeatherUri = Uri.parse("content://com.android.systemui.keyguard/smartSpace/weather");

    private static class AddShadowTask extends AsyncTask<Bitmap, Void, Bitmap> {
        private final float mBlurRadius;
        private final WeakReference<KeyguardSliceProviderGoogle> mProviderReference;
        private final SmartSpaceCard mWeatherCard;

        AddShadowTask(KeyguardSliceProviderGoogle keyguardSliceProviderGoogle, SmartSpaceCard smartSpaceCard) {
            mProviderReference = new WeakReference<>(keyguardSliceProviderGoogle);
            mWeatherCard = smartSpaceCard;
            mBlurRadius = keyguardSliceProviderGoogle.getContext().getResources().getDimension(R.dimen.smartspace_icon_shadow);
        }

        @Override
        public Bitmap doInBackground(Bitmap... bitmapArr) {
            return applyShadow(bitmapArr[0]);
        }

        @Override
        public void onPostExecute(Bitmap bitmap) {
            KeyguardSliceProviderGoogle keyguardSliceProviderGoogle;
            synchronized (this) {
                mWeatherCard.setIcon(bitmap);
                keyguardSliceProviderGoogle = (KeyguardSliceProviderGoogle) mProviderReference.get();
            }
            if (keyguardSliceProviderGoogle != null) {
                keyguardSliceProviderGoogle.notifyChange();
            }
        }

        private Bitmap applyShadow(Bitmap bitmap) {
            BlurMaskFilter blurMaskFilter = new BlurMaskFilter(mBlurRadius, Blur.NORMAL);
            Paint paint = new Paint();
            paint.setMaskFilter(blurMaskFilter);
            int[] iArr = new int[2];
            Bitmap extractAlpha = bitmap.extractAlpha(paint, iArr);
            Bitmap createBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Config.ARGB_8888);
            Canvas canvas = new Canvas(createBitmap);
            Paint paint2 = new Paint();
            paint2.setAlpha(70);
            canvas.drawBitmap(extractAlpha, (float) iArr[0], ((float) iArr[1]) + (mBlurRadius / 2.0f), paint2);
            extractAlpha.recycle();
            paint2.setAlpha(255);
            canvas.drawBitmap(bitmap, 0.0f, 0.0f, paint2);
            return createBitmap;
        }
    }

    @Override
    public boolean onCreateSliceProvider() {
        boolean onCreateSliceProvider = super.onCreateSliceProvider();
        SmartSpaceController.get(getContext()).addListener(this);
        mSmartSpaceData = new SmartSpaceData();
        return onCreateSliceProvider;
    }

    @Override
    public Slice onBindSlice(Uri uri) {
        Trace.beginSection("KeyguardSliceProviderGoogle#onBindSlice");
        Slice slice;
        synchronized (this) {
            ListBuilder listBuilder = new ListBuilder(getContext(), mSliceUri, -1);
            SmartSpaceCard currentCard = mSmartSpaceData.getCurrentCard();
            if (currentCard == null || currentCard.isExpired() || TextUtils.isEmpty(currentCard.getTitle())) {
                if (needsMediaLocked()) {
                    addMediaLocked(listBuilder);
                } else {
                    RowBuilder rowBuilder = new RowBuilder(mDateUri);
                    rowBuilder.setTitle(getFormattedDateLocked());
                    listBuilder.addRow(rowBuilder);
                }
                addWeather(listBuilder);
                addNextAlarmLocked(listBuilder);
                addZenModeLocked(listBuilder);
                addPrimaryActionLocked(listBuilder);
            } else {
                Bitmap icon = currentCard.getIcon();
                SliceAction sliceAction = null;
                IconCompat iconCompat;
                if (icon == null) {
                    iconCompat = null;
                } else {
                    iconCompat = IconCompat.createWithBitmap(icon);
                }
                PendingIntent pendingIntent = currentCard.getPendingIntent();
                if (iconCompat != null) {
                    if (pendingIntent != null) {
                        sliceAction = SliceAction.create(pendingIntent, iconCompat, 1, currentCard.getTitle());
                    }
                }
                HeaderBuilder headerBuilder = new HeaderBuilder(mHeaderUri);
                headerBuilder.setTitle(currentCard.getFormattedTitle());
                if (sliceAction != null) {
                    headerBuilder.setPrimaryAction(sliceAction);
                }
                listBuilder.setHeader(headerBuilder);
                String subtitle = currentCard.getSubtitle();
                if (subtitle != null) {
                    RowBuilder rowBuilder2 = new RowBuilder(mCalendarUri);
                    rowBuilder2.setTitle(subtitle);
                    if (iconCompat != null) {
                        rowBuilder2.addEndItem(iconCompat, 1);
                    }
                    if (sliceAction != null) {
                        rowBuilder2.setPrimaryAction(sliceAction);
                    }
                    listBuilder.addRow(rowBuilder2);
                }
                addWeather(listBuilder);
                addZenModeLocked(listBuilder);
                addPrimaryActionLocked(listBuilder);
            }
            slice = listBuilder.build();
        }
        Trace.endSection();
        return slice;
    }

    private void addWeather(ListBuilder listBuilder) {
        SmartSpaceCard weatherCard = mSmartSpaceData.getWeatherCard();
        if (weatherCard != null && !weatherCard.isExpired()) {
            RowBuilder rowBuilder = new RowBuilder(mWeatherUri);
            rowBuilder.setTitle(weatherCard.getTitle());
            Bitmap icon = weatherCard.getIcon();
            if (icon != null) {
                IconCompat createWithBitmap = IconCompat.createWithBitmap(icon);
                createWithBitmap.setTintMode(Mode.DST);
                rowBuilder.addEndItem(createWithBitmap, 1);
            }
            listBuilder.addRow(rowBuilder);
        }
    }

    @Override
    public void onSmartSpaceUpdated(SmartSpaceData smartSpaceData) {
        synchronized (this) {
            mSmartSpaceData = smartSpaceData;
        }
        SmartSpaceCard weatherCard = smartSpaceData.getWeatherCard();
        if (weatherCard == null || weatherCard.getIcon() == null || weatherCard.isIconProcessed()) {
            notifyChange();
            return;
        }
        weatherCard.setIconProcessed(true);
        new AddShadowTask(this, weatherCard).execute(new Bitmap[]{weatherCard.getIcon()});
    }

    @Override
    public void updateClockLocked() {
        notifyChange();
    }
}
