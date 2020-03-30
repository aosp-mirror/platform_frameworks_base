/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.BinderThread;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Size;
import android.util.Slog;
import android.view.SurfaceControlViewHost;
import android.widget.inline.InlineContentView;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class represents an inline suggestion which is made by one app
 * and can be embedded into the UI of another. Suggestions may contain
 * sensitive information not known to the host app which needs to be
 * protected from spoofing. To address that the suggestion view inflated
 * on demand for embedding is created in such a way that the hosting app
 * cannot introspect its content and cannot interact with it.
 */
@DataClass(
        genEqualsHashCode = true,
        genToString = true,
        genHiddenConstDefs = true,
        genHiddenConstructor = true)
@DataClass.Suppress({"getContentProvider"})
public final class InlineSuggestion implements Parcelable {

    private static final String TAG = "InlineSuggestion";

    private final @NonNull InlineSuggestionInfo mInfo;

    private final @Nullable IInlineContentProvider mContentProvider;

    /**
     * Used to keep a strong reference to the callback so it doesn't get garbage collected.
     *
     * @hide
     */
    @DataClass.ParcelWith(InlineContentCallbackImplParceling.class)
    private @Nullable InlineContentCallbackImpl mInlineContentCallback;

    /**
     * Creates a new {@link InlineSuggestion}, for testing purpose.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public static InlineSuggestion newInlineSuggestion(@NonNull InlineSuggestionInfo info) {
        return new InlineSuggestion(info, null, /* inlineContentCallback */ null);
    }

    /**
     * Creates a new {@link InlineSuggestion}.
     *
     * @hide
     */
    public InlineSuggestion(
            @NonNull InlineSuggestionInfo info,
            @Nullable IInlineContentProvider contentProvider) {
        this(info, contentProvider, /* inlineContentCallback */ null);
    }

    /**
     * Inflates a view with the content of this suggestion at a specific size.
     * The size must be between the
     * {@link android.widget.inline.InlinePresentationSpec#getMinSize() min size} and the
     * {@link android.widget.inline.InlinePresentationSpec#getMaxSize() max size} of the
     * presentation spec returned by {@link InlineSuggestionInfo#getInlinePresentationSpec()}.
     *
     * <p> The caller can attach an {@link android.view.View.OnClickListener} and/or an
     * {@link android.view.View.OnLongClickListener} to the view in the
     * {@code callback} to receive click and
     * long click events on the view.
     *
     * @param context  Context in which to inflate the view.
     * @param size     The size at which to inflate the suggestion.
     * @param callback Callback for receiving the inflated view.
     * @throws IllegalArgumentException If an invalid argument is passed.
     * @throws IllegalStateException    If this method is already called.
     */
    public void inflate(@NonNull Context context, @NonNull Size size,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<InlineContentView> callback) {
        final Size minSize = mInfo.getInlinePresentationSpec().getMinSize();
        final Size maxSize = mInfo.getInlinePresentationSpec().getMaxSize();
        if (size.getHeight() < minSize.getHeight() || size.getHeight() > maxSize.getHeight()
                || size.getWidth() < minSize.getWidth() || size.getWidth() > maxSize.getWidth()) {
            throw new IllegalArgumentException("size not between min:"
                    + minSize + " and max:" + maxSize);
        }
        mInlineContentCallback = getInlineContentCallback(context, callbackExecutor, callback);
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            if (mContentProvider == null) {
                callback.accept(/* view */ null);
                return;
            }
            try {
                mContentProvider.provideContent(size.getWidth(), size.getHeight(),
                        new InlineContentCallbackWrapper(mInlineContentCallback));
            } catch (RemoteException e) {
                Slog.w(TAG, "Error creating suggestion content surface: " + e);
                callback.accept(/* view */ null);
            }
        });
    }

    private synchronized InlineContentCallbackImpl getInlineContentCallback(Context context,
            Executor callbackExecutor, Consumer<InlineContentView> callback) {
        if (mInlineContentCallback != null) {
            throw new IllegalStateException("Already called #inflate()");
        }
        return new InlineContentCallbackImpl(context, callbackExecutor, callback);
    }

    private static final class InlineContentCallbackWrapper extends IInlineContentCallback.Stub {

        private final WeakReference<InlineContentCallbackImpl> mCallbackImpl;

        InlineContentCallbackWrapper(InlineContentCallbackImpl callbackImpl) {
            mCallbackImpl = new WeakReference<>(callbackImpl);
        }

        @Override
        @BinderThread
        public void onContent(SurfaceControlViewHost.SurfacePackage content) {
            final InlineContentCallbackImpl callbackImpl = mCallbackImpl.get();
            if (callbackImpl != null) {
                callbackImpl.onContent(content);
            }
        }

        @Override
        @BinderThread
        public void onClick() {
            final InlineContentCallbackImpl callbackImpl = mCallbackImpl.get();
            if (callbackImpl != null) {
                callbackImpl.onClick();
            }
        }

        @Override
        @BinderThread
        public void onLongClick() {
            final InlineContentCallbackImpl callbackImpl = mCallbackImpl.get();
            if (callbackImpl != null) {
                callbackImpl.onLongClick();
            }
        }
    }

    private static final class InlineContentCallbackImpl {

        private final @NonNull Context mContext;
        private final @NonNull Executor mCallbackExecutor;
        private final @NonNull Consumer<InlineContentView> mCallback;
        private @Nullable InlineContentView mView;

        InlineContentCallbackImpl(@NonNull Context context,
                @NonNull @CallbackExecutor Executor callbackExecutor,
                @NonNull Consumer<InlineContentView> callback) {
            mContext = context;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }

        @BinderThread
        public void onContent(SurfaceControlViewHost.SurfacePackage content) {
            if (content == null) {
                mCallbackExecutor.execute(() -> mCallback.accept(/* view */null));
            } else {
                mView = new InlineContentView(mContext);
                mView.setChildSurfacePackage(content);
                mCallbackExecutor.execute(() -> mCallback.accept(mView));
            }
        }

        @BinderThread
        public void onClick() {
            if (mView != null && mView.hasOnClickListeners()) {
                mView.callOnClick();
            }
        }

        @BinderThread
        public void onLongClick() {
            if (mView != null && mView.hasOnLongClickListeners()) {
                mView.performLongClick();
            }
        }
    }

    /**
     * This class used to provide parcelling logic for InlineContentCallbackImpl. It's intended to
     * make this parcelling a no-op, since it can't be parceled and we don't need to parcel it.
     */
    private static class InlineContentCallbackImplParceling implements
            Parcelling<InlineContentCallbackImpl> {
        @Override
        public void parcel(InlineContentCallbackImpl item, Parcel dest, int parcelFlags) {
        }

        @Override
        public InlineContentCallbackImpl unparcel(Parcel source) {
            return null;
        }
    }



    // Code below generated by codegen v1.0.15.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/inputmethod/InlineSuggestion.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new InlineSuggestion.
     *
     * @param inlineContentCallback
     *   Used to keep a strong reference to the callback so it doesn't get garbage collected.
     * @hide
     */
    @DataClass.Generated.Member
    public InlineSuggestion(
            @NonNull InlineSuggestionInfo info,
            @Nullable IInlineContentProvider contentProvider,
            @Nullable InlineContentCallbackImpl inlineContentCallback) {
        this.mInfo = info;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInfo);
        this.mContentProvider = contentProvider;
        this.mInlineContentCallback = inlineContentCallback;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @NonNull InlineSuggestionInfo getInfo() {
        return mInfo;
    }

    /**
     * Used to keep a strong reference to the callback so it doesn't get garbage collected.
     *
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable InlineContentCallbackImpl getInlineContentCallback() {
        return mInlineContentCallback;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "InlineSuggestion { " +
                "info = " + mInfo + ", " +
                "contentProvider = " + mContentProvider + ", " +
                "inlineContentCallback = " + mInlineContentCallback +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(InlineSuggestion other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        InlineSuggestion that = (InlineSuggestion) o;
        //noinspection PointlessBooleanExpression
        return true
                && java.util.Objects.equals(mInfo, that.mInfo)
                && java.util.Objects.equals(mContentProvider, that.mContentProvider)
                && java.util.Objects.equals(mInlineContentCallback, that.mInlineContentCallback);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + java.util.Objects.hashCode(mInfo);
        _hash = 31 * _hash + java.util.Objects.hashCode(mContentProvider);
        _hash = 31 * _hash + java.util.Objects.hashCode(mInlineContentCallback);
        return _hash;
    }

    @DataClass.Generated.Member
    static Parcelling<InlineContentCallbackImpl> sParcellingForInlineContentCallback =
            Parcelling.Cache.get(
                    InlineContentCallbackImplParceling.class);
    static {
        if (sParcellingForInlineContentCallback == null) {
            sParcellingForInlineContentCallback = Parcelling.Cache.put(
                    new InlineContentCallbackImplParceling());
        }
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mContentProvider != null) flg |= 0x2;
        if (mInlineContentCallback != null) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeTypedObject(mInfo, flags);
        if (mContentProvider != null) dest.writeStrongInterface(mContentProvider);
        sParcellingForInlineContentCallback.parcel(mInlineContentCallback, dest, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ InlineSuggestion(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        InlineSuggestionInfo info = (InlineSuggestionInfo) in.readTypedObject(InlineSuggestionInfo.CREATOR);
        IInlineContentProvider contentProvider = (flg & 0x2) == 0 ? null : IInlineContentProvider.Stub.asInterface(in.readStrongBinder());
        InlineContentCallbackImpl inlineContentCallback = sParcellingForInlineContentCallback.unparcel(in);

        this.mInfo = info;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mInfo);
        this.mContentProvider = contentProvider;
        this.mInlineContentCallback = inlineContentCallback;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<InlineSuggestion> CREATOR
            = new Parcelable.Creator<InlineSuggestion>() {
        @Override
        public InlineSuggestion[] newArray(int size) {
            return new InlineSuggestion[size];
        }

        @Override
        public InlineSuggestion createFromParcel(@NonNull Parcel in) {
            return new InlineSuggestion(in);
        }
    };

    @DataClass.Generated(
            time = 1585180783541L,
            codegenVersion = "1.0.15",
            sourceFile = "frameworks/base/core/java/android/view/inputmethod/InlineSuggestion.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.annotation.NonNull android.view.inputmethod.InlineSuggestionInfo mInfo\nprivate final @android.annotation.Nullable com.android.internal.view.inline.IInlineContentProvider mContentProvider\nprivate @com.android.internal.util.DataClass.ParcelWith(android.view.inputmethod.InlineSuggestion.InlineContentCallbackImplParceling.class) @android.annotation.Nullable android.view.inputmethod.InlineSuggestion.InlineContentCallbackImpl mInlineContentCallback\npublic static @android.annotation.TestApi @android.annotation.NonNull android.view.inputmethod.InlineSuggestion newInlineSuggestion(android.view.inputmethod.InlineSuggestionInfo)\npublic  void inflate(android.content.Context,android.util.Size,java.util.concurrent.Executor,java.util.function.Consumer<android.widget.inline.InlineContentView>)\nprivate synchronized  android.view.inputmethod.InlineSuggestion.InlineContentCallbackImpl getInlineContentCallback(android.content.Context,java.util.concurrent.Executor,java.util.function.Consumer<android.widget.inline.InlineContentView>)\nclass InlineSuggestion extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genEqualsHashCode=true, genToString=true, genHiddenConstDefs=true, genHiddenConstructor=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
