package com.manhwa.tracker.webtoons.social;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SocialRankingImageController {
    private final SocialRankingImageService imageService;

    public SocialRankingImageController(SocialRankingImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping(value = "/social-ranking.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> socialRanking(@ModelAttribute SocialRankingImageRequest request) {
        try {
            byte[] payload = imageService.createImage(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noCache())
                    .body(payload);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noCache())
                    .body(new byte[0]);
        }
    }
}
