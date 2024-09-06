/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.Instruction.InstructionIterable;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Bind.DefaultExpression;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Represents the current bytecode for bytecode DSL root node. The bytecode node may be replaced
 * over time with newer versions, but do only limited internal mutation. Whenever the
 * {@link BytecodeConfig bytecode configuration} or the {@link BytecodeTier tier} changes a new
 * {@link BytecodeNode} is created and automatically replaced.
 * <p>
 * The {@link #getTier() tier} of a bytecode node initially always starts out as
 * {@link BytecodeTier#UNCACHED}. This means that no cached nodes were created yet. It takes number
 * {@link #setUncachedThreshold(int) uncached threshold} calls and back-edges for the node to
 * transition to the cached tier. BY default the uncached threshold is 16 if the
 * {@link GenerateBytecode#enableUncachedInterpreter() uncached generation} is enabled, and 0 if
 * not. The intention of the uncached bytecode tier is to reduce footprint overhead for root nodes
 * that are only executed infrequently.
 * <p>
 * Since the the number of bytecodes may change between bytecode nodes of a root node, a
 * bytecodeIndex returned by the the DSL is only valid for a single bytecode node, it is therefore
 * recommended to use {@link BytecodeLocation} for longer term storage.
 * <p>
 * The current bytecode node can be bound using <code>@Bind BytecodeNode bytecode</code> from
 * {@link Operation operations}. This class is not intended to be subclasses by clients, only by DSL
 * generated code.
 *
 * @see BytecodeLocation
 * @since 24.2
 */
@DefaultExpression("$bytecodeNode")
public abstract class BytecodeNode extends Node {

    /**
     * Internal constructor for generated code. Do not use.
     *
     * @since 24.2
     */
    protected BytecodeNode(Object token) {
        BytecodeRootNodes.checkToken(token);
    }

    /**
     * Returns the current bytecode location using the current frame and location.
     *
     * @param frame the current frame
     * @param location the current location
     * @return the bytecode location, or null if the frame and node does not originate from a
     *         bytecode DSL root node.
     * @since 24.2
     */
    public final BytecodeLocation getBytecodeLocation(Frame frame, Node location) {
        int bytecodeIndex = findBytecodeIndexImpl(frame, location);
        if (bytecodeIndex < -1) {
            return null;
        }
        return new BytecodeLocation(this, bytecodeIndex);
    }

    /**
     * Gets the bytecode location associated with a particular {@link FrameInstance frameInstance},
     * obtained from a stack walk.
     *
     * @param frameInstance the frame instance
     * @return the bytecode location, or null if the frame does not originate from a bytecode DSL
     *         root node.
     * @since 24.2
     */
    public final BytecodeLocation getBytecodeLocation(FrameInstance frameInstance) {
        int bytecodeIndex = findBytecodeIndex(frameInstance);
        if (bytecodeIndex == -1) {
            return null;
        }
        return new BytecodeLocation(this, bytecodeIndex);
    }

    /**
     * Gets the bytecode location associated with a bytecode index. The result is only valid if
     * bytecode index was obtained from this bytecode node using a bind variable $bytecodeIndex or
     * {@link #getBytecodeIndex}.
     *
     * @param bytecodeIndex the current bytecode index. A valid bytecode index can be obtained by
     *            calling {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation.
     * @throws IllegalArgumentException if an invalid bytecode index was passed. This check is
     *             performed only if assertions (-ea) are enabled for performance reasons.
     * @since 24.2
     */
    public final BytecodeLocation getBytecodeLocation(int bytecodeIndex) {
        assert validateBytecodeIndex(bytecodeIndex);
        return findLocation(bytecodeIndex);
    }

    /**
     * Reads and returns the bytecode index from the {@code frame}. This method should only be
     * called if the interpreter is configured to {@link GenerateBytecode#storeBytecodeIndexInFrame
     * store the bytecode index in the frame}; be sure to read the documentation before using this
     * feature.
     *
     * @return the bytecode index stored in the frame
     * @throws UnsupportedOperationException if the interpreter does not always store the bytecode
     *             index in the frame. See {@link GenerateBytecode#storeBytecodeIndexInFrame()}
     * @since 24.2
     */
    @SuppressWarnings("unused")
    public int getBytecodeIndex(Frame frame) {
        throw new UnsupportedOperationException("Interpreter does not store the bytecode index in the frame.");
    }

    /**
     * Gets the most concrete {@link SourceSection source location} associated with a particular
     * location. Returns {@code null} if the node was not parsed {@link BytecodeConfig#WITH_SOURCE
     * with sources} or if there is no associated source section for the given location. A location
     * must always be provided to get a source location otherwise <code>null</code> will be
     * returned.
     *
     * @param frame the current frame
     * @param location the current location
     * @return a source section corresponding to the location. Returns {@code null} if the location
     *         is invalid or source sections are not available.
     *
     * @since 24.2
     */
    public final SourceSection getSourceLocation(Frame frame, Node location) {
        int bci = findBytecodeIndexImpl(frame, location);
        if (bci == -1) {
            return null;
        }
        return getSourceLocation(bci);
    }

    /**
     * Gets all {@link SourceSection source locations} associated with a particular location.
     * Returns {@code null} if the node was not parsed {@link BytecodeConfig#WITH_SOURCE with
     * sources} or if there is no associated source section for the given location. A location must
     * always be provided to get a source location otherwise <code>null</code> will be returned. *
     * <p>
     * If source sections have not yet been materialized, then <code>null</code> is returned. Source
     * sections may be materialized by calling {@link #ensureSourceInformation()}.
     *
     * @param frame the current frame
     * @param location the current location
     * @return an array of source sections corresponding to the location. Returns {@code null} if
     *         the location is invalid or source sections are not available.
     */
    public final SourceSection[] getSourceLocations(Frame frame, Node location) {
        int bci = findBytecodeIndexImpl(frame, location);
        if (bci == -1) {
            return null;
        }
        return getSourceLocations(bci);
    }

    /**
     * Finds the most concrete source location associated with the given bytecode index. The method
     * returns <code>null</code> if no source section could be found. Calling this method also
     * {@link BytecodeRootNodes#ensureSourceInformation() ensures source sections} are materialized.
     * *
     * <p>
     * If source sections have not yet been materialized, then <code>null</code> is returned. Source
     * sections may be materialized by calling {@link #ensureSourceInformation()}.
     *
     *
     * @param bytecodeIndex the bytecode index, used to determine liveness of source sections. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @since 24.2
     */
    public abstract SourceSection getSourceLocation(int bytecodeIndex);

    /**
     * Finds all source locations associated with the given bytecode index. More concrete source
     * sections appear earlier in the array. Typically, a given section will contain the previous
     * source section, but there is no guarantee that this the case. Calling this method also
     * {@link BytecodeRootNodes#ensureSourceInformation() ensures source sections} are materialized.
     * <p>
     * If source sections have not yet been materialized, then <code>null</code> is returned. Source
     * sections may be materialized by calling {@link BytecodeRootNodes#update(BytecodeConfig)
     * update} with {@link BytecodeConfig#WITH_SOURCE}.
     *
     * @param bytecodeIndex the bytecode index, used to determine liveness of source sections. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @since 24.2
     */
    public abstract SourceSection[] getSourceLocations(int bytecodeIndex);

    private int findBytecodeIndexImpl(Frame frame, Node location) {
        Objects.requireNonNull(frame, "Provided frame must not be null.");
        Objects.requireNonNull(location, "Provided location must not be null.");
        Node operationNode = findOperationNode(location);
        return findBytecodeIndex(frame, operationNode);
    }

    @TruffleBoundary
    private Node findOperationNode(Node location) {
        Node prev = null;
        BytecodeNode bytecode = null;
        // Validate that location is this or a child of this.
        for (Node current = location; current != null; current = current.getParent()) {
            if (current == this) {
                bytecode = this;
                break;
            }
            prev = current;
        }
        if (bytecode == null) {
            return null;
        }
        return prev;
    }

    /**
     * Gets the source location associated with a particular {@link FrameInstance frameInstance}. *
     * <p>
     * If source sections have not yet been materialized, then <code>null</code> is returned. Source
     * sections may be materialized by calling {@link BytecodeRootNodes#update(BytecodeConfig)
     * update} with {@link BytecodeConfig#WITH_SOURCE}.
     *
     *
     * @param frameInstance the frame instance
     * @return the source location, or null if a location could not be found
     * @since 24.2
     */
    public final SourceSection getSourceLocation(FrameInstance frameInstance) {
        int bci = findBytecodeIndex(frameInstance);
        if (bci == -1) {
            return null;
        }
        return getSourceLocation(bci);
    }

    /**
     * Gets all source locations associated with a particular {@link FrameInstance frameInstance}. *
     * <p>
     * If source sections have not yet been materialized, then <code>null</code> is returned. Source
     * sections may be materialized by calling {@link BytecodeRootNodes#update(BytecodeConfig)
     * update} with {@link BytecodeConfig#WITH_SOURCE}.
     *
     *
     * @param frameInstance the frame instance
     * @return the source locations, or null if they could not be found
     * @since 24.2
     */
    public final SourceSection[] getSourceLocations(FrameInstance frameInstance) {
        int bci = findBytecodeIndex(frameInstance);
        if (bci == -1) {
            return null;
        }
        return getSourceLocations(bci);
    }

    /**
     * Returns the {@link BytecodeRootNode} to which this node belongs.
     *
     * @since 24.2
     */
    public final BytecodeRootNode getBytecodeRootNode() {
        return (BytecodeRootNode) getParent();
    }

    /**
     * Gets the instruction with a bytecode index. The result is only valid if bytecode index was
     * obtained from this bytecode node using a bind variable $bytecodeIndex or
     * {@link #getBytecodeIndex}.
     * <p>
     * Compatibility note: The result of this method is subject to change without notice between
     * Truffle versions. This introspection API is therefore intended to be used for debugging and
     * tracing purposes only. Do not rely on instructions for your language semantics.
     *
     * @param bytecodeIndex the current bytecode index. A valid bytecode index can be obtained by
     *            calling {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation.
     * @since 24.2
     */
    public final Instruction getInstruction(int bytecodeIndex) {
        assert validateBytecodeIndex(bytecodeIndex);
        return findInstruction(bytecodeIndex);
    }

    /**
     * Returns the current set of {@link Instruction instructions} as an {@link Iterable}.
     * <p>
     * Compatibility note: The result of this method is subject to change without notice between
     * Truffle versions. This introspection API is therefore intended to be used for debugging and
     * tracing purposes only. Do not rely on instructions for your language semantics.
     * <p>
     * Footprint note: the backing iterable implementation consumes a fixed amount of memory. It
     * allocates the underlying instructions when it is iterated.
     *
     * @since 24.2
     */
    public final Iterable<Instruction> getInstructions() {
        return new InstructionIterable(this);
    }

    /**
     * Returns the current set of {@link Instruction instructions} as a {@link List} with random
     * access.
     * <p>
     * Compatibility note: The result of this method is subject to change without notice between
     * Truffle versions. This introspection API is therefore intended to be used for debugging and
     * tracing purposes only. Do not rely on instructions for your language semantics.
     * <p>
     * Footprint note: this method eagerly materializes an entire list, unlike
     * {@link #getInstructions()}, which allocates its elements on demand. Prefer to use
     * {@link #getInstructions()} for simple iteration use cases.
     *
     * @since 24.2
     */
    public final List<Instruction> getInstructionsAsList() {
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction instruction : getInstructions()) {
            instructions.add(instruction);
        }
        return instructions;
    }

    /**
     * Produces a list of {@link SourceInformation} for a bytecode node. If no source information is
     * available, returns {@code null}.
     * <p>
     * Footprint note: the backing list implementation consumes a fixed amount of memory. It
     * allocates the underlying {@link SourceInformation} elements when it is {@link List#get
     * accessed}.
     *
     * @since 24.2
     */
    public abstract List<SourceInformation> getSourceInformation();

    /**
     * Produces a {@link SourceInformationTree} for this node. If no source information is
     * available, returns {@code null}.
     * <p>
     * The tree returned by this method will have a {@link SourceInformationTree#getSourceSection()
     * source section} that spans the whole bytecode range, or a {@code null} section if no such
     * section exists. For example, the Root operation could directly contain two SourceSection
     * operations that cover different bytecode ranges. The source section can be {@code null} even
     * if there is a single SourceSection operation containing the entire root body. For reliable
     * source information that covers the entire bytecode range, the Root operation should be nested
     * inside of a SourceSection operation.
     * <p>
     * Footprint note: this method eagerly materializes an entire tree, unlike
     * {@link #getSourceInformation()}, which allocates its elements on demand. Prefer to use
     * {@link #getSourceInformation()} unless you need to traverse the source tree.
     *
     * @since 24.2
     */
    public abstract SourceInformationTree getSourceInformationTree();

    /**
     * Ensures that sources are materialized for this node and returns an updated bytecode node if
     * it changed during materialization.
     *
     * @see BytecodeLocation#ensureSourceInformation()
     * @see BytecodeRootNodes#ensureSourceInformation()
     * @since 24.2
     */
    public final BytecodeNode ensureSourceInformation() {
        if (hasSourceInformation()) {
            // fast-path optimization
            return this;
        }
        BytecodeRootNode rootNode = this.getBytecodeRootNode();
        rootNode.getRootNodes().update(BytecodeConfig.WITH_SOURCE);
        BytecodeNode newNode = getBytecodeRootNode().getBytecodeNode();
        assert newNode.hasSourceInformation() : "materialization of sources failed";
        return newNode;
    }

    /**
     * Returns <code>true</code> if source information was materialized for this bytecode node,
     * otherwise <code>false</code>. This methods is guaranteed to return <code>true</code> if
     * {@link #ensureSourceInformation()} was called prior to this method.
     *
     * @see #ensureSourceInformation()
     * @since 24.2
     */
    public abstract boolean hasSourceInformation();

    /**
     * Returns all of the {@link ExceptionHandler exception handlers} associated with this node.
     *
     * @since 24.2
     */
    public abstract List<ExceptionHandler> getExceptionHandlers();

    /**
     * Returns the {@link TagTree} for this node. The tree only contains tag operations for the tags
     * that were enabled during parsing; if no tags were enabled, returns {@code null}.
     *
     * @since 24.2
     */
    public abstract TagTree getTagTree();

    /**
     * Returns a new array containing the current value of each local in the frame. This method
     * should only be used for slow-path use cases (like frame introspection). Prefer regular local
     * load operations (via {@code LoadLocal} operations) when possible.
     * <p>
     * An operation can use this method by binding the root node to a specialization parameter (via
     * {@code @Bind("$root")}) and then invoking the method on the root node.
     * <p>
     * The order of the locals corresponds to the order in which they were created using one of the
     * {@code createLocal()} overloads. It is up to the language to track the creation order.
     *
     * @param bytecodeIndex the current bytecode index of the given frame. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frame passed then the result of this method is unspecified.
     * @param frame the frame to read locals from
     * @return an array of local values
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final Object[] getLocalValues(int bytecodeIndex, Frame frame) {
        assert validateBytecodeIndex(bytecodeIndex);
        Objects.requireNonNull(frame);
        CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
        int count = getLocalCount(bytecodeIndex);
        Object[] locals = new Object[count];
        for (int i = 0; i < count; i++) {
            locals[i] = getLocalValue(bytecodeIndex, frame, i);
        }
        return locals;
    }

    /**
     * Returns the current value of the local at offset {@code localOffset} in the frame. This
     * method should be used for uncommon scenarios, like when a node needs to read a local directly
     * from the frame. Prefer reading locals directly in the bytecode (via {@code LoadLocal}
     * operations) when possible.
     *
     * @param bytecodeIndex the current bytecode index of the given frame. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frame passed then the result of this method is unspecified.
     * @param frame the frame to read locals from
     * @param localOffset the logical offset of the local (as obtained by
     *            {@link BytecodeLocal#getLocalOffset()} or {@link LocalVariable#getLocalOffset()}).
     * @return the current local value
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    public abstract Object getLocalValue(int bytecodeIndex, Frame frame, int localOffset);

    /**
     * Returns the current int value of the local at offset {@code localOffset} in the frame. Throws
     * {@link UnexpectedResultException} if the value is not an int.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public int getLocalValueInt(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Integer i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current float value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a float.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public float getLocalValueFloat(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Float i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current long value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a long.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public long getLocalValueLong(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Long i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current double value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a double.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public double getLocalValueDouble(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Double i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current short value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a short.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public short getLocalValueShort(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Short i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current byte value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a byte.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public byte getLocalValueByte(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Byte i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns the current boolean value of the local at offset {@code localOffset} in the frame.
     * Throws {@link UnexpectedResultException} if the value is not a boolean.
     *
     * @see #getLocalValue(int, Frame, int)
     * @since 24.2
     */
    public boolean getLocalValueBoolean(int bytecodeIndex, Frame frame, int localOffset) throws UnexpectedResultException {
        Object value = getLocalValue(bytecodeIndex, frame, localOffset);
        if (value instanceof Boolean i) {
            return i;
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new UnexpectedResultException(value);
        }
    }

    /**
     * Returns a new array containing the slot name of locals, as provided during bytecode building.
     * If a local is not allocated using a {@code createLocal} overload that takes a {@code name},
     * its name will be {@code null}.
     * <p>
     * The order of the local names corresponds to the order in which the locals were created using
     * one of the {@code createLocal()} overloads. It is up to the language to track the creation
     * order.
     *
     * @param bytecodeIndex the current bytecode index, used to determine liveness of locals. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @return an array of local names
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final Object[] getLocalNames(int bytecodeIndex) {
        CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
        int count = getLocalCount(bytecodeIndex);
        Object[] locals = new Object[count];
        for (int i = 0; i < count; i++) {
            locals[i] = getLocalName(bytecodeIndex, i);
        }
        return locals;
    }

    /**
     * Returns the name of the local at the given {@code localOffset}, as provided during bytecode
     * building. If a local is not allocated using a {@code createLocal} overload that takes a
     * {@code name}, its name will be {@code null}.
     *
     * @param bytecodeIndex the current bytecode index, used to determine liveness of locals. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @param localOffset the logical offset of the local (as obtained by
     *            {@link BytecodeLocal#getLocalOffset()} or {@link LocalVariable#getLocalOffset()}).
     * @return the local name as a partial evaluation constant
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    public abstract Object getLocalName(int bytecodeIndex, int localOffset);

    /**
     * Returns a new array containing the {@link FrameDescriptor#getSlotInfo infos} of locals, as
     * provided during bytecode building. If a local is not allocated using a {@code createLocal}
     * overload that takes an {@code info}, its info will be {@code null}.
     * <p>
     * The order of the local infos corresponds to the order in which the locals were created using
     * one of the {@code createLocal()} overloads. It is up to the language to track the creation
     * order.
     *
     * @param bytecodeIndex the current bytecode index, used to determine liveness of locals. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @return an array of local names
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final Object[] getLocalInfos(int bytecodeIndex) {
        CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
        int count = getLocalCount(bytecodeIndex);
        Object[] locals = new Object[count];
        for (int i = 0; i < count; i++) {
            locals[i] = getLocalInfo(bytecodeIndex, i);
        }
        return locals;
    }

    /**
     * Returns the {@link FrameDescriptor#getSlotInfo info} of a local, as provided during bytecode
     * building. If a local is not allocated using a {@code createLocal} overload that takes an
     * {@code info}, its info will be {@code null}.
     *
     * @param bytecodeIndex bytecodeIndex the current bytecode index, used to determine liveness of
     *            locals. A valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @param localOffset the logical offset of the local (as obtained by
     *            {@link BytecodeLocal#getLocalOffset()} or {@link LocalVariable#getLocalOffset()}).
     * @return the local info as a partial evaluation constant
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    public abstract Object getLocalInfo(int bytecodeIndex, int localOffset);

    /**
     * Updates the values of the live locals in the frame. This method should be used for uncommon
     * scenarios, like setting locals in the prolog/epilog or from another root node. Prefer setting
     * locals directly in the bytecode (via {@code StoreLocal} operations or {@link LocalSetter})
     * when possible.
     * <p>
     *
     * @param bytecodeIndex the current bytecode index of the given frame. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frame passed then the result of this method is unspecified.
     * @param frame the frame to store the local values into
     * @param values the values to store into the frame
     *
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final void setLocalValues(int bytecodeIndex, Frame frame, Object[] values) {
        CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
        int count = getLocalCount(bytecodeIndex);
        if (values.length != count) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Invalid number of values.");
        }
        for (int i = 0; i < count; i++) {
            setLocalValue(bytecodeIndex, frame, i, values[i]);
        }
    }

    /**
     * Copies the values of the live locals from the source frame to the destination frame.
     *
     * @param bytecodeIndex the current bytecode index of the given frames. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frames passed then the result of this method is unspecified.
     * @param source the frame to copy locals from
     * @param destination the frame to copy locals into
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final void copyLocalValues(int bytecodeIndex, Frame source, Frame destination) {
        CompilerAsserts.partialEvaluationConstant(bytecodeIndex);
        int count = getLocalCount(bytecodeIndex);
        for (int i = 0; i < count; i++) {
            setLocalValue(bytecodeIndex, destination, i, getLocalValue(bytecodeIndex, source, i));
        }
    }

    /**
     * Copies the first {@code length} locals from the {@code source} frame to the
     * {@code destination} frame. The frames must have the same {@link Frame#getFrameDescriptor()
     * layouts}. Compared to {@link #copyLocalValues(int, Frame, Frame)}, this method allows
     * languages to selectively copy a subset of the frame's locals.
     * <p>
     * For example, suppose that in addition to regular locals, a root node uses temporary locals
     * for intermediate computations. Suppose also that the node needs to be able to compute the
     * values of its regular locals (e.g., for frame introspection). This method can be used to only
     * copy the regular locals and not the temporary locals -- assuming all of the regular locals
     * were allocated (using {@code createLocal()}) before the temporary locals.
     *
     * @param bytecodeIndex the current bytecode index of the given frames. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frame passed then the result of this method is unspecified.
     * @param source the frame to copy locals from
     * @param destination the frame to copy locals into
     * @param localOffset the logical offset of the first local to be copied (as obtained by
     *            {@link BytecodeLocal#getLocalOffset()} or {@link LocalVariable#getLocalOffset()}).
     * @param localCount the number of locals to copy
     * @see GenerateBytecode#enableLocalScoping
     * @since 24.2
     */
    @ExplodeLoop
    public final void copyLocalValues(int bytecodeIndex, Frame source, Frame destination, int localOffset, int localCount) {
        CompilerAsserts.partialEvaluationConstant(localCount);
        if (localCount < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Negative length not allowed.");
        }
        if (localOffset < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Negative startIndex not allowed.");
        }
        int endLocal = Math.min(localOffset + localCount, getLocalCount(bytecodeIndex));
        for (int i = localOffset; i < endLocal; i++) {
            setLocalValue(bytecodeIndex, destination, i, getLocalValue(bytecodeIndex, source, i));
        }
    }

    /**
     * Updates the current value of the local at index {@code localOffset} in the frame. This method
     * should be used for uncommon scenarios, like setting a local in the prolog/epilog or from
     * another root node. Prefer setting locals directly in the bytecode (via {@code StoreLocal}
     * operations when possible.
     * <p>
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param bytecodeIndex the current bytecode index of the given frame. A valid bytecode index
     *            can be obtained by calling {@link BytecodeLocation#getBytecodeIndex()} or
     *            using @{@link Bind Bind}("$bytecodeIndex") annotation. The value must be a partial
     *            evaluation constant. If the bytecode index is inconsistent with the state of the
     *            frame passed then the result of this method is unspecified.
     * @param frame the frame to store the locals value into
     * @param localOffset the logical offset of the local (as obtained by
     *            {@link BytecodeLocal#getLocalOffset()} or {@link LocalVariable#getLocalOffset()}).
     * @param value the value to store into the local
     * @since 24.2
     * @see GenerateBytecode#enableLocalScoping
     */
    public abstract void setLocalValue(int bytecodeIndex, Frame frame, int localOffset, Object value);

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given int value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueInt(int bytecodeIndex, Frame frame, int localOffset, int value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given long value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueLong(int bytecodeIndex, Frame frame, int localOffset, long value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given float value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueFloat(int bytecodeIndex, Frame frame, int localOffset, float value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given double value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueDouble(int bytecodeIndex, Frame frame, int localOffset, double value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given short value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueShort(int bytecodeIndex, Frame frame, int localOffset, short value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given byte value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueByte(int bytecodeIndex, Frame frame, int localOffset, byte value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Updates the current value of the local at offset {@code localOffset} in the frame to the
     * given boolean value.
     *
     * @since 24.2
     * @see #setLocalValue(int, Frame, int, Object)
     */
    public void setLocalValueBoolean(int bytecodeIndex, Frame frame, int localOffset, boolean value) {
        setLocalValue(bytecodeIndex, frame, localOffset, value);
    }

    /**
     * Returns the number of live locals at the given {@code bytecodeIndex}.
     *
     * @param bytecodeIndex the current bytecode index, used to determine liveness of locals. A
     *            valid bytecode index can be obtained by calling
     *            {@link BytecodeLocation#getBytecodeIndex()} or using @{@link Bind
     *            Bind}("$bytecodeIndex") annotation. The value must be a partial evaluation
     *            constant.
     * @return the number of live locals
     * @since 24.2
     * @see GenerateBytecode#enableLocalScoping
     */
    public abstract int getLocalCount(int bytecodeIndex);

    /**
     * Returns a list of all of the {@link LocalVariable local variables} with liveness info.
     *
     * @return a list of locals
     * @since 24.2
     */
    public abstract List<LocalVariable> getLocals();

    /**
     * Sets a threshold that must be reached before the uncached interpreter switches to a cached
     * interpreter. The interpreter can switch to cached when the number of times it returns,
     * yields, and branches backwards exceeds the threshold.
     * <p>
     * This method has no effect if an uncached interpreter is not
     * {@link GenerateBytecode#enableUncachedInterpreter enabled} or the root node has already
     * switched to a specializing interpreter.
     *
     * @since 24.2
     */
    public abstract void setUncachedThreshold(int threshold);

    /**
     * Returns the tier of this bytecode node.
     *
     * @since 24.2
     */
    public abstract BytecodeTier getTier();

    /**
     * Dumps the bytecode with no highlighted location.
     *
     * @see #dump(BytecodeLocation)
     * @since 24.2
     */
    public final String dump() {
        return dump(null);
    }

    /**
     * Convert this bytecode node to a string representation for debugging purposes.
     *
     * @param bytecodeIndex an optional location to highlight in the dump.
     * @since 24.2
     */
    @TruffleBoundary
    public final String dump(int bytecodeIndex) {
        BytecodeLocation location;
        if (bytecodeIndex >= 0) {
            location = getBytecodeLocation(bytecodeIndex);
        } else {
            location = null;
        }
        return dump(location);
    }

    /**
     * Convert this bytecode node to a string representation for debugging purposes.
     *
     * @param highlightedLocation an optional location to highlight in the dump.
     * @since 24.2
     */
    @TruffleBoundary
    public final String dump(BytecodeLocation highlightedLocation) {
        record IndexedInstruction(Instruction instruction, int index) {
        }

        if (highlightedLocation != null && highlightedLocation.getBytecodeNode() != this) {
            throw new IllegalArgumentException("Invalid highlighted location. Belongs to a different BytecodeNode.");
        }
        List<Instruction> instructions = getInstructionsAsList();
        List<ExceptionHandler> exceptions = getExceptionHandlers();
        List<LocalVariable> locals = getLocals();
        List<SourceInformation> sourceInformation = getSourceInformation();
        int highlightedBci = highlightedLocation == null ? -1 : highlightedLocation.getBytecodeIndex();

        int instructionCount = instructions.size();
        int maxLabelSize = Math.min(80, instructions.stream().mapToInt((i) -> Instruction.formatLabel(i).length()).max().orElse(0));
        int maxArgumentSize = Math.min(100, instructions.stream().mapToInt((i) -> Instruction.formatArguments(i).length()).max().orElse(0));

        List<IndexedInstruction> indexedInstructions = new ArrayList<>(instructions.size());
        for (Instruction i : instructions) {
            indexedInstructions.add(new IndexedInstruction(i, indexedInstructions.size()));
        }

        String instructionsDump = formatList(indexedInstructions,
                        (i) -> i.instruction().getBytecodeIndex() == highlightedBci,
                        (i) -> Instruction.formatInstruction(i.index(), i.instruction(), maxLabelSize, maxArgumentSize));

        int exceptionCount = exceptions.size();
        String exceptionDump = formatList(exceptions,
                        (e) -> highlightedBci >= e.getStartBytecodeIndex() && highlightedBci < e.getEndBytecodeIndex(),
                        ExceptionHandler::toString);

        int localsCount = locals.size();
        String localsDump = formatList(locals,
                        (e) -> highlightedBci >= e.getStartIndex() && highlightedBci < e.getEndIndex(),
                        LocalVariable::toString);

        String sourceInfoCount = sourceInformation != null ? String.valueOf(sourceInformation.size()) : "-";
        String sourceDump = formatList(sourceInformation,
                        (s) -> highlightedBci >= s.getStartBytecodeIndex() && highlightedBci < s.getEndBytecodeIndex(),
                        SourceInformation::toString);

        String tagDump = formatTagTree(getTagTree(), (s) -> highlightedBci >= s.getEnterBytecodeIndex() && highlightedBci <= s.getReturnBytecodeIndex());
        return String.format("""
                        %s(name=%s)[
                            instructions(%s) = %s
                            exceptionHandlers(%s) = %s
                            locals(%s) = %s
                            sourceInformation(%s) = %s
                            tagTree%s
                        ]""",
                        getClass().getSimpleName(), ((RootNode) getParent()).getQualifiedName(),
                        instructionCount, instructionsDump,
                        exceptionCount, exceptionDump,
                        localsCount, localsDump,
                        sourceInfoCount, sourceDump,
                        tagDump);
    }

    private static <T> String formatList(List<T> list, Predicate<T> highlight, Function<T, String> toString) {
        if (list == null) {
            return "Not Available";
        } else if (list.isEmpty()) {
            return "Empty";
        }
        StringBuilder b = new StringBuilder();
        for (T o : list) {
            if (highlight.test(o)) {
                b.append("\n    ==> ");
            } else {
                b.append("\n        ");
            }
            b.append(toString.apply(o));
        }
        return b.toString();
    }

    private static String formatTagTree(TagTree tree, Predicate<TagTree> highlight) {
        if (tree == null) {
            return " = Not Available";
        }
        int maxWidth = maxTagTreeWidth(0, tree);

        StringBuilder b = new StringBuilder();
        int count = appendTagTree(b, 0, maxWidth, tree, highlight);
        b.insert(0, "(" + count + ") = ");
        return b.toString();
    }

    private static int maxTagTreeWidth(int indentation, TagTree tree) {
        int width = formatTagTreeLabel(indentation, tree, (i) -> false, tree).length();
        for (TagTree child : tree.getTreeChildren()) {
            width = Math.max(width, maxTagTreeWidth(indentation + 2, child));
        }
        return width;
    }

    private static int appendTagTree(StringBuilder sb, int indentation, int maxWidth, TagTree tree, Predicate<TagTree> highlight) {
        TagTreeNode node = (TagTreeNode) tree;
        sb.append("\n");

        String line = formatTagTreeLabel(indentation, tree, highlight, node);
        sb.append(line);

        int spaces = maxWidth - line.length();
        for (int i = 0; i < spaces; i++) {
            sb.append(" ");
        }
        sb.append(" | ");

        SourceSection sourceSection = node.getSourceSection();
        if (sourceSection != null) {
            sb.append(SourceInformation.formatSourceSection(sourceSection, 60));
        }

        int count = 1;
        for (TagTree child : tree.getTreeChildren()) {
            count += appendTagTree(sb, indentation + 2, maxWidth, child, highlight);
        }
        return count;
    }

    private static String formatTagTreeLabel(int indentation, TagTree tree, Predicate<TagTree> highlight, TagTree node) {
        StringBuilder line = new StringBuilder();
        if (highlight.test(tree)) {
            line.append("    ==> ");
        } else {
            line.append("        ");
        }
        line.append("[");
        line.append(String.format("%04x", node.getEnterBytecodeIndex()));
        line.append(" .. ");
        line.append(String.format("%04x", node.getReturnBytecodeIndex()));
        line.append("] ");
        for (int i = 0; i < indentation; i++) {
            line.append(" ");
        }
        line.append("(");
        line.append(((TagTreeNode) node).getTagsString());
        line.append(")");
        return line.toString();
    }

    protected abstract Instruction findInstruction(int bytecodeIndex);

    protected abstract int findBytecodeIndex(Frame frame, Node operationNode);

    protected abstract int findBytecodeIndex(FrameInstance frameInstance);

    protected abstract int translateBytecodeIndex(BytecodeNode newNode, int bytecodeIndex);

    protected abstract boolean validateBytecodeIndex(int bytecodeIndex);

    protected final BytecodeLocation findLocation(int bytecodeIndex) {
        return new BytecodeLocation(this, bytecodeIndex);
    }

    protected static final Object createDefaultStackTraceElement(TruffleStackTraceElement e) {
        return new DefaultBytecodeStackTraceElement(e);
    }

    /**
     * Returns a new array containing the current value of each live local in the
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @see #getLocalValues(int, Frame)
     * @param frameInstance the frame instance
     * @return a new array of local values, or null if the frame instance does not correspond to an
     *         {@link BytecodeRootNode}
     * @since 24.2
     */
    public static Object[] getLocalValues(FrameInstance frameInstance) {
        BytecodeNode bytecode = get(frameInstance);
        if (bytecode == null) {
            return null;
        }
        Frame frame = resolveFrame(frameInstance);
        int bci = bytecode.findBytecodeIndexImpl(frame, frameInstance.getCallNode());
        return bytecode.getLocalValues(bci, frame);
    }

    /**
     * Returns a new array containing the names of the live locals in the
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @see #getLocalNames(int)
     * @param frameInstance the frame instance
     * @return a new array of names, or null if the frame instance does not correspond to an
     *         {@link BytecodeRootNode}
     * @since 24.2
     */
    public static Object[] getLocalNames(FrameInstance frameInstance) {
        BytecodeNode bytecode = get(frameInstance);
        if (bytecode == null) {
            return null;
        }
        int bci = bytecode.findBytecodeIndex(frameInstance);
        return bytecode.getLocalNames(bci);
    }

    /**
     * Sets the current values of the live locals in the
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @see #setLocalValues(int, Frame, Object[])
     * @param frameInstance the frame instance
     * @return whether the locals could be set with the information available in the frame instance
     * @since 24.2
     */
    public static boolean setLocalValues(FrameInstance frameInstance, Object[] values) {
        BytecodeNode bytecode = get(frameInstance);
        if (bytecode == null) {
            return false;
        }
        int bci = bytecode.findBytecodeIndex(frameInstance);
        bytecode.setLocalValues(bci, frameInstance.getFrame(FrameAccess.READ_WRITE), values);
        return true;
    }

    private static Frame resolveFrame(FrameInstance frameInstance) {
        Frame frame = frameInstance.getFrame(FrameAccess.READ_ONLY);
        if (frameInstance.getCallTarget() instanceof RootCallTarget root) {
            if (root.getRootNode() instanceof ContinuationRootNode continuation) {
                frame = continuation.findFrame(frame);
            }
        }
        return frame;
    }

    /**
     * Gets the bytecode node for a given FrameInstance. Frame instances are invalid as soon as the
     * execution of a frame is continued. A bytecode node can be used to materialize a
     * {@link BytecodeLocation}, which can be used after the {@link FrameInstance} is no longer
     * valid.
     *
     * @param frameInstance the frame instance
     * @return the corresponding bytecode node or null if no node can be found.
     * @since 24.2
     */
    @TruffleBoundary
    public static BytecodeNode get(FrameInstance frameInstance) {
        return get(frameInstance.getCallNode());
    }

    /**
     * Gets the bytecode location for a given Node, if it can be found in the parent chain.
     *
     * @param node the node
     * @return the corresponding bytecode location or null if no location can be found.
     * @since 24.2
     */
    @ExplodeLoop
    public static BytecodeNode get(Node node) {
        Node location = node;
        for (Node currentNode = location; currentNode != null; currentNode = currentNode.getParent()) {
            if (currentNode instanceof BytecodeNode bytecodeNode) {
                return bytecodeNode;
            }
        }
        return null;
    }

    /**
     * Gets the bytecode location for a given {@link TruffleStackTraceElement}, if it can be found
     * using the stack trace location.
     *
     * @param element the stack trace element
     * @return the corresponding bytecode location or null if no location can be found.
     * @since 24.2
     */
    public static BytecodeNode get(TruffleStackTraceElement element) {
        Node location = element.getLocation();
        if (location == null) {
            return null;
        }
        return get(location);
    }

}
