package com.manhwa.tracker.webtoons.social;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class SocialRankingVideoController {
    private final SocialRankingVideoService videoService;

    public SocialRankingVideoController(SocialRankingVideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping(value = "/social-ranking.mp4", produces = "video/mp4")
    public ResponseEntity<byte[]> socialRankingVideo(@ModelAttribute SocialRankingVideoRequest request) {
        try {
            byte[] payload = videoService.createVideo(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"social-ranking.mp4\"")
                    .cacheControl(CacheControl.noCache())
                    .body(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to render social ranking video", e);
        }
    }
}
