/*
** Types and resources
*/
typedef int EGLBoolean;
typedef long EGLint;
typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef void *EGLSurface;
typedef void *EGLContext;
typedef void *NativeDisplayType;
typedef void *NativeWindowType;
typedef void *NativePixmapType;

/*
** EGL and native handle values
*/
#define EGL_DEFAULT_DISPLAY ((NativeDisplayType)0)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_NO_SURFACE ((EGLSurface)0)
