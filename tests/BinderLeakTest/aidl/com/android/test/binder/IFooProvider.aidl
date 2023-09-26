package com.android.test.binder;
import com.android.test.binder.IFoo;

interface IFooProvider {
    IFoo createFoo();

    boolean isFooGarbageCollected();

    oneway void killProcess();
}
