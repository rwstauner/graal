/*
 * Copyright (c) 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.wasm.binary;

import static com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import static com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import static com.oracle.truffle.api.CompilerDirectives.transferToInterpreter;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.wasm.binary.exception.WasmException;
import com.oracle.truffle.wasm.collection.BooleanArrayList;
import com.oracle.truffle.wasm.collection.ByteArrayList;
import com.oracle.truffle.wasm.collection.LongArrayList;

import java.util.ArrayList;

@ExportLibrary(InteropLibrary.class)
public class WasmModule implements TruffleObject {
    @CompilationFinal private final String name;
    @CompilationFinal private final SymbolTable symbolTable;
    @CompilationFinal private final Table table;

    public WasmModule(String name) {
        this.name = name;
        this.symbolTable = new SymbolTable(this);
        this.table = new Table();
    }

    // static final class Globals {
    //     // Temporary objects to store globals as they come.
    //     // This is necessary as there may be imported globals before the module globals.
    //     LongArrayList globals;
    //     ByteArrayList globalTypes;
    //     BooleanArrayList globalMut;
    //
    //     /**
    //      * Stores the final globals as 64-bit values.
    //      */
    //     @CompilationFinal(dimensions = 1)
    //     private long[] finalGlobals;
    //
    //     /**
    //      * Stores the type of each final global. A global can be of any of the valid value types.
    //      */
    //     @CompilationFinal(dimensions = 1)
    //     private byte[] finalGlobalTypes;
    //
    //     /**
    //      * Stores whether each final global is mutable.
    //      */
    //     @CompilationFinal(dimensions = 1)
    //     private boolean[] finalGlobalMut;
    //
    //     private boolean madeFinal;
    //
    //     private Globals() {
    //         globals = new LongArrayList();
    //         globalTypes = new ByteArrayList();
    //         globalMut = new BooleanArrayList();
    //         madeFinal = false;
    //     }
    //
    //     public void makeFinal() {
    //         if (madeFinal) {
    //             throw new WasmException("Globals has already been made final.");
    //         }
    //
    //         finalGlobals = globals.toArray();
    //         globals = null;
    //
    //         finalGlobalTypes = globalTypes.toArray();
    //         globalTypes = null;
    //
    //         finalGlobalMut = globalMut.toArray();
    //         globalMut = null;
    //
    //         madeFinal = true;
    //     }
    //
    //     public void register(long value, byte type, boolean isMutable) {
    //         globals.add(value);
    //         globalTypes.add(type);
    //         globalMut.add(isMutable);
    //     }
    //
    //     public void registerImported(String importName, byte type, boolean isMutable) {
    //         // Hack for imported globals, that are generated by emscripten.
    //         switch (importName) {
    //             case "__table_base": {
    //                 globals.add(0);
    //                 break;
    //             }
    //             default: {
    //                 globals.add(0);
    //             }
    //         }
    //         globalTypes.add(type);
    //         globalMut.add(isMutable);
    //     }
    //
    //     public int size() {
    //         return finalGlobals.length;
    //     }
    //
    //     public byte type(int index) {
    //         return finalGlobalTypes[index];
    //     }
    //
    //     public boolean isMutable(int index) {
    //         return finalGlobalMut[index];
    //     }
    // }

    static final class Table {
        /**
         * A table is an array of u32 values, indexing the module functions (imported or defined).
         */
        @CompilationFinal(dimensions = 1) private int[] functionIndices;
        private int maxSize;
        private boolean initialized = false;

        private Table() {
        }

        public void initialize(int initSize) {
            this.initialize(initSize, Integer.MAX_VALUE);
        }

        public void initialize(int initSize, int maxSize) {
            if (initialized) {
                throw new WasmException("Table has already been initialized.");
            }
            this.functionIndices = new int[initSize];
            this.maxSize = maxSize;
            initialized = true;
        }

        public boolean validateIndex(int index) {
            // TODO: Ensure index is initialized.
            return index < functionIndices.length;
        }

        public void initializeContents(int offset, int[] contents) {
            System.arraycopy(contents, 0, functionIndices, offset, contents.length);
        }

        public int functionIndex(int index) {
            return functionIndices[index];
        }
    }

    public SymbolTable symbolTable() {
        return symbolTable;
    }

    public String name() {
        return name;
    }

    public Table table() {
        return table;
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String exportName) {
        // TODO: Do we need to special case __START__?
        return exportName.equals("__START__") ? symbolTable.startFunction() : symbolTable.function(exportName);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        try {
            return symbolTable.exportedFunctions().containsKey(member);
        } catch (NumberFormatException exc) {
            return false;
        }
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new ExportedFunctions(symbolTable);
    }

    @ExportLibrary(InteropLibrary.class)
    static final class ExportedFunctions implements TruffleObject {

        private SymbolTable symbolTable;

        ExportedFunctions(SymbolTable symbolTable) {
            this.symbolTable = symbolTable;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < symbolTable.exportedFunctions().size();
        }

        @ExportMessage
        long getArraySize() {
            return symbolTable.exportedFunctions().size();
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                transferToInterpreter();
                throw InvalidArrayIndexException.create(index);
            }
            // TODO: Use a custom collection to ensure more efficient access.
            return new ArrayList<>(symbolTable.exportedFunctions().values()).get((int) index);
        }
    }

    @Override
    public String toString() {
        return "wasm-module(" + name + ")";
    }
}
