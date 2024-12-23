/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.graphbuilderconf;

import static org.graalvm.nativeimage.ImageInfo.inImageBuildtimeCode;
import static org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.PluginReplacementNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin.RequiredInlineOnlyInvocationPlugin;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Abstract class for a plugin generated for a method annotated by {@link NodeIntrinsic} or
 * {@link Fold}.
 */
public abstract class GeneratedInvocationPlugin extends RequiredInlineOnlyInvocationPlugin {

    private static List<Class<?>> foldNodePluginClasses = List.of(GeneratedFoldInvocationPlugin.class, PluginReplacementNode.ReplacementFunction.class);

    public static void setFoldNodePluginClasses(List<Class<?>> customFoldNodePluginClasses) {
        foldNodePluginClasses = customFoldNodePluginClasses;
    }

    public static List<Class<?>> getFoldNodePluginClasses() {
        return foldNodePluginClasses;
    }

    private ResolvedJavaMethod executeMethod;

    public GeneratedInvocationPlugin(String name, Type... argumentTypes) {
        super(name, argumentTypes);
    }

    /**
     * Gets the class of the annotation for which this plugin was generated.
     */
    public abstract Class<? extends Annotation> getSource();

    @Override
    public abstract boolean execute(GraphBuilderContext b, ResolvedJavaMethod targetMethod, InvocationPlugin.Receiver receiver, ValueNode[] args);

    @Override
    public String getSourceLocation() {
        Class<?> c = getClass();
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("execute")) {
                return String.format("%s.%s()", m.getDeclaringClass().getName(), m.getName());
            }
        }
        throw new GraalError("could not find method named \"execute\" in " + c.getName());
    }

    protected boolean checkInjectedArgument(GraphBuilderContext b, ValueNode arg, ResolvedJavaMethod foldAnnotatedMethod) {
        if (arg.isNullConstant()) {
            return true;
        }

        if (inImageRuntimeCode()) {
            // The reflection here is problematic for SVM.
            return true;
        }

        if (b.getMethod().equals(foldAnnotatedMethod)) {
            return false;
        }

        if (inImageBuildtimeCode()) {
            // The use of this plugin in the plugin itself shouldn't be folded since that defeats
            // the purpose of the fold.
            for (Class<?> foldNodePluginClass : foldNodePluginClasses) {
                ResolvedJavaType foldNodeClass = b.getMetaAccess().lookupJavaType(foldNodePluginClass);
                if (foldNodeClass.isAssignableFrom(b.getMethod().getDeclaringClass())) {
                    return false;
                }
            }
        }

        ResolvedJavaMethod thisExecuteMethod = getExecutedMethod(b);
        if (b.getMethod().equals(thisExecuteMethod)) {
            return true;
        }
        throw new AssertionError("must pass null to injected argument of " + foldAnnotatedMethod.format("%H.%n(%p)") + ", not " + arg + " in " + b.getMethod().format("%H.%n(%p)"));
    }

    private ResolvedJavaMethod getExecutedMethod(GraphBuilderContext b) {
        if (executeMethod == null) {
            MetaAccessProvider metaAccess = b.getMetaAccess();
            ResolvedJavaMethod baseMethod = metaAccess.lookupJavaMethod(getExecuteMethod());
            ResolvedJavaType thisClass = metaAccess.lookupJavaType(getClass());
            executeMethod = thisClass.resolveConcreteMethod(baseMethod, thisClass);
        }
        return executeMethod;
    }

    private static Method getExecuteMethod() {
        try {
            return GeneratedInvocationPlugin.class.getMethod("execute", GraphBuilderContext.class, ResolvedJavaMethod.class, InvocationPlugin.Receiver.class, ValueNode[].class);
        } catch (NoSuchMethodException | SecurityException e) {
            throw new GraalError(e);
        }
    }

    public final boolean isGeneratedFromFoldOrNodeIntrinsic() {
        return getSource().equals(Fold.class) || getSource().equals(NodeIntrinsic.class);
    }
}
