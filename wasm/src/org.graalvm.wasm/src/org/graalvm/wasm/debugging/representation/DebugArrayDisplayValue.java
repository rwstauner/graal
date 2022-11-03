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

package org.graalvm.wasm.debugging.representation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import org.graalvm.wasm.WasmConstant;
import org.graalvm.wasm.debugging.DebugLocation;
import org.graalvm.wasm.debugging.data.DebugContext;
import org.graalvm.wasm.debugging.data.DebugObject;
import org.graalvm.wasm.debugging.data.DebugType;

/**
 * Representation of an array scope in the debug environment.
 */
@ExportLibrary(InteropLibrary.class)
public class DebugArrayDisplayValue extends DebugDisplayValue implements TruffleObject {
    private final DebugContext context;
    private final DebugLocation location;
    private final DebugType array;
    private final String name;
    private final int dimension;
    private final int indexOffset;

    private DebugArrayDisplayValue(DebugContext context, String name, int dimension, int indexOffset, DebugLocation location, DebugType array) {
        this.context = context;
        this.dimension = dimension;
        this.indexOffset = indexOffset;
        this.location = location;
        this.array = array;
        this.name = name;
    }

    public static DebugArrayDisplayValue fromDebugObject(DebugObject object, DebugContext context, DebugLocation location) {
        return new DebugArrayDisplayValue(context, object.toString(), 0, 0, location, object);
    }

    @SuppressWarnings("static-method")
    @ExportMessage
    public boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    public long getArraySize() {
        return array.arrayDimensionSize(dimension);
    }

    @ExportMessage
    @TruffleBoundary
    public boolean isArrayElementReadable(long index) {
        return index >= 0 && index < getArraySize();
    }

    @SuppressWarnings({"unused", "static-method"})
    @ExportMessage
    public final boolean isArrayElementModifiable(long index) {
        return false;
    }

    @SuppressWarnings({"unused", "static-method"})
    @ExportMessage
    public final boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    public Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        final int offset = indexOffset + (int) index;
        if (dimension < array.arrayDimensionCount() - 1) {
            final int dimensionLength = array.arrayDimensionSize(dimension + 1);
            return new DebugArrayDisplayValue(context, "", dimension + 1, offset * dimensionLength, location, array);
        }
        final DebugObject object = array.readArrayElement(context, location, offset);
        return resolveObject(object, context, location);
    }

    private Object resolveObject(DebugObject object, DebugContext context, DebugLocation objectLocation) {
        final DebugLocation loc = object.getLocation(objectLocation);
        final DebugContext ctx = object.getContext(context);
        if (object.isValue()) {
            return object.asValue(ctx, loc);
        }
        if (object.isDebugObject()) {
            return resolveObject(object.asDebugObject(ctx, loc), ctx, loc);
        }
        if (object.hasMembers()) {
            return DebugObjectDisplayValue.fromDebugObject(object, ctx, loc);
        }
        if (object.hasArrayElements()) {
            return DebugArrayDisplayValue.fromDebugObject(object, ctx, loc);
        }
        return WasmConstant.NULL;
    }

    @SuppressWarnings({"unused", "static-method"})
    @ExportMessage
    @TruffleBoundary
    public final void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @SuppressWarnings("unused")
    Object toDisplayString(boolean allowSideEffects) {
        return name != null ? name : "";
    }
}
