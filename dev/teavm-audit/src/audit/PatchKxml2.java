package audit;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;

/** Preserve the original dependency and overlay only its supplementary-code-point append fix. */
public final class PatchKxml2 implements Opcodes {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]), output = Path.of(args[1]);
        String owner = "org/kxml2/io/KXmlParser", entry = owner + ".class";
        byte[] original;
        try (ZipFile zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry(entry))) { original = input.readAllBytes(); }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] replaced = {0};
        new ClassReader(original).accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("push") && descriptor.equals("(I)V")) {
                    if ((access & ACC_PRIVATE) == 0 || (access & ACC_STATIC) != 0) { throw new IllegalStateException("Review kxml2 push method"); }
                    replaced[0]++;
                    name = "__audiverisPushBmp";
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            @Override public void visitEnd() {
                MethodVisitor method = super.visitMethod(ACC_PRIVATE, "push", "(I)V", null, null);
                method.visitCode(); Label bmp = new Label(), valid = new Label();
                method.visitVarInsn(ILOAD, 1); method.visitLdcInsn(0xffff); method.visitJumpInsn(IF_ICMPLE, bmp);
                method.visitVarInsn(ILOAD, 1); method.visitLdcInsn(0x10ffff); method.visitJumpInsn(IF_ICMPLE, valid);
                method.visitTypeInsn(NEW, "org/xmlpull/v1/XmlPullParserException"); method.visitInsn(DUP);
                method.visitLdcInsn("Invalid supplementary character reference");
                method.visitMethodInsn(INVOKESPECIAL, "org/xmlpull/v1/XmlPullParserException", "<init>", "(Ljava/lang/String;)V", false);
                method.visitInsn(ATHROW); method.visitLabel(valid);
                method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ILOAD, 1); method.visitIntInsn(BIPUSH, 10);
                method.visitInsn(IUSHR); method.visitLdcInsn(0xd7c0); method.visitInsn(IADD);
                method.visitMethodInsn(INVOKESPECIAL, owner, "__audiverisPushBmp", "(I)V", false);
                method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ILOAD, 1); method.visitLdcInsn(0x3ff);
                method.visitInsn(IAND); method.visitLdcInsn(0xdc00); method.visitInsn(IADD);
                method.visitMethodInsn(INVOKESPECIAL, owner, "__audiverisPushBmp", "(I)V", false);
                method.visitInsn(RETURN); method.visitLabel(bmp);
                method.visitVarInsn(ALOAD, 0); method.visitVarInsn(ILOAD, 1);
                method.visitMethodInsn(INVOKESPECIAL, owner, "__audiverisPushBmp", "(I)V", false);
                method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd(); super.visitEnd();
            }
        }, 0);
        if (replaced[0] != 1) { throw new IllegalStateException("Expected exactly one kxml2 push method"); }
        Path destination = output.resolve(entry); Files.createDirectories(destination.getParent());
        byte[] patched = writer.toByteArray(); Files.write(destination, patched);
        Files.writeString(output.resolve("kxml-patch.json"), "{\n  \"artifact\":\"net.sf.kxml:kxml2:2.3.0\",\n"
                + "  \"patch\":\"append supplementary code points as UTF-16 surrogate pairs\",\n"
                + "  \"originalClassSha256\":\"" + hash(original) + "\",\n  \"patchedClassSha256\":\"" + hash(patched) + "\",\n"
                + "  \"originalJarSha256\":\"" + hash(Files.readAllBytes(jar)) + "\"\n}\n");
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
