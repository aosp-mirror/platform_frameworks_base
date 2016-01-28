/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.test.mock;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.content.res.ColorStateList;
import android.content.res.XmlResourceParser;
import android.content.res.AssetFileDescriptor;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.util.AttributeSet;
import android.graphics.drawable.Drawable;
import android.graphics.Movie;

import java.io.InputStream;

/**
 * A mock {@link android.content.res.Resources} class. All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}. Override it to provide the operations that you
 * need.
 *
 * @deprecated Use a mocking framework like <a href="https://github.com/mockito/mockito">Mockito</a>.
 * New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class MockResources extends Resources {

    public MockResources() {
        super(new AssetManager(), null, null);
    }

    @Override
    public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
        // this method is called from the constructor, so we just do nothing
    }

    @Override
    public CharSequence getText(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public CharSequence getQuantityText(int id, int quantity) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getString(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getString(int id, Object... formatArgs) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getQuantityString(int id, int quantity, Object... formatArgs)
            throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getQuantityString(int id, int quantity) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public CharSequence getText(int id, CharSequence def) {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public CharSequence[] getTextArray(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String[] getStringArray(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int[] getIntArray(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public TypedArray obtainTypedArray(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public float getDimension(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int getDimensionPixelOffset(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int getDimensionPixelSize(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public Movie getMovie(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int getColor(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public ColorStateList getColorStateList(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int getInteger(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public XmlResourceParser getLayout(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public XmlResourceParser getAnimation(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public XmlResourceParser getXml(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public InputStream openRawResource(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public AssetFileDescriptor openRawResourceFd(int id) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public void getValue(int id, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public void getValue(String name, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public TypedArray obtainAttributes(AttributeSet set, int[] attrs) {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public DisplayMetrics getDisplayMetrics() {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public Configuration getConfiguration() {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public int getIdentifier(String name, String defType, String defPackage) {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getResourceName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getResourcePackageName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getResourceTypeName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }

    @Override
    public String getResourceEntryName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException("mock object, not implemented");
    }
}
