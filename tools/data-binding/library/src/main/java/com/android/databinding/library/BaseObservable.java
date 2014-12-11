package com.android.databinding.library;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Created by yboyar on 11/9/14.
 */
public class BaseObservable implements Observable {
    final ObservableHelper mHelper;

    public BaseObservable() {
        mHelper = new ObservableHelper(this);
    }

    @Override
    public void register(ObservableListener listener) {
        mHelper.register(listener);
    }

    @Override
    public void unRegister(ObservableListener listener) {
        mHelper.unRegister(listener);
    }

    public void fireChange() {
        mHelper.fireChange();
    }
    public void fireChange(String fieldName) {
        mHelper.fireChange(fieldName);
    }
    public void fireChange(int fieldId) {mHelper.fireChange(fieldId);}
}
