package com.android.databinding.library;

import android.view.View;

/**
 * Created by yboyar on 11/8/14.
 */
public interface DataBinderMapper {
    ViewDataBinder getDataBinder(View view, int layoutId);
    public int getId(String key);
}
