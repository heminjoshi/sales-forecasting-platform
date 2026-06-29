package com.topsales.datagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * Loads {@link SeedConfig} from the committed {@code data/seed/seed-config.json}. The path is
 * resolved by walking up from the working directory (so it resolves whether the app is launched from
 * the repo root or a module dir), with an explicit property override.
 */
@Configuration
public class SeedConfigLoader {

    @Bean
    public SeedConfig seedConfig(
            @Value("${topsales.datagen.seed-config:data/seed/seed-config.json}") String configPath,
            ObjectMapper objectMapper)
            throws IOException {
        Path resolved = resolve(configPath);
        return objectMapper.readValue(Files.readString(resolved), SeedConfig.class);
    }

    /** Walk up from {@code user.dir} until {@code relativePath} exists; absolute paths pass through. */
    private static Path resolve(String relativePath) {
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path here = p.resolve(candidate);
            if (Files.exists(here)) {
                return here;
            }
        }
        throw new IllegalStateException(
                "seed config not found (searched up from " + dir + "): " + relativePath);
    }
}
