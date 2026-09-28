package ru.mechanica.sync.forge.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class ConnectTransformerChecks implements Opcodes {
    private static final String TARGET = "net/minecraft/client/multiplayer/GuiConnecting";
    private static final String BRIDGE = "ru/mechanica/sync/forge/ClientBridge";
    private static final String STATUS = "net/minecraft/network/status/server/SPacketServerInfo";
    private static final String BUFFER = "net/minecraft/network/PacketBuffer";
    private static final String ADVERTISEMENT = "ru/mechanica/sync/server/StatusAdvertisement";
    private static final String JSON = "{\"description\":\"test\",\"modinfo\":{\"type\":\"FML\"}}";

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
        verifyStatus("writePacketData", "writeString");
        verifyStatus("func_148840_b", "func_180714_a");
        verifyMissingStatus("changedMethod", "writeString");
        verifyMissingStatus("writePacketData", "changedWrite");
        verifyRealStatus();
        if (new ConnectTransformer().transform(STATUS, STATUS.replace('/', '.'), null) != null) throw new AssertionError("null_class");
        System.out.println("MECHANICA_SYNC_HOOK_CHECKS_PASS 10");
    }

    private static void verifyStatus(String methodName, String writeName) throws Exception {
        byte[] patched = new ConnectTransformer().transform(STATUS, STATUS.replace('/', '.'), statusTarget(methodName, writeName));
        TestLoader loader = new TestLoader();
        Class<?> advertisement = loader.define(ADVERTISEMENT, advertisement());
        Class<?> buffer = loader.define(BUFFER, buffer(writeName));
        Class<?> status = loader.define(STATUS, patched);
        Object destination = buffer.newInstance();
        status.getMethod(methodName, buffer).invoke(status.newInstance(), destination);
        if (!(JSON + " decorated").equals(buffer.getField("value").get(destination))) throw new AssertionError("status_json");
        if (advertisement.getField("calls").getInt(null) != 1) throw new AssertionError("status_decoration_count");
    }

    private static void verifyMissingStatus(String methodName, String writeName) {
        try {
            new ConnectTransformer().transform(STATUS, STATUS.replace('/', '.'), statusTarget(methodName, writeName));
            throw new AssertionError("missing_status_hook_accepted");
        } catch (IllegalStateException expected) {
            if (!"mechanica_status_hook_missing".equals(expected.getMessage())) throw expected;
        }
    }

    private static void verifyRealStatus() throws Exception {
        byte[] bytes;
        try (InputStream input = ConnectTransformerChecks.class.getClassLoader().getResourceAsStream(STATUS + ".class");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new AssertionError("real_status_class_missing");
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            bytes = output.toByteArray();
        }
        ClassNode type = new ClassNode();
        new ClassReader(new ConnectTransformer().transform(STATUS, STATUS.replace('/', '.'), bytes)).accept(type, 0);
        int decorated = 0;
        for (MethodNode method : type.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals(ADVERTISEMENT) && call.name.equals("decorate")) decorated++;
        }
        if (decorated != 1) throw new AssertionError("real_status_hook_count");
    }

    private static byte[] advertisement() {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.visit(V1_8, ACC_PUBLIC, ADVERTISEMENT, null, "java/lang/Object", null);
        type.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor method = type.visitMethod(ACC_PUBLIC | ACC_STATIC, "decorate", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, ADVERTISEMENT, "calls", "I");
        method.visitInsn(ICONST_1);
        method.visitInsn(IADD);
        method.visitFieldInsn(PUTSTATIC, ADVERTISEMENT, "calls", "I");
        method.visitVarInsn(ALOAD, 0);
        method.visitLdcInsn(" decorated");
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static byte[] buffer(String writeName) {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.visit(V1_8, ACC_PUBLIC, BUFFER, null, "java/lang/Object", null);
        type.visitField(ACC_PUBLIC, "value", "Ljava/lang/String;", null, null).visitEnd();
        constructor(type);
        MethodVisitor method = type.visitMethod(ACC_PUBLIC, writeName, "(Ljava/lang/String;)L" + BUFFER + ";", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitFieldInsn(PUTFIELD, BUFFER, "value", "Ljava/lang/String;");
        method.visitVarInsn(ALOAD, 0);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static byte[] statusTarget(String methodName, String writeName) {
        ClassWriter type = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.visit(V1_8, ACC_PUBLIC, STATUS, null, "java/lang/Object", null);
        constructor(type);
        MethodVisitor method = type.visitMethod(ACC_PUBLIC, methodName, "(L" + BUFFER + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 1);
        method.visitLdcInsn(JSON);
        method.visitMethodInsn(INVOKEVIRTUAL, BUFFER, writeName, "(Ljava/lang/String;)L" + BUFFER + ";", false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static void constructor(ClassWriter type) {
        MethodVisitor method = type.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
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
