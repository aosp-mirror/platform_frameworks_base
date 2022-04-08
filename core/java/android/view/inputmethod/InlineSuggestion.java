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
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Size;
import android.util.Slog;
import android.view.SurfaceControlViewHost;
import android.view.ViewGroup;
import android.widget.inline.InlineContentView;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class represents an inline suggestion which is made by one app and can be embedded into the
 * UI of another. Suggestions may contain sensitive information not known to the host app which
 * needs to be protected from spoofing. To address that the suggestion view inflated on demand for
 * embedding is created in such a way that the hosting app cannot introspect its content and cannot
 * interact with it.
 */
@DataClass(genEqualsHashCode = true, genToString = true, genHiddenConstDefs = true,
        genHiddenConstructor = true)
public final class InlineSuggestion implements Parcelable {

    private static final String TAG = "InlineSuggestion";

    @NonNull
    private final InlineSuggestionInfo mInfo;

    /**
     * @hide
     */
    @Nullable
    private final IInlineContentProvider mContentProvider;

    /**
     * Used to keep a strong reference to the callback so it doesn't get garbage collected.
     *
     * @hide
     */
    @DataClass.ParcelWith(InlineContentCallbackImplParceling.class)
    @Nullable
    private InlineContentCallbackImpl mInlineContentCallback;

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
    public InlineSuggestion(@NonNull InlineSuggestionInfo info,
            @Nullable IInlineContentProvider contentProvider) {
        this(info, contentProvider, /* inlineContentCallback */ null);
    }

    /**
     * Inflates a view with the content of this suggestion at a specific size.
     *
     * <p> Each dimension of the size must satisfy one of the following conditions:
     *
     * <ol>
     *     <li>between {@link android.widget.inline.InlinePresentationSpec#getMinSize()} and
     * {@link android.widget.inline.InlinePresentationSpec#getMaxSize()} of the presentation spec
     * from {@code mInfo}
     *     <li>{@link ViewGroup.LayoutParams#WRAP_CONTENT}
     * </ol>
     *
     * If the size is set to {@link
     * ViewGroup.LayoutParams#WRAP_CONTENT}, then the size of the inflated view will be just large
     * enough to fit the content, while still conforming to the min / max size specified by the
     * {@link android.widget.inline.InlinePresentationSpec}.
     *
     * <p> The caller can attach an {@link android.view.View.OnClickListener} and/or an
     * {@link android.view.View.OnLongClickListener} to the view in the {@code callback} to receive
     * click and long click events on the view.
     *
     * @param context  Context in which to inflate the view.
     * @param size     The size at which to inflate the suggestion. For each dimension, it maybe an
     *                 exact value or {@link ViewGroup.LayoutParams#WRAP_CONTENT}.
     * @param callback Callback for receiving the inflated view, where the {@link
     *                 ViewGroup.LayoutParams} of the view is set as the actual size of the
     *                 underlying remote view.
     * @throws IllegalArgumentException If an invalid argument is passed.
     * @throws IllegalStateException    If this method is already called.
     */
    public void inflate(@NonNull Context context, @NonNull Size size,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<InlineContentView> callback) {
        final Size minSize = mInfo.getInlinePresentationSpec().getMinSize();
        final Size maxSize = mInfo.getInlinePresentationSpec().getMaxSize();
        if (!isValid(size.getWidth(), minSize.getWidth(), maxSize.getWidth())
                || !isValid(size.getHeight(), minSize.getHeight(), maxSize.getHeight())) {
            throw new IllegalArgumentException(
                    "size is neither between min:" + minSize + " and max:" + maxSize
                            + ", nor wrap_content");
        }
        mInlineContentCallback = getInlineContentCallback(context, callbackExecutor, callback);
        if (mContentProvider == null) {
            callbackExecutor.execute(() -> callback.accept(/* view */ null));
            return;
        }
        try {
            mContentProvider.provideContent(size.getWidth(), size.getHeight(),
                    new InlineContentCallbackWrapper(mInlineContentCallback));
        } catch (RemoteException e) {
            Slog.w(TAG, "Error creating suggestion content surface: " + e);
            callbackExecutor.execute(() -> callback.accept(/* view */ null));
        }
    }

    /**
     * Returns true if the {@code actual} length is within [min, max] or is {@link
     * ViewGroup.LayoutParams#WRAP_CONTENT}.
     */
    private static boolean isValid(int actual, int min, int max) {
        if (actual == ViewGroup.LayoutParams.WRAP_CONTENT) {
            return true;
        }
        return actual >= min && actual <= max;
    }

