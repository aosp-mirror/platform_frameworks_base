/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License") {}
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

package android.graphics.drawable;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.internal.view.animation.NativeInterpolatorFactoryHelper_Delegate;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.AnimatedVectorDrawable.VectorDrawableAnimatorRT;
import android.graphics.drawable.VectorDrawable_Delegate.VFullPath_Delegate;
import android.graphics.drawable.VectorDrawable_Delegate.VGroup_Delegate;
import android.graphics.drawable.VectorDrawable_Delegate.VNativeObject;
import android.graphics.drawable.VectorDrawable_Delegate.VPathRenderer_Delegate;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Delegate used to provide new implementation of a select few methods of {@link
 * AnimatedVectorDrawable}
 * <p>
 * Through the layoutlib_create tool, the original  methods of AnimatedVectorDrawable have been
 * replaced by calls to methods of the same name in this delegate class.
 */
@SuppressWarnings("unused")
public class AnimatedVectorDrawable_Delegate {
    private static DelegateManager<AnimatorSetHolder> sAnimatorSets = new
            DelegateManager<>(AnimatorSetHolder.class);
    private static DelegateManager<PropertySetter> sHolders = new
            DelegateManager<>(PropertySetter.class);


    @LayoutlibDelegate
    /*package*/ static long nCreateAnimatorSet() {
        return sAnimatorSets.addNewDelegate(new AnimatorSetHolder());
    }

    @LayoutlibDelegate
    /*package*/ static void nAddAnimator(long setPtr, long propertyValuesHolder,
            long nativeInterpolator, long startDelay, long duration, int repeatCount) {
        PropertySetter holder = sHolders.getDelegate(propertyValuesHolder);
        if (holder == null || holder.getValues() == null) {
            return;
        }

        ObjectAnimator animator = new ObjectAnimator();
        animator.setValues(holder.getValues());
        animator.setInterpolator(
                NativeInterpolatorFactoryHelper_Delegate.getDelegate(nativeInterpolator));
        animator.setStartDelay(startDelay);
        animator.setDuration(duration);
        animator.setRepeatCount(repeatCount);
        animator.setTarget(holder);
        animator.setPropertyName(holder.getValues().getPropertyName());

        AnimatorSetHolder set = sAnimatorSets.getDelegate(setPtr);
        assert set != null;
        set.addAnimator(animator);
    }

    @LayoutlibDelegate
    /*package*/ static long nCreateGroupPropertyHolder(long nativePtr, int propertyId,
            float startValue, float endValue) {
        VGroup_Delegate group = VNativeObject.getDelegate(nativePtr);
        Consumer<Float> setter = group.getPropertySetter(propertyId);

        return sHolders.addNewDelegate(FloatPropertySetter.of(setter, startValue,
                endValue));
    }

