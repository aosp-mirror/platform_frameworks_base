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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.util.List;

/**
 * TODO(phanna): Complete documentation.
 * {@hide} while developing
 */
public abstract class UrlRendererService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.urlrenderer.UrlRendererService";

    static final String TAG = "UrlRendererService";

    private static class InternalCallback implements UrlRenderer.Callback {
        private final IUrlRendererCallback mCallback;
        InternalCallback(IUrlRendererCallback cb) {
            mCallback = cb;
        }

        public void complete(String url, ParcelFileDescriptor result) {
            try {
                mCallback.complete(url, result);
            } catch (RemoteException ex) {
            }
        }
    }

    private final IUrlRendererService.Stub mBinderInterface =
            new IUrlRendererService.Stub() {
                public void render(List<String> urls, int width, int height,
                        IUrlRendererCallback cb) {
                    processRequest(urls, width, height,
                            new InternalCallback(cb));
                }
            };

    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final android.os.IBinder onBind(android.content.Intent intent) {
        return mBinderInterface;
    }

    /**
     * When all clients unbind from the service, stop the service.  Subclasses
     * should not override.
     */
    @Override
    public final boolean onUnbind(android.content.Intent intent) {
        stopSelf();
        return false;
    }

    /**
     * Subclasses implement this function to process the given urls.  When each
     * url is complete, the subclass must invoke the callback with the result.
     * @param urls  A list of urls to render at the given dimensions.
     * @param width  The desired width of the result.
     * @param height  The desired height of the result.
     * @param cb  The callback to invoke when each url is complete.
     */
    public abstract void processRequest(List<String> urls, int width,
            int height, UrlRenderer.Callback cb);
}
