# Live streams (patched companion)

## Why a patch is needed

Upstream [invidious-companion](https://github.com/iv-org/invidious-companion)
does not support live streams ([issue #7](https://github.com/iv-org/invidious-companion/issues/7),
open as of this writing): live videos have no progressive `itag=18` stream and
the companion returns no `hlsManifestUrl`, so a live watch page paints the
thumbnail and stalls (companion log shows `latest_version?id=<live>&itag=18`
→ 400 loops).

The fix lives in [crutchcrew/invidious-companion](https://github.com/crutchcrew/invidious-companion)
(author: akellbl4, a contributor on the upstream issue; FreeTube-based
approach, intended as an upstream PR once #7 gets attention). Two commits:

| Commit | What |
|---|---|
| `c9b5656` | `add livestreaming ability` — live player responses are fetched from the `ANDROID_VR` innertube client (returns an HLS manifest + plain segment URLs that work without a PO token). `hlsManifestUrl` is rewritten to the companion's own `/api/manifest/hls/id/<id>` route, which re-issues the ~30 s-expiring segment URLs on every request and serves a 12-segment sliding live window. |
| `9ad284a` | `fix: drop AV1 video and Dolby ac-3/ec-3 audio formats from DASH manifest` — Chromium can't decode those through video.js/MSE and hard-fails with no fallback. |

With the patch, Invidious' own watch-page template embeds
`<source src="/companion/api/manifest/hls/id/<id>" type="application/x-mpegURL">`
and the stock video.js player (VHS) plays the stream — every client (APK,
PWA, desktop) gets live playback with no client changes.

## Building the image

```sh
git clone https://github.com/crutchcrew/invidious-companion.git companion-fork
cd companion-fork
git remote add upstream https://github.com/iv-org/invidious-companion.git
git fetch upstream master
git checkout -b live-patch upstream/master
git cherry-pick c9b5656 9ad284a
# podman only: fully-qualify the deno base image (no TTY for short-name prompts)
sed -i 's|FROM denoland/deno:bin-2.9.8|FROM docker.io/denoland/deno:bin-2.9.8|' Dockerfile
podman build -t invidious-companion:live .
```

The cherry-pick may conflict in `src/routes/videoPlaybackProxy.ts` when
upstream churns (it has flipped the playback body between `postResponse.body`
and `stream.responseReadable` more than once). Resolution: keep **upstream's
body expression** and add the fork's empty-header strip above it (live
segments have no `Content-Length`; clients reject an empty one).

## Deploying

In `compose/docker-compose.yml` (see the comment on the `companion:` service):

```yaml
  companion:
    image: localhost/invidious-companion:live
```

Then:

```sh
podman-compose up -d --force-recreate --no-deps companion
```

Allow ~1 min for the PO token to regenerate.

**Cache gotchas (both will silently keep the old no-HLS behavior):**

1. The companion caches player responses in its KV volume. After swapping the
   image, wipe it: `podman rm -f <project>_companion_1 && podman volume rm
   <project>_companioncache && podman-compose up -d --no-deps companion`.
2. Invidious caches video info in the `videos` table for 10 minutes. To force
   a refetch of one video: `delete from videos where id='<videoId>';` (psql
   inside the db container).

## Maintenance

- **Upstream merges HLS support (issue #7):** delete the patch, go back to
  `quay.io/invidious/invidious-companion:latest`, wipe the cache volume.
- **Refresh the patch before that:** `git fetch upstream && git rebase
  upstream/master live-patch`, resolve (expected conflict site above), rebuild,
  redeploy, wipe cache.

## Known gap

The Android APK's background-audio hand-off plays `latest_version?itag=18`,
which live streams don't have — so *background* audio still does not work for
live streams (page playback works fine; VOD background audio is unaffected).
