package school.sorokin.javabot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HostedFileService {

    public static class HostedFile {
        public final Path path;
        public final long expiresAt;
        public final String originalName;
        public final long size;
        HostedFile(Path path, long ttlMillis, String originalName, long size) {
            this.path = path;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
            this.originalName = originalName;
            this.size = size;
        }
        public boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final Map<String, HostedFile> storage = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final String baseUrl;

    public HostedFileService(@Value("${download.host.ttl-minutes:60}") long ttlMinutes,
                             @Value("${download.base-url:}") String baseUrlProp) {
        this.ttlMillis = ttlMinutes * 60_000L;
        this.baseUrl = (baseUrlProp == null || baseUrlProp.isBlank()) ? "http://localhost:8080" : baseUrlProp.replaceAll("/$", "");
    }

    public String register(File file) {
        String id = UUID.randomUUID().toString().replace("-", "");
        storage.put(id, new HostedFile(file.toPath(), ttlMillis, file.getName(), file.length()));
        return id;
    }

    public HostedFile get(String id) {
        HostedFile hf = storage.get(id);
        if (hf == null) return null;
        if (hf.expired() || !Files.exists(hf.path)) {
            storage.remove(id);
            return null;
        }
        return hf;
    }

    public String buildUrl(String id) {
        return baseUrl + "/dl/" + id;
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        storage.entrySet().removeIf(e -> e.getValue().expired() || !Files.exists(e.getValue().path));
    }
}

