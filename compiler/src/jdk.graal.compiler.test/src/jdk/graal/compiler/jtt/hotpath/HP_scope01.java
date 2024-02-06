/*
 * Copyright (c) 2007, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
// Checkstyle: stop
package jdk.graal.compiler.jtt.hotpath;

import jdk.graal.compiler.jtt.JTTTest;
import org.junit.Ignore;
import org.junit.Test;

/*
 */
public class HP_scope01 extends JTTTest {

    public static int test(int count) {
        int sum = 0;

        for (int k = 0; k < count; k++) {
            {
                int i = 1;
                sum += i;
            }
            {
                float f = 3;
                sum += (int) f;
            }
            {
                long l = 7;
                sum += (int) l;
            }
            {
                double d = 11;
                sum += (int) d;
            }
        }

        for (int k = 0; k < count; k++) {
            if (k < 20) {
                int i = 1;
                sum += i;
            } else {
                float f = 3;
                sum += (int) f;
            }
        }

        for (int k = 0; k < count; k++) {
            int i = 3;
            for (int j = 0; j < count; j++) {
                float f = 7;
                sum += (int) (i + f);
            }
        }

        for (int k = 0; k < count; k++) {
            for (int j = 0; j < count; j++) {
                float f = 7;
                sum += (int) (j + f);
            }
            int i = 3;
            sum += i;
        }

        return sum;
    }

    @Ignore
    @Test
    public void run0() throws Throwable {
        runTest("test", 40);
    }

}