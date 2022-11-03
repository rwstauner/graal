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

package org.graalvm.wasm.nodes;

import java.util.Set;
import java.util.concurrent.locks.Lock;

import org.graalvm.collections.EconomicMap;
import org.graalvm.wasm.BinaryParser;
import org.graalvm.wasm.WasmCodeEntry;
import org.graalvm.wasm.WasmContext;
import org.graalvm.wasm.WasmInstance;
import org.graalvm.wasm.WasmModule;
import org.graalvm.wasm.debugging.data.DebugContext;
import org.graalvm.wasm.debugging.data.DebugFunction;
import org.graalvm.wasm.debugging.representation.DebugObjectDisplayValue;
import org.graalvm.wasm.memory.WasmMemory;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Represents an instrumentable Wasm node.
 */
@GenerateWrapper
@ExportLibrary(NodeLibrary.class)
public abstract class WasmInstrumentableNode extends Node implements InstrumentableNode, WasmDataAccess {
    private final int functionSourceLocation;
    @Child private WasmInstrumentationSupportNode instrumentation;

    protected WasmInstrumentableNode(int functionSourceLocation) {
        this.functionSourceLocation = functionSourceLocation;
    }

    protected WasmInstrumentableNode(WasmInstrumentableNode node) {
        this.functionSourceLocation = node.functionSourceLocation;
        this.instrumentation = node.instrumentation;
    }

    abstract void execute(VirtualFrame frame, WasmContext context);

    abstract WasmInstance instance();

    abstract WasmCodeEntry codeEntry();

    abstract void enterErrorBranch();

    abstract int localCount();

    abstract int paramCount();

    abstract int resultCount();

    abstract byte localType(int index);

    abstract byte resultType(int index);

    abstract String qualifiedName();

    protected abstract void setSource(byte[] source, int startOffset, int endOffset);

    private DebugFunction debugFunction() {
        final WasmInstance instance = instance();
        if (instance.module().hasDebugInfo()) {
            final EconomicMap<Integer, DebugFunction> debugFunctions = instance.module().debugFunctions(instance.context());
            if (debugFunctions.containsKey(functionSourceLocation)) {
                return debugFunctions.get(functionSourceLocation);
            }
        }
        return null;
    }

    protected void notifyLine(VirtualFrame frame, int line, int nextLine, int sourceLocation) {
        instrumentation.notifyLine(frame, line, nextLine, sourceLocation);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.RootBodyTag.class || tag == StandardTags.RootTag.class;
    }

    @Override
    public boolean isInstrumentable() {
        return getSourceSection() != null;
    }

    @Override
    public SourceSection getSourceSection() {
        final WasmInstance instance = instance();
        if (!instance.module().hasDebugInfo()) {
            return null;
        }
        final EconomicMap<Integer, DebugFunction> debugFunctions = instance.module().debugFunctions(instance.context());
        if (debugFunctions.containsKey(functionSourceLocation)) {
            return debugFunctions.get(functionSourceLocation).sourceSection();
        }
        return null;
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        WasmInstrumentationSupportNode info = this.instrumentation;
        // We need to check if linking is completed. Else the call nodes might not have been
        // resolved yet.
        final WasmInstance instance = instance();
        if (info == null && instance.isLinkCompleted() && materializedTags.contains(StandardTags.StatementTag.class)) {
            Lock lock = getLock();
            lock.lock();
            try {
                info = this.instrumentation;
                if (info == null) {
                    final WasmContext context = instance.context();
                    final WasmModule module = instance.module();
                    final int functionIndex = codeEntry().functionIndex();
                    final DebugFunction debugFunction = module.debugFunctions(context).get(functionSourceLocation);
                    this.instrumentation = info = insert(new WasmInstrumentationSupportNode(debugFunction, module, functionIndex));
                    final BinaryParser binaryParser = new BinaryParser(module, context, module.codeSection());
                    final byte[] bytecode = binaryParser.createFunctionDebugBytecode(functionIndex, debugFunction.lineMap().sourceLocationToLineMap());
                    setSource(bytecode, 0, bytecode.length);
                    // the debug info contains instrumentable nodes, so we need to notify for
                    // instrumentation updates.
                    notifyInserted(info);
                }
            } finally {
                lock.unlock();
            }
        }
        return this;
    }

