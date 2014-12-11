package com.android.databinding.library;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Created by yboyar on 11/19/14.
 */
public class ObservableHelper implements Observable {
    final Observable owner;
    CopyOnWriteArraySet<ObservableListener> mListeners;

    public ObservableHelper(Observable owner) {
        this.owner = owner;
    }

    private synchronized CopyOnWriteArraySet<ObservableListener> getListeners(boolean createIfMissing) {
        if (mListeners == null) {
            if (createIfMissing) {
                mListeners = new CopyOnWriteArraySet<>();
            }
        }
        return mListeners;
    }

    public void fireChange() {
        fireChange("");
    }
    public void fireChange(String fieldName) {
        fireChange(DataBinder.convertToId(fieldName));
    }
    public void fireChange(int fieldId) {
        final CopyOnWriteArraySet<ObservableListener> listeners = getListeners(false);
        if (listeners == null) {
            return;
        }
        for (ObservableListener listener : listeners) {
            listener.onChange(fieldId);
        }
    }

    @Override
    public void register(ObservableListener listener) {
        getListeners(true).add(listener);
    }

    @Override
    public void unRegister(ObservableListener listener) {
        final CopyOnWriteArraySet<ObservableListener> listeners = getListeners(false);
        if (listener != null) {
            listeners.remove(listener);
        }
    }
}