    @LayoutlibDelegate
    /*package*/ static long nCreatePathDataPropertyHolder(long nativePtr, long startValuePtr,
            long endValuePtr) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED, "AnimatedVectorDrawable path " +
                "animations are not supported.", null, null);
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static long nCreatePathColorPropertyHolder(long nativePtr, int propertyId,
            int startValue, int endValue) {
        VFullPath_Delegate path = VNativeObject.getDelegate(nativePtr);
        Consumer<Integer> setter = path.getIntPropertySetter(propertyId);

        return sHolders.addNewDelegate(IntPropertySetter.of(setter, startValue,
                endValue));
    }

    @LayoutlibDelegate
    /*package*/ static long nCreatePathPropertyHolder(long nativePtr, int propertyId,
            float startValue, float endValue) {
        VFullPath_Delegate path = VNativeObject.getDelegate(nativePtr);
        Consumer<Float> setter = path.getFloatPropertySetter(propertyId);

        return sHolders.addNewDelegate(FloatPropertySetter.of(setter, startValue,
                endValue));
    }

    @LayoutlibDelegate
    /*package*/ static long nCreateRootAlphaPropertyHolder(long nativePtr, float startValue,
            float endValue) {
        VPathRenderer_Delegate renderer = VNativeObject.getDelegate(nativePtr);

        return sHolders.addNewDelegate(FloatPropertySetter.of(renderer::setRootAlpha,
                startValue,
                endValue));
    }

    @LayoutlibDelegate
    /*package*/ static void nSetPropertyHolderData(long nativePtr, float[] data, int length) {
        PropertySetter setter = sHolders.getDelegate(nativePtr);
        assert setter != null;

        setter.setValues(data);
    }

    @LayoutlibDelegate
    /*package*/ static void nStart(long animatorSetPtr, VectorDrawableAnimatorRT set, int id) {
        AnimatorSetHolder animatorSet = sAnimatorSets.getDelegate(animatorSetPtr);
        assert animatorSet != null;

        animatorSet.start();
    }

    @LayoutlibDelegate
    /*package*/ static void nReverse(long animatorSetPtr, VectorDrawableAnimatorRT set, int id) {
        AnimatorSetHolder animatorSet = sAnimatorSets.getDelegate(animatorSetPtr);
        assert animatorSet != null;

        animatorSet.reverse();
    }

    @LayoutlibDelegate
    /*package*/ static void nEnd(long animatorSetPtr) {
        AnimatorSetHolder animatorSet = sAnimatorSets.getDelegate(animatorSetPtr);
        assert animatorSet != null;

        animatorSet.end();
    }

    @LayoutlibDelegate
    /*package*/ static void nReset(long animatorSetPtr) {
        AnimatorSetHolder animatorSet = sAnimatorSets.getDelegate(animatorSetPtr);
        assert animatorSet != null;

        animatorSet.end();
        animatorSet.start();
    }

    private static class AnimatorSetHolder {
        private ArrayList<Animator> mAnimators = new ArrayList<>();
        private AnimatorSet mAnimatorSet = null;

        private void addAnimator(@NonNull Animator animator) {
            mAnimators.add(animator);
        }

        private void ensureAnimatorSet() {
            if (mAnimatorSet == null) {
                mAnimatorSet = new AnimatorSet();
                mAnimatorSet.playTogether(mAnimators);
            }
        }

        private void start() {
            ensureAnimatorSet();

            mAnimatorSet.start();
        }

        private void end() {
            mAnimatorSet.end();
        }

        private void reset() {
            end();
            start();
        }

        private void reverse() {
            mAnimatorSet.reverse();
        }
    }

    /**
     * Class that allows setting a value and holds the range of values for the given property.
     *
     * @param <T> the type of the property
     */
    private static class PropertySetter<T> {
        final Consumer<T> mValueSetter;
        private PropertyValuesHolder mValues;

        private PropertySetter(@NonNull Consumer<T> valueSetter) {
            mValueSetter = valueSetter;
        }

        /**
         * Method to set an {@link Integer} value for this property. The default implementation of
         * this method doesn't do anything. This method is accessed via reflection by the
         * PropertyValuesHolder.
         */
        public void setIntValue(Integer value) {
        }

        /**
         * Method to set an {@link Integer} value for this property. The default implementation of
         * this method doesn't do anything. This method is accessed via reflection by the
         * PropertyValuesHolder.
         */
        public void setFloatValue(Float value) {
        }

        void setValues(float... values) {
            mValues = PropertyValuesHolder.ofFloat("floatValue", values);
        }

        @Nullable
        PropertyValuesHolder getValues() {
            return mValues;
        }

        void setValues(int... values) {
            mValues = PropertyValuesHolder.ofInt("intValue", values);
        }
    }

    private static class IntPropertySetter extends PropertySetter<Integer> {
        private IntPropertySetter(Consumer<Integer> valueSetter) {
            super(valueSetter);
        }

        private static PropertySetter of(Consumer<Integer> valueSetter, int... values) {
            PropertySetter setter = new IntPropertySetter(valueSetter);
            setter.setValues(values);

            return setter;
        }

        public void setIntValue(Integer value) {
            mValueSetter.accept(value);
        }
    }

    private static class FloatPropertySetter extends PropertySetter<Float> {
        private FloatPropertySetter(Consumer<Float> valueSetter) {
            super(valueSetter);
        }

        private static PropertySetter of(Consumer<Float> valueSetter, float... values) {
            PropertySetter setter = new FloatPropertySetter(valueSetter);
            setter.setValues(values);

            return setter;
        }

        public void setFloatValue(Float value) {
            mValueSetter.accept(value);
        }

    }
}
