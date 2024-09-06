/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags.RootBodyTag;
import com.oracle.truffle.api.instrumentation.StandardTags.RootTag;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.nodes.Node;

/**
 * Generates a bytecode interpreter using the Bytecode DSL. The Bytecode DSL automatically produces
 * an optimizing bytecode interpreter from a set of Node-like "operations". The following is an
 * example of a Bytecode DSL interpreter with a single {@code Add} operation.
 *
 * <pre>
 * &#64;GenerateBytecode(languageClass = MyLanguage.class)
 * public abstract class MyBytecodeRootNode extends RootNode implements BytecodeRootNode {
 *     &#64;Operation
 *     public static final class Add {
 *         &#64;Specialization
 *         public static int doInts(int lhs, int rhs) {
 *             return lhs + rhs;
 *         }
 *
 *         &#64;Specialization
 *         &#64;TruffleBoundary
 *         public static String doStrings(String lhs, String rhs) {
 *             return lhs + rhs;
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * The DSL generates a node suffixed with {@code Gen} (e.g., {@code MyBytecodeRootNodeGen}) that
 * contains (among other things) a full bytecode encoding, an optimizing interpreter, and a
 * {@code Builder} class to generate and validate bytecode automatically.
 * <p>
 * A node can opt in to additional features, like an {@link #enableUncachedInterpreter uncached
 * interpreter}, {@link #boxingEliminationTypes boxing elimination}, {@link #enableQuickening
 * quickened instructions}, and more. The fields of this annotation control which features are
 * included in the generated interpreter.
 * <p>
 * For information about using the Bytecode DSL, please consult the <a href=
 * "https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/BytecodeDSL.md">tutorial</a>.
 *
 * @since 24.2
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE})
public @interface GenerateBytecode {
    /**
     * The {@link TruffleLanguage} class associated with this node.
     *
     * @since 24.2
     */
    Class<? extends TruffleLanguage<?>> languageClass();

    /**
     * Whether to generate an uncached interpreter.
     * <p>
     * The uncached interpreter improves start-up performance by executing
     * {@link com.oracle.truffle.api.dsl.GenerateUncached uncached} nodes instead of allocating and
     * executing cached (specializing) nodes.
     * <p>
     * The node will transition to a specializing interpreter after enough invocations/back-edges
     * (as determined by the {@link BytecodeNode#setUncachedThreshold uncached interpreter
     * threshold}).
     *
     * @since 24.2
     */
    boolean enableUncachedInterpreter() default false;

    /**
     * Whether the generated interpreter should support serialization and deserialization.
     * <p>
     * When serialization is enabled, Bytecode DSL generates code to convert bytecode nodes to and
     * from a serialized byte array representation. The code effectively serializes the node's
     * execution data (bytecode, constants, etc.) and all of its non-transient fields.
     * <p>
     * The serialization logic is defined in static {@code serialize} and {@code deserialize}
     * methods on the generated root class. The generated {@link BytecodeRootNodes} class also
     * overrides {@link BytecodeRootNodes#serialize}.
     * <p>
     * This feature can be used to avoid the overhead of parsing source code on start up. Note that
     * serialization still incurs some overhead, as it does not trivially copy bytecode directly: in
     * order to validate the bytecode (balanced stack pointers, valid branches, etc.), serialization
     * encodes builder method calls and deserialization replays those calls.
     * <p>
     * Note that the generated {@code deserialize} method takes a {@link java.util.function.Supplier
     * Supplier<DataInput>} rather than a {@link java.io.DataInput} directly. The supplier should
     * produce a fresh {@link java.io.DataInput} each time because the input may be processed
     * multiple times (due to {@link BytecodeRootNodes#update(BytecodeConfig) reparsing}).
     *
     * @see com.oracle.truffle.api.bytecode.serialization.BytecodeSerializer
     * @see com.oracle.truffle.api.bytecode.serialization.BytecodeDeserializer
     * @see <a href=
     *      "https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples/SerializationTutorial.java">Serialization
     *      tutorial</a>
     * @since 24.2
     */
    boolean enableSerialization() default false;

    /**
     * Whether the generated interpreter should support Truffle tag instrumentation. When
     * instrumentation is enabled, the generated builder will define <code>startTag(...)</code> and
     * <code>endTag(...)</code> methods that can be used to annotate the bytecode with
     * {@link com.oracle.truffle.api.instrumentation.Tag tags}. Truffle tag instrumentation also
     * allows you to specify implicit tagging using {@link Operation#tags()}. If tag instrumentation
     * is enabled all tagged operations will automatically handle and insert {@link ProbeNode
     * probes} from the Truffle instrumentation framework.
     * <p>
     * Only tags that are {@link ProvidedTags provided} by the specified {@link #languageClass()
     * Truffle language} can be used.
     *
     * @see #enableRootTagging() to enable implicit root and root body tagging (default enabled)
     * @see #enableRootBodyTagging() to enable implicit root body tagging (default enabled)
     * @since 24.2
     */
    boolean enableTagInstrumentation() default false;

    /**
     * Enables automatic root tagging if {@link #enableTagInstrumentation() instrumentation} is
     * enabled. Automatic root tagging automatically tags each root with {@link RootTag} and
     * {@link RootBodyTag} if the language {@link ProvidedTags provides} it.
     * <p>
     * Root tagging requires the probe to be notified before the {@link Prolog prolog} is executed.
     * Implementing this behavior manually is not trivial and not recommended. It is recommended to
     * use automatic root tagging. For inlining performed by the parser it may be useful to emit
     * custom {@link RootTag root} tag using the builder methods for inlined methods. This ensures
     * that tools can still work correctly for inlined calls.
     *
     * @since 24.2
     * @see #enableRootBodyTagging()
     */
    boolean enableRootTagging() default true;

    /**
     * Enables automatic root body tagging if {@link #enableTagInstrumentation() instrumentation} is
     * enabled. Automatic root body tagging automatically tags each root with {@link RootBodyTag} if
     * the language {@link ProvidedTags provides} it.
     *
     * @since 24.2
     * @see #enableRootTagging()
     */
    boolean enableRootBodyTagging() default true;

    /**
     * Allows to customize the {@link NodeLibrary} implementation that is used for tag
     * instrumentation. This option only makes sense if {@link #enableTagInstrumentation()} is set
     * to <code>true</code>.
     * <p>
     * Common use-cases when implementing a custom tag tree node library is required:
     * <ul>
     * <li>Allowing instruments to access the current receiver or function object
     * <li>Implementing custom scopes for local variables instead of the default global scope.
     * <li>Hiding certain local local variables or arguments from instruments.
     * </ul>
     * <p>
     * Minimal example tag node library:
     *
     * <pre>
     * &#64;ExportLibrary(value = NodeLibrary.class, receiverType = TagTreeNode.class)
     * final class MyTagTreeNodeExports {
     *
     *     &#64;ExportMessage
     *     static boolean hasScope(TagTreeNode node, Frame frame) {
     *         return true;
     *     }
     *
     *     &#64;ExportMessage
     *     &#64;SuppressWarnings("unused")
     *     static Object getScope(TagTreeNode node, Frame frame, boolean nodeEnter) throws UnsupportedMessageException {
     *         return new MyScope(node, frame);
     *     }
     * }
     * </pre>
     *
     * See the {@link NodeLibrary} javadoc for more details.
     *
     * @see TagTreeNode
     * @since 24.2
     */
    Class<?> tagTreeNodeLibrary() default TagTreeNodeExports.class;

    /**
     * Whether to use unsafe array accesses.
     * <p>
     * Unsafe accesses are faster, but they do not perform array bounds checks. This means it is
     * possible (though unlikely) for unsafe accesses to cause undefined behaviour. Undefined
     * behavior may only happen due to a bug in the Bytecode DSL implementation and not language
     * implementation code.
     *
     * @since 24.2
     */
    boolean allowUnsafe() default true;

    /**
     * Whether the generated interpreter should support coroutines via a {@code yield} operation.
     * <p>
     * The yield operation returns a {@link ContinuationResult} from the current point in execution.
     * The {@link ContinuationResult} saves the current state of the interpreter so that it can be
     * resumed at a later time. The yield and resume actions pass values, enabling communication
     * between the caller and callee.
     * <p>
     * Technical note: in theoretical terms, a {@link ContinuationResult} implements an asymmetric
     * stack-less coroutine.
     *
     * @see com.oracle.truffle.api.bytecode.ContinuationResult
     * @since 24.2
     */
    boolean enableYield() default false;

    /**
     * Enables local variable scoping for this interpreter. By default local variable scoping is
     * enabled (<code>true</code>). Whether this flag is enabled significantly changes the behavior
     * of local variables (breaking changes), so the value of this flag should be determined
     * relatively early in the development of a language.
     * <p>
     * If local scoping is enabled then all local variables are scoped with the parent block. If no
     * block is currently on the operation stack then the local variable will be scoped with their
     * respective root. Local variables that are no longer in scope are automatically
     * {@link Frame#clear(int) cleared} when their block or root ends. When local variables are read
     * or written without an instruction using the methods in {@link BytecodeNode} then a
     * compilation final bytecode index must be passed. For example,
     * {@link BytecodeNode#getLocalValues(int, Frame)} requires a valid
     * {@link CompilerAsserts#partialEvaluationConstant(boolean) partial evaluation constant}
     * bytecode index parameter to determine which values are currently accessible. The life-time of
     * local variables can be accessed using {@link LocalVariable#getStartIndex()} and
     * {@link LocalVariable#getEndIndex()}.
     * <p>
     * If local scoping is disabled all local variables get their unique absolute index in the frame
     * independent of the current source location. This means that when reading the current
     * {@link BytecodeNode#getLocalValues(int, Frame) local values} the bytecode index parameter has
     * no effect. With scoping disabled no additional meta-data needs to be emitted for the
     * life-time of local variables, hence {@link BytecodeNode#getLocals()} returns local variables
     * without life-time ranges.
     * <p>
     * Primarily local variable scoping is intended to be disabled if the implemented language does
     * not use local variable scoping, but it can also be useful if the default local variable
     * scoping is not flexible enough and custom scoping rules are needed.
     *
     * @since 24.2
     */
    boolean enableLocalScoping() default true;

    /**
     * Whether quickened bytecodes should be emitted.
     * <p>
     * Quickened versions of instructions support a subset of the
     * {@link com.oracle.truffle.api.dsl.Specialization specializations} defined by an operation.
     * They can improve interpreted performance by reducing footprint and requiring fewer guards.
     * <p>
     * Quickened versions of operations can be specified using
     * {@link com.oracle.truffle.api.bytecode.ForceQuickening}. When an instruction re-specializes
     * itself, the interpreter attempts to automatically replace it with a quickened instruction.
     *
     * @since 24.2
     */
    boolean enableQuickening() default true;

    /**
     * Whether the generated interpreter should store the bytecode index (bci) in the frame.
     * <p>
     * By default, methods that compute location-dependent information (like
     * {@link BytecodeNode#getBytecodeLocation(com.oracle.truffle.api.frame.Frame, Node)}) must
     * follow {@link Node#getParent() Node parent} pointers and scan the bytecode to compute the
     * current bci, which is not suitable for the fast path. When this feature is enabled, an
     * implementation can use
     * {@link BytecodeNode#getBytecodeIndex(com.oracle.truffle.api.frame.Frame)} to obtain the bci
     * efficiently on the fast path and use it for location-dependent computations (e.g.,
     * {@link BytecodeNode#getBytecodeLocation(int)}).
     * <p>
     * Note that operations always have fast-path access to the bci using a bind parameter (e.g.,
     * {@code @Bind("$bytecodeIndex") int bci}); this feature should only be enabled for fast-path
     * bci access outside of the current operation (e.g., for closures or frame introspection).
     * Storing the bci in the frame increases frame size and requires additional frame writes, so it
     * can negatively affect performance.
     *
     * @since 24.2
     */
    boolean storeBytecodeIndexInFrame() default false;

    /**
     * Path to a file containing optimization decisions. This file is generated using tracing on a
     * representative corpus of code.
     * <p>
     * This feature is not yet supported.
     *
     * @see #forceTracing()
     * @since 24.2
     */
    // TODO GR-57220
    // String decisionsFile() default "";

    /**
     * Path to files with manually-provided optimization decisions. These files can be used to
     * encode optimizations that are not generated automatically via tracing.
     * <p>
     * This feature is not yet supported.
     *
     * @since 24.2
     */
    // TODO GR-57220
    // String[] decisionOverrideFiles() default {};

    /**
     * Whether to build the interpreter with tracing. Can also be configured using the
     * {@code truffle.dsl.OperationsEnableTracing} option during compilation.
     * <p>
     * Note that this is a debug option that should not be used in production. Also note that this
     * field only affects code generation: whether tracing is actually performed at run time is
     * still controlled by the aforementioned option.
     * <p>
     * This feature is not yet supported.
     *
     * @since 24.2
     */
    // TODO GR-57220
    // boolean forceTracing() default false;

    /**
     * Primitive types for which the interpreter should attempt to avoid boxing.
     *
     * If boxing elimination types are provided, the cached interpreter will generate instruction
     * variants that load/store primitive values when possible. It will automatically use these
     * instructions in a best-effort manner (falling back on boxed representations when necessary).
     *
     * @since 24.2
     */
    Class<?>[] boxingEliminationTypes() default {};

    /**
     * Whether to generate introspection data for specializations. The data is accessible using
     * {@link com.oracle.truffle.api.bytecode.Instruction.Argument#getSpecializationInfo()}.
     *
     * @since 24.2
     */
    boolean enableSpecializationIntrospection() default true;

}
