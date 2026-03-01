package com.manhwa.tracker.webtoons.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Configuration
public class CoverStorageConfig implements WebMvcConfigurer {
    private final Path coverPath;

    public CoverStorageConfig(@Value("${app.cover-storage.path:cover-cache}") String storagePath) {
        this.coverPath = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(coverPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare cover storage directory " + coverPath, e);
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = coverPath.toUri().toString() + "/";
        registry.addResourceHandler("/covers/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
    }
}
