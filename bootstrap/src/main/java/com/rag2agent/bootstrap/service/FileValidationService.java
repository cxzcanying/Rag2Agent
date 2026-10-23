package com.rag2agent.bootstrap.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 校验真实文件签名，并限制压缩包展开规模。 */
@Service
public class FileValidationService {

    private static final long MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    private static final long MAX_COMPRESSION_RATIO = 50;
    private static final Set<String> ZIP_TYPES = Set.of("docx", "pptx", "xlsx");

    public void validate(MultipartFile file, String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        byte[] head = head(file, 512);
        boolean valid = switch (ext) {
            case "pdf" -> startsWith(head, "%PDF".getBytes(StandardCharsets.US_ASCII));
            case "docx", "pptx", "xlsx" -> startsWith(head, new byte[] {'P', 'K'});
            case "png" -> startsWith(head, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            case "jpg", "jpeg" -> startsWith(head, new byte[] {(byte) 0xFF, (byte) 0xD8});
            case "tif", "tiff" -> startsWith(head, new byte[] {'I', 'I', 42, 0})
                    || startsWith(head, new byte[] {'M', 'M', 0, 42});
            case "msg" -> startsWith(head, new byte[] {
                    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
            case "html", "htm" -> textContains(head, "<html") || textContains(head, "<!doctype");
            case "eml" -> textContains(head, "from:") || textContains(head, "received:")
                    || textContains(head, "return-path:");
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("文件扩展名与实际内容不一致: " + ext);
        }
        if (ZIP_TYPES.contains(ext)) {
            inspectZip(file, ext);
        }
    }

    private void inspectZip(MultipartFile file, String extension) {
        long uncompressed = 0;
        int entries = 0;
        boolean contentTypes = false;
        boolean officeBody = false;
        try (InputStream input = file.getInputStream();
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 10_000) {
                    throw new IllegalArgumentException("压缩包文件项过多");
                }
                String name = entry.getName().replace('\\', '/');
                contentTypes |= "[Content_Types].xml".equals(name);
                officeBody |= switch (extension) {
                    case "docx" -> name.startsWith("word/");
                    case "pptx" -> name.startsWith("ppt/");
                    case "xlsx" -> name.startsWith("xl/");
                    default -> false;
                };
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    uncompressed += read;
                    if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw new IllegalArgumentException("压缩包展开后超过 100MB");
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("压缩结构读取失败", exception);
        }
        if (file.getSize() > 0
                && uncompressed / Math.max(1, file.getSize()) > MAX_COMPRESSION_RATIO) {
            throw new IllegalArgumentException("压缩比例异常");
        }
        if (!contentTypes || !officeBody) {
            throw new IllegalArgumentException("文件扩展名与实际 Office 内容不一致: " + extension);
        }
    }

    private byte[] head(MultipartFile file, int limit) {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes(limit);
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件头读取失败", exception);
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    private boolean textContains(byte[] value, String text) {
        return new String(value, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .contains(text);
    }
}
