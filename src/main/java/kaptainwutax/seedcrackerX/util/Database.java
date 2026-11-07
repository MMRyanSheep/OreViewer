package kaptainwutax.seedcrackerX.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.finder.Finder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class Database {
   private static final String DATABASE_POST_URL = "https://script.google.com/macros/s/AKfycbye87L-fEYq2EkgczvhKb_kGecp5wL1oX95vg45TRSwNvpv7K-53zoInGTeI1FZ0kv7DA/exec";
   private static final String DATABASE_URL = "https://docs.google.com/spreadsheets/d/1tuQiE-0leW88em9OHbZnH-RFNhVqgoHhIt9WQbeqqWw/export?format=csv&gid=0";
   private static final String BACKEND_SEED_URL = "https://kami.wltk.beer/api/seed.php";
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build();
   private static final Duration TIMEOUT = Duration.ofSeconds(10L);
   private static final String SEEDCRACKERX_USER_AGENT = "SeedcrackerX mod";
   private static final File SEED_RECORDS_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "seed_records.txt");
   private static final DateTimeFormatter TXT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private static final Map<String, Database.SeedRecord> connectionToSeed = new HashMap<>();
   private static final Map<Long, Database.SeedRecord> hashedSeedToSeed = new HashMap<>();
   private static final Map<Database.SeedRecord, String> recordToConnection = new HashMap<>();

   public static Component joinFakeServerForAuth() {
      try {
         Minecraft client = Minecraft.getInstance();
         client.services().sessionService().joinServer(client.getUser().getProfileId(), client.getUser().getAccessToken(), "seedcrackerx");
         return null;
      } catch (AuthenticationUnavailableException authenticationUnavailableException) {
         return Component.translatable("disconnect.loginFailedInfo", new Object[]{Component.translatable("disconnect.loginFailedInfo.serversUnavailable")});
      } catch (InsufficientPrivilegesException authenticationUnavailableException) {
         return Component.translatable("disconnect.loginFailedInfo", new Object[]{Component.translatable("disconnect.loginFailedInfo.insufficientPrivileges")});
      } catch (AuthenticationException authenticationUnavailableException) {
         return Component.translatable("disconnect.loginFailedInfo", new Object[]{authenticationUnavailableException.getMessage()});
      }
   }

   @Nullable
   public static Long getSeed(String connection, long hashedSeed) {
      Database.SeedRecord record = getSeedRecord(connection, hashedSeed);
      return record == null ? null : record.seed;
   }

   @Nullable
   public static Database.SeedRecord getSeedRecord(String connection, long hashedSeed) {
      Database.SeedRecord record = connectionToSeed.get(connection);
      return record != null ? record : hashedSeedToSeed.get(hashedSeed);
   }

   @Nullable
   public static Database.SeedRecord lookupByIp(String serverIp) {
      if (serverIp != null && !serverIp.isBlank()) {
         for (Entry<Database.SeedRecord, String> entry : recordToConnection.entrySet()) {
            String conn = entry.getValue();
            if (conn != null && conn.contains(serverIp)) {
               return entry.getKey();
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public static void handleDatabaseCall(Long seed) {
      Minecraft client = Minecraft.getInstance();
      String serverIp = client.getConnection().getConnection().getRemoteAddress().toString();
      String dimension = Finder.inferDimension(client.level.dimensionType());
      String version = getServerVersion();
      Map<String, Object> data = new HashMap<>();
      data.put("serverIp", serverIp);
      data.put("dimension", dimension);
      data.put("seed", seed + "L");
      data.put("version", version);
      data.put("username", client.player.getName().getString());
      data.put("hash", Config.get().anonymusSubmits ? 1 : 0);
      HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://script.google.com/macros/s/AKfycbye87L-fEYq2EkgczvhKb_kGecp5wL1oX95vg45TRSwNvpv7K-53zoInGTeI1FZ0kv7DA/exec")
         )
         .timeout(TIMEOUT)
         .POST(BodyPublishers.ofString(buildQuery(data)))
         .setHeader("User-Agent", "SeedcrackerX mod")
         .header("Content-Type", "application/x-www-form-urlencoded")
         .build();

      try {
         HttpResponse<String> response = HTTP_CLIENT.send(request, BodyHandlers.ofString());
         if (response.statusCode() == 302) {
            Log.warn("database.success");
         } else {
            Log.warn("database.fail");
         }
      } catch (IOException | InterruptedException e) {
         Log.warn("database.fail");
      }

      uploadToBackend(serverIp, seed, dimension, version);
      appendSeedRecord(serverIp, dimension, seed, version);
   }

   private static void uploadToBackend(String serverIp, long seed, String dimension, String version) {
      Map<String, Object> form = new HashMap<>();
      form.put("ip", serverIp);
      form.put("seed", String.valueOf(seed));
      form.put("world", dimension);
      form.put("version", version);
      HttpRequest req = HttpRequest.newBuilder(URI.create("https://kami.wltk.beer/api/seed.php"))
         .timeout(TIMEOUT)
         .POST(BodyPublishers.ofString(buildQuery(form)))
         .setHeader("User-Agent", "SeedcrackerX mod")
         .header("Content-Type", "application/x-www-form-urlencoded")
         .build();
      HTTP_CLIENT.sendAsync(req, BodyHandlers.ofString()).whenComplete((resp, err) -> {});
   }

   public static CompletableFuture<Database.SeedRecord> fetchSeedFromBackendAsync(String serverIp, String serverHost) {
      if (serverIp != null && !serverIp.isBlank() || serverHost != null && !serverHost.isBlank()) {
         StringBuilder url = new StringBuilder("https://kami.wltk.beer/api/seed.php").append("?");
         boolean first = true;
         if (serverIp != null && !serverIp.isBlank()) {
            url.append("ip=").append(URLEncoder.encode(serverIp, StandardCharsets.UTF_8));
            first = false;
         }

         if (serverHost != null && !serverHost.isBlank()) {
            if (!first) {
               url.append("&");
            }

            url.append("host=").append(URLEncoder.encode(serverHost, StandardCharsets.UTF_8));
         }

         HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(TIMEOUT).GET().setHeader("User-Agent", "SeedcrackerX mod").build();
         return HTTP_CLIENT.sendAsync(req, BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() != 200) {
               return null;
            }

            try {
               JsonObject jo = JsonParser.parseString(resp.body()).getAsJsonObject();
               if (jo.get("code").getAsInt() != 0) {
                  return null;
               }

               long seed = Long.parseLong(jo.get("seed").getAsString());
               String world = jo.has("world") ? jo.get("world").getAsString() : "";
               String ver = jo.has("version") ? jo.get("version").getAsString() : "";
               return new Database.SeedRecord(seed, ver, world, 0L);
            } catch (Exception e) {
               return null;
            }
         }).exceptionally(ex -> null);
      } else {
         return CompletableFuture.completedFuture(null);
      }
   }

   public static void fetchSeeds() {
      fetchSeedsAsync();
   }

   public static CompletableFuture<Void> fetchSeedsAsync() {
      HttpRequest request = HttpRequest.newBuilder(
            URI.create("https://docs.google.com/spreadsheets/d/1tuQiE-0leW88em9OHbZnH-RFNhVqgoHhIt9WQbeqqWw/export?format=csv&gid=0")
         )
         .timeout(TIMEOUT)
         .GET()
         .setHeader("User-Agent", "SeedcrackerX mod")
         .build();
      return HTTP_CLIENT.sendAsync(request, BodyHandlers.ofString()).thenApply(HttpResponse::body).thenAccept(Database::parseCsv);
   }

   private static String buildQuery(Map<String, ?> data) {
      return data.entrySet()
         .stream()
         .map(
            entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
               + "="
               + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8)
         )
         .collect(Collectors.joining("&"));
   }

   private static void parseCsv(String csv) {
      connectionToSeed.clear();
      hashedSeedToSeed.clear();
      recordToConnection.clear();
      if (csv != null && !csv.isBlank()) {
         Arrays.stream(csv.split("\n")).skip(1L).forEach(row -> {
            try {
               String[] seedEntry = row.split(",");
               if (seedEntry.length < 3) {
                  return;
               }

               String connection = seedEntry[0];
               String dimension = seedEntry.length > 1 ? seedEntry[1].trim() : "";
               String seedString = seedEntry[2].trim();
               if (seedString.endsWith("L") || seedString.endsWith("l")) {
                  seedString = seedString.substring(0, seedString.length() - 1);
               }

               long seed = Long.parseLong(seedString);
               String version = seedEntry.length > 3 ? seedEntry[3].trim() : "";
               long hashedSeed = seedEntry.length > 6 ? Long.parseLong(seedEntry[6].trim()) : 0L;
               Database.SeedRecord record = new Database.SeedRecord(seed, version, dimension, hashedSeed);
               connectionToSeed.put(connection, record);
               if (hashedSeed != 0L) {
                  hashedSeedToSeed.put(hashedSeed, record);
               }

               recordToConnection.put(record, connection);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException var11) {
            }
         });
      }
   }

   private static String getServerVersion() {
      try {
         Object versionData = SharedConstants.getCurrentVersion();

         try {
            return (String)versionData.getClass().getMethod("getName").invoke(versionData);
         } catch (NoSuchMethodException var5) {
            try {
               return (String)versionData.getClass().getMethod("name").invoke(versionData);
            } catch (NoSuchMethodException var4) {
               try {
                  Object id = versionData.getClass().getMethod("getId").invoke(versionData);
                  return String.valueOf(id);
               } catch (NoSuchMethodException var3) {
               }
            }
         }
      } catch (Throwable var6) {
      }

      try {
         return Config.get().getVersion().name;
      } catch (Throwable ignored) {
         return "";
      }
   }

   private static void appendSeedRecord(String serverIp, String dimension, long seed, String version) {
      try {
         SEED_RECORDS_FILE.getParentFile().mkdirs();
         String line = String.format(
            "[%s] ip=%s | world=%s | seed=%d | version=%s%n", LocalDateTime.now().format(TXT_TIME_FMT), serverIp, dimension, seed, version
         );
         Files.writeString(SEED_RECORDS_FILE.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException var6) {
      }
   }

   public static List<Database.SeedRecord> allRecords() {
      return new ArrayList<>(recordToConnection.keySet());
   }

   public static final class SeedRecord {
      public final long seed;
      public final String version;
      public final String dimension;
      public final long hashedSeed;

      public SeedRecord(long seed, String version, String dimension, long hashedSeed) {
         this.seed = seed;
         this.version = version == null ? "" : version;
         this.dimension = dimension == null ? "" : dimension;
         this.hashedSeed = hashedSeed;
      }
   }
}
