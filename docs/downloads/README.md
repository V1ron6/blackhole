# Downloads

Where built APKs go, and how the landing page links to them.

## Latest version (main download button)

```bash
cp app/build/outputs/apk/debug/app-debug.apk landing/downloads/blackhole-latest.apk
```

## Previous versions (the "Previous versions" list below the button)

Name each file with its version, e.g.:

```
landing/downloads/blackhole-v1.0.apk
landing/downloads/blackhole-v0.9.apk
```

Then in `landing/index.html`, find the `<ul id="versionList">` block and add one `<li>` per version:

```html
<li><a href="./downloads/blackhole-v1.0.apk" download>v1.0</a><span>— what changed in this version</span></li>
<li><a href="./downloads/blackhole-v0.9.apk" download>v0.9</a><span>— what changed in this version</span></li>
```

Newest first reads best. There's a placeholder `v1.0` entry in there now — replace it (or delete it if you don't have a previous version to link yet) rather than leaving it pointing at a file that doesn't exist.

## Cutting a new release

When you cut a new "latest," the old `blackhole-latest.apk` file's contents become a previous version — keep a copy under its own version-numbered name before overwriting `blackhole-latest.apk` with the new build.

