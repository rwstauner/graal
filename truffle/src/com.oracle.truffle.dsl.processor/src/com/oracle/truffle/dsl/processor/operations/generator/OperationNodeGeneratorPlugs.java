package com.oracle.truffle.dsl.processor.operations.generator;

import java.util.List;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.ChildExecutionResult;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.FrameState;
import com.oracle.truffle.dsl.processor.generator.FlatNodeGenFactory.LocalVariable;
import com.oracle.truffle.dsl.processor.generator.NodeGeneratorPlugs;
import com.oracle.truffle.dsl.processor.java.ElementUtils;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.model.NodeChildData;
import com.oracle.truffle.dsl.processor.model.NodeExecutionData;
import com.oracle.truffle.dsl.processor.model.TemplateMethod;
import com.oracle.truffle.dsl.processor.operations.model.InstructionModel;

public class OperationNodeGeneratorPlugs implements NodeGeneratorPlugs {

    private final ProcessorContext context;
    private final TruffleTypes types;
    private final TypeMirror nodeType;
    private final InstructionModel instr;
    private final boolean isBoxingOperations;

    public OperationNodeGeneratorPlugs(ProcessorContext context, TypeMirror nodeType, InstructionModel instr) {
        this.context = context;
        this.types = context.getTypes();
        this.nodeType = nodeType;
        this.instr = instr;

        this.isBoxingOperations = nodeType.toString().endsWith("BoxingOperationsGen");
    }

    public List<? extends VariableElement> additionalArguments() {
        return List.of(
                        new CodeVariableElement(nodeType, "$root"),
                        new CodeVariableElement(context.getType(Object[].class), "$objs"),
                        new CodeVariableElement(context.getType(int.class), "$bci"),
                        new CodeVariableElement(context.getType(int.class), "$sp"));
    }

    public ChildExecutionResult createExecuteChild(FlatNodeGenFactory factory, CodeTreeBuilder builder, FrameState originalFrameState, FrameState frameState, NodeExecutionData execution,
                    LocalVariable targetValue) {

        CodeTreeBuilder b = builder.create();
        CodeTree frame = frameState.get(TemplateMethod.FRAME_NAME).createReference();

        b.string(targetValue.getName(), " = ");

        int index = execution.getIndex();

        boolean th = buildChildExecution(b, frame, index, targetValue.getTypeMirror());

        return new ChildExecutionResult(b.build(), th);
    }

    private boolean buildChildExecution(CodeTreeBuilder b, CodeTree frame, int idx, TypeMirror resultType) {
        int index = idx;

        if (index < instr.signature.valueCount) {

            String slotString = "$sp - " + (instr.signature.valueCount - index);

            if (instr.signature.isVariadic) {
                slotString += " - this.op_variadicCount_";
            }

            boolean canThrow;

            if (instr.signature.valueBoxingElimination[index]) {
                if (ElementUtils.isObject(resultType)) {
                    b.startCall("doPopObject");
                    canThrow = false;
                } else {
                    b.startCall("doPopPrimitive" + ElementUtils.firstLetterUpperCase(resultType.toString()));
                    canThrow = true;
                }

                b.tree(frame);
                b.string("$root");
                b.string(slotString);
                b.string("this.op_childValue" + index + "_boxing_");
                b.string("$objs");
                b.end();

                return canThrow;
            } else {
                b.startCall(frame, "getObject");
                b.string(slotString);
                b.end();
                return false;
            }
        }

        index -= instr.signature.valueCount;
        if (instr.signature.isVariadic) {
            if (index == 0) {
                b.startCall("readVariadic");
                b.tree(frame);
                b.string("$sp");
                b.string("this.op_variadicCount_");
                b.end();
                return false;
            }
            index -= 1;
        }

        if (index < instr.signature.localSetterCount) {
            b.string("this.op_localSetter" + index + "_");
            return false;
        }

        index -= instr.signature.localSetterCount;

        if (index < instr.signature.localSetterRangeCount) {
            b.string("this.op_localSetterRange" + index + "_");
            return false;
        }

        throw new AssertionError("index=" + index + ", signature=" + instr.signature);
    }

    public void createNodeChildReferenceForException(FlatNodeGenFactory flatNodeGenFactory, FrameState frameState, CodeTreeBuilder builder, List<CodeTree> values, NodeExecutionData execution,
                    NodeChildData child, LocalVariable var) {
        builder.string("null");
    }

    public CodeTree createTransferToInterpreterAndInvalidate() {
        if (isBoxingOperations) {
            CodeTreeBuilder b = CodeTreeBuilder.createBuilder();
            b.statement("$root.transferToInterpreterAndInvalidate()");
            return b.build();
        }
        return NodeGeneratorPlugs.super.createTransferToInterpreterAndInvalidate();
    }
}
