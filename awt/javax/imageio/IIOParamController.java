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
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

package javax.imageio;

/* 
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

/**
 * The IIOParamController specifies an activate method that invokes the
 * controller.
 * 
 * @since Android 1.0
 */
public interface IIOParamController {

    /**
     * Activates the controller.
     * 
     * @param param
     *            the IIOParam.
     * @return true, if the IIOParam has been modified, false otherwise.
     */
    boolean activate(IIOParam param);
}
