package com.pigtv.server.service;

import com.pigtv.server.common.BizException;
import com.pigtv.server.config.AppProps;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * APK 存储。
 *
 * <p>默认落本地磁盘并由 /files/** 提供下载；生产环境建议换腾讯云 COS
 * （免备案 + 不占服务器带宽）。切换时只需换掉本类实现，Controller 不用动。
 */
@Service
public class StorageService {

    private final AppProps props;

    public StorageService(AppProps props) {
        this.props = props;
    }

    public Map<String, Object> save(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("文件为空");
        }
        String origin = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
        if (!origin.toLowerCase().endsWith(".apk")) {
            throw BizException.badRequest("只接受 .apk 文件");
        }
        byte[] head = new byte[4];
        try (InputStream in = file.getInputStream()) {
            int n = in.read(head);
            if (n < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
                throw BizException.badRequest("文件不是合法的 APK（ZIP 头校验失败）");
            }
        }

        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path dir = Paths.get(props.getStorage().getDir(), month).toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String safe = origin.replaceAll("[^A-Za-z0-9._-]", "_");
        String name = System.currentTimeMillis() + "-" + safe;
        Path target = dir.resolve(name);

        String sha256;
        MessageDigest md = sha256Digest();
        try (InputStream in = file.getInputStream();
             DigestInputStream dis = new DigestInputStream(in, md)) {
            Files.copy(dis, target, StandardCopyOption.REPLACE_EXISTING);
            sha256 = hex(md.digest());
        }
        md.reset();

        String base = normalizedBase();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", name);
        out.put("relativePath", month + "/" + name);
        out.put("url", base + "/" + month + "/" + name);
        out.put("size", Files.size(target));
        out.put("sha256", sha256);
        return out;
    }

    /**
     * 按相对路径现拼下载地址。
     *
     * <p>数据库里只存相对路径，地址在读取时算 —— 这样把 STORAGE_BASE_URL 换成公网地址后，
     * 历史版本立刻跟着变，不用回表刷 file_url。
     */
    public String urlOf(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String rel = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return normalizedBase() + "/" + rel;
    }

    private String normalizedBase() {
        String base = props.getStorage().getBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public void delete(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        try {
            Path p = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize().resolve(fileName).normalize();
            if (p.startsWith(Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize())) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // 删除失败不阻断主流程
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
