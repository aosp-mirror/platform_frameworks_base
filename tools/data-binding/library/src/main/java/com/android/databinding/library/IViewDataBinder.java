package com.android.databinding.library;

import android.view.View;

/**
 * Created by yboyar on 11/14/14.
 */
public interface IViewDataBinder {
    public View getRoot();
    public abstract void rebindDirty();
}
