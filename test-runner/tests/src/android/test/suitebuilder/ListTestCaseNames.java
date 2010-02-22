/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test.suitebuilder;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListTestCaseNames {
    public static List<String> getTestCaseNames(TestSuite suite) {
        // TODO: deprecate this method and move all callers to use getTestNames
        List<Test> tests = Collections.<Test>list(suite.tests());
        ArrayList<String> testCaseNames = new ArrayList<String>();
        for (Test test : tests) {
            if (test instanceof TestCase) {
                testCaseNames.add(((TestCase) test).getName());
            } else if (test instanceof TestSuite) {
                testCaseNames.addAll(getTestCaseNames((TestSuite) test));
            }
        }
        return testCaseNames;
    }
    
    /** 
     * Returns a list of test class and method names for each TestCase in suite.  
     */
    public static List<TestDescriptor> getTestNames(TestSuite suite) {
        List<Test> tests = Collections.<Test>list(suite.tests());
        ArrayList<TestDescriptor> testNames = new ArrayList<TestDescriptor>();
        for (Test test : tests) {
            if (test instanceof TestCase) {
                String className = test.getClass().getName();
                String testName = ((TestCase) test).getName();
                testNames.add(new TestDescriptor(className, testName));
            } else if (test instanceof TestSuite) {
                testNames.addAll(getTestNames((TestSuite) test));
            }
        }
        return testNames;
    }
    
    /**
     * Data holder for test case info
     */
    public static class TestDescriptor {
       private String mClassName;
       private String mTestName;
      
       public TestDescriptor(String className, String testName) {
           mClassName = className;
           mTestName = testName;
       }
       
       public String getClassName() {
           return mClassName;
       }
       
       public String getTestName() {
           return mTestName;
       }
       
       /**
        * Override parent to do string-based class and test name comparison
        */
       @Override
       public boolean equals(Object otherObj) {
           if (otherObj instanceof TestDescriptor) {
               TestDescriptor otherDesc = (TestDescriptor)otherObj;
               return otherDesc.getClassName().equals(this.getClassName()) && 
                      otherDesc.getTestName().equals(this.getTestName());
               
           }
           return false;
       }
       
       /**
        * Override parent to return a more user-friendly display string
        */
       @Override
       public String toString() {
           return getClassName() + "#" + getTestName();
       }
    }
}
