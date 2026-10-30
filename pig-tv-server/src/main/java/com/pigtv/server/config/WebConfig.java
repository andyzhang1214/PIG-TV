package com.pigtv.server.config;

import com.pigtv.server.security.AdminInterceptor;
import com.pigtv.server.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final AppProps props;

    public WebConfig(AuthInterceptor authInterceptor, AdminInterceptor adminInterceptor, AppProps props) {
        this.authInterceptor = authInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/update/check",
                        "/api/device/code",
                        "/api/device/poll"
                );

        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/auth/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
        // 目录必须先建出来再取 URI：目录不存在时 toUri() 不带结尾斜杠，
        // 资源映射会把 apk/ 当成文件而不是目录，下载一律 500。
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 建不出来就让下面的挂载失败，启动日志里能看到原因
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                // 明确不要缓存 404，便于排查「文件在不在」
                .setCacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .maxAge(3600);
    }
}
