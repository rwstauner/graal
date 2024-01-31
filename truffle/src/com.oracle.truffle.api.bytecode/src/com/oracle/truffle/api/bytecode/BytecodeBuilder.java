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

/**
 * Parent class for a bytecode builder generated by the DSL. A parser uses a {@link BytecodeBuilder}
 * instance to automatically generate and validate bytecode for each root node.
 *
 * Since each {@link BytecodeRootNode} defines its own set of operations, each
 * {@link BytecodeBuilder} has its own set of builder methods. Thus, this class is an opaque
 * definition with no declared methods. Parser code should reference the builder class directly
 * (e.g., {@code MyBytecodeRootNodeGen.Builder}).
 *
 * @see <a href=
 *      "https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/UserGuide.md">Bytecode
 *      DSL user guide</a>
 *
 * @since 24.1
 */
@SuppressWarnings("static-method")
public abstract class BytecodeBuilder {

    protected static final Class<?>[] EMPTY_ARRAY = new Class<?>[0];

    /**
     * Default constructor for a {@link BytecodeBuilder}.
     *
     * @since 24.1
     */
    public BytecodeBuilder() {
    }

    protected abstract Class<?>[] getAllInstrumentations();

    protected abstract Class<?>[] getAllTags();

    /**
     * Accessor to be used by generated code only.
     */
    protected final boolean isAddSource(BytecodeConfig config) {
        return config.addSource;
    }

    /**
     * Accessor to be used by generated code only.
     */
    protected final Class<?>[] getAddInstrumentations(BytecodeConfig config) {
        if (config.addAllInstrumentationData) {
            return getAllInstrumentations();
        }
        return config.addInstrumentations;
    }

    /**
     * Accessor to be used by generated code only.
     */
    protected final Class<?>[] getRemoveInstrumentations(BytecodeConfig config) {
        if (config.addAllInstrumentationData) {
            return EMPTY_ARRAY;
        }
        return config.removeInstrumentations;
    }

    /**
     * Accessor to be used by generated code only.
     */
    protected final Class<?>[] getAddTags(BytecodeConfig config) {
        if (config.addAllInstrumentationData) {
            return getAllTags();
        }
        return config.addTags;
    }

}
