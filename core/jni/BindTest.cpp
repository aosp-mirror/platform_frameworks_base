/* //device/libs/android_runtime/BindTest.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdio.h>
#include <stdlib.h>
#include <jam-public.h>

static u4 offset_instanceString;
static FieldBlock *fb_classString = NULL;
static Class *class_ReturnedObject = NULL;
static MethodBlock *mb_ReturnedObject_setReturnedString = NULL;
static MethodBlock *mb_Java_Lang_Object_Equals = NULL;

static u4 offset_mObj;
static u4 offset_mBool;
static u4 offset_mInt;
static u4 offset_mString;
static u4 offset_mDouble;
static u4 offset_mLong;


/* native String getString(); */
static uintptr_t *
getString(Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    RETURN_OBJ (createString ("String"));
}

/* native String getNullString(); */
static uintptr_t *
getNullString(Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    RETURN_OBJ (createString (NULL));
}

/* native String getBooleanTrue(); */
static uintptr_t *
getBooleanTrue(Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    RETURN_BOOLEAN (TRUE);
}

/* native String getBooleanFalse(); */
static uintptr_t *
getBooleanFalse(Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    RETURN_BOOLEAN (FALSE);
}

/* native Object nonvoidThrowsException() */
static uintptr_t *
nonvoidThrowsException (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    if (1) {
        signalException("java/lang/NullPointerException", NULL);
        goto exception;
    } 
    
    RETURN_OBJ (NULL);
exception:
    RETURN_VOID;
}

/* native void setInstanceString(String s); */
static uintptr_t *
setInstanceString (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    Object *jthis = (Object *) ostack[0];
    
    JOBJ_set_obj(jthis, offset_instanceString, ostack[1]);

    RETURN_VOID;
}

/* native void setClassString(String s) */
static uintptr_t *
setClassString (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
//    Object *jthis = (Object *) ostack[0];

    fb_classString->static_value = ostack[1];

    RETURN_VOID;
}

/* native String makeStringFromThreeChars(char a, char b, char c); */
static uintptr_t *
makeStringFromThreeChars (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    // Object *jthis =  ostack[0];
    char a = (char) ostack[1];
    char b = (char) ostack[2];
    char c = (char) ostack[3];

    char str[4];

    str[0] = a;
    str[1] = b;
    str[2] = c;
    str[3] = 0;
    
    RETURN_OBJ(createString(str));
}

/* native ReturnedObject makeReturnedObject(String a); */
static uintptr_t *
makeReturnedObject (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    //Object *jthis = (Object*)ostack[0];
    Object *a = (Object*)ostack[1];

    Object *ret;

    ret = allocObject(class_ReturnedObject);

    executeMethod(ret, mb_ReturnedObject_setReturnedString, a);
    
    RETURN_OBJ (ret);
}

/* native double addDoubles(double a, double b); */
static uintptr_t *
addDoubles (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    //Object *jthis = (Object*)ostack[0];        
    double a = JARG_get_double(1);
    double b = JARG_get_double(3);

    RETURN_DOUBLE(a+b);
}

/* native void setAll (Object obj, boolean bool, int i, String str, double d, long l) */
static uintptr_t *
setAll  (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    Object *jthis = JARG_get_obj(0);

    Object *obj     = JARG_get_obj(1);
    bool b          = JARG_get_bool(2);
    int i           = JARG_get_int(3);
    char *str       = JARG_get_cstr_strdup(4);
    double d        = JARG_get_double(5);
    long long ll    = JARG_get_long_long(5+2);

    JOBJ_set_obj(jthis, offset_mObj, obj);
    JOBJ_set_bool(jthis, offset_mBool, b);
    JOBJ_set_int(jthis, offset_mInt, i);
    JOBJ_set_cstr(jthis, offset_mString, str);
    free(str);
    str = NULL;
    JOBJ_set_double(jthis, offset_mDouble, d);
    JOBJ_set_long_long(jthis, offset_mLong, ll);

    RETURN_VOID;
}

