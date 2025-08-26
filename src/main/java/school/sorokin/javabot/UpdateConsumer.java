package school.sorokin.javabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UpdateConsumer implements LongPollingSingleThreadUpdateConsumer {

    private OkHttpTelegramClient telegramClient;
    private final String ytDlpPath;
    private String ffmpegPath; // кэшированный путь к ffmpeg или null
    private final HostedFileService hostedFileService; // добавлено

    // Кэш соответствий короткий ID -> оригинальный URL
    private static final Map<String, String> URL_CACHE = new ConcurrentHashMap<>();
    private static final Pattern AUDIO_CALLBACK_PATTERN = Pattern.compile("a_(mp3|orig)_([a-zA-Z0-9]{12})");
    private static final Map<Long, Boolean> LINK_PREFS = new ConcurrentHashMap<>(); // chatId -> showRawUrl

    private volatile boolean ffmpegReported = false;

    public UpdateConsumer(@Value("${telegram.bot.token}") String botToken,
                          @Value("${downloader.ytdlp.path:yt-dlp}") String ytDlpPath,
                          @Value("${ffmpeg.path:}") String ffmpegConfigured,
                          HostedFileService hostedFileService) { // добавлен параметр
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.ytDlpPath = ytDlpPath;
        this.ffmpegPath = resolveFfmpegPath(ffmpegConfigured);
        this.hostedFileService = hostedFileService; // присваивание
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update);
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
        }
    }

    private void handleTextMessage(Update update) {
        String messageText = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        try {
            switch (messageText) {
                case "/start" -> sendWelcomeMessage(chatId);
                case "/help" -> sendHelpMessage(chatId);
                case "/debug" -> sendDebug(chatId);
                case "/togglelink" -> toggleLinkPreference(chatId);
                default -> {
                    if (isValidUrl(messageText)) {
                        showDownloadOptions(chatId, messageText);
                    } else {
                        sendMessage(chatId, "❌ Неверная ссылка. Поддерживаются YouTube и TikTok ссылки.");
                    }
                }
            }
        } catch (Exception e) {
            sendMessage(chatId, "❌ Произошла ошибка при обработке сообщения.");
            e.printStackTrace();
        }
    }

    private void sendDebug(Long chatId) {
        StringBuilder sb = new StringBuilder();
        sb.append("yt-dlp path: ").append(ytDlpPath).append('\n');
        sb.append("yt-dlp available: ").append(isYtDlpAvailable()).append('\n');
        sb.append("ffmpeg configured path: ").append(ffmpegPath == null ? "<null>" : ffmpegPath).append('\n');
        sb.append("ffmpeg available: ").append(isFfmpegAvailable()).append('\n');
        sendMessage(chatId, sb.toString());
    }

    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();

        if (callbackData.startsWith("v_")) {
            String url = extractUrlFromCallback(callbackData); // v_<id>
            if (url == null) {
                sendMessage(chatId, "❌ Ссылка устарела. Отправьте её снова.");
                return;
            }
            downloadVideo(chatId, url);
            return;
        }
        Matcher m = AUDIO_CALLBACK_PATTERN.matcher(callbackData);
        if (m.matches()) {
            String fmt = m.group(1); // mp3 | orig
            String id = m.group(2);
            String url = URL_CACHE.get(id);
            if (url == null) {
                sendMessage(chatId, "❌ Ссылка устарела. Отправьте её снова.");
                return;
            }
            downloadAudio(chatId, url, fmt);
            return;
        }
        sendMessage(chatId, "❌ Неизвестное действие.");
    }

    private void sendWelcomeMessage(Long chatId) {
        String welcomeText = """
                🎬 *Добро пожаловать в YouTube & TikTok Downloader!*
                
                📹 Отправьте мне ссылку на видео или аудио из:
                • YouTube
                • TikTok
                
                И я помогу вам скачать видео или извлечь аудио!
                
                Используйте /help для получения дополнительной информации.
                """;

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(welcomeText)
                .parseMode("Markdown")
                .build();

        executeMessage(message);
    }

    private void sendHelpMessage(Long chatId) {
        String helpText = """
                📖 *Помощь по использованию бота:*
                
                1️⃣ Скопируйте ссылку на видео
                2️⃣ Отправьте её боту
                3️⃣ Выберите формат скачивания:
                   • 📹 Видео - полное видео
                   • 🎵 Аудио - только звуковая дорожка
                
                ⚡ *Поддерживаемые платформы:*
                • YouTube (youtube.com)
                • TikTok (tiktok.com)
                
                ⚠️ *Ограничения(Временно):*
                • Максимальная длительность: 10 минут
                • Максимальный размер файла: 50 МБ
                """;

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(helpText)
                .parseMode("Markdown")
                .build();

        executeMessage(message);
    }

    private void showDownloadOptions(Long chatId, String url) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        URL_CACHE.put(id, url);

        InlineKeyboardButton openUrlBtn = InlineKeyboardButton.builder()
                .text("🔗 Открыть ссылку")
                .url(url)
                .build();
        InlineKeyboardButton videoBtn = InlineKeyboardButton.builder()
                .text("📹 Видео")
                .callbackData("v_" + id)
                .build();
        InlineKeyboardButton audioMp3Btn = InlineKeyboardButton.builder()
                .text("🎵 MP3 128k")
                .callbackData("a_mp3_" + id)
                .build();
        InlineKeyboardButton audioOrigBtn = InlineKeyboardButton.builder()
                .text("🎵 Оригинал")
                .callbackData("a_orig_" + id)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(openUrlBtn))
                .keyboardRow(new InlineKeyboardRow(videoBtn))
                .keyboardRow(new InlineKeyboardRow(audioMp3Btn, audioOrigBtn))
                .build();

        String body = "🔗 Ссылка получена! Выберите формат или откройте оригинал:\n" + url;
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(body)
                .disableWebPagePreview(true)
                .replyMarkup(keyboard)
                .build();

        executeMessage(message);
    }

    private void downloadVideo(Long chatId, String url) {
        sendMessage(chatId, "⏬ Начинаю скачивание видео...");
        CompletableFuture.runAsync(() -> {
            try {
                String fileName = downloadContent(chatId, url, "video");
                if (fileName != null) {
                    sendFile(chatId, fileName, "📹 Ваше видео готово!");
                    File f = new File(fileName);
                    if (f.exists() && f.length() <= 50L * 1024 * 1024) {
                        deleteFile(fileName);
                    }
                } else {
                    sendMessage(chatId, "❌ Не удалось скачать видео.");
                }
            } catch (Exception e) {
                sendMessage(chatId, "�� Ошибка при скачивании видео.");
                e.printStackTrace();
            }
        });
    }

    private void downloadAudio(Long chatId, String url, String fmt) {
        sendMessage(chatId, "⏬ Аудио — начинаю...");
        CompletableFuture.runAsync(() -> {
            try {
                String downloaded = downloadBestAudio(chatId, url);
                if (downloaded == null) return;
                sendFile(chatId, downloaded, "🎵 Аудио готово!");
                File f = new File(downloaded);
                if (f.exists() && f.length() <= 50L * 1024 * 1024) {
                    deleteFile(downloaded);
                }
            } catch (Exception e) {
                sendMessage(chatId, "❌ Ошибка при скачивании аудио.");
                e.printStackTrace();
            }
        });
    }

    // --- ffmpeg detection helpers ---
    private String resolveFfmpegPath(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (fileExecutable(configured) || versionOk(configured)) return configured;
        }
        String env = System.getenv("FFMPEG_PATH");
        if (env != null && !env.isBlank()) {
            if (fileExecutable(env) || versionOk(env)) return env;
        }
        // common locations
        List<String> candidates = List.of(
                "ffmpeg",
                "/opt/homebrew/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "/usr/bin/ffmpeg"
        );
        for (String c : candidates) {
            if (fileExecutable(c) || versionOk(c)) return c;
        }
        return null;
    }

    private boolean fileExecutable(String pathStr) {
        try {
            Path p = Path.of(pathStr);
            return java.nio.file.Files.exists(p) && java.nio.file.Files.isExecutable(p);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean versionOk(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "-version").start();
            boolean ok = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFfmpegAvailable() {
        if (ffmpegPath != null) return true;
        ffmpegPath = resolveFfmpegPath(null);
        if (ffmpegPath != null && !ffmpegReported) {
            ffmpegReported = true; // можно при первом обнаружении вывести отладку; отключено чтобы не шуметь
        }
        return ffmpegPath != null;
    }

    private String ensureMp3(Long chatId, String fileName) {
        if (fileName.endsWith(".mp3")) return fileName;
        if (!isFfmpegAvailable()) {
            sendMessage(chatId, "⚠️ ffmpeg недоступен, отправляю исходный формат.");
            return fileName;
        }
        try {
            String target = fileName.replaceFirst("\\.[^.]+$", "") + "_conv.mp3";
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y", "-i", fileName, "-vn", "-ac", "2", "-ar", "44100", "-b:a", "128k", target
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) { while (br.readLine() != null) {} }
            p.waitFor();
            if (p.exitValue() == 0 && Files.exists(Paths.get(target))) {
                return target;
            } else {
                sendMessage(chatId, "⚠️ Не удалось конвертировать в mp3 (ffmpeg ошибка). Отправляю исходный файл.");
                return fileName;
            }
        } catch (Exception e) {
            sendMessage(chatId, "⚠️ Ошибка конвертации в mp3, отправляю исходный файл.");
            return fileName;
        }
    }

    private String downloadContent(Long chatId, String url, String type) {
        try {
            if (!isYtDlpAvailable()) {
                sendMessage(chatId, "⚠️ yt-dlp не установлен или недоступен.");
                return null;
            }
            boolean needMp3 = "audio".equals(type);
            boolean ffmpegAvailable = !needMp3 || isFfmpegAvailable();
            if (needMp3 && !ffmpegAvailable) {
                sendMessage(chatId, "⚠️ ffmpeg не найден — будет загружен исходный аудио-файл без конвертации.");
            }

            String baseName = "download_" + System.currentTimeMillis();
            String targetFile = baseName + ("video".equals(type) ? ".mp4" : ".mp3");

            Set<String> before = snapshotFiles();
            ProcessBuilder pb = new ProcessBuilder(buildCommandEnhanced(url, type, targetFile, ffmpegAvailable));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder log = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(Duration.ofMinutes(7).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                sendMessage(chatId, "⏱ Превышено время ожидания.");
                return null;
            }
            int exit = process.exitValue();
            if (exit != 0) {
                String logStr = log.toString();
                if (logStr.toLowerCase().contains("ffmpeg")) {
                    sendMessage(chatId, "⚠️ Требуется ffmpeg для конвертации в mp3: установите ffmpeg.");
                } else {
                    sendMessage(chatId, "❌ Ошибка скачивания (код " + exit + ").");
                }
                return null;
            }
            // Прямой файл (для видео или mp3 после конвертации)
            if (Files.exists(Paths.get(targetFile))) {
                // если аудио без ffmpeg: имя будет baseName.<origExt>; targetFile (mp3) не существует — обработка ниже
            }
            Set<String> after = snapshotFiles();
            after.removeAll(before);
            String chosen = after.stream().filter(f -> f.startsWith(baseName + ".")).findFirst().orElse(null);
            if (chosen != null) {
                if (needMp3 && ffmpegAvailable && !chosen.endsWith(".mp3")) {
                    sendMessage(chatId, "⚠️ Получен файл без конвертации в mp3.");
                }
                return chosen;
            }
            sendMessage(chatId, "❌ Файл не найден после скачивания.");
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, "❌ Внутренняя ошибка при скачивании.");
        }
        return null;
    }

    private boolean isYtDlpAvailable() {
        try {
            Process p = new ProcessBuilder(ytDlpPath, "--version").start();
            boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Set<String> snapshotFiles() {
        try (var stream = Files.list(Paths.get("."))) {
            Set<String> set = new HashSet<>();
            stream.filter(p -> !Files.isDirectory(p)).forEach(p -> set.add(p.getFileName().toString()));
            return set;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private java.util.List<String> buildCommandEnhanced(String url, String type, String targetFile, boolean ffmpegAvailable) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ytDlpPath);
        if ("video".equals(type)) {
            cmd.addAll(java.util.List.of("-f", "best[height<=720]", "-o", targetFile, url));
        } else { // audio
            // targetFile = baseName + ".mp3" (если ffmpegAvailable) иначе baseName + ".mp3" но потом будет другой ext
            String base = targetFile.endsWith(".mp3") ? targetFile.substring(0, targetFile.length() - 4) : targetFile;
            if (ffmpegAvailable) {
                cmd.addAll(java.util.List.of("-f", "bestaudio", "-x", "--audio-format", "mp3", "-o", base + ".%(ext)s", url));
            } else {
                // без ffmpeg: просто bestaudio в формате исходника, но имя всё равно baseName.ext чтобы обнаружить
                cmd.addAll(java.util.List.of("-f", "bestaudio", "-o", base + ".%(ext)s", url));
            }
        }
        return cmd;
    }

    private void sendFile(Long chatId, String fileName, String caption) {
        try {
            File file = new File(fileName);
            long fileSize = file.length();
            if (fileSize > 50 * 1024 * 1024) { // 50MB limit -> выдаём локальную ссылку
                String id = hostedFileService.register(file);
                String url = hostedFileService.buildUrl(id);
                sendLinkMessage(chatId, "Файл >50МБ. Нажмите кнопку для скачивания (ссылка временная):", "⬇️ Скачать", url);
                return;
            }

            SendDocument document = SendDocument.builder()
                    .chatId(chatId.toString())
                    .document(new InputFile(file))
                    .caption(caption)
                    .build();

            telegramClient.execute(document);
        } catch (TelegramApiException e) {
            sendMessage(chatId, "❌ Ошибка при отправке файла.");
            e.printStackTrace();
        }
    }

    private void toggleLinkPreference(Long chatId) {
        boolean current = LINK_PREFS.getOrDefault(chatId, true);
        boolean next = !current;
        LINK_PREFS.put(chatId, next);
        sendMessage(chatId, next ? "Теперь ссылка будет дублироваться текстом." : "Теперь показываю только кнопку без текстовой ссылки.");
    }

    private void sendLinkMessage(Long chatId, String text, String buttonText, String url) {
        boolean showRaw = LINK_PREFS.getOrDefault(chatId, true);
        if (!isValidButtonUrl(url)) {
            // Fallback: просто текстовая ссылка без inline-кнопки (иначе Telegram вернёт 400)
            String body = showRaw ? text + "\n" + url : text;
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(body)
                    .disableWebPagePreview(true)
                    .build();
            executeMessage(msg);
            return;
        }
        InlineKeyboardButton btn = InlineKeyboardButton.builder().text(buttonText).url(url).build();
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(btn))
                .build();
        String body = showRaw ? text + "\n[" + buttonText + "](" + url + ")" : text;
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(body)
                .parseMode("Markdown")
                .replyMarkup(markup)
                .disableWebPagePreview(true)
                .build();
        executeMessage(message);
    }

    private boolean isValidButtonUrl(String url) {
        if (url == null || url.isBlank()) return false;
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return false;
        String lower = url.toLowerCase();
        // Блокируем localhost и приватные диапазоны, которые не доступны Telegram
        if (lower.contains("localhost") || lower.contains("127.0.0.1")) return false;
        if (lower.matches("https?://10\\..*")) return false;
        if (lower.matches("https?://192\\.168\\..*")) return false;
        if (lower.matches("https?://172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) return false;
        return true;
    }

    // Загрузка на 0x0.st (анонимно)
    private String uploadTo0x0(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-F", "file=@" + file.getAbsolutePath(), "https://0x0.st");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sb.append(line); }
            p.waitFor();
            String resp = sb.toString().trim();
            if (resp.startsWith("https://0x0.st/")) return resp;
            return "ERROR:" + resp;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // Загрузка на catbox.moe (анонимно)
    private String uploadToCatbox(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "curl",
                    "-F", "reqtype=fileupload",
                    "-F", "fileToUpload=@" + file.getAbsolutePath(),
                    "https://catbox.moe/user/api.php"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String resp = sb.toString().trim();
            // Успешный ответ — прямая ссылка, иначе текст ошибки
            if (resp.startsWith("https://")) return resp;
            return "ERROR:" + resp;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String uploadToFileIo(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "-F", "file=@" + file.getAbsolutePath(), "https://file.io");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            String result = sb.toString().trim();
            // file.io возвращает JSON вида: {"success":true,"link":"https://file.io/xxxxxx",...}
            if (result.contains("https://file.io/")) {
                int start = result.indexOf("https://file.io/");
                int end = result.indexOf('"', start);
                if (end > start) {
                    return result.substring(start, end);
                } else {
                    return result.substring(start);
                }
            } else {
                return "ERROR:" + result;
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String uploadToGoFile(File file) {
        try {
            ProcessBuilder pbServer = new ProcessBuilder("curl", "-s", "https://api.gofile.io/getServer");
            pbServer.redirectErrorStream(true);
            Process pServer = pbServer.start();
            StringBuilder sbServer = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(pServer.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sbServer.append(line); }
            pServer.waitFor();
            String serverJson = sbServer.toString();
            // Получение сервера (замена regex на простой парсинг)
            String server = extractJsonString(serverJson, "server");
            if (server == null || server.isBlank()) return "ERROR: gofile getServer raw=" + serverJson;
            String uploadUrl = "https://" + server + ".gofile.io/uploadFile";
            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "-F", "file=@" + file.getAbsolutePath(), uploadUrl);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sb.append(line); }
            p.waitFor();
            String result = sb.toString().trim();
            if (result.contains("https://gofile.io/d/")) {
                int start = result.indexOf("https://gofile.io/d/");
                int end = result.indexOf('"', start);
                if (end > start) return result.substring(start, end); else return result.substring(start);
            }
            return "ERROR:" + result;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String uploadToPixelDrain(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "curl","-s","-X","POST","-F","file=@" + file.getAbsolutePath(),"https://pixeldrain.com/api/file"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String json = sb.toString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (m.find()) {
                return "https://pixeldrain.com/u/" + m.group(1);
            }
            return "ERROR:" + json.trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private boolean isValidUrl(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be") || url.contains("tiktok.com");
    }

    private String extractUrlFromCallback(String callbackData) {
        int idx = callbackData.indexOf('_');
        if (idx < 0 || idx == callbackData.length() - 1) return null;
        String id = callbackData.substring(idx + 1);
        return URL_CACHE.get(id);
    }

    private void deleteFile(String fileName) {
        try {
            Files.deleteIfExists(Paths.get(fileName));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String downloadBestAudio(Long chatId, String url) {
        try {
            if (!isYtDlpAvailable()) {
                sendMessage(chatId, "⚠️ yt-dlp недоступен.");
                return null;
            }
            String baseName = "download_" + System.currentTimeMillis();
            String pattern = baseName + ".%(ext)s";
            Set<String> before = snapshotFiles();
            ProcessBuilder pb = new ProcessBuilder(ytDlpPath, "-f", "bestaudio", "-o", pattern, url);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) {}
            }
            boolean finished = p.waitFor(6, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                sendMessage(chatId, "⏱ Таймаут скачивания аудио.");
                return null;
            }
            if (p.exitValue() != 0) {
                sendMessage(chatId, "❌ Ошибка скачивания аудио (код " + p.exitValue() + ").");
                return null;
            }
            Set<String> after = snapshotFiles();
            after.removeAll(before);
            return after.stream().filter(f -> f.startsWith(baseName + ".")).findFirst().orElseGet(() -> {
                sendMessage(chatId, "❌ Аудио файл не найден.");
                return null;
            });
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, "❌ Внутренняя ошибка при скачивании аудио.");
            return null;
        }
    }

    /**
     * Отправка текстового сообщения в чат
     */
    private void sendMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
        executeMessage(message);
    }

    /**
     * Выполнение отправки сообщения через Telegram API
     */
    private void executeMessage(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private boolean isOk0x0(String url) {
        return url != null && url.startsWith("https://0x0.st/");
    }

    private String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        int k = json.indexOf('"' + key + '"');
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;
        int first = json.indexOf('"', colon + 1);
        if (first < 0) return null;
        int second = json.indexOf('"', first + 1);
        if (second < 0) return null;
        return json.substring(first + 1, second);
    }
}
