package ru.mechanica.sync.forge.core;

import java.lang.reflect.Method;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ConnectTransformerChecks implements Opcodes {
    private static final String TARGET = "net/minecraft/client/multiplayer/GuiConnecting";
    private static final String BRIDGE = "ru/mechanica/sync/forge/ClientBridge";

    public static void main(String[] arguments) throws Exception {
        verify("connect");
        verify("func_146367_a");
        byte[] other = { 1, 2, 3 };
        if (new ConnectTransformer().transform("other", "other", other) != other) throw new AssertionError("other_class");
        try {
            new ConnectTransformer().transform(TARGET, TARGET.replace('/', '.'), target("changedMethod"));
            throw new AssertionError("missing_hook_accepted");
        } catch (IllegalStateException expected) {
            if (!"mechanica_connect_hook_missing".equals(expected.getMessage())) throw expected;
        }
        System.out.println("MECHANICA_SYNC_HOOK_CHECKS_PASS 4");
    }

    private static void verify(String methodName) throws Exception {
        byte[] patched = new ConnectTransformer().transform(TARGET, TARGET.replace('/', '.'), target(methodName));
        TestLoader loader = new TestLoader();
        Class<?> bridge = loader.define(BRIDGE, bridge());
        Class<?> connecting = loader.define(TARGET, patched);
        Object instance = connecting.newInstance();
        Method connect = connecting.getMethod(methodName, String.class, int.class);
        connect.invoke(instance, "127.0.0.1", 25565);
        if (connecting.getField("starts").getInt(instance) != 1) throw new AssertionError("normal_connection");
        bridge.getField("cancel").setBoolean(null, true);
        connect.invoke(instance, "127.0.0.1", 25565);
        if (connecting.getField("starts").getInt(instance) != 1) throw new AssertionError("cancelled_connection");
    }

    private static byte[] bridge() {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.visit(V1_8, ACC_PUBLIC, BRIDGE, null, "java/lang/Object", null);
        type.visitField(ACC_PUBLIC | ACC_STATIC, "cancel", "Z", null, null).visitEnd();
        MethodVisitor method = type.visitMethod(ACC_PUBLIC | ACC_STATIC, "intercept", "(Ljava/lang/Object;Ljava/lang/String;I)Z", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, BRIDGE, "cancel", "Z");
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static byte[] target(String methodName) {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.visit(V1_8, ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        type.visitField(ACC_PUBLIC, "starts", "I", null, null).visitEnd();
        MethodVisitor constructor = type.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = type.visitMethod(ACC_PUBLIC, methodName, "(Ljava/lang/String;I)V", null, null);
        method.visitCode();
        Label validPort = new Label();
        method.visitVarInsn(ILOAD, 2);
        method.visitJumpInsn(IFGE, validPort);
        method.visitInsn(RETURN);
        method.visitLabel(validPort);
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(DUP);
        method.visitFieldInsn(GETFIELD, TARGET, "starts", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTFIELD, TARGET, "starts", "I");
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static final class TestLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
