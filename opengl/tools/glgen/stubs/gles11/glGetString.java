    // C function const GLubyte * glGetString ( GLenum name )

    public native String _glGetString(
        int name
    );

    public String glGetString(
        int name
    ) {
        String returnValue;
        returnValue = _glGetString(
            name
        );
        return returnValue;
    }

