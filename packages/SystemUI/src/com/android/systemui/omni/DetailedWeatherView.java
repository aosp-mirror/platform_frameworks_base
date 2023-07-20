/*
* Copyright (C) 2017 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.omni;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.util.xtended.OmniJawsClient;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.plugins.ActivityStarter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DetailedWeatherView extends FrameLayout {

    static final String TAG = "DetailedWeatherView";
    static final boolean DEBUG = false;

    private TextView mWeatherCity;
    private TextView mWeatherTimestamp;
    private TextView mWeatherData;
    private ImageView mCurrentImage;
    private ImageView mForecastImage0;
    private ImageView mForecastImage1;
    private ImageView mForecastImage2;
    private ImageView mForecastImage3;
    private ImageView mForecastImage4;
    private TextView mForecastTemp0;
    private TextView mForecastTemp1;
    private TextView mForecastTemp2;
    private TextView mForecastTemp3;
    private TextView mForecastTemp4;
    private TextView mForecastText0;
    private TextView mForecastText1;
    private TextView mForecastText2;
    private TextView mForecastText3;
    private TextView mForecastText4;
    private ActivityStarter mActivityStarter;
    private OmniJawsClient mWeatherClient;
    private View mCurrentView;
    private TextView mCurrentTemp;
    private TextView mCurrentText;
    private View mProgressContainer;
    private TextView mStatusMsg;
    private View mEmptyView;
    private ImageView mEmptyViewImage;
    private View mWeatherLine;
    private TextView mProviderName;

    public DetailedWeatherView(Context context) {
        this(context, null);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetailedWeatherView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setWeatherClient(OmniJawsClient client) {
        mWeatherClient = client;
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mProgressContainer = findViewById(R.id.progress_container);
        mWeatherCity  = (TextView) findViewById(R.id.current_weather_city);
        mWeatherTimestamp  = (TextView) findViewById(R.id.current_weather_timestamp);
        mWeatherData  = (TextView) findViewById(R.id.current_weather_data);
        mForecastImage0  = (ImageView) findViewById(R.id.forecast_image_0);
        mForecastImage1  = (ImageView) findViewById(R.id.forecast_image_1);
        mForecastImage2  = (ImageView) findViewById(R.id.forecast_image_2);
        mForecastImage3  = (ImageView) findViewById(R.id.forecast_image_3);
        mForecastImage4  = (ImageView) findViewById(R.id.forecast_image_4);
        mForecastTemp0 = (TextView) findViewById(R.id.forecast_temp_0);
        mForecastTemp1 = (TextView) findViewById(R.id.forecast_temp_1);
        mForecastTemp2 = (TextView) findViewById(R.id.forecast_temp_2);
        mForecastTemp3 = (TextView) findViewById(R.id.forecast_temp_3);
        mForecastTemp4 = (TextView) findViewById(R.id.forecast_temp_4);
        mForecastText0 = (TextView) findViewById(R.id.forecast_text_0);
        mForecastText1 = (TextView) findViewById(R.id.forecast_text_1);
        mForecastText2 = (TextView) findViewById(R.id.forecast_text_2);
        mForecastText3 = (TextView) findViewById(R.id.forecast_text_3);
        mForecastText4 = (TextView) findViewById(R.id.forecast_text_4);
        mCurrentView = findViewById(R.id.current);
        mCurrentImage  = (ImageView) findViewById(R.id.current_image);
        mCurrentTemp = (TextView) findViewById(R.id.current_temp);
        mCurrentText = (TextView) findViewById(R.id.current_text);
        mStatusMsg = (TextView) findViewById(R.id.status_msg);
        mEmptyView = findViewById(android.R.id.empty);
        mEmptyViewImage = (ImageView) findViewById(R.id.empty_weather_image);
        mWeatherLine = findViewById(R.id.current_weather);
        mProviderName = (TextView) findViewById(R.id.current_weather_provider);

        mEmptyViewImage.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mWeatherClient.isOmniJawsEnabled()) {
                    startProgress();
                    forceRefreshWeatherSettings();
                }
                return true;
            }
        });

        mWeatherLine.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mWeatherClient.isOmniJawsEnabled()) {
                    startProgress();
                    forceRefreshWeatherSettings();
                }
                return true;
            }
        });
    }

    public void updateWeatherData(OmniJawsClient.WeatherInfo weatherData) {
        if (DEBUG) Log.d(TAG, "updateWeatherData");
        mProgressContainer.setVisibility(View.GONE);

        if (weatherData == null || !mWeatherClient.isOmniJawsEnabled()) {
            setErrorView();
            if (mWeatherClient.isOmniJawsEnabled()) {
                mEmptyViewImage.setImageResource(R.drawable.ic_qs_weather_default_on);
                mStatusMsg.setText(getResources().getString(R.string.omnijaws_service_unknown));
            } else {
                mEmptyViewImage.setImageResource(R.drawable.ic_qs_weather_default_off);
                mStatusMsg.setText(getResources().getString(R.string.omnijaws_service_disabled));
            }
            return;
        }
        mEmptyView.setVisibility(View.GONE);
        mWeatherLine.setVisibility(View.VISIBLE);

        mWeatherCity.setText(weatherData.city);
        mProviderName.setText(weatherData.provider);
        Long timeStamp = weatherData.timeStamp;
        String format = DateFormat.is24HourFormat(mContext) ? "HH:mm" : "hh:mm a";
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        mWeatherTimestamp.setText(getResources().getString(R.string.omnijaws_service_last_update) + " " + sdf.format(timeStamp));
        mWeatherData.setText(weatherData.windSpeed + " " + weatherData.windUnits + " " + weatherData.pinWheel +" - " +
                weatherData.humidity);

        sdf = new SimpleDateFormat("EE");
        Calendar cal = Calendar.getInstance();
        String dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        Drawable d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.forecasts.get(0).conditionCode));
        mForecastImage0.setImageDrawable(d);
        mForecastTemp0.setText(formatTempString(weatherData.forecasts.get(0).low,
                weatherData.forecasts.get(0).high, weatherData.tempUnits));
        mForecastText0.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.forecasts.get(1).conditionCode));
        mForecastImage1.setImageDrawable(d);
        mForecastTemp1.setText(formatTempString(weatherData.forecasts.get(1).low,
                weatherData.forecasts.get(1).high, weatherData.tempUnits));
        mForecastText1.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.forecasts.get(2).conditionCode));
        mForecastImage2.setImageDrawable(d);
        mForecastTemp2.setText(formatTempString(weatherData.forecasts.get(2).low,
                weatherData.forecasts.get(2).high, weatherData.tempUnits));
        mForecastText2.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.forecasts.get(3).conditionCode));
        mForecastImage3.setImageDrawable(d);
        mForecastTemp3.setText(formatTempString(weatherData.forecasts.get(3).low,
                weatherData.forecasts.get(3).high, weatherData.tempUnits));
        mForecastText3.setText(dayShort);

        cal.add(Calendar.DATE, 1);
        dayShort = sdf.format(new Date(cal.getTimeInMillis()));

        d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.forecasts.get(4).conditionCode));
        mForecastImage4.setImageDrawable(d);
        mForecastTemp4.setText(formatTempString(weatherData.forecasts.get(4).low,
                weatherData.forecasts.get(4).high, weatherData.tempUnits));
        mForecastText4.setText(dayShort);

        d = overlay(mContext.getResources(), mWeatherClient.getWeatherConditionImage(
                weatherData.conditionCode));
        mCurrentImage.setImageDrawable(d);
        mCurrentTemp.setText(formatTempString(weatherData.temp, null, weatherData.tempUnits));
        mCurrentText.setText(mContext.getResources().getText(R.string.omnijaws_current_text));

    }

    private Drawable overlay(Resources resources, Drawable image) {
        if (image instanceof VectorDrawable) {
            image = applyTint(image);
        }
        final Canvas canvas = new Canvas();
        canvas.setDrawFilter(new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG,
                Paint.FILTER_BITMAP_FLAG));
        final int height = image.getIntrinsicHeight();
        final int width = image.getIntrinsicWidth();

        final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bmp);
        image.setBounds(0, 0, width, height);
        image.draw(canvas);

        return new BitmapDrawable(resources, bmp);
    }

    private Drawable applyTint(Drawable icon) {
        icon = icon.mutate();
        icon.setTint(getTintColor());
        return icon;
    }

    private int getTintColor() {
        TypedArray array = mContext.obtainStyledAttributes(new int[]{android.R.attr.colorControlNormal});
        int color = array.getColor(0, 0);
        array.recycle();
        return color;
    }

    private void forceRefreshWeatherSettings() {
        mWeatherClient.updateWeather();
    }

    private void setErrorView() {
        mEmptyView.setVisibility(View.VISIBLE);
        mWeatherLine.setVisibility(View.GONE);
    }

    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
        mProgressContainer.setVisibility(View.GONE);
        setErrorView();

        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mEmptyViewImage.setImageResource(R.drawable.ic_qs_weather_default_off);
            mStatusMsg.setText(getResources().getString(R.string.omnijaws_service_disabled));
        } else {
            mEmptyViewImage.setImageResource(R.drawable.ic_qs_weather_default_on);
            mStatusMsg.setText(getResources().getString(R.string.omnijaws_service_error_long));
        }
    }

    public void startProgress() {
        mEmptyView.setVisibility(View.GONE);
        mWeatherLine.setVisibility(View.GONE);
        mProgressContainer.setVisibility(View.VISIBLE);
    }

    public void stopProgress() {
        mProgressContainer.setVisibility(View.GONE);
    }

    private static String formatTempString(String min, String max, String tempUnits) {
        String str = null;
        if (max != null) {
            str = min + "/" + max + tempUnits;
        } else {
            str = min + tempUnits;
        }
        return str;
    }
}
