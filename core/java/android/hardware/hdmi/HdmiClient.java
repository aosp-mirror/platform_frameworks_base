package android.hardware.hdmi;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiControlManager.VendorCommandListener;
import android.os.RemoteException;
import android.util.Log;

/**
 * Parent for classes of various HDMI-CEC device type used to access
 * the HDMI control system service. Contains methods and data used in common.
 *
 * @hide
 */
@SystemApi
public abstract class HdmiClient {
    private static final String TAG = "HdmiClient";

    /* package */ final IHdmiControlService mService;

    private IHdmiVendorCommandListener mIHdmiVendorCommandListener;

    /* package */ abstract int getDeviceType();

    /* package */ HdmiClient(IHdmiControlService service) {
        mService = service;
    }

    /**
     * Callback interface used to get the result of {@link #selectDevice}.
     */
    public interface SelectDeviceCallback {
        /**
         * Called when the operation is finished.
         * @param result the result value of {@link #selectDevice} and can have the values mentioned
         *               in {@link HdmiControlShellCommand#getResultString}
         * @param logicalAddress logical address of the selected device
         */
        void onComplete(@HdmiControlManager.ControlCallbackResult int result, int logicalAddress);
    }

    /**
     * Selects a CEC logical device to be a new active source.
     *
     * <p> Multiple calls to this method are handled in parallel and independently, with no
     * guarantees about the execution order. The caller receives a callback for each call,
     * containing the result of that call only.
     *
     * @param logicalAddress logical address of the device to select
     * @param callback callback to get the result with
     * @throws {@link IllegalArgumentException} if the {@code callback} is null
     */
    public void selectDevice(int logicalAddress, @NonNull SelectDeviceCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null.");
        }
        try {
            mService.deviceSelect(logicalAddress, getCallbackWrapper(callback, logicalAddress));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select device: ", e);
        }
    }

    /**
     * @hide
     */
    private static IHdmiControlCallback getCallbackWrapper(final SelectDeviceCallback callback,
            int logicalAddress) {
        return new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                callback.onComplete(result, logicalAddress);
            }
        };
    }

    /**
     * Returns the active source information.
     *
     * @return {@link HdmiDeviceInfo} object that describes the active source
     *         or active routing path
     */
    public HdmiDeviceInfo getActiveSource() {
        try {
            return mService.getActiveSource();
        } catch (RemoteException e) {
            Log.e(TAG, "getActiveSource threw exception ", e);
        }
        return null;
    }

    /**
     * Sends a key event to other logical device.
     *
     * @param keyCode key code to send. Defined in {@link android.view.KeyEvent}.
     * @param isPressed true if this is key press event
     */
    public void sendKeyEvent(int keyCode, boolean isPressed) {
        try {
            mService.sendKeyEvent(getDeviceType(), keyCode, isPressed);
        } catch (RemoteException e) {
            Log.e(TAG, "sendKeyEvent threw exception ", e);
        }
    }

    /**
     * Sends a volume key event to the primary audio receiver in the system. This method should only
     * be called when the volume key is not handled by the local device. HDMI framework handles the
     * logic of finding the address of the receiver.
     *
     * @param keyCode key code to send. Defined in {@link android.view.KeyEvent}.
     * @param isPressed true if this is key press event
     *
     * @hide
     */
    public void sendVolumeKeyEvent(int keyCode, boolean isPressed) {
        try {
            mService.sendVolumeKeyEvent(getDeviceType(), keyCode, isPressed);
        } catch (RemoteException e) {
            Log.e(TAG, "sendVolumeKeyEvent threw exception ", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends vendor-specific command.
     *
     * @param targetAddress address of the target device
     * @param params vendor-specific parameter. For &lt;Vendor Command With ID&gt; do not
     *               include the first 3 bytes (vendor ID).
     * @param hasVendorId {@code true} if the command type will be &lt;Vendor Command With ID&gt;.
     *                    {@code false} if the command will be &lt;Vendor Command&gt;
     */
    public void sendVendorCommand(int targetAddress,
            @SuppressLint("MissingNullability") byte[] params, boolean hasVendorId) {
        try {
            mService.sendVendorCommand(getDeviceType(), targetAddress, params, hasVendorId);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to send vendor command: ", e);
        }
    }

    /**
     * Sets a listener used to receive incoming vendor-specific command.
     *
     * @param listener listener object
     */
    public void setVendorCommandListener(@NonNull VendorCommandListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (mIHdmiVendorCommandListener != null) {
            throw new IllegalStateException("listener was already set");
        }
        try {
            IHdmiVendorCommandListener wrappedListener = getListenerWrapper(listener);
            mService.addVendorCommandListener(wrappedListener, getDeviceType());
            mIHdmiVendorCommandListener = wrappedListener;
        } catch (RemoteException e) {
            Log.e(TAG, "failed to set vendor command listener: ", e);
        }
    }

    private static IHdmiVendorCommandListener getListenerWrapper(
            final VendorCommandListener listener) {
        return new IHdmiVendorCommandListener.Stub() {
            @Override
            public void onReceived(int srcAddress, int destAddress, byte[] params,
                    boolean hasVendorId) {
                listener.onReceived(srcAddress, destAddress, params, hasVendorId);
            }
            @Override
            public void onControlStateChanged(boolean enabled, int reason) {
                listener.onControlStateChanged(enabled, reason);
            }
        };
    }
}
