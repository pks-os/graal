/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted;

import java.util.function.Function;

import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.core.imagelayer.DynamicImageLayerInfo;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.layeredimagesingleton.MultiLayeredImageSingleton;
import com.oracle.svm.core.meta.SharedField;
import com.oracle.svm.hosted.imagelayer.HostedImageLayerBuildingSupport;
import com.oracle.svm.hosted.meta.HostedField;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaField;

@AutomaticallyRegisteredImageSingleton(StaticFieldsSupport.HostedStaticFieldSupport.class)
public class HostedStaticFieldSupportImpl implements StaticFieldsSupport.HostedStaticFieldSupport {

    @Override
    public JavaConstant getStaticPrimitiveFieldsConstant(int layerNum, Function<Object, JavaConstant> toConstant) {
        if (layerNum == MultiLayeredImageSingleton.UNUSED_LAYER_NUMBER) {
            return toConstant.apply(StaticFieldsSupport.getCurrentLayerStaticPrimitiveFields());
        } else {
            int currentLayerNum = DynamicImageLayerInfo.singleton().layerNumber;
            if (currentLayerNum == layerNum) {
                return toConstant.apply(StaticFieldsSupport.getCurrentLayerStaticPrimitiveFields());
            } else {
                assert layerNum == 0 && currentLayerNum == 1;
                return HostedImageLayerBuildingSupport.singleton().getLoader().getBaseLayerStaticPrimitiveFields();
            }
        }
    }

    @Override
    public JavaConstant getStaticObjectFieldsConstant(int layerNum, Function<Object, JavaConstant> toConstant) {
        if (layerNum == MultiLayeredImageSingleton.UNUSED_LAYER_NUMBER) {
            return toConstant.apply(StaticFieldsSupport.getCurrentLayerStaticObjectFields());
        } else {
            int currentLayerNum = DynamicImageLayerInfo.singleton().layerNumber;
            if (currentLayerNum == layerNum) {
                return toConstant.apply(StaticFieldsSupport.getCurrentLayerStaticObjectFields());
            } else {
                assert layerNum == 0 && currentLayerNum == 1;
                return HostedImageLayerBuildingSupport.singleton().getLoader().getBaseLayerStaticObjectFields();
            }
        }
    }

    @Override
    public boolean isPrimitive(ResolvedJavaField field) {
        if (field instanceof AnalysisField aField) {
            return aField.getStorageKind().isPrimitive();
        }
        return ((HostedField) field).getStorageKind().isPrimitive();
    }

    @Override
    public int getInstalledLayerNum(ResolvedJavaField field) {
        assert ImageLayerBuildingSupport.buildingImageLayer();
        if (field instanceof SharedField sField) {
            return sField.getInstalledLayerNum();
        } else {
            AnalysisField aField = (AnalysisField) field;
            return (ImageLayerBuildingSupport.buildingInitialLayer() || aField.isInBaseLayer()) ? 0 : 1;
        }
    }
}
