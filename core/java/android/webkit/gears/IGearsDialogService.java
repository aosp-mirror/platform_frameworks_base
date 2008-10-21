/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: android.webkit.gears/IGearsDialogService.aidl
 */
package android.webkit.gears;
import java.lang.String;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Binder;
import android.os.Parcel;
public interface IGearsDialogService extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements android.webkit.gears.IGearsDialogService
{
private static final java.lang.String DESCRIPTOR = "com.android.browser.IGearsDialogService";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an IGearsDialogService interface,
 * generating a proxy if needed.
 */
public static android.webkit.gears.IGearsDialogService asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.webkit.gears.IGearsDialogService in = (android.webkit.gears.IGearsDialogService)obj.queryLocalInterface(DESCRIPTOR);
if ((in!=null)) {
return in;
}
return new android.webkit.gears.IGearsDialogService.Stub.Proxy(obj);
}
public android.os.IBinder asBinder()
{
return this;
}
public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_showDialog:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
java.lang.String _arg1;
_arg1 = data.readString();
boolean _arg2;
_arg2 = (0!=data.readInt());
java.lang.String _result = this.showDialog(_arg0, _arg1, _arg2);
reply.writeNoException();
reply.writeString(_result);
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements android.webkit.gears.IGearsDialogService
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
public java.lang.String showDialog(java.lang.String htmlContent, java.lang.String dialogArguments, boolean inSettings) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
java.lang.String _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(htmlContent);
_data.writeString(dialogArguments);
_data.writeInt(((inSettings)?(1):(0)));
mRemote.transact(Stub.TRANSACTION_showDialog, _data, _reply, 0);
_reply.readException();
_result = _reply.readString();
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
}
static final int TRANSACTION_showDialog = (IBinder.FIRST_CALL_TRANSACTION + 0);
}
public java.lang.String showDialog(java.lang.String htmlContent, java.lang.String dialogArguments, boolean inSettings) throws android.os.RemoteException;
}
