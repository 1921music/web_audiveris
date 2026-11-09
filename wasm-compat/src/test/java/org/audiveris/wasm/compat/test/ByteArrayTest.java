package org.audiveris.wasm.compat.test;

import java.lang.reflect.*;
import java.util.*;

/** Direct comparison with the JDK utility, including writes and failures before mutation. */
final class ByteArrayTest {
    private static Object invoke(Method method, Object[] args) throws Exception {
        try {
            Object result = method.invoke(null, args);
            if (result instanceof Float) { return Float.floatToRawIntBits((Float) result); }
            if (result instanceof Double) { return Double.doubleToRawLongBits((Double) result); }
            return result;
        } catch (InvocationTargetException failure) { return failure.getCause().getClass().getName(); }
    }
    static int run() throws Exception {
        int checks = 0;
        Random random = new Random(721);
        for (String order : new String[] {"Little", "Big"}) {
            String name = order.equals("Little") ? "ByteArrayLittleEndian" : "ByteArray";
            Class<?> actual = Class.forName("org.audiveris.wasm.compat.bytes." + name);
            Class<?> expected = Class.forName("jdk.internal.util." + name);
            for (Method method : actual.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) { continue; }
                Method oracle = expected.getMethod(method.getName(), method.getParameterTypes());
                for (int i = 0; i < 250; i++) {
                    byte[] first = i == 0 ? null : new byte[16];
                    if (first != null) { random.nextBytes(first); }
                    byte[] second = first == null ? null : first.clone();
                    int offset = i < 20 ? new int[] {-1, 0, 1, 8, 14, 15, 16, Integer.MAX_VALUE, Integer.MIN_VALUE, 3}[i % 10] : random.nextInt(17);
                    Object[] a = new Object[method.getParameterCount()], b = new Object[a.length];
                    a[0] = first; b[0] = second; a[1] = b[1] = offset;
                    if (a.length == 3) {
                        Class<?> type = method.getParameterTypes()[2];
                        Object value;
                        if (type == boolean.class) { value = random.nextBoolean(); }
                        else if (type == char.class) { value = (char) random.nextInt(); }
                        else if (type == short.class) { value = (short) random.nextInt(); }
                        else if (type == int.class) { value = random.nextInt(); }
                        else if (type == long.class) { value = random.nextLong(); }
                        else if (type == float.class) { value = Float.intBitsToFloat(random.nextInt()); }
                        else { value = Double.longBitsToDouble(random.nextLong()); }
                        a[2] = b[2] = value;
                    }
                    Object result = invoke(method, a), reference = invoke(oracle, b);
                    if (!Objects.equals(result, reference) || !Arrays.equals(first, second)) {
                        throw new AssertionError(method.getName() + " " + order + " offset=" + offset + ": " + result + " != " + reference);
                    }
                    checks++;
                }
            }
        }
        return checks;
    }
}
