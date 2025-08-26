package school.sorokin.javabot;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.InputStreamResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/dl")
public class DownloadController {

    private final HostedFileService hostedFileService;

    public DownloadController(HostedFileService hostedFileService) {
        this.hostedFileService = hostedFileService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) throws IOException {
        HostedFileService.HostedFile hf = hostedFileService.get(id);
        if (hf == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Link expired or file not found");
        }
        Path p = hf.path;
        if (!Files.exists(p)) {
            return ResponseEntity.status(HttpStatus.GONE).body("File missing");
        }
        String ct = Files.probeContentType(p);
        if (ct == null) ct = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + hf.originalName.replace("\"", "_") + "\"");
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(hf.size)
                .contentType(MediaType.parseMediaType(ct))
                .body(new InputStreamResource(Files.newInputStream(p)));
    }
}

