/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.service.urlrenderer;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.util.List;

/**
 * TODO(phanna): Document this class.
 * {@hide} while developing
 */
public final class UrlRenderer {
    /**
     * Interface for clients to receive the result of calls to
     * {@link UrlRenderer#render}.
     * {@hide} while developing
     */
    public interface Callback {
        /**
         * Calls to {@link render} will result in multiple invokations of this
         * method for each url.  A null result means that there was a server
         * error or a problem rendering the url.
         * @param url  The url that has been rendered.
         * @param result  A ParcelFileDescriptor containing the encoded image
         *                data. The client is responsible for closing the stream
         *                to free resources.  A null result indicates a failure
         *                to render.
         */
        public void complete(String url, ParcelFileDescriptor result);
    }

    private IUrlRendererService mService;

    /**
     * Create a new UrlRenderer to remotely render urls.
     * @param service  An IBinder service usually obtained through
     *                 {@link ServiceConnection#onServiceConnected}
     */
    public UrlRenderer(IBinder service) {
        mService = IUrlRendererService.Stub.asInterface(service);
    }

    private static class InternalCallback extends IUrlRendererCallback.Stub {
        private final Callback mCallback;
        InternalCallback(Callback cb) {
            mCallback = cb;
        }

        public void complete(String url, ParcelFileDescriptor result) {
            mCallback.complete(url, result);
        }
    }

    /**
     * Render the list of <var>urls</var> and invoke the <var>callback</var>
     * for each result.
     * @param urls  A List of urls to render.
     * @param width  The desired width of the result.
     * @param height  The desired height of the result.
     * @param callback  An instance of {@link Callback} invoked for each url.
     */
    public void render(List<String> urls, int width, int height,
            Callback callback) {
        if (mService != null) {
            try {
                mService.render(urls, width, height,
                        new InternalCallback(callback));
            } catch (RemoteException ex) {
            }
        }
    }
}
