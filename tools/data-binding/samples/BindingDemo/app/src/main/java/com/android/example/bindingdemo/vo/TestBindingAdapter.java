package com.android.example.bindingdemo.vo;

import android.binding.BindingAdapter;
import android.widget.TextView;

public class TestBindingAdapter {
    @BindingAdapter(attribute = "android:text")
    public static void setText(TextView view, String value) {
        view.setText(value);
    }
    @BindingAdapter(attribute = "android:text")
    public static void setObjectText(Object view, String value) {
        ((TextView)view).setText(String.valueOf(value));
    }
    @BindingAdapter(attribute = "android:text")
    public static void setTextObject(TextView view, Object value) {
        view.setText(String.valueOf(value));
    }
    @BindingAdapter(attribute = "android:text")
    public static void setTextFloat(TextView view, float value) {
        view.setText(String.valueOf(value));
    }
}
