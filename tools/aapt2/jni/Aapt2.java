package com.android.tools.aapt2;

import java.util.List;

/**
 * {@code aapt2} JNI interface. To use the {@code aapt2} native interface, the
 * shared library must first be loaded and then a new instance of this class can
 * be used to access the library.
 */
public class Aapt2 {

  /**
   * Invokes {@code aapt2} to perform resource compilation.
   *
   * @param arguments arguments for compilation (see {@code Compile.cpp})
   */
  public static void compile(List<String> arguments) {
    nativeCompile(arguments);
  }

  /**
   * Invokes {@code aapt2} to perform linking.
   *
   * @param arguments arguments for linking (see {@code Link.cpp})
   */
  public static void link(List<String> arguments) {
    nativeLink(arguments);
  }

  /**
   * JNI call.
   *
   * @param arguments arguments for compilation (see {@code Compile.cpp})
   */
  private static native void nativeCompile(List<String> arguments);

  /**
   * JNI call.
   *
   * @param arguments arguments for linking (see {@code Link.cpp})
   */
  private static native void nativeLink(List<String> arguments);
}

