package kaptainwutax.seedcrackerX.cracker.bedrock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class BedrockPluginLoader {
   public static final String PLUGIN_CLASS = "kaptainwutax.seedcrackerX.cracker.bedrock.BedrockCracker";
   private static final Object LOCK = new Object();
   private static volatile int installedHash;
   private static volatile Map<String, byte[]> installedClasses;

   private BedrockPluginLoader() {
   }

   public static void install(byte[] pluginZip) throws IOException {
      int hash = Arrays.hashCode(pluginZip);
      if (installedClasses == null || installedHash != hash) {
         synchronized (LOCK) {
            if (installedClasses == null || installedHash != hash) {
               Map<String, byte[]> classes = readClasses(pluginZip);
               if (!classes.containsKey("kaptainwutax.seedcrackerX.cracker.bedrock.BedrockCracker".replace('.', '/') + ".class")) {
                  throw new IOException("插件包缺少破译实现类");
               }

               installedClasses = classes;
               installedHash = hash;
            }
         }
      }
   }

   public static boolean isInstalled() {
      return installedClasses != null;
   }

   public static BedrockSearcher create(List<BedrockBlock> blocks, int threads, BedrockSearcher.Output output) throws Exception {
      Map<String, byte[]> classes = installedClasses;
      if (classes == null) {
         throw new IllegalStateException("尚未装载破译插件");
      }

      ClassLoader loader = defineClasses(classes);
      Class<?> impl = Class.forName("kaptainwutax.seedcrackerX.cracker.bedrock.BedrockCracker", true, loader);
      Constructor<?> constructor = impl.getConstructor(List.class, int.class, BedrockMode.class, BedrockSearcher.Output.class);
      return (BedrockSearcher)constructor.newInstance(blocks, threads, BedrockMode.NORMAL, output);
   }

   private static ClassLoader defineClasses(final Map<String, byte[]> classes) {
      return new ClassLoader(BedrockPluginLoader.class.getClassLoader()) {
         {
            for (Entry<String, byte[]> e : classes.entrySet()) {
               String name = e.getKey().substring(0, e.getKey().length() - ".class".length()).replace('/', '.');
               byte[] bytes = e.getValue();

               try {
                  this.defineClass(name, bytes, 0, bytes.length);
               } catch (LinkageError var8) {
               }
            }
         }
      };
   }

   private static Map<String, byte[]> readClasses(byte[] pluginZip) throws IOException {
      Map<String, byte[]> out = new HashMap<>();
      InputStream in = new ByteArrayInputStream(pluginZip);

      ZipEntry entry;
      try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
         while ((entry = zip.getNextEntry()) != null) {
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
               out.put(entry.getName(), zip.readAllBytes());
            }
         }
      } catch (Throwable var9) {
         try {
            in.close();
         } catch (Throwable var6) {
            var9.addSuppressed(var6);
         }

         throw var9;
      }

      in.close();
      if (out.isEmpty()) {
         throw new IOException("插件包为空");
      } else {
         return out;
      }
   }
}
