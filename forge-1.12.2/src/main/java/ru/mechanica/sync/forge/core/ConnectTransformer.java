package ru.mechanica.sync.forge.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ConnectTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (!"net.minecraft.client.multiplayer.GuiConnecting".equals(transformedName) || bytes == null) return bytes;
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        int changed = 0;
        for (MethodNode method : type.methods) {
            if (!(method.name.equals("connect") || method.name.equals("func_146367_a"))
                || !method.desc.equals("(Ljava/lang/String;I)V")) continue;
            LabelNode proceed = new LabelNode();
            InsnList prefix = new InsnList();
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
            prefix.add(new VarInsnNode(Opcodes.ILOAD, 2));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "ru/mechanica/sync/forge/ClientBridge",
                "intercept", "(Ljava/lang/Object;Ljava/lang/String;I)Z", false));
            prefix.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            prefix.add(new InsnNode(Opcodes.RETURN));
            prefix.add(proceed);
            prefix.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(prefix);
            changed++;
        }
        if (changed != 1) throw new IllegalStateException("mechanica_connect_hook_missing");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }
}

