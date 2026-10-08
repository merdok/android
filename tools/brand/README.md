# Brand assets

The published [Panel Assistant brand set](https://panel-assistant.io/brand/) is the source for the app's artwork. `manifest.json` records each bundled source's URL, local path and SHA-256. The native wordmarks use the published transparent PNGs at a mark height of 256 pixels. The outlined name and Android vectors are generated from the published SVGs; the vector resource names and launcher safe zone stay the same.

Run `python3 tools/brand/sync.py sync` to refresh the sources and derivatives together. `python3 tools/brand/sync.py check` verifies every local hash, regenerated derivative and latest published hash; an unavailable publication fails this CI check. `check --offline` verifies the committed set without the freshness check. APK builds use only the committed artwork and do not require the website.

README and documentation embed the stable published URLs directly. Historical documentation CDN objects remain permanent.
