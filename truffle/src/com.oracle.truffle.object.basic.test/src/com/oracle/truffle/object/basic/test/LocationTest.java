/*
 * Copyright (c) 2016, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.object.basic.test;

import static com.oracle.truffle.object.basic.test.DOTestAsserts.getLocationType;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Location;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.test.AbstractParametrizedLibraryTest;
import com.oracle.truffle.object.ShapeImpl;

@SuppressWarnings("deprecation")
@RunWith(Parameterized.class)
public class LocationTest extends AbstractParametrizedLibraryTest {

    @Parameter(1) public boolean useLookup;

    @Parameters(name = "{0},{1}")
    public static List<Object[]> data() {
        return Arrays.stream(TestRun.values()).flatMap(run -> List.of(Boolean.FALSE, Boolean.TRUE).stream().map(lookup -> new Object[]{run, lookup})).toList();
    }

    Shape rootShape;

    @Before
    public void setup() {
        rootShape = makeRootShape();
    }

    private Shape makeRootShape() {
        if (useLookup) {
            return Shape.newBuilder().layout(TestDynamicObjectDefault.class, MethodHandles.lookup()).build();
        } else {
            return Shape.newBuilder().layout(TestDynamicObjectDefault.class).build();
        }
    }

    private DynamicObject newInstance() {
        return new TestDynamicObjectDefault(rootShape);
    }

    @Test
    public void testOnlyObjectLocationForObject() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "obj", new Object());
        Location location = object.getShape().getProperty("obj").getLocation();
        DOTestAsserts.assertObjectLocation(location);
        DOTestAsserts.assertLocationFields(location, 0, 1);
        DOTestAsserts.assertShapeFields(object, 0, 1);
    }

    @Test
    public void testOnlyPrimLocationForPrimitive() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "prim", 42);
        Location location = object.getShape().getProperty("prim").getLocation();
        Assert.assertEquals(int.class, getLocationType(location));
        DOTestAsserts.assertLocationFields(location, 1, 0);
        DOTestAsserts.assertShapeFields(object, 1, 0);
    }

    @Test
    public void testPrim2Object() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "foo", 42);
        Location location1 = object.getShape().getProperty("foo").getLocation();
        Assert.assertEquals(int.class, getLocationType(location1));
        DOTestAsserts.assertLocationFields(location1, 1, 0);
        DOTestAsserts.assertShapeFields(object, 1, 0);

        library.putIfPresent(object, "foo", new Object());
        Location location2 = object.getShape().getProperty("foo").getLocation();
        Assert.assertEquals(Object.class, getLocationType(location2));
        DOTestAsserts.assertLocationFields(location2, 0, 1);
        DOTestAsserts.assertShapeFields(object, 1, 1);
    }

    @Test
    public void testUnrelatedPrimitivesGoToObject() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "foo", 42L);
        Location location1 = object.getShape().getProperty("foo").getLocation();
        Assert.assertEquals(long.class, getLocationType(location1));
        DOTestAsserts.assertLocationFields(location1, 1, 0);
        DOTestAsserts.assertShapeFields(object, 1, 0);

        library.putIfPresent(object, "foo", 3.14);
        Location location2 = object.getShape().getProperty("foo").getLocation();
        Assert.assertEquals(Object.class, getLocationType(location2));
        DOTestAsserts.assertLocationFields(location2, 0, 1);
        DOTestAsserts.assertShapeFields(object, 1, 1);
    }

    @Test
    public void testChangeFlagsReuseLocation() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "foo", 42);
        Location location = object.getShape().getProperty("foo").getLocation();

        library.putWithFlags(object, "foo", 43, 111);
        Assert.assertEquals(43, library.getOrDefault(object, "foo", null));
        Property newProperty = object.getShape().getProperty("foo");
        Assert.assertEquals(111, newProperty.getFlags());
        Location newLocation = newProperty.getLocation();
        Assert.assertSame(location, newLocation);
    }

    @Test
    public void testChangeFlagsChangeLocation() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "foo", 42);
        Location location = object.getShape().getProperty("foo").getLocation();

        library.putWithFlags(object, "foo", "str", 111);
        Assert.assertEquals("str", library.getOrDefault(object, "foo", null));
        Property newProperty = object.getShape().getProperty("foo");
        Assert.assertEquals(111, newProperty.getFlags());
        Location newLocation = newProperty.getLocation();
        Assert.assertNotSame(location, newLocation);
    }

    @Test
    public void testDelete() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.put(object, "a", 1);
        library.put(object, "b", 2);
        library.removeKey(object, "a");
        Assert.assertFalse(library.containsKey(object, "a"));
        Assert.assertTrue(library.containsKey(object, "b"));
        Assert.assertEquals(2, library.getOrDefault(object, "b", null));
        library.put(object, "a", 3);
        library.removeKey(object, "b");
        Assert.assertEquals(3, library.getOrDefault(object, "a", null));
    }

    @Test
    public void testLocationDecoratorEquals() {
        var allocator = ((ShapeImpl) rootShape).allocator();
        Location intLocation1 = allocator.locationForType(int.class);
        Location intLocation2 = allocator.locationForType(int.class);
        Assert.assertEquals(intLocation1.getClass(), intLocation2.getClass());
        Assert.assertNotEquals(intLocation1, intLocation2);
    }

    @Test
    public void testDeleteDeclaredProperty() {
        DynamicObject object = newInstance();

        DynamicObjectLibrary library = createLibrary(DynamicObjectLibrary.class, object);

        library.putConstant(object, "a", new Object(), 0);
        Assert.assertTrue(library.containsKey(object, "a"));
        library.put(object, "a", 42);
        Assert.assertEquals(1, object.getShape().getPropertyCount());
        library.removeKey(object, "a");
        Assert.assertFalse(library.containsKey(object, "a"));
    }

    @Test
    public void testLocationIsPrimitive() {
        var allocator = ((ShapeImpl) rootShape).allocator();

        Location objectLocation = allocator.locationForType(Object.class);
        Assert.assertFalse(objectLocation.isPrimitive());

        Location intLocation = allocator.locationForType(int.class);
        Assert.assertTrue(intLocation.isPrimitive());
        Location doubleLocation = allocator.locationForType(double.class);
        Assert.assertTrue(doubleLocation.isPrimitive());
        Location longLocation = allocator.locationForType(long.class);
        Assert.assertTrue(longLocation.isPrimitive());

        Location constantLocation = allocator.constantLocation("constantValue");
        Assert.assertFalse(constantLocation.isPrimitive());
    }
}
