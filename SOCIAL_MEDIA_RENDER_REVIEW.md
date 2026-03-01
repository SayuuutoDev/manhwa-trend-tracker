# Social Media Render Review (Image + Video)

## Executive summary
Current rendering is functional but not yet optimized for social-native performance (hook speed, motion rhythm, readability on small screens, and brand consistency). The output looks like an internal dashboard exported to media rather than content designed for TikTok/IG Reels/X feed behavior.

## What works today
- Correct vertical video canvas (`1080x1920`) for short-form platforms.
- Video includes motion and ranking reveal, which is better than static slides.
- Image includes clear ranking rows, cover art, and metric labels.
- Fallback behavior for missing covers is robust.

## Why it currently feels "not cool"

### 1) Weak first-second hook in video
- Video uses a 2-second intro window before ranking reveal starts (`INTRO_FRAMES = FPS * 2`).
- On social platforms, the first 0.3–1.0s usually decides retention.
- Recommendation: reduce intro to ~0.5–0.8s and put rank #1 teaser immediately in frame.

### 2) Duration is too long for repeatability
- Current video duration is 15 seconds.
- For daily ranking clips, 7–10s often performs better (more loops, higher completion).
- Recommendation: ship 2 presets: `FAST` (8s), `STANDARD` (12s).

### 3) Typography hierarchy is inconsistent with mobile viewing
- There are many text layers per entry (`label`, `metric`, `total`, `mode`) and several are too subtle at phone scroll speed.
- "Mode" text is useful for internal validation but low value for end users.
- Recommendation: simplify each card to 2 key lines max: title + one metric story.

### 4) Color/mood reads as generic dashboard
- Both image/video use blue gradients with glass cards and modest contrast accents.
- Social winners usually have a stronger visual signature (high-contrast accents, bold badges, cleaner spacing).
- Recommendation: define a brand kit: one primary gradient, one accent, one warning/highlight, and consistent badge style.

### 5) Motion language lacks punch moments
- Current reveal is smooth but mostly linear and utility-oriented.
- Recommendation: add:
  - beat-synced pop for rank changes,
  - stronger easing for #1 reveal,
  - subtle zoom pulse at peak frame,
  - optional audio-reactive timing later.

### 6) Information density in image is too high
- The image includes title, metric story, total value, mode, and full row framing for each entry.
- In feed contexts (X/IG), too much microtext reduces skim value.
- Recommendation: make image composition "poster first":
  - large headline,
  - 3–5 entries max,
  - bold delta metric,
  - minimal metadata in footer only.

### 7) Missing creator-friendly customization layer
- APIs already support title/subtitle/timestamp, but there is no theme preset system for platform-specific variants.
- Recommendation: add query presets:
  - `theme=neon|clean|dark`,
  - `format=tiktok|instagram|x`,
  - `pace=fast|standard`.

## Concrete rendering improvements (prioritized)

### P0 (high impact, low-to-medium effort)
1. Shorten intro and total video duration.
2. Remove low-value text fields from cards (`Mode:` line on image/video).
3. Increase headline contrast and rank badge prominence.
4. Limit visible entries to 3–5 for both image and video outputs.

### P1 (high impact, medium effort)
1. Add brand theme presets (palette + fonts + badge style).
2. Add motion preset profiles (`FAST` / `STANDARD`).
3. Add "hook templates" (e.g., "Biggest breakout today", "New #1").

### P2 (strategic)
1. A/B test render variants and track watch-through/post CTR.
2. Auto-generate per-platform exports with different safe-area spacing.
3. Add watermark/logo placement and @handle CTA module.

## Code-level observations (where to start)
- Video timing constants are currently hardcoded in `SocialRankingVideoService` (`FPS`, `DURATION_SECONDS`, `INTRO_FRAMES`, reveal windows).
- Video footer currently includes utility copy that could be replaced by CTA/handle.
- Image rows currently render `Mode:` and `Total:` line details that can be simplified for feed readability.

## Suggested acceptance criteria for a redesign
- First ranking element appears within first 0.8s.
- Final duration configurable (8s/12s presets).
- At least one theme preset with stronger contrast.
- Readability validated on a 6-inch mobile viewport at 50% brightness.
- Export variants for TikTok/Reels and X image cards.

## Final take
Your rendering system is technically strong and already close to production utility. To make it "social-media cool," prioritize hook speed, simplified storytelling per frame, and stronger visual identity over extra metric detail.