    private synchronized InlineContentCallbackImpl getInlineContentCallback(Context context,
            Executor callbackExecutor, Consumer<InlineContentView> callback) {
        if (mInlineContentCallback != null) {
            throw new IllegalStateException("Already called #inflate()");
        }
        return new InlineContentCallbackImpl(context, mContentProvider, callbackExecutor,
                callback);
    }

    /**
     * A wrapper class around the {@link InlineContentCallbackImpl} to ensure it's not strongly
     * reference by the remote system server process.
     */
    private static final class InlineContentCallbackWrapper extends IInlineContentCallback.Stub {

        private final WeakReference<InlineContentCallbackImpl> mCallbackImpl;

        InlineContentCallbackWrapper(InlineContentCallbackImpl callbackImpl) {
            mCallbackImpl = new WeakReference<>(callbackImpl);
        }

        @Override
        @BinderThread
        public void onContent(SurfaceControlViewHost.SurfacePackage content, int width,
                int height) {
            final InlineContentCallbackImpl callbackImpl = mCallbackImpl.get();
            if (callbackImpl != null) {
                callbackImpl.onContent(content, width, height);
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

    /**
     * Handles the communication between the inline suggestion view in current (IME) process and
     * the remote view provided from the system server.
     *
     * <p>This class is thread safe, because all the outside calls are piped into a single
     * handler thread to be processed.
     */
    private static final class InlineContentCallbackImpl {

        @NonNull
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());

        @NonNull
        private final Context mContext;
        @Nullable
        private final IInlineContentProvider mInlineContentProvider;
        @NonNull
        private final Executor mCallbackExecutor;

        /**
         * Callback from the client (IME) that will receive the inflated suggestion view. It'll
         * only be called once when the view SurfacePackage is first sent back to the client. Any
         * updates to the view due to attach to window and detach from window events will be
         * handled under the hood, transparent from the client.
         */
        @NonNull
        private final Consumer<InlineContentView> mCallback;

        /**
         * Indicates whether the first content has been received or not.
         */
        private boolean mFirstContentReceived = false;

        /**
         * The client (IME) side view which internally wraps a remote view. It'll be set when
         * {@link #onContent(SurfaceControlViewHost.SurfacePackage, int, int)} is called, which
         * should only happen once in the lifecycle of this inline suggestion instance.
         */
        @Nullable
        private InlineContentView mView;

        /**
         * The SurfacePackage pointing to the remote view. It's cached here to be sent to the next
         * available consumer.
         */
        @Nullable
        private SurfaceControlViewHost.SurfacePackage mSurfacePackage;

        /**
         * The callback (from the {@link InlineContentView}) which consumes the surface package.
         * It's cached here to be called when the SurfacePackage is returned from the remote
         * view owning process.
         */
        @Nullable
        private Consumer<SurfaceControlViewHost.SurfacePackage> mSurfacePackageConsumer;

        InlineContentCallbackImpl(@NonNull Context context,
                @Nullable IInlineContentProvider inlineContentProvider,
                @NonNull @CallbackExecutor Executor callbackExecutor,
                @NonNull Consumer<InlineContentView> callback) {
            mContext = context;
            mInlineContentProvider = inlineContentProvider;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }

        @BinderThread
        public void onContent(SurfaceControlViewHost.SurfacePackage content, int width,
                int height) {
            mMainHandler.post(() -> handleOnContent(content, width, height));
        }

        @MainThread
        private void handleOnContent(SurfaceControlViewHost.SurfacePackage content, int width,
                int height) {
            if (!mFirstContentReceived) {
                handleOnFirstContentReceived(content, width, height);
                mFirstContentReceived = true;
            } else {
                handleOnSurfacePackage(content);
            }
        }

        /**
         * Called when the view content is returned for the first time.
         */
        @MainThread
        private void handleOnFirstContentReceived(SurfaceControlViewHost.SurfacePackage content,
                int width, int height) {
            mSurfacePackage = content;
            if (mSurfacePackage == null) {
                mCallbackExecutor.execute(() -> mCallback.accept(/* view */null));
            } else {
                mView = new InlineContentView(mContext);
                mView.setLayoutParams(new ViewGroup.LayoutParams(width, height));
                mView.setChildSurfacePackageUpdater(getSurfacePackageUpdater());
                mCallbackExecutor.execute(() -> mCallback.accept(mView));
            }
        }

        /**
         * Called when any subsequent SurfacePackage is returned from the remote view owning
         * process.
         */
        @MainThread
        private void handleOnSurfacePackage(SurfaceControlViewHost.SurfacePackage surfacePackage) {
            if (surfacePackage == null) {
                return;
            }
            if (mSurfacePackage != null || mSurfacePackageConsumer == null) {
                // The surface package is not consumed, release it immediately.
                surfacePackage.release();
                try {
                    mInlineContentProvider.onSurfacePackageReleased();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error calling onSurfacePackageReleased(): " + e);
                }
                return;
            }
            mSurfacePackage = surfacePackage;
            if (mSurfacePackage == null) {
                return;
            }
            if (mSurfacePackageConsumer != null) {
                mSurfacePackageConsumer.accept(mSurfacePackage);
                mSurfacePackageConsumer = null;
            }
        }

        @MainThread
        private void handleOnSurfacePackageReleased() {
            if (mSurfacePackage != null) {
                try {
                    mInlineContentProvider.onSurfacePackageReleased();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error calling onSurfacePackageReleased(): " + e);
                }
                mSurfacePackage = null;
            }
            // Clear the pending surface package consumer, if any. This can happen if the IME
            // attaches the view to window and then quickly detaches it from the window, before
            // the surface package requested upon attaching to window was returned.
            mSurfacePackageConsumer = null;
        }

        @MainThread
        private void handleGetSurfacePackage(
                Consumer<SurfaceControlViewHost.SurfacePackage> consumer) {
            if (mSurfacePackage != null) {
                consumer.accept(mSurfacePackage);
            } else {
                mSurfacePackageConsumer = consumer;
                try {
                    mInlineContentProvider.requestSurfacePackage();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error calling getSurfacePackage(): " + e);
                    consumer.accept(null);
                    mSurfacePackageConsumer = null;
                }
            }
        }

        private InlineContentView.SurfacePackageUpdater getSurfacePackageUpdater() {
            return new InlineContentView.SurfacePackageUpdater() {
                @Override
                public void onSurfacePackageReleased() {
                    mMainHandler.post(
                            () -> InlineContentCallbackImpl.this.handleOnSurfacePackageReleased());
                }

                @Override
                public void getSurfacePackage(
                        Consumer<SurfaceControlViewHost.SurfacePackage> consumer) {
                    mMainHandler.post(
                            () -> InlineContentCallbackImpl.this.handleGetSurfacePackage(consumer));
                }
            };
        }

        @BinderThread
        public void onClick() {
            mMainHandler.post(() -> {
                if (mView != null && mView.hasOnClickListeners()) {
                    mView.callOnClick();
                }
            });
        }

        @BinderThread
        public void onLongClick() {
            mMainHandler.post(() -> {
                if (mView != null && mView.hasOnLongClickListeners()) {
                    mView.performLongClick();
                }
            });
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
     * @hide
     */
    @DataClass.Generated.Member
    public @Nullable IInlineContentProvider getContentProvider() {
        return mContentProvider;
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
            time = 1589396017700L,
            codegenVersion = "1.0.15",
            sourceFile = "frameworks/base/core/java/android/view/inputmethod/InlineSuggestion.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.annotation.NonNull android.view.inputmethod.InlineSuggestionInfo mInfo\nprivate final @android.annotation.Nullable com.android.internal.view.inline.IInlineContentProvider mContentProvider\nprivate @com.android.internal.util.DataClass.ParcelWith(android.view.inputmethod.InlineSuggestion.InlineContentCallbackImplParceling.class) @android.annotation.Nullable android.view.inputmethod.InlineSuggestion.InlineContentCallbackImpl mInlineContentCallback\npublic static @android.annotation.TestApi @android.annotation.NonNull android.view.inputmethod.InlineSuggestion newInlineSuggestion(android.view.inputmethod.InlineSuggestionInfo)\npublic  void inflate(android.content.Context,android.util.Size,java.util.concurrent.Executor,java.util.function.Consumer<android.widget.inline.InlineContentView>)\nprivate static  boolean isValid(int,int,int)\nprivate synchronized  android.view.inputmethod.InlineSuggestion.InlineContentCallbackImpl getInlineContentCallback(android.content.Context,java.util.concurrent.Executor,java.util.function.Consumer<android.widget.inline.InlineContentView>)\nclass InlineSuggestion extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genEqualsHashCode=true, genToString=true, genHiddenConstDefs=true, genHiddenConstructor=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
