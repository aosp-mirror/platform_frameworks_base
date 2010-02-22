// Copyright 2008 The Android Open Source Project

package android.test.mock;

import android.content.DialogInterface;

/**
 * A mock {@link android.content.DialogInterface} class.  All methods are non-functional and throw 
 * {@link java.lang.UnsupportedOperationException}. Override it to provide the operations that you 
 * need.
 */
public class MockDialogInterface implements DialogInterface {
    public void cancel() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void dismiss() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
