/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt.image.renderable;

import java.awt.image.RenderedImage;
import java.io.Serializable;
import java.util.Vector;

/**
 * The class ParameterBlock groups an indexed set of parameter data with a set
 * of renderable (source) images. The mapping between the indexed parameters and
 * their property names is provided by a {@link ContextualRenderedImageFactory}.
 * 
 * @since Android 1.0
 */
public class ParameterBlock implements Cloneable, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -7577115551785240750L;

    /**
     * The sources (renderable images).
     */
    protected Vector<Object> sources = new Vector<Object>();

    /**
     * The parameters.
     */
    protected Vector<Object> parameters = new Vector<Object>();

    /**
     * Instantiates a new parameter block.
     * 
     * @param sources
     *            the vector of source images.
     * @param parameters
     *            the vector of parameters.
     */
    public ParameterBlock(Vector<Object> sources, Vector<Object> parameters) {
        setSources(sources);
        setParameters(parameters);
    }

    /**
     * Instantiates a new parameter block with no parameters.
     * 
     * @param sources
     *            the vector of source images.
     */
    public ParameterBlock(Vector<Object> sources) {
        setSources(sources);
    }

    /**
     * Instantiates a new parameter block with no image or parameter vectors.
     */
    public ParameterBlock() {
    }

    /**
     * Sets the source image at the specified index.
     * 
     * @param source
     *            the source image.
     * @param index
     *            the index where the source will be placed.
     * @return this parameter block.
     */
    public ParameterBlock setSource(Object source, int index) {
        if (sources.size() < index + 1) {
            sources.setSize(index + 1);
        }
        sources.setElementAt(source, index);
        return this;
    }

    /**
     * Sets the parameter value object at the specified index.
     * 
     * @param obj
     *            the parameter value to place at the desired index.
     * @param index
     *            the index where the object is to be placed in the vector of
     *            parameters.
     * @return this parameter block.
     */
    public ParameterBlock set(Object obj, int index) {
        if (parameters.size() < index + 1) {
            parameters.setSize(index + 1);
        }
        parameters.setElementAt(obj, index);
        return this;
    }

    /**
     * Adds a source to the vector of sources.
     * 
     * @param source
     *            the source to add.
     * @return this parameter block.
     */
    public ParameterBlock addSource(Object source) {
        sources.addElement(source);
        return this;
    }

    /**
     * Adds the object to the vector of parameter values
     * 
     * @param obj
     *            the obj to add.
     * @return this parameter block.
     */
    public ParameterBlock add(Object obj) {
        parameters.addElement(obj);
        return this;
    }

    /**
     * Sets the vector of sources, replacing the existing vector of sources, if
     * any.
     * 
     * @param sources
     *            the new sources.
     */
    public void setSources(Vector<Object> sources) {
        this.sources = sources;
    }

    /**
     * Sets the vector of parameters, replacing the existing vector of
     * parameters, if any.
     * 
     * @param parameters
     *            the new parameters.
     */
    public void setParameters(Vector<Object> parameters) {
        this.parameters = parameters;
    }

    /**
     * Gets the vector of sources.
     * 
     * @return the sources.
     */
    public Vector<Object> getSources() {
        return sources;
    }

    /**
     * Gets the vector of parameters.
     * 
     * @return the parameters.
     */
    public Vector<Object> getParameters() {
        return parameters;
    }

    /**
     * Gets the source at the specified index.
     * 
     * @param index
     *            the index.
     * @return the source object found at the specified index.
     */
    public Object getSource(int index) {
        return sources.elementAt(index);
    }

    /**
     * Gets the object parameter found at the specified index.
     * 
     * @param index
     *            the index.
     * @return the parameter object found at the specified index.
     */
    public Object getObjectParameter(int index) {
        return parameters.elementAt(index);
    }

    /**
     * Shallow clone (clones using the superclass clone method).
     * 
     * @return the clone of this object.
     */
    public Object shallowClone() {
        try {
            return super.clone();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns a copy of this ParameterBlock instance.
     * 
     * @return the identical copy of this instance.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        ParameterBlock replica;
        try {
            replica = (ParameterBlock)super.clone();
        } catch (Exception e) {
            return null;
        }
        if (sources != null) {
            replica.setSources((Vector<Object>)(sources.clone()));
        }
        if (parameters != null) {
            replica.setParameters((Vector<Object>)(parameters.clone()));
        }
        return replica;
    }

    /**
     * Gets an array of classes corresponding to all of the parameter values
     * found in the array of parameters, in order.
     * 
     * @return the parameter classes.
     */
    public Class[] getParamClasses() {
        int count = parameters.size();
        Class paramClasses[] = new Class[count];

        for (int i = 0; i < count; i++) {
            paramClasses[i] = parameters.elementAt(i).getClass();
        }
        return paramClasses;
    }

    /**
     * Gets the renderable source image found at the specified index in the
     * source array.
     * 
     * @param index
     *            the index.
     * @return the renderable source image.
     */
    public RenderableImage getRenderableSource(int index) {
        return (RenderableImage)sources.elementAt(index);
    }

    /**
     * Wraps the short value in a Short and places it in the parameter block at
     * the specified index.
     * 
     * @param s
     *            the short value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(short s, int index) {
        return set(new Short(s), index);
    }

    /**
     * Wraps the short value in a Short and adds it to the parameter block.
     * 
     * @param s
     *            the short value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(short s) {
        return add(new Short(s));
    }

    /**
     * Wraps the long value in a Long and places it in the parameter block at
     * the specified index.
     * 
     * @param l
     *            the long value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(long l, int index) {
        return set(new Long(l), index);
    }

    /**
     * Wraps the long value in a Long and adds it to the parameter block.
     * 
     * @param l
     *            the long value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(long l) {
        return add(new Long(l));
    }

    /**
     * Wraps the integer value in an Integer and places it in the parameter
     * block at the specified index.
     * 
     * @param i
     *            the integer value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(int i, int index) {
        return set(new Integer(i), index);
    }

    /**
     * Wraps the integer value in an Integer and adds it to the parameter block.
     * 
     * @param i
     *            the integer value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(int i) {
        return add(new Integer(i));
    }

    /**
     * Wraps the float value in a Float and places it in the parameter block at
     * the specified index.
     * 
     * @param f
     *            the float value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(float f, int index) {
        return set(new Float(f), index);
    }

    /**
     * Wraps the float value in a Float and adds it to the parameter block.
     * 
     * @param f
     *            the float value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(float f) {
        return add(new Float(f));
    }

    /**
     * Wraps the double value in a Double and places it in the parameter block
     * at the specified index.
     * 
     * @param d
     *            the double value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(double d, int index) {
        return set(new Double(d), index);
    }

    /**
     * Wraps the double value in a Double and adds it to the parameter block.
     * 
     * @param d
     *            the double value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(double d) {
        return add(new Double(d));
    }

    /**
     * Wraps the char value in a Character and places it in the parameter block
     * at the specified index.
     * 
     * @param c
     *            the char value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(char c, int index) {
        return set(new Character(c), index);
    }

    /**
     * Wraps the char value in a Character and adds it to the parameter block.
     * 
     * @param c
     *            the char value of the parameter.
     * @return this parameter block.
     */
    public ParameterBlock add(char c) {
        return add(new Character(c));
    }

    /**
     * Wraps the byte value in a Byte and places it in the parameter block at
     * the specified index.
     * 
     * @param b
     *            the byte value of the parameter.
     * @param index
     *            the index.
     * @return this parameter block.
     */
    public ParameterBlock set(byte b, int index) {
        return set(new Byte(b), index);
    }

    /**
     * Wraps the byte value in a Byte and adds it to the parameter block.
     * 
     * @param b
     *            the byte value of the parameter.
     * @return the parameter block.
     */
    public ParameterBlock add(byte b) {
        return add(new Byte(b));
    }

    /**
     * Gets the RenderedImage at the specified index from the vector of source
     * images.
     * 
     * @param index
     *            the index.
     * @return the rendered image.
     */
    public RenderedImage getRenderedSource(int index) {
        return (RenderedImage)sources.elementAt(index);
    }

    /**
     * Gets the short-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the short parameter.
     */
    public short getShortParameter(int index) {
        return ((Short)parameters.elementAt(index)).shortValue();
    }

    /**
     * Gets the long-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the long parameter.
     */
    public long getLongParameter(int index) {
        return ((Long)parameters.elementAt(index)).longValue();
    }

    /**
     * Gets the integer-valued parameter found at the desired index in the
     * vector of parameter values.
     * 
     * @param index
     *            the index.
     * @return the integer parameter.
     */
    public int getIntParameter(int index) {
        return ((Integer)parameters.elementAt(index)).intValue();
    }

    /**
     * Gets the float-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the float parameter.
     */
    public float getFloatParameter(int index) {
        return ((Float)parameters.elementAt(index)).floatValue();
    }

    /**
     * Gets the double-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the double parameter.
     */
    public double getDoubleParameter(int index) {
        return ((Double)parameters.elementAt(index)).doubleValue();
    }

    /**
     * Gets the char-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the char parameter.
     */
    public char getCharParameter(int index) {
        return ((Character)parameters.elementAt(index)).charValue();
    }

    /**
     * Gets the byte-valued parameter found at the desired index in the vector
     * of parameter values.
     * 
     * @param index
     *            the index.
     * @return the byte parameter.
     */
    public byte getByteParameter(int index) {
        return ((Byte)parameters.elementAt(index)).byteValue();
    }

    /**
     * Clears the vector of sources.
     */
    public void removeSources() {
        sources.removeAllElements();
    }

    /**
     * Clears the vector of parameters.
     */
    public void removeParameters() {
        parameters.removeAllElements();
    }

    /**
     * Gets the number of elements in the vector of sources.
     * 
     * @return the number of elements in the vector of sources.
     */
    public int getNumSources() {
        return sources.size();
    }

    /**
     * Gets the number of elements in the vector of parameters.
     * 
     * @return the number of elements in the vector of parameters.
     */
    public int getNumParameters() {
        return parameters.size();
    }
}