    public String name() {
        final DebugFunction function = debugFunction();
        return function != null ? function.name() : codeEntry().function().name();
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new WasmInstrumentableNodeWrapper(this, this, probe);
    }

    @SuppressWarnings({"static-method", "unused"})
    @ExportMessage
    public final boolean hasScope(Frame frame) {
        return debugFunction() != null;
    }

    @ExportMessage
    public final Object getScope(Frame frame, @SuppressWarnings("unused") boolean nodeEnter) {
        final DebugFunction debugFunction = debugFunction();
        assert debugFunction != null;
        final DebugContext context = new DebugContext(instrumentation.currentSourceLocation());
        final MaterializedFrame materializedFrame = frame.materialize();
        return DebugObjectDisplayValue.fromDebugFunction(debugFunction, context, materializedFrame, this);
    }

    @Override
    public int loadI32FromStack(Frame frame, int index) {
        return frame.getIntStatic(codeEntry().localCount() + index);
    }

    @Override
    public long loadI64FromStack(Frame frame, int index) {
        return frame.getLongStatic(codeEntry().localCount() + index);
    }

    @Override
    public float loadF32FromStack(Frame frame, int index) {
        return frame.getFloatStatic(codeEntry().localCount() + index);
    }

    @Override
    public double loadF64FromStack(Frame frame, int index) {
        return frame.getDoubleStatic(codeEntry().localCount() + index);
    }

    @Override
    public int loadI32FromLocals(Frame frame, int index) {
        return frame.getIntStatic(index);
    }

    @Override
    public long loadI64FromLocals(Frame frame, int index) {
        return frame.getLongStatic(index);
    }

    @Override
    public float loadF32FromLocals(Frame frame, int index) {
        return frame.getFloatStatic(index);
    }

    @Override
    public double loadF64FromLocals(Frame frame, int index) {
        return frame.getDoubleStatic(index);
    }

    @Override
    public int loadI32FromGlobals(int index) {
        int address = instance().globalAddress(index);
        return instance().context().globals().loadAsInt(address);
    }

    @Override
    public long loadI64FromGlobals(int index) {
        int address = instance().globalAddress(index);
        return instance().context().globals().loadAsLong(address);
    }

    @Override
    public float loadF32FromGlobals(int index) {
        return Float.floatToRawIntBits(loadI32FromGlobals(index));
    }

    @Override
    public double loadF64FromGlobals(int index) {
        return Double.doubleToRawLongBits(loadI64FromGlobals(index));
    }

    @Override
    public byte loadI8FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return (byte) memory.load_i32_8s(this, address);
    }

    @Override
    public short loadI16FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return (short) memory.load_i32_16s(this, address);
    }

    @Override
    public int loadI32FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return memory.load_i32(this, address);
    }

    @Override
    public long loadI64FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return memory.load_i64(this, address);
    }

    @Override
    public float loadF32FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return memory.load_f32(this, address);
    }

    @Override
    public double loadF64FromMemory(long address) {
        WasmMemory memory = instance().memory();
        return memory.load_f64(this, address);
    }

    private byte[] loadByteArrayFromMemory(long address, int length) {
        WasmMemory memory = instance().memory();
        byte[] dataArray = new byte[length];
        for (int i = 0; i < length; i++) {
            dataArray[i] = (byte) memory.load_i32_8s(this, address + i);
        }
        return dataArray;
    }

    @Override
    public String loadStringFromMemory(long address, int length) {
        byte[] dataArray = loadByteArrayFromMemory(address, length);
        return new String(dataArray);
    }
}
