package com.manhwa.tracker.webtoons.service;

import java.text.Normalizer;

public final class TitleNormalizer {
    private TitleNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String value = input.trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        normalized = normalized.toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", " ").trim();
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }
}
