package com.android.databinding.library;

/**
 * Created by yboyar on 11/9/14.
 */
public interface Observable {
    public void register(ObservableListener listener);
    public void unRegister(ObservableListener listener);
}
