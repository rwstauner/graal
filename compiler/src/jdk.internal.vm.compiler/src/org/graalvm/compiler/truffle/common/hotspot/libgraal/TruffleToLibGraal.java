/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.common.hotspot.libgraal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates methods associated with both ends of a HotSpot to libgraal call. This annotation
 * simplifies navigating between these methods in an IDE.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TruffleToLibGraal {
    /**
     * Gets the token identifying a call from HotSpot to libgraal.
     */
    Id value();

    /**
     * Identifier for a call from HotSpot to libgraal.
     */
    // Please keep sorted
    enum Id {
        DoCompile,
        GetCompilerConfigurationFactoryName,
        GetDataPatchesCount,
        GetExceptionHandlersCount,
        GetInfopoints,
        GetInfopointsCount,
        GetMarksCount,
        GetNodeCount,
        GetNodeTypes,
        GetSuppliedString,
        GetTargetCodeSize,
        GetTotalFrameSize,
        InitializeCompiler,
        RegisterRuntime,
        ListCompilerOptions,
        CompilerOptionExists,
        ValidateCompilerOption,
        InitializeRuntime,
        InstallTruffleCallBoundaryMethod,
        InstallTruffleReservedOopMethod,
        NewCompiler,
        PendingTransferToInterpreterOffset,
        PurgePartialEvaluationCaches,
        Shutdown;
    }
}
