package net.agrarius.ftbquestssync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * Reflection wrapper for NbtIo.writeCompressed/readCompressed.
 *
 * Why reflection: the 1.0.0 build was compiled against deobf MCP names
 * (`writeCompressed(CompoundTag, OutputStream)`), but at runtime Forge 1.20.1
 * exposes either the deobf name (mod compiled at dev time with -PUSE_MAPPINGS)
 * or the SRG name (`m_128947_`). NoSuchMethodError on agr1 (crash 18:32 on
 * 2026-05-24) was caused by the compile-time link looking for one and only
 * finding the other.
 *
 * This wrapper tries the deobf name first, falls back to SRG, caches the
 * Method handle, and lets both prod and dev runtimes work without rebuild.
 */
final class NbtCompat {

    private static volatile Method writeMethod;
    private static volatile Method readMethod;

    private NbtCompat() {
    }

    static void writeCompressed(CompoundTag tag, OutputStream out) throws Exception {
        if (writeMethod == null) {
            writeMethod = find("writeCompressed", "m_128947_", CompoundTag.class, OutputStream.class);
        }
        writeMethod.invoke(null, tag, out);
    }

    static CompoundTag readCompressed(InputStream in) throws Exception {
        if (readMethod == null) {
            readMethod = find("readCompressed", "m_128939_", InputStream.class);
        }
        return (CompoundTag) readMethod.invoke(null, in);
    }

    private static Method find(String deobfName, String srgName, Class<?>... params) throws NoSuchMethodException {
        try {
            Method m = NbtIo.class.getMethod(deobfName, params);
            FTBQuestsSync.LOGGER.info("NbtCompat using deobf NbtIo.{}", deobfName);
            return m;
        } catch (NoSuchMethodException ignored) {
            Method m = NbtIo.class.getMethod(srgName, params);
            FTBQuestsSync.LOGGER.info("NbtCompat using runtime NbtIo.{}", srgName);
            return m;
        }
    }
}