/* native void compareAll (Object obj, boolean bool, int i, String str, double d, long l) */
static uintptr_t *
compareAll  (Class *clazz, MethodBlock *mb, uintptr_t *ostack)
{
    Object *jthis = JARG_get_obj(0);

    Object *obj     = JARG_get_obj(1);
    bool b          = JARG_get_bool(2);
    int i           = JARG_get_int(3);
    Object *strObj  = JARG_get_obj(4);
    double d        = JARG_get_double(5);
    long long ll    = JARG_get_long_long(5+2);

    bool ret;

    void *result;

    Object *mStringObj = JOBJ_get_obj(jthis, offset_mString);

    char *s = JARG_get_cstr_strdup(4);
    
    result = executeMethod (strObj, lookupVirtualMethod(strObj,mb_Java_Lang_Object_Equals), 
                JOBJ_get_obj(jthis, offset_mString));

    if (exceptionOccurred()) {
        RETURN_VOID;
    }

    ret =   (*(uintptr_t *)result != 0)
            && (obj == JOBJ_get_obj(jthis, offset_mObj))
            && (b == JOBJ_get_bool(jthis, offset_mBool))
            && (i == JOBJ_get_int(jthis, offset_mInt))
            && (d == JOBJ_get_double(jthis, offset_mDouble))
            && (ll == JOBJ_get_long_long(jthis, offset_mLong));


    RETURN_BOOLEAN(ret);
}

static VMMethod methods[] = {
    {"getString", getString},
    {"getNullString", getNullString},
    {"getBooleanTrue", getBooleanTrue},
    {"getBooleanFalse", getBooleanFalse},
    {"nonvoidThrowsException", nonvoidThrowsException},
    {"setInstanceString",     setInstanceString},
    {"setClassString",     setClassString},
    {"makeStringFromThreeChars", makeStringFromThreeChars}, 
    {"makeReturnedObject", makeReturnedObject},
    {"addDoubles", addDoubles},
    {"setAll", setAll},
    {"compareAll", compareAll},
    {NULL, NULL}
};


void register_BindTest()
{
    jamvm_registerClass("BindTest", methods);

    Class *clazz = NULL;

    clazz = findClassFromClassLoader("BindTest", getSystemClassLoader());

    if (clazz == NULL) {
        fprintf(stderr, "Error: BindTest not found\n");
	clearException();
        return;
    }
    
    FieldBlock *fb;

    fb = findField(clazz, "instanceString", "Ljava/lang/String;");

    if (fb == NULL || ((fb->access_flags & ACC_STATIC) == ACC_STATIC)) {
        fprintf(stderr, "Error: BindTest.instanceString not found or error\n");        
        return;
    }  

    offset_instanceString = fb->offset;

    fb_classString = findField(clazz, "classString", "Ljava/lang/String;");

    if (fb_classString == NULL || ((fb_classString->access_flags & ACC_STATIC) != ACC_STATIC)) {
        fprintf(stderr, "Error: BindTest.classString not found or error\n");        
        return;
    }  


    class_ReturnedObject = findClassFromClassLoader("ReturnedObject", getSystemClassLoader());

    if (class_ReturnedObject == NULL) {
        fprintf(stderr, "Error: ReturnedObject class not found or error\n");        
        return;
    }
    
    mb_ReturnedObject_setReturnedString=
           findMethod (class_ReturnedObject, "setReturnedString", "(Ljava/lang/String;)V");

    if (mb_ReturnedObject_setReturnedString == NULL) {
        fprintf(stderr, "Error: ReturnedObject.setReturnedString class not found or error\n");        
        return;
    }

    offset_mObj = findField(clazz, "mObj", "Ljava/lang/Object;")->offset;
    offset_mBool = findField(clazz, "mBool", "Z" )->offset;
    offset_mInt = findField(clazz, "mInt", "I")->offset;
    offset_mString = findField(clazz, "mString", "Ljava/lang/String;")->offset;
    offset_mDouble = findField(clazz, "mDouble", "D")->offset;
    offset_mLong = findField(clazz, "mLong", "J")->offset;


    mb_Java_Lang_Object_Equals = findMethod (
                                    findClassFromClassLoader("java/lang/Object", getSystemClassLoader()),
                                    "equals", "(Ljava/lang/Object;)Z");

}


