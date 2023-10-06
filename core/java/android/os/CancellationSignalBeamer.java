/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.system.SystemCleaner;
import android.util.Pair;
import android.view.inputmethod.CancellableHandwritingGesture;
import android.view.inputmethod.HandwritingGesture;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * A transport for {@link CancellationSignal}, but unlike
 * {@link CancellationSignal#createTransport()} doesn't require pre-creating the transport in the
 * target process. Instead, cancellation is forwarded over the same IPC surface as the cancellable
 * request.
 *
 * <p><strong>Important:</strong> For this to work, the following invariants must be held up:
 * <ul>
 *     <li>A call to beam() <strong>MUST</strong> result in a call to close() on the result
 *     (otherwise, the token will be leaked and cancellation isn't propagated), and that call
 *     must happen after the call using the
 *     token is sent (otherwise, any concurrent cancellation may be lost). It is strongly
 *     recommended to use try-with-resources on the token.
 *     <li>The cancel(), forget() and cancellable operations transporting the token must either
 *     all be oneway on the same binder, or all be non-oneway to guarantee proper ordering.
 *     <li>A {@link CancellationSignal} <strong>SHOULD</strong> be used only once, as there
 *     can only be a single {@link android.os.CancellationSignal.OnCancelListener OnCancelListener}.
 *
 * </ul>
 * <p>Caveats:
 * <ul>
 *     <li>Cancellation is only ever dispatched after the token is closed, and thus after the
 *     call performing the cancellable operation (if the invariants are followed). The operation
 *     must therefore not block the incoming binder thread, or cancellation won't be possible.
 *     <li>Consequently, in the unlikely event that the sender dies right after beaming an already
 *     cancelled {@link CancellationSignal}, the cancellation may be lost (unlike with
 *     {@link CancellationSignal#createTransport()}).
 *     <li>The forwarding OnCancelListener is set in the implied finally phase of try-with-resources
 *         / when closing the token. If the receiver is in the same process, and the signal is
 *         already cancelled, this may invoke the target's OnCancelListener during that phase.
 * </ul>
 *
 *
 * <p>Usage:
 * <pre>
 *  // Sender:
 *
 *  class FooManager {
 *    var mCancellationSignalSender = new CancellationSignalBeamer.Sender() {
 *      &#064;Override
 *      public void onCancel(IBinder token) { remoteIFooService.onCancelToken(token); }
 *
 *      &#064;Override
 *      public void onForget(IBinder token) { remoteIFooService.onForgetToken(token); }
 *    };
 *
 *    public void doCancellableOperation(..., CancellationSignal cs) {
 *      try (var csToken = mCancellationSignalSender.beam(cs)) {
 *          remoteIFooService.doCancellableOperation(..., csToken);
 *      }
 *    }
 *  }
 *
 *  // Receiver:
 *
 *  class FooManagerService extends IFooService.Stub {
 *    var mCancellationSignalReceiver = new CancellationSignalBeamer.Receiver();
 *
 *    &#064;Override
 *    public void doCancellableOperation(..., IBinder csToken) {
 *      CancellationSignal cs = mCancellationSignalReceiver.unbeam(csToken))
 *      // ...
 *    }
 *
 *    &#064;Override
 *    public void onCancelToken(..., IBinder csToken) {
 *      mCancellationSignalReceiver.cancelToken(csToken))
 *    }
 *
 *    &#064;Override
 *    public void onForgetToken(..., IBinder csToken) {
 *      mCancellationSignalReceiver.forgetToken(csToken))
 *    }
 *  }
 *
 * </pre>
 *
 * @hide
 */
public class CancellationSignalBeamer {

    static final Cleaner sCleaner = SystemCleaner.cleaner();

    /** The sending side of an {@link CancellationSignalBeamer} */
    public abstract static class Sender {

        /**
         * Beams a {@link CancellationSignal} through an existing Binder interface.
         *
         * @param cs the {@code CancellationSignal} to beam, or {@code null}.
         * @return an {@link IBinder} token. MUST be {@link CloseableToken#close}d <em>after</em>
         *         the binder call transporting it to the remote process, best with
         *         try-with-resources. {@code null} if {@code cs} was {@code null}.
         */
        // TODO(b/254888024): @MustBeClosed
        @Nullable
        public CloseableToken beam(@Nullable CancellationSignal cs) {
            if (cs == null) {
                return null;
            }
            return new Token(this, cs);
        }

        /**
         * A {@link #beam}ed {@link CancellationSignal} was closed.
         *
         * MUST be forwarded to {@link Receiver#cancel} with proper ordering. See
         * {@link CancellationSignalBeamer} for details.
         */
        public abstract void onCancel(@NonNull IBinder token);

        /**
         * A {@link #beam}ed {@link CancellationSignal} was GC'd.
         *
         * MUST be forwarded to {@link Receiver#forget} with proper ordering. See
         * {@link CancellationSignalBeamer} for details.
         */
        public abstract void onForget(@NonNull IBinder token);

        private static final ThreadLocal<Pair<Sender, ArrayList<CloseableToken>>> sScope =
                new ThreadLocal<>();

        /**
         * Beams a {@link CancellationSignal} through an existing Binder interface.
         * @param gesture {@link HandwritingGesture} that supports
         *  {@link CancellableHandwritingGesture cancellation} requesting cancellation token.
         * @return {@link IBinder} token. MUST be {@link MustClose#close}d <em>after</em>
         *  the binder call transporting it to the remote process, best with
         *  try-with-resources. {@code null} if {@code cs} was {@code null} or if
         *  {@link HandwritingGesture} isn't {@link CancellableHandwritingGesture cancellable}.
         */
        @NonNull
        public MustClose beamScopeIfNeeded(@NonNull HandwritingGesture gesture) {
            if (!(gesture instanceof CancellableHandwritingGesture)) {
                return null;
            }
            sScope.set(Pair.create(this, new ArrayList<>()));
            return () -> {
                var tokens = sScope.get().second;
                sScope.remove();
                for (int i = tokens.size() - 1; i >= 0; i--) {
                    if (tokens.get(i) != null) {
                        tokens.get(i).close();
                    }
                }
            };
        }

        /**
         * An {@link AutoCloseable} interface with {@link AutoCloseable#close()} callback.
         */
        public interface MustClose extends AutoCloseable {
            @Override
            void close();
        }

        /**
         * Beams a {@link CancellationSignal} token from existing scope created by previous call to
         * {@link #beamScopeIfNeeded()}
         * @param cs {@link CancellationSignal} for which token should be returned.
         * @return {@link IBinder} token.
         */
        @NonNull
        public static IBinder beamFromScope(@NonNull CancellationSignal cs) {
            var state = sScope.get();
            if (state != null) {
                var token = state.first.beam(cs);
                state.second.add(token);
                return token;
            }
            return null;
        }

        private static class Token extends Binder implements CloseableToken, Runnable {

            private final Sender mSender;
            private Preparer mPreparer;

            private Token(Sender sender, CancellationSignal signal) {
                mSender = sender;
                mPreparer = new Preparer(sender, signal, this);
            }

            @Override
            public void close() {
                Preparer preparer = mPreparer;
                mPreparer = null;
                if (preparer != null) {
                    preparer.setup();
                }
            }

            @Override
            public void run() {
                mSender.onForget(this);
            }

            private static class Preparer implements CancellationSignal.OnCancelListener {
                private final Sender mSender;
                private final CancellationSignal mSignal;
                private final Token mToken;

                private Preparer(Sender sender, CancellationSignal signal, Token token) {
                    mSender = sender;
                    mSignal = signal;
                    mToken = token;
                }

                void setup() {
                    sCleaner.register(this, mToken);
                    mSignal.setOnCancelListener(this);
                }

                @Override
                public void onCancel() {
                    try {
                        mSender.onCancel(mToken);
                    } finally {
                        // Make sure we dispatch onCancel before the cleaner can run.
                        Reference.reachabilityFence(this);
                    }
                }
            }
        }

        /**
         * A {@link #beam}ed {@link CancellationSignal} ready for sending over Binder.
         *
         * MUST be closed <em>after</em> it is sent over binder, ideally through try-with-resources.
         */
        public interface CloseableToken extends IBinder, MustClose {
            @Override
            void close(); // No throws
        }
    }

    /** The receiving side of a {@link CancellationSignalBeamer}. */
    public static class Receiver implements IBinder.DeathRecipient {
        private final HashMap<IBinder, CancellationSignal> mTokenMap = new HashMap<>();
        private final boolean mCancelOnSenderDeath;

        /**
         * Constructs a new {@code Receiver}.
         *
         * @param cancelOnSenderDeath if true, {@link CancellationSignal}s obtained from
         *  {@link #unbeam} are automatically {@link #cancel}led if the sender token
         *  {@link Binder#linkToDeath dies}; otherwise they are simnply dropped. Note: if the
         *  sending process drops all references to the {@link CancellationSignal} before
         *  process death, the cancellation is not guaranteed.
         */
        public Receiver(boolean cancelOnSenderDeath) {
            mCancelOnSenderDeath = cancelOnSenderDeath;
        }

        /**
         * Unbeams a token that was obtained via {@link Sender#beam} and turns it back into a
         * {@link CancellationSignal}.
         *
         * A subsequent call to {@link #cancel} with the same token will cancel the returned
         * {@code CancellationSignal}.
         *
         * @param token a token that was obtained from {@link Sender}, possibly in a remote process.
         * @return a {@link CancellationSignal} linked to the given token.
         */
        @Nullable
        @SuppressLint("VisiblySynchronized")
        public CancellationSignal unbeam(@Nullable IBinder token) {
            if (token == null) {
                return null;
            }
            synchronized (this) {
                CancellationSignal cs = mTokenMap.get(token);
                if (cs != null) {
                    return cs;
                }

                cs = new CancellationSignal();
                mTokenMap.put(token, cs);
                try {
                    token.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    dead(token);
                }
                return cs;
            }
        }

        /**
         * Forgets state associated with the given token (if any).
         *
         * Subsequent calls to {@link #cancel} or binder death notifications on the token will not
         * have any effect.
         *
         * This MUST be invoked when forwarding {@link Sender#onForget}, otherwise the token and
         * {@link CancellationSignal} will leak if the token was ever {@link #unbeam}ed.
         *
         * Optionally, the receiving service logic may also invoke this if it can guarantee that
         * the unbeamed CancellationSignal isn't needed anymore (i.e. the cancellable operation
         * using the CancellationSignal has been fully completed).
         *
         * @param token the token to forget. No-op if {@code null}.
         */
        @SuppressLint("VisiblySynchronized")
        public void forget(@Nullable IBinder token) {
            synchronized (this) {
                if (mTokenMap.remove(token) != null) {
                    token.unlinkToDeath(this, 0);
                }
            }
        }

        /**
         * Cancels the {@link CancellationSignal} associated with the given token (if any).
         *
         * This MUST be invoked when forwarding {@link Sender#onCancel}, otherwise the token and
         * {@link CancellationSignal} will leak if the token was ever {@link #unbeam}ed.
         *
         * Optionally, the receiving service logic may also invoke this if it can guarantee that
         * the unbeamed CancellationSignal isn't needed anymore (i.e. the cancellable operation
         * using the CancellationSignal has been fully completed).
         *
         * @param token the token to forget. No-op if {@code null}.
         */
        @SuppressLint("VisiblySynchronized")
        public void cancel(@Nullable IBinder token) {
            CancellationSignal cs;
            synchronized (this) {
                cs = mTokenMap.get(token);
                if (cs != null) {
                    forget(token);
                } else {
                    return;
                }
            }
            cs.cancel();
        }

        private void dead(@NonNull IBinder token) {
            if (mCancelOnSenderDeath) {
                cancel(token);
            } else {
                forget(token);
            }
        }

        @Override
        public void binderDied(@NonNull IBinder who) {
            dead(who);
        }

        @Override
        public void binderDied() {
            throw new RuntimeException("unreachable");
        }
    }
}
