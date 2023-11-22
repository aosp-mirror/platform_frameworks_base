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

package com.google.android.lint.aidl

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class EnforcePermissionHelperDetectorCodegenTest : LintDetectorTest() {
    override fun getDetector(): Detector = EnforcePermissionDetector()

    override fun getIssues(): List<Issue> = listOf(
            EnforcePermissionDetector.ISSUE_ENFORCE_PERMISSION_HELPER,
            EnforcePermissionDetector.ISSUE_MISUSING_ENFORCE_PERMISSION
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun test_generated_IProtected() {
        lint().testModes(TestMode.DEFAULT).files(
            java(
                """
                /*
                 * This file is auto-generated.  DO NOT MODIFY.
                 */
                package android.aidl.tests.permission;
                public interface IProtected extends android.os.IInterface
                {
                  /** Default implementation for IProtected. */
                  public static class Default implements android.aidl.tests.permission.IProtected
                  {
                    @Override public void PermissionProtected() throws android.os.RemoteException
                    {
                    }
                    @Override public void MultiplePermissionsAll() throws android.os.RemoteException
                    {
                    }
                    @Override public void MultiplePermissionsAny() throws android.os.RemoteException
                    {
                    }
                    @Override public void NonManifestPermission() throws android.os.RemoteException
                    {
                    }
                    // Used by the integration tests to dynamically set permissions that are considered granted.
                    @Override public void SetGranted(java.util.List<java.lang.String> permissions) throws android.os.RemoteException
                    {
                    }
                    @Override
                    public android.os.IBinder asBinder() {
                      return null;
                    }
                  }
                  /** Local-side IPC implementation stub class. */
                  public static abstract class Stub extends android.os.Binder implements android.aidl.tests.permission.IProtected
                  {
                    private final android.os.PermissionEnforcer mEnforcer;
                    /** Construct the stub using the Enforcer provided. */
                    public Stub(android.os.PermissionEnforcer enforcer)
                    {
                      this.attachInterface(this, DESCRIPTOR);
                      if (enforcer == null) {
                        throw new IllegalArgumentException("enforcer cannot be null");
                      }
                      mEnforcer = enforcer;
                    }
                    @Deprecated
                    /** Default constructor. */
                    public Stub() {
                      this(android.os.PermissionEnforcer.fromContext(
                         android.app.ActivityThread.currentActivityThread().getSystemContext()));
                    }
                    /**
                     * Cast an IBinder object into an android.aidl.tests.permission.IProtected interface,
                     * generating a proxy if needed.
                     */
                    public static android.aidl.tests.permission.IProtected asInterface(android.os.IBinder obj)
                    {
                      if ((obj==null)) {
                        return null;
                      }
                      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
                      if (((iin!=null)&&(iin instanceof android.aidl.tests.permission.IProtected))) {
                        return ((android.aidl.tests.permission.IProtected)iin);
                      }
                      return new android.aidl.tests.permission.IProtected.Stub.Proxy(obj);
                    }
                    @Override public android.os.IBinder asBinder()
                    {
                      return this;
                    }
                    /** @hide */
                    public static java.lang.String getDefaultTransactionName(int transactionCode)
                    {
                      switch (transactionCode)
                      {
                        case TRANSACTION_PermissionProtected:
                        {
                          return "PermissionProtected";
                        }
                        case TRANSACTION_MultiplePermissionsAll:
                        {
                          return "MultiplePermissionsAll";
                        }
                        case TRANSACTION_MultiplePermissionsAny:
                        {
                          return "MultiplePermissionsAny";
                        }
                        case TRANSACTION_NonManifestPermission:
                        {
                          return "NonManifestPermission";
                        }
                        case TRANSACTION_SetGranted:
                        {
                          return "SetGranted";
                        }
                        default:
                        {
                          return null;
                        }
                      }
                    }
                    /** @hide */
                    public java.lang.String getTransactionName(int transactionCode)
                    {
                      return this.getDefaultTransactionName(transactionCode);
                    }
                    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
                    {
                      java.lang.String descriptor = DESCRIPTOR;
                      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
                        data.enforceInterface(descriptor);
                      }
                      switch (code)
                      {
                        case INTERFACE_TRANSACTION:
                        {
                          reply.writeString(descriptor);
                          return true;
                        }
                      }
                      switch (code)
                      {
                        case TRANSACTION_PermissionProtected:
                        {
                          this.PermissionProtected();
                          reply.writeNoException();
                          break;
                        }
                        case TRANSACTION_MultiplePermissionsAll:
                        {
                          this.MultiplePermissionsAll();
                          reply.writeNoException();
                          break;
                        }
                        case TRANSACTION_MultiplePermissionsAny:
                        {
                          this.MultiplePermissionsAny();
                          reply.writeNoException();
                          break;
                        }
                        case TRANSACTION_NonManifestPermission:
                        {
                          this.NonManifestPermission();
                          reply.writeNoException();
                          break;
                        }
                        case TRANSACTION_SetGranted:
                        {
                          java.util.List<java.lang.String> _arg0;
                          _arg0 = data.createStringArrayList();
                          data.enforceNoDataAvail();
                          this.SetGranted(_arg0);
                          reply.writeNoException();
                          break;
                        }
                        default:
                        {
                          return super.onTransact(code, data, reply, flags);
                        }
                      }
                      return true;
                    }
                    private static class Proxy implements android.aidl.tests.permission.IProtected
                    {
                      private android.os.IBinder mRemote;
                      Proxy(android.os.IBinder remote)
                      {
                        mRemote = remote;
                      }
                      @Override public android.os.IBinder asBinder()
                      {
                        return mRemote;
                      }
                      public java.lang.String getInterfaceDescriptor()
                      {
                        return DESCRIPTOR;
                      }
                      @Override public void PermissionProtected() throws android.os.RemoteException
                      {
                        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                        android.os.Parcel _reply = android.os.Parcel.obtain();
                        try {
                          _data.writeInterfaceToken(DESCRIPTOR);
                          boolean _status = mRemote.transact(Stub.TRANSACTION_PermissionProtected, _data, _reply, 0);
                          _reply.readException();
                        }
                        finally {
                          _reply.recycle();
                          _data.recycle();
                        }
                      }
                      @Override public void MultiplePermissionsAll() throws android.os.RemoteException
                      {
                        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                        android.os.Parcel _reply = android.os.Parcel.obtain();
                        try {
                          _data.writeInterfaceToken(DESCRIPTOR);
                          boolean _status = mRemote.transact(Stub.TRANSACTION_MultiplePermissionsAll, _data, _reply, 0);
                          _reply.readException();
                        }
                        finally {
                          _reply.recycle();
                          _data.recycle();
                        }
                      }
                      @Override public void MultiplePermissionsAny() throws android.os.RemoteException
                      {
                        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                        android.os.Parcel _reply = android.os.Parcel.obtain();
                        try {
                          _data.writeInterfaceToken(DESCRIPTOR);
                          boolean _status = mRemote.transact(Stub.TRANSACTION_MultiplePermissionsAny, _data, _reply, 0);
                          _reply.readException();
                        }
                        finally {
                          _reply.recycle();
                          _data.recycle();
                        }
                      }
                      @Override public void NonManifestPermission() throws android.os.RemoteException
                      {
                        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                        android.os.Parcel _reply = android.os.Parcel.obtain();
                        try {
                          _data.writeInterfaceToken(DESCRIPTOR);
                          boolean _status = mRemote.transact(Stub.TRANSACTION_NonManifestPermission, _data, _reply, 0);
                          _reply.readException();
                        }
                        finally {
                          _reply.recycle();
                          _data.recycle();
                        }
                      }
                      // Used by the integration tests to dynamically set permissions that are considered granted.
                      @Override public void SetGranted(java.util.List<java.lang.String> permissions) throws android.os.RemoteException
                      {
                        android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                        android.os.Parcel _reply = android.os.Parcel.obtain();
                        try {
                          _data.writeInterfaceToken(DESCRIPTOR);
                          _data.writeStringList(permissions);
                          boolean _status = mRemote.transact(Stub.TRANSACTION_SetGranted, _data, _reply, 0);
                          _reply.readException();
                        }
                        finally {
                          _reply.recycle();
                          _data.recycle();
                        }
                      }
                    }
                    static final int TRANSACTION_PermissionProtected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
                    /** Helper method to enforce permissions for PermissionProtected */
                    protected void PermissionProtected_enforcePermission() throws SecurityException {
                      android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                      mEnforcer.enforcePermission(android.Manifest.permission.READ_PHONE_STATE, source);
                    }
                    static final int TRANSACTION_MultiplePermissionsAll = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
                    /** Helper method to enforce permissions for MultiplePermissionsAll */
                    protected void MultiplePermissionsAll_enforcePermission() throws SecurityException {
                      android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                      mEnforcer.enforcePermissionAllOf(new String[]{android.Manifest.permission.INTERNET, android.Manifest.permission.VIBRATE}, source);
                    }
                    static final int TRANSACTION_MultiplePermissionsAny = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
                    /** Helper method to enforce permissions for MultiplePermissionsAny */
                    protected void MultiplePermissionsAny_enforcePermission() throws SecurityException {
                      android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                      mEnforcer.enforcePermissionAnyOf(new String[]{android.Manifest.permission.INTERNET, android.Manifest.permission.VIBRATE}, source);
                    }
                    static final int TRANSACTION_NonManifestPermission = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
                    /** Helper method to enforce permissions for NonManifestPermission */
                    protected void NonManifestPermission_enforcePermission() throws SecurityException {
                      android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                      mEnforcer.enforcePermission(android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, source);
                    }
                    static final int TRANSACTION_SetGranted = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
                    /** @hide */
                    public int getMaxTransactionId()
                    {
                      return 4;
                    }
                  }
                  
                  @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                  public void PermissionProtected() throws android.os.RemoteException;
                  @android.annotation.EnforcePermission(allOf = {android.Manifest.permission.INTERNET, android.Manifest.permission.VIBRATE})
                  public void MultiplePermissionsAll() throws android.os.RemoteException;
                  @android.annotation.EnforcePermission(anyOf = {android.Manifest.permission.INTERNET, android.Manifest.permission.VIBRATE})
                  public void MultiplePermissionsAny() throws android.os.RemoteException;
                  @android.annotation.EnforcePermission(android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
                  public void NonManifestPermission() throws android.os.RemoteException;
                  // Used by the integration tests to dynamically set permissions that are considered granted.
                  @android.annotation.RequiresNoPermission
                  public void SetGranted(java.util.List<java.lang.String> permissions) throws android.os.RemoteException;
                }
                """
            ).indented(),
                *stubs
        )
            .run()
            .expectClean()
    }

    fun test_generated_IProtectedInterface() {
        lint().files(
                java(
                    """
                    /*
                     * This file is auto-generated.  DO NOT MODIFY.
                     */
                    package android.aidl.tests.permission;
                    public interface IProtectedInterface extends android.os.IInterface
                    {
                      /** Default implementation for IProtectedInterface. */
                      public static class Default implements android.aidl.tests.permission.IProtectedInterface
                      {
                        @Override public void Method1() throws android.os.RemoteException
                        {
                        }
                        @Override public void Method2() throws android.os.RemoteException
                        {
                        }
                        @Override
                        public android.os.IBinder asBinder() {
                          return null;
                        }
                      }
                      /** Local-side IPC implementation stub class. */
                      public static abstract class Stub extends android.os.Binder implements android.aidl.tests.permission.IProtectedInterface
                      {
                        private final android.os.PermissionEnforcer mEnforcer;
                        /** Construct the stub using the Enforcer provided. */
                        public Stub(android.os.PermissionEnforcer enforcer)
                        {
                          this.attachInterface(this, DESCRIPTOR);
                          if (enforcer == null) {
                            throw new IllegalArgumentException("enforcer cannot be null");
                          }
                          mEnforcer = enforcer;
                        }
                        @Deprecated
                        /** Default constructor. */
                        public Stub() {
                          this(android.os.PermissionEnforcer.fromContext(
                             android.app.ActivityThread.currentActivityThread().getSystemContext()));
                        }
                        /**
                         * Cast an IBinder object into an android.aidl.tests.permission.IProtectedInterface interface,
                         * generating a proxy if needed.
                         */
                        public static android.aidl.tests.permission.IProtectedInterface asInterface(android.os.IBinder obj)
                        {
                          if ((obj==null)) {
                            return null;
                          }
                          android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
                          if (((iin!=null)&&(iin instanceof android.aidl.tests.permission.IProtectedInterface))) {
                            return ((android.aidl.tests.permission.IProtectedInterface)iin);
                          }
                          return new android.aidl.tests.permission.IProtectedInterface.Stub.Proxy(obj);
                        }
                        @Override public android.os.IBinder asBinder()
                        {
                          return this;
                        }
                        /** @hide */
                        public static java.lang.String getDefaultTransactionName(int transactionCode)
                        {
                          switch (transactionCode)
                          {
                            case TRANSACTION_Method1:
                            {
                              return "Method1";
                            }
                            case TRANSACTION_Method2:
                            {
                              return "Method2";
                            }
                            default:
                            {
                              return null;
                            }
                          }
                        }
                        /** @hide */
                        public java.lang.String getTransactionName(int transactionCode)
                        {
                          return this.getDefaultTransactionName(transactionCode);
                        }
                        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
                        {
                          java.lang.String descriptor = DESCRIPTOR;
                          if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
                            data.enforceInterface(descriptor);
                          }
                          switch (code)
                          {
                            case INTERFACE_TRANSACTION:
                            {
                              reply.writeString(descriptor);
                              return true;
                            }
                          }
                          switch (code)
                          {
                            case TRANSACTION_Method1:
                            {
                              this.Method1();
                              reply.writeNoException();
                              break;
                            }
                            case TRANSACTION_Method2:
                            {
                              this.Method2();
                              reply.writeNoException();
                              break;
                            }
                            default:
                            {
                              return super.onTransact(code, data, reply, flags);
                            }
                          }
                          return true;
                        }
                        private static class Proxy implements android.aidl.tests.permission.IProtectedInterface
                        {
                          private android.os.IBinder mRemote;
                          Proxy(android.os.IBinder remote)
                          {
                            mRemote = remote;
                          }
                          @Override public android.os.IBinder asBinder()
                          {
                            return mRemote;
                          }
                          public java.lang.String getInterfaceDescriptor()
                          {
                            return DESCRIPTOR;
                          }
                          @Override public void Method1() throws android.os.RemoteException
                          {
                            android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                            android.os.Parcel _reply = android.os.Parcel.obtain();
                            try {
                              _data.writeInterfaceToken(DESCRIPTOR);
                              boolean _status = mRemote.transact(Stub.TRANSACTION_Method1, _data, _reply, 0);
                              _reply.readException();
                            }
                            finally {
                              _reply.recycle();
                              _data.recycle();
                            }
                          }
                          @Override public void Method2() throws android.os.RemoteException
                          {
                            android.os.Parcel _data = android.os.Parcel.obtain(asBinder());
                            android.os.Parcel _reply = android.os.Parcel.obtain();
                            try {
                              _data.writeInterfaceToken(DESCRIPTOR);
                              boolean _status = mRemote.transact(Stub.TRANSACTION_Method2, _data, _reply, 0);
                              _reply.readException();
                            }
                            finally {
                              _reply.recycle();
                              _data.recycle();
                            }
                          }
                        }
                        static final int TRANSACTION_Method1 = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
                        /** Helper method to enforce permissions for Method1 */
                        protected void Method1_enforcePermission() throws SecurityException {
                          android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                          mEnforcer.enforcePermission(android.Manifest.permission.ACCESS_FINE_LOCATION, source);
                        }
                        static final int TRANSACTION_Method2 = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
                        /** Helper method to enforce permissions for Method2 */
                        protected void Method2_enforcePermission() throws SecurityException {
                          android.content.AttributionSource source = new android.content.AttributionSource(getCallingUid(), null, null);
                          mEnforcer.enforcePermission(android.Manifest.permission.ACCESS_FINE_LOCATION, source);
                        }
                        /** @hide */
                        public int getMaxTransactionId()
                        {
                          return 1;
                        }
                      }
                      
                      @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                      public void Method1() throws android.os.RemoteException;
                      @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                      public void Method2() throws android.os.RemoteException;
                    }
                    """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    /* Stubs */

    private val manifestPermissionStub: TestFile = java(
        """
        package android.Manifest;
        class permission {
          public static final String READ_PHONE_STATE = "android.permission.READ_PHONE_STATE";
          public static final String INTERNET = "android.permission.INTERNET";
        }
        """
    ).indented()

    private val enforcePermissionAnnotationStub: TestFile = java(
        """
        package android.annotation;
        public @interface EnforcePermission {}
        """
    ).indented()

    private val stubs = arrayOf(manifestPermissionStub, enforcePermissionAnnotationStub)
}
